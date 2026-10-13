package io.github.alisial786.watchgpt.organizer

import android.Manifest
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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alisial786.watchgpt.reminders.AlertDevice
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Kind
import io.github.alisial786.watchgpt.reminders.Repeat
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class OrganizerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val organizer = Organizer.get(this)
        setContent {
            val watch = organizer.isWatch || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("watch_preview", false))
            val dark = watch || androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color.White, onPrimary = Color.Black, background = Color(0xFF0B0B0D), surface = Color(0xFF17171A)) else lightColorScheme(primary = Color(0xFF0A0A0B), onPrimary = Color.White, background = Color.White, surface = Color(0xFFF4F4F5))) {
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

@Composable
fun OrganizerPanel(organizer: Organizer, onClose: () -> Unit, watch: Boolean = organizer.isWatch) {
    val entries by organizer.entries.collectAsStateWithLifecycle()
    val status by organizer.status.collectAsStateWithLifecycle()
    val calendarStatus by organizer.calendar.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var calendarPage by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("Reminders") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Entry?>(null) }
    var notifications by remember { mutableStateOf(organizer.notificationsAllowed()) }
    var calendarPermission by remember { mutableStateOf(organizer.calendar.permitted()) }
    var exact by remember { mutableStateOf(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notifications = organizer.notificationsAllowed()
        calendarPermission = organizer.calendar.permitted()
        organizer.requestSync()
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
    val selected = entries.firstOrNull { it.id == selectedId }
    BackHandler(creating || selectedId != null || calendarPage) {
        creating = false
        selectedId = null
        calendarPage = false
    }
    if (creating || (selectedId != null && selected != null)) {
        EntryEditor(selected, watch, error, onCancel = {
            creating = false
            selectedId = null
            error = null
        }, onSave = { value ->
            run {
                organizer.save(value)
                creating = false
                selectedId = null
            }
        })
        return
    }
    val padding = if (watch) 30.dp else 20.dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = padding).padding(top = if (watch) 32.dp else 48.dp, bottom = if (watch) 40.dp else 32.dp), horizontalAlignment = if (watch) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = if (calendarPage) ({ calendarPage = false }) else onClose) { Text("‹ WatchGPT") }
        Text(if (calendarPage) "Calendar sync" else "Your day", style = if (watch) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        if (calendarPage) {
            var calendars by remember { mutableStateOf<List<CalendarSync.Calendar>>(emptyList()) }
            var calendarLoading by remember { mutableStateOf(true) }
            var selectedCalendar by remember { mutableStateOf(organizer.calendar.selected) }
            LaunchedEffect(calendarStatus, calendarPermission) {
                calendarLoading = true
                try {
                    calendars = organizer.calendar.calendars()
                } finally {
                    calendarLoading = false
                }
            }
            Text("Optional. Your reminders work without Google. Calendar copies are events, not Google Tasks.")
            Text("Only WatchGPT-created events are managed. Calendar alerts are off to avoid duplicate reminders.", style = MaterialTheme.typography.bodySmall)
            Text(calendarStatus)
            organizer.calendar.lastSync?.let { Text("Last provider sync: ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}", style = MaterialTheme.typography.bodySmall) }
            if (!calendarPermission) Button(onClick = { permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { Text("Allow calendar access") }
            if (calendarLoading) Text("Loading calendars…")
            if (!calendarLoading && calendars.isEmpty()) Text("No writable Google calendar found. Add a Google account in your phone’s settings and enable Calendar sync.")
            calendars.forEach { calendar ->
                OutlinedButton(onClick = {
                    run {
                        organizer.calendar.select(calendar.id)
                        selectedCalendar = calendar.id
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text((if (selectedCalendar == calendar.id) "✓ " else "") + calendar.name)
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
            Text("Turning sync off keeps existing calendar copies. Concurrent changes to the same item keep the WatchGPT version. Calendar-only deletion removes the linked item after sync.", style = MaterialTheme.typography.bodySmall)
        } else {
            if (watch) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { tab = "Reminders" }) { Text("Tasks") }
                    TextButton(onClick = { tab = "Agenda" }) { Text("Agenda") }
                }
                TextButton(onClick = { tab = if (tab == "Done") "Reminders" else "Done" }) { Text(if (tab == "Done") "Show active" else "Completed") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Reminders", "Agenda", "Done").forEach { name -> FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) }) } }
            }
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ New ${if (tab == "Agenda") "item" else "reminder"}") }
            val visible = entries.filter { if (tab == "Done") it.completed else !it.completed && (tab == "Agenda" || it.kind == Kind.REMINDER) }.sortedBy { it.snoozeUntil ?: it.initialInstant().toEpochMilli() }
            if (visible.isEmpty()) {
                Text(if (tab == "Done") "Nothing completed yet" else "A little space in your day", style = MaterialTheme.typography.titleMedium, textAlign = if (watch) TextAlign.Center else TextAlign.Start)
                Text("Create a reminder here. It stays on this device and syncs when your ${if (watch) "phone" else "watch"} reconnects.", style = MaterialTheme.typography.bodySmall)
            }
            visible.forEach { entry ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(if (watch) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(LocalDateTime.parse(entry.localTime).format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")), style = MaterialTheme.typography.bodySmall)
                        Text("${entry.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${entry.repeat.name.lowercase()} · alerts: ${entry.alertDevice.name.lowercase()}", style = MaterialTheme.typography.labelSmall)
                        entry.snoozeUntil?.let { Text("Snoozed until ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}", style = MaterialTheme.typography.bodySmall) }
                        if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                        if (watch) {
                            if (!entry.completed) OutlinedButton(onClick = { run { organizer.complete(entry.id) } }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                            TextButton(onClick = { selectedId = entry.id }, modifier = Modifier.fillMaxWidth()) { Text("Edit") }
                            if (!entry.completed) TextButton(onClick = { run { organizer.snooze(entry.id) } }, modifier = Modifier.fillMaxWidth()) { Text("Snooze 10 min") }
                            TextButton(onClick = { confirmDelete = entry }, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
                        } else {
                            Row {
                                if (!entry.completed) TextButton(onClick = { run { organizer.complete(entry.id) } }) { Text("Done") }
                                TextButton(onClick = { selectedId = entry.id }) { Text("Edit") }
                                if (!entry.completed) TextButton(onClick = { run { organizer.snooze(entry.id) } }) { Text("Snooze 10 min") }
                                TextButton(onClick = { confirmDelete = entry }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            organizer.lastSync?.let { Text("Last sent: ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}", style = MaterialTheme.typography.bodySmall) }
            Text(status, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = organizer::requestSync, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
            if (!watch) TextButton(onClick = { calendarPage = true }) { Text("Google Calendar · optional") }
            if (!notifications) {
                Text("Reminder notifications are off. Saved reminders cannot alert you.", color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }) { Text("Enable alerts") }
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
            }
            if (!exact) {
                Text("Precise timing is off. Android may delay alerts.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) } }) { Text("Allow precise alerts") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    confirmDelete?.let { entry ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete this item?") }, text = { Text("${entry.title}\nThe deletion syncs to your paired device and its linked calendar copy.") }, confirmButton = {
            TextButton(onClick = {
                run { organizer.delete(entry.id) }
                confirmDelete = null
            }) { Text("Delete") }
        }, dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun EntryEditor(original: Entry?, watch: Boolean, externalError: String?, onCancel: () -> Unit, onSave: (Entry) -> Unit) {
    var title by rememberSaveable(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var notes by rememberSaveable(original?.id) { mutableStateOf(original?.notes.orEmpty()) }
    var local by rememberSaveable(original?.id) { mutableStateOf(original?.localTime ?: LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString()) }
    var repeat by rememberSaveable(original?.id) { mutableStateOf(original?.repeat ?: Repeat.NONE) }
    var kind by rememberSaveable(original?.id) { mutableStateOf(original?.kind ?: Kind.REMINDER) }
    var alert by rememberSaveable(original?.id) { mutableStateOf(original?.alertDevice ?: if (watch) AlertDevice.WATCH else AlertDevice.PHONE) }
    var allDay by rememberSaveable { mutableStateOf(original?.allDay ?: false) }
    var lead by rememberSaveable { mutableStateOf((original?.alertMinutesBefore ?: 0).toString()) }
    var duration by rememberSaveable(original?.id) { mutableStateOf((original?.durationMinutes ?: 30).toString()) }
    var follows by rememberSaveable(original?.id) { mutableStateOf(original?.followsDeviceZone ?: true) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val time = LocalDateTime.parse(local)
    if (watch && picker != null) {
        WatchDateTimePicker(time, picker == "date", onCancel = { picker = null }, onSave = {
            local = it.toString()
            picker = null
        })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (watch) 28.dp else 20.dp).padding(top = if (watch) 32.dp else 48.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = if (watch) Alignment.CenterHorizontally else Alignment.Start) {
        TextButton(onClick = onCancel) { Text("‹ Cancel") }
        Text(if (original == null) "New reminder" else "Edit item", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(notes, { notes = it.take(8000) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), maxLines = if (watch) 3 else 5)
        TextButton(onClick = { kind = if (kind == Kind.REMINDER) Kind.EVENT else Kind.REMINDER }) { Text("Type: ${kind.name.lowercase()}") }
        OutlinedButton(onClick = {
            if (watch) picker = "date" else DatePickerDialog(context, { _, y, m, d -> local = time.withDayOfMonth(1).withYear(y).withMonth(m + 1).withDayOfMonth(d).toString() }, time.year, time.monthValue - 1, time.dayOfMonth).show()
        }, modifier = Modifier.fillMaxWidth()) { Text(time.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) }
        OutlinedButton(onClick = {
            if (watch) picker = "time" else TimePickerDialog(context, { _, h, m -> local = time.withHour(h).withMinute(m).toString() }, time.hour, time.minute, true).show()
        }, modifier = Modifier.fillMaxWidth()) { Text(time.format(DateTimeFormatter.ofPattern("HH:mm"))) }
        if (kind == Kind.EVENT) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allDay, { allDay = it })
                Text("All day")
            }
            OutlinedTextField(lead, { lead = it.filter(Char::isDigit).take(5) }, label = { Text("Alert minutes before") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        Text("Repeat", style = MaterialTheme.typography.titleSmall)
        Repeat.entries.forEach { option -> FilterChip(selected = repeat == option, onClick = { repeat = option }, label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = if (watch) Modifier.fillMaxWidth() else Modifier) }
        Text("Alert on", style = MaterialTheme.typography.titleSmall)
        AlertDevice.entries.forEach { option -> FilterChip(selected = alert == option, onClick = { alert = option }, label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = if (watch) Modifier.fillMaxWidth() else Modifier) }
        Text("Only the selected device alerts. Sync must reach it first; no automatic duplicate alerts on the other device.", style = MaterialTheme.typography.bodySmall)
        if (kind == Kind.EVENT) OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(follows, { follows = it })
            Text("Keep local time when travelling", style = MaterialTheme.typography.bodySmall)
        }
        if (original?.completed == true) Text("Saving this item reopens it.")
        (error ?: externalError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            try {
                val entry = (original ?: Entry(title = title, localTime = local)).copy(title = title.trim(), notes = notes, localTime = if (kind == Kind.EVENT && allDay) time.toLocalDate().atStartOfDay().toString() else local, monthlyDay = if (original?.localTime != local || original.repeat != repeat) null else original.monthlyDay, repeat = repeat, kind = kind, allDay = kind == Kind.EVENT && allDay, alertMinutesBefore = if (kind == Kind.EVENT) lead.toIntOrNull() ?: 0 else 0, durationMinutes = if (kind == Kind.EVENT && allDay) 1440 else duration.toIntOrNull() ?: 30, alertDevice = alert, followsDeviceZone = follows, completed = false, snoozeUntil = null).validate()
                onSave(entry)
            } catch (_: Exception) {
                error = "Add a title and a valid duration (1–10080 minutes)."
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save on this device") }
    }
}

@Composable
private fun WatchDateTimePicker(initial: LocalDateTime, date: Boolean, onCancel: () -> Unit, onSave: (LocalDateTime) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    BackHandler { onCancel() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (date) "Choose date" else "Choose time", style = MaterialTheme.typography.titleMedium)
        Text(value.format(DateTimeFormatter.ofPattern(if (date) "EEE d MMM yyyy" else "HH:mm")))
        val parts = if (date) listOf("Year", "Month", "Day") else listOf("Hour", "Minute")
        parts.forEach { part ->
            Text(part)
            Row {
                listOf(-1L, 1L).forEach { delta ->
                    OutlinedButton(onClick = {
                        value = when (part) {
                            "Year" -> value.plusYears(delta)
                            "Month" -> value.plusMonths(delta)
                            "Day" -> value.plusDays(delta)
                            "Hour" -> value.plusHours(delta)
                            else -> value.plusMinutes(delta)
                        }
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (delta < 0) "−" else "+") }
                }
            }
        }
        Button(onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth()) { Text("Set") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
