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

    /**
     * The next alert that has not been [delivered], or null for none. A missed one is still due while the time it is
     * for is within its grace window: an advance alert missed overnight still rings before the event.
     */
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
            val forTime = if (alert === snooze) alert.at else alert.at.plus(lead)
            return alert.takeUnless { delivered(it) || forTime < now.minus(GRACE_ONCE) }
        }
        val earliest = now.minus(GRACE_REPEAT)
        if (snooze != null && snooze.at >= earliest && !delivered(snooze)) return snooze
        // A snooze replaces the alerts it postponed.
        val pending = { alert: Alert -> (snooze == null || alert.at > snooze.at) && !delivered(alert) }
        // Search by occurrence, starting early enough that an all-day item's morning alert isn't passed over.
        val shift = if (entry.allDay) Duration.ofHours(ALL_DAY_AT.hour + 2L) else Duration.ZERO
        // The coming occurrence keeps an advance alert that was missed while that occurrence is still ahead.
        generateSequence(Recurrence.occurrence(entry, earliest.minus(shift), zone)) { Recurrence.occurrence(entry, it.atZone(zone).toInstant().plusSeconds(1), zone) }
            .take(2).firstOrNull { alert(entry, it, zone, Duration.ZERO).at >= earliest }
            ?.let { alert(entry, it, zone, lead) }?.takeIf { it.at < earliest && pending(it) }?.let { return it }
        var from = earliest.plus(lead).minus(shift)
        repeat(MAX_CANDIDATES) {
            val local = Recurrence.occurrence(entry, from, zone) ?: return null
            val alert = alert(entry, local, zone, lead)
            if (alert.at >= earliest && pending(alert)) return alert
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

    /**
     * When the alert would have rung under the receipt format before 2026-10 ("id:revision:author:millis"). Only the id
     * and this time still identify it: the revision and author change with every edit and sync.
     */
    fun legacyAt(
        entry: Entry,
        alert: Alert,
        deviceZone: ZoneId,
    ): Long {
        if (alert.key.startsWith("snooze:")) return alert.at.toEpochMilli()
        val local = runCatching { LocalDateTime.parse(alert.key) }.getOrNull() ?: return alert.at.toEpochMilli()
        // All-day items used to alert at midnight.
        return local.atZone(entry.effectiveZone(deviceZone)).toInstant().minusSeconds(entry.alertMinutesBefore * 60L).toEpochMilli()
    }

    /**
     * Whether an action tapped on the notification for [key] still applies. A series that has already moved past that
     * occurrence (completed on the other device, or by an earlier tap), a one-off moved to another time, and a snooze
     * that was replaced or cleared are left alone: the stale notification just goes.
     */
    fun current(
        entry: Entry,
        key: String,
    ): Boolean {
        if (entry.deleted || entry.completed) return false
        if (key.startsWith("snooze:")) return entry.snoozeUntil?.toString() == key.removePrefix("snooze:")
        val occurrence = runCatching { LocalDateTime.parse(key) }.getOrNull() ?: return true
        val scheduled = LocalDateTime.parse(entry.localTime)
        return if (entry.repeat == Repeat.NONE) scheduled == occurrence else scheduled <= occurrence
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
