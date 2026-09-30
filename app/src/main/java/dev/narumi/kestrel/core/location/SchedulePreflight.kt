@file:Suppress("MatchingDeclarationName")

package dev.narumi.kestrel.core.location

internal enum class SchedulePreflightFailure {
    MockNotAllowed,
    NotificationsDisabled,
}

internal const val SCHEDULE_NOTIFICATIONS_DISABLED_MESSAGE =
    "Notifications are turned off for Kestrel. Turn them on so the scheduled route stays visible, then try again."

/**
 * Checks that must hold before a plan is armed, so a problem shows now rather than at the start time,
 * possibly hours later. The checks are lambdas so a failed first check skips the second.
 */
internal fun schedulePreflight(
    mockAllowed: () -> Boolean,
    notificationsEnabled: () -> Boolean,
): SchedulePreflightFailure? =
    when {
        !mockAllowed() -> SchedulePreflightFailure.MockNotAllowed
        !notificationsEnabled() -> SchedulePreflightFailure.NotificationsDisabled
        else -> null
    }
