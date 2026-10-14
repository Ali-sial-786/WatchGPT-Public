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
import java.security.MessageDigest
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Only projects owned records. Android's existing Google account sync adapter handles cloud access. */
class CalendarSync internal constructor(private val organizer: Organizer) {
    data class Calendar(val id: Long, val name: String, val account: String)

    /**
     * The calendar copy of an entry, and what it said ([CalendarProjection.fingerprint]) when last written or read.
     * [v] is the format: a copy linked before [LINK_VERSION] is rewritten once, as this version writes copies.
     */
    @Serializable private data class Link(val eventId: Long, val revision: Long, val author: String, val fingerprint: String, val zone: String = "", val v: Int = 0)
    private class Remote(val id: Long, val fields: EventFields)
    private enum class Outcome { DONE, CONFLICT, PAUSED, UNSUPPORTED, RETAGGED }

    private val context = organizer.context
    private val resolver = context.contentResolver
    private val db = organizer.db
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(CHECKING)
    val status = _status.asStateFlow()
    val lastSync: Long? get() = db.meta("calendar_last_sync")?.toLongOrNull()
    val selected: Long? get() = db.meta("calendar")?.toLongOrNull()
    fun permitted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The first status line, from the saved choice; read off the main thread (it opens the database). */
    internal fun loadStatus() {
        _status.compareAndSet(CHECKING, if (selected == null) OFF else "Calendar sync is on · waiting for the next sync")
    }

    suspend fun calendars(): List<Calendar> = withContext(Dispatchers.IO) {
        if (!permitted()) return@withContext emptyList()
        // Only calendars the owner owns: a calendar shared by someone else would show them every reminder.
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName", "account_name"), "account_type=? AND calendar_access_level>=? AND visible=1", arrayOf("com.google", CalendarContract.Calendars.CAL_ACCESS_OWNER.toString()), null)?.use { c -> buildList { while (c.moveToNext()) add(Calendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())) } }.orEmpty()
    }
    suspend fun select(id: Long?) {
        require(id == null || calendars().any { it.id == id }) { "Choose one of your own Google calendars first." }
        withContext(Dispatchers.IO) {
            val old = selected
            // Moving to another calendar moves the copies: the next sync removes them from the old one. Turning sync
            // off keeps them.
            if (id != null && old != null && old != id) db.meta("$MOVED$old", "1")
            if (id != null) db.removeMeta("$MOVED$id")
            // Links from an earlier time with this calendar are forgotten: copies deleted while sync was off must not
            // delete their items now. Ownership is found again through the WatchGPT markers.
            if (id != null && id != old) db.clearMeta("calendar_${id}_")
            db.meta("calendar", id?.toString().orEmpty())
        }
        _status.value = if (id == null) "Calendar sync is off. Existing calendar copies were kept." else "Calendar sync queued"
        organizer.requestSync()
    }

    /**
     * "Delete everything": the copies of [ids] go from every calendar WatchGPT wrote to, found by their markers. Throws
     * when calendar access is needed for that.
     */
    internal fun removeCopies(ids: Set<String>) {
        val written = db.metaNames("calendar_").mapNotNull { (LINKED.matchEntire(it) ?: PENDING.matchEntire(it))?.groupValues?.get(1)?.toLongOrNull() }
        val calendars = (written + listOfNotNull(selected)).toSet()
        if (calendars.isEmpty()) return
        check(permitted()) { "Allow Calendar access so WatchGPT can remove its calendar copies, then try again." }
        calendars.forEach { removeCopies(it, ids) }
    }

    /** "Delete everything": the opt-in and every link go. */
    internal fun forget() {
        db.meta("calendar", "")
        db.clearMeta("calendar_")
        _status.value = OFF
    }

    internal suspend fun synchronize() {
        val calendar = selected
        val moved = db.metaNames(MOVED)
        if (calendar == null && moved.isEmpty()) {
            _status.value = OFF
            return
        }
        if (!permitted()) {
            _status.value = if (calendar == null) OFF else "Calendar permission needed · local reminders are safe"
            return
        }
        try {
            if (moved.isNotEmpty()) {
                val ids = db.all().map { it.id }.toSet()
                for (name in moved) {
                    name.removePrefix(MOVED).toLongOrNull()?.let { removeCopies(it, ids) }
                    db.removeMeta(name)
                }
            }
            if (calendar == null) {
                _status.value = OFF
                return
            }
            // A calendar chosen before only owned calendars were offered keeps working, with a warning.
            val access = access(calendar)
            if (access == null || access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
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
                "this calendar is shared with you, so its owner can see these reminders: choose one of your own".takeIf { access < CalendarContract.Calendars.CAL_ACCESS_OWNER },
                outcomes[Outcome.CONFLICT]?.let { "$it conflicting edit(s): WatchGPT version kept" },
                outcomes[Outcome.PAUSED]?.let { "$it paused: a calendar copy was moved to another calendar; move it back to resume" },
                outcomes[Outcome.RETAGGED]?.let { "$it calendar copy(ies) had lost their WatchGPT tag and were left alone; new copies written" },
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
        var link = stored?.takeIf { it.isNotEmpty() }?.let { json.decodeFromString<Link>(it) }
        if (entry.deleted || entry.completed) {
            // "" means the copy is known to be gone: no more searching the calendar for it on every sync.
            if (stored == "") return Outcome.DONE
            (link?.let { read(it.eventId, calendar, entry) } ?: find(calendar, entry))?.let { resolver.delete(event(it.id), null, null) }
            db.meta(key, "")
            return Outcome.DONE
        }
        var existing = link?.let { read(it.eventId, calendar, entry) }
        var outcome = Outcome.DONE
        if (link != null && existing == null) {
            // The copy under another id (the calendar's storage was reset and downloaded again) is linked afresh.
            existing = find(calendar, entry)
            if (existing == null) {
                when (state(link.eventId, entry)) {
                    // Moved to another calendar: no event outside the chosen calendar is touched, and this item waits.
                    Copy.ELSEWHERE -> return Outcome.PAUSED

                    // An event without this item's marker is not its copy, whatever its id: it is never changed.
                    Copy.NOT_OURS -> outcome = Outcome.RETAGGED

                    Copy.GONE -> {
                        // The owner deleted the calendar copy: delete the item too (permission and calendar were checked above).
                        db.tombstone(entry.id)
                        db.meta(key, "")
                        return Outcome.DONE
                    }
                }
            }
            link = null
        }
        val outdated = link != null && link.v < LINK_VERSION
        val fingerprint = existing?.let { CalendarProjection.fingerprint(it.fields) }
        val localChanged = link == null || entry.revision != link.revision || entry.author != link.author
        val remoteChanged = existing != null && link != null && link.fingerprint != (if (outdated) legacyFingerprint(existing.fields) else fingerprint)
        var current = entry
        // A calendar edit is taken first; a zone change since then rewrites the copy on the next pass.
        if (existing != null && remoteChanged && !localChanged) {
            val applied = try {
                CalendarProjection.apply(entry, existing.fields, zone)
            } catch (_: IllegalArgumentException) {
                return Outcome.UNSUPPORTED
            }
            current = if (applied != entry) db.save(applied) else entry
            if (!outdated) {
                db.meta(key, json.encodeToString(Link.serializer(), Link(existing.id, current.revision, current.author, fingerprint!!, link.zone, LINK_VERSION)))
                return outcome
            }
        }
        val writtenZone = current.effectiveZone(zone).id
        // An item that keeps local time while travelling is rewritten when the device's zone changes.
        val travelled = link != null && current.followsDeviceZone && !current.allDay && link.zone.isNotEmpty() && link.zone != writtenZone
        if (!localChanged && !travelled && !outdated) return outcome
        // A repeat the owner set in Calendar that WatchGPT can't follow is left as it is, never overwritten.
        if (existing != null && runCatching { CalendarRules.decode(existing.fields.rule, CalendarProjection.startDate(existing.fields)) }.isFailure) return Outcome.UNSUPPORTED
        val values = values(current, CalendarProjection.toEvent(current, zone), calendar)
        val id = if (existing != null) {
            resolver.update(event(existing.id), values, null, null)
            existing.id
        } else {
            ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("Calendar rejected the event"))
        }
        // No calendar alarms: WatchGPT owns alert delivery, avoiding mirrored duplicate alerts.
        resolver.delete(CalendarContract.Reminders.CONTENT_URI, "event_id=?", arrayOf(id.toString()))
        val written = read(id, calendar, current) ?: error("Calendar event could not be verified")
        db.meta(key, json.encodeToString(Link.serializer(), Link(id, current.revision, current.author, CalendarProjection.fingerprint(written.fields), writtenZone, LINK_VERSION)))
        return if (remoteChanged && localChanged) Outcome.CONFLICT else outcome
    }

    private enum class Copy { GONE, ELSEWHERE, NOT_OURS }

    /** What became of a linked copy that is no longer in the chosen calendar with its marker. */
    private fun state(eventId: Long, entry: Entry): Copy = resolver.query(event(eventId), arrayOf("deleted", "description"), null, null, null)?.use { c ->
        when {
            !c.moveToFirst() || c.getInt(0) != 0 -> Copy.GONE
            c.getString(1).orEmpty().contains(CalendarProjection.marker(entry.id)) -> Copy.ELSEWHERE
            else -> Copy.NOT_OURS
        }
    } ?: error("Calendar provider unavailable")

    /** Deletes the copies of [ids] in [calendar], found by their markers, and forgets the links there. */
    private fun removeCopies(calendar: Long, ids: Set<String>) {
        for (copy in query("calendar_id=? AND deleted=0 AND original_id IS NULL AND description LIKE ?", arrayOf(calendar.toString(), "%[WatchGPT:%"), null)) {
            val id = MARKER.find(copy.fields.description)?.groupValues?.get(1) ?: continue
            if (id in ids) resolver.delete(event(copy.id), null, null)
        }
        db.clearMeta("calendar_${calendar}_")
    }

    /** The access level the owner has to [calendar], or null when it is gone or hidden. */
    private fun access(calendar: Long): Int? = resolver.query(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendar), arrayOf("calendar_access_level"), "account_type=? AND visible=1", arrayOf("com.google"), null)?.use { c -> if (c.moveToFirst()) c.getInt(0) else null } ?: error("Calendar provider is temporarily unavailable")

    /** What a link written before [LINK_VERSION] recorded for the copy it read back. */
    private fun legacyFingerprint(fields: EventFields): String = MessageDigest.getInstance("SHA-256").digest(listOf(fields.title, fields.description, fields.start, fields.end ?: 0L, fields.zone, fields.rule, fields.duration.orEmpty(), fields.allDay).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

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

    /** A linked copy, by its id, while it still carries the item's marker: an id alone can name an unrelated event. */
    private fun read(id: Long, calendar: Long, entry: Entry): Remote? = query("_id=? AND calendar_id=? AND deleted=0 AND original_id IS NULL", arrayOf(id.toString(), calendar.toString()), entry.id).firstOrNull()
    private fun query(selection: String, args: Array<String>, marked: String?): List<Remote> = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf("_id", "title", "description", "dtstart", "dtend", "eventTimezone", "rrule", "duration", "allDay"), selection, args, null)?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val description = c.getString(2).orEmpty()
                if (marked != null && !description.contains(CalendarProjection.marker(marked))) continue
                add(Remote(c.getLong(0), EventFields(c.getString(1).orEmpty(), description, c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(7), c.getString(5).orEmpty(), c.getInt(8) == 1, c.getString(6).orEmpty())))
            }
        }
    } ?: error("Calendar provider is temporarily unavailable")

    private companion object {
        const val LINK_VERSION = 1
        const val CHECKING = "Calendar sync · checking…"
        const val OFF = "Calendar sync is off"
        const val MOVED = "calendar_moved_"
        val LINKED = Regex("calendar_(\\d+)_.+")
        val PENDING = Regex("calendar_moved_(\\d+)")
        val MARKER = Regex("\\[WatchGPT:([0-9a-fA-F-]{36})]")
    }
}
