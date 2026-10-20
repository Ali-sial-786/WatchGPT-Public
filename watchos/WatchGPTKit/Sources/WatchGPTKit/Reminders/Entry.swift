import Foundation

/// A wall-clock date and time without a zone, "2026-09-28T15:00": what reminders keep, so 09:00 stays 09:00 across DST.
public struct LocalDateTime: Comparable, Hashable, Sendable, CustomStringConvertible, Codable {
    public let year: Int
    public let month: Int
    public let day: Int
    public let hour: Int
    public let minute: Int

    public init(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) {
        self.year = year
        self.month = month
        self.day = day
        self.hour = hour
        self.minute = minute
    }

    /// "2026-09-28T15:00" or "2026-09-28T15:00:00"; nil for anything else or a date that doesn't exist.
    public init?(_ text: String) {
        let parts = text.split(separator: "T", omittingEmptySubsequences: false)
        guard parts.count == 2, let date = LocalDateTime.date(String(parts[0])) else { return nil }
        let time = parts[1].split(separator: ":").map { Int($0) }
        guard (2 ... 3).contains(time.count), let h = time[0], let m = time[1], (0 ... 23).contains(h), (0 ... 59).contains(m),
              time.count == 2 || (time[2].map { (0 ... 59).contains($0) } ?? false)
        else { return nil }
        self.init(year: date.year, month: date.month, day: date.day, hour: h, minute: m)
    }

    /// "2026-09-28": midnight of that day.
    public static func date(_ text: String) -> LocalDateTime? {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false).map { Int($0) }
        guard parts.count == 3, parts.allSatisfy({ $0 != nil }), let y = parts[0], let mo = parts[1], let d = parts[2],
              (1 ... 12).contains(mo), (1 ... LocalDateTime.length(y, mo)).contains(d)
        else { return nil }
        return LocalDateTime(year: y, month: mo, day: d)
    }

    public init(_ date: Date, in zone: TimeZone) {
        let c = Self.calendar(zone).dateComponents([.year, .month, .day, .hour, .minute], from: date)
        self.init(year: c.year!, month: c.month!, day: c.day!, hour: c.hour!, minute: c.minute!)
    }

    public init(from decoder: Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard let value = LocalDateTime(text) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "not a local date and time: \(text)"))
        }
        self = value
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(description)
    }

    public var description: String {
        String(format: "%04d-%02d-%02dT%02d:%02d", year, month, day, hour, minute)
    }

    /// The instant it is in [zone]. A time in a DST gap moves forward through the gap.
    public func date(in zone: TimeZone) -> Date {
        Self.calendar(zone).date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    public var startOfDay: LocalDateTime { LocalDateTime(year: year, month: month, day: day) }

    public func at(hour: Int, minute: Int) -> LocalDateTime {
        LocalDateTime(year: year, month: month, day: day, hour: hour, minute: minute)
    }

    public func plusDays(_ n: Int) -> LocalDateTime {
        let moved = Self.utc.date(byAdding: .day, value: n, to: date(in: Self.gmt))!
        return LocalDateTime(moved, in: Self.gmt)
    }

    public func plusMinutes(_ n: Int) -> LocalDateTime {
        LocalDateTime(date(in: Self.gmt).addingTimeInterval(TimeInterval(n * 60)), in: Self.gmt)
    }

    /// [n] months on, on [day] (or the month's last day when it's shorter).
    public func plusMonths(_ n: Int, day wanted: Int? = nil) -> LocalDateTime {
        let total = year * 12 + (month - 1) + n
        let y = total / 12
        let m = total % 12 + 1
        return LocalDateTime(year: y, month: m, day: min(wanted ?? day, Self.length(y, m)), hour: hour, minute: minute)
    }

    /// 1 Sunday … 7 Saturday.
    public var weekday: Int {
        Self.utc.component(.weekday, from: date(in: Self.gmt))
    }

    public static func daysBetween(_ a: LocalDateTime, _ b: LocalDateTime) -> Int {
        utc.dateComponents([.day], from: a.startOfDay.date(in: gmt), to: b.startOfDay.date(in: gmt)).day!
    }

    public static func < (a: LocalDateTime, b: LocalDateTime) -> Bool {
        (a.year, a.month, a.day, a.hour, a.minute) < (b.year, b.month, b.day, b.hour, b.minute)
    }

    static func length(_ year: Int, _ month: Int) -> Int {
        switch month {
        case 2: (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    static func calendar(_ zone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return calendar
    }

    private static let gmt = TimeZone(identifier: "UTC")!
    private static let utc = calendar(gmt)
}

public enum Repeat: String, Codable, CaseIterable, Sendable { case none, daily, weekdays, weekly, monthly }

public enum EntryKind: String, Codable, Sendable { case reminder, event }

/// A reminder or an agenda event, kept on the watch. Local wall time plus a zone policy keeps 09:00 across DST and
/// travel; a monthly repeat keeps its original day. The Android app's item, without the fields only sync needs.
public struct Entry: Codable, Equatable, Identifiable, Sendable {
    public var id: String = UUID().uuidString
    public var title: String
    public var notes: String = ""
    public var localTime: LocalDateTime
    public var zone: String = TimeZone.current.identifier
    public var followsDeviceZone: Bool = true
    public var `repeat`: Repeat = .none
    public var monthlyDay: Int?
    public var allDay: Bool = false
    public var alertMinutesBefore: Int = 0
    public var kind: EntryKind = .reminder
    public var durationMinutes: Int = 30
    public var completed: Bool = false
    /// Epoch milliseconds.
    public var snoozeUntil: Int?

    public init(
        title: String,
        notes: String = "",
        localTime: LocalDateTime,
        repeat: Repeat = .none,
        allDay: Bool = false,
        alertMinutesBefore: Int = 0,
        kind: EntryKind = .reminder,
        durationMinutes: Int = 30
    ) {
        self.title = title
        self.notes = notes
        self.localTime = localTime
        self.repeat = `repeat`
        self.allDay = allDay
        self.alertMinutesBefore = alertMinutesBefore
        self.kind = kind
        self.durationMinutes = durationMinutes
    }

    /// The zone the wall time is read in. All-day items are dates, so they are on their day wherever the watch is.
    public func effectiveZone(_ device: TimeZone) -> TimeZone {
        followsDeviceZone || allDay ? device : TimeZone(identifier: zone) ?? device
    }

    public var snooze: Date? { snoozeUntil.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) } }
}

public enum Recurrence {
    /// The next time [entry] is due at or after [from], snooze included; nil when it's done.
    public static func next(_ entry: Entry, from: Date, zone device: TimeZone) -> Date? {
        if entry.completed { return nil }
        if let snooze = entry.snooze, snooze >= from { return snooze }
        let zone = entry.effectiveZone(device)
        return occurrence(entry, from: from, zone: zone)?.date(in: zone)
    }

    /// The wall-clock time of the first scheduled occurrence at or after [from], ignoring snooze.
    public static func occurrence(_ entry: Entry, from: Date, zone: TimeZone) -> LocalDateTime? {
        let seed = entry.localTime
        if entry.repeat == .none { return seed.date(in: zone) >= from ? seed : nil }
        let target = LocalDateTime(from, in: zone)
        var n: Int
        switch entry.repeat {
        case .monthly: n = (target.year * 12 + target.month) - (seed.year * 12 + seed.month)
        case .weekly: n = LocalDateTime.daysBetween(seed, target) / 7
        default: n = LocalDateTime.daysBetween(seed, target)
        }
        n = max(n, 0)
        for _ in 0 ..< 10 {
            let local: LocalDateTime
            switch entry.repeat {
            case .monthly: local = seed.plusMonths(n, day: entry.monthlyDay ?? seed.day)
            case .weekly: local = seed.plusDays(7 * n)
            default: local = seed.plusDays(n)
            }
            if local.date(in: zone) >= from, entry.repeat != .weekdays || ![1, 7].contains(local.weekday) { return local }
            n += 1
        }
        return nil
    }

    /// Completing an occurrence: a one-off is done; a series moves to the occurrence after [occurrence] (a
    /// notification's, or the one a list shows), else after the one due now.
    public static func complete(_ entry: Entry, now: Date, zone device: TimeZone, occurrence: LocalDateTime? = nil) -> Entry {
        var done = entry
        done.snoozeUntil = nil
        if entry.repeat == .none {
            done.completed = true
            return done
        }
        let zone = entry.effectiveZone(device)
        // The first real occurrence: a weekdays series written for a Saturday starts on Monday.
        guard let first = self.occurrence(done, from: entry.localTime.date(in: zone), zone: zone) else {
            done.completed = true
            return done
        }
        // Done on an advance alert (09:45 for 10:00) completes the occurrence it announced.
        let at = occurrence?.date(in: zone) ?? now.addingTimeInterval(TimeInterval(entry.alertMinutesBefore * 60))
        guard let following = self.occurrence(done, from: max(at, first.date(in: zone)).addingTimeInterval(1), zone: zone) else {
            done.completed = true
            return done
        }
        done.monthlyDay = entry.monthlyDay ?? entry.localTime.day
        done.localTime = following
        return done
    }
}
