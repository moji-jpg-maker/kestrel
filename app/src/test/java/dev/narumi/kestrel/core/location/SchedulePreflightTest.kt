package dev.narumi.kestrel.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchedulePreflightTest {
    @Test
    fun passesWhenMockAndNotificationsAreAllowed() {
        assertNull(schedulePreflight(mockAllowed = { true }, notificationsEnabled = { true }))
    }

    @Test
    fun reportsMockNotAllowedFirstAndSkipsTheNotificationCheck() {
        var notificationsAsked = false
        val failure =
            schedulePreflight(
                mockAllowed = { false },
                notificationsEnabled = {
                    notificationsAsked = true
                    false
                },
            )
        assertEquals(SchedulePreflightFailure.MockNotAllowed, failure)
        assertEquals(false, notificationsAsked)
    }

    @Test
    fun reportsDisabledNotifications() {
        assertEquals(
            SchedulePreflightFailure.NotificationsDisabled,
            schedulePreflight(mockAllowed = { true }, notificationsEnabled = { false }),
        )
    }
}
