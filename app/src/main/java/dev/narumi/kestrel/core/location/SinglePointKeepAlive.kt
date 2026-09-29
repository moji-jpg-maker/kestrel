package dev.narumi.kestrel.core.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps a point fix fresh, including the destination of a completed scheduled route. */
internal class SinglePointKeepAlive(
    private val scope: CoroutineScope,
    private val push: (LatLng) -> Unit,
) {
    private var job: Job? = null

    fun start(point: LatLng) {
        stop()
        job =
            scope.launch {
                while (isActive) {
                    push(point)
                    delay(LOCATION_SERVICE_TICK_MILLIS)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
