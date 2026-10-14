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

    @Test fun aLinkToAnotherEventNeverChangesItAndCopiesMoveWithTheCalendar() = runBlocking {
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
        fun calendar(name: String) = ContentUris.parseId(
            resolver.insert(
                calendars,
                ContentValues().apply {
                    put(CalendarContract.Calendars.ACCOUNT_NAME, account)
                    put(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google")
                    put(CalendarContract.Calendars.NAME, name)
                    put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
                    put(CalendarContract.Calendars.CALENDAR_COLOR, -1)
                    put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                    put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
                    put(CalendarContract.Calendars.VISIBLE, 1)
                    put(CalendarContract.Calendars.SYNC_EVENTS, 0)
                },
            )!!,
        )
        val first = calendar("Synthetic first calendar")
        val second = calendar("Synthetic second calendar")
        val organizer = Organizer(context, startWorkers = false)
        try {
            val item = organizer.db.save(Entry(title = "Synthetic linked entry", localTime = "2027-05-03T09:00"))
            fun copies(calendar: Long): List<Long> = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf("_id"), "calendar_id=? AND deleted=0 AND description LIKE ?", arrayOf(calendar.toString(), "%[WatchGPT:${item.id}]%"), null)!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            organizer.calendar.select(first)
            organizer.calendar.synchronize()
            assertEquals(1, copies(first).size)

            // The calendar's storage was reset: the link's id now names someone else's event, and the copy is gone.
            val unrelated = ContentUris.parseId(
                resolver.insert(
                    CalendarContract.Events.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, first)
                        put(CalendarContract.Events.TITLE, "Synthetic unrelated event")
                        put(CalendarContract.Events.DESCRIPTION, "Synthetic unrelated notes")
                        put(CalendarContract.Events.DTSTART, 1809334800000L)
                        put(CalendarContract.Events.DTEND, 1809338400000L)
                        put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    },
                )!!,
            )
            resolver.delete(CalendarContract.Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build(), "_id=?", arrayOf(copies(first).single().toString()))
            val key = "calendar_${first}_${item.id}"
            organizer.db.meta(key, organizer.db.meta(key)!!.replace(Regex("\"eventId\":\\d+"), "\"eventId\":$unrelated"))
            organizer.db.save(organizer.db.get(item.id)!!.copy(title = "Synthetic linked entry, edited"))
            organizer.calendar.synchronize()
            resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, unrelated), arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION, "deleted"), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic unrelated event", it.getString(0))
                assertEquals("Synthetic unrelated notes", it.getString(1))
                assertEquals(0, it.getInt(2))
            }
            assertFalse(organizer.db.get(item.id)!!.deleted)
            assertEquals(1, copies(first).size)

            // Sync off and on again: the copy is found by its marker, not written twice.
            organizer.calendar.select(null)
            organizer.calendar.select(first)
            organizer.calendar.synchronize()
            assertEquals(1, copies(first).size)

            // Calendar Storage cleared: every copy gone at once deletes no item, and nothing is written meanwhile.
            val other = organizer.db.save(Entry(title = "Synthetic second entry", localTime = "2027-05-04T09:00"))
            organizer.calendar.synchronize()
            fun wipe() = resolver.delete(CalendarContract.Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build(), "calendar_id=? AND description LIKE ?", arrayOf(first.toString(), "%[WatchGPT:%"))
            wipe()
            organizer.calendar.synchronize()
            assertFalse(organizer.db.get(item.id)!!.deleted)
            assertFalse(organizer.db.get(other.id)!!.deleted)
            assertTrue(copies(first).isEmpty())
            organizer.db.removeMeta("calendar_${first}_reset")
            organizer.calendar.synchronize()
            assertEquals(1, copies(first).size)

            // Choosing another calendar moves the copy there.
            organizer.calendar.select(second)
            organizer.calendar.synchronize()
            assertTrue(copies(first).isEmpty())
            assertEquals(1, copies(second).size)
            resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, unrelated), arrayOf("deleted"), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }

            // "Delete everything" removes the copy, even with sync turned off first.
            organizer.calendar.select(null)
            organizer.deleteAll()
            assertTrue(copies(second).isEmpty())
            assertTrue(organizer.db.get(item.id)!!.deleted)
        } finally {
            organizer.calendar.select(null)
            resolver.delete(ContentUris.withAppendedId(calendars, first), null, null)
            resolver.delete(ContentUris.withAppendedId(calendars, second), null, null)
        }
    }
}
