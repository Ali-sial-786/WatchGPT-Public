package io.github.alisial786.watchgpt.organizer

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alisial786.watchgpt.reminders.Agenda
import io.github.alisial786.watchgpt.reminders.AlertDevice
import io.github.alisial786.watchgpt.reminders.Alerts
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.EntryCodec
import io.github.alisial786.watchgpt.reminders.Kind
import io.github.alisial786.watchgpt.reminders.Repeat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class OrganizerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val organizer = Organizer.get(this)
        val watch = organizer.isWatch || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("watch_preview", false))
        if (watch) WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            val dark = watch || androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color.White, onPrimary = Color.Black, background = Color(0xFF0B0B0D), surface = Color(0xFF17171A), surfaceContainerHighest = Color(0xFF17171A), secondaryContainer = Color(0xFF29292D), onSecondaryContainer = Color.White) else lightColorScheme(primary = Color(0xFF0A0A0B), onPrimary = Color.White, background = Color.White, surface = Color(0xFFF4F4F5), surfaceContainerHighest = Color(0xFFF4F4F5), secondaryContainer = Color(0xFFEAEAEA), onSecondaryContainer = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { OrganizerPanel(organizer, onClose = ::finish, watch = watch) }
            }
        }
    }
    companion object {
        fun open(context: Context, watchPreview: Boolean = false) {
            context.startActivity(Intent(context, OrganizerActivity::class.java).putExtra("watch_preview", watchPreview))
        }
    }
}

/** Dates and times the way this device shows them: its 12- or 24-hour setting and its language. */
internal class TimeFormat(context: Context) {
    private val time = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
    private val day = DateTimeFormatter.ofPattern("EEE d MMM")
    val is24Hour = DateFormat.is24HourFormat(context)
    fun time(at: LocalDateTime): String = at.format(time)
    fun day(at: LocalDateTime): String = at.format(day)
    fun dateYear(at: LocalDateTime): String = at.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))
    fun dateTime(at: LocalDateTime, allDay: Boolean): String = if (allDay) "${day(at)} · all day" else "${day(at)} · ${time(at)}"
}

private fun snoozedUntil(entry: Entry, now: Instant): Instant? = entry.snoozeUntil?.let(Instant::ofEpochMilli)?.takeIf { it > now && !entry.completed }

@Composable
fun OrganizerPanel(organizer: Organizer, onClose: () -> Unit, watch: Boolean = organizer.isWatch) {
    val entries by organizer.entries.collectAsStateWithLifecycle()
    val status by organizer.status.collectAsStateWithLifecycle()
    val calendarStatus by organizer.calendar.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val format = remember(context) { TimeFormat(context) }
    val peer = if (watch) "phone" else "watch"

    // The item as it was when its editor opened (as JSON, to survive rotation). Saving a version that another device
    // replaced meanwhile is refused instead of silently overwriting it.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var calendarPage by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("Reminders") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Entry?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(organizer.notificationsAllowed()) }
    var calendarPermission by remember { mutableStateOf(organizer.calendar.permitted()) }
    var exact by remember { mutableStateOf(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) }
    // Kept while the editor is open, so coming back lands where the list was.
    val listScroll = rememberScrollState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notifications = organizer.notificationsAllowed()
        calendarPermission = organizer.calendar.permitted()
        organizer.requestSync()
    }
    // Reminders need notifications: ask once, the first time the screen opens (on the watch nothing else may have).
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && !notifications && Build.VERSION.SDK_INT >= 33) permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        asked = true
    }
    fun open(intent: Intent) {
        // Settings screens differ between devices (and some watches have none for this).
        if (runCatching { context.startActivity(intent) }.isFailure) error = "This device can't open that setting. Open it from its Settings app."
    }
    LifecycleResumeEffect(Unit) {
        notifications = organizer.notificationsAllowed()
        calendarPermission = organizer.calendar.permitted()
        exact = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        organizer.requestSync()
        onPauseOrDispose { }
    }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not save. Your existing items are safe."
            }
        }
    }
    val original = editing?.let { runCatching { EntryCodec.decode(it) }.getOrNull() }
    BackHandler(creating || editing != null || calendarPage) {
        creating = false
        editing = null
        calendarPage = false
        error = null
    }
    @Composable
    fun DeleteDialog() {
        confirmDelete?.let { entry ->
            AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete this item?") }, text = { Text("${entry.title}\nThe deletion syncs to your $peer and removes its calendar copy.") }, confirmButton = {
                TextButton(onClick = {
                    run { organizer.delete(entry.id) }
                    confirmDelete = null
                    editing = null
                }) { Text("Delete") }
            }, dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
        }
    }
    if (creating || original != null) {
        val current = original?.let { o -> entries.firstOrNull { it.id == o.id } }
        val changedElsewhere = when {
            original == null -> null
            current == null -> "Deleted on your $peer while you were editing."
            current.revision != original.revision || current.author != original.author -> "Changed on your $peer while you were editing. Reload to see it; saving this version is refused."
            else -> null
        }
        EntryEditor(
            original = original,
            watch = watch,
            format = format,
            externalError = error,
            saving = saving,
            changedElsewhere = changedElsewhere,
            onReload = {
                editing = current?.let(EntryCodec::encode)
                error = null
            },
            onDelete = current?.let { { confirmDelete = it } },
            onCancel = {
                creating = false
                editing = null
                error = null
            },
            onSave = { value ->
                saving = true
                run {
                    try {
                        organizer.save(value)
                        creating = false
                        editing = null
                    } finally {
                        saving = false
                    }
                }
            },
        )
        DeleteDialog()
        return
    }
    val padding = if (watch) 30.dp else 20.dp
    ScrollingColumn(watch, listScroll, Modifier.fillMaxSize().padding(horizontal = padding).padding(top = if (watch) 32.dp else 48.dp, bottom = if (watch) 40.dp else 32.dp), horizontalAlignment = if (watch) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The watch goes back with a swipe; its first screen is for the reminders themselves.
        if (!watch || calendarPage) TextButton(onClick = if (calendarPage) ({ calendarPage = false }) else onClose) { Text("‹ WatchGPT") }
        if (!watch || calendarPage) Text(if (calendarPage) "Calendar sync" else "Your day", style = if (watch) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        if (calendarPage) {
            CalendarPage(organizer, calendarStatus, calendarPermission, format, onAllow = { permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }, onError = { error = it }, run = ::run)
        } else {
            if (watch) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { tab = "Reminders" }, modifier = Modifier.semantics { selected = tab == "Reminders" }) { Text("Tasks", fontWeight = if (tab == "Reminders") FontWeight.Bold else FontWeight.Normal) }
                    TextButton(onClick = { tab = "Agenda" }, modifier = Modifier.semantics { selected = tab == "Agenda" }) { Text("Agenda", fontWeight = if (tab == "Agenda") FontWeight.Bold else FontWeight.Normal) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Reminders", "Agenda", "Done").forEach { name -> FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) }) } }
            }
            // Up front: without these, everything below can't alert.
            if (!notifications) {
                Text("Reminder notifications are off. Saved reminders cannot alert you.", color = MaterialTheme.colorScheme.error, textAlign = if (watch) TextAlign.Center else TextAlign.Start)
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !asked) {
                        permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else {
                        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }) { Text("Enable alerts") }
            }
            if (!exact) {
                Text("Precise timing is off. Android may delay alerts.", style = MaterialTheme.typography.bodySmall, textAlign = if (watch) TextAlign.Center else TextAlign.Start)
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("Allow precise alerts") }
            }
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ New ${if (tab == "Agenda") "item" else "reminder"}") }
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val visible = entries.filter { if (tab == "Done") it.completed else !it.completed && (tab == "Agenda" || it.kind == Kind.REMINDER) }
                .sortedBy { snoozedUntil(it, now) ?: Agenda.nextShown(it, now, zone).atZone(zone).toInstant() }
            val loading = status.startsWith("Loading") || status.startsWith("Local storage unavailable")
            if (loading && visible.isEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium, textAlign = if (watch) TextAlign.Center else TextAlign.Start)
            if (!loading && visible.isEmpty()) {
                Text(if (tab == "Done") "Nothing completed yet" else "A little space in your day", style = MaterialTheme.typography.titleMedium, textAlign = if (watch) TextAlign.Center else TextAlign.Start)
                Text("Create a reminder here. It stays on this device and syncs when your $peer reconnects.", style = MaterialTheme.typography.bodySmall, textAlign = if (watch) TextAlign.Center else TextAlign.Start)
            }
            visible.forEach { entry ->
                EntryCard(
                    entry = entry,
                    now = now,
                    zone = zone,
                    watch = watch,
                    format = format,
                    onDone = { run { organizer.complete(entry.id) } },
                    onEdit = {
                        error = null
                        editing = EntryCodec.encode(entry)
                    },
                    onSnooze = { run { organizer.snooze(entry.id) } },
                    onDelete = { confirmDelete = entry },
                )
            }
            if (watch) TextButton(onClick = { tab = if (tab == "Done") "Reminders" else "Done" }) { Text(if (tab == "Done") "Show active" else "Completed") }
            HorizontalDivider()
            organizer.lastSync?.let { Text("Last sent: ${format.dateTime(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime(), allDay = false)}", style = MaterialTheme.typography.bodySmall) }
            Text(status, style = MaterialTheme.typography.bodySmall, textAlign = if (watch) TextAlign.Center else TextAlign.Start)
            OutlinedButton(onClick = organizer::syncNow, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
            if (!watch) TextButton(onClick = { calendarPage = true }) { Text("Google Calendar · optional") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    DeleteDialog()
}

@Composable
private fun EntryCard(entry: Entry, now: Instant, zone: ZoneId, watch: Boolean, format: TimeFormat, onDone: () -> Unit, onEdit: () -> Unit, onSnooze: () -> Unit, onDelete: () -> Unit) {
    val shown = Agenda.nextShown(entry, now, zone)
    val overdue = entry.repeat == Repeat.NONE && Agenda.overdue(entry, now, zone) && snoozedUntil(entry, now) == null
    // Snooze is offered while an alert is due, the moment it is useful; earlier it would only move the reminder.
    val dueNow = !entry.completed && Alerts.due(entry, now, zone)?.at?.let { it <= now } == true
    val align = if (watch) TextAlign.Center else TextAlign.Start
    // On the watch a card is a tap target of its own (it opens the item) with only the buttons that matter now.
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(if (watch) 12.dp else 18.dp), horizontalAlignment = if (watch) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, textAlign = align)
            Text(
                (
                    if (overdue) {
                        "Overdue · "
                    } else if (entry.repeat != Repeat.NONE && !entry.completed) {
                        "Next · "
                    } else {
                        ""
                    }
                    ) + format.dateTime(shown, entry.allDay),
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else Color.Unspecified,
                textAlign = align,
            )
            Text(if (watch) listOfNotNull(entry.repeat.label().takeIf { entry.repeat != Repeat.NONE }, entry.alertDevice.label().takeIf { entry.alertDevice != AlertDevice.WATCH }?.let { "alerts: ${it.lowercase()}" }).joinToString(" · ") else listOfNotNull(entry.kind.label(), entry.repeat.label().takeIf { entry.repeat != Repeat.NONE }, "alerts on ${entry.alertDevice.label().lowercase()}").joinToString(" · "), style = MaterialTheme.typography.labelSmall, textAlign = align)
            snoozedUntil(entry, now)?.let { Text("Snoozed until ${format.time(it.atZone(zone).toLocalDateTime())}", style = MaterialTheme.typography.bodySmall, textAlign = align) }
            if (entry.notes.isNotBlank() && !watch) Text(entry.notes, style = MaterialTheme.typography.bodySmall, textAlign = align, maxLines = 6)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, if (watch) Alignment.CenterHorizontally else Alignment.Start)) {
                // Each button says which item it acts on: a list of identical "Done"s is useless to a screen reader.
                if (!entry.completed) TextButton(onClick = onDone, modifier = Modifier.semantics { contentDescription = "Done: ${entry.title}" }) { Text("Done") }
                if (!watch) TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "Edit ${entry.title}" }) { Text("Edit") }
                if (dueNow) TextButton(onClick = onSnooze, modifier = Modifier.semantics { contentDescription = "Snooze ${entry.title} for 10 minutes" }) { Text(if (watch) "Snooze" else "Snooze 10 min") }
                if (!watch) TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Delete ${entry.title}" }) { Text("Delete") }
            }
        }
    }
    if (watch) {
        Card(onClick = onEdit, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { body() }
    } else {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { body() }
    }
}

@Composable
private fun CalendarPage(organizer: Organizer, calendarStatus: String, calendarPermission: Boolean, format: TimeFormat, onAllow: () -> Unit, onError: (String) -> Unit, run: (suspend () -> Unit) -> Unit) {
    var calendars by remember { mutableStateOf<List<CalendarSync.Calendar>>(emptyList()) }
    var calendarLoading by remember { mutableStateOf(true) }
    var selectedCalendar by remember { mutableStateOf(organizer.calendar.selected) }
    LaunchedEffect(calendarStatus, calendarPermission) {
        calendarLoading = true
        try {
            calendars = organizer.calendar.calendars()
        } catch (_: Exception) {
            calendars = emptyList()
            onError("Calendars are unavailable. Check calendar permission and try again.")
        } finally {
            calendarLoading = false
        }
    }
    Text("Optional. Your reminders work without Google. Calendar copies are events, not Google Tasks.")
    Text("Only WatchGPT-created events are managed. Titles and notes are copied to the calendar you choose, as private events; calendar alerts are off so you aren't reminded twice.", style = MaterialTheme.typography.bodySmall)
    Text(calendarStatus)
    organizer.calendar.lastSync?.let { Text("Last provider sync: ${format.dateTime(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime(), allDay = false)}", style = MaterialTheme.typography.bodySmall) }
    if (!calendarPermission) Button(onClick = onAllow) { Text("Allow calendar access") }
    if (calendarLoading) Text("Loading calendars…")
    if (!calendarLoading && calendars.isEmpty()) Text("No Google calendar of yours found. Add your Google account in the phone's settings and turn on Calendar sync. Calendars shared with you by others aren't offered.")
    calendars.forEach { calendar ->
        val choose = {
            run {
                organizer.calendar.select(calendar.id)
                selectedCalendar = calendar.id
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selectedCalendar == calendar.id, role = Role.RadioButton, onClick = choose), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selectedCalendar == calendar.id, onClick = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(calendar.name)
                Text(calendar.account, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    OutlinedButton(onClick = {
        run {
            organizer.calendar.select(null)
            selectedCalendar = null
        }
    }) { Text("Turn sync off") }
    Text("Turning sync off keeps existing calendar copies. Concurrent changes to the same item keep the WatchGPT version. Deleting a copy in Calendar deletes its item.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EntryEditor(original: Entry?, watch: Boolean, format: TimeFormat, externalError: String?, saving: Boolean, changedElsewhere: String?, onReload: () -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit, onSave: (Entry) -> Unit) {
    // Keyed on the version being edited, so "Reload" shows the newer one.
    val key = "${original?.id}:${original?.revision}:${original?.author}"
    var title by rememberSaveable(key) { mutableStateOf(original?.title.orEmpty()) }
    var notes by rememberSaveable(key) { mutableStateOf(original?.notes.orEmpty()) }
    var local by rememberSaveable(key) { mutableStateOf(original?.localTime ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0).toString()) }
    var repeat by rememberSaveable(key) { mutableStateOf(original?.repeat ?: Repeat.NONE) }
    var kind by rememberSaveable(key) { mutableStateOf(original?.kind ?: Kind.REMINDER) }
    var alert by rememberSaveable(key) { mutableStateOf(original?.alertDevice ?: if (watch) AlertDevice.WATCH else AlertDevice.PHONE) }
    var allDay by rememberSaveable(key) { mutableStateOf(original?.allDay ?: false) }
    var lead by rememberSaveable(key) { mutableStateOf((original?.alertMinutesBefore ?: 0).toString()) }
    var duration by rememberSaveable(key) { mutableStateOf((original?.durationMinutes ?: 30).toString()) }
    var follows by rememberSaveable(key) { mutableStateOf(original?.followsDeviceZone ?: true) }
    var error by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var picker by rememberSaveable(key) { mutableStateOf<String?>(null) }
    // One id per new item, so a double tap can't save two copies.
    val newId = rememberSaveable(key) { UUID.randomUUID().toString() }
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { title = it.take(200) }
    }
    val time = LocalDateTime.parse(local)
    if (watch && picker != null) {
        WatchDateTimePicker(time, picker == "date", format, onCancel = { picker = null }, onSave = {
            local = it.toString()
            picker = null
        })
        return
    }
    val align = if (watch) TextAlign.Center else TextAlign.Start
    ScrollingColumn(watch, scroll, Modifier.fillMaxSize().padding(horizontal = if (watch) 28.dp else 20.dp).padding(top = if (watch) 32.dp else 48.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = if (watch) Alignment.CenterHorizontally else Alignment.Start) {
        TextButton(onClick = onCancel) { Text("‹ Cancel") }
        Text(if (original == null) "New reminder" else "Edit item", style = MaterialTheme.typography.titleLarge)
        changedElsewhere?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = align)
            OutlinedButton(onClick = onReload) { Text(if (it.startsWith("Deleted")) "Close" else "Reload") }
        }
        OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (watch && Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null) {
            OutlinedButton(onClick = { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the title")) }, modifier = Modifier.fillMaxWidth()) { Text("Speak title") }
        }
        OutlinedTextField(notes, { notes = it.take(8000) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), maxLines = if (watch) 3 else 5)
        TextButton(onClick = { kind = if (kind == Kind.REMINDER) Kind.EVENT else Kind.REMINDER }) { Text("Type: ${kind.label().lowercase()}") }
        OutlinedButton(onClick = {
            if (watch) picker = "date" else DatePickerDialog(context, { _, y, m, d -> local = time.withDayOfMonth(1).withYear(y).withMonth(m + 1).withDayOfMonth(d).toString() }, time.year, time.monthValue - 1, time.dayOfMonth).show()
        }, modifier = Modifier.fillMaxWidth()) { Text(format.dateYear(time)) }
        if (!(kind == Kind.EVENT && allDay)) {
            OutlinedButton(onClick = {
                if (watch) picker = "time" else TimePickerDialog(context, { _, h, m -> local = time.withHour(h).withMinute(m).toString() }, time.hour, time.minute, format.is24Hour).show()
            }, modifier = Modifier.fillMaxWidth()) { Text(format.time(time)) }
            // One tap for the usual times, which is most of what a watch is for.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, if (watch) Alignment.CenterHorizontally else Alignment.Start)) {
                val now = LocalDateTime.now().withSecond(0).withNano(0)
                listOf("In 15 min" to now.plusMinutes(15), "In 1 hour" to now.plusHours(1), "Tomorrow ${format.time(now.withHour(9).withMinute(0))}" to now.plusDays(1).withHour(9).withMinute(0)).forEach { (label, at) ->
                    TextButton(onClick = { local = at.toString() }) { Text(label) }
                }
            }
        }
        if (kind == Kind.EVENT) {
            Row(Modifier.toggleable(value = allDay, role = Role.Checkbox, onValueChange = { allDay = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allDay, null)
                Text("All day")
            }
            if (allDay) Text("All-day items alert at ${format.time(time.with(Alerts.ALL_DAY_AT))} on the day.", style = MaterialTheme.typography.bodySmall, textAlign = align)
            OutlinedTextField(lead, { lead = it.filter(Char::isDigit).take(5) }, label = { Text("Alert minutes before") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Text("Repeat", style = MaterialTheme.typography.titleSmall)
        val chips = if (watch) Alignment.CenterHorizontally else Alignment.Start
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, chips)) {
            Repeat.entries.forEach { option -> FilterChip(selected = repeat == option, onClick = { repeat = option }, label = { Text(option.label()) }) }
        }
        Text("Alert on", style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, chips)) {
            AlertDevice.entries.forEach { option -> FilterChip(selected = alert == option, onClick = { alert = option }, label = { Text(option.label()) }) }
        }
        Text("Only the selected device alerts. Sync must reach it first; no automatic duplicate alerts on the other device.", style = MaterialTheme.typography.bodySmall, textAlign = align)
        if (kind == Kind.EVENT && !allDay) OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(Modifier.toggleable(value = follows, role = Role.Checkbox, onValueChange = { follows = it }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(follows, null)
            Text("Keep local time when travelling", style = MaterialTheme.typography.bodySmall)
        }
        if (original?.completed == true) Text("Saving this item reopens it.")
        (error ?: externalError)?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = align) }
        Button(onClick = {
            val minutes = duration.toIntOrNull()
            val before = lead.toIntOrNull() ?: 0
            error = when {
                title.isBlank() -> "Add a title."
                kind == Kind.EVENT && !allDay && (minutes == null || minutes !in 1..10080) -> "Minutes must be between 1 and 10080 (a week)."
                kind == Kind.EVENT && before !in 0..10080 -> "An alert can be at most a week (10080 minutes) before."
                else -> null
            }
            if (error != null) return@Button
            val retimed = original == null || original.localTime != local || original.repeat != repeat || original.allDay != (kind == Kind.EVENT && allDay)
            // A month-end series keeps its day (the 31st) through time-of-day changes; a new date or repeat restarts it.
            val redated = original == null || LocalDateTime.parse(original.localTime).toLocalDate() != time.toLocalDate() || original.repeat != repeat
            val entry = (original ?: Entry(id = newId, title = title, localTime = local)).copy(
                title = title.trim(),
                notes = notes,
                localTime = if (kind == Kind.EVENT && allDay) time.toLocalDate().atStartOfDay().toString() else local,
                monthlyDay = if (redated) null else original.monthlyDay,
                repeat = repeat,
                kind = kind,
                allDay = kind == Kind.EVENT && allDay,
                alertMinutesBefore = if (kind == Kind.EVENT) before else 0,
                durationMinutes = if (kind == Kind.EVENT && allDay) 1440 else minutes?.takeIf { it in 1..10080 } ?: 30,
                alertDevice = alert,
                followsDeviceZone = follows,
                completed = false,
                snoozeUntil = if (retimed) null else original.snoozeUntil,
            )
            try {
                onSave(entry.validate())
            } catch (_: Exception) {
                error = "That date or time can't be saved. Choose another."
            }
        }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save on this device") }
        onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun WatchDateTimePicker(initial: LocalDateTime, date: Boolean, format: TimeFormat, onCancel: () -> Unit, onSave: (LocalDateTime) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    BackHandler { onCancel() }
    ScrollingColumn(true, rememberScrollState(), Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (date) "Choose date" else "Choose time", style = MaterialTheme.typography.titleMedium)
        Text(if (date) format.dateYear(value) else format.time(value), style = MaterialTheme.typography.titleLarge)
        // Coarse steps: a watch has no keyboard, and nobody wants 30 taps for half an hour.
        val parts: List<Pair<String, (LocalDateTime, Long) -> LocalDateTime>> = if (date) {
            listOf("Day" to { t, d -> t.plusDays(d) }, "Week" to { t, d -> t.plusWeeks(d) }, "Month" to { t, d -> t.plusMonths(d) })
        } else {
            listOf("Hour" to { t, d -> t.plusHours(d) }, "5 minutes" to { t, d -> t.plusMinutes(5 * d) }, "Minute" to { t, d -> t.plusMinutes(d) })
        }
        parts.forEach { (part, step) ->
            Text(part)
            Row {
                listOf(-1L, 1L).forEach { delta ->
                    OutlinedButton(onClick = { value = step(value, delta) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (delta < 0) "−" else "+") }
                }
            }
        }
        if (!date) {
            Row {
                listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0)).forEach { at ->
                    TextButton(onClick = { value = value.with(at) }, modifier = Modifier.weight(1f)) { Text(format.time(value.with(at))) }
                }
            }
        }
        Button(onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth()) { Text("Set") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** A scrolling column; on the watch the bezel or crown scrolls it too. */
@Composable
private fun ScrollingColumn(watch: Boolean, scroll: ScrollState, modifier: Modifier, horizontalAlignment: Alignment.Horizontal, verticalArrangement: Arrangement.Vertical, content: @Composable ColumnScope.() -> Unit) {
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    if (watch) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val rotary = if (watch) {
        Modifier.onRotaryScrollEvent {
            scope.launch { scroll.scrollBy(it.verticalScrollPixels) }
            true
        }.focusRequester(focus).focusable()
    } else {
        Modifier
    }
    Column(modifier.then(rotary).verticalScroll(scroll), horizontalAlignment = horizontalAlignment, verticalArrangement = verticalArrangement, content = content)
}

private fun Kind.label() = if (this == Kind.REMINDER) "Reminder" else "Event"

private fun Repeat.label() = when (this) {
    Repeat.NONE -> "Once"
    Repeat.DAILY -> "Daily"
    Repeat.WEEKDAYS -> "Weekdays"
    Repeat.WEEKLY -> "Weekly"
    Repeat.MONTHLY -> "Monthly"
}

private fun AlertDevice.label() = when (this) {
    AlertDevice.WATCH -> "Watch"
    AlertDevice.PHONE -> "Phone"
    AlertDevice.BOTH -> "Both"
}
