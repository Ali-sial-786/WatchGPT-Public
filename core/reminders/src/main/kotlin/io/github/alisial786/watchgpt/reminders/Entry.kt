package io.github.alisial786.watchgpt.reminders

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /** The zone the wall time is read in. All-day items are dates, so they are on their day wherever the device is. */
    fun effectiveZone(deviceZone: ZoneId = ZoneId.systemDefault()): ZoneId = if (followsDeviceZone || allDay) deviceZone else ZoneId.of(zone)
    fun initialInstant(deviceZone: ZoneId = ZoneId.systemDefault()): Instant = LocalDateTime.parse(localTime).atZone(effectiveZone(deviceZone)).toInstant()

    /** What a deletion keeps: enough to stop an offline peer restoring the item, none of its content or schedule. */
    fun tombstone(): Entry = Entry(id = id, title = DELETED_TITLE, localTime = TOMBSTONE_TIME, zone = "UTC", deleted = true, revision = revision, author = author)

    companion object {
        const val DELETED_TITLE = "Deleted item"
        private const val TOMBSTONE_TIME = "2000-01-01T00:00"
    }
}

/**
 * The one encoding for storage and the watch link. Defaults are always written: [Entry.zone]'s default is the
 * device's current zone, so leaving it out would move a pinned item to whatever zone the reader is in.
 */
object EntryCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(entry: Entry): String = json.encodeToString(Entry.serializer(), entry)

    fun decode(text: String): Entry = json.decodeFromString(Entry.serializer(), text).validate()
}

/** Lamport revision handles clock skew; immutable tombstones prevent resurrection after long offline periods. */
object Merge {
    fun winner(a: Entry?, b: Entry): Entry {
        b.validate()
        if (a == null) return b
        require(a.id == b.id)
        if (a.deleted != b.deleted) return if (a.deleted) a else b
        // Equal revisions and authors: the canonical encoding decides, the same way on every device.
        return if (compareValuesBy(a, b, { it.revision }, { it.author }, { EntryCodec.encode(it) }) >= 0) a else b
    }
}

object Recurrence {
    fun next(entry: Entry, from: Instant, deviceZone: ZoneId = ZoneId.systemDefault()): Instant? {
        if (entry.deleted || entry.completed) return null
        entry.snoozeUntil?.let { if (Instant.ofEpochMilli(it) >= from) return Instant.ofEpochMilli(it) }
        val zone = entry.effectiveZone(deviceZone)
        return occurrence(entry, from, zone)?.atZone(zone)?.toInstant()
    }

    /** The wall-clock time of the first scheduled occurrence at or after [from], ignoring snooze. A time in a DST gap stays as written. */
    fun occurrence(entry: Entry, from: Instant, zone: ZoneId): LocalDateTime? {
        val seed = LocalDateTime.parse(entry.localTime)
        if (entry.repeat == Repeat.NONE) return seed.takeIf { it.atZone(zone).toInstant() >= from }
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
            if (local.atZone(zone).toInstant() >= from && (entry.repeat != Repeat.WEEKDAYS || local.dayOfWeek !in WEEKEND)) return local
            n++
        }
        return null
    }

    /**
     * Completing an occurrence: a one-off is done; a series moves to the occurrence after [occurrence] (a notification's,
     * or the one a list shows), else after the one due now. The series keeps its wall-clock time even when an occurrence
     * falls in a DST gap.
     */
    fun complete(entry: Entry, now: Instant, deviceZone: ZoneId = ZoneId.systemDefault(), occurrence: LocalDateTime? = null): Entry {
        if (entry.repeat == Repeat.NONE) return entry.copy(completed = true, snoozeUntil = null)
        val zone = entry.effectiveZone(deviceZone)
        val plain = entry.copy(snoozeUntil = null)
        // The first real occurrence: a weekdays series written for a Saturday starts on Monday.
        val first = next(plain, entry.initialInstant(deviceZone), deviceZone) ?: return entry.copy(completed = true, snoozeUntil = null)
        // Done on an advance alert (09:45 for 10:00) completes the occurrence it announced.
        val done = occurrence?.atZone(zone)?.toInstant() ?: now.plusSeconds(entry.alertMinutesBefore * 60L)
        val following = occurrence(plain, maxOf(done, first).plusSeconds(1), zone) ?: return entry.copy(completed = true, snoozeUntil = null)
        return entry.copy(monthlyDay = entry.monthlyDay ?: LocalDateTime.parse(entry.localTime).dayOfMonth, localTime = following.toString(), snoozeUntil = null)
    }

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
