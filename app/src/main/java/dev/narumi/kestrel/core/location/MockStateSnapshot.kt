package dev.narumi.kestrel.core.location

import dev.narumi.kestrel.core.data.MockState
import dev.narumi.kestrel.core.data.SinglePointState
import kotlinx.coroutines.CancellationException

/** Called under the provider lock to keep runtime and persisted playback snapshots coherent. */
internal fun mockStateSnapshot(
    runtime: RuntimeState,
    activeRoute: ActiveRouteSnapshot?,
    activeScheduled: ActiveScheduled?,
): MockState? =
    when (runtime) {
        RuntimeState.Idle -> null
        is RuntimeState.Single ->
            MockState(
                mode = MockState.Mode.Single,
                single = SinglePointState(runtime.point.lat, runtime.point.lng),
            )
        is RuntimeState.Route ->
            MockState(
                mode = MockState.Mode.Route,
                route =
                    activeRoute?.toRouteState()
                        ?: throw CancellationException("Route snapshot is no longer available."),
            )
        is RuntimeState.Scheduled ->
            activeScheduled?.toMockState()
                ?: throw CancellationException("Schedule snapshot is no longer available.")
    }
