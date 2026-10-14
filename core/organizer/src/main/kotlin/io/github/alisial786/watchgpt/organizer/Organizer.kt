package io.github.alisial786.watchgpt.organizer

import android.content.Context
import android.content.pm.PackageManager
import android.database.SQLException
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
import io.github.alisial786.watchgpt.reminders.Alerts
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.EntryCodec
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
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Organizer internal constructor(context: Context, private val startWorkers: Boolean = true) {
    val context: Context = context.applicationContext
    val isWatch = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    internal val db = EntryDatabase(this.context)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** A sync is queued or running and will pick up changes made now. */
    private val syncQueued = AtomicBoolean(false)
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
            }.onFailure { _status.value = "Local storage unavailable: ${it.message ?: it.javaClass.simpleName}" }
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

    /** Deletes every item here and, through tombstones, on the other device and in the calendar copy. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        // Contents go now; a concurrent edit to one item doesn't stop the others being deleted.
        db.all().filterNot { it.deleted }.forEach { runCatching { db.save(it.tombstone()) } }
        refresh()
        alerts.restore()
        requestSync()
    }
    suspend fun delete(id: String) {
        update(id) { it.tombstone() }
    }
    suspend fun complete(id: String) {
        update(id) { Recurrence.complete(it, Instant.now()) }
    }

    /** Postpones the alert that is due (or the next one) by [minutes]; snoozing never brings an alert forward. */
    suspend fun snooze(id: String, minutes: Int = 10) {
        require(minutes in 1..10080)
        update(id) { it.copy(completed = false, snoozeUntil = Alerts.snoozeFrom(it, Instant.now(), ZoneId.systemDefault()).toEpochMilli() + minutes * 60_000L) }
    }
    private suspend fun update(id: String, transform: (Entry) -> Entry) = withContext(Dispatchers.IO) {
        val current = db.get(id)?.takeUnless { it.deleted } ?: return@withContext
        save(transform(current))
        alerts.dismiss(id)
    }
    suspend fun refresh() = withContext(Dispatchers.IO) { _entries.value = db.all().filterNot { it.deleted }.sortedBy { it.localTime } }

    fun requestSync() {
        if (!startWorkers || syncQueued.getAndSet(true)) return
        WorkManager.getInstance(context).enqueueUniqueWork("organizer-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<OrganizerWorker>().build())
    }

    suspend fun sync() = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                do {
                    syncQueued.set(false)
                    syncOnce()
                } while (syncQueued.get())
            } catch (e: Exception) {
                // Let the next change queue a sync again; WorkManager retries this one.
                syncQueued.set(false)
                throw e
            }
        }
    }

    private suspend fun syncOnce() {
        var problem: Exception? = null
        var rejected = 0
        // This device's own published copies: unchanged items are not put again.
        var published: Map<String, String>? = null
        try {
            val data = Wearable.getDataClient(context)
            val self = Wearable.getNodeClient(context).localNode.await().id
            val own = HashMap<String, String>()
            val items = data.dataItems.await()
            try {
                for (item in items) {
                    val path = item.uri.path ?: continue
                    if (!path.startsWith(PATH)) continue
                    val raw = DataMapItem.fromDataItem(item).dataMap.getString(KEY) ?: continue
                    if (item.uri.host == self) own[path] = raw
                    // Own copies merge too: they restore this device's items if its local storage had to start over.
                    try {
                        require(raw.length <= MAX_ITEM)
                        db.merge(EntryCodec.decode(raw))
                    } catch (e: EntryDatabase.KeyUnavailable) {
                        throw e
                    } catch (e: SQLException) {
                        throw e
                    } catch (_: RuntimeException) {
                        // One unreadable record (e.g. from a newer app version) must not stop the rest syncing.
                        rejected++
                    }
                }
            } finally {
                items.release()
            }
            published = own
        } catch (e: Exception) {
            problem = e
        }
        // The calendar works without a paired watch, so its pass doesn't depend on the watch link.
        if (!isWatch) calendar.synchronize()
        var connected = false
        if (published != null) {
            try {
                val data = Wearable.getDataClient(context)
                for (entry in db.all()) {
                    val path = "$PATH${entry.id}"
                    val raw = EntryCodec.encode(entry)
                    if (published[path] == raw) continue
                    val request = PutDataMapRequest.create(path)
                    request.dataMap.putString(KEY, raw)
                    data.putDataItem(request.asPutDataRequest().setUrgent()).await()
                }
                connected = Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
                if (connected) db.meta("last_sync", System.currentTimeMillis().toString())
            } catch (e: Exception) {
                problem = e
            }
        }
        refresh()
        alerts.restore()
        val peer = if (isWatch) "phone" else "watch"
        val notes = listOfNotNull(
            "$rejected item(s) from your $peer could not be read; update both apps".takeIf { rejected > 0 },
            "${db.unreadable} stored item(s) could not be decrypted".takeIf { db.unreadable > 0 },
        )
        _status.value = (
            when {
                problem != null -> "Saved locally · sync pending (${problem.javaClass.simpleName})"
                connected -> "Sent to Wear Data Layer · ${LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
                else -> "Saved locally · waiting for your $peer"
            }
            ) + notes.joinToString("") { " · $it" }
        problem?.let { throw it }
    }

    companion object {
        internal const val PATH = "/watchgpt/organizer/entries/"
        private const val KEY = "entry"
        private const val MAX_ITEM = 32_000

        @Volatile private var instance: Organizer? = null
        fun get(context: Context): Organizer = instance ?: synchronized(this) { instance ?: Organizer(context).also { instance = it } }
    }
}

/** A save based on a version that another device (or the calendar) has replaced or deleted meanwhile. Nothing was saved. */
class ItemChangedException(message: String) : IllegalStateException(message)

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
