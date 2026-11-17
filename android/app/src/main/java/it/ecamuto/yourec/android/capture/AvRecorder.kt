package it.ecamuto.yourec.android.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Camera2 supplies video. AudioRecord owns the microphone independently of camera sessions.
 * All clock, codec and muxer state is confined to [handler]; only the blocking PCM read has
 * its own thread. Paused samples never reach the file. Encoders stay warm during pauses.
 */
@SuppressLint("MissingPermission") // Runtime permissions checked by CaptureController.start.
internal class AvRecorder(
    private val file: File,
    private val rotation: Int,
    private val handler: Handler,
    private val onPhase: (CapturePhase) -> Unit,
    private val onStats: (Long, Long) -> Unit,
    private val onStopping: (() -> Unit) -> Unit,
    private val onFinished: (String?) -> Unit,
) {
    private data class Pcm(val firstUs: Long, val bytes: ByteArray)
    private data class AudioInput(val bytes: ByteArray, val pts: Long)
    private data class Sample(val video: Boolean, val bytes: ByteArray, val pts: Long, val flags: Int)
    private val clock = CaptureClock()
    private lateinit var video: MediaCodec
    private lateinit var audio: MediaCodec
    private lateinit var muxer: MediaMuxer
    private val pendingSamples = ArrayDeque<Sample>()
    private var pendingSampleBytes = 0
    private val pendingPcm = ArrayDeque<Pcm>()
    private val audioInputs = ArrayDeque<AudioInput>()
    private val audioSlots = ArrayDeque<Int>()
    private val postedAudioBlocks = AtomicInteger()
    private var audioFrames = 0L
    private var audioOriginUs: Long? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var videoEnded = false
    private var audioEnded = false
    private var microphoneEnded = false
    private var audioEosQueued = false
    private var finished = false
    @Volatile private var stopping = false
    val isStopping: Boolean get() = stopping
    private var failure: String? = null
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var bytesWritten = 0L
    private var lastStatsAt = 0L
    private lateinit var microphone: AudioRecord
    lateinit var surface: Surface
        private set

    init {
        try {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Microfono stereo 48 kHz non disponibile su questo dispositivo." }
            microphone = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, RATE * BYTES_PER_FRAME / 2)).build()
            check(microphone.state == AudioRecord.STATE_INITIALIZED) { "Inizializzazione microfono fallita." }
            video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 17_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            video.setCallback(callback(true), handler)
            video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = video.createInputSurface()
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            audio.setCallback(callback(false), handler)
            audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer.setOrientationHint(rotation)
            audio.start()
            video.start()
        } catch (error: Exception) {
            // Codec callbacks may already be queued even when construction fails.
            finished = true
            stopping = true
            if (::microphone.isInitialized) runCatching { microphone.release() }
            if (::video.isInitialized) runCatching { video.release() }
            if (::audio.isInitialized) runCatching { audio.release() }
            if (::muxer.isInitialized) runCatching { muxer.release() }
            if (::surface.isInitialized) runCatching { surface.release() }
            throw error
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (configs.any { it.clientAudioSessionId == microphone.audioSessionId && it.isClientSilenced }) {
                abort("Il sistema ha silenziato il microfono. Ripresa interrotta e file provvisorio conservato.")
            }
        }
    }

    fun startMicrophone() {
        try {
            microphone.registerAudioRecordingCallback(Executor { handler.post(it) }, recordingCallback)
            microphone.startRecording()
        } catch (error: Exception) {
            runCatching { microphone.unregisterAudioRecordingCallback(recordingCallback) }
            microphone.release()
            microphoneEnded = true
            throw error
        }
        thread(name = "CapturePCM") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var readFrames = 0L
            var anchorUs: Long? = null
            val waiting = ArrayDeque<Pair<Long, ByteArray>>()
            val timestamp = AudioTimestamp()
            try {
                while (!stopping) {
                    val data = ByteArray(480 * BYTES_PER_FRAME)
                    val count = microphone.read(data, 0, data.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) {
                        if (!stopping) error("Lettura microfono fallita: $count")
                        break
                    }
                    check(count % BYTES_PER_FRAME == 0) { "PCM incompleto." }
                    waiting += readFrames to data.copyOf(count)
                    readFrames += count / BYTES_PER_FRAME
                    if (anchorUs == null && microphone.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        anchorUs = timestamp.nanoTime / 1000 - timestamp.framePosition * 1_000_000 / RATE
                        Log.i(TAG, "audio anchor=$anchorUs frame=${timestamp.framePosition} timestamp=${timestamp.nanoTime / 1000}")
                    }
                    val origin = anchorUs
                    if (origin != null) {
                        while (waiting.isNotEmpty()) {
                            val (frame, bytes) = waiting.removeFirst()
                            check(postedAudioBlocks.incrementAndGet() <= 200) { "Pipeline audio troppo lenta." }
                            handler.post {
                                postedAudioBlocks.decrementAndGet()
                                if (!finished) receivePcm(Pcm(origin + frame * 1_000_000 / RATE, bytes))
                            }
                        }
                    } else check(readFrames < RATE * 2L) { "Timestamp hardware del microfono non disponibile." }
                }
            } catch (error: Exception) {
                if (!stopping) handler.post { abort(error.message ?: "Acquisizione audio fallita.") }
            } finally {
                runCatching { microphone.unregisterAudioRecordingCallback(recordingCallback) }
                runCatching { microphone.stop() }
                microphone.release()
                handler.post { microphoneEnded = true; if (!finished) { drainPcm(); feedAudio() } }
            }
        }
        handler.postDelayed({ if (!finished && lastVideoPts < 0) abort("La camera non ha consegnato un fotogramma iniziale valido.") }, 5_000)
    }

    fun pause() {
        if (stopping) return
        clock.pause(nowUs())
        drainPcm()
        onPhase(CapturePhase.PAUSED)
        Log.i(TAG, "pause source=${nowUs()} video=$lastVideoPts audioFrames=$audioFrames")
    }

    fun resume() {
        if (stopping) return
        clock.resume(nowUs())
        onPhase(CapturePhase.RESUMING)
        video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        handler.postDelayed({
            if (!finished && !stopping && clock.awaitingKeyFrame) abort("Nessun fotogramma chiave dopo Riprendi.")
        }, 3_000)
    }

    fun stop() {
        if (stopping || finished) return
        stopping = true
        clock.stop(nowUs())
        onPhase(CapturePhase.STOPPING)
        runCatching { microphone.stop() }
        if (microphoneEnded) feedAudio()
        // Camera requests already in flight may still carry pre-stop timestamps.
        // Signal EOS only once their surfaces have received those frames.
        onStopping {
            if (!finished) runCatching { video.signalEndOfInputStream() }
                .onFailure { abort(it.message ?: "Chiusura video fallita.") }
        }
        handler.postDelayed({ if (!finished) finish("Timeout durante la chiusura della ripresa.") }, 5_000)
    }

    fun abort(reason: String) {
        if (finished) return
        if (failure == null) failure = reason
        if (!stopping) stop()
    }

    private fun receivePcm(block: Pcm) {
        pendingPcm += block
        if (pendingPcm.size > 200) { abort("Attesa del video troppo lunga per il buffer audio."); return }
        if (!clock.awaitingKeyFrame || stopping) drainPcm()
    }

    private fun drainPcm() {
        if (clock.awaitingKeyFrame && !stopping) return
        while (pendingPcm.isNotEmpty()) {
            val block = pendingPcm.removeFirst()
            for (slice in clock.audioSlices(block.firstUs, block.bytes.size / BYTES_PER_FRAME, RATE)) {
                val origin = audioOriginUs ?: slice.timeUs.also { audioOriginUs = it }
                val pts = origin + audioFrames * 1_000_000 / RATE
                // An actual missing PCM interval must fail visibly, not be hidden by retiming.
                if (kotlin.math.abs(pts - slice.timeUs) > 3_000) {
                    abort("Discontinuità PCM rilevata (${slice.timeUs - pts} µs). File provvisorio conservato.")
                    return
                }
                audioInputs += AudioInput(block.bytes.copyOfRange(slice.firstFrame * BYTES_PER_FRAME,
                    (slice.firstFrame + slice.frames) * BYTES_PER_FRAME), pts)
                audioFrames += slice.frames
                if (audioInputs.size > 100) { abort("Encoder audio troppo lento."); return }
            }
        }
        feedAudio()
    }

    private fun feedAudio() {
        while (audioSlots.isNotEmpty() && audioInputs.isNotEmpty()) {
            val slot = audioSlots.removeFirst()
            val input = audioInputs.removeFirst()
            val buffer = checkNotNull(audio.getInputBuffer(slot))
            check(buffer.capacity() >= input.bytes.size) { "Buffer encoder audio insufficiente." }
            buffer.clear(); buffer.put(input.bytes)
            audio.queueInputBuffer(slot, 0, input.bytes.size, input.pts, 0)
        }
        if (microphoneEnded && audioInputs.isEmpty() && audioSlots.isNotEmpty() && !audioEosQueued) {
            audioEosQueued = true
            val pts = (audioOriginUs ?: 0) + audioFrames * 1_000_000 / RATE
            audio.queueInputBuffer(audioSlots.removeFirst(), 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun callback(isVideo: Boolean) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!isVideo && !finished) guarded { audioSlots += index; feedAudio() }
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (finished) return
            guarded {
                check(!muxerStarted) { "Il formato del codec è cambiato durante la ripresa." }
                if (isVideo) videoTrack = muxer.addTrack(format) else audioTrack = muxer.addTrack(format)
                if (videoTrack >= 0 && audioTrack >= 0) {
                    muxer.start(); muxerStarted = true
                    while (pendingSamples.isNotEmpty()) write(pendingSamples.removeFirst())
                    pendingSampleBytes = 0
                }
            }
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (finished) return
            guarded {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val wasWaiting = clock.awaitingKeyFrame
                        if (isVideo && lastVideoPts < 0) check(kotlin.math.abs(info.presentationTimeUs - nowUs()) < 5_000_000) {
                            "Clock video non confrontabile con il microfono su questo dispositivo."
                        }
                        val pts = if (isVideo) clock.videoTime(info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) else info.presentationTimeUs
                        if (pts != null && pts >= 0) {
                            if (isVideo && wasWaiting && !clock.awaitingKeyFrame) {
                                Log.i(TAG, "video window source=${info.presentationTimeUs} output=$pts")
                                drainPcm(); onPhase(CapturePhase.RECORDING)
                            }
                            val buffer = checkNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            val sample = Sample(isVideo, bytes, pts, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            if (muxerStarted) write(sample) else {
                                pendingSamples += sample; pendingSampleBytes += bytes.size
                                check(pendingSampleBytes <= 8 * 1024 * 1024) { "Attesa della traccia audio troppo lunga." }
                            }
                        }
                    }
                } finally { codec.releaseOutputBuffer(index, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (isVideo) videoEnded = true else audioEnded = true
                    if (videoEnded && audioEnded) finish(failure)
                }
            }
        }
        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { abort(error.diagnosticInfo) }
    }

    private fun write(sample: Sample) {
        val previous = if (sample.video) lastVideoPts else lastAudioPts
        check(sample.pts > previous) { "Timestamp ${if (sample.video) "video" else "audio"} non crescente." }
        muxer.writeSampleData(if (sample.video) videoTrack else audioTrack, ByteBuffer.wrap(sample.bytes),
            MediaCodec.BufferInfo().apply { set(0, sample.bytes.size, sample.pts, sample.flags) })
        if (sample.video) lastVideoPts = sample.pts else lastAudioPts = sample.pts
        bytesWritten += sample.bytes.size
        if (nowUs() - lastStatsAt >= 250_000) {
            lastStatsAt = nowUs()
            onStats(maxOf(lastVideoPts, lastAudioPts, 0) * 1000, bytesWritten)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (error: Exception) { abort(error.message ?: "Errore nella pipeline multimediale.") }
    }

    private fun finish(error: String?) {
        if (finished) return
        finished = true; stopping = true
        var result = failure ?: error
        if (lastVideoPts < 0 || lastAudioPts < 0) result = result ?: "Ripresa priva di campioni audio/video validi."
        runCatching { microphone.stop() }
        runCatching { video.stop() }; runCatching { audio.stop() }
        runCatching { video.release() }.onFailure { result = result ?: it.message }
        runCatching { audio.release() }.onFailure { result = result ?: it.message }
        runCatching { surface.release() }.onFailure { result = result ?: it.message }
        if (muxerStarted) runCatching { muxer.stop() }.onFailure { result = result ?: it.message }
        runCatching { muxer.release() }.onFailure { result = result ?: it.message }
        onFinished(result)
    }

    companion object {
        private const val TAG = "AvRecorder"
        private const val RATE = 48_000
        private const val BYTES_PER_FRAME = 4 // Stereo PCM16.
        private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
    }
}
