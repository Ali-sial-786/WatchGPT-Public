package io.github.alisial786.watchgpt.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class EntryTest {
    private fun entry(repeat: Repeat = Repeat.NONE, time: String = "2026-10-13T09:00") = Entry(title = "Synthetic reminder", localTime = time, zone = "UTC", followsDeviceZone = false, repeat = repeat)

    @Test fun duplicateMergeIsIdempotent() {
        val e = entry()
        assertEquals(e, Merge.winner(e, e))
    }

    @Test fun concurrentEditsConvergeDespiteClockSkew() {
        val a = entry().copy(revision = 5, author = "phone")
        val b = a.copy(title = "Watch edit", author = "watch")
        assertEquals(Merge.winner(a, b), Merge.winner(b, a))
    }

    @Test fun deletionWinsEvenAgainstNewerOfflineEdit() {
        val a = entry().copy(deleted = true, revision = 2)
        val b = a.copy(deleted = false, revision = 99)
        assertEquals(a, Merge.winner(a, b))
        assertEquals(a, Merge.winner(b, a))
    }

    @Test fun mergeIsAssociative() {
        val a = entry().copy(revision = 1, author = "a")
        val b = a.copy(revision = 2, author = "b")
        val c = a.copy(revision = 2, author = "c")
        assertEquals(Merge.winner(Merge.winner(a, b), c), Merge.winner(a, Merge.winner(b, c)))
    }

    @Test fun monthlyDoesNotDriftAfterFebruary() {
        val e = entry(Repeat.MONTHLY, "2027-01-31T09:00")
        assertEquals(Instant.parse("2027-03-31T09:00:00Z"), Recurrence.next(e, Instant.parse("2027-03-01T00:00:00Z")))
    }

    @Test fun weekdaysSkipWeekend() {
        assertEquals(Instant.parse("2026-10-19T09:00:00Z"), Recurrence.next(entry(Repeat.WEEKDAYS), Instant.parse("2026-10-17T00:00:00Z")))
    }

    @Test fun daylightSavingKeepsWallClock() {
        val e = entry(Repeat.DAILY, "2026-03-07T09:00").copy(zone = "America/New_York")
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), Recurrence.next(e, Instant.parse("2026-03-08T00:00:00Z")))
    }

    @Test fun springGapMovesForward() {
        val e = entry(Repeat.DAILY, "2026-03-07T02:30").copy(zone = "America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), Recurrence.next(e, Instant.parse("2026-03-08T00:00:00Z")))
    }

    @Test fun travelUsesDeviceZoneOnlyWhenSelected() {
        val e = entry().copy(followsDeviceZone = true)
        assertEquals(Instant.parse("2026-10-13T08:00:00Z"), e.initialInstant(ZoneId.of("Europe/London")))
        assertEquals(Instant.parse("2026-10-13T09:00:00Z"), e.copy(followsDeviceZone = false).initialInstant(ZoneId.of("Europe/London")))
    }

    @Test fun completeAndSnooze() {
        val e = entry()
        assertNull(Recurrence.next(Recurrence.complete(e, Instant.now()), Instant.EPOCH))
        val snooze = Instant.parse("2026-10-13T10:00:00Z")
        assertEquals(snooze, Recurrence.next(e.copy(snoozeUntil = snooze.toEpochMilli()), Instant.parse("2026-10-13T09:30:00Z")))
    }

    @Test fun completedAndDeletedNeverSchedule() {
        assertNull(Recurrence.next(entry().copy(deleted = true), Instant.EPOCH))
        assertNull(Recurrence.next(entry().copy(completed = true), Instant.EPOCH))
    }

    @Test fun completingMonthlyOccurrenceRetainsOriginalDay() {
        val e = entry(Repeat.MONTHLY, "2027-01-31T09:00")
        val feb = Recurrence.complete(e, Instant.parse("2027-01-31T09:00:00Z"))
        val mar = Recurrence.complete(feb, Instant.parse("2027-02-28T09:00:00Z"))
        assertEquals("2027-03-31T09:00", mar.localTime)
    }

    @Test fun fallOverlapFiresOnlyFirstOccurrence() {
        val e = entry(Repeat.DAILY, "2026-10-31T01:30").copy(zone = "America/New_York")
        val first = Recurrence.next(e, Instant.parse("2026-11-01T00:00:00Z"))!!
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first)
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), Recurrence.next(e, first.plusSeconds(1)))
    }

    @Test fun offlineReconnectOrderCannotResurrectDeletion() {
        val base = entry().copy(revision = 1, author = "phone")
        val phone = base.copy(title = "Phone edit", revision = 2)
        val watch = base.copy(notes = "Watch edit", revision = 2, author = "watch")
        val deleted = phone.copy(deleted = true, revision = 3)
        val messages = listOf(base, phone, watch, deleted, watch, base, deleted)
        repeat(100) { assertEquals(deleted, messages.shuffled().fold(null as Entry?) { old, message -> Merge.winner(old, message) }) }
    }

    @Test fun invalidWireRecordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { entry().copy(id = "bad").validate() }
        assertThrows(IllegalArgumentException::class.java) { entry().copy(title = "").validate() }
        assertThrows(IllegalArgumentException::class.java) { entry().copy(monthlyDay = 32).validate() }
    }

    @Test fun completingBeforeDueSkipsThatOccurrence() {
        val e = entry(Repeat.DAILY, "2026-10-13T09:00")
        assertEquals("2026-10-14T09:00", Recurrence.complete(e, Instant.parse("2026-10-13T08:00:00Z")).localTime)
    }

    @Test fun pinnedZoneSurvivesStorageWhileTheDeviceIsInThatZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Karachi"))
            val pinned = Entry(title = "Synthetic pinned", localTime = "2026-10-13T09:00", followsDeviceZone = false)
            val text = EntryCodec.encode(pinned)
            assertTrue(text.contains("Asia/Karachi"))
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals("Asia/Karachi", EntryCodec.decode(text).zone)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun olderRecordsWithoutNewFieldsStillDecode() {
        val e = EntryCodec.decode("""{"id":"00000000-0000-4000-8000-000000000001","title":"Synthetic","localTime":"2026-10-13T09:00","zone":"UTC"}""")
        assertEquals(Repeat.NONE, e.repeat)
        assertEquals(AlertDevice.WATCH, e.alertDevice)
    }

    @Test fun tombstoneKeepsNoContent() {
        val e = entry(Repeat.WEEKLY).copy(notes = "Synthetic private note", snoozeUntil = 5, revision = 4, author = "phone")
        val t = e.tombstone()
        assertTrue(t.deleted)
        assertEquals(e.id, t.id)
        assertEquals(4, t.revision)
        assertFalse(EntryCodec.encode(t).contains("Synthetic"))
        assertEquals(t, Merge.winner(t, e.copy(revision = 50)))
    }

    @Test fun completingIntoADstGapKeepsTheSeriesWallTime() {
        val e = entry(Repeat.DAILY, "2026-03-07T02:30").copy(zone = "America/New_York")
        val gap = Recurrence.complete(e, Instant.parse("2026-03-07T07:31:00Z"))
        assertEquals("2026-03-08T02:30", gap.localTime)
        val after = Recurrence.complete(gap, Instant.parse("2026-03-08T07:31:00Z"))
        assertEquals("2026-03-09T02:30", after.localTime)
    }

    @Test fun weekdaysSeededOnSaturdayCompletesItsFirstRealOccurrence() {
        val e = entry(Repeat.WEEKDAYS, "2026-10-17T09:00")
        assertEquals("2026-10-20T09:00", Recurrence.complete(e, Instant.parse("2026-10-16T12:00:00Z")).localTime)
    }
}
