package io.github.alisial786.watchgpt.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AgendaTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun entry(
        repeat: Repeat = Repeat.NONE,
        time: String = "2026-10-05T09:00",
    ) = Entry(title = "Synthetic agenda", localTime = time, repeat = repeat)

    private fun t(text: String) = LocalDateTime.parse(text)

    @Test fun aSeriesShowsItsNextOccurrenceNotTheDayItWasMade() {
        assertEquals(t("2026-10-14T09:00"), Agenda.nextShown(entry(Repeat.DAILY), Instant.parse("2026-10-14T07:00:00Z"), utc))
        assertEquals(t("2026-10-14T09:00"), Agenda.nextShown(entry(Repeat.DAILY), Instant.parse("2026-10-14T09:20:00Z"), utc))
        assertEquals(t("2026-10-15T09:00"), Agenda.nextShown(entry(Repeat.DAILY), Instant.parse("2026-10-14T11:00:00Z"), utc))
        assertEquals(t("2026-10-05T09:00"), Agenda.nextShown(entry(), Instant.parse("2026-10-14T11:00:00Z"), utc))
    }

    @Test fun aPinnedItemShowsTheTimeItRingsHere() {
        val karachi = entry().copy(zone = "Asia/Karachi", followsDeviceZone = false)
        assertEquals(t("2026-10-05T04:00"), Agenda.nextShown(karachi, Instant.parse("2026-10-01T00:00:00Z"), utc))
    }

    @Test fun occurrencesInARange() {
        val weekdays = entry(Repeat.WEEKDAYS, "2026-10-01T09:00")
        val week = Agenda.occurrences(weekdays, t("2026-10-12T00:00"), t("2026-10-19T00:00"), utc)
        assertEquals((12..16).map { t("2026-10-%02dT09:00".format(it)) }, week)
        assertEquals(listOf(t("2026-10-05T09:00")), Agenda.occurrences(entry(), t("2026-10-05T00:00"), t("2026-10-06T00:00"), utc))
        assertTrue(Agenda.occurrences(entry(), t("2026-10-06T00:00"), t("2026-10-07T00:00"), utc).isEmpty())
    }

    @Test fun allDayItemsStayOnTheirDate() {
        val birthday = entry(Repeat.WEEKLY, "2026-10-06T00:00").copy(allDay = true)
        assertEquals(listOf(t("2026-10-13T00:00")), Agenda.occurrences(birthday, t("2026-10-13T00:00"), t("2026-10-14T00:00"), ZoneId.of("America/New_York")))
    }

    @Test fun anAllDayItemIsShownOnItsDayAllDay() {
        val weekly = entry(Repeat.WEEKLY, "2026-10-20T00:00").copy(allDay = true)
        assertEquals(t("2026-10-20T00:00"), Agenda.nextShown(weekly, Instant.parse("2026-10-20T20:00:00Z"), utc))
        val oneOff = entry(time = "2026-10-20T00:00").copy(allDay = true)
        assertFalse(Agenda.overdue(oneOff, Instant.parse("2026-10-20T20:00:00Z"), utc))
        assertTrue(Agenda.overdue(oneOff, Instant.parse("2026-10-21T00:01:00Z"), utc))
        assertTrue(Agenda.overdue(entry(), Instant.parse("2026-10-05T09:01:00Z"), utc))
    }

    @Test fun doneOnACardCompletesTheOccurrenceItShows() {
        // Made a week ago and never marked done: the card shows today's 10:00, and Done moves it to tomorrow.
        val pills = entry(Repeat.DAILY, "2026-10-07T10:00")
        val now = Instant.parse("2026-10-14T09:00:00Z")
        assertEquals(t("2026-10-14T10:00"), Agenda.nextShown(pills, now, utc))
        val done = Recurrence.complete(pills, now, utc, Agenda.doneOccurrence(pills, now, utc))
        assertEquals(t("2026-10-15T10:00"), Agenda.nextShown(done, now, utc))
    }

    @Test fun doneLateOrWhileSnoozedCompletesTheOneThatWentBy() {
        // 09:00 went by two hours ago; the card already shows tomorrow's. Done is for today's, tomorrow's stays.
        val daily = entry(Repeat.DAILY, "2026-10-14T09:00")
        val late = Instant.parse("2026-10-14T11:00:00Z")
        assertEquals("2026-10-15T09:00", Recurrence.complete(daily, late, utc, Agenda.doneOccurrence(daily, late, utc)).localTime)
        // Snoozed to 10:20: Done at 10:15 finishes the snoozed occurrence, not next week's too.
        val weekly = entry(Repeat.WEEKLY, "2026-10-15T09:00").copy(snoozeUntil = Instant.parse("2026-10-15T10:20:00Z").toEpochMilli())
        val snoozed = Instant.parse("2026-10-15T10:15:00Z")
        assertEquals("2026-10-22T09:00", Recurrence.complete(weekly, snoozed, utc, Agenda.doneOccurrence(weekly, snoozed, utc)).localTime)
    }

    @Test fun anEventIsOverdueOnlyOnceItHasEnded() {
        val meeting = entry().copy(kind = Kind.EVENT, durationMinutes = 60)
        assertFalse(Agenda.overdue(meeting, Instant.parse("2026-10-05T09:30:00Z"), utc))
        assertTrue(Agenda.overdue(meeting, Instant.parse("2026-10-05T10:01:00Z"), utc))
    }

    @Test fun aPinnedAllDayItemIsOnItsDateHere() {
        val pinned = entry(Repeat.WEEKLY, "2026-10-06T00:00").copy(allDay = true, zone = "Asia/Tokyo", followsDeviceZone = false)
        assertEquals(listOf(t("2026-10-13T00:00")), Agenda.occurrences(pinned, t("2026-10-13T00:00"), t("2026-10-14T00:00"), ZoneId.of("America/New_York")))
    }

    @Test fun completedItemsHaveNoOccurrences() {
        assertTrue(Agenda.occurrences(entry(Repeat.DAILY).copy(completed = true), t("2026-10-01T00:00"), t("2026-11-01T00:00"), utc).isEmpty())
    }
}
