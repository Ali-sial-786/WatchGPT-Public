package io.github.alisial786.watchgpt.organizer

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import io.github.alisial786.watchgpt.reminders.CalendarProjection
import io.github.alisial786.watchgpt.reminders.CalendarRules
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.EventFields
import io.github.alisial786.watchgpt.reminders.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Only projects owned records. Android's existing Google account sync adapter handles cloud access. */
class CalendarSync internal constructor(private val organizer: Organizer) {
    data class Calendar(val id: Long, val name: String, val account: String)

    /** The calendar copy of an entry, and what it said ([CalendarProjection.fingerprint]) when last written or read. */
    @Serializable private data class Link(val eventId: Long, val revision: Long, val author: String, val fingerprint: String, val zone: String = "")
    private class Remote(val id: Long, val fields: EventFields)
    private enum class Outcome { DONE, CONFLICT, PAUSED, UNSUPPORTED }

    private val context = organizer.context
    private val resolver = context.contentResolver
    private val db = organizer.db
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(if (db.meta("calendar").isNullOrEmpty()) "Calendar sync is off" else "Calendar sync is on · waiting for the next sync")
    val status = _status.asStateFlow()
    val lastSync: Long? get() = db.meta("calendar_last_sync")?.toLongOrNull()
    val selected: Long? get() = db.meta("calendar")?.toLongOrNull()
    fun permitted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    suspend fun calendars(): List<Calendar> = withContext(Dispatchers.IO) {
        if (!permitted()) return@withContext emptyList()
        // Only calendars the owner owns: a calendar shared by someone else would show them every reminder.
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName", "account_name"), "account_type=? AND calendar_access_level>=? AND visible=1", arrayOf("com.google", CalendarContract.Calendars.CAL_ACCESS_OWNER.toString()), null)?.use { c -> buildList { while (c.moveToNext()) add(Calendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())) } }.orEmpty()
    }
    suspend fun select(id: Long?) {
        require(id == null || calendars().any { it.id == id }) { "Choose a writable Google calendar first." }
        // Links from an earlier time with this calendar are forgotten: copies deleted while sync was off must not
        // delete their items now. Ownership is found again through the WatchGPT markers.
        if (id != null && id != selected) db.clearMeta("calendar_${id}_")
        db.meta("calendar", id?.toString().orEmpty())
        _status.value = if (id == null) "Calendar sync is off. Existing calendar copies were kept." else "Calendar sync queued"
        organizer.requestSync()
    }

    /** "Delete everything": the opt-in and every link go; any copies left behind are no longer managed. */
    internal fun forget() {
        db.meta("calendar", "")
        db.clearMeta("calendar_")
        _status.value = "Calendar sync is off"
    }

    internal suspend fun synchronize() {
        val calendar = selected ?: run {
            _status.value = "Calendar sync is off"
            return
        }
        if (!permitted()) {
            _status.value = "Calendar permission needed · local reminders are safe"
            return
        }
        try {
            if (calendars().none { it.id == calendar }) {
                _status.value = "Selected Google calendar is unavailable. Choose a calendar."
                return
            }
            val zone = ZoneId.systemDefault()
            val outcomes = mutableMapOf<Outcome, Int>()
            var failed = 0
            for (entry in db.all()) {
                // Each item on its own: one event the provider rejects must not hold back every other item.
                try {
                    val outcome = synchronize(entry, calendar, zone)
                    outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
                } catch (e: EntryDatabase.KeyUnavailable) {
                    throw e
                } catch (_: RuntimeException) {
                    failed++
                }
            }
            db.meta("calendar_last_sync", System.currentTimeMillis().toString())
            val notes = listOfNotNull(
                outcomes[Outcome.CONFLICT]?.let { "$it conflicting edit(s): WatchGPT version kept" },
                outcomes[Outcome.PAUSED]?.let { "$it paused: a calendar copy was moved to another calendar; move it back to resume" },
                outcomes[Outcome.UNSUPPORTED]?.let { "$it waiting: a calendar repeat WatchGPT can't follow (simplify it in Calendar)" },
                "$failed not written yet; will retry".takeIf { failed > 0 },
            )
            _status.value = "Written to Android Calendar · ${LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}" + notes.joinToString("") { " · $it" }
        } catch (e: Exception) {
            _status.value = "Calendar sync pending · ${e.message?.take(160) ?: e.javaClass.simpleName}"
        }
    }

    private fun synchronize(entry: Entry, calendar: Long, zone: ZoneId): Outcome {
        val key = "calendar_${calendar}_${entry.id}"
        val stored = db.meta(key)
        val link = stored?.takeIf { it.isNotEmpty() }?.let { json.decodeFromString<Link>(it) }
        if (entry.deleted || entry.completed) {
            // "" means the copy is known to be gone: no more searching the calendar for it on every sync.
            if (stored == "") return Outcome.DONE
            (link?.let { read(it.eventId, calendar, entry) } ?: find(calendar, entry))?.let { resolver.delete(event(it.id), null, null) }
            db.meta(key, "")
            return Outcome.DONE
        }
        val existing = if (link != null) read(link.eventId, calendar, entry) else find(calendar, entry)
        if (link != null && existing == null) {
            val removed = resolver.query(event(link.eventId), arrayOf("deleted"), null, null, null)?.use { c -> !c.moveToFirst() || c.getInt(0) != 0 } ?: error("Calendar provider unavailable")
            // Moved to another calendar: no event outside the chosen calendar is touched, and this item waits.
            if (!removed) return Outcome.PAUSED
            // The owner deleted the calendar copy: delete the item too (permission and calendar were checked above).
            db.save(entry.tombstone())
            db.meta(key, "")
            return Outcome.DONE
        }
        val fingerprint = existing?.let { CalendarProjection.fingerprint(it.fields) }
        val writtenZone = entry.effectiveZone(zone).id
        // An item that keeps local time while travelling is rewritten when the device's zone changes.
        val travelled = link != null && entry.followsDeviceZone && !entry.allDay && link.zone.isNotEmpty() && link.zone != writtenZone
        val localChanged = link == null || entry.revision != link.revision || entry.author != link.author
        val remoteChanged = existing != null && link != null && fingerprint != link.fingerprint
        // A calendar edit is taken first; a zone change since then rewrites the copy on the next pass.
        if (existing != null && remoteChanged && !localChanged) {
            val applied = try {
                CalendarProjection.apply(entry, existing.fields, zone)
            } catch (_: IllegalArgumentException) {
                return Outcome.UNSUPPORTED
            }
            val saved = if (applied != entry) db.save(applied) else entry
            db.meta(key, json.encodeToString(Link.serializer(), Link(existing.id, saved.revision, saved.author, fingerprint!!, link!!.zone)))
            return Outcome.DONE
        }
        if (!localChanged && !travelled) return Outcome.DONE
        // A repeat the owner set in Calendar that WatchGPT can't follow is left as it is, never overwritten.
        if (existing != null && runCatching { CalendarRules.decode(existing.fields.rule, CalendarProjection.startDate(existing.fields)) }.isFailure) return Outcome.UNSUPPORTED
        val values = values(entry, CalendarProjection.toEvent(entry, zone), calendar)
        val id = if (existing != null) {
            resolver.update(event(existing.id), values, null, null)
            existing.id
        } else {
            ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("Calendar rejected the event"))
        }
        // No calendar alarms: WatchGPT owns alert delivery, avoiding mirrored duplicate alerts.
        resolver.delete(CalendarContract.Reminders.CONTENT_URI, "event_id=?", arrayOf(id.toString()))
        val written = read(id, calendar, entry) ?: error("Calendar event could not be verified")
        db.meta(key, json.encodeToString(Link.serializer(), Link(id, entry.revision, entry.author, CalendarProjection.fingerprint(written.fields), writtenZone)))
        return if (remoteChanged) Outcome.CONFLICT else Outcome.DONE
    }

    private fun event(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)

    private fun values(entry: Entry, fields: EventFields, calendar: Long) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendar)
        // Private to the owner in shared views, and a reminder doesn't make them look busy.
        put(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_PRIVATE)
        put(CalendarContract.Events.AVAILABILITY, if (entry.kind == Kind.REMINDER) CalendarContract.Events.AVAILABILITY_FREE else CalendarContract.Events.AVAILABILITY_BUSY)
        put(CalendarContract.Events.TITLE, fields.title)
        put(CalendarContract.Events.DESCRIPTION, fields.description)
        put(CalendarContract.Events.DTSTART, fields.start)
        put(CalendarContract.Events.EVENT_TIMEZONE, fields.zone)
        put(CalendarContract.Events.ALL_DAY, if (fields.allDay) 1 else 0)
        put(CalendarContract.Events.HAS_ALARM, 0)
        fields.end?.let { put(CalendarContract.Events.DTEND, it) } ?: putNull(CalendarContract.Events.DTEND)
        fields.duration?.let { put(CalendarContract.Events.DURATION, it) } ?: putNull(CalendarContract.Events.DURATION)
        fields.rule.takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.RRULE, it) } ?: putNull(CalendarContract.Events.RRULE)
    }

    // Exceptions to one occurrence (original_id set) copy the series' description and marker: never link to them.

    /** An unlinked copy, found by its marker (e.g. after a crash between inserting it and recording the link). */
    private fun find(calendar: Long, entry: Entry): Remote? = query("calendar_id=? AND deleted=0 AND original_id IS NULL AND description LIKE ?", arrayOf(calendar.toString(), "%${CalendarProjection.marker(entry.id)}%"), entry.id).firstOrNull()

    /** A linked copy, by its id: still ours if the owner edited the marker out of its description. */
    private fun read(id: Long, calendar: Long, entry: Entry): Remote? = query("_id=? AND calendar_id=? AND deleted=0 AND original_id IS NULL", arrayOf(id.toString(), calendar.toString()), null).firstOrNull()
    private fun query(selection: String, args: Array<String>, marked: String?): List<Remote> = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf("_id", "title", "description", "dtstart", "dtend", "eventTimezone", "rrule", "duration", "allDay"), selection, args, null)?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val description = c.getString(2).orEmpty()
                if (marked != null && !description.contains(CalendarProjection.marker(marked))) continue
                add(Remote(c.getLong(0), EventFields(c.getString(1).orEmpty(), description, c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(7), c.getString(5).orEmpty(), c.getInt(8) == 1, c.getString(6).orEmpty())))
            }
        }
    } ?: error("Calendar provider is temporarily unavailable")
}
