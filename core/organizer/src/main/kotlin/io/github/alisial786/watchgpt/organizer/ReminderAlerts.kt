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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.alisial786.watchgpt.reminders.AlertDevice
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Recurrence
import io.github.alisial786.watchgpt.reminders.Repeat
import kotlinx.coroutines.launch
import java.time.Instant

internal class ReminderAlerts(private val organizer: Organizer) {
    private val context = organizer.context
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun target(entry: Entry) = entry.alertDevice == AlertDevice.BOTH || entry.alertDevice == if (organizer.isWatch) AlertDevice.WATCH else AlertDevice.PHONE

    @Synchronized fun restore() {
        organizer.db.all().forEach { schedule(it) }
    }
    fun dismiss(id: String) {
        NotificationManagerCompat.from(context).cancel(id, 1)
    }
    private fun pending(entry: Entry, at: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction("ring").setData(Uri.parse("watchgpt://reminder/${entry.id}"))
            .putExtra("id", entry.id).putExtra("revision", entry.revision).putExtra("author", entry.author).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("ScheduleExactAlarm")
    @Synchronized
    fun schedule(entry: Entry, from: Instant = Instant.now()) {
        alarms.cancel(pending(entry))
        if (entry.deleted || entry.completed || !target(entry)) {
            dismiss(entry.id)
            return
        }
        val now = from.toEpochMilli()
        val initial = entry.snoozeUntil ?: (entry.initialInstant().toEpochMilli() - entry.alertMinutesBefore * 60_000L)
        val snoozed = entry.snoozeUntil
        val due = if (snoozed != null && (entry.repeat == Repeat.NONE || snoozed >= now)) {
            snoozed
        } else if (entry.repeat == Repeat.NONE) {
            initial
        } else {
            Recurrence.next(entry.copy(snoozeUntil = null), from.plusSeconds(entry.alertMinutesBefore * 60L))?.toEpochMilli()?.minus(entry.alertMinutesBefore * 60_000L) ?: return
        }
        if (organizer.db.wasDelivered(token(entry, due))) return
        val whenToRing = maxOf(due, now + 1000)
        val intent = pending(entry, due)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToRing, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToRing, intent)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun fire(id: String, revision: Long, author: String, at: Long) {
        val entry = organizer.db.get(id) ?: return
        if (entry.deleted || entry.completed || !target(entry) || entry.revision != revision || entry.author != author) return
        val token = token(entry, at)
        if (organizer.db.wasDelivered(token)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.putExtra("organizer", true)?.putExtra("quiet", true)?.putExtra("entry", id)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(entry.title).setContentText(entry.notes.ifBlank { "WatchGPT reminder" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.notes.ifBlank { entry.title }))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setLocalOnly(true).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", action(entry, "complete")).addAction(0, "Snooze 10 min", action(entry, "snooze"))
        if (open != null) notification.setContentIntent(PendingIntent.getActivity(context, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        manager.notify(id, 1, notification.build())
        organizer.db.delivered(token)
        if (entry.repeat != Repeat.NONE) schedule(entry, Instant.ofEpochMilli(maxOf(at + 1000, System.currentTimeMillis() + 1000)))
    }
    private fun action(entry: Entry, action: String) = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("watchgpt://$action/${entry.id}")).putExtra("id", entry.id).putExtra("revision", entry.revision).putExtra("author", entry.author), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun token(entry: Entry, at: Long) = "${entry.id}:${entry.revision}:${entry.author}:$at"
    companion object {
        const val CHANNEL = "watchgpt_reminders"
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val organizer = Organizer.get(context)
        organizer.scope.launch {
            try {
                val id = intent.getStringExtra("id") ?: return@launch
                val current = organizer.db.get(id) ?: return@launch
                if (current.revision != intent.getLongExtra("revision", -1) || current.author != intent.getStringExtra("author")) return@launch
                when (intent.action) {
                    "complete" -> organizer.complete(id)
                    "snooze" -> organizer.snooze(id)
                    "ring" -> organizer.alerts.fire(id, intent.getLongExtra("revision", -1), intent.getStringExtra("author").orEmpty(), intent.getLongExtra("at", 0))
                }
            } catch (_: Exception) {
                organizer.requestSync()
            } catch (_: Exception) {
                organizer.requestSync()
            } finally {
                pending.finish()
            }
        }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val organizer = Organizer.get(context)
        organizer.scope.launch {
            try {
                organizer.alerts.restore()
                organizer.requestSync()
            } catch (_: Exception) {
                organizer.requestSync()
            } finally {
                pending.finish()
            }
        }
    }
}
