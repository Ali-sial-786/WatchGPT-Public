package io.github.alisial786.watchgpt.organizer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

/**
 * Native Clock has no receipt API: an accepted intent is a request, never proof of an alarm. Results follow the tool
 * convention: "ok:" sent (the card shows), "not done yet:" waiting for the user's tap, "error:" not sent.
 */
object NativeClock {
    fun request(context: Context, foreground: Boolean, seconds: Int? = null, hour: Int? = null, minute: Int = 0, label: String? = null, days: List<Int> = emptyList()): String {
        require(days.size <= 7 && days.all { it in 1..7 })
        val device = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) "watch" else "phone"
        val intent = if (seconds != null) {
            require(seconds in 1..86400)
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        } else {
            require(hour != null && hour in 0..23 && minute in 0..59)
            Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).apply {
                // Only for repeating alarms: some Clock apps read an empty list as "never".
                if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.distinct()))
            }
        }
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.take(200)) }
        // On screen the Clock app takes it directly; from a notification tap it shows its screen to confirm.
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, foreground).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return "error: the $device has no Clock app that accepts this request"
        if (foreground) {
            return try {
                context.startActivity(intent)
                "ok: sent to the $device's Clock app. Clock doesn't confirm it, so the user can check there" + if (days.isNotEmpty()) "; repeating days depend on that Clock app." else "."
            } catch (_: ActivityNotFoundException) {
                "error: Clock is unavailable"
            } catch (_: SecurityException) {
                "error: Clock refused this request"
            }
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return "not done yet: the $device can't open its Clock app from the background and WatchGPT's notifications are off there. Ask the user to open WatchGPT on the $device and ask again."
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("watchgpt_clock", "Clock requests", NotificationManager.IMPORTANCE_HIGH))
        // Tapping a notification is an Android-supported user initiated activity launch.
        val id = UUID.randomUUID().hashCode()
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val notification = NotificationCompat.Builder(context, "watchgpt_clock").setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(if (seconds == null) "Tap to create alarm" else "Tap to start timer")
            .setContentText(label ?: if (seconds == null) "%02d:%02d".format(hour, minute) else "$seconds seconds")
            .setContentIntent(pending).setAutoCancel(true).setLocalOnly(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter(15 * 60_000L).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        manager.notify(id, notification)
        return "not done yet: nothing is set until the user taps the WatchGPT notification on the $device (within 15 minutes) and confirms in Clock. Tell them."
    }
}
