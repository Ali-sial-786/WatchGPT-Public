package io.github.alisial786.watchgpt.organizer

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import io.github.alisial786.watchgpt.reminders.CalendarRules
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Repeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/** Only projects owned records. Android's existing Google account sync adapter handles cloud access. */
class CalendarSync internal constructor(private val organizer: Organizer) {
    data class Calendar(val id: Long, val name: String, val account: String)

    @Serializable private data class Link(val eventId: Long, val revision: Long, val author: String, val fingerprint: String)
    private val context = organizer.context
    private val resolver = context.contentResolver
    private val db = organizer.db
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow("Calendar sync is off")
    val status = _status.asStateFlow()
    val lastSync: Long? get() = db.meta("calendar_last_sync")?.toLongOrNull()
    val selected: Long? get() = db.meta("calendar")?.toLongOrNull()
    fun permitted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    suspend fun calendars(): List<Calendar> = withContext(Dispatchers.IO) {
        if (!permitted()) return@withContext emptyList()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName", "account_name"), "account_type=? AND calendar_access_level>=? AND visible=1", arrayOf("com.google", CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c -> buildList { while (c.moveToNext()) add(Calendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())) } }.orEmpty()
    }
    suspend fun select(id: Long?) {
        require(id == null || calendars().any { it.id == id }) { "Choose a writable Google calendar first." }
        db.meta("calendar", id?.toString().orEmpty())
        _status.value = if (id == null) "Calendar sync is off. Existing calendar copies were kept." else "Calendar sync queued"
        organizer.requestSync()
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
            var conflicts = 0
            for (entry in db.all()) {
                val key = "calendar_${calendar}_${entry.id}"
                val link = db.meta(key)?.takeIf { it.isNotEmpty() }?.let { json.decodeFromString<Link>(it) }
                val existing = if (link != null) read(link.eventId, calendar, entry) else find(calendar, entry)
                if (entry.deleted || entry.completed) {
                    if (existing != null) resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.id), null, null)
                    db.meta(key, "")
                    continue
                }
                if (link != null && existing == null) {
                    resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, link.eventId), arrayOf("deleted"), null, null, null)?.use { c ->
                        check(!c.moveToFirst() || c.getInt(0) != 0) { "An owned calendar event was moved or its WatchGPT marker removed. Restore its marker/calendar to resume sync; no unrelated event was changed." }
                    } ?: error("Calendar provider unavailable")
                    // Permission and calendar were verified above. A removed owned event deletes its local counterpart.
                    db.save(entry.copy(deleted = true))
                    db.meta(key, "")
                    continue
                }
                val localChanged = link == null || entry.revision != link.revision || entry.author != link.author
                val remoteChanged = existing != null && link != null && existing.fingerprint != link.fingerprint
                if (remoteChanged && !localChanged) {
                    val remote = existing.toEntry(entry)
                    val saved = db.save(remote)
                    db.meta(key, json.encodeToString(Link.serializer(), Link(existing.id, saved.revision, saved.author, existing.fingerprint)))
                } else if (localChanged) {
                    if (remoteChanged) conflicts++ // deterministic: local wins a simultaneous calendar edit
                    val id = if (existing != null) {
                        resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.id), values(entry, calendar), null, null)
                        existing.id
                    } else {
                        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values(entry, calendar)) ?: error("Calendar rejected the event")
                        ContentUris.parseId(uri)
                    }
                    // No calendar alarms: WatchGPT owns alert delivery, avoiding mirrored duplicate alerts.
                    resolver.delete(CalendarContract.Reminders.CONTENT_URI, "event_id=?", arrayOf(id.toString()))
                    val written = read(id, calendar, entry) ?: error("Calendar event could not be verified")
                    db.meta(key, json.encodeToString(Link.serializer(), Link(id, entry.revision, entry.author, written.fingerprint)))
                }
            }
            db.meta("calendar_last_sync", System.currentTimeMillis().toString())
            _status.value = "Written to Android Calendar · ${java.time.LocalTime.now().withSecond(0).withNano(0)}" + if (conflicts > 0) " · $conflicts conflicting edit(s): WatchGPT version kept" else ""
        } catch (e: Exception) {
            _status.value = "Calendar sync pending · ${e.message?.take(160) ?: e.javaClass.simpleName}"
        }
    }
    private fun marker(entry: Entry) = "[WatchGPT:${entry.id}]"
    private fun values(entry: Entry, calendar: Long) = ContentValues().apply {
        val start = if (entry.allDay) java.time.LocalDateTime.parse(entry.localTime).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else entry.initialInstant().toEpochMilli()
        put(CalendarContract.Events.CALENDAR_ID, calendar)
        put(CalendarContract.Events.TITLE, entry.title)
        put(CalendarContract.Events.DESCRIPTION, "${entry.notes}\n\n${marker(entry)}\nManaged by WatchGPT; reminder alerts are delivered by WatchGPT, not Google Tasks.")
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.EVENT_TIMEZONE, if (entry.allDay) "UTC" else entry.effectiveZone().id)
        put(CalendarContract.Events.ALL_DAY, if (entry.allDay) 1 else 0)
        put(CalendarContract.Events.HAS_ALARM, 0)
        if (entry.repeat == Repeat.NONE) {
            put(CalendarContract.Events.DTEND, start + entry.durationMinutes * 60_000L)
            putNull(CalendarContract.Events.DURATION)
            putNull(CalendarContract.Events.RRULE)
        } else {
            putNull(CalendarContract.Events.DTEND)
            put(CalendarContract.Events.DURATION, "PT${entry.durationMinutes}M")
            put(CalendarContract.Events.RRULE, CalendarRules.encode(entry))
        }
    }
    private data class Remote(val id: Long, val title: String, val description: String, val start: Long, val end: Long, val zone: String, val rule: String, val duration: String, val allDay: Boolean) {
        val fingerprint: String get() = java.security.MessageDigest.getInstance("SHA-256").digest(listOf(title, description, start, end, zone, rule, duration, allDay).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
        fun toEntry(entry: Entry): Entry {
            val decoded = CalendarRules.decode(rule)
            val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
            val minutes = if (end > start) ((end - start) / 60_000).toInt() else Regex("PT(\\d+)M").matchEntire(duration)?.groupValues?.get(1)?.toIntOrNull() ?: entry.durationMinutes
            return entry.copy(allDay = allDay, monthlyDay = decoded.monthlyDay, title = title, notes = description.substringBefore("\n\n[WatchGPT:"), localTime = Instant.ofEpochMilli(start).atZone(z).toLocalDateTime().toString(), zone = z.id, followsDeviceZone = false, durationMinutes = minutes.coerceIn(1, 10080), repeat = decoded.repeat, snoozeUntil = null)
        }
    }
    private fun find(calendar: Long, entry: Entry): Remote? = query("calendar_id=? AND deleted=0 AND description LIKE ?", arrayOf(calendar.toString(), "%${marker(entry)}%"), entry).firstOrNull()
    private fun read(id: Long, calendar: Long, entry: Entry): Remote? = query("_id=? AND calendar_id=? AND deleted=0", arrayOf(id.toString(), calendar.toString()), entry).firstOrNull()
    private fun query(selection: String, args: Array<String>, entry: Entry): List<Remote> = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf("_id", "title", "description", "dtstart", "dtend", "eventTimezone", "rrule", "duration", "allDay"), selection, args, null)?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val description = c.getString(2).orEmpty()
                if (!description.contains(marker(entry))) continue
                add(Remote(c.getLong(0), c.getString(1).orEmpty(), description, c.getLong(3), c.getLong(4), c.getString(5).orEmpty(), c.getString(6).orEmpty(), c.getString(7).orEmpty(), c.getInt(8) == 1))
            }
        }
    } ?: error("Calendar provider is temporarily unavailable")
}
