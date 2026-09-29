package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.MockSample
import dev.narumi.kestrel.core.location.haversineMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Drives [ScheduledPlaybackRunner.run] in virtual time: the test's own `delayer` advances a fake
 * clock and returns immediately instead of suspending, so a route that spans hours "runs" in
 * milliseconds and every tick is deterministic. This checks the runner's own scheduling logic
 * (start gating, sample cadence, pause and cancellation handling); [RouteTimelineTest] already
 * covers position and speed at a given elapsed time.
 */
class ScheduledPlaybackRunnerTest {
    private class FakeTime(
        var elapsedMs: Long = 0L,
        var wallMs: Long = 1_700_000_000_000L,
    ) : TimeSource {
        override fun elapsedRealtimeMs() = elapsedMs

        override fun currentTimeMs() = wallMs
    }

    private class RecordingSink : LocationSink {
        val samples = ArrayList<MockSample>()
        var failAfter = Int.MAX_VALUE

        override fun push(sample: MockSample) {
            if (samples.size >= failAfter) throw IllegalStateException("sink refused the update")
            samples += sample
        }
    }

    private class RecordingListener : ScheduledPlaybackRunner.Listener {
        var movingCount = 0
        var arrivedAt: LatLng? = null
        var failure: Exception? = null

        override fun onMoving() {
            movingCount++
        }

        override fun onArrived(destination: LatLng) {
            arrivedAt = destination
        }

        override fun onFailed(error: Exception) {
            failure = error
        }
    }

    private val a = LatLng(0.0, 0.0)
    private val b = LatLng(0.0, 0.01)
    private val routeMeters = haversineMeters(a, b)

    /** Advances [time] by the requested amount instead of really waiting. */
    private fun virtualDelayer(time: FakeTime): suspend (Long) -> Unit =
        {
            time.elapsedMs += it
            time.wallMs += it
        }

    /** Runs a suspend function to completion synchronously; fails the test if it does not. */
    private fun runToCompletion(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> Unit,
    ) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = context

                override fun resumeWith(result: Result<Unit>) {
                    outcome = result
                }
            },
        )
        val result = checkNotNull(outcome) { "the runner suspended on something this test does not simulate" }
        result.getOrThrow()
    }

    private fun plan(
        speed: SpeedSource = SpeedSource.Constant(kmh = routeMeters / 10.0 * 3.6),
        startAtEpochMs: Long = 0L,
        updateIntervalMs: Long = 1_000L,
        source: LatLng? = null,
    ) = PlaybackPlan(
        source = source,
        points = listOf(TrackPoint(a), TrackPoint(b)),
        startAtEpochMs = startAtEpochMs,
        speed = speed,
        updateIntervalMs = updateIntervalMs,
    )

    @Test
    fun playsAConstantSpeedRouteEndToEnd() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs + 5_000L)
        val clock = PlaybackClock(time, builtPlan.startAtEpochMs)
        val sink = RecordingSink()
        val listener = RecordingListener()
        val runner =
            ScheduledPlaybackRunner(builtPlan, RouteTimeline(builtPlan), clock, sink, listener, virtualDelayer(time))

        runToCompletion { runner.run() }

        assertEquals(1, listener.movingCount)
        assertEquals(b, listener.arrivedAt)
        assertNull(listener.failure)
        assertTrue("expected several samples, got ${sink.samples.size}", sink.samples.size >= 9)
        // 10 s of travel at 1 s intervals: about one sample per second, none before the plan starts.
        assertEquals(10.0, sink.samples.size.toDouble(), 2.0)
        assertEquals(0.0, sink.samples.last().speedMps, 1e-6)
        assertEquals(
            b.lng,
            sink.samples
                .last()
                .point.lng,
            1e-9,
        )
    }

    @Test
    fun heldSourceIsPushedWhileWaitingButNotAfterMoving() {
        val time = FakeTime()
        val source = LatLng(0.0, -0.001)
        val builtPlan = plan(startAtEpochMs = time.wallMs + 3_000L, source = source, updateIntervalMs = 1_000L)
        val clock = PlaybackClock(time, builtPlan.startAtEpochMs)
        val sink = RecordingSink()
        val listener = RecordingListener()
        val runner =
            ScheduledPlaybackRunner(builtPlan, RouteTimeline(builtPlan), clock, sink, listener, virtualDelayer(time))

        runToCompletion { runner.run() }

        // The wait loop pushes the held source three times (at 3s, 2s and 1s remaining); the very
        // next push, at elapsed == 0, is `follow()`'s own hold-at-source sample -- so four pushes
        // read as the source before progress is visible. Real movement must show up after that.
        val beforeMoving = sink.samples.take(4)
        assertTrue(beforeMoving.all { it.point == source && it.speedMps == 0.0 })
        assertTrue(sink.samples.drop(4).none { it.point == source })
        assertTrue(sink.samples.last().point == b)
    }

    @Test
    fun withNoSourceOnlyRouteSamplesAreEverPushed() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs + 3_000L, source = null)
        val clock = PlaybackClock(time, builtPlan.startAtEpochMs)
        val sink = RecordingSink()
        val runner =
            ScheduledPlaybackRunner(builtPlan, RouteTimeline(builtPlan), clock, sink, RecordingListener(), virtualDelayer(time))

        runToCompletion { runner.run() }

        // With no source, waitForStart never calls the sink, so only follow()'s ~10 route-duration
        // samples are pushed -- not the 3 extra ticks spent waiting.
        assertEquals(10.0, sink.samples.size.toDouble(), 2.0)
        assertEquals(
            a.lng,
            sink.samples
                .first()
                .point.lng,
            1e-9,
        )
        assertEquals(
            b.lng,
            sink.samples
                .last()
                .point.lng,
            1e-9,
        )
    }

    @Test
    fun aStartInThePastBeginsMovingImmediately() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs - 60_000L)
        val clock = PlaybackClock(time, builtPlan.startAtEpochMs)
        val sink = RecordingSink()
        val listener = RecordingListener()
        val runner =
            ScheduledPlaybackRunner(builtPlan, RouteTimeline(builtPlan), clock, sink, listener, virtualDelayer(time))

        runToCompletion { runner.run() }

        assertEquals(1, listener.movingCount)
        assertEquals(b, listener.arrivedAt)
    }

    @Test
    fun aPausedClockReportsZeroSpeedWithoutStoppingUpdates() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs, updateIntervalMs = 1_000L)
        val clock = PlaybackClock(time, builtPlan.startAtEpochMs)
        val sink = RecordingSink()
        var paused = false
        val pausingSink =
            LocationSink { sample ->
                sink.push(sample)
                // Pause once we are clearly moving, then resume a couple of ticks later.
                if (!paused && sink.samples.size == 3) {
                    clock.pause()
                    paused = true
                } else if (paused && sink.samples.size == 5) {
                    clock.resume()
                }
            }
        val runner =
            ScheduledPlaybackRunner(builtPlan, RouteTimeline(builtPlan), clock, pausingSink, RecordingListener(), virtualDelayer(time))

        runToCompletion { runner.run() }

        assertEquals(0.0, sink.samples[3].speedMps, 1e-9)
        assertEquals(0.0, sink.samples[4].speedMps, 1e-9)
        assertTrue(sink.samples[2].speedMps > 0.0)
        // Pausing does not advance progress, so later samples should not be past the paused position.
        assertEquals(sink.samples[3].point.lng, sink.samples[4].point.lng, 1e-9)
    }

    @Test
    fun aSinkFailureEndsPlaybackViaOnFailedWithoutThrowing() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs)
        val clock = PlaybackClock(time, builtPlan.startAtEpochMs)
        val sink = RecordingSink().apply { failAfter = 2 }
        val listener = RecordingListener()
        val runner =
            ScheduledPlaybackRunner(builtPlan, RouteTimeline(builtPlan), clock, sink, listener, virtualDelayer(time))

        runToCompletion { runner.run() }

        assertTrue(listener.failure is IllegalStateException)
        assertNull(listener.arrivedAt)
    }

    @Test
    fun cancellationDuringTheWaitPropagatesInsteadOfBeingSwallowed() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs + 30_000L)
        val clock = PlaybackClock(time, builtPlan.startAtEpochMs)
        val job = Job()
        val delayer: suspend (Long) -> Unit = {
            time.elapsedMs += it
            time.wallMs += it
            if (time.elapsedMs >= 5_000L) job.cancel()
        }
        val runner =
            ScheduledPlaybackRunner(builtPlan, RouteTimeline(builtPlan), clock, RecordingSink(), RecordingListener(), delayer)

        assertThrows(CancellationException::class.java) {
            runToCompletion(context = job) { runner.run() }
        }
    }

    @Test
    fun aCancelledRunnerDoesNotWriteAHeldSourceOrPublishMoving() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs + 30_000L, source = a)
        val job = Job().apply { cancel() }
        val sink = RecordingSink()
        val listener = RecordingListener()
        val runner =
            ScheduledPlaybackRunner(
                builtPlan,
                RouteTimeline(builtPlan),
                PlaybackClock(time, builtPlan.startAtEpochMs),
                sink,
                listener,
                virtualDelayer(time),
            )

        assertThrows(CancellationException::class.java) {
            runToCompletion(context = job) { runner.run() }
        }
        assertTrue(sink.samples.isEmpty())
        assertEquals(0, listener.movingCount)
        assertNull(listener.failure)
    }

    @Test
    fun delayedTicksJoinTheCurrentPositionInsteadOfCountingTicks() {
        val time = FakeTime()
        val builtPlan = plan(startAtEpochMs = time.wallMs)
        val sink = RecordingSink()
        val listener = RecordingListener()
        val runner =
            ScheduledPlaybackRunner(
                builtPlan,
                RouteTimeline(builtPlan),
                PlaybackClock(time, builtPlan.startAtEpochMs),
                sink,
                listener,
                delayer = { time.elapsedMs += 4_000L },
            )

        runToCompletion { runner.run() }

        assertEquals(b.lng * 0.4, sink.samples[1].point.lng, 1e-9)
        assertEquals(b, listener.arrivedAt)
        assertEquals(4, sink.samples.size)
    }
}
