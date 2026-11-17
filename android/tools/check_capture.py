#!/usr/bin/env python3
"""Read-only capture checks using ffprobe/ffmpeg and Python's standard library.

Example: python3 tools/check_capture.py take.mp4 --max-video-step-ms 60 \
    --max-audio-step-ms 23 --decode --tone-hz 700 --tone-hz 1700 --tone-hz 1100
Timing checks target the capture backend's AVC without B-frames and AAC 48 kHz.
They do not establish perceptual A/V sync or correct camera framing.
"""

import argparse
import array
import json
import math
from pathlib import Path
import subprocess
import sys


def run(*args):
    return subprocess.check_output(list(args))


def read_audio(path):
    track = json.loads(run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                           "stream=channels", "-of", "json", str(path)))["streams"][0]
    channels = int(track["channels"])
    pcm = array.array("f")
    pcm.frombytes(run("ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0",
                     "-ar", "16000", "-f", "f32le", "-"))
    if sys.byteorder != "little":
        pcm.byteswap()
    return pcm, channels


def tone_continuity(path, frequency):
    """Controlled steady-tone probe: 20 ms windows every 10 ms, each channel.

    Excludes 200 ms at each end for AAC priming/tail. It can detect short source
    dropouts even when the AAC packet timestamps themselves remain continuous.
    Do not apply to arbitrary speech or a tone that was deliberately stopped.
    """
    pcm, channels = read_audio(path)
    frames = len(pcm) // channels
    if frames < 9600:
        raise ValueError("Continuous-tone probe requires at least 0.6 seconds")
    coefficient = 2 * math.cos(2 * math.pi * frequency / 16000)
    results = []
    for channel in range(channels):
        minimum, minimum_at = 0.0, 0.0
        missing = []
        windows = 0
        for start in range(3200, frames - 3200 - 320 + 1, 160):
            a = b = 0.0
            for sample in pcm[start * channels + channel:(start + 320) * channels:channels]:
                a, b = sample + coefficient * a - b, a
            power = max(1e-20, (a * a + b * b - coefficient * a * b) / (320 * 320))
            db = 10 * math.log10(4 * power)
            windows += 1
            if db < minimum:
                minimum, minimum_at = db, start / 16000
            if db < -55:
                missing.append(start / 16000)
        results.append({"channel": channel, "windows": windows,
                        "minimumAmplitudeDbfs": minimum, "minimumAtSeconds": minimum_at,
                        "windowsBelowMinus55Dbfs": missing, "passed": not missing})
    return {"frequencyHz": frequency, "windowMs": 20, "hopMs": 10,
            "trimEachEndMs": 200, "channels": results,
            "passed": all(r["passed"] for r in results)}


def tone_analysis(path, frequencies):
    pcm, channels = read_audio(path)
    peaks = {str(f): {"sineAmplitudeDbfs": -200.0, "atSeconds": 0.0, "isolatedRuns": []} for f in frequencies}
    for channel in range(channels):
        # Test each microphone channel independently: a mono mix can cancel an out-of-phase tone.
        detections = {str(f): [] for f in frequencies}
        for start in range(0, len(pcm) // channels - 3200 + 1, 1600):
            window = pcm[start * channels + channel:(start + 3200) * channels:channels]
            for frequency in frequencies:
                powers = []
                for hz in (frequency, frequency - 20, frequency + 20, frequency - 40, frequency + 40):
                    coefficient = 2 * math.cos(2 * math.pi * hz / 16000)
                    a = b = 0.0
                    for sample in window:
                        a, b = sample + coefficient * a - b, a
                    powers.append(max(1e-20, (a * a + b * b - coefficient * a * b) / (3200 * 3200)))
                db = 10 * math.log10(4 * powers[0])
                if db > peaks[str(frequency)]["sineAmplitudeDbfs"]:
                    peaks[str(frequency)].update(sineAmplitudeDbfs=db, atSeconds=start / 16000, channel=channel)
                # A peak at one frequency can be speech/noise. Require a sustained narrow tone.
                isolation = 10 * math.log10(powers[0] / (sum(powers[1:]) / 4))
                if db > -65 and isolation > 12:
                    detections[str(frequency)].append(start / 16000)
        for frequency, times in detections.items():
            groups = []
            for time in times:
                if groups and time - groups[-1][-1] < 0.11:
                    groups[-1].append(time)
                else:
                    groups.append([time])
            peaks[frequency]["isolatedRuns"].extend(
                {"channel": channel, "startSeconds": g[0], "endSeconds": g[-1] + 0.2}
                for g in groups if len(g) >= 5
            )
    return peaks


def inspect(path, args):
    metadata = json.loads(run("ffprobe", "-v", "error", "-show_streams", "-show_format",
                              "-of", "json", str(path)))
    packet_data = json.loads(run("ffprobe", "-v", "error", "-show_packets", "-show_entries",
                                "packet=stream_index,pts_time,duration_time,flags", "-of", "json", str(path)))
    result = {"file": str(path), "streams": metadata["streams"], "timing": {}, "errors": []}
    for kind, threshold in [("video", args.max_video_step_ms), ("audio", args.max_audio_step_ms)]:
        tracks = [s for s in metadata["streams"] if s["codec_type"] == kind]
        if len(tracks) != 1:
            result["errors"].append(f"Expected one {kind} track, found {len(tracks)}")
            continue
        track = tracks[0]
        timestamps = [float(p["pts_time"]) for p in packet_data["packets"]
                      if p["stream_index"] == track["index"] and "pts_time" in p]
        if len(timestamps) < 2:
            result["errors"].append(f"Insufficient {kind} samples")
            continue
        steps = [b - a for a, b in zip(timestamps, timestamps[1:])]
        maximum = max(steps)
        result["timing"][kind] = {
            "packets": len(timestamps), "firstPtsSeconds": timestamps[0],
            "lastPtsSeconds": timestamps[-1], "strictlyIncreasing": all(s > 0 for s in steps),
            "maxStepMs": maximum * 1000, "maxStepAtSeconds": timestamps[steps.index(maximum)],
        }
        if kind == "video" and track.get("has_b_frames", 0):
            result["errors"].append("B-frame captures require a different presentation-order check")
        elif any(s <= 0 for s in steps):
            result["errors"].append(f"Non-increasing {kind} PTS")
        if threshold is not None and maximum * 1000 > threshold:
            result["errors"].append(f"{kind} step {maximum * 1000:.3f} ms exceeds {threshold:g} ms")
    if args.decode:
        decoded = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path),
                                  "-fps_mode:v", "passthrough", "-enc_time_base:v", "demux", "-f", "null", "-"],
                                 capture_output=True, text=True)
        result["decode"] = {"exitCode": decoded.returncode, "stderr": decoded.stderr.strip()}
        if decoded.returncode or decoded.stderr.strip():
            result["errors"].append("Decode verification reported an error")
    frequencies = list(dict.fromkeys(args.tone_hz + args.require_tone_hz + args.reject_tone_hz))
    if frequencies:
        result["toneAnalysis"] = tone_analysis(path, frequencies)
        for frequency in args.require_tone_hz:
            if not result["toneAnalysis"][str(frequency)]["isolatedRuns"]:
                result["errors"].append(f"Required tone {frequency:g} Hz not detected")
        for frequency in args.reject_tone_hz:
            if result["toneAnalysis"][str(frequency)]["isolatedRuns"]:
                result["errors"].append(f"Excluded tone {frequency:g} Hz detected")
    if args.continuous_tone_hz is not None:
        result["toneContinuity"] = tone_continuity(path, args.continuous_tone_hz)
        if not result["toneContinuity"]["passed"]:
            result["errors"].append("Continuous tone falls below -55 dBFS in one or more 20 ms windows")
    result["passed"] = not result["errors"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", type=Path, nargs="+")
    parser.add_argument("--max-video-step-ms", type=float)
    parser.add_argument("--max-audio-step-ms", type=float)
    parser.add_argument("--decode", action="store_true")
    parser.add_argument("--tone-hz", type=float, action="append", default=[])
    parser.add_argument("--require-tone-hz", type=float, action="append", default=[])
    parser.add_argument("--reject-tone-hz", type=float, action="append", default=[])
    parser.add_argument("--continuous-tone-hz", type=float,
                        help="Check a deliberately played steady tone for short dropouts on each channel")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    reports = []
    for path in args.files:
        try:
            report = inspect(path, args)
        except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
            report = {"file": str(path), "passed": False, "errors": [str(error)]}
        reports.append(report)
        print(f"{'PASS' if report['passed'] else 'FAIL'} {path.name}")
        for error in report["errors"]:
            print(f"  {error}")
        for kind, timing in report.get("timing", {}).items():
            print(f"  {kind}: {timing['packets']} samples, max step {timing['maxStepMs']:.3f} ms")
        if report.get("toneAnalysis"):
            print("  tones:", json.dumps(report["toneAnalysis"]))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(reports, indent=2) + "\n")
    return 0 if all(r["passed"] for r in reports) else 1


if __name__ == "__main__":
    sys.exit(main())
