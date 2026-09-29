package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.MockSample

/**
 * Where playback samples go. The Android implementation writes them to the mock providers; tests
 * record them. [push] may throw (for example when mock location is no longer permitted), which
 * ends playback with [ScheduledPlaybackRunner.Listener.onFailed].
 */
fun interface LocationSink {
    fun push(sample: MockSample)
}
