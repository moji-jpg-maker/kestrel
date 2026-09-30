package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.routeimport.ImportedRoute
import dev.narumi.kestrel.core.routeplan.routeimport.PlanHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDraftTest {
    private val now = 1_700_000_000_000L
    private val a = LatLng(52.0, 4.0)
    private val b = LatLng(52.01, 4.0)

    private fun plain(hints: PlanHints = PlanHints()) = ImportedRoute("Loop", listOf(TrackPoint(a), TrackPoint(b)), hints)

    private fun timed(hints: PlanHints = PlanHints()) = ImportedRoute("Ride", listOf(TrackPoint(a, 0L), TrackPoint(b, 120_000L)), hints)

    private fun ready(result: PlanBuildResult) = (result as PlanBuildResult.Ready).plan

    private fun invalid(result: PlanBuildResult) = (result as PlanBuildResult.Invalid).message

    @Test
    fun importPrefillsFromHintsWhenNothingWasTouched() {
        val hints = PlanHints(source = a, startAtEpochMs = now + 5_000, speedKmh = 30.0, updateIntervalMs = 500)
        val draft = ScheduleDraft().withImportedRoute(plain(hints))
        assertEquals(a, draft.source)
        assertEquals(now + 5_000, draft.startAtEpochMs)
        assertEquals(30.0, draft.speedKmh, 0.0)
        assertEquals(500L, draft.updateIntervalMs)
    }

    @Test
    fun importDoesNotOverwriteValuesTheUserAdjusted() {
        val draft =
            ScheduleDraft()
                .withSpeedKmh(45.0)
                .withUpdateIntervalMs(2_000)
                .withStart(now + 60_000)
                .withImportedRoute(plain(PlanHints(speedKmh = 10.0, updateIntervalMs = 300, startAtEpochMs = now + 1)))
        assertEquals(45.0, draft.speedKmh, 0.0)
        assertEquals(2_000L, draft.updateIntervalMs)
        assertEquals(now + 60_000, draft.startAtEpochMs)
    }

    @Test
    fun aPastStartHintIsIgnoredWhenTheClockIsKnown() {
        val hints = PlanHints(startAtEpochMs = now - 86_400_000L)
        assertNull(ScheduleDraft().withImportedRoute(plain(hints), nowMs = now).startAtEpochMs)
        assertEquals(now + 5_000, ScheduleDraft().withImportedRoute(plain(PlanHints(startAtEpochMs = now + 5_000)), nowMs = now).startAtEpochMs)
    }

    @Test
    fun invalidHintsAreIgnored() {
        val draft = ScheduleDraft().withImportedRoute(plain(PlanHints(speedKmh = -3.0, updateIntervalMs = 5)))
        assertEquals(DEFAULT_SCHEDULE_SPEED_KMH, draft.speedKmh, 0.0)
        assertEquals(DEFAULT_UPDATE_INTERVAL_MS, draft.updateIntervalMs)
    }

    @Test
    fun timestampedRoutesDefaultToReplayAndPlainOnesToConstant() {
        assertEquals(SpeedMode.FromTimestamps, ScheduleDraft().withImportedRoute(timed()).speedMode)
        assertEquals(SpeedMode.Constant, ScheduleDraft().withImportedRoute(plain()).speedMode)
    }

    @Test
    fun aChosenReplayModeFallsBackWhenTheNewRouteHasNoTimestamps() {
        val draft = ScheduleDraft().withImportedRoute(timed()).withSpeedMode(SpeedMode.TimestampsScaled)
        assertEquals(SpeedMode.Constant, draft.withImportedRoute(plain()).speedMode)
        assertEquals(SpeedMode.TimestampsScaled, draft.withImportedRoute(timed()).speedMode)
    }

    @Test
    fun aChosenConstantModeSurvivesImportingATimestampedRoute() {
        val draft = ScheduleDraft().withSpeedMode(SpeedMode.Constant).withImportedRoute(timed())
        assertEquals(SpeedMode.Constant, draft.speedMode)
    }

    @Test
    fun buildRequiresARoute() {
        assertEquals("Import a route first.", invalid(buildPlaybackPlan(ScheduleDraft(), now)))
    }

    @Test
    fun startNowUsesTheClock() {
        val plan = ready(buildPlaybackPlan(ScheduleDraft().withImportedRoute(plain()), now))
        assertEquals(now, plan.startAtEpochMs)
        assertEquals(SpeedSource.Constant(DEFAULT_SCHEDULE_SPEED_KMH), plan.speed)
        assertNull(plan.leadInSpeedKmh)
        assertEquals("Loop", plan.name)
    }

    @Test
    fun aFutureStartIsKept() {
        val draft = ScheduleDraft().withImportedRoute(plain()).withStart(now + 600_000)
        assertEquals(now + 600_000, ready(buildPlaybackPlan(draft, now)).startAtEpochMs)
    }

    @Test
    fun aStartWellInThePastIsRejectedButSlightlyLateIsFine() {
        val base = ScheduleDraft().withImportedRoute(plain())
        assertEquals("The start time is in the past.", invalid(buildPlaybackPlan(base.withStart(now - 120_000), now)))
        assertEquals(now - 30_000, ready(buildPlaybackPlan(base.withStart(now - 30_000), now)).startAtEpochMs)
    }

    @Test
    fun badSpeedAndIntervalAreRejected() {
        val base = ScheduleDraft().withImportedRoute(plain())
        assertTrue(invalid(buildPlaybackPlan(base.withSpeedKmh(0.0), now)).contains("Speed"))
        assertTrue(invalid(buildPlaybackPlan(base.withSpeedKmh(Double.NaN), now)).contains("Speed"))
        assertTrue(invalid(buildPlaybackPlan(base.withUpdateIntervalMs(50), now)).contains("interval"))
        assertTrue(invalid(buildPlaybackPlan(base.withUpdateIntervalMs(60_000), now)).contains("interval"))
    }

    @Test
    fun replayNeedsTimestamps() {
        val draft = ScheduleDraft().withImportedRoute(plain()).withSpeedMode(SpeedMode.FromTimestamps)
        assertEquals("This route has no timestamps to replay.", invalid(buildPlaybackPlan(draft, now)))
    }

    @Test
    fun scaledReplayCarriesTheFactorAndALeadInSpeed() {
        val draft =
            ScheduleDraft()
                .withImportedRoute(timed())
                .withSpeedMode(SpeedMode.TimestampsScaled)
                .withTimestampFactor(2.0)
                .withLeadInSpeedKmh(15.0)
                .withSource(LatLng(52.0, 3.99))
        val plan = ready(buildPlaybackPlan(draft, now))
        assertEquals(SpeedSource.TimestampsScaled(2.0), plan.speed)
        assertEquals(15.0, plan.leadInSpeedKmh!!, 0.0)
        assertEquals(LatLng(52.0, 3.99), plan.source)
    }

    @Test
    fun badScaleFactorIsRejected() {
        val draft = ScheduleDraft().withImportedRoute(timed()).withSpeedMode(SpeedMode.TimestampsScaled).withTimestampFactor(0.0)
        assertTrue(invalid(buildPlaybackPlan(draft, now)).contains("factor"))
    }

    @Test
    fun aBuiltPlanAlwaysHasATimeline() {
        val draft = ScheduleDraft().withImportedRoute(timed()).withSource(LatLng(52.0, 3.99))
        RouteTimeline(ready(buildPlaybackPlan(draft, now)))
    }

    @Test
    fun joiningAPastStartKeepsTheOriginalStartSoTheRouteIsAlreadyUnderway() {
        val past = now - 120_000
        val base = ScheduleDraft().withImportedRoute(plain()).withStart(past)
        assertTrue(base.startIsPast(now))
        assertEquals("The start time is in the past.", invalid(buildPlaybackPlan(base, now)))
        assertEquals(past, ready(buildPlaybackPlan(base.withJoinInProgress(), now)).startAtEpochMs)
    }

    @Test
    fun changingTheStartWithdrawsAJoin() {
        val joined = ScheduleDraft().withImportedRoute(plain()).withStart(now - 120_000).withJoinInProgress()
        assertTrue(joined.joinInProgress)
        assertEquals(false, joined.withStart(now + 60_000).joinInProgress)
        assertEquals(false, joined.withStart(null).joinInProgress)
    }

    @Test
    fun joiningARouteThatAlreadyFinishedIsRejected() {
        val draft = ScheduleDraft().withImportedRoute(plain()).withStart(now - 3_600_000).withJoinInProgress()
        assertTrue(invalid(buildPlaybackPlan(draft, now)).contains("already be finished"))
    }

    @Test
    fun aBigStepPerUpdateIsFlaggedButASmallOneIsNot() {
        val base = ScheduleDraft().withImportedRoute(plain())
        val fast = buildPlaybackPlan(base.withSpeedKmh(120.0).withUpdateIntervalMs(2_000), now) as PlanBuildResult.Ready
        assertEquals(120.0 / 3.6 * 2.0, fast.largeStepMeters!!, 0.001)
        assertNull((buildPlaybackPlan(base.withSpeedKmh(20.0).withUpdateIntervalMs(1_000), now) as PlanBuildResult.Ready).largeStepMeters)
        assertNull((buildPlaybackPlan(base.withSpeedKmh(100.0).withUpdateIntervalMs(1_000), now) as PlanBuildResult.Ready).largeStepMeters)
    }

    @Test
    fun editingTheFactorOrLeadInSpeedDoesNotBlockTheSpeedHint() {
        val draft =
            ScheduleDraft()
                .withTimestampFactor(2.0)
                .withLeadInSpeedKmh(15.0)
                .withImportedRoute(plain(PlanHints(speedKmh = 30.0)))
        assertEquals(30.0, draft.speedKmh, 0.0)
    }

    @Test
    fun aReadyPlanCarriesItsTimeline() {
        val result = buildPlaybackPlan(ScheduleDraft().withImportedRoute(plain()), now) as PlanBuildResult.Ready
        assertTrue(result.timeline.durationSeconds > 0.0)
    }
}
