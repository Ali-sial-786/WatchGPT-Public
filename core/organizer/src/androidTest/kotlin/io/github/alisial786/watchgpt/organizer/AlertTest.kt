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

class AlertTest {
    @Test fun duplicateDeliveryStaleAlarmAndCompletionAreSafe() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val organizer = Organizer(context, startWorkers = false)
        val manager = context.getSystemService(NotificationManager::class.java)
        val item = organizer.db.save(Entry(title = "Synthetic alert", localTime = "2027-01-01T09:00", alertDevice = AlertDevice.PHONE))
        val due = item.initialInstant().toEpochMilli()
        try {
            organizer.alerts.fire(item.id, item.revision, item.author, due)
            organizer.alerts.fire(item.id, item.revision, item.author, due)
            assertEquals(1, manager.activeNotifications.count { it.tag == item.id })
            organizer.complete(item.id)
            organizer.alerts.fire(item.id, item.revision, item.author, due)
            organizer.alerts.restore()
            assertFalse(manager.activeNotifications.any { it.tag == item.id })
        } finally {
            organizer.delete(item.id)
            manager.cancel(item.id, 1)
        }
    }
}
