package io.github.alisial786.watchgpt.reminders

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What lists show: when an item is next due and its occurrences in a range, in the device's local time. */
object Agenda {
    /** A series shows its next occurrence (or one missed moments ago), not the day it was made; a one-off its own time. */
    fun nextShown(
        entry: Entry,
        now: Instant,
        deviceZone: ZoneId,
    ): LocalDateTime {
        val zone = entry.effectiveZone(deviceZone)
        val local = if (entry.repeat == Repeat.NONE || entry.completed) LocalDateTime.parse(entry.localTime) else Recurrence.occurrence(entry, now.minus(Alerts.GRACE_REPEAT), zone) ?: LocalDateTime.parse(entry.localTime)
        return shown(entry, local, zone, deviceZone)
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
