@testable import WatchGPTKit
import XCTest

private let utc = TimeZone(identifier: "UTC")!

private func at(_ iso: String) -> Date {
    ISO8601DateFormatter().date(from: iso)!
}

final class SleepReportTests: XCTestCase {
    private func night(_ source: String = "Apple Watch") -> [SleepSample] {
        [
            SleepSample(start: at("2026-10-19T23:00:00Z"), end: at("2026-10-20T01:00:00Z"), stage: .core, source: source),
            SleepSample(start: at("2026-10-20T01:00:00Z"), end: at("2026-10-20T02:00:00Z"), stage: .deep, source: source),
            SleepSample(start: at("2026-10-20T02:00:00Z"), end: at("2026-10-20T02:10:00Z"), stage: .awake, source: source),
            SleepSample(start: at("2026-10-20T02:10:00Z"), end: at("2026-10-20T04:10:00Z"), stage: .rem, source: source),
            SleepSample(start: at("2026-10-20T04:10:00Z"), end: at("2026-10-20T06:30:00Z"), stage: .core, source: source),
        ]
    }

    func testANightIsToldWithItsStages() throws {
        let sessions = SleepReport.sessions(night())
        XCTAssertEqual(sessions.count, 1)
        let text = try XCTUnwrap(SleepReport.describe(sessions, nights: 1, today: LocalDateTime("2026-10-20T08:00")!, zone: utc, hours24: true))
        XCTAssertTrue(text.contains("Woke up today (Tuesday 20 October): 23:00 to 06:30, 7 hours 20 minutes asleep of 7 hours 30 minutes"), text)
        XCTAssertTrue(text.contains("deep 1 hour, core 4 hours 20 minutes, REM 2 hours, awake 10 minutes, woke up once"), text)
        XCTAssertTrue(text.contains("no sleep score"))
    }

    func testTheSameNightFromTwoSourcesCountsOnceAndNapsAreToldApart() throws {
        let copy = [SleepSample(start: at("2026-10-19T23:05:00Z"), end: at("2026-10-20T06:20:00Z"), stage: .asleep, source: "Sleep app")]
        let nap = [SleepSample(start: at("2026-10-20T14:00:00Z"), end: at("2026-10-20T14:40:00Z"), stage: .asleep, source: "Apple Watch")]
        let text = try XCTUnwrap(SleepReport.describe(SleepReport.sessions(night() + copy + nap), nights: 1, today: LocalDateTime("2026-10-20T20:00")!, zone: utc, hours24: false))
        XCTAssertFalse(text.contains("Sleep app"), text)
        XCTAssertTrue(text.contains("11:00 PM to 6:30 AM"), text)
        XCTAssertTrue(text.contains("Also 2:00 PM to 2:40 PM, 40 minutes asleep of 40 minutes (no sleep stages recorded)."), text)
    }

    func testNothingRecordedIsNil() {
        XCTAssertNil(SleepReport.describe([], nights: 1, today: LocalDateTime("2026-10-20T08:00")!, zone: utc, hours24: true))
        XCTAssertEqual(SleepReport.say(3600 * 7 + 16 * 60), "7 hours 16 minutes")
        XCTAssertEqual(SleepReport.say(3600), "1 hour")
    }
}

final class SentenceSplitterTests: XCTestCase {
    func testSpeechStartsAfterTheFirstSentence() {
        var splitter = SentenceSplitter()
        XCTAssertEqual(splitter.add("Hello there, how"), [])
        XCTAssertEqual(splitter.add(" are you today? I am"), ["Hello there, how are you today?"])
        XCTAssertEqual(splitter.add(" fine."), [])
        XCTAssertEqual(splitter.flush(), "I am fine.")
        XCTAssertNil(splitter.flush())
    }

    func testShortPiecesWaitAndALongFirstSentenceBreaksAtAComma() {
        var splitter = SentenceSplitter()
        XCTAssertEqual(splitter.add("Ok. Sure. "), [])
        XCTAssertEqual(splitter.add("The weather in Berlin today is mild, with some sun"), ["Ok. Sure. The weather in Berlin today is mild,"])
    }
}

private final class FakeDevice: WatchDevice {
    var timers: [(Int, String?)] = []
    var changes = 0
    var calls: [String] = []
    var texts: [(String, String)] = []
    var ended = false
    var contactList: Access<[Contact]> = .granted([])
    var sleepAccess: Access<[SleepSample]> = .denied
    var events: Access<[CalendarItem]> = .denied
    var hours24 = true

    func battery() async -> (level: Double?, charging: Bool?) { (0.734, true) }
    func startTimer(seconds: Int, label: String?) async -> Bool { timers.append((seconds, label)); return true }
    func remindersChanged() async { changes += 1 }
    func sleep(from _: Date, to _: Date) async -> Access<[SleepSample]> { sleepAccess }
    func calendar(from _: Date, to _: Date) async -> Access<[CalendarItem]> { events }
    func contacts() async -> Access<[Contact]> { contactList }
    func call(_ number: String) async -> Bool { calls.append(number); return true }
    func compose(to number: String, body: String) async -> Bool { texts.append((number, body)); return true }
    func endConversation() { ended = true }
}

final class WatchActionsTests: XCTestCase {
    private var clock = TestClock(at("2026-10-20T10:00:00Z"))
    private var device = FakeDevice()
    private var turns: UserTurns!
    private var book = ReminderBook(store: MemoryStore())
    private var memory = Memory(store: MemoryStore())

    private func actions() -> WatchActions {
        let clock = clock
        turns = UserTurns(now: { clock.now })
        return WatchActions(
            reminders: book, notes: NoteBook(store: MemoryStore()), memory: memory, device: device,
            pending: Pending(turns: turns, now: { clock.now }), turns: turns, now: { clock.now }, zone: { utc }
        )
    }

    private func run(_ actions: WatchActions, _ name: String, _ args: JSON = [:]) async -> String {
        await actions.run(name: name, arguments: args.text, context: ToolContext(question: ""))
    }

    func testEveryToolHasALevelAndValidSchema() {
        for spec in WatchActions.specs {
            XCTAssertNotNil(Safety.levels[spec.name], spec.name)
            XCTAssertEqual(JSON.parse(spec.parameters)?["type"], "object", spec.name)
        }
        XCTAssertEqual(Set(WatchActions.specs.map(\.name)).count, WatchActions.specs.count)
    }

    func testRemindersAreAddedListedAndCompleted() async {
        let a = actions()
        let added = await run(a, "add_reminder", ["text": "Call mum", "at": "2026-10-20T15:00", "repeat": "weekly"])
        XCTAssertEqual(added, "ok: reminder \"Call mum\" set for today at 15:00, every week. It alerts on this watch.")
        XCTAssertEqual(device.changes, 1)
        let listed = await run(a, "list_reminders")
        XCTAssertEqual(listed, "Open reminders, soonest first: Call mum, today at 15:00, every week")
        let done = await run(a, "change_reminder", ["match_title": "call MUM", "operation": "complete"])
        XCTAssertTrue(done.hasPrefix("ok: this time of \"Call mum\" is done"))
        let next = await book.all().first?.localTime.description
        XCTAssertEqual(next, "2026-10-27T15:00")
        let past = await run(a, "add_reminder", ["text": "Too late", "at": "2026-10-19T15:00"])
        XCTAssertTrue(past.hasPrefix("error: that time has passed"))
    }

    func testDeletingWaitsForTheUsersYes() async {
        let a = actions()
        _ = await run(a, "add_reminder", ["text": "Gym", "at": "2026-10-21T07:00"])
        let asked = await run(a, "change_reminder", ["match_title": "Gym", "operation": "delete"])
        XCTAssertTrue(asked.hasPrefix("not done yet: Delete \"Gym\"?"))
        let early = await run(a, "confirm_action", ["answer": "yes"])
        XCTAssertTrue(early.hasPrefix("error"))
        clock.advance(3)
        turns.heard()
        let yes = await run(a, "confirm_action", ["answer": "yes"])
        XCTAssertEqual(yes, "ok: deleted \"Gym\"")
        let left = await book.all()
        XCTAssertTrue(left.isEmpty)
    }

    func testEventsGoOnTheAgendaWithTheWatchCalendar() async {
        device.events = .granted([CalendarItem(title: "Dentist", start: at("2026-10-20T09:00:00Z"), end: at("2026-10-20T10:00:00Z"), allDay: false, location: "Main Street")])
        let a = actions()
        let added = await run(a, "add_calendar_event", ["title": "Party", "start": "2026-10-20"])
        XCTAssertEqual(added, "ok: event \"Party\" added for all day today. It alerts on this watch at 9:00 that day.")
        let agenda = await run(a, "list_calendar_events")
        XCTAssertEqual(agenda, "On the agenda: Party, all day today | Dentist, today at 09:00 until 10:00, at Main Street")
    }

    func testACallIsReadBackAndTwoContactsThatFitAreNeverGuessed() async {
        device.contactList = .granted([
            Contact(name: "Anna Schmidt", phones: [Contact.Phone(label: "mobile", number: "+49 151 0000001")]),
            Contact(name: "Anna Weber", phones: [Contact.Phone(label: "home", number: "+49 30 0000002")]),
            Contact(name: "Ben Ito", nickname: "Benny", phones: [Contact.Phone(label: "mobile", number: "+1 555 0100")]),
        ])
        let a = actions()
        let both = await run(a, "call_contact", ["to": "Anna"])
        XCTAssertEqual(both, "error: several contacts fit (Anna Schmidt, Anna Weber); ask the user which one")
        let asked = await run(a, "call_contact", ["to": "anna weber"])
        XCTAssertTrue(asked.hasPrefix("not done yet: Call Anna Weber (home)?"))
        clock.advance(2)
        turns.heard()
        _ = await run(a, "confirm_action", ["answer": "yes"])
        XCTAssertEqual(device.calls, ["+49 30 0000002"])

        let text = await run(a, "send_text_message", ["to": "Benny", "text": "Running late"])
        XCTAssertTrue(text.hasPrefix("not done yet: Text Ben Ito: \"Running late\"?"))
        clock.advance(2)
        turns.heard()
        let sent = await run(a, "confirm_action", ["answer": "yes"])
        XCTAssertTrue(sent.hasPrefix("ok: Messages is open"))
        XCTAssertEqual(device.texts.first?.1, "Running late")
    }

    func testOnlyWhatTheUserJustSaidIsRemembered() async {
        let a = actions()
        turns.heard()
        let kept = await run(a, "remember", ["text": "I like jazz"])
        XCTAssertEqual(kept, "ok: remembered as [1]: I like jazz")
        let secret = await run(a, "remember", ["text": "my wifi password is Sonne123"])
        XCTAssertTrue(secret.hasPrefix("error: nothing was kept: it has a password"))
        clock.advance(120)
        let late = await run(a, "remember", ["text": "I like tea"])
        XCTAssertTrue(late.hasPrefix("error: nothing was kept: only what the user just said"))
    }

    func testTheWatchItself() async {
        let a = actions()
        let battery = await run(a, "device_status")
        XCTAssertEqual(battery, "Watch battery 73 percent, charging.")
        let timer = await run(a, "set_timer", ["seconds": 600, "label": "Tea"])
        XCTAssertEqual(timer, "ok: a timer notification rings in 10 minutes, at 10:10. It's WatchGPT's notification, not the Timer app.")
        XCTAssertEqual(device.timers.first?.0, 600)
        let sleep = await run(a, "read_sleep")
        XCTAssertTrue(sleep.hasPrefix("error: WatchGPT isn't allowed to read sleep"))
        _ = await run(a, "end_conversation")
        XCTAssertTrue(device.ended)
        let bad = await a.run(name: "add_note", arguments: "not json", context: ToolContext(question: ""))
        XCTAssertTrue(bad.hasPrefix("error"))
    }

    func testTimesAreReadInEveryShapeTheModelSends() {
        XCTAssertEqual(ToolArgs.time("2026-09-28T15:00", zone: utc), .at(LocalDateTime("2026-09-28T15:00")!))
        XCTAssertEqual(ToolArgs.time("2026-09-28 15:00:00", zone: utc), .at(LocalDateTime("2026-09-28T15:00")!))
        XCTAssertEqual(ToolArgs.time("2026-09-28T15:00+02:00", zone: utc), .at(LocalDateTime("2026-09-28T13:00")!))
        XCTAssertEqual(ToolArgs.time("2026-09-28T15:00:00Z", zone: TimeZone(identifier: "Europe/Berlin")!), .at(LocalDateTime("2026-09-28T17:00")!))
        XCTAssertEqual(ToolArgs.time("2026-09-28", zone: utc), .day(LocalDateTime("2026-09-28T00:00")!))
        XCTAssertNil(ToolArgs.time("tomorrow", zone: utc))
        XCTAssertEqual(ToolArgs.sayClock(LocalDateTime("2026-09-28T15:05")!, hours24: false), "3:05 PM")
        XCTAssertEqual(ToolArgs.sayClock(LocalDateTime("2026-09-28T00:05")!, hours24: false), "12:05 AM")
    }
}

final class BuddyTests: XCTestCase {
    func testASeedGivesTheSameWatchGPTAsOnAndroid() {
        // Pinned in core/buddy's BuddyTest: if this changes, every user's WatchGPT changes.
        let genes = Genes.of(42)
        XCTAssertEqual(genes.shape, .squircle)
        XCTAssertEqual(genes.color, .lilac)
        XCTAssertEqual(genes.rest, .bright)
        XCTAssertEqual(genes.face.gaze.yaw, 6.4419065, accuracy: 1e-4)
    }

    func testTheSeedComesFromTheAccountAsOnAndroid() {
        XCTAssertEqual(SHA256.hash(Array("abc".utf8)).map { String(format: "%02x", $0) }.joined(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        // Python: struct.unpack('>q', sha256(b'watch-ai buddy v1|account-123').digest()[:8])
        XCTAssertEqual(Genes.seedFor("account-123"), 8_253_517_354_660_737_933)
        XCTAssertEqual(Genes.forUser("account-123"), Genes.forUser("account-123"))
        let many = (1 ... 200).map { Genes.forUser("account-\($0)") }
        XCTAssertEqual(Set(many.map { "\($0.shape)" }).count, BodyShape.allCases.count)
        XCTAssertEqual(Set(many.map { "\($0.color)" }).count, Tint.allCases.count)
    }

    func testShapesWeighTheSame() {
        for shape in BodyShape.allCases {
            XCTAssertGreaterThanOrEqual(shape.radii.max()!, 1)
            XCTAssertLessThanOrEqual(shape.radii.max()!, 1.16)
            XCTAssertGreaterThan(shape.radii.min()!, 0.65)
        }
    }

    func testEveryStateDrawsFiniteFrames() {
        let genes = Genes.of(11)
        for act in [Act.rest, .awake, .connect, .listen, .think, .speak, .error] {
            for mood in Mood.allCases {
                let director = Director(genes: genes)
                for t in stride(from: 0.0, to: 4.0, by: 0.37) {
                    let frame = director.frame(FaceScene(act: act, reaction: Reaction(mood), reactionAt: 0.5), level: 0.5, t: t)
                    XCTAssertEqual(frame.body.count, Silhouette.samples * 2)
                    XCTAssertTrue(frame.body.allSatisfy(\.isFinite))
                    XCTAssertTrue(frame.features.allSatisfy { [$0.a, $0.b, $0.c, $0.d, $0.e, $0.f].allSatisfy(\.isFinite) })
                }
            }
        }
    }

    func testTheFaceHasTwoEyesAndAMouthAtRestAndNoneWhileThinking() {
        let director = Director(genes: Genes.of(7))
        XCTAssertEqual(director.frame(FaceScene(act: .rest), level: 0, t: 1).features.count, 3)
        XCTAssertTrue(director.frame(FaceScene(act: .think, actAt: 1), level: 0, t: 3).features.isEmpty)
    }

    func testReactionsPlayThenHandBack() {
        let scene = FaceScene(act: .speak, actAt: 1, reaction: Reaction(.love), reactionAt: 10)
        XCTAssertEqual(Director.target(scene, 9).0, .speak)
        XCTAssertEqual(Director.target(scene, 11).0, .love)
        XCTAssertEqual(Director.target(scene, 10 + State.love.hold + 0.1).1, 10 + State.love.hold)
        XCTAssertEqual(Director.target(FaceScene(act: .speak, reaction: Reaction(.neutral)), 11).0, .speak)
    }

    func testWithReduceMotionThePictureStandsStill() {
        let director = Director(genes: Genes.of(5), still: true)
        XCTAssertEqual(director.frame(FaceScene(act: .rest), level: 0, t: 1).body, director.frame(FaceScene(act: .rest), level: 0, t: 7.3).body)
    }

    func testRingsStayInTheUsersHue() {
        let genes = Genes.of(21)
        let frame = Director(genes: genes).frame(FaceScene(act: .awake), level: 0, t: 0.3)
        XCTAssertFalse(frame.arcs.isEmpty)
        XCTAssertEqual(Decor.hsl(0, 1, 0.5), 0xFF0000)
        XCTAssertEqual(Decor.hsl(120, 1, 0.5), 0x00FF00)
    }
}
