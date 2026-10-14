package io.github.alisial786.watchgpt.reminders

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** When a device alerts for an entry. Pure, so scheduling is tested without Android; ReminderAlerts does the I/O. */
object Alerts {
    /**
     * Missed alerts (device off, app restarting, Doze) are still delivered within these windows, older ones are skipped:
     * a series resumes at its next occurrence, and a new or reset device doesn't ring months of history at once.
     */
    val GRACE_REPEAT: Duration = Duration.ofHours(1)
    val GRACE_ONCE: Duration = Duration.ofHours(12)

    /** All-day items alert in the morning of their day, not at midnight. */
    val ALL_DAY_AT: LocalTime = LocalTime.of(9, 0)

    /**
     * One alert: when it rings, and [key], the occurrence it is for. Keys are wall-clock times (or the snooze), so an
     * edit, a sync or a time-zone change doesn't make an alert that already rang ring again.
     */
    data class Alert(val at: Instant, val key: String)

    fun targets(
        entry: Entry,
        watch: Boolean,
    ): Boolean = entry.alertDevice == AlertDevice.BOTH || entry.alertDevice == if (watch) AlertDevice.WATCH else AlertDevice.PHONE

    /** The next alert that has not been [delivered], or null for none. A missed one within its grace window is due now. */
    fun due(
        entry: Entry,
        now: Instant,
        deviceZone: ZoneId,
        delivered: (Alert) -> Boolean = { false },
    ): Alert? {
        if (entry.deleted || entry.completed) return null
        val zone = entry.effectiveZone(deviceZone)
        val lead = Duration.ofMinutes(entry.alertMinutesBefore.toLong())
        val snooze = entry.snoozeUntil?.let { Alert(Instant.ofEpochMilli(it), "snooze:$it") }
        if (entry.repeat == Repeat.NONE) {
            val alert = snooze ?: alert(entry, LocalDateTime.parse(entry.localTime), zone, lead)
            return alert.takeUnless { delivered(it) || it.at < now.minus(GRACE_ONCE) }
        }
        val earliest = now.minus(GRACE_REPEAT)
        if (snooze != null && snooze.at >= earliest && !delivered(snooze)) return snooze
        // Search by occurrence, starting early enough that an all-day item's morning alert isn't passed over.
        var from = earliest.plus(lead).minus(if (entry.allDay) Duration.ofHours(ALL_DAY_AT.hour + 2L) else Duration.ZERO)
        repeat(MAX_CANDIDATES) {
            val local = Recurrence.occurrence(entry, from, zone) ?: return null
            val alert = alert(entry, local, zone, lead)
            if (alert.at >= earliest && !delivered(alert)) return alert
            from = local.atZone(zone).toInstant().plusSeconds(1)
        }
        return null
    }

    /** What a snooze postpones from: the alert that is due now, else the next one. Snoozing never brings an alert forward. */
    fun snoozeFrom(
        entry: Entry,
        now: Instant,
        deviceZone: ZoneId,
    ): Instant = maxOf(now, due(entry, now, deviceZone)?.at ?: now)

    fun token(
        entry: Entry,
        alert: Alert,
    ): String = "${entry.id}@${alert.key}"

    /** The receipt format before 2026-10: it changed with every revision, so edits and syncs re-rang overdue items. */
    fun legacyToken(
        entry: Entry,
        alert: Alert,
    ): String = "${entry.id}:${entry.revision}:${entry.author}:${alert.at.toEpochMilli()}"

    /**
     * Whether "Done" tapped on the notification for [key] still applies: a series that has already moved past that
     * occurrence (completed on the other device, or by an earlier tap) is not advanced again.
     */
    fun completes(
        entry: Entry,
        key: String,
    ): Boolean {
        if (entry.deleted || entry.completed) return false
        if (entry.repeat == Repeat.NONE || key.startsWith("snooze:")) return true
        val occurrence = runCatching { LocalDateTime.parse(key) }.getOrNull() ?: return true
        return LocalDateTime.parse(entry.localTime) <= occurrence
    }

    private fun alert(
        entry: Entry,
        local: LocalDateTime,
        zone: ZoneId,
        lead: Duration,
    ): Alert {
        val wall = if (entry.allDay) local.toLocalDate().atTime(ALL_DAY_AT) else local
        return Alert(wall.atZone(zone).toInstant().minus(lead), local.toString())
    }

    private const val MAX_CANDIDATES = 4
}
