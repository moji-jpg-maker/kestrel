package dev.narumi.kestrel.core.location

import dev.narumi.kestrel.core.routeplan.ScheduledPlaybackRunner

/** Gates callbacks by session identity under the same lock as provider replacement. */
internal class ScheduledPlaybackCallbacks(
    private val lock: Any,
    private val isCurrent: () -> Boolean,
    private val moving: () -> Unit,
    private val arrived: (LatLng) -> Unit,
    private val failed: (Exception) -> Unit,
) : ScheduledPlaybackRunner.Listener {
    override fun onMoving() {
        synchronized(lock) {
            if (isCurrent()) moving()
        }
    }

    override fun onArrived(destination: LatLng) {
        synchronized(lock) {
            if (isCurrent()) arrived(destination)
        }
    }

    // The host serializes teardown on the service thread and rechecks identity there.
    override fun onFailed(error: Exception) {
        synchronized(lock) {
            if (isCurrent()) failed(error)
        }
    }
}
