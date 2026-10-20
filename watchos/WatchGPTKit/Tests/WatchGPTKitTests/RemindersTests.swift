@testable import WatchGPTKit
import XCTest

private let utc = TimeZone(identifier: "UTC")!
private let newYork = TimeZone(identifier: "America/New_York")!

private func at(_ iso: String) -> Date {
    ISO8601DateFormatter().date(from: iso)!
}

private func entry(_ repeats: Repeat = .none, _ time: String = "2026-10-13T09:00", zone: String = "UTC") -> Entry {
    var e = Entry(title: "Synthetic reminder", localTime: LocalDateTime(time)!, repeat: repeats)
    e.zone = zone
    e.followsDeviceZone = false
    return e
}

/// The Android app's EntryTest and AlertsTest cases that apply without sync.
final class RecurrenceTests: XCTestCase {
    func testLocalTimesParseAndPrint() {
        XCTAssertEqual(LocalDateTime("2026-09-28T15:00")?.description, "2026-09-28T15:00")
        XCTAssertEqual(LocalDateTime("2026-09-28T15:00:30")?.description, "2026-09-28T15:00")
        XCTAssertNil(LocalDateTime("2026-02-30T15:00"))
        XCTAssertNil(LocalDateTime("2026-09-28 15:00"))
        XCTAssertEqual(LocalDateTime.date("2028-02-29"), LocalDateTime(year: 2028, month: 2, day: 29))
    }

    func testMonthlyDoesNotDriftAfterFebruary() {
        let e = entry(.monthly, "2027-01-31T09:00")
        XCTAssertEqual(Recurrence.next(e, from: at("2027-02-01T00:00:00Z"), zone: utc), at("2027-02-28T09:00:00Z"))
        XCTAssertEqual(Recurrence.next(e, from: at("2027-03-01T00:00:00Z"), zone: utc), at("2027-03-31T09:00:00Z"))
    }

    func testWeekdaysSkipTheWeekend() {
        XCTAssertEqual(Recurrence.next(entry(.weekdays), from: at("2026-10-17T00:00:00Z"), zone: utc), at("2026-10-19T09:00:00Z"))
    }

    func testDaylightSavingKeepsTheWallClock() {
        let e = entry(.daily, "2026-03-07T09:00", zone: "America/New_York")
        XCTAssertEqual(Recurrence.next(e, from: at("2026-03-08T00:00:00Z"), zone: utc), at("2026-03-08T13:00:00Z"))
    }

    func testSpringGapMovesForward() {
        let e = entry(.daily, "2026-03-07T02:30", zone: "America/New_York")
        XCTAssertEqual(Recurrence.next(e, from: at("2026-03-08T00:00:00Z"), zone: utc), at("2026-03-08T07:30:00Z"))
    }

    func testTravelUsesTheWatchZoneOnlyWhenSelected() {
        var e = entry()
        e.followsDeviceZone = true
        let london = TimeZone(identifier: "Europe/London")!
        XCTAssertEqual(Recurrence.next(e, from: .distantPast, zone: london), at("2026-10-13T08:00:00Z"))
        e.followsDeviceZone = false
        XCTAssertEqual(Recurrence.next(e, from: .distantPast, zone: london), at("2026-10-13T09:00:00Z"))
    }

    func testCompleteAndSnooze() {
        let e = entry()
        XCTAssertNil(Recurrence.next(Recurrence.complete(e, now: Date(), zone: utc), from: .distantPast, zone: utc))
        var snoozed = e
        snoozed.snoozeUntil = Int(at("2026-10-13T10:00:00Z").timeIntervalSince1970 * 1000)
        XCTAssertEqual(Recurrence.next(snoozed, from: at("2026-10-13T09:30:00Z"), zone: utc), at("2026-10-13T10:00:00Z"))
    }

    func testCompletingAMonthlyOccurrenceKeepsTheOriginalDay() {
        let e = entry(.monthly, "2027-01-31T09:00")
        let feb = Recurrence.complete(e, now: at("2027-01-31T09:00:00Z"), zone: utc)
        XCTAssertEqual(feb.localTime.description, "2027-02-28T09:00")
        let mar = Recurrence.complete(feb, now: at("2027-02-28T09:00:00Z"), zone: utc)
        XCTAssertEqual(mar.localTime.description, "2027-03-31T09:00")
    }

    func testCompletingBeforeItIsDueSkipsThatOccurrence() {
        let e = entry(.daily, "2026-10-13T09:00")
        XCTAssertEqual(Recurrence.complete(e, now: at("2026-10-13T08:00:00Z"), zone: utc).localTime.description, "2026-10-14T09:00")
    }

    func testOlderRecordsDecodeAndRoundTrip() throws {
        let e = entry(.weekly)
        let decoded = try JSONDecoder().decode(Entry.self, from: JSONEncoder().encode(e))
        XCTAssertEqual(decoded, e)
    }
}

final class AlertPlanTests: XCTestCase {
    func testTheNearestAlertsAreScheduledSeveralPerSeries() {
        var daily = entry(.daily, "2026-10-13T09:00")
        daily.title = "Daily"
        var once = entry(.none, "2026-10-14T12:00")
        once.title = "Once"
        let plan = AlertPlan.upcoming([daily, once], now: at("2026-10-13T10:00:00Z"), zone: utc, limit: 4)
        XCTAssertEqual(plan.map(\.key), ["2026-10-14T09:00", "2026-10-14T12:00", "2026-10-15T09:00", "2026-10-16T09:00"])
        XCTAssertEqual(plan[0].id, "reminder.\(daily.id)@2026-10-14T09:00")
    }

    func testAllDayItemsAlertInTheMorningAndEventsAhead() {
        var party = entry(.none, "2026-10-20T00:00")
        party.allDay = true
        XCTAssertEqual(AlertPlan.alerts(for: party, now: at("2026-10-19T00:00:00Z"), zone: utc, max: 1).first?.at, at("2026-10-20T09:00:00Z"))
        var meeting = entry(.none, "2026-10-20T10:00")
        meeting.alertMinutesBefore = 15
        XCTAssertEqual(AlertPlan.alerts(for: meeting, now: at("2026-10-19T00:00:00Z"), zone: utc, max: 1).first?.at, at("2026-10-20T09:45:00Z"))
    }

    func testASnoozeReplacesTheAlertsItPostponed() async {
        let book = ReminderBook(store: MemoryStore())
        let e = entry(.daily, "2026-10-13T09:00")
        await book.save(e)
        let snoozed = await book.snooze(e.id, minutes: 24 * 60 + 30, now: at("2026-10-13T08:00:00Z"), zone: utc)!
        let plan = AlertPlan.alerts(for: snoozed, now: at("2026-10-13T08:01:00Z"), zone: utc, max: 3)
        XCTAssertEqual(plan.first?.at, at("2026-10-14T09:30:00Z"))
        XCTAssertEqual(plan.dropFirst().first?.key, "2026-10-15T09:00")
    }

    func testASnoozeRightAfterTheAlertCountsFromNow() async {
        let book = ReminderBook(store: MemoryStore())
        let e = entry(.daily, "2026-10-13T09:00")
        await book.save(e)
        let late = await book.snooze(e.id, minutes: 10, now: at("2026-10-13T09:05:00Z"), zone: utc)!
        XCTAssertEqual(late.snooze, at("2026-10-13T09:15:00Z"))
        let tapped = await book.snooze(e.id, minutes: 10, now: at("2026-10-13T11:00:00Z"), zone: utc, fromNow: true)!
        XCTAssertEqual(tapped.snooze, at("2026-10-13T11:10:00Z"))
    }

    func testDoneOnAnOldNotificationDoesNothing() {
        var e = entry(.daily, "2026-10-15T09:00")
        XCTAssertTrue(AlertPlan.current(e, key: "2026-10-15T09:00"))
        XCTAssertFalse(AlertPlan.current(e, key: "2026-10-14T09:00"))
        e.snoozeUntil = 5
        XCTAssertFalse(AlertPlan.current(e, key: "snooze:4"))
        XCTAssertTrue(AlertPlan.current(e, key: "snooze:5"))
        e.completed = true
        XCTAssertFalse(AlertPlan.current(e, key: "2026-10-15T09:00"))
    }

    func testAgendaShowsTheNextOccurrenceAndWhatIsOverdue() {
        let e = entry(.daily, "2026-10-01T09:00")
        XCTAssertEqual(Agenda.shown(e, now: at("2026-10-13T12:00:00Z"), zone: utc).description, "2026-10-14T09:00")
        XCTAssertEqual(Agenda.shown(e, now: at("2026-10-13T09:30:00Z"), zone: utc).description, "2026-10-13T09:00")
        XCTAssertTrue(Agenda.overdue(entry(.none, "2026-10-13T09:00"), now: at("2026-10-13T09:01:00Z"), zone: utc))
        let week = Agenda.occurrences(entry(.weekdays, "2026-10-12T09:00"), from: LocalDateTime("2026-10-12T00:00")!, to: LocalDateTime("2026-10-19T00:00")!, zone: utc)
        XCTAssertEqual(week.count, 5)
        XCTAssertEqual(Agenda.occurrences(entry(.none, "2026-10-13T09:00", zone: "America/New_York"), from: LocalDateTime("2026-10-13T00:00")!, to: LocalDateTime("2026-10-14T00:00")!, zone: utc).first?.description, "2026-10-13T13:00")
        _ = newYork
    }
}

final class MemoryTests: XCTestCase {
    func testFactsAreKeptOnceAndForgotten() async {
        let memory = Memory(store: MemoryStore())
        let first = await memory.remember("My sister is called Mai")
        let again = await memory.remember("my sister is called mai")
        XCTAssertEqual(first.id, again.id)
        let forgot = await memory.forget(first.id)
        XCTAssertTrue(forgot)
        let none = await memory.forget(first.id)
        XCTAssertFalse(none)
    }

    func testAConversationStartsWithWhatWasKeptCleaned() async {
        let clock = TestClock()
        let store = MemoryStore()
        let memory = Memory(store: store, now: { clock.now }, zone: { utc })
        _ = await memory.remember("I work at the bakery")
        await memory.add(conversation: "c1", user: true, text: "My code is 482913")
        await memory.add(conversation: "c1", user: false, text: "Got it.")
        await memory.add(conversation: "c1", user: false, text: "You slept seven hours.", health: true)
        let context = await memory.context(except: "c2") ?? ""
        XCTAssertTrue(context.contains("[1] I work at the bakery"))
        XCTAssertTrue(context.contains("User: My code is [code hidden]"))
        XCTAssertFalse(context.contains("slept"))
        // The conversation going on isn't repeated, and a new instance reads what the first one kept.
        let skipped = await Memory(store: store, now: { clock.now }).context(except: "c1") ?? ""
        XCTAssertFalse(skipped.contains("Got it."))

        clock.advance(3 * 24 * 3600)
        let later = await Memory(store: store, now: { clock.now }).context(except: "c2") ?? ""
        XCTAssertFalse(later.contains("Got it."))
        XCTAssertTrue(later.contains("bakery"))
    }

    func testRecallFindsWordsWhateverTheAccents() async {
        let memory = Memory(store: MemoryStore(), zone: { utc })
        await memory.add(conversation: "c1", user: true, text: "Where is the Café Müller?")
        await memory.add(conversation: "c2", user: true, text: "Something else")
        let found = await memory.recall(from: nil, to: nil, words: "cafe muller")
        XCTAssertTrue(found.contains("Café Müller"))
        XCTAssertFalse(found.contains("Something else"))
        let nothing = await memory.recall(from: nil, to: nil, words: "zebra")
        XCTAssertTrue(nothing.hasPrefix("nothing like that"))
    }
}
