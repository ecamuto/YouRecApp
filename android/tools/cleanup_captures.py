#!/usr/bin/env python3
"""Remove explicitly archived debug captures, never projects or whole directories.

Default is a dry run. The app must already be stopped. --apply first backs up
the entire private app container and verifies every regular file against the
device's SHA-256 inventory. After deletion it verifies all remaining files before
anyone launches the app. A failure leaves the backup and audit in --output.
"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import tarfile

PACKAGE = "it.ecamuto.yourec.android.prototype"
CAPTURE = re.compile(
    r"files/capture-prototype/(?:completed|pending)/"
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.mp4"
)
SHA256 = re.compile(r"[0-9a-f]{64}")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_manifest(path, serial):
    data = json.loads(path.read_text())
    if data.get("schema") != 1 or data.get("package") != PACKAGE or data.get("serial") != serial:
        raise ValueError("Manifest schema, package or device mismatch.")
    entries = data.get("files")
    if not isinstance(entries, list) or not entries:
        raise ValueError("An explicit nonempty list of archived test captures is required.")
    validated = {}
    for entry in entries:
        name = entry["path"]
        sha = entry["sha256"]
        if not isinstance(name, str) or not CAPTURE.fullmatch(name):
            raise ValueError(f"Not a capture in the debug namespace: {name!r}")
        if not isinstance(sha, str) or not SHA256.fullmatch(sha) or name in validated:
            raise ValueError(f"Invalid digest or duplicate path: {name}")
        archive = (path.parent / entry["archive"]).resolve()
        if not archive.is_file() or digest(archive) != sha:
            raise ValueError(f"Missing or mismatched local evidence: {name}")
        validated[name] = sha
    return validated


class Device:
    def __init__(self, adb, serial):
        self.command = [adb, "-s", serial]

    def run(self, *args, timeout=120):
        return subprocess.check_output(self.command + list(args), timeout=timeout)

    def stopped(self):
        result = subprocess.run(self.command + ["shell", "pidof", PACKAGE],
                                capture_output=True, timeout=15)
        if result.returncode != 1 or result.stdout.strip() or result.stderr.strip():
            raise RuntimeError("Stop the app from a verified idle state before cleanup.")

    def private(self, *args):
        # adb sends one remote shell string. Quote every argument rather than
        # trusting local subprocess argument boundaries on the remote side.
        command = shlex.join(["run-as", PACKAGE, *args])
        return self.run("exec-out", command)

    def inventory(self):
        paths = self.private("find", ".", "-type", "f").decode().splitlines()
        result = {}
        for start in range(0, len(paths), 50):
            raw = self.private("sha256sum", *paths[start:start + 50]).decode()
            for line in raw.splitlines():
                sha, name = line.split(None, 1)
                name = name.removeprefix("./")
                if not SHA256.fullmatch(sha) or name in result:
                    raise ValueError("Unexpected checksum output.")
                result[name] = sha
        return result

    def paths(self):
        return sorted(self.private("find", ".").decode().splitlines())

    def backup(self, archive):
        with archive.open("xb") as stream:
            subprocess.run(self.command + ["exec-out", shlex.join(["run-as", PACKAGE, "tar", "-cf", "-", "."])],
                           stdout=stream, check=True, timeout=600)


def verify_backup(archive, expected):
    actual = {}
    with tarfile.open(archive, "r|") as backup:
        for member in backup:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unsafe path in backup archive.")
            if member.isfile():
                name = str(path)
                if name in actual:
                    raise ValueError("Duplicate backup member.")
                with backup.extractfile(member) as stream:
                    actual[name] = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected:
        raise RuntimeError("Full backup does not match the device inventory. Nothing deleted.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New audit/backup directory")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    manifest = args.manifest.resolve()
    targets = read_manifest(manifest, args.serial)
    args.output.mkdir(parents=True, exist_ok=False)

    def record(name, data):
        (args.output / name).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")

    record("manifest.json", json.loads(manifest.read_text()))
    device = Device(args.adb, args.serial)
    device.stopped()
    before = device.inventory()
    before_paths = device.paths()
    record("inventory-before.json", before)
    record("paths-before.json", before_paths)
    if any(before.get(path) != sha for path, sha in targets.items()):
        raise RuntimeError("A target is missing or has changed. Nothing deleted.")
    plan = {"apply": args.apply, "serial": args.serial, "package": PACKAGE,
            "targets": targets, "preserved_files": len(before) - len(targets)}
    record("plan.json", plan)
    print(json.dumps({"apply": args.apply, "captures": len(targets),
                      "other_files_preserved": plan["preserved_files"]}), flush=True)
    if not args.apply:
        return

    archive = args.output / "app-private-before.tar"
    device.backup(archive)
    verify_backup(archive, before)
    record("backup-verified.json", {"sha256": digest(archive), "bytes": archive.stat().st_size,
                                    "verified_regular_files": len(before)})
    # Recheck immediately before mutation; never delete data from an active writer.
    device.stopped()
    if device.inventory() != before or device.paths() != before_paths:
        raise RuntimeError("App data changed during backup. Nothing deleted.")

    deleted = []
    try:
        for path in targets:
            # Exact files only. No globs, recursive flags, directory deletion or fallback.
            device.private("rm", "--", path)
            deleted.append(path)
    finally:
        # Record and compare BEFORE a launch or further diagnostics, also on error.
        after = device.inventory()
        after_paths = device.paths()
        record("deleted.json", deleted)
        record("inventory-after.json", after)
        record("paths-after.json", after_paths)
        expected = {p: h for p, h in before.items() if p not in deleted}
        expected_paths = [p for p in before_paths if p.removeprefix("./") not in deleted]
        unchanged = after == expected and after_paths == expected_paths
        record("verification.json", {"remaining_inventory_unchanged": unchanged,
                                     "deleted_count": len(deleted), "backup": str(archive.resolve())})
        if not unchanged:
            raise RuntimeError("Cleanup inventory mismatch. Inspect backup and audit before launching the app.")
    print(f"Removed {len(deleted)} archived test captures; all remaining app data verified.", flush=True)


if __name__ == "__main__":
    main()
