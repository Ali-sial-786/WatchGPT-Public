import Foundation

/// Reads and checks what the model passed to a tool. Model output is untrusted: anything odd is rejected.
public enum ToolArgs {
    public static func parse(_ arguments: String) -> [String: JSON]? {
        JSON.parse(arguments)?.object
    }

    /// Trimmed text of at most [max] characters, control characters removed; nil if missing or empty.
    public static func text(_ args: [String: JSON], _ key: String, max: Int) -> String? {
        guard let raw = args[key]?.string else { return nil }
        let clean = String(raw.unicodeScalars.filter { $0 == "\n" || !CharacterSet.controlCharacters.contains($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty || clean.count > max ? nil : clean
    }

    public static func int(_ args: [String: JSON], _ key: String, _ range: ClosedRange<Int>) -> Int? {
        guard let value = args[key]?.int, range.contains(value) else { return nil }
        return value
    }

    public static func bool(_ args: [String: JSON], _ key: String) -> Bool? {
        args[key]?.bool
    }

    /// One of [allowed] (any case), lower-cased; nil if missing or anything else.
    public static func choice(_ args: [String: JSON], _ key: String, _ allowed: Set<String>) -> String? {
        guard let value = text(args, key, max: 40)?.lowercased(), allowed.contains(value) else { return nil }
        return value
    }

    public enum When: Equatable {
        case at(LocalDateTime)
        case day(LocalDateTime)

        public var local: LocalDateTime {
            switch self {
            case let .at(t): t
            case let .day(d): d
            }
        }
    }

    /// "2026-09-28T15:00", "2026-09-28 15:00:00", "2026-09-28T15:00+02:00" (moved to [zone]) or a date "2026-09-28".
    public static func time(_ value: String?, zone: TimeZone) -> When? {
        guard let v = value?.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "T"), !v.isEmpty else { return nil }
        if let local = LocalDateTime(v) { return .at(local) }
        // With a zone, also without seconds: "2026-09-28T15:00+02:00".
        let withSeconds = v.count > 16 && v[v.index(v.startIndex, offsetBy: 16)] != ":" ? String(v.prefix(16)) + ":00" + v.dropFirst(16) : v
        if let date = instant(withSeconds) { return .at(LocalDateTime(date, in: zone)) }
        if let day = LocalDateTime.date(v) { return .day(day) }
        return nil
    }

    private static func instant(_ text: String) -> Date? {
        let iso = ISO8601DateFormatter()
        for options: ISO8601DateFormatter.Options in [[.withInternetDateTime], [.withInternetDateTime, .withFractionalSeconds]] {
            iso.formatOptions = options
            if let date = iso.date(from: text) { return date }
        }
        return nil
    }

    /// "today at 3:00 PM", "tomorrow at 09:00", "Friday 2 October at 09:00": as it's said.
    public static func say(_ time: LocalDateTime, now: Date, zone: TimeZone, hours24: Bool) -> String {
        "\(sayDay(time, now: now, zone: zone)) at \(sayClock(time, hours24: hours24))"
    }

    public static func sayDay(_ time: LocalDateTime, now: Date, zone: TimeZone) -> String {
        let today = LocalDateTime(now, in: zone).startOfDay
        switch LocalDateTime.daysBetween(today, time) {
        case 0: return "today"
        case 1: return "tomorrow"
        case -1: return "yesterday"
        default:
            let format = DateFormatter()
            format.locale = Locale(identifier: "en_US_POSIX")
            format.timeZone = TimeZone(identifier: "UTC")
            format.dateFormat = "EEEE d MMMM"
            return format.string(from: time.startOfDay.date(in: TimeZone(identifier: "UTC")!))
        }
    }

    public static func sayClock(_ time: LocalDateTime, hours24: Bool) -> String {
        if hours24 { return String(format: "%02d:%02d", time.hour, time.minute) }
        let h = time.hour % 12 == 0 ? 12 : time.hour % 12
        return String(format: "%d:%02d ", h, time.minute) + (time.hour < 12 ? "AM" : "PM")
    }
}
