package io.github.alisial786.watchgpt.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarProjectionTest {
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun entry() = Entry(title = "Synthetic calendar", notes = "Synthetic notes", localTime = "2026-10-20T09:00", repeat = Repeat.WEEKLY, alertDevice = AlertDevice.PHONE, alertMinutesBefore = 15)

    @Test fun recurringAllDayUsesDayDurations() {
        val e = entry().copy(allDay = true, localTime = "2026-10-20T00:00", durationMinutes = 1440)
        val event = CalendarProjection.toEvent(e, newYork)
        assertEquals("P1D", event.duration)
        assertNull(event.end)
        assertEquals("UTC", event.zone)
        assertEquals(LocalDate.parse("2026-10-20"), CalendarProjection.startDate(event))
    }

    @Test fun oneOffAllDayEndsAtTheNextUtcMidnight() {
        val e = entry().copy(allDay = true, repeat = Repeat.NONE, localTime = "2026-10-20T00:00", durationMinutes = 1440)
        val event = CalendarProjection.toEvent(e, newYork)
        assertEquals(event.start + 86_400_000L, event.end)
    }

    @Test fun durationsInEveryFormCalendarsWrite() {
        assertEquals(30L, CalendarProjection.minutes("PT30M"))
        assertEquals(30L, CalendarProjection.minutes("P1800S"))
        assertEquals(90L, CalendarProjection.minutes("PT1H30M"))
        assertEquals(1440L, CalendarProjection.minutes("P1D"))
        assertEquals(10_080L, CalendarProjection.minutes("P1W"))
        assertNull(CalendarProjection.minutes("P"))
        assertNull(CalendarProjection.minutes("soon"))
    }

    @Test fun googleRewritesAreNotEdits() {
        val event = CalendarProjection.toEvent(entry(), newYork)
        val rewritten = event.copy(duration = "P1800S", rule = "FREQ=WEEKLY;WKST=SU;BYDAY=TU")
        assertEquals(CalendarProjection.fingerprint(event), CalendarProjection.fingerprint(rewritten))
        assertNotEquals(CalendarProjection.fingerprint(event), CalendarProjection.fingerprint(event.copy(title = "Moved by the owner")))
    }

    @Test fun applyingAnUnchangedCopyChangesNothing() {
        val e = entry()
        val event = CalendarProjection.toEvent(e, newYork).copy(duration = "P1800S", rule = "FREQ=WEEKLY;WKST=SU;BYDAY=TU")
        assertEquals(e, CalendarProjection.apply(e, event, newYork))
    }

    @Test fun calendarTitleEditKeepsWatchGptSettings() {
        val e = entry().copy(snoozeUntil = 1_800_000_000_000)
        val edited = CalendarProjection.toEvent(e, newYork).copy(title = "Synthetic renamed")
        val applied = CalendarProjection.apply(e, edited, newYork)
        assertEquals("Synthetic renamed", applied.title)
        assertTrue(applied.followsDeviceZone)
        assertEquals(15, applied.alertMinutesBefore)
        assertEquals(AlertDevice.PHONE, applied.alertDevice)
        assertEquals(e.snoozeUntil, applied.snoozeUntil)
        assertEquals("Synthetic notes", applied.notes)
    }

    @Test fun travellingThenEditingInTheCalendarKeepsLocalTime() {
        val e = entry()
        val writtenAtHome = CalendarProjection.toEvent(e, newYork)
        val applied = CalendarProjection.apply(e, writtenAtHome.copy(title = "Synthetic renamed"), ZoneId.of("Europe/London"))
        assertEquals("2026-10-20T09:00", applied.localTime)
        assertTrue(applied.followsDeviceZone)
    }

    @Test fun movedInTheCalendarMovesTheItem() {
        val e = entry()
        val event = CalendarProjection.toEvent(e, newYork)
        val applied = CalendarProjection.apply(e, event.copy(start = event.start + 3_600_000L), newYork)
        assertEquals("2026-10-20T10:00", applied.localTime)
        assertNull(applied.snoozeUntil)
    }

    @Test fun allDayStaysOnItsDate() {
        val e = entry().copy(allDay = true, localTime = "2026-10-20T00:00", durationMinutes = 1440)
        val event = CalendarProjection.toEvent(e, newYork)
        val applied = CalendarProjection.apply(e, event.copy(title = "Synthetic renamed"), newYork)
        assertEquals("2026-10-20T00:00", applied.localTime)
        assertTrue(applied.followsDeviceZone)
        assertEquals(1440, applied.durationMinutes)
    }

    @Test fun overlongCalendarTextIsTrimmedToWhatWatchGptStores() {
        val e = entry()
        val event = CalendarProjection.toEvent(e, newYork).copy(title = "x".repeat(500), description = "y".repeat(9000))
        val applied = CalendarProjection.apply(e, event, newYork)
        assertEquals(200, applied.title.length)
        assertEquals(8000, applied.notes.length)
    }

    @Test fun notesSurviveEditorsMovingTheMarker() {
        val id = entry().id
        val description = "${CalendarProjection.marker(id)}\nSynthetic first line\n${CalendarProjection.FOOTER}\nSynthetic added"
        assertEquals("Synthetic first line\n\nSynthetic added", CalendarProjection.notes(description, id))
    }

    @Test fun webEditedHtmlDescriptionsKeepOnlyTheNotes() {
        val id = entry().id
        val html = "Bring passport<br><br>${CalendarProjection.marker(id)}<br>${CalendarProjection.FOOTER}<br>Gate B12"
        assertEquals("Bring passport\n\nGate B12", CalendarProjection.notes(html, id))
    }

    @Test fun unsupportedRecurrenceIsRefusedNotApproximated() {
        val e = entry()
        val event = CalendarProjection.toEvent(e, newYork).copy(rule = "FREQ=WEEKLY;INTERVAL=2")
        assertThrows(IllegalArgumentException::class.java) { CalendarProjection.apply(e, event, newYork) }
    }

    @Test fun aSeriesWrittenInADstGapReadsBackUnchanged() {
        // 02:30 doesn't exist on 8 March 2026 in New York; the calendar stores 03:30 EDT for it.
        val e = entry().copy(repeat = Repeat.DAILY, localTime = "2026-03-08T02:30", snoozeUntil = 1_800_000_000_000)
        assertEquals(e, CalendarProjection.apply(e, CalendarProjection.toEvent(e, newYork), newYork))
    }

    @Test fun monthlyEndOfMonthRoundTrips() {
        val e = entry().copy(repeat = Repeat.MONTHLY, localTime = "2027-02-28T09:00", monthlyDay = 31)
        val applied = CalendarProjection.apply(e, CalendarProjection.toEvent(e, newYork), newYork)
        assertEquals(31, applied.monthlyDay)
        assertEquals(e, applied)
    }
}
