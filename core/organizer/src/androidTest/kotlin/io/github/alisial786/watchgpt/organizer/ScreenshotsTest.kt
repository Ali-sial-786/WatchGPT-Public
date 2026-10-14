package io.github.alisial786.watchgpt.organizer

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.alisial786.watchgpt.reminders.AlertDevice
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Kind
import io.github.alisial786.watchgpt.reminders.Repeat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Captures actual Compose screens and checks manual offline creation through their native semantics. */
class ScreenshotsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun offlineEditorAndPhoneWatchLayoutPreviews() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
        device.executeShellCommand("settings put global stay_on_while_plugged_in 3")
        val organizer = Organizer.get(context)
        organizer.calendar.select(null)
        organizer.db.all().filterNot { it.deleted }.forEach { organizer.db.save(it.copy(deleted = true)) }
        organizer.save(Entry(title = "Take an afternoon walk", notes = "A little fresh air", localTime = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0).toString(), repeat = Repeat.DAILY, alertDevice = AlertDevice.PHONE))
        organizer.save(Entry(title = "Coffee with Alex", notes = "Synthetic preview event", localTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0).toString(), kind = Kind.EVENT, alertDevice = AlertDevice.PHONE))
        var scenario: ActivityScenario<OrganizerActivity>? = null
        fun open(watch: Boolean = false) {
            val intent = Intent(context, OrganizerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("watch_preview", watch)
            scenario?.close()
            scenario = ActivityScenario.launch(intent)
            compose.waitUntil(15000) { runCatching { compose.onNodeWithText(if (watch) "+ New reminder" else "Your day").assertExists() }.isSuccess }
            compose.waitForIdle()
            SystemClock.sleep(1000)
        }
        fun click(text: String) {
            compose.onNodeWithText(text).performScrollTo().performClick()
            compose.waitForIdle()
        }
        fun shot(name: String) {
            compose.waitForIdle()
            SystemClock.sleep(600)
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            val dir = context.getExternalFilesDir("screenshots")!!
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatchGPT_Demos")
                },
            )!!
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            open()
            shot("phone-reminders")
            click("+ New reminder")
            shot("phone-editor")
            click("Monthly")
            shot("phone-recurrence")
            compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput("Synthetic offline UI reminder")
            click("Save on this device")
            compose.waitUntil(10000) { organizer.entries.value.any { it.title == "Synthetic offline UI reminder" } }
            assertTrue(organizer.list().any { it.title == "Synthetic offline UI reminder" })
            open()
            click("Google Calendar · optional")
            shot("phone-calendar-off")
            open()
            click("Agenda")
            shot("phone-agenda")
            device.executeShellCommand("wm size 450x450")
            device.executeShellCommand("wm density 320")
            open(true)
            compose.onNodeWithText("+ New reminder").assertIsDisplayed()
            shot("watch-reminders-preview")
            click("+ New reminder")
            shot("watch-editor-preview")
            click("Monthly")
            shot("watch-recurrence-preview")
            click("‹ Cancel")
            open(true)
            click("Agenda")
            shot("watch-agenda-preview")
        } finally {
            scenario?.close()
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
        }
    }
}
