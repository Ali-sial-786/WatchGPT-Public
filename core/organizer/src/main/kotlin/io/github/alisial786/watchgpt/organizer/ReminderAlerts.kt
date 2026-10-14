package io.github.alisial786.watchgpt.organizer

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.alisial786.watchgpt.reminders.Alerts
import io.github.alisial786.watchgpt.reminders.Entry
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

internal class ReminderAlerts(private val organizer: Organizer) {
    private val context = organizer.context
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    init {
        // Created up front so it shows in the app's notification settings before the first reminder.
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }

    private fun delivered(entry: Entry): (Alerts.Alert) -> Boolean = { alert -> organizer.db.wasDelivered(Alerts.token(entry, alert)) || organizer.db.wasDelivered(Alerts.legacyToken(entry, alert)) }

    @Synchronized fun restore() {
        organizer.db.all().forEach { schedule(it) }
    }
    fun dismiss(id: String) {
        NotificationManagerCompat.from(context).cancel(id, 1)
    }
    private fun ring(entry: Entry): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(RING).setData(Uri.parse("watchgpt://reminder/${entry.id}")).putExtra(EXTRA_ID, entry.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun notificationsAllowed(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val channel = notifications.getNotificationChannel(CHANNEL)
        return manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    @SuppressLint("ScheduleExactAlarm")
    @Synchronized
    fun schedule(entry: Entry, now: Instant = Instant.now()) {
        alarms.cancel(ring(entry))
        if (entry.deleted) organizer.db.forgetDeliveries(entry.id)
        if (entry.deleted || entry.completed || !Alerts.targets(entry, organizer.isWatch)) {
            dismiss(entry.id)
            return
        }
        val due = Alerts.due(entry, now, ZoneId.systemDefault(), delivered(entry)) ?: return
        val whenToRing = maxOf(due.at.toEpochMilli(), now.toEpochMilli() + 1000)
        val intent = ring(entry)
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToRing, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToRing, intent)
            }
        } catch (_: SecurityException) {
            // Exact access can be revoked between the permission check and scheduling.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToRing, intent)
        }
    }

    /** An alarm went off: shows the alert that is due now, if the item still has one, and schedules the next. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun fire(id: String) {
        val entry = organizer.db.get(id) ?: return
        if (entry.deleted || entry.completed || !Alerts.targets(entry, organizer.isWatch)) return
        val now = Instant.now()
        val due = Alerts.due(entry, now, ZoneId.systemDefault(), delivered(entry)) ?: return
        // Edited elsewhere since this alarm was set: it now belongs at another time.
        if (due.at > now.plusSeconds(EARLY_SECONDS)) return schedule(entry, now)
        if (!notificationsAllowed()) return
        // Straight to the reminders, whether or not the app is already running (the launcher's extras were lost then).
        val open = Intent(context, OrganizerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(entry.title).setContentText(entry.notes.ifBlank { "WatchGPT reminder" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.notes.ifBlank { entry.title }))
            .setWhen(due.at.toEpochMilli()).setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // The lock screen shows that a reminder is due, not what it says.
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_reminder).setContentTitle("WatchGPT reminder").setContentText("Unlock to see it").build())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setLocalOnly(true).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", action(entry, COMPLETE, due)).addAction(0, "Snooze 10 min", action(entry, SNOOZE, due))
        notification.setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        NotificationManagerCompat.from(context).notify(id, 1, notification.build())
        organizer.db.delivered(Alerts.token(entry, due))
        schedule(entry, now)
    }

    /** "Done" on a notification: completes the occurrence it was about, never a later one. */
    suspend fun complete(id: String, key: String?) {
        val entry = organizer.db.get(id) ?: return
        if (key == null || Alerts.completes(entry, key)) organizer.complete(id)
    }

    private fun action(entry: Entry, action: String, alert: Alerts.Alert) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("watchgpt://$action/${entry.id}")).putExtra(EXTRA_ID, entry.id).putExtra(EXTRA_KEY, alert.key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL = "watchgpt_reminders"
        const val RING = "ring"
        const val COMPLETE = "complete"
        const val SNOOZE = "snooze"
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"

        /** Alarms can be delivered slightly early; this much early still counts as on time. */
        private const val EARLY_SECONDS = 60L
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val organizer = Organizer.get(context)
        organizer.scope.launch {
            val id = intent.getStringExtra(ReminderAlerts.EXTRA_ID)
            try {
                if (id != null) {
                    when (intent.action) {
                        ReminderAlerts.COMPLETE -> organizer.alerts.complete(id, intent.getStringExtra(ReminderAlerts.EXTRA_KEY))
                        ReminderAlerts.SNOOZE -> organizer.snooze(id)
                        ReminderAlerts.RING -> organizer.alerts.fire(id)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reminder ${intent.action} failed: ${e.javaClass.simpleName}")
                organizer.requestSync()
            } finally {
                // A tapped action always clears its notification, even when the item changed or went meanwhile.
                if (id != null && intent.action != ReminderAlerts.RING) organizer.alerts.dismiss(id)
                pending.finish()
            }
        }
    }
}
private const val TAG = "WatchGPTAlerts"

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val organizer = Organizer.get(context)
        organizer.scope.launch {
            try {
                organizer.alerts.restore()
                organizer.requestSync()
            } catch (e: Exception) {
                Log.w(TAG, "Restoring reminder alarms failed: ${e.javaClass.simpleName}")
                organizer.requestSync()
            } finally {
                pending.finish()
            }
        }
    }
}
