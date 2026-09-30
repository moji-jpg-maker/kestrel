package dev.narumi.kestrel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPresentationRulesTest {
    @Test
    fun setupStep_requestsPermissionsBeforeMockAppSelection() {
        assertEquals(
            MapSetupStep.Permissions,
            mapSetupStep(permissionsGranted = false, mockAllowed = false),
        )
        assertEquals(
            MapSetupStep.Permissions,
            mapSetupStep(permissionsGranted = false, mockAllowed = true),
        )
    }

    @Test
    fun setupStep_requestsMockAppAfterPermissions() {
        assertEquals(
            MapSetupStep.MockLocationApp,
            mapSetupStep(permissionsGranted = true, mockAllowed = false),
        )
    }

    @Test
    fun setupStep_isReadyAfterBothRequirements() {
        assertEquals(
            MapSetupStep.Ready,
            mapSetupStep(permissionsGranted = true, mockAllowed = true),
        )
    }

    @Test
    fun liveRouteSettings_areVisibleForPlayingAndPausedRoutes() {
        assertTrue(shouldShowLiveRouteSettings(RunState.RoutePlaying))
        assertTrue(shouldShowLiveRouteSettings(RunState.RoutePaused))
        assertFalse(shouldShowLiveRouteSettings(RunState.Idle))
        assertFalse(shouldShowLiveRouteSettings(RunState.Single))
    }

    @Test
    fun speedChoices_includeTheActualRuntimeSpeedWithoutDuplicatingPresets() {
        assertEquals(listOf(5.0, 10.0, 15.0, 20.0), routeSpeedChoices(10.0))
        assertEquals(listOf(5.0, 10.0, 12.0, 15.0, 20.0), routeSpeedChoices(12.0))
    }

    @Test
    fun routeSettings_areVisibleOnlyForEditableMultiPointDraft() {
        assertFalse(shouldShowRouteSettings(RunState.Idle, waypointCount = 0))
        assertFalse(shouldShowRouteSettings(RunState.Idle, waypointCount = 1))
        assertTrue(shouldShowRouteSettings(RunState.Idle, waypointCount = 2))
        assertTrue(shouldShowRouteSettings(RunState.Single, waypointCount = 2))
        assertFalse(shouldShowRouteSettings(RunState.RoutePlaying, waypointCount = 2))
        assertFalse(shouldShowRouteSettings(RunState.RoutePaused, waypointCount = 2))
        assertTrue(
            shouldShowRouteSettings(
                RunState.RoutePlaying,
                waypointCount = 2,
                hasReplacementPreview = true,
            ),
        )
    }

    @Test
    fun setupPrompt_hasTextForEveryStepThatBlocksAndNoneWhenReady() {
        assertEquals(null, setupPromptTitle(MapSetupStep.Ready))
        assertEquals(null, setupPromptMessage(MapSetupStep.Ready))
        assertTrue(setupPromptTitle(MapSetupStep.Permissions).orEmpty().isNotBlank())
        assertTrue(setupPromptMessage(MapSetupStep.Permissions).orEmpty().contains("location"))
        assertTrue(setupPromptMessage(MapSetupStep.MockLocationApp).orEmpty().contains("developer options"))
    }

    @Test
    fun scheduleSetupError_prioritizesDisabledNotifications() {
        val message =
            scheduleSetupError(
                setupStep = MapSetupStep.Permissions,
                notificationPermissionGranted = false,
            )

        assertTrue(message.orEmpty().contains("Notifications are turned off"))
    }

    @Test
    fun scheduleSetupError_explainsMockSelectionAndAllowsReadyState() {
        assertTrue(
            scheduleSetupError(
                setupStep = MapSetupStep.MockLocationApp,
                notificationPermissionGranted = true,
            ).orEmpty().contains("Developer options"),
        )
        assertEquals(
            null,
            scheduleSetupError(
                setupStep = MapSetupStep.Ready,
                notificationPermissionGranted = true,
            ),
        )
    }
}
