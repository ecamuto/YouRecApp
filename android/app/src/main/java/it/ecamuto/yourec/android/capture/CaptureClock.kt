package it.ecamuto.yourec.android.capture

/** One source clock (boottime microseconds), shared by video and microphone samples.
 * Windows are half-open. Resume stays pending until a decodable video keyframe arrives.
 * This clock never squeezes the audio and video gaps independently.
 */
internal class CaptureClock {
    private data class Window(val start: Long, var end: Long?, val output: Long)
    data class AudioSlice(val firstFrame: Int, val frames: Int, val timeUs: Long)
    private val windows = mutableListOf<Window>()
    var awaitingKeyFrame = true
        private set
    private var requestedAt = Long.MIN_VALUE
    private var stopped = false

    fun videoTime(sourceUs: Long, keyFrame: Boolean): Long? {
        if (!stopped && awaitingKeyFrame && keyFrame && sourceUs >= requestedAt) {
            val previous = windows.lastOrNull()
            val output = previous?.let { it.output + checkNotNull(it.end) - it.start } ?: 0L
            windows += Window(sourceUs, null, output)
            awaitingKeyFrame = false
        }
        return windows.firstOrNull { sourceUs >= it.start && (it.end == null || sourceUs < it.end!!) }
            ?.let { it.output + sourceUs - it.start }
    }

    fun pause(sourceUs: Long) {
        windows.lastOrNull()?.takeIf { it.end == null }?.let { it.end = maxOf(it.start, sourceUs) }
        awaitingKeyFrame = false
    }

    fun resume(sourceUs: Long) {
        check(!stopped && windows.lastOrNull()?.end != null)
        requestedAt = sourceUs
        awaitingKeyFrame = true
    }

    fun stop(sourceUs: Long) {
        pause(sourceUs)
        stopped = true
    }

    fun audioSlices(firstSourceUs: Long, frames: Int, sampleRate: Int): List<AudioSlice> =
        windows.mapNotNull { window ->
            val first = frameAtOrAfter(window.start - firstSourceUs, sampleRate).coerceIn(0, frames.toLong()).toInt()
            val end = window.end?.let {
                frameAtOrAfter(it - firstSourceUs, sampleRate).coerceIn(0, frames.toLong()).toInt()
            } ?: frames
            if (end <= first) null else AudioSlice(first, end - first,
                window.output + firstSourceUs + first.toLong() * 1_000_000 / sampleRate - window.start)
        }

    private fun frameAtOrAfter(us: Long, rate: Int): Long =
        if (us >= 0) (us * rate + 999_999) / 1_000_000 else us * rate / 1_000_000
}
