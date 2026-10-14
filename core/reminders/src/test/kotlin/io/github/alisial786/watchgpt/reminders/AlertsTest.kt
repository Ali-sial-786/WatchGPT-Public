package io.github.alisial786.watchgpt.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class AlertsTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun entry(
        repeat: Repeat = Repeat.NONE,
        time: String = "2026-10-13T09:00",
    ) = Entry(title = "Synthetic alert", localTime = time, zone = "UTC", followsDeviceZone = false, repeat = repeat, revision = 3, author = "phone")

    private fun at(text: String) = Instant.parse(text)

    private fun ringsAt(
        e: Entry,
        now: String,
        zone: ZoneId = utc,
        delivered: Set<String> = emptySet(),
    ) = Alerts.due(e, at(now), zone) { Alerts.token(e, it) in delivered }?.at

    @Test fun oneOffIsDueAtItsTimeMinusLead() {
        assertEquals(at("2026-10-13T08:45:00Z"), ringsAt(entry().copy(alertMinutesBefore = 15), "2026-10-13T07:00:00Z"))
    }

    @Test fun missedOneOffIsDeliveredOnceWhenSchedulingResumes() {
        val e = entry()
        val alert = Alerts.due(e, at("2026-10-13T15:00:00Z"), utc)!!
        assertEquals(at("2026-10-13T09:00:00Z"), alert.at)
        assertNull(ringsAt(e, "2026-10-13T15:00:00Z", delivered = setOf(Alerts.token(e, alert))))
    }

    @Test fun oldHistoryDoesNotRingOnANewDevice() {
        assertNull(ringsAt(entry(), "2026-10-14T09:01:00Z"))
    }

    @Test fun editsSyncsAndTravelDoNotRingAgain() {
        val e = entry().copy(followsDeviceZone = true)
        val first = Alerts.due(e, at("2026-10-13T08:00:00Z"), utc)!!
        val edited = e.copy(notes = "New notes", revision = 9, author = "watch")
        val travelled = Alerts.due(edited, at("2026-10-13T08:00:00Z"), ZoneId.of("Asia/Dubai"))!!
        assertEquals(Alerts.token(e, first), Alerts.token(edited, travelled))
    }

    @Test fun recurringOccurrenceMissedByMinutesIsStillDelivered() {
        val daily = entry(Repeat.DAILY)
        assertEquals(at("2026-10-15T09:00:00Z"), ringsAt(daily, "2026-10-15T09:10:00Z"))
        assertEquals(at("2026-10-16T09:00:00Z"), ringsAt(daily, "2026-10-15T09:10:00Z", delivered = setOf("${daily.id}@2026-10-15T09:00")))
    }

    @Test fun recurringBacklogIsNotReplayed() {
        assertEquals(at("2026-10-16T09:00:00Z"), ringsAt(entry(Repeat.DAILY), "2026-10-15T11:00:00Z"))
    }

    @Test fun recurringLeadTimeApplies() {
        assertEquals(at("2026-10-20T08:30:00Z"), ringsAt(entry(Repeat.WEEKLY).copy(alertMinutesBefore = 30), "2026-10-14T00:00:00Z"))
    }

    @Test fun allDayItemsAlertInTheMorning() {
        val birthday = entry(time = "2026-10-20T00:00").copy(allDay = true, kind = Kind.EVENT)
        assertEquals(at("2026-10-20T09:00:00Z"), ringsAt(birthday, "2026-10-19T12:00:00Z"))
        val weekly = birthday.copy(repeat = Repeat.WEEKLY)
        assertEquals(at("2026-10-27T09:00:00Z"), ringsAt(weekly, "2026-10-20T10:30:00Z"))
        assertEquals(at("2026-10-20T09:00:00Z"), ringsAt(weekly, "2026-10-20T09:30:00Z"))
    }

    @Test fun snoozeThenSeriesContinues() {
        val snooze = at("2026-10-15T09:10:00Z")
        val snoozed = entry(Repeat.DAILY).copy(snoozeUntil = snooze.toEpochMilli())
        assertEquals(snooze, ringsAt(snoozed, "2026-10-15T09:01:00Z"))
        val after = ringsAt(snoozed, "2026-10-15T09:10:01Z", delivered = setOf("${snoozed.id}@snooze:${snooze.toEpochMilli()}", "${snoozed.id}@2026-10-15T09:00"))
        assertEquals(at("2026-10-16T09:00:00Z"), after)
    }

    @Test fun aSnoozeReplacesTheAlertsItPostponed() {
        // "Snooze it 10 minutes" at 08:00, ahead of the 09:00 alert: the snooze rings, the 09:00 alert doesn't too.
        val snooze = at("2026-10-15T09:10:00Z")
        val snoozed = entry(Repeat.DAILY).copy(snoozeUntil = snooze.toEpochMilli())
        assertEquals(at("2026-10-16T09:00:00Z"), ringsAt(snoozed, "2026-10-15T09:10:01Z", delivered = setOf("${snoozed.id}@snooze:${snooze.toEpochMilli()}")))
        // A snooze longer than a day passes over the occurrences it covers.
        val long = at("2026-10-17T09:05:00Z")
        val away = entry(Repeat.DAILY).copy(snoozeUntil = long.toEpochMilli())
        assertEquals(long, ringsAt(away, "2026-10-15T09:01:00Z"))
        assertEquals(at("2026-10-18T09:00:00Z"), ringsAt(away, "2026-10-17T09:05:01Z", delivered = setOf("${away.id}@snooze:${long.toEpochMilli()}")))
    }

    @Test fun anAdvanceAlertMissedOvernightStillRingsBeforeTheEvent() {
        // Friday 15:00 with a day's notice; the device was off from Thursday 14:00 to Friday 04:30.
        val trip = entry(time = "2026-10-16T15:00").copy(alertMinutesBefore = 1440)
        assertEquals(at("2026-10-15T15:00:00Z"), ringsAt(trip, "2026-10-16T04:30:00Z"))
        val weekly = entry(Repeat.WEEKLY, "2026-10-15T15:00").copy(alertMinutesBefore = 1440)
        assertEquals(at("2026-10-14T15:00:00Z"), ringsAt(weekly, "2026-10-14T17:00:00Z"))
        // Once delivered, the next is the following week's, on time.
        assertEquals(at("2026-10-21T15:00:00Z"), ringsAt(weekly, "2026-10-14T17:00:00Z", delivered = setOf("${weekly.id}@2026-10-15T15:00")))
    }

    @Test fun aLongLeadOnADailySeriesDoesNotReplayEveryDay() {
        val daily = entry(Repeat.DAILY).copy(alertMinutesBefore = 3 * 1440)
        // Made after today's 09:00: one late alert for the nearest occurrence, then the next on time, not three at once.
        val first = Alerts.due(daily, at("2026-10-13T10:30:00Z"), utc)!!
        assertEquals("2026-10-14T09:00", first.key)
        assertEquals(at("2026-10-14T09:00:00Z"), ringsAt(daily, "2026-10-13T10:30:01Z", delivered = setOf(Alerts.token(daily, first))))
    }

    @Test fun snoozeOnlyPostpones() {
        val later = entry(time = "2026-10-13T18:00")
        assertEquals(at("2026-10-13T18:00:00Z"), Alerts.snoozeFrom(later, at("2026-10-13T12:00:00Z"), utc))
        assertEquals(at("2026-10-13T18:05:00Z"), Alerts.snoozeFrom(later, at("2026-10-13T18:05:00Z"), utc))
    }

    @Test fun completedAndDeletedNeverAlert() {
        assertNull(Alerts.due(entry().copy(completed = true), Instant.EPOCH, utc))
        assertNull(Alerts.due(entry(Repeat.DAILY).copy(deleted = true), Instant.EPOCH, utc))
    }

    @Test fun onlyTheChosenDeviceAlerts() {
        assertTrue(Alerts.targets(entry().copy(alertDevice = AlertDevice.WATCH), watch = true))
        assertFalse(Alerts.targets(entry().copy(alertDevice = AlertDevice.WATCH), watch = false))
        assertTrue(Alerts.targets(entry().copy(alertDevice = AlertDevice.BOTH), watch = false))
    }

    @Test fun doneFromAnOldNotificationDoesNotSkipAnotherOccurrence() {
        val daily = entry(Repeat.DAILY)
        assertTrue(Alerts.current(daily, "2026-10-13T09:00"))
        val advanced = Recurrence.complete(daily, at("2026-10-13T09:05:00Z"), utc)
        assertFalse(Alerts.current(advanced, "2026-10-13T09:00"))
        assertTrue(Alerts.current(advanced, "2026-10-14T09:00"))
    }

    @Test fun anOldSnoozeNotificationGoesWithItsSnooze() {
        val snooze = at("2026-10-15T09:10:00Z").toEpochMilli()
        val snoozed = entry(Repeat.DAILY, "2026-10-15T09:00").copy(snoozeUntil = snooze)
        assertTrue(Alerts.current(snoozed, "snooze:$snooze"))
        // Done on the other device cleared the snooze and moved the series on.
        assertFalse(Alerts.current(Recurrence.complete(snoozed, at("2026-10-15T09:11:00Z"), utc), "snooze:$snooze"))
        assertFalse(Alerts.current(snoozed.copy(snoozeUntil = snooze + 600_000), "snooze:$snooze"))
    }

    @Test fun anOldNotificationForAMovedOneOffIsLeftAlone() {
        val e = entry()
        assertTrue(Alerts.current(e, "2026-10-13T09:00"))
        assertFalse(Alerts.current(e.copy(localTime = "2026-10-14T09:00"), "2026-10-13T09:00"))
        assertFalse(Alerts.current(e.copy(completed = true), "2026-10-13T09:00"))
    }

    @Test fun doneOnAnAdvanceAlertCompletesThatOccurrence() {
        val standup = entry(Repeat.DAILY, "2026-10-13T10:00").copy(alertMinutesBefore = 15)
        assertEquals("2026-10-15T10:00", Recurrence.complete(standup, at("2026-10-14T09:46:00Z"), utc).localTime)
    }

    @Test fun legacyReceiptsAreFoundByTheirTime() {
        val e = entry().copy(alertMinutesBefore = 15)
        val alert = Alerts.due(e, at("2026-10-13T08:00:00Z"), utc)!!
        assertEquals(at("2026-10-13T08:45:00Z").toEpochMilli(), Alerts.legacyAt(e, alert, utc))
        // All-day items used to alert at midnight.
        val birthday = entry(time = "2026-10-20T00:00").copy(allDay = true)
        assertEquals(at("2026-10-20T00:00:00Z").toEpochMilli(), Alerts.legacyAt(birthday, Alerts.due(birthday, at("2026-10-19T12:00:00Z"), utc)!!, utc))
    }

    @Test fun allDayItemsAreOnTheirDayWhereverTheDeviceIs() {
        // Calendar edits used to pin all-day items to UTC: they still alert at 09:00 here.
        val pinned = entry(time = "2026-10-20T00:00").copy(allDay = true)
        val la = ZoneId.of("America/Los_Angeles")
        assertEquals(at("2026-10-20T16:00:00Z"), ringsAt(pinned, "2026-10-20T07:00:00Z", la))
    }
}
