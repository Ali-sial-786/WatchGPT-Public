package io.github.alisial786.watchgpt.reminders

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
enum class Repeat { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY }

@Serializable
enum class Kind { REMINDER, EVENT }

@Serializable
enum class AlertDevice { WATCH, PHONE, BOTH }

/** Local wall time + zone preserves 09:00 across DST. Monthly repeats retain their original day. */
@Serializable
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    val localTime: String,
    val zone: String = ZoneId.systemDefault().id,
    val followsDeviceZone: Boolean = true,
    val repeat: Repeat = Repeat.NONE,
    val monthlyDay: Int? = null,
    val allDay: Boolean = false,
    val alertMinutesBefore: Int = 0,
    val kind: Kind = Kind.REMINDER,
    val durationMinutes: Int = 30,
    val alertDevice: AlertDevice = AlertDevice.WATCH,
    val completed: Boolean = false,
    val deleted: Boolean = false,
    val snoozeUntil: Long? = null,
    val revision: Long = 0,
    val author: String = "",
) {
    fun validate(): Entry = apply {
        UUID.fromString(id)
        require(title.isNotBlank() && title.length <= 200)
        require(notes.length <= 8000 && author.length <= 100)
        require(monthlyDay == null || monthlyDay in 1..31)
        require(alertMinutesBefore in 0..10080)
        require(durationMinutes in 1..10080 && revision >= 0)
        val date = LocalDateTime.parse(localTime)
        require(date.year in 1970..2200)
        ZoneId.of(zone)
        require(snoozeUntil == null || snoozeUntil > 0)
    }
    fun effectiveZone(deviceZone: ZoneId = ZoneId.systemDefault()): ZoneId = if (followsDeviceZone) deviceZone else ZoneId.of(zone)
    fun initialInstant(deviceZone: ZoneId = ZoneId.systemDefault()): Instant = LocalDateTime.parse(localTime).atZone(effectiveZone(deviceZone)).toInstant()
}

/** Lamport revision handles clock skew; immutable tombstones prevent resurrection after long offline periods. */
object Merge {
    fun winner(a: Entry?, b: Entry): Entry {
        b.validate()
        if (a == null) return b
        require(a.id == b.id)
        if (a.deleted != b.deleted) return if (a.deleted) a else b
        return if (compareValuesBy(a, b, { it.revision }, { it.author }, { it.toString() }) >= 0) a else b
    }
}

object Recurrence {
    fun next(entry: Entry, from: Instant, deviceZone: ZoneId = ZoneId.systemDefault()): Instant? {
        if (entry.deleted || entry.completed) return null
        entry.snoozeUntil?.let { if (Instant.ofEpochMilli(it) >= from) return Instant.ofEpochMilli(it) }
        val zone = entry.effectiveZone(deviceZone)
        val seed = LocalDateTime.parse(entry.localTime)
        if (entry.repeat == Repeat.NONE) return seed.atZone(zone).toInstant().takeIf { it >= from }
        val target = from.atZone(zone).toLocalDateTime()
        var n = when (entry.repeat) {
            Repeat.MONTHLY -> ChronoUnit.MONTHS.between(seed.toLocalDate().withDayOfMonth(1), target.toLocalDate().withDayOfMonth(1))
            Repeat.WEEKLY -> ChronoUnit.DAYS.between(seed.toLocalDate(), target.toLocalDate()) / 7
            else -> ChronoUnit.DAYS.between(seed.toLocalDate(), target.toLocalDate())
        }.coerceAtLeast(0)
        repeat(10) {
            val local = when (entry.repeat) {
                Repeat.MONTHLY -> seed.plusMonths(n).let { it.withDayOfMonth(minOf(entry.monthlyDay ?: seed.dayOfMonth, it.toLocalDate().lengthOfMonth())) }
                Repeat.WEEKLY -> seed.plusWeeks(n)
                else -> seed.plusDays(n)
            }
            val at = local.atZone(zone).toInstant()
            if (at >= from && (entry.repeat != Repeat.WEEKDAYS || local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))) return at
            n++
        }
        return null
    }

    fun complete(entry: Entry, now: Instant, deviceZone: ZoneId = ZoneId.systemDefault()): Entry {
        if (entry.repeat == Repeat.NONE) return entry.copy(completed = true, snoozeUntil = null)
        val next = next(entry.copy(snoozeUntil = null), maxOf(now, entry.initialInstant(deviceZone)).plusSeconds(1), deviceZone) ?: return entry.copy(completed = true)
        return entry.copy(monthlyDay = entry.monthlyDay ?: LocalDateTime.parse(entry.localTime).dayOfMonth, localTime = next.atZone(entry.effectiveZone(deviceZone)).toLocalDateTime().toString(), snoozeUntil = null)
    }
}
