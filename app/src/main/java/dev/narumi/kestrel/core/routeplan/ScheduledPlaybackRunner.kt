package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Runs a [PlaybackPlan]: waits for the start time, then samples [timeline] at the plan's update
 * interval until the destination is reached.
 *
 * Every sample is computed from [clock], never from a tick count, so late or skipped ticks only
 * change how often positions are written, not where the device is. While waiting, the source point
 * (if the plan has one) is written at the same interval so the location does not go stale; with no
 * source the real location is left alone until the start.
 *
 * [delayer] exists so tests can advance a fake clock instead of sleeping.
 */
class ScheduledPlaybackRunner(
    private val plan: PlaybackPlan,
    private val timeline: RouteTimeline,
    private val clock: PlaybackClock,
    private val sink: LocationSink,
    private val listener: Listener,
    private val delayer: suspend (Long) -> Unit = { delay(it) },
) {
    interface Listener {
        /** The start time was reached and the first moving sample is about to be written. */
        fun onMoving()

        /** The final sample (speed 0) has been written. Playback is over. */
        fun onArrived(destination: LatLng)

        /** Playback stopped early because the sink or a callback threw. */
        fun onFailed(error: Exception)
    }

    suspend fun run() {
        try {
            waitForStart()
            listener.onMoving()
            follow()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            listener.onFailed(e)
        }
    }

    private suspend fun waitForStart() {
        while (true) {
            coroutineContext.ensureActive()
            val remainingMs = clock.msUntilStart()
            if (remainingMs <= 0L) return
            if (plan.source != null) sink.push(timeline.positionAt(0.0))
            delayer(minOf(plan.updateIntervalMs, remainingMs))
            coroutineContext.ensureActive()
        }
    }

    private suspend fun follow() {
        while (true) {
            coroutineContext.ensureActive()
            val elapsed = clock.elapsedSeconds()
            val sample = timeline.positionAt(elapsed)
            if (elapsed >= timeline.durationSeconds) {
                sink.push(sample)
                listener.onArrived(sample.point)
                return
            }
            // While paused the device stands still but keeps reporting, so the fix does not go stale.
            sink.push(if (clock.isPaused) sample.copy(speedMps = 0.0) else sample)
            delayer(plan.updateIntervalMs)
        }
    }
}
