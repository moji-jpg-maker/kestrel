package dev.narumi.kestrel.core.routeplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackClockTest {
    private class FakeTimeSource(
        var elapsedMs: Long = 1_000_000L,
        var wallMs: Long = 1_700_000_000_000L,
    ) : TimeSource {
        override fun elapsedRealtimeMs() = elapsedMs

        override fun currentTimeMs() = wallMs

        fun advance(ms: Long) {
            elapsedMs += ms
            wallMs += ms
        }
    }

    @Test
    fun elapsedIsNegativeBeforeStartAndCountsUpAfter() {
        val time = FakeTimeSource()
        val clock = PlaybackClock(time, startAtEpochMs = time.wallMs + 60_000L)

        assertEquals(-60.0, clock.elapsedSeconds(), 1e-9)
        assertEquals(60_000L, clock.msUntilStart())

        time.advance(60_000L)
        assertEquals(0.0, clock.elapsedSeconds(), 1e-9)
        assertEquals(0L, clock.msUntilStart())

        time.advance(5_500L)
        assertEquals(5.5, clock.elapsedSeconds(), 1e-9)
    }

    @Test
    fun startInThePastJoinsAtTheCurrentOffset() {
        val time = FakeTimeSource()

        val clock = PlaybackClock(time, startAtEpochMs = time.wallMs - 90_000L)

        assertEquals(90.0, clock.elapsedSeconds(), 1e-9)
    }

    @Test
    fun wallClockJumpsDoNotMovePlayback() {
        val time = FakeTimeSource()
        val clock = PlaybackClock(time, startAtEpochMs = time.wallMs)
        time.advance(10_000L)

        time.wallMs += 3_600_000L // NTP or the user changes the clock
        assertEquals(10.0, clock.elapsedSeconds(), 1e-9)

        time.elapsedMs += 2_000L
        assertEquals(12.0, clock.elapsedSeconds(), 1e-9)
    }

    @Test
    fun pauseFreezesAndResumeSkipsThePausedTime() {
        val time = FakeTimeSource()
        val clock = PlaybackClock(time, startAtEpochMs = time.wallMs)
        time.advance(10_000L)

        assertTrue(clock.pause())
        time.advance(30_000L)
        assertEquals(10.0, clock.elapsedSeconds(), 1e-9)
        assertTrue(clock.isPaused)

        clock.resume()
        assertFalse(clock.isPaused)
        assertEquals(10.0, clock.elapsedSeconds(), 1e-9)
        time.advance(5_000L)
        assertEquals(15.0, clock.elapsedSeconds(), 1e-9)
        assertEquals(30_000L, clock.pausedTotalMs())
    }

    @Test
    fun cannotPauseBeforeTheStartOrTwice() {
        val time = FakeTimeSource()
        val clock = PlaybackClock(time, startAtEpochMs = time.wallMs + 10_000L)

        assertFalse(clock.pause())

        time.advance(10_000L)
        assertTrue(clock.pause())
        assertFalse(clock.pause())
    }

    @Test
    fun restoredClockContinuesFromPersistedState() {
        val time = FakeTimeSource()
        val start = time.wallMs
        time.advance(100_000L)

        val restored = PlaybackClock(time, startAtEpochMs = start, pausedTotalMs = 20_000L)

        assertEquals(80.0, restored.elapsedSeconds(), 1e-9)
    }
}
