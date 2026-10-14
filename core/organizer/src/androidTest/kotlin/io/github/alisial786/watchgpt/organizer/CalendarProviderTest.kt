package io.github.alisial786.watchgpt.organizer

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Kind
import io.github.alisial786.watchgpt.reminders.Repeat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses only a synthetic local Calendar Provider account on the test device; no Google sign-in. */
class CalendarProviderTest {
    @Test fun optInOwnershipDuplicateRecoveryRemoteEditAndDeletion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        val resolver = context.contentResolver
        val account = "synthetic-${java.util.UUID.randomUUID()}@example.invalid"
        val calendars = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build()
        val uri = resolver.insert(
            calendars,
            ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, account)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google")
                put(CalendarContract.Calendars.NAME, "Synthetic WatchGPT calendar")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Synthetic WatchGPT calendar")
                put(CalendarContract.Calendars.CALENDAR_COLOR, -1)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 0)
            },
        )!!
        val calendarId = ContentUris.parseId(uri)
        val organizer = Organizer(context, startWorkers = false)
        try {
            organizer.calendar.select(null)
            val local = organizer.db.save(Entry(title = "Synthetic calendar entry", localTime = "2027-03-31T09:00"))
            organizer.calendar.synchronize()
            fun owned(): List<Long> = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf("_id"), "calendar_id=? AND deleted=0 AND description LIKE ?", arrayOf(calendarId.toString(), "%[WatchGPT:${local.id}]%"), null)!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            assertTrue(owned().isEmpty())
            val unrelated = resolver.insert(
                CalendarContract.Events.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, "Synthetic unrelated event")
                    put(CalendarContract.Events.DTSTART, 1806483600000L)
                    put(CalendarContract.Events.DTEND, 1806487200000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                },
            )!!
            organizer.calendar.select(calendarId)
            organizer.calendar.synchronize()
            organizer.calendar.synchronize()
            assertEquals(1, owned().size)
            val event = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, owned().single())
            resolver.update(event, ContentValues().apply { put(CalendarContract.Events.TITLE, "Synthetic calendar edit") }, null, null)
            organizer.calendar.synchronize()
            assertEquals("Synthetic calendar edit", organizer.db.get(local.id)!!.title)
            resolver.delete(event, null, null)
            organizer.calendar.synchronize()
            assertTrue(organizer.db.get(local.id)!!.deleted)
            organizer.db.merge(local.copy(revision = 999))
            organizer.calendar.synchronize()
            assertTrue(owned().isEmpty())
            resolver.query(unrelated, arrayOf(CalendarContract.Events.TITLE), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic unrelated event", it.getString(0))
            }

            // A recurring all-day item: the provider only accepts day-based durations for these.
            val birthday = organizer.db.save(Entry(title = "Synthetic birthday", localTime = "2027-04-14T00:00", allDay = true, durationMinutes = 1440, kind = Kind.EVENT, repeat = Repeat.WEEKLY))
            organizer.calendar.synchronize()
            val birthdayCopy = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf("duration", "allDay", "accessLevel"), "calendar_id=? AND deleted=0 AND description LIKE ?", arrayOf(calendarId.toString(), "%[WatchGPT:${birthday.id}]%"), null)!!.use { c ->
                assertTrue(c.moveToFirst())
                Triple(c.getString(0), c.getInt(1), c.getInt(2))
            }
            assertEquals(Triple("P1D", 1, CalendarContract.Events.ACCESS_PRIVATE), birthdayCopy)

            // Sync off, the copy deleted meanwhile, sync on again: the item stays, and gets a new copy.
            organizer.calendar.select(null)
            resolver.delete(CalendarContract.Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build(), "calendar_id=? AND description LIKE ?", arrayOf(calendarId.toString(), "%[WatchGPT:${birthday.id}]%"))
            organizer.calendar.select(calendarId)
            organizer.calendar.synchronize()
            assertFalse(organizer.db.get(birthday.id)!!.deleted)
            resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf("_id"), "calendar_id=? AND deleted=0 AND description LIKE ?", arrayOf(calendarId.toString(), "%[WatchGPT:${birthday.id}]%"), null)!!.use { assertEquals(1, it.count) }
        } finally {
            organizer.calendar.select(null)
            resolver.delete(ContentUris.withAppendedId(calendars, calendarId), null, null)
        }
    }
}
