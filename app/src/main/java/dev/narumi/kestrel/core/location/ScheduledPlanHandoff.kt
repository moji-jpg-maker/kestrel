package dev.narumi.kestrel.core.location

import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import java.util.UUID

/**
 * Passes a plan from the UI to [LocationService] inside the same process. A long route does not fit
 * in an Intent extra (a binder transaction is limited to about 1 MB), so the Intent carries only a
 * token. Only the most recent plan is kept; a token that no longer matches yields null.
 */
internal object ScheduledPlanHandoff {
    private class Pending(
        val token: String,
        val plan: PlaybackPlan,
    )

    @Volatile
    private var pending: Pending? = null

    @Synchronized
    fun put(plan: PlaybackPlan): String {
        val token = UUID.randomUUID().toString()
        pending = Pending(token, plan)
        return token
    }

    @Synchronized
    fun take(token: String?): PlaybackPlan? {
        val current = pending ?: return null
        if (token == null || current.token != token) return null
        pending = null
        return current.plan
    }
}
