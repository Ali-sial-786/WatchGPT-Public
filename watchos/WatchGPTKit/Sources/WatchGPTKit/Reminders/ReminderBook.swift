import Foundation

/// The reminders and events kept on the watch, in a protected file. Manual use needs no phone and no internet.
public actor ReminderBook {
    private let store: DataStore
    private var cache: [Entry]?

    public init(store: DataStore) {
        self.store = store
    }

    public func all() -> [Entry] {
        if let cache { return cache }
        let entries = (try? store.read(Self.name)).flatMap { $0 }.flatMap { try? JSONDecoder().decode([Entry].self, from: $0) } ?? []
        cache = entries
        return entries
    }

    public func entry(_ id: String) -> Entry? {
        all().first { $0.id == id }
    }

    /// Adds [entry], or replaces the one with its id.
    public func save(_ entry: Entry) {
        var entries = all()
        if let i = entries.firstIndex(where: { $0.id == entry.id }) { entries[i] = entry } else { entries.append(entry) }
        write(entries)
    }

    public func delete(_ id: String) {
        write(all().filter { $0.id != id })
    }

    public func clear() {
        write([])
    }

    /// Done: a one-off is completed, a series moves on past [occurrence] (default: the one due now).
    @discardableResult
    public func complete(_ id: String, occurrence: LocalDateTime? = nil, now: Date = Date(), zone: TimeZone = .current) -> Entry? {
        guard let entry = entry(id) else { return nil }
        let done = Recurrence.complete(entry, now: now, zone: zone, occurrence: occurrence)
        save(done)
        return done
    }

    /// Postpones the alert that is due (one in the last hour, or a notification just tapped: [fromNow]) or else the next
    /// one by [minutes]; never brings an alert forward.
    @discardableResult
    public func snooze(_ id: String, minutes: Int, now: Date = Date(), zone: TimeZone = .current, fromNow: Bool = false) -> Entry? {
        guard var entry = entry(id) else { return nil }
        let due = AlertPlan.alerts(for: entry, now: now.addingTimeInterval(-3600), zone: zone, max: 1).first?.at ?? now
        let from = fromNow ? now : max(now, due)
        entry.snoozeUntil = Int(from.addingTimeInterval(TimeInterval(minutes * 60)).timeIntervalSince1970 * 1000)
        entry.completed = false
        save(entry)
        return entry
    }

    private func write(_ entries: [Entry]) {
        cache = entries
        if let data = try? JSONEncoder().encode(entries) { try? store.write(Self.name, data) }
    }

    private static let name = "reminders"
}

/// One notification to schedule: when it rings and which occurrence it's for. [key] is the occurrence's wall-clock
/// time (or "snooze:<ms>"), so Done on an old notification can tell whether it still applies.
public struct PlannedAlert: Equatable, Sendable {
    public let entryId: String
    public let key: String
    public let at: Date
    public let title: String
    public let body: String

    /// The notification's identifier.
    public var id: String { "\(AlertPlan.prefix)\(entryId)@\(key)" }
}

/// Which notifications the watch keeps scheduled. watchOS keeps at most 64 per app, so the nearest alerts go in first,
/// several occurrences of a series each, and the plan is redone whenever the app runs or an alert is acted on.
public enum AlertPlan {
    public static let prefix = "reminder."
    /// All-day items alert in the morning of their day, not at midnight.
    public static let allDayHour = 9

    public static func upcoming(_ entries: [Entry], now: Date, zone: TimeZone, limit: Int = 56) -> [PlannedAlert] {
        entries.flatMap { alerts(for: $0, now: now, zone: zone, max: $0.repeat == .none ? 1 : 6) }
            .sorted { $0.at < $1.at }
            .prefix(limit)
            .map { $0 }
    }

    /// The next [max] alerts of [entry] after [now]. A snooze replaces the alerts it postponed.
    public static func alerts(for entry: Entry, now: Date, zone device: TimeZone, max: Int) -> [PlannedAlert] {
        guard !entry.completed else { return [] }
        var out: [PlannedAlert] = []
        let snooze = entry.snooze.flatMap { $0 > now ? $0 : nil }
        if let snooze, let ms = entry.snoozeUntil {
            out.append(PlannedAlert(entryId: entry.id, key: "snooze:\(ms)", at: snooze, title: entry.title, body: body(entry, snoozed: true)))
        }
        let zone = entry.effectiveZone(device)
        let lead = TimeInterval(entry.alertMinutesBefore * 60)
        let after = snooze ?? now
        // Search by occurrence, starting early enough that an all-day item's morning alert isn't passed over.
        var from = after.addingTimeInterval(lead - (entry.allDay ? TimeInterval((allDayHour + 2) * 3600) : 0))
        for _ in 0 ..< max * 2 + 4 {
            guard out.count < max, let local = Recurrence.occurrence(entry, from: from, zone: zone) else { break }
            let at = wall(entry, local).date(in: zone).addingTimeInterval(-lead)
            if at > after {
                out.append(PlannedAlert(entryId: entry.id, key: local.description, at: at, title: entry.title, body: body(entry, snoozed: false)))
            }
            from = local.date(in: zone).addingTimeInterval(1)
        }
        return out
    }

    /// Whether Done or Snooze on the notification for [key] still applies: not when the series has already moved past
    /// that occurrence, the one-off was moved or completed, or that snooze was replaced.
    public static func current(_ entry: Entry, key: String) -> Bool {
        if entry.completed { return false }
        if key.hasPrefix("snooze:") { return entry.snoozeUntil.map { "snooze:\($0)" } == key }
        guard let occurrence = LocalDateTime(key) else { return true }
        return entry.repeat == .none ? entry.localTime == occurrence : entry.localTime <= occurrence
    }

    private static func wall(_ entry: Entry, _ local: LocalDateTime) -> LocalDateTime {
        entry.allDay ? local.at(hour: allDayHour, minute: 0) : local
    }

    private static func body(_ entry: Entry, snoozed: Bool) -> String {
        let notes = entry.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        if !notes.isEmpty { return String(notes.prefix(200)) }
        if snoozed { return "Snoozed reminder" }
        return entry.kind == .event ? (entry.allDay ? "Today" : "Starting") : "Reminder"
    }
}

/// What lists show: when an item is next due, in the watch's local time.
public enum Agenda {
    /// A series shows its next occurrence (or one missed moments ago, or today's for an all-day item); a one-off its own time.
    public static func current(_ entry: Entry, now: Date, zone device: TimeZone) -> LocalDateTime {
        let zone = entry.effectiveZone(device)
        if entry.repeat == .none || entry.completed { return entry.localTime }
        let from = entry.allDay ? LocalDateTime(now, in: zone).startOfDay.date(in: zone) : now.addingTimeInterval(-3600)
        return Recurrence.occurrence(entry, from: from, zone: zone) ?? entry.localTime
    }

    /// [current] in the watch's zone: all-day items stay on their date.
    public static func shown(_ entry: Entry, now: Date, zone device: TimeZone) -> LocalDateTime {
        let local = current(entry, now: now, zone: device)
        return entry.allDay ? local : LocalDateTime(local.date(in: entry.effectiveZone(device)), in: device)
    }

    /// Whether the item's time has passed: for an all-day item once its day is over, for an event once it has ended.
    public static func overdue(_ entry: Entry, now: Date, zone device: TimeZone) -> Bool {
        if entry.completed { return false }
        let shown = shown(entry, now: now, zone: device)
        let end: LocalDateTime
        if entry.allDay {
            end = shown.startOfDay.plusDays(1)
        } else if entry.kind == .event {
            end = shown.plusMinutes(entry.durationMinutes)
        } else {
            end = shown
        }
        return end.date(in: device) < now
    }

    /// The item's occurrences from [from] (inclusive) to [to] (exclusive), in the watch's zone.
    public static func occurrences(_ entry: Entry, from: LocalDateTime, to: LocalDateTime, zone device: TimeZone, max: Int = 62) -> [LocalDateTime] {
        if entry.completed { return [] }
        let zone = entry.effectiveZone(device)
        func shown(_ local: LocalDateTime) -> LocalDateTime {
            entry.allDay ? local : LocalDateTime(local.date(in: zone), in: device)
        }
        if entry.repeat == .none {
            let at = shown(entry.localTime)
            return at >= from && at < to ? [at] : []
        }
        var out: [LocalDateTime] = []
        var cursor = from.date(in: device)
        while out.count < max, let local = Recurrence.occurrence(entry, from: cursor, zone: zone) {
            let at = shown(local)
            if at >= to { break }
            if at >= from { out.append(at) }
            cursor = local.date(in: zone).addingTimeInterval(1)
        }
        return out
    }
}
