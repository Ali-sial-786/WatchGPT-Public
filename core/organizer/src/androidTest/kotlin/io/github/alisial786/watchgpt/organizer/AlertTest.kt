package io.github.alisial786.watchgpt.organizer

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alisial786.watchgpt.reminders.AlertDevice
import io.github.alisial786.watchgpt.reminders.Entry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime

class AlertTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun showing(id: String) = manager.activeNotifications.count { it.tag == id }

    private fun minutesFromNow(minutes: Long) = LocalDateTime.now().plusMinutes(minutes).withSecond(0).withNano(0).toString()

    @Test fun duplicateDeliveryEditsStaleAlarmsAndCompletionAreSafe() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val organizer = Organizer(context, startWorkers = false)
        val item = organizer.db.save(Entry(title = "Synthetic alert", localTime = minutesFromNow(-5), alertDevice = AlertDevice.PHONE))
        val later = organizer.db.save(Entry(title = "Synthetic later alert", localTime = minutesFromNow(120), alertDevice = AlertDevice.PHONE))
        try {
            organizer.alerts.fire(item.id)
            organizer.alerts.fire(item.id)
            assertEquals(1, showing(item.id))
            manager.cancel(item.id, 1)
            // The receipt outlives restarts and edits that keep the time: no second alert.
            organizer.alerts.restore()
            organizer.alerts.fire(item.id)
            organizer.db.save(organizer.db.get(item.id)!!.copy(notes = "Synthetic edit"))
            organizer.alerts.fire(item.id)
            assertEquals(0, showing(item.id))
            // An alarm that goes off early for an item moved elsewhere doesn't alert.
            organizer.alerts.fire(later.id)
            assertEquals(0, showing(later.id))
            organizer.complete(item.id)
            organizer.alerts.fire(item.id)
            assertFalse(manager.activeNotifications.any { it.tag == item.id })
        } finally {
            organizer.delete(item.id)
            organizer.delete(later.id)
            manager.cancel(item.id, 1)
            manager.cancel(later.id, 1)
        }
    }
}
