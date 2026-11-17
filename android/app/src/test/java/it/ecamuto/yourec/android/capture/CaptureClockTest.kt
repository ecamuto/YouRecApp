package it.ecamuto.yourec.android.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureClockTest {
    @Test fun resumeWaitsForVideoAndUsesTheSameCutForAudio() {
        val clock = CaptureClock()
        assertNull(clock.videoTime(1_000_000, false))
        assertEquals(0L, clock.videoTime(1_100_000, true))
        clock.pause(1_600_000)
        clock.resume(3_000_000)
        assertNull(clock.videoTime(3_000_000, false))
        assertNull(clock.videoTime(1_700_000, true)) // delayed frame from the paused interval
        assertEquals(500_000L, clock.videoTime(3_200_000, true))
        assertEquals(listOf(CaptureClock.AudioSlice(240, 240, 500_000)),
            clock.audioSlices(3_195_000, 480, 48_000))
    }

    @Test fun audioBlockStraddlingPauseIsCutAtTheSampleBoundary() {
        val clock = CaptureClock()
        clock.videoTime(1_000_000, true)
        clock.pause(1_005_000)
        assertEquals(listOf(CaptureClock.AudioSlice(0, 240, 0)),
            clock.audioSlices(1_000_000, 480, 48_000))
        assertNull(clock.videoTime(1_005_000, true))
        assertTrue(clock.audioSlices(1_005_000, 480, 48_000).isEmpty())
    }

    @Test fun multiplePausesDoNotAccumulateAnEncoderWarmupGap() {
        val clock = CaptureClock()
        clock.videoTime(0, true)
        clock.pause(1_000_000)
        clock.resume(2_000_000)
        assertEquals(1_000_000L, clock.videoTime(2_200_000, true))
        clock.pause(3_200_000)
        clock.resume(4_000_000)
        assertEquals(2_000_000L, clock.videoTime(4_300_000, true))
        assertEquals(listOf(CaptureClock.AudioSlice(0, 480, 2_000_000)),
            clock.audioSlices(4_300_000, 480, 48_000))
    }

    @Test fun latePrePauseBuffersStillBelongToTheirOriginalWindow() {
        val clock = CaptureClock()
        clock.videoTime(0, true)
        clock.pause(1_000_000)
        clock.resume(2_000_000)
        clock.videoTime(2_100_000, true)
        assertEquals(900_000L, clock.videoTime(900_000, false))
        assertNull(clock.videoTime(1_500_000, false))
        clock.stop(2_500_000)
        assertNull(clock.videoTime(2_600_000, true))
    }

    @Test fun fractionalSampleCutsNeverIncludePausedPcm() {
        val clock = CaptureClock()
        clock.videoTime(1_000_003, true)
        clock.pause(1_005_003)
        // At 48 kHz the endpoints lie between PCM samples, not on millisecond ticks.
        val cut = clock.audioSlices(1_000_000, 480, 48_000).single()
        assertEquals(1, cut.firstFrame)
        assertEquals(240, cut.frames)
        assertEquals(17L, cut.timeUs)
        assertTrue(clock.audioSlices(1_005_004, 480, 48_000).isEmpty())
    }
}
