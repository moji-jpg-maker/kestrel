package dev.narumi.kestrel.core.routeplan

/** Clock access split out so tests can drive time by hand. */
interface TimeSource {
    /** Monotonic milliseconds that keep counting through deep sleep (Android `elapsedRealtime`). */
    fun elapsedRealtimeMs(): Long

    /** Wall-clock milliseconds since the Unix epoch. */
    fun currentTimeMs(): Long
}

/**
 * Turns a wall-clock start time into elapsed playback seconds.
 *
 * The start is converted to a monotonic anchor once, at construction. After that only the
 * monotonic clock is read, so an NTP correction or the user changing the device clock cannot make
 * the device jump mid-route.
 *
 * [elapsedSeconds] is negative before the start and freezes while paused. To restore after the
 * service was killed, construct a new clock from the persisted [startAtEpochMs] and
 * [pausedTotalMs]; a pause that was still open when the process died is not recoverable and
 * counts as running.
 */
class PlaybackClock(
    private val time: TimeSource,
    val startAtEpochMs: Long,
    pausedTotalMs: Long = 0L,
) {
    private val anchorElapsedMs: Long = time.elapsedRealtimeMs() + (startAtEpochMs - time.currentTimeMs())
    private var pausedTotalMs: Long = pausedTotalMs
    private var pausedAtElapsedMs: Long? = null

    val isPaused: Boolean get() = pausedAtElapsedMs != null

    fun pausedTotalMs(): Long = pausedTotalMs + openPauseMs()

    fun elapsedSeconds(): Double {
        val now = pausedAtElapsedMs ?: time.elapsedRealtimeMs()
        return (now - anchorElapsedMs - pausedTotalMs) / MS_PER_SECOND
    }

    /** Milliseconds until the start; 0 once it has passed. */
    fun msUntilStart(): Long = (-(time.elapsedRealtimeMs() - anchorElapsedMs)).coerceAtLeast(0L)

    /** Pauses playback. Returns false (and does nothing) before the start or if already paused. */
    fun pause(): Boolean {
        if (isPaused || elapsedSeconds() < 0.0) return false
        pausedAtElapsedMs = time.elapsedRealtimeMs()
        return true
    }

    fun resume() {
        if (!isPaused) return
        pausedTotalMs += openPauseMs()
        pausedAtElapsedMs = null
    }

    private fun openPauseMs(): Long = pausedAtElapsedMs?.let { time.elapsedRealtimeMs() - it } ?: 0L

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
