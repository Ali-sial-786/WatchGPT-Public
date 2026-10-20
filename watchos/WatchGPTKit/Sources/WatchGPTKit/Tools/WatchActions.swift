import Foundation

/// An event in the watch's calendar (the iPhone's calendars, synced to the watch).
public struct CalendarItem: Equatable, Sendable {
    public let title: String
    public let start: Date
    public let end: Date
    public let allDay: Bool
    public let location: String?

    public init(title: String, start: Date, end: Date, allDay: Bool, location: String? = nil) {
        self.title = title
        self.start = start
        self.end = end
        self.allDay = allDay
        self.location = location
    }
}

public struct Contact: Equatable, Sendable {
    public let name: String
    public let nickname: String?
    /// Label ("mobile") and number, the first one is used.
    public let phones: [Phone]

    public struct Phone: Equatable, Sendable {
        public let label: String
        public let number: String

        public init(label: String, number: String) {
            self.label = label
            self.number = number
        }
    }

    public init(name: String, nickname: String? = nil, phones: [Phone]) {
        self.name = name
        self.nickname = nickname
        self.phones = phones
    }
}

/// What the app may read, or why not.
public enum Access<Value> {
    case granted(Value)
    /// The user hasn't allowed it (or said no).
    case denied
    /// The watch can't do it at all.
    case unavailable
}

/// What the tools need from watchOS. The app implements it with HealthKit, EventKit, Contacts, notifications and
/// WatchKit; tests with fakes.
public protocol WatchDevice: AnyObject {
    /// 0...1 and whether it's charging; nil when the watch can't tell.
    func battery() async -> (level: Double?, charging: Bool?)
    /// A notification after [seconds]. False when notifications aren't allowed.
    func startTimer(seconds: Int, label: String?) async -> Bool
    /// The reminders changed: schedule their notifications again.
    func remindersChanged() async
    func sleep(from: Date, to: Date) async -> Access<[SleepSample]>
    func calendar(from: Date, to: Date) async -> Access<[CalendarItem]>
    func contacts() async -> Access<[Contact]>
    /// Starts the call (the Phone app takes over). False when it couldn't.
    func call(_ number: String) async -> Bool
    /// Opens Messages with the text; the user taps Send. False when it couldn't.
    func compose(to number: String, body: String) async -> Bool
    /// The conversation ends after the goodbye being said.
    func endConversation()
    var hours24: Bool { get }
}

/// What WatchGPT can do on the Apple Watch, as tools for ChatGPT. Names, arguments and wording follow the Android app's
/// actions where the watch can do the same thing; [Safety.levels] says how much each can do.
public final class WatchActions: Toolbox {
    private let reminders: ReminderBook
    private let notes: NoteBook
    private let memory: Memory
    private let device: WatchDevice
    private let pending: Pending
    private let turns: UserTurns
    private let now: () -> Date
    private let zone: () -> TimeZone

    public init(
        reminders: ReminderBook,
        notes: NoteBook,
        memory: Memory,
        device: WatchDevice,
        pending: Pending,
        turns: UserTurns,
        now: @escaping () -> Date = { Date() },
        zone: @escaping () -> TimeZone = { .current }
    ) {
        self.reminders = reminders
        self.notes = notes
        self.memory = memory
        self.device = device
        self.pending = pending
        self.turns = turns
        self.now = now
        self.zone = zone
    }

    public func tools() -> [ToolSpec] {
        Self.specs
    }

    public func run(name: String, arguments: String, context _: ToolContext) async -> String {
        guard let args = ToolArgs.parse(arguments) else { return "error: the arguments were not valid JSON" }
        switch name {
        case Self.deviceStatus: return await deviceStatus()
        case Self.end:
            device.endConversation()
            return "ok: the conversation ends right after your goodbye; say it in two or three words"
        case Self.addNote: return await addNote(args)
        case Self.listNotes: return await listNotes(args)
        case Self.addReminder: return await addReminder(args)
        case Self.addEvent: return await addEvent(args)
        case Self.listReminders: return await listReminders()
        case Self.listEvents: return await listEvents(args)
        case Self.changeReminder: return await changeReminder(args)
        case Self.setTimer: return await setTimer(args)
        case Self.readSleep: return await readSleep(args)
        case Self.call: return await call(args)
        case Self.sendText: return await sendText(args)
        case Self.confirm:
            guard let answer = ToolArgs.choice(args, "answer", ["yes", "no"]) else { return "error: answer must be yes or no" }
            return await pending.decide(yes: answer == "yes")
        case Self.remember: return await remember(args)
        case Self.forget:
            guard let id = ToolArgs.int(args, "id", 1 ... 100_000) else { return "error: give the number of what to forget" }
            return await memory.forget(id) ? "ok: forgot [\(id)]" : "error: there's nothing remembered as [\(id)]"
        case Self.recall: return await recall(args)
        default: return "error: there is no action called \(name)"
        }
    }

    // MARK: - The watch

    private func deviceStatus() async -> String {
        let (level, charging) = await device.battery()
        guard let level else { return "The watch can't tell its battery level right now." }
        let state = charging.map { $0 ? ", charging" : ", not charging" } ?? ""
        return "Watch battery \(Int((level * 100).rounded())) percent\(state)."
    }

    private func setTimer(_ args: [String: JSON]) async -> String {
        guard let seconds = ToolArgs.int(args, "seconds", 1 ... 86400) else { return "error: say how long the timer should run, 1 second to 24 hours" }
        let label = ToolArgs.text(args, "label", max: 60)
        guard await device.startTimer(seconds: seconds, label: label) else {
            return "error: WatchGPT can't show notifications, so it can't ring a timer. Tell the user to allow notifications for WatchGPT in the watch's settings."
        }
        let ends = LocalDateTime(now().addingTimeInterval(TimeInterval(seconds)), in: zone())
        return "ok: a timer notification rings in \(SleepReport.say(TimeInterval(seconds))), at \(ToolArgs.sayClock(ends, hours24: device.hours24)). " +
            "It's WatchGPT's notification, not the Timer app."
    }

    // MARK: - Notes

    private func addNote(_ args: [String: JSON]) async -> String {
        guard let text = ToolArgs.text(args, "text", max: Self.noteMax) else { return "error: say what to note, in at most \(Self.noteMax) characters" }
        await notes.add(text)
        return "ok: noted"
    }

    private func listNotes(_ args: [String: JSON]) async -> String {
        let limit = ToolArgs.int(args, "limit", 1 ... 20) ?? 5
        let newest = await notes.all().suffix(limit).reversed()
        guard !newest.isEmpty else { return "There are no notes yet." }
        return "Your newest notes: " + newest.map { $0.text.replacingOccurrences(of: "\n", with: " ") }.joined(separator: " | ")
    }

    // MARK: - Reminders and agenda

    private func addReminder(_ args: [String: JSON]) async -> String {
        guard let text = ToolArgs.text(args, "text", max: Self.titleMax) else { return "error: say what to remind of" }
        guard case let .at(at)? = ToolArgs.time(ToolArgs.text(args, "at", max: 40), zone: zone()) else {
            return "error: give the time as a local date and time, like 2026-09-28T15:00"
        }
        let repeats = ToolArgs.choice(args, "repeat", Self.repeats).flatMap(Repeat.init(rawValue:)) ?? .none
        var entry = Entry(title: text, notes: ToolArgs.text(args, "notes", max: 1000) ?? "", localTime: at, repeat: repeats)
        entry.zone = zone().identifier
        if repeats == .none, at.date(in: zone()) < now().addingTimeInterval(-60) { return "error: that time has passed; ask the user when" }
        await reminders.save(entry)
        await device.remindersChanged()
        let next = Agenda.shown(entry, now: now(), zone: zone())
        return "ok: reminder \"\(text)\" set for \(say(next))\(repeatText(repeats)). It alerts on this watch."
    }

    private func addEvent(_ args: [String: JSON]) async -> String {
        guard let title = ToolArgs.text(args, "title", max: Self.titleMax) else { return "error: the event needs a title" }
        guard let start = ToolArgs.time(ToolArgs.text(args, "start", max: 40), zone: zone()) else {
            return "error: give the start as a local date and time, like 2026-09-28T15:00, or a date for all day"
        }
        var notes = ToolArgs.text(args, "notes", max: 1000) ?? ""
        if let place = ToolArgs.text(args, "location", max: 200) { notes = notes.isEmpty ? place : "\(place)\n\(notes)" }
        var entry: Entry
        switch start {
        case let .day(day):
            entry = Entry(title: title, notes: notes, localTime: day, allDay: true, kind: .event, durationMinutes: 24 * 60)
        case let .at(at):
            var minutes = ToolArgs.int(args, "duration_minutes", 5 ... 1440) ?? 60
            if case let .at(end)? = ToolArgs.time(ToolArgs.text(args, "end", max: 40), zone: zone()) {
                let span = Int(end.date(in: zone()).timeIntervalSince(at.date(in: zone())) / 60)
                guard span > 0 else { return "error: the end is before the start" }
                minutes = min(span, 10080)
            }
            entry = Entry(title: title, notes: notes, localTime: at, kind: .event, durationMinutes: minutes)
            entry.alertMinutesBefore = ToolArgs.int(args, "alert_minutes_before", 0 ... 10080) ?? 0
        }
        entry.zone = zone().identifier
        await reminders.save(entry)
        await device.remindersChanged()
        let when = entry.allDay ? "all day \(ToolArgs.sayDay(entry.localTime, now: now(), zone: zone()))" : say(entry.localTime)
        return "ok: event \"\(title)\" added for \(when). It alerts on this watch\(entry.allDay ? " at 9:00 that day" : "")."
    }

    private func listReminders() async -> String {
        let open = await reminders.all()
            .filter { !$0.completed && $0.kind == .reminder }
            .sorted { Recurrence.next($0, from: now(), zone: zone()) ?? .distantFuture < Recurrence.next($1, from: now(), zone: zone()) ?? .distantFuture }
        guard !open.isEmpty else { return "There are no open reminders." }
        let named = open.prefix(Self.listMax).map { entry -> String in
            let late = Agenda.overdue(entry, now: now(), zone: zone()) ? ", overdue" : ""
            return "\(entry.title), \(say(Agenda.shown(entry, now: now(), zone: zone())))\(repeatText(entry.repeat))\(late)"
        }
        let more = open.count > Self.listMax ? " | and \(open.count - Self.listMax) more" : ""
        return "Open reminders, soonest first: " + named.joined(separator: " | ") + more
    }

    private func listEvents(_ args: [String: JSON]) async -> String {
        let z = zone()
        let today = LocalDateTime(now(), in: z).startOfDay
        let from = ToolArgs.time(ToolArgs.text(args, "from", max: 40), zone: z)?.local ?? today
        var to = ToolArgs.time(ToolArgs.text(args, "to", max: 40), zone: z).map { when -> LocalDateTime in
            if case let .day(d) = when { return d.plusDays(1) }
            return when.local
        } ?? from.startOfDay.plusDays(1)
        if to <= from { to = from.startOfDay.plusDays(1) }
        if LocalDateTime.daysBetween(from, to) > 31 { to = from.plusDays(31) }
        var items: [(LocalDateTime, String)] = []
        for entry in await reminders.all() {
            for at in Agenda.occurrences(entry, from: from, to: to, zone: z) {
                let what = entry.kind == .event ? entry.title : "reminder: \(entry.title)"
                items.append((at, entry.allDay ? "\(what), all day \(ToolArgs.sayDay(at, now: now(), zone: z))" : "\(what), \(say(at))"))
            }
        }
        var note = ""
        switch await device.calendar(from: from.date(in: z), to: to.date(in: z)) {
        case let .granted(events):
            for event in events {
                let at = LocalDateTime(event.start, in: z)
                let place = event.location.map { ", at \($0)" } ?? ""
                if event.allDay {
                    items.append((at.startOfDay, "\(event.title), all day \(ToolArgs.sayDay(at, now: now(), zone: z))\(place)"))
                } else {
                    let end = ToolArgs.sayClock(LocalDateTime(event.end, in: z), hours24: device.hours24)
                    items.append((at, "\(event.title), \(say(at)) until \(end)\(place)"))
                }
            }
        case .denied:
            note = " | The watch's calendar isn't shared with WatchGPT; it can be allowed in WatchGPT's settings on the watch."
        case .unavailable:
            break
        }
        guard !items.isEmpty else { return "Nothing on the agenda then." + note }
        let sorted = items.sorted { $0.0 < $1.0 }
        let more = sorted.count > Self.listMax ? " | and \(sorted.count - Self.listMax) more" : ""
        return "On the agenda: " + sorted.prefix(Self.listMax).map(\.1).joined(separator: " | ") + more + note
    }

    private func changeReminder(_ args: [String: JSON]) async -> String {
        guard let operation = ToolArgs.choice(args, "operation", ["complete", "snooze", "delete", "edit"]) else {
            return "error: operation must be complete, snooze, delete or edit"
        }
        let all = await reminders.all()
        let found: [Entry]
        if let id = ToolArgs.text(args, "id", max: 64) {
            found = all.filter { $0.id == id }
        } else if let title = ToolArgs.text(args, "match_title", max: Self.titleMax) {
            found = all.filter { $0.title.caseInsensitiveCompare(title) == .orderedSame }
        } else {
            return "error: say which item, by its exact title"
        }
        guard found.count == 1, let entry = found.first else {
            return found.isEmpty ? "error: there's no reminder or event with that title; ask the user for its exact title" :
                "error: \(found.count) items have that title, so nothing was changed; ask the user to tell them apart"
        }
        switch operation {
        case "complete":
            await reminders.complete(entry.id, now: now(), zone: zone())
            await device.remindersChanged()
            return entry.repeat == .none ? "ok: \"\(entry.title)\" is done" : "ok: this time of \"\(entry.title)\" is done; it comes back next time"
        case "snooze":
            let minutes = ToolArgs.int(args, "minutes", 1 ... 10080) ?? 10
            await reminders.snooze(entry.id, minutes: minutes, now: now(), zone: zone())
            await device.remindersChanged()
            return "ok: \"\(entry.title)\" snoozed for \(SleepReport.say(TimeInterval(minutes * 60)))"
        case "delete":
            let what = entry.repeat == .none ? "\"\(entry.title)\"" : "the whole series \"\(entry.title)\""
            return pending.propose("Delete \(what)?") { [reminders, device] in
                await reminders.delete(entry.id)
                await device.remindersChanged()
                return "ok: deleted \(what)"
            }
        default:
            var edited = entry
            if let title = ToolArgs.text(args, "title", max: Self.titleMax) { edited.title = title }
            if let notes = args["notes"]?.string { edited.notes = String(notes.prefix(1000)) }
            if let at = args["at"]?.string {
                guard let when = ToolArgs.time(at, zone: zone()) else { return "error: the new time isn't a local date and time, so nothing was changed" }
                if case let .at(t) = when, !entry.allDay { edited.localTime = t } else { edited.localTime = when.local }
                edited.monthlyDay = nil
                edited.snoozeUntil = nil
                edited.completed = false
            }
            if let repeats = ToolArgs.choice(args, "repeat", Self.repeats).flatMap(Repeat.init(rawValue:)) {
                edited.repeat = repeats
                edited.completed = false
            }
            await reminders.save(edited)
            await device.remindersChanged()
            return "ok: \"\(edited.title)\" is now \(say(Agenda.shown(edited, now: now(), zone: zone())))\(repeatText(edited.repeat))"
        }
    }

    // MARK: - Health

    private func readSleep(_ args: [String: JSON]) async -> String {
        let nights = ToolArgs.int(args, "nights", 1 ... SleepReport.maxNights) ?? 1
        let today = LocalDateTime(now(), in: zone())
        let (from, to) = SleepReport.window(nights: nights, today: today, zone: zone())
        switch await device.sleep(from: from, to: to) {
        case .denied:
            return "error: WatchGPT isn't allowed to read sleep. Tell the user to allow it in WatchGPT's settings on the watch, under Health."
        case .unavailable:
            return "error: this watch has no Apple Health data WatchGPT can read, so it can't tell how the user slept."
        case let .granted(samples):
            let report = SleepReport.describe(SleepReport.sessions(samples), nights: nights, today: today, zone: zone(), hours24: device.hours24)
            return report ?? "error: Apple Health has no sleep for \(nights == 1 ? "last night or the night before" : "the last \(nights) nights"). " +
                "Sleep is recorded when the user wears the watch to bed with Sleep turned on."
        }
    }

    // MARK: - Calls and texts

    private func call(_ args: [String: JSON]) async -> String {
        guard let to = ToolArgs.text(args, "to", max: 100) else { return "error: say who to call" }
        switch await reach(to) {
        case let .failure(error):
            return error.text
        case let .success((name, phone)):
            return pending.propose("Call \(name) (\(phone.label))?") { [device] in
                await device.call(phone.number) ? "ok: the watch is calling \(name)" : "error: the watch couldn't start the call"
            }
        }
    }

    private func sendText(_ args: [String: JSON]) async -> String {
        guard let to = ToolArgs.text(args, "to", max: 100) else { return "error: say who to text" }
        guard let text = ToolArgs.text(args, "text", max: 600) else { return "error: say what the message should say" }
        switch await reach(to) {
        case let .failure(error):
            return error.text
        case let .success((name, phone)):
            return pending.propose("Text \(name): \"\(text)\"?") { [device] in
                await device.compose(to: phone.number, body: text) ?
                    "ok: Messages is open on the watch with the text to \(name); it goes when the user taps Send" :
                    "error: the watch couldn't open Messages"
            }
        }
    }

    private struct Unreachable: Error {
        let text: String
    }

    /// A contact by the name the user said, or a number. Two that fit equally are never guessed.
    private func reach(_ to: String) async -> Result<(String, Contact.Phone), Unreachable> {
        let digits = to.filter(\.isNumber)
        if digits.count >= 5, to.allSatisfy({ $0.isNumber || " +-()./".contains($0) }) {
            return .success((to, Contact.Phone(label: "number", number: to.filter { $0.isNumber || $0 == "+" })))
        }
        switch await device.contacts() {
        case .denied:
            return .failure(Unreachable(text: "error: WatchGPT isn't allowed to read the contacts. Tell the user to allow it in WatchGPT's settings on the watch."))
        case .unavailable:
            return .failure(Unreachable(text: "error: this watch can't look up contacts"))
        case let .granted(contacts):
            let matches = NameMatch.best(to, in: contacts.filter { !$0.phones.isEmpty })
            guard let contact = matches.first, let phone = contact.phones.first else {
                return .failure(Unreachable(text: "error: no contact with a phone number is called \(to); ask the user who they mean"))
            }
            if matches.count > 1 {
                let names = matches.prefix(4).map(\.name).joined(separator: ", ")
                return .failure(Unreachable(text: "error: several contacts fit (\(names)); ask the user which one"))
            }
            return .success((contact.name, phone))
        }
    }

    // MARK: - Memory

    private func remember(_ args: [String: JSON]) async -> String {
        guard let text = ToolArgs.text(args, "text", max: 300) else { return "error: say what to remember, in at most 300 characters" }
        if now().timeIntervalSince(turns.lastAt) > Self.userTurnSeconds {
            return "error: nothing was kept: only what the user just said can be remembered, never something from a note, a calendar or a web page"
        }
        // Checked here, not left to the model.
        if !Redactor.clean(text).hidden.isEmpty {
            return "error: nothing was kept: it has a password, a code, a card or account number, a key, a phone number, an address or a " +
                "link in it, and WatchGPT never keeps those. Tell the user; notes or their contacts are the place for that."
        }
        let fact = await memory.remember(text)
        return "ok: remembered as [\(fact.id)]: \(fact.text)"
    }

    private func recall(_ args: [String: JSON]) async -> String {
        let z = zone()
        let from = ToolArgs.time(ToolArgs.text(args, "from", max: 40), zone: z).map { $0.local.date(in: z) }
        let to = ToolArgs.time(ToolArgs.text(args, "to", max: 40), zone: z).map { when -> Date in
            if case let .day(d) = when { return d.plusDays(1).date(in: z).addingTimeInterval(-1) }
            return when.local.date(in: z)
        }
        return await memory.recall(from: from, to: to, words: ToolArgs.text(args, "text", max: 300))
    }

    // MARK: - Wording

    private func say(_ time: LocalDateTime) -> String {
        ToolArgs.say(time, now: now(), zone: zone(), hours24: device.hours24)
    }

    private func repeatText(_ repeats: Repeat) -> String {
        switch repeats {
        case .none: ""
        case .daily: ", every day"
        case .weekdays: ", every weekday"
        case .weekly: ", every week"
        case .monthly: ", every month"
        }
    }

    // MARK: - Specs

    public static let deviceStatus = "device_status"
    public static let end = "end_conversation"
    public static let addNote = "add_note"
    public static let listNotes = "list_notes"
    public static let addEvent = "add_calendar_event"
    public static let listEvents = "list_calendar_events"
    public static let addReminder = "add_reminder"
    public static let listReminders = "list_reminders"
    public static let changeReminder = "change_reminder"
    public static let setTimer = "set_timer"
    public static let readSleep = "read_sleep"
    public static let sendText = "send_text_message"
    public static let call = "call_contact"
    public static let confirm = "confirm_action"
    public static let remember = "remember"
    public static let forget = "forget"
    public static let recall = "recall_conversations"

    private static let noteMax = 1000
    private static let titleMax = 200
    private static let listMax = 15
    private static let userTurnSeconds: TimeInterval = 60
    private static let repeats: Set<String> = ["none", "daily", "weekdays", "weekly", "monthly"]

    private static let noArgs = #"{"type":"object","properties":{},"additionalProperties":false}"#
    private static let localTime = "Local date and time without a zone, like 2026-09-28T15:00, or just a date for all day."
    private static let readOut =
        #""instruction":{"type":"string","description":"In English: what the user wants to know from it."},"# +
        #""language":{"type":"string","description":"The language the user is speaking, as a BCP 47 tag: the watch speaks in it."},"# +
        #""details":{"type":"boolean","description":"True only when the user asked for a code, a number or a link itself."}"#
    private static let nothingYet = "Nothing happens yet: the result gives you what to read back to the user; only after they say yes, call confirm_action."

    public static let specs: [ToolSpec] = [
        ToolSpec(addNote, "Save a note for the user in their WatchGPT notes (use for 'note that…', 'add … to my notes', shopping items, ideas).",
                 #"{"type":"object","properties":{"text":{"type":"string","description":"The note, in the user's words."}},"required":["text"],"additionalProperties":false}"#),
        ToolSpec(listNotes, "Read the user's newest notes. The watch reads them out itself.",
                 #"{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":20},"# + readOut + #"},"additionalProperties":false}"#),
        ToolSpec(addEvent, "Create a WatchGPT agenda event on the watch. It alerts at its start, or alert_minutes_before; date-only events are all day and alert at 9:00 that day.",
                 #"{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string","description":""# + localTime + #""},"end":{"type":"string","description":""# + localTime + #" Optional."},"duration_minutes":{"type":"integer","minimum":5,"maximum":1440,"description":"Used when there is no end. Default 60."},"location":{"type":"string"},"notes":{"type":"string"},"alert_minutes_before":{"type":"integer","minimum":0,"maximum":10080}},"required":["title","start"],"additionalProperties":false}"#),
        ToolSpec(listEvents, "Read the user's agenda between two times (at most 31 days): WatchGPT's events and reminders and the watch's calendar. Defaults to today. The watch reads it out itself.",
                 #"{"type":"object","properties":{"from":{"type":"string","description":""# + localTime + #""},"to":{"type":"string","description":""# + localTime + #""},"# + readOut + #"},"additionalProperties":false}"#),
        ToolSpec(addReminder, "Create a WatchGPT reminder on the watch, optionally repeating. It works offline and alerts as a notification on the watch.",
                 #"{"type":"object","properties":{"text":{"type":"string"},"at":{"type":"string","description":"Local date and time without a zone, like 2026-09-28T15:00."},"notes":{"type":"string"},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly","monthly"]}},"required":["text","at"],"additionalProperties":false}"#),
        ToolSpec(listReminders, "Read the user's open WatchGPT reminders, soonest first. The watch reads them out itself.",
                 #"{"type":"object","properties":{"# + readOut + #"},"additionalProperties":false}"#),
        ToolSpec(changeReminder, "Complete, snooze, edit or delete a WatchGPT reminder or agenda item by the exact title the user said (match_title). Two items with that title are refused; never guess a title. Delete only when the user asks: it is read back and waits for their yes.",
                 #"{"type":"object","properties":{"match_title":{"type":"string","description":"The item's exact existing title, as the user said it."},"operation":{"type":"string","enum":["complete","snooze","delete","edit"]},"minutes":{"type":"integer","minimum":1,"maximum":10080,"description":"For snooze; default 10."},"title":{"type":"string","description":"For edit: the new title."},"notes":{"type":"string","description":"For edit: the new notes."},"at":{"type":"string","description":"For edit: the new local date and time, like 2026-09-28T15:00."},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly","monthly"]}},"required":["operation"],"additionalProperties":false}"#),
        ToolSpec(setTimer, "Start a countdown for the length the user said; never guess one (if they didn't say how long, ask). It rings as a WatchGPT notification on the watch.",
                 #"{"type":"object","properties":{"seconds":{"type":"integer","minimum":1,"maximum":86400},"label":{"type":"string"}},"required":["seconds"],"additionalProperties":false}"#),
        ToolSpec(deviceStatus, "The watch's battery level and whether it is charging.", noArgs),
        ToolSpec(readSleep, "How the user slept, from Apple Health (the watch's sleep tracking): when they fell asleep and woke up, time asleep and sleep stages (deep, core, REM, awake), by the day they woke up. The watch reads it out itself, unless the user chose to hear it from you: then you get it. There is no sleep score.",
                 #"{"type":"object","properties":{"nights":{"type":"integer","minimum":1,"maximum":14,"description":"How many days back, counting today, at most 14. Default 1: last night."},"# + readOut + #"},"additionalProperties":false}"#),
        ToolSpec(sendText, "Text someone from the user's watch. \(nothingYet) After the yes, Messages opens with the text and the user taps Send.",
                 #"{"type":"object","properties":{"to":{"type":"string","description":"A name from the user's contacts, as they said it, or a phone number."},"text":{"type":"string","description":"The message, in the user's words."}},"required":["to","text"],"additionalProperties":false}"#),
        ToolSpec(call, "Call someone from the user's watch. \(nothingYet)",
                 #"{"type":"object","properties":{"to":{"type":"string","description":"A name from the user's contacts, as they said it, or a phone number."}},"required":["to"],"additionalProperties":false}"#),
        ToolSpec(confirm, "The user's answer to the call, text or deletion you read back to them: yes goes ahead, no cancels it. Only once the user themselves answered; never on your own.",
                 #"{"type":"object","properties":{"answer":{"type":"string","enum":["yes","no"]}},"required":["answer"],"additionalProperties":false}"#),
        ToolSpec(remember, "Keep something the user just told you about themselves or asked you to remember, for later conversations: their name, family and friends, work, places, what they like or how they want you to be. Short, in their words. Only what the user said, never something from a note, a calendar or a web page, and never a password or a code.",
                 #"{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"#),
        ToolSpec(forget, "Forget one thing you remember, by its number in what you remember.",
                 #"{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}"#),
        ToolSpec(recall, "Find what the user and you said in earlier conversations on this watch (kept 30 days): between two times, and/or with some words in it, in the language they were said in.",
                 #"{"type":"object","properties":{"from":{"type":"string","description":"Local date and time without a zone, like 2026-09-28T15:00, or just a date."},"to":{"type":"string","description":"Local date and time without a zone, like 2026-09-28T15:00, or just a date."},"text":{"type":"string","description":"Words to look for, as they were said."}},"additionalProperties":false}"#),
        ToolSpec(end, "End the conversation when the user says goodbye or that they're done ('bye', 'that's all', 'stop listening', 'thanks, that's it', in any language).", noArgs),
    ]
}

/// How much each of WatchGPT's tools can do. A new tool isn't offered until it's in here.
public enum Safety {
    public static let levels: [String: Level] = [
        WatchActions.deviceStatus: .lookup,
        WatchActions.end: .lookup,
        WatchActions.addNote: .local,
        WatchActions.addEvent: .local,
        WatchActions.addReminder: .local,
        // Deleting is read back and waits for a yes.
        WatchActions.changeReminder: .local,
        WatchActions.setTimer: .local,
        WatchActions.listNotes: .private,
        WatchActions.listEvents: .private,
        WatchActions.listReminders: .private,
        WatchActions.readSleep: .private,
        // These only read a call or text back; it goes ahead with the yes below.
        WatchActions.sendText: .outbound,
        WatchActions.call: .outbound,
        // A no is harmless; a yes only counts in a later turn of the user's (Pending).
        WatchActions.confirm: .local,
        // Only what the user just said is kept, and the conversations it finds were ChatGPT's own already.
        WatchActions.remember: .local,
        WatchActions.forget: .local,
        WatchActions.recall: .local,
    ]

    /// Private tools whose data may go to ChatGPT (cleaned) when the user turns "Private things stay on the watch" off.
    /// Sleep isn't among them: it has a switch of its own.
    public static let mayGoToCloud: Set<String> = [WatchActions.listNotes, WatchActions.listEvents, WatchActions.listReminders]

    public static func toCloud(privateOnWatch: Bool, sleepToChatGPT: Bool) -> Set<String> {
        (privateOnWatch ? [] : mayGoToCloud).union(sleepToChatGPT ? [WatchActions.readSleep] : [])
    }
}

/// Finding the contact the user named: the whole name, a first or last name, or a nickname, ignoring case and accents.
public enum NameMatch {
    /// The best matches, best first: several only when they fit equally well.
    public static func best(_ said: String, in contacts: [Contact]) -> [Contact] {
        let wanted = Memory.tokens(said)
        guard !wanted.isEmpty else { return [] }
        let scored = contacts.compactMap { contact -> (Int, Contact)? in
            let score = score(wanted, contact)
            return score > 0 ? (score, contact) : nil
        }
        guard let top = scored.map(\.0).max() else { return [] }
        return scored.filter { $0.0 == top }.map(\.1)
    }

    private static func score(_ wanted: [String], _ contact: Contact) -> Int {
        let name = Memory.tokens(contact.name)
        let nick = contact.nickname.map(Memory.tokens) ?? []
        if wanted == name || (!nick.isEmpty && wanted == nick) { return 3 }
        if wanted.allSatisfy({ name.contains($0) || nick.contains($0) }) { return 2 }
        if wanted.allSatisfy({ w in name.contains { $0.hasPrefix(w) } }) { return 1 }
        return 0
    }
}
