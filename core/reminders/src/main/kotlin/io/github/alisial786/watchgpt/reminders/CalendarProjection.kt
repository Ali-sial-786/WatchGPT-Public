package io.github.alisial786.watchgpt.reminders

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** An event row as Android's Calendar Provider stores it. */
data class EventFields(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long?,
    val duration: String?,
    val zone: String,
    val allDay: Boolean,
    val rule: String,
)

/**
 * How an entry maps to its calendar copy and back. Pure, so the mapping is tested without a Calendar Provider;
 * CalendarSync does the provider I/O.
 */
object CalendarProjection {
    const val FOOTER = "Managed by WatchGPT; reminder alerts are delivered by WatchGPT, not Google Tasks."
    private const val DAY_MS = 86_400_000L

    fun marker(id: String) = "[WatchGPT:$id]"

    fun toEvent(
        entry: Entry,
        deviceZone: ZoneId,
    ): EventFields {
        val days = ((entry.durationMinutes + 1439) / 1440).coerceAtLeast(1)
        // All-day events live at UTC midnight with day-based lengths: the provider rejects "PT1440M" for them.
        val start = if (entry.allDay) LocalDateTime.parse(entry.localTime).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else entry.initialInstant(deviceZone).toEpochMilli()
        val recurring = entry.repeat != Repeat.NONE
        return EventFields(
            title = entry.title,
            description = listOf(entry.notes.trim(), "${marker(entry.id)}\n$FOOTER").filter { it.isNotEmpty() }.joinToString("\n\n"),
            start = start,
            end = if (recurring) null else start + if (entry.allDay) days * DAY_MS else entry.durationMinutes * 60_000L,
            duration = if (!recurring) {
                null
            } else if (entry.allDay) {
                "P${days}D"
            } else {
                "PT${entry.durationMinutes}M"
            },
            zone = if (entry.allDay) "UTC" else entry.effectiveZone(deviceZone).id,
            allDay = entry.allDay,
            rule = CalendarRules.encode(entry),
        )
    }

    /** The user's own notes: without the marker and footer, wherever an editor moved them, and with web-editor line breaks. */
    fun notes(
        description: String,
        id: String,
    ): String = description.replace(marker(id), "").replace(FOOTER, "")
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim().take(NOTES_MAX)

    /**
     * Minutes in an RFC 5545 duration as calendars write them: "PT30M", "P1800S" (Google's sync), "PT1H30M", "P1D",
     * "P1W". Null when unreadable.
     */
    fun minutes(duration: String): Long? {
        val m = Regex("""P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(duration.trim().uppercase()) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        val (w, d, h, min, s) = m.groupValues.drop(1).map { it.toLongOrNull() ?: 0 }
        return w * 10_080 + d * 1_440 + h * 60 + min + (s + 59) / 60
    }

    /** What the copy means, compared instead of raw fields: Google's sync rewrites DURATION and RRULE without changing them. */
    fun fingerprint(event: EventFields): String {
        val repeat = runCatching { CalendarRules.decode(event.rule, startDate(event)) }.map { "${it.repeat}:${it.monthlyDay}" }.getOrElse { "raw:${event.rule}" }
        val minutes = (event.end?.takeIf { it > event.start }?.let { (it - event.start) / 60_000 }) ?: event.duration?.let(::minutes)
        val zone = if (event.allDay) "" else runCatching { ZoneId.of(event.zone).normalized().id }.getOrDefault(event.zone)
        val text = listOf(event.title.trim(), event.description.trim(), event.start, minutes, zone, event.allDay, repeat).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * The entry after a calendar edit. Only what the calendar can express changes; WatchGPT's own settings (alert
     * device, lead time, "keep local time when travelling", snooze when the time is unchanged) are kept.
     * Throws [IllegalArgumentException] for recurrence WatchGPT can't represent; the caller leaves that item pending.
     */
    fun apply(
        entry: Entry,
        event: EventFields,
        deviceZone: ZoneId,
    ): Entry {
        val decoded = CalendarRules.decode(event.rule, startDate(event))
        val seed = LocalDateTime.parse(entry.localTime)
        var next = entry.copy(
            title = event.title.trim().take(TITLE_MAX).ifBlank { entry.title },
            notes = notes(event.description, entry.id),
            allDay = event.allDay,
            repeat = decoded.repeat,
            monthlyDay = if (decoded.repeat == Repeat.MONTHLY) decoded.monthlyDay ?: startDate(event).dayOfMonth else null,
        )
        if (event.allDay) {
            // A date, not an instant: it stays on that day in whatever zone the entry uses.
            next = next.copy(localTime = startDate(event).atStartOfDay().toString())
        } else {
            // Compared as wall time in the event's own zone: an item that follows the device was written in the zone
            // the device had then, so travelling since doesn't look like a calendar edit.
            val zone = runCatching { ZoneId.of(event.zone) }.getOrDefault(entry.effectiveZone(deviceZone))
            val local = Instant.ofEpochMilli(event.start).atZone(zone).toLocalDateTime().withSecond(0).withNano(0)
            next = when {
                entry.followsDeviceZone -> next.copy(localTime = if (local == seed && !entry.allDay) entry.localTime else local.toString())
                else -> next.copy(localTime = if (local == seed && zone.id == entry.zone && !entry.allDay) entry.localTime else local.toString(), zone = zone.id)
            }
        }
        val minutes = (event.end?.takeIf { it > event.start }?.let { (it - event.start) / 60_000 }) ?: event.duration?.let(::minutes)
        if (minutes != null) next = next.copy(durationMinutes = minutes.coerceIn(1, 10_080).toInt())
        // A snooze belongs to the time it was set for.
        if (next.localTime != entry.localTime || next.repeat != entry.repeat || next.zone != entry.zone) next = next.copy(snoozeUntil = null)
        return next.validate()
    }

    /** The calendar date an event starts on: all-day events are dated at UTC midnight. */
    fun startDate(event: EventFields): LocalDate {
        val zone = if (event.allDay) ZoneOffset.UTC else runCatching { ZoneId.of(event.zone) }.getOrDefault(ZoneOffset.UTC)
        return Instant.ofEpochMilli(event.start).atZone(zone).toLocalDate()
    }

    private const val TITLE_MAX = 200
    private const val NOTES_MAX = 8000
}
