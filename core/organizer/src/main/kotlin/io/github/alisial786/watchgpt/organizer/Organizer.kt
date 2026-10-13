package io.github.alisial786.watchgpt.organizer

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Recurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.TimeUnit

class Organizer internal constructor(context: Context, private val startWorkers: Boolean = true) {
    val context: Context = context.applicationContext
    val isWatch = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    internal val db = EntryDatabase(this.context)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries = _entries.asStateFlow()
    private val _status = MutableStateFlow("Loading local reminders…")
    val status = _status.asStateFlow()
    val lastSync: Long? get() = db.meta("last_sync")?.toLongOrNull()
    val calendar = CalendarSync(this)
    internal val alerts = ReminderAlerts(this)
    init {
        scope.launch {
            runCatching {
                refresh()
                alerts.restore()
            }.onFailure { _status.value = "Local storage unavailable: ${it.javaClass.simpleName}" }
        }
        if (startWorkers) WorkManager.getInstance(context).enqueueUniquePeriodicWork("organizer-periodic", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<OrganizerWorker>(15, TimeUnit.MINUTES).build())
        requestSync()
    }
    fun notificationsAllowed(): Boolean = alerts.notificationsAllowed()
    suspend fun list(): List<Entry> = withContext(Dispatchers.IO) { db.all().filterNot { it.deleted } }
    suspend fun save(entry: Entry): Entry = withContext(Dispatchers.IO) {
        val saved = db.save(entry)
        refresh()
        alerts.schedule(saved)
        requestSync()
        saved
    }
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.all().filterNot { it.deleted }.forEach { save(it.copy(deleted = true, title = "Deleted item", notes = "")) }
        // Retain non-content deletion records so an offline device cannot restore private content.
        refresh()
        alerts.restore()
        requestSync()
    }
    suspend fun delete(id: String) {
        update(id) { it.copy(deleted = true, title = "Deleted item", notes = "") }
    }
    suspend fun complete(id: String) {
        update(id) { Recurrence.complete(it, Instant.now()) }
    }
    suspend fun snooze(id: String, minutes: Int = 10) {
        require(minutes in 1..10080)
        update(id) { it.copy(completed = false, snoozeUntil = System.currentTimeMillis() + minutes * 60_000L) }
    }
    private suspend fun update(id: String, transform: (Entry) -> Entry) = withContext(Dispatchers.IO) {
        db.get(id)?.takeUnless { it.deleted }?.let { save(transform(it)) }
        alerts.dismiss(id)
    }
    suspend fun refresh() = withContext(Dispatchers.IO) { _entries.value = db.all().filterNot { it.deleted }.sortedBy { it.localTime } }
    fun requestSync() {
        if (!startWorkers) return
        WorkManager.getInstance(context).enqueueUniqueWork("organizer-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<OrganizerWorker>().build())
    }
    suspend fun sync() = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!isWatch) calendar.synchronize()
                val data = Wearable.getDataClient(context)
                val items = data.dataItems.await()
                try {
                    items.forEach { item ->
                        if (item.uri.path?.startsWith(PATH) == true) {
                            val raw = DataMapItem.fromDataItem(item).dataMap.getString("entry")
                            if (raw != null && raw.length <= 32_000) db.merge(json.decodeFromString<Entry>(raw))
                        }
                    }
                } finally {
                    items.release()
                }
                if (!isWatch) calendar.synchronize()
                db.all().forEach { entry ->
                    val request = PutDataMapRequest.create("$PATH${entry.id}")
                    request.dataMap.putString("entry", json.encodeToString(Entry.serializer(), entry))
                    data.putDataItem(request.asPutDataRequest().setUrgent()).await()
                }
                val connected = Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
                val now = System.currentTimeMillis()
                if (connected) db.meta("last_sync", now.toString())
                _status.value = if (connected) "Sent to Wear Data Layer · ${java.time.LocalTime.now().withSecond(0).withNano(0)}" else "Saved locally · waiting for your ${if (isWatch) "phone" else "watch"}"
                refresh()
                alerts.restore()
            } catch (e: Exception) {
                _status.value = "Saved locally · sync pending (${e.javaClass.simpleName})"
                refresh()
                alerts.restore()
                throw e
            }
        }
    }
    companion object {
        internal const val PATH = "/watchgpt/organizer/entries/"

        @Volatile private var instance: Organizer? = null
        fun get(context: Context): Organizer = instance ?: synchronized(this) { instance ?: Organizer(context).also { instance = it } }
    }
}

class OrganizerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Organizer.get(applicationContext).sync()
        Result.success()
    } catch (_: Exception) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
class OrganizerListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(Organizer.PATH) == true }) Organizer.get(this).requestSync()
    }
}
