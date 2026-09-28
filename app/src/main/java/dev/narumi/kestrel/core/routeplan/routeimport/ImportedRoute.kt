package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.TrackPoint

/** Settings a file can carry alongside its geometry, used to prefill the schedule form. */
data class PlanHints(
    val source: LatLng? = null,
    val startAtEpochMs: Long? = null,
    val speedKmh: Double? = null,
    val updateIntervalMs: Long? = null,
)

/**
 * A route read from a file. Timestamps, when present, cover every point (see [hasTimestamps]);
 * [warnings] lists things that were dropped or guessed so the UI can show them before starting.
 */
data class ImportedRoute(
    val name: String?,
    val points: List<TrackPoint>,
    val hints: PlanHints = PlanHints(),
    val warnings: List<String> = emptyList(),
) {
    val hasTimestamps: Boolean get() = points.isNotEmpty() && points.all { it.timeMs != null }
}

sealed interface ImportOutcome {
    data class Success(
        val route: ImportedRoute,
    ) : ImportOutcome

    data class Failure(
        val message: String,
    ) : ImportOutcome
}

/** A user-presentable reason why a file could not be imported. */
class RouteImportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
