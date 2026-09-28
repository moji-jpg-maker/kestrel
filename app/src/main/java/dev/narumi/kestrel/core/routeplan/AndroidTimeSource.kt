package dev.narumi.kestrel.core.routeplan

import android.os.SystemClock

object AndroidTimeSource : TimeSource {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun currentTimeMs(): Long = System.currentTimeMillis()
}
