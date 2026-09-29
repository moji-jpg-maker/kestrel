package dev.narumi.kestrel.core.location

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_PAUSE
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_RESUME
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_SET_LOCATION
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_START
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_START_ROUTE
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_START_SCHEDULED
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_STOP
import dev.narumi.kestrel.core.location.LocationService.Companion.ACTION_UPDATE_ROUTE_SETTINGS
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_LAT
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_LATS
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_LNG
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_LNGS
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_MODE
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_PLAN_TOKEN
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_PLAYBACK_ID
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_REQUEST_ID
import dev.narumi.kestrel.core.location.LocationService.Companion.EXTRA_SPEED_KMH
import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.RouteTimeline
import java.util.UUID

/** Validates and dispatches service commands while the service owns playback state. */
internal class LocationServiceCommands(
    private val report: (LocationOperationResult) -> Unit,
    private val hasActiveMock: () -> Boolean,
) {
    fun start(context: Context) {
        sendIntent(context, ACTION_START, foreground = true)
    }

    fun setLocation(
        context: Context,
        point: LatLng,
    ): String =
        dispatchOperation(
            context = context,
            action = ACTION_SET_LOCATION,
            foreground = true,
        ) {
            putExtra(EXTRA_LAT, point.lat)
            putExtra(EXTRA_LNG, point.lng)
        }

    fun startRoute(
        context: Context,
        waypoints: List<LatLng>,
        speedKmh: Double,
        mode: MovementEngine.Mode = MovementEngine.Mode.Once,
    ): String {
        val requestError = validateRouteRequest(waypoints, speedKmh)
        val requestId = UUID.randomUUID().toString()
        if (requestError != null) {
            report(
                LocationOperationResult(
                    requestId = requestId,
                    action = LocationOperationAction.StartRoute,
                    succeeded = false,
                    message = requestError,
                ),
            )
            return requestId
        }
        val lats = DoubleArray(waypoints.size) { waypoints[it].lat }
        val lngs = DoubleArray(waypoints.size) { waypoints[it].lng }
        return dispatchOperation(
            context = context,
            action = ACTION_START_ROUTE,
            foreground = true,
            requestId = requestId,
        ) {
            putExtra(EXTRA_LATS, lats)
            putExtra(EXTRA_LNGS, lngs)
            putExtra(EXTRA_SPEED_KMH, speedKmh)
            putExtra(EXTRA_MODE, mode.name)
        }
    }

    /**
     * Arms [plan]: playback starts at `plan.startAtEpochMs` (immediately if that has passed) and
     * ends at the destination. The plan travels in-process rather than in the Intent because a
     * long route would exceed the Intent size limit.
     */
    fun startScheduled(
        context: Context,
        plan: PlaybackPlan,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val planError = runCatching { RouteTimeline(plan) }.exceptionOrNull()
        if (planError != null) {
            report(
                LocationOperationResult(
                    requestId = requestId,
                    action = LocationOperationAction.StartScheduled,
                    succeeded = false,
                    message = planError.message ?: "The route cannot be scheduled.",
                ),
            )
            return requestId
        }
        val token = ScheduledPlanHandoff.put(plan)
        return dispatchOperation(
            context = context,
            action = ACTION_START_SCHEDULED,
            foreground = true,
            requestId = requestId,
        ) {
            putExtra(EXTRA_PLAN_TOKEN, token)
        }
    }

    fun updateRouteSettings(
        context: Context,
        playbackId: String,
        speedKmh: Double? = null,
        mode: MovementEngine.Mode? = null,
    ): String =
        dispatchOperation(context, ACTION_UPDATE_ROUTE_SETTINGS, foreground = false) {
            putExtra(EXTRA_PLAYBACK_ID, playbackId)
            speedKmh?.let { putExtra(EXTRA_SPEED_KMH, it) }
            mode?.let { putExtra(EXTRA_MODE, it.name) }
        }

    fun pause(context: Context): String = dispatchOperation(context, ACTION_PAUSE, foreground = false)

    fun resume(context: Context): String = dispatchOperation(context, ACTION_RESUME, foreground = false)

    fun stop(context: Context): String = dispatchOperation(context, ACTION_STOP, foreground = false)

    private fun sendIntent(
        context: Context,
        action: String,
        foreground: Boolean,
    ) {
        val intent =
            Intent(context, LocationService::class.java).apply {
                this.action = action
            }
        startCompat(context, intent, foreground)
    }

    private fun dispatchOperation(
        context: Context,
        action: String,
        foreground: Boolean,
        requestId: String = UUID.randomUUID().toString(),
        configure: Intent.() -> Unit = {},
    ): String {
        val intent =
            Intent(context, LocationService::class.java).apply {
                this.action = action
                putExtra(EXTRA_REQUEST_ID, requestId)
                configure()
            }
        runCatching { startCompat(context, intent, foreground) }
            .onFailure { error ->
                if (action == ACTION_START_SCHEDULED) ScheduledPlanHandoff.take(intent.getStringExtra(EXTRA_PLAN_TOKEN))
                val operationAction = action.toLocationOperationAction() ?: return@onFailure
                report(
                    LocationOperationResult(
                        requestId = requestId,
                        action = operationAction,
                        succeeded = false,
                        message =
                            mockOperationErrorMessage(
                                error,
                                previousMockActive = hasActiveMock(),
                            ),
                    ),
                )
            }
        return requestId
    }

    private fun startCompat(
        context: Context,
        intent: Intent,
        foreground: Boolean,
    ) {
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
