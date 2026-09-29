package dev.narumi.kestrel.core.location

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.narumi.kestrel.MainActivity
import dev.narumi.kestrel.R
import dev.narumi.kestrel.core.data.MockState

/** Builds notifications from a single playback snapshot without growing the service lifecycle code. */
internal class LocationServiceNotification(
    private val context: Context,
    private val channelId: String,
) {
    fun build(
        currentMode: MockState.Mode,
        paused: Boolean,
        scheduled: ActiveScheduled?,
    ): Notification {
        val launchIntent = Intent(context, MainActivity::class.java)
        val contentPI =
            PendingIntent.getActivity(
                context,
                REQ_CONTENT,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val armed = scheduled?.phase == SchedulePhase.Armed
        val text =
            when {
                currentMode == MockState.Mode.Idle -> context.getString(R.string.location_service_text_ready)
                currentMode == MockState.Mode.Single -> context.getString(R.string.location_service_text_single)
                armed -> context.getString(R.string.location_service_text_scheduled_armed)
                paused -> context.getString(R.string.location_service_text_route_paused)
                else -> context.getString(R.string.location_service_text_route_playing)
            }
        val builder =
            NotificationCompat
                .Builder(context, channelId)
                .setContentTitle(context.getString(R.string.location_service_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentIntent(contentPI)
                .setOngoing(true)

        if (scheduled != null && armed) {
            // Native countdown to the start time; the system redraws it, so nothing ticks here.
            builder
                .setWhen(scheduled.plan.startAtEpochMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        if (currentMode == MockState.Mode.Route && !armed) {
            if (paused) {
                builder.addAction(
                    0,
                    context.getString(R.string.location_service_action_resume),
                    servicePI(REQ_RESUME, LocationService.ACTION_RESUME),
                )
            } else {
                builder.addAction(
                    0,
                    context.getString(R.string.location_service_action_pause),
                    servicePI(REQ_PAUSE, LocationService.ACTION_PAUSE),
                )
            }
        }
        if (currentMode != MockState.Mode.Idle) {
            builder.addAction(
                0,
                context.getString(R.string.location_service_action_stop),
                servicePI(REQ_STOP, LocationService.ACTION_STOP),
            )
        }
        return builder.build()
    }

    private fun servicePI(
        requestCode: Int,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, LocationService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val REQ_CONTENT = 0
        const val REQ_PAUSE = 1
        const val REQ_RESUME = 2
        const val REQ_STOP = 3
    }
}
