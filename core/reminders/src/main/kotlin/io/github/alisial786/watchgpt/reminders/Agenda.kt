package io.github.alisial786.watchgpt.reminders

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What lists show: when an item is next due and its occurrences in a range, in the device's local time. */
object Agenda {
    /**
     * A series shows its next occurrence (or one missed moments ago, or today's for an all-day item), not the day it
     * was made; a one-off its own time.
     */
    fun nextShown(
        entry: Entry,
        now: Instant,
        deviceZone: ZoneId,
    ): LocalDateTime = shown(entry, current(entry, now, deviceZone), entry.effectiveZone(deviceZone), deviceZone)

    /** The occurrence [nextShown] shows, in the item's own wall time: what "Done" on that card completes. */
    fun current(
        entry: Entry,
        now: Instant,
        deviceZone: ZoneId,
    ): LocalDateTime {
        val zone = entry.effectiveZone(deviceZone)
        val from = if (entry.allDay) now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant() else now.minus(Alerts.GRACE_REPEAT)
        return if (entry.repeat == Repeat.NONE || entry.completed) LocalDateTime.parse(entry.localTime) else Recurrence.occurrence(entry, from, zone) ?: LocalDateTime.parse(entry.localTime)
    }

    /**
     * What "Done" on the item's card completes, or null for the occurrence due now (the default). The occurrence the
     * card shows, unless one went by in the last half day (Done late is for that one) or a snooze is pending (Done is for
     * the occurrence it postponed, as on its notification).
     */
    fun doneOccurrence(
        entry: Entry,
        now: Instant,
        deviceZone: ZoneId,
    ): LocalDateTime? {
        if (entry.repeat == Repeat.NONE || entry.completed) return null
        if (entry.snoozeUntil?.let { Instant.ofEpochMilli(it) >= now.minus(Alerts.GRACE_REPEAT) } == true) return null
        val zone = entry.effectiveZone(deviceZone)
        val shown = current(entry, now, deviceZone)
        val recent = Recurrence.occurrence(entry, now.minus(Alerts.GRACE_ONCE), zone) ?: return shown
        return if (recent < shown) null else shown
    }

    /** Whether the item's time has passed: for an all-day item once its day is over, for an event once it has ended. */
    fun overdue(
        entry: Entry,
        now: Instant,
        deviceZone: ZoneId,
    ): Boolean {
        if (entry.completed || entry.deleted) return false
        val shown = nextShown(entry, now, deviceZone)
        val end = when {
            entry.allDay -> shown.toLocalDate().plusDays(1).atStartOfDay()
            entry.kind == Kind.EVENT -> shown.plusMinutes(entry.durationMinutes.toLong())
            else -> shown
        }
        return end.atZone(deviceZone).toInstant() < now
    }

    /** The item's occurrences from [from] (inclusive) to [to] (exclusive), at most [max]. */
    fun occurrences(
        entry: Entry,
        from: LocalDateTime,
        to: LocalDateTime,
        deviceZone: ZoneId,
        max: Int = 62,
    ): List<LocalDateTime> {
        if (entry.deleted || entry.completed) return emptyList()
        val zone = entry.effectiveZone(deviceZone)
        if (entry.repeat == Repeat.NONE) return listOf(shown(entry, LocalDateTime.parse(entry.localTime), zone, deviceZone)).filter { it >= from && it < to }
        val out = mutableListOf<LocalDateTime>()
        var cursor = from.atZone(deviceZone).toInstant()
        while (out.size < max) {
            val local = Recurrence.occurrence(entry, cursor, zone) ?: break
            val at = shown(entry, local, zone, deviceZone)
            if (at >= to) break
            if (at >= from) out += at
            cursor = local.atZone(zone).toInstant().plusSeconds(1)
        }
        return out
    }

    // All-day items are dates: they stay on their day wherever the device is.
    private fun shown(
        entry: Entry,
        local: LocalDateTime,
        zone: ZoneId,
        deviceZone: ZoneId,
    ): LocalDateTime = if (entry.allDay) local else local.atZone(zone).withZoneSameInstant(deviceZone).toLocalDateTime()
}
