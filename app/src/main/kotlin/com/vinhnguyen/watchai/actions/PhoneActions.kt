package io.github.alisial786.watchgpt.actions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import io.github.alisial786.watchgpt.brain.BrainLogger
import io.github.alisial786.watchgpt.brain.LogEvent
import io.github.alisial786.watchgpt.brain.ToolSpec
import io.github.alisial786.watchgpt.brain.Toolbox
import io.github.alisial786.watchgpt.organizer.ItemChangedException
import io.github.alisial786.watchgpt.organizer.Organizer
import io.github.alisial786.watchgpt.reminders.Agenda
import io.github.alisial786.watchgpt.reminders.AlertDevice
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Kind
import io.github.alisial786.watchgpt.reminders.Repeat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What the assistant can do on this phone: notes (kept in this app), reminders and agenda events
 * (WatchGPT's own, encrypted on the phone and the watch; copied to Google Calendar only if the user
 * turned that on), reading the agenda together with the phone's calendar, timers and alarms (on the
 * device the user talks through, or where they say), and [PhoneControls] that work from a pocket:
 * find my phone, music, volume, ringer, Do Not Disturb, battery. A reminder is only deleted after the
 * user's yes ([Pending]); nothing is sent anywhere. Every argument is checked; results are short plain
 * sentences for the model.
 */
class PhoneActions(
    context: Context,
    private val notes: NoteStore,
    private val controls: PhoneControls,
    private val phoneClock: Clock,
    /** Set while the user talks through the watch: timers and alarms go there unless they ask for the phone. */
    private val watch: Watch? = null,
    private val logger: BrainLogger = BrainLogger.None,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** The phone's pop-up shows what got done. */
    private val cards: CardHub? = null,
    /** Deleting a reminder waits here for the user's yes; without it, deleting by voice isn't offered. */
    private val pending: Pending? = null,
) : Toolbox {
    private val appContext = context.applicationContext

    override fun tools(): List<ToolSpec> = SPECS

    fun calendarAllowed(): Boolean = CALENDAR_PERMISSIONS.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String {
        val args = ActionArgs.parse(argumentsJson) ?: return done(name, "invalid", "error: the arguments were not valid JSON")
        return try {
            when (name) {
                ADD_NOTE -> addNote(args)
                LIST_NOTES -> listNotes(args)
                ADD_EVENT -> addEvent(args)
                LIST_EVENTS -> listEvents(args)
                ADD_REMINDER -> addReminder(args)
                LIST_REMINDERS -> listReminders()
                CHANGE_REMINDER -> changeReminder(args)
                SET_TIMER -> setTimer(args)
                SET_ALARM -> setAlarm(args)
                RING_PHONE -> outcome(name, controls.ring(ActionArgs.int(args, "seconds", 5..60) ?: RING_SECONDS)) { WatchGPTCard.Done(Symbol.PHONE, "Ringing your phone", "Stop it from its notification") }
                STOP_RINGING -> outcome(name, controls.stopRinging())
                MEDIA -> ActionArgs.choice(args, "command", PhoneControls.MEDIA_KEYS.keys)?.let { command -> outcome(name, controls.media(command)) { WatchGPTCard.Done(Symbol.MUSIC, MEDIA_TITLES.getValue(command)) } } ?: invalid(name, "command")
                SET_VOLUME -> setVolume(args)
                SET_RINGER -> ActionArgs.choice(args, "mode", PhoneControls.RINGER_MODES.keys)?.let { mode -> outcome(name, controls.ringer(mode)) { WatchGPTCard.Done(Symbol.PHONE, "Ringer: $mode") } } ?: invalid(name, "mode")
                DO_NOT_DISTURB -> ActionArgs.bool(args, "on")?.let { on -> outcome(name, controls.doNotDisturb(on)) { WatchGPTCard.Done(Symbol.PHONE, if (on) "Do Not Disturb on" else "Do Not Disturb off") } } ?: invalid(name, "on")
                DEVICE_STATUS -> outcome(name, listOfNotNull(controls.status(), watch?.battery()).joinToString("; "))
                else -> done(name, "unknown", "error: there is no action called $name")
            }
        } catch (e: ItemChangedException) {
            done(name, "conflict", "error: that item changed on the user's other device just now; nothing was saved. Ask them to try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            done(name, "invalid", "error: invalid reminder or event details; nothing was saved")
        } catch (e: IllegalStateException) {
            done(name, "failed", "error: WatchGPT's reminder storage isn't available on the phone right now; nothing was saved")
        } catch (e: SecurityException) {
            done(name, "denied", "error: the phone refused this action")
        }
    }

    private suspend fun addNote(args: JsonObject): String {
        val text = ActionArgs.text(args, "text", NOTE_MAX) ?: return done(ADD_NOTE, "invalid", "error: the note needs text (up to $NOTE_MAX characters)")
        notes.add(text)
        cards?.show(WatchGPTCard.Done(Symbol.NOTE, "Note saved", text))
        return done(ADD_NOTE, "ok", "ok: note saved")
    }

    private suspend fun listNotes(args: JsonObject): String {
        val limit = ActionArgs.int(args, "limit", 1..20) ?: 10
        val all = notes.list()
        if (all.isEmpty()) return done(LIST_NOTES, "ok", "there are no notes yet")
        val newest = all.takeLast(limit).reversed()
        return done(LIST_NOTES, "ok", "${all.size} notes, newest first: " + newest.joinToString(" | ") { it.text })
    }

    private suspend fun addEvent(args: JsonObject): String {
        val title = ActionArgs.text(args, "title", TITLE_MAX) ?: return done(ADD_EVENT, "invalid", "error: the event needs a title")
        val start = ActionArgs.time(ActionArgs.text(args, "start", 40), zone()) ?: return done(ADD_EVENT, "invalid", "error: start must be a date or date and time like 2026-09-28T15:00")
        val location = ActionArgs.text(args, "location", TITLE_MAX)
        val details = ActionArgs.text(args, "notes", NOTE_MAX)
        return when (start) {
            is ActionArgs.When.Day -> {
                if (!plausible(start.date.atStartOfDay())) return done(ADD_EVENT, "invalid", "error: that date is too far away")
                insertEvent(title, start.date.atStartOfDay(), start.date.plusDays(1).atStartOfDay(), allDay = true, location, details, alertMinutes = null)
                cards?.show(WatchGPTCard.Done(Symbol.CALENDAR, title, "${ActionArgs.say(start.date)} · all day"))
                done(ADD_EVENT, "ok", "ok: added \"$title\" as an all-day event on ${ActionArgs.say(start.date)}")
            }

            is ActionArgs.When.At -> {
                if (!plausible(start.time)) return done(ADD_EVENT, "invalid", "error: that time is in the past or too far away")
                val end =
                    (ActionArgs.time(ActionArgs.text(args, "end", 40), zone()) as? ActionArgs.When.At)?.time?.takeIf { it.isAfter(start.time) }
                        ?: start.time.plusMinutes((ActionArgs.int(args, "duration_minutes", 5..1_440) ?: 60).toLong())
                if (Duration.between(start.time, end) > Duration.ofDays(1)) return done(ADD_EVENT, "invalid", "error: events can be at most a day long")
                insertEvent(title, start.time, end, allDay = false, location, details, alertMinutes = ActionArgs.int(args, "alert_minutes_before", 0..10_080))
                cards?.show(WatchGPTCard.Done(Symbol.CALENDAR, title, ActionArgs.say(start.time)))
                done(ADD_EVENT, "ok", "ok: added \"$title\" on ${ActionArgs.say(start.time)} until ${end.toLocalTime()}")
            }
        }
    }

    private suspend fun addReminder(args: JsonObject): String {
        val text = ActionArgs.text(args, "text", TITLE_MAX) ?: return done(ADD_REMINDER, "invalid", "error: the reminder needs text")
        val at = ActionArgs.time(ActionArgs.text(args, "at", 40), zone()) as? ActionArgs.When.At ?: return done(ADD_REMINDER, "invalid", "error: at must be a date and time like 2026-09-28T15:00")
        if (!plausible(at.time)) return done(ADD_REMINDER, "invalid", "error: that time is in the past or too far away")
        val repeat = ActionArgs.choice(args, "repeat", REPEATS) ?: "none"
        val target = alertDevice(args)
        Organizer.get(appContext).save(Entry(title = text, notes = ActionArgs.text(args, "notes", 8000).orEmpty(), localTime = at.time.toString(), zone = zone().id, repeat = Repeat.valueOf(repeat.uppercase()), alertDevice = target))
        cards?.show(WatchGPTCard.Done(Symbol.CALENDAR, text, "Reminder · ${ActionArgs.say(at.time)}"))
        val where = when (target) {
            AlertDevice.BOTH -> "the phone and the watch"
            AlertDevice.WATCH -> "the watch (once it has synced)"
            AlertDevice.PHONE -> "the phone"
        }
        return done(ADD_REMINDER, "ok", "ok: reminder \"$text\" saved for ${ActionArgs.say(at.time)}; it alerts on $where if notifications are allowed there.")
    }

    /** Open reminders by when they are next due, said the way people say them; changes go by title, so no IDs. */
    private suspend fun listReminders(): String {
        val now = Instant.now()
        val items = Organizer.get(appContext).list().filter { it.kind == Kind.REMINDER && !it.completed }
            .map { it to Agenda.nextShown(it, now, zone()) }.sortedBy { it.second }
        if (items.isEmpty()) return done(LIST_REMINDERS, "ok", "no open reminders")
        val shown = items.take(LIST_MAX_REMINDERS).joinToString(" | ") { (e, at) ->
            val repeat = if (e.repeat == Repeat.NONE) "" else ", ${e.repeat.name.lowercase()}"
            val notes = e.notes.takeIf { it.isNotBlank() }?.let { "; notes: ${it.take(200)}" }.orEmpty()
            "\"${e.title}\" ${ActionArgs.say(at)}$repeat$notes"
        }
        return done(LIST_REMINDERS, "ok", "${items.size} open reminders, soonest first: $shown")
    }

    private suspend fun changeReminder(args: JsonObject): String {
        val id = ActionArgs.text(args, "id", 36)
        val title = ActionArgs.text(args, "match_title", 200)
        if (id == null && title == null) return invalid(CHANGE_REMINDER, "id or match_title")
        val organizer = Organizer.get(appContext)
        val matches = organizer.list().filter { if (id != null) it.id == id else it.title.trim().equals(title, ignoreCase = true) }
        if (matches.size > 1) return done(CHANGE_REMINDER, "ambiguous", "error: more than one item has that exact title. Manage the items in WatchGPT's reminders screen; no item was changed.")
        val item = matches.singleOrNull() ?: return done(CHANGE_REMINDER, "missing", "error: reminder not found")
        val selectedId = item.id
        when (ActionArgs.choice(args, "operation", setOf("complete", "snooze", "delete", "edit"))) {
            "complete" -> organizer.complete(selectedId)

            "snooze" -> organizer.snooze(selectedId, ActionArgs.int(args, "minutes", 1..10080) ?: 10)

            // Deleting can't be undone (and syncs to the watch and the calendar): read it back and wait for a yes.
            "delete" -> {
                val confirm = pending ?: return done(CHANGE_REMINDER, "refused", "error: deleting by voice isn't available here; the user can delete it in WatchGPT's reminders screen")
                val proposal = confirm.propose("Delete \"${item.title}\" from WatchGPT on the phone and the watch.") {
                    try {
                        organizer.delete(selectedId)
                        "ok: deleted \"${item.title}\""
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        "error: couldn't delete \"${item.title}\" right now; nothing was changed"
                    }
                }
                // Its own card, so a Send or Call button still on screen from before can't confirm this instead.
                cards?.show(WatchGPTCard.Ask("Delete reminder", item.title, "Delete", null))
                return done(CHANGE_REMINDER, "proposed", proposal)
            }

            "edit" -> {
                val atText = ActionArgs.text(args, "at", 40)
                val at = atText?.let { (ActionArgs.time(it, zone()) as? ActionArgs.When.At)?.time ?: return done(CHANGE_REMINDER, "invalid", "error: at must be a date and time like 2026-09-28T15:00; nothing was changed") }
                if (at != null && !plausible(at)) return done(CHANGE_REMINDER, "invalid", "error: that time is in the past or too far away")
                val repeat = ActionArgs.choice(args, "repeat", REPEATS)?.let { Repeat.valueOf(it.uppercase()) }
                // A new time or repeat starts the item afresh (reopened if it was done); a new title alone doesn't.
                val retimed = (at != null && at.toString() != item.localTime) || (repeat != null && repeat != item.repeat)
                // A month-end series keeps its day (the 31st) through a time-of-day change.
                val redated = (at != null && at.toLocalDate() != LocalDateTime.parse(item.localTime).toLocalDate()) || (repeat != null && repeat != item.repeat)
                organizer.save(
                    item.copy(
                        title = ActionArgs.text(args, "title", 200) ?: item.title,
                        notes = ActionArgs.text(args, "notes", 8000) ?: item.notes,
                        localTime = at?.toString() ?: item.localTime,
                        repeat = repeat ?: item.repeat,
                        monthlyDay = if (redated) null else item.monthlyDay,
                        snoozeUntil = if (retimed) null else item.snoozeUntil,
                        completed = item.completed && !retimed,
                    ),
                )
            }

            else -> return invalid(CHANGE_REMINDER, "operation")
        }
        return done(CHANGE_REMINDER, "ok", "ok: reminder updated locally; device sync queued")
    }

    private suspend fun listEvents(args: JsonObject): String {
        val today = LocalDate.now(zone())
        val from = ActionArgs.time(ActionArgs.text(args, "from", 40), zone()).startTime() ?: today.atStartOfDay()
        val to = ActionArgs.time(ActionArgs.text(args, "to", 40), zone()).endTime()?.takeIf { it.isAfter(from) } ?: from.toLocalDate().plusDays(1).atStartOfDay()
        if (Duration.between(from, to) > Duration.ofDays(LIST_MAX_DAYS)) return done(LIST_EVENTS, "invalid", "error: ask for at most $LIST_MAX_DAYS days at a time")
        // WatchGPT's own agenda (it needs no calendar access) and the phone's calendar, without WatchGPT's copies twice.
        // The phone's calendar is still read when WatchGPT's own storage can't be.
        val ownItems = try {
            Organizer.get(appContext).list()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            null
        }
        val own = ownItems.orEmpty().flatMap { e ->
            Agenda.occurrences(e, from, to, zone()).map { at ->
                val kind = if (e.kind == Kind.REMINDER) " (reminder)" else ""
                at to if (e.allDay) "${ActionArgs.say(at.toLocalDate())}: ${e.title} (all day)$kind" else "${ActionArgs.say(at)}: ${e.title}$kind"
            }
        }
        val readable = calendarAllowed()
        val phone = if (readable) withContext(Dispatchers.IO) { queryEvents(from, to) } else emptyList()
        val events = (own + phone).sortedBy { it.first }.take(LIST_MAX_EVENTS).map { it.second }
        val unread = (if (readable) "" else " (the phone's own calendar wasn't read: calendar access is off; the user can allow it in the WatchGPT phone app, What WatchGPT can do)") +
            (if (ownItems == null) " (WatchGPT's own reminders couldn't be read right now)" else "")
        if (events.isEmpty()) return done(LIST_EVENTS, "ok", "no events between ${ActionArgs.say(from)} and ${ActionArgs.say(to)}$unread")
        return done(LIST_EVENTS, "ok", events.joinToString(" | ") + unread)
    }

    private suspend fun setTimer(args: JsonObject): String {
        val seconds = ActionArgs.int(args, "seconds", 1..86_400) ?: return done(SET_TIMER, "invalid", "error: seconds must be between 1 and 86400")
        val clock = clockFor(args) ?: return done(SET_TIMER, "invalid", NO_WATCH)
        val label = ActionArgs.text(args, "label", TITLE_MAX)
        return outcome(SET_TIMER, clock.setTimer(seconds, label)) { WatchGPTCard.Done(Symbol.TIMER, "Timer · ${length(seconds)}", label) }
    }

    private suspend fun setAlarm(args: JsonObject): String {
        val hour = ActionArgs.int(args, "hour", 0..23) ?: return done(SET_ALARM, "invalid", "error: hour must be 0 to 23")
        val minute = ActionArgs.int(args, "minute", 0..59) ?: 0
        val clock = clockFor(args) ?: return done(SET_ALARM, "invalid", NO_WATCH)
        val label = ActionArgs.text(args, "label", TITLE_MAX)
        val rawDays = args["days"] as? JsonArray
        val days = rawDays?.map { (it as? JsonPrimitive)?.intOrNull ?: return invalid(SET_ALARM, "days") }.orEmpty()
        if (days.size > 7 || days.any { it !in 1..7 }) return invalid(SET_ALARM, "days")
        return outcome(SET_ALARM, clock.setAlarm(hour, minute, label, days)) { WatchGPTCard.Done(Symbol.ALARM, "Alarm · %02d:%02d".format(hour, minute), label) }
    }

    /** Where a reminder alerts: where the user said, else both (see insertEvent). */
    private fun alertDevice(args: JsonObject): AlertDevice = when (ActionArgs.choice(args, "on", REMINDER_PLACES)) {
        "watch" -> AlertDevice.WATCH
        "phone" -> AlertDevice.PHONE
        else -> AlertDevice.BOTH
    }

    /** Where the user said ("on my phone"), else the device they talk through; null if they asked for a watch that isn't in the conversation. */
    private fun clockFor(args: JsonObject): Clock? = when (ActionArgs.choice(args, "on", PLACES)) {
        "phone" -> phoneClock
        "watch" -> watch
        else -> watch ?: phoneClock
    }

    private fun setVolume(args: JsonObject): String {
        val level = ActionArgs.int(args, "level", 0..100)
        val change = ActionArgs.choice(args, "change", setOf("up", "down"))
        if (level == null && change == null) return done(SET_VOLUME, "invalid", "error: give a level from 0 to 100, or change up or down")
        return outcome(SET_VOLUME, controls.volume(level, change?.let { it == "up" })) { WatchGPTCard.Done(Symbol.MUSIC, "Volume", level?.let { "$it%" } ?: change) }
    }

    /** [card] shows on the phone when it worked. */
    private fun outcome(
        tool: String,
        result: String,
        card: (() -> WatchGPTCard)? = null,
    ): String {
        if (result.startsWith("ok:")) card?.let { cards?.show(it()) }
        val status = when {
            result.startsWith("error") -> "failed"
            result.startsWith("not done yet") -> "proposed"
            else -> "ok"
        }
        return done(tool, status, result)
    }

    /** "10 min", "1 h 30 min", "45 s". */
    private fun length(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 3_600 == 0 -> "${seconds / 3_600} h"
        seconds >= 3_600 -> "${seconds / 3_600} h ${seconds % 3_600 / 60} min"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }

    private fun invalid(
        tool: String,
        key: String,
    ) = done(tool, "invalid", "error: $key is missing or not one of the allowed values")

    private suspend fun insertEvent(
        title: String,
        start: LocalDateTime,
        end: LocalDateTime,
        allDay: Boolean,
        location: String?,
        details: String?,
        alertMinutes: Int?,
    ) = withContext(Dispatchers.IO) {
        Organizer.get(appContext).save(
            Entry(
                title = title, allDay = allDay, alertMinutesBefore = alertMinutes ?: 0,
                notes = listOfNotNull(details, location?.let { "Location: $it" }).joinToString("\n"),
                localTime = start.toString(), zone = zone().id, kind = Kind.EVENT,
                durationMinutes = Duration.between(start, end).toMinutes().toInt().coerceIn(1, 10080),
                // Both, like the calendar reminders this replaced (phone, mirrored to the watch): a watch off the
                // wrist or charging mustn't swallow it. Done on either clears the other once they sync.
                alertDevice = AlertDevice.BOTH,
            ),
        )
    }

    private fun queryEvents(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Pair<LocalDateTime, String>> {
        val begin = from.atZone(zone()).toInstant().toEpochMilli()
        val end = to.atZone(zone()).toInstant().toEpochMilli()
        val uri =
            CalendarContract.Instances.CONTENT_URI
                .buildUpon()
                .appendPath(begin.toString())
                .appendPath(end.toString())
                .build()
        val projection =
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
            )
        val out = mutableListOf<Pair<LocalDateTime, String>>()
        appContext.contentResolver
            .query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { c ->
                while (c.moveToNext() && out.size < LIST_MAX_EVENTS) {
                    // WatchGPT's own copies are listed from WatchGPT itself.
                    if (c.getString(5)?.contains("[WatchGPT:") == true) continue
                    val title = c.getString(0)?.take(TITLE_MAX) ?: "(no title)"
                    val allDay = c.getInt(3) == 1
                    val startsAt =
                        Instant
                            .ofEpochMilli(c.getLong(1))
                            .atZone(if (allDay) ZoneOffset.UTC else zone())
                            .toLocalDateTime()
                    val place = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " at ${it.take(TITLE_MAX)}" }.orEmpty()
                    out += startsAt to if (allDay) "${ActionArgs.say(startsAt.toLocalDate())}: $title (all day)$place" else "${ActionArgs.say(startsAt)}: $title$place"
                }
            }
        return out
    }

    private fun plausible(time: LocalDateTime): Boolean {
        val now = LocalDateTime.now(zone())
        return time.isAfter(now.minusHours(1)) && time.isBefore(now.plusYears(2))
    }

    private fun ActionArgs.When?.startTime(): LocalDateTime? = when (this) {
        is ActionArgs.When.At -> time
        is ActionArgs.When.Day -> date.atStartOfDay()
        null -> null
    }

    private fun ActionArgs.When?.endTime(): LocalDateTime? = when (this) {
        is ActionArgs.When.At -> time
        is ActionArgs.When.Day -> date.plusDays(1).atStartOfDay()
        null -> null
    }

    private fun done(
        tool: String,
        outcome: String,
        result: String,
    ): String {
        logger.log(LogEvent.ToolUsed(tool, outcome))
        return result
    }

    companion object {
        const val ADD_NOTE = "add_note"
        const val LIST_NOTES = "list_notes"
        const val ADD_EVENT = "add_calendar_event"
        const val LIST_EVENTS = "list_calendar_events"
        const val ADD_REMINDER = "add_reminder"
        const val LIST_REMINDERS = "list_reminders"
        const val CHANGE_REMINDER = "change_reminder"
        const val SET_TIMER = "set_timer"
        const val SET_ALARM = "set_alarm"
        const val RING_PHONE = "ring_phone"
        const val STOP_RINGING = "stop_ringing"
        const val MEDIA = "media_control"
        const val SET_VOLUME = "set_volume"
        const val SET_RINGER = "set_ringer"
        const val DO_NOT_DISTURB = "do_not_disturb"
        const val DEVICE_STATUS = "device_status"

        val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        private const val NOTE_MAX = 1_000
        private const val TITLE_MAX = 200
        private const val LIST_MAX_DAYS = 31L
        private const val LIST_MAX_EVENTS = 15
        private const val RING_SECONDS = 30
        private val PLACES = setOf("watch", "phone")
        private val REPEATS = setOf("none", "daily", "weekdays", "weekly", "monthly")
        private val REMINDER_PLACES = setOf("watch", "phone", "both")
        private const val LIST_MAX_REMINDERS = 15
        private val MEDIA_TITLES = mapOf("play" to "Playing", "pause" to "Paused", "next" to "Next track", "previous" to "Previous track")
        private const val NO_WATCH = "error: timers and alarms only go on the watch while the user talks through it; offer the phone instead"

        private const val LOCAL_TIME = "Local date and time without a zone, like 2026-09-28T15:00, or just a date for all day."
        private const val ON =
            """"on":{"type":"string","enum":["watch","phone"],"description":"Only when the user says where; otherwise leave it out and it goes to the device they are talking through."}"""
        private const val NO_ARGS = """{"type":"object","properties":{},"additionalProperties":false}"""

        val SPECS =
            listOf(
                ToolSpec(
                    ADD_NOTE,
                    "Save a note for the user in their WatchGPT notes (use for 'note that…', 'add … to my notes', shopping items, ideas).",
                    """{"type":"object","properties":{"text":{"type":"string","description":"The note, in the user's words."}},"required":["text"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    LIST_NOTES,
                    "Read the user's newest notes.",
                    """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":20},"instruction":{"type":"string","description":"In English: exactly what the phone should find in it and tell the user."},"language":{"type":"string","description":"The language the user is speaking, as a BCP 47 tag: the phone answers and speaks in it."},"details":{"type":"boolean","description":"True only when the user asked for a code, a number or a link itself."}},"additionalProperties":false}""",
                ),
                ToolSpec(
                    ADD_EVENT,
                    "Create a WatchGPT agenda event, kept on the phone and the watch (copied to Google Calendar only if the user turned that on). It alerts on both at its start, or alert_minutes_before; date-only events are all day and alert at 9:00 that day.",
                    """{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string","description":"$LOCAL_TIME"},"end":{"type":"string","description":"$LOCAL_TIME Optional."},"duration_minutes":{"type":"integer","minimum":5,"maximum":1440,"description":"Used when there is no end. Default 60."},"location":{"type":"string"},"notes":{"type":"string"},"alert_minutes_before":{"type":"integer","minimum":0,"maximum":10080}},"required":["title","start"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    LIST_EVENTS,
                    "Read the user's agenda between two times (at most 31 days): WatchGPT's events and reminders and the phone's calendar. Defaults to today.",
                    """{"type":"object","properties":{"from":{"type":"string","description":"$LOCAL_TIME"},"to":{"type":"string","description":"$LOCAL_TIME"},"instruction":{"type":"string","description":"In English: exactly what the phone should find in it and tell the user."},"language":{"type":"string","description":"The language the user is speaking, as a BCP 47 tag: the phone answers and speaks in it."},"details":{"type":"boolean","description":"True only when the user asked for a code, a number or a link itself."}},"additionalProperties":false}""",
                ),
                ToolSpec(
                    ADD_REMINDER,
                    "Create a WatchGPT reminder, optionally repeating. It works offline and alerts on the phone and the watch unless the user says only one of them. No Google Calendar needed.",
                    """{"type":"object","properties":{"text":{"type":"string"},"at":{"type":"string","description":"Local date and time without a zone, like 2026-09-28T15:00."},"notes":{"type":"string"},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly","monthly"]},"on":{"type":"string","enum":["watch","phone","both"],"description":"Only when the user says where; otherwise leave it out and it alerts on both."}},"required":["text","at"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    LIST_REMINDERS,
                    "Read the user's open WatchGPT reminders, soonest first.",
                    """{"type":"object","properties":{"instruction":{"type":"string"},"language":{"type":"string"},"details":{"type":"boolean"}},"additionalProperties":false}""",
                ),
                ToolSpec(
                    CHANGE_REMINDER,
                    "Complete, snooze, edit or delete a WatchGPT reminder or agenda item by the exact title the user said (match_title). Two items with that title are refused; never guess a title. Delete only when the user asks: it is read back and waits for their yes.",
                    """{"type":"object","properties":{"match_title":{"type":"string","description":"The item's exact existing title, as the user said it."},"id":{"type":"string","description":"Rarely needed: an item ID WatchGPT gave you."},"operation":{"type":"string","enum":["complete","snooze","delete","edit"]},"minutes":{"type":"integer","minimum":1,"maximum":10080,"description":"For snooze; default 10."},"title":{"type":"string","description":"For edit: the new title."},"notes":{"type":"string","description":"For edit: the new notes."},"at":{"type":"string","description":"For edit: the new local date and time, like 2026-09-28T15:00."},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly","monthly"]}},"required":["operation"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_TIMER,
                    "Start a countdown timer for the length the user said; never guess one (if they didn't say how long, ask). " +
                        "It rings on the device the user is talking through (their watch, or the phone) unless they say where.",
                    """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1,"maximum":86400},"label":{"type":"string"},$ON},"required":["seconds"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_ALARM,
                    "Set an alarm for the next time it is this hour and minute; never guess a time (if the user didn't say one, ask). " +
                        "It is set on the device the user is talking through (their watch, or the phone) unless they say where.",
                    """{"type":"object","properties":{"hour":{"type":"integer","minimum":0,"maximum":23},"minute":{"type":"integer","minimum":0,"maximum":59},"days":{"type":"array","items":{"type":"integer","minimum":1,"maximum":7},"description":"Repeat weekdays using Android Calendar: 1 Sunday, 2 Monday, through 7 Saturday. Omit for one alarm."},"label":{"type":"string"},$ON},"required":["hour"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    RING_PHONE,
                    "Ring the phone loudly, even on silent, so the user can find it ('where's my phone?'). " +
                        "It stops by itself, from its notification, or with stop_ringing.",
                    """{"type":"object","properties":{"seconds":{"type":"integer","minimum":5,"maximum":60,"description":"Default 30."}},"additionalProperties":false}""",
                ),
                ToolSpec(STOP_RINGING, "Stop the phone ringing (after ring_phone).", NO_ARGS),
                ToolSpec(
                    MEDIA,
                    "Control what plays on the phone, also in the user's earbuds (music, podcasts, videos): play, pause, next or previous. " +
                        "Only when the user asks for exactly that; never for the volume.",
                    """{"type":"object","properties":{"command":{"type":"string","enum":["play","pause","next","previous"]}},"required":["command"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_VOLUME,
                    "Set the phone's media volume (music, videos, the user's earbuds): a level in percent, or one step up or down. " +
                        "Not how loud your own voice is on the watch's speaker: the user sets that by turning the watch's bezel (or crown) while you talk.",
                    """{"type":"object","properties":{"level":{"type":"integer","minimum":0,"maximum":100},"change":{"type":"string","enum":["up","down"]}},"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_RINGER,
                    "Switch the phone's ringer for calls and notifications: normal (sound on), vibrate or silent.",
                    """{"type":"object","properties":{"mode":{"type":"string","enum":["normal","vibrate","silent"]}},"required":["mode"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    DO_NOT_DISTURB,
                    "Turn Do Not Disturb on the phone on or off (a Galaxy watch usually follows the phone).",
                    """{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    DEVICE_STATUS,
                    "Battery of the phone (and of the watch while the user talks through it) and whether it is charging, " +
                        "plus the phone's ringer and Do Not Disturb.",
                    NO_ARGS,
                ),
            )
    }
}
