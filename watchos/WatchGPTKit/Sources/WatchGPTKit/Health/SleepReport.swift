import Foundation

/// One stretch of a sleep stage as Apple Health has it (Apple Watch writes the stages one after another).
public struct SleepSample: Equatable, Sendable {
    public enum Stage: Sendable { case awake, core, deep, rem, asleep }

    public let start: Date
    public let end: Date
    public let stage: Stage
    /// The app or device that wrote it ("Apple Watch").
    public let source: String

    public init(start: Date, end: Date, stage: Stage, source: String) {
        self.start = start
        self.end = end
        self.stage = stage
        self.source = source
    }
}

/// One sleep, a night or a nap: samples of one source with less than [SleepReport.sessionGap] between them.
public struct SleepSession: Equatable, Sendable {
    public let start: Date
    public let end: Date
    public let stages: [SleepSample]
    public let source: String
}

/// How the user slept, in plain English sentences the watch reads out, by the day the user woke up, newest first. The
/// Android app's SleepReport, reading Apple Health instead of Health Connect. A day's night is its longest sleep and the
/// sleeps a short wake-up away from it, added up; other sleeps that day are told after it.
public enum SleepReport {
    public static let maxNights = 14
    static let sessionGap: TimeInterval = 3600

    /// What to ask Apple Health for: sleeps that end on one of the last [nights] days (at least two), which started at
    /// most a day before.
    public static func window(nights: Int, today: LocalDateTime, zone: TimeZone) -> (Date, Date) {
        (today.startOfDay.plusDays(-max(nights, 2)).date(in: zone), today.startOfDay.plusDays(1).date(in: zone))
    }

    /// Samples into sessions, per source.
    public static func sessions(_ samples: [SleepSample]) -> [SleepSession] {
        var out: [SleepSession] = []
        for (source, mine) in Dictionary(grouping: samples.filter { $0.end > $0.start }, by: \.source) {
            var current: [SleepSample] = []
            for sample in mine.sorted(by: { $0.start < $1.start }) {
                if let last = current.map(\.end).max(), sample.start.timeIntervalSince(last) >= sessionGap {
                    out.append(session(current, source))
                    current = []
                }
                current.append(sample)
            }
            if !current.isEmpty { out.append(session(current, source)) }
        }
        return out.sorted { $0.start < $1.start }
    }

    /// The last [nights] days up to [today], from [sessions]; nil when none of them has any sleep.
    public static func describe(_ sessions: [SleepSession], nights: Int, today: LocalDateTime, zone: TimeZone, hours24: Bool) -> String? {
        let all = Dictionary(grouping: distinct(sessions)) { LocalDateTime($0.end, in: zone).startOfDay }
        let today = today.startOfDay
        let asked = (0 ..< nights).map { today.plusDays(-$0) }
        // Asked about last night before it reached Apple Health: the night before too, told apart.
        let nightToday = (all[today] ?? []).contains { !nap($0, zone) }
        let days = nights == 1 && !nightToday ? asked + [today.plusDays(-1)] : asked
        let byDay = all.filter { days.contains($0.key) }
        guard !byDay.isEmpty else { return nil }
        let time = DateFormatter()
        time.locale = Locale(identifier: "en_US_POSIX")
        time.timeZone = zone
        time.dateFormat = hours24 ? "HH:mm" : "h:mm a"
        let sources = byDay.values.flatMap { $0 }.map(\.source).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        var lines = ["Sleep in Apple Health, from \(sources.joined(separator: " and "))."]
        var asleep: [TimeInterval] = []
        for day in days {
            let slept = (byDay[day] ?? []).sorted { $0.start < $1.start }
            let label = "Woke up \(dayName(day, today: today))"
            guard let main = slept.max(by: { totals($0).asleep < totals($1).asleep }) else {
                lines.append(day == today ? "\(label): nothing recorded yet." : "\(label): nothing recorded.")
                continue
            }
            if nap(main, zone) {
                lines.append("\(label): no night recorded, only " + slept.map { "a nap, \(tell($0, time))" }.joined(separator: " and ") + ".")
                continue
            }
            let night = nightOf(slept, main)
            let total = night.reduce(0) { $0 + totals($1).asleep }
            asleep.append(total)
            let told = night.count == 1 ? tell(main, time) :
                "\(say(total)) asleep in all, in \(night.count) parts: " + night.map { tell($0, time) }.joined(separator: "; ")
            lines.append("\(label): \(told)." + slept.filter { !night.contains($0) }.map { " Also \(tell($0, time))." }.joined())
        }
        if asleep.count > 1 {
            lines.append("On average \(say(asleep.reduce(0, +) / Double(asleep.count))) asleep over the \(asleep.count) nights with sleep.")
        }
        lines.append("There is no sleep score here: Apple Health doesn't share one with apps.")
        return lines.joined(separator: " ")
    }

    struct Totals {
        let asleep: TimeInterval
        let byStage: [SleepSample.Stage: TimeInterval]
        let wakings: Int
        let staged: Bool
    }

    /// Time asleep and awake in a session; each moment counts once even if samples overlap.
    static func totals(_ session: SleepSession) -> Totals {
        var byStage: [SleepSample.Stage: TimeInterval] = [:]
        var covered = session.start
        var wakings = 0
        var sleptYet = false
        var awakeSince = false
        for sample in session.stages.sorted(by: { $0.start < $1.start }) {
            let from = max(sample.start, covered)
            let to = min(sample.end, session.end)
            guard to > from else { continue }
            covered = to
            byStage[sample.stage, default: 0] += to.timeIntervalSince(from)
            if sample.stage == .awake {
                awakeSince = true
            } else {
                if sleptYet, awakeSince { wakings += 1 }
                sleptYet = true
                awakeSince = false
            }
        }
        let asleep = byStage.filter { $0.key != .awake }.values.reduce(0, +)
        let staged = byStage.keys.contains { [.core, .deep, .rem].contains($0) }
        return Totals(asleep: asleep, byStage: byStage, wakings: wakings, staged: staged)
    }

    /// "11:42 PM to 6:58 AM, 6 hours 51 minutes asleep of 7 hours 16 minutes (deep …, core …, REM …, awake …, woke up 3 times)".
    private static func tell(_ session: SleepSession, _ time: DateFormatter) -> String {
        let from = time.string(from: session.start)
        let to = time.string(from: session.end)
        let whole = session.end.timeIntervalSince(session.start)
        let t = totals(session)
        guard t.staged else { return "\(from) to \(to), \(say(t.asleep)) asleep of \(say(whole)) (no sleep stages recorded)" }
        var parts = stageNames.compactMap { stage, name in t.byStage[stage].flatMap { $0 >= 30 ? "\(name) \(say($0))" : nil } }
        if let awake = t.byStage[.awake], awake >= 30 { parts.append("awake \(say(awake))") }
        switch t.wakings {
        case 0: break
        case 1: parts.append("woke up once")
        default: parts.append("woke up \(t.wakings) times")
        }
        return "\(from) to \(to), \(say(t.asleep)) asleep of \(say(whole)) (\(parts.joined(separator: ", ")))"
    }

    /// [main] and the sleeps around it with less than three hours awake between them, in order.
    private static func nightOf(_ slept: [SleepSession], _ main: SleepSession) -> [SleepSession] {
        guard var first = slept.firstIndex(of: main) else { return [main] }
        var last = first
        while first > 0, slept[first].start.timeIntervalSince(slept[first - 1].end) < nightGap { first -= 1 }
        while last < slept.count - 1, slept[last + 1].start.timeIntervalSince(slept[last].end) < nightGap { last += 1 }
        return Array(slept[first ... last])
    }

    /// Begun in the day and short: not a night.
    private static func nap(_ session: SleepSession, _ zone: TimeZone) -> Bool {
        (10 ..< 18).contains(LocalDateTime(session.start, in: zone).hour) && session.end.timeIntervalSince(session.start) < 3 * 3600
    }

    /// The same sleep written by two sources (Apple Watch and a sleep app, say) counts once: the one with stages, else
    /// the longer.
    private static func distinct(_ sessions: [SleepSession]) -> [SleepSession] {
        let best = sessions.sorted {
            let a = totals($0).staged, b = totals($1).staged
            return a != b ? a : $0.end.timeIntervalSince($0.start) > $1.end.timeIntervalSince($1.start)
        }
        var kept: [SleepSession] = []
        for s in best where !kept.contains(where: { same($0, s) }) {
            kept.append(s)
        }
        return kept
    }

    /// Overlapping by at least half of the shorter one.
    private static func same(_ a: SleepSession, _ b: SleepSession) -> Bool {
        let overlap = min(a.end, b.end).timeIntervalSince(max(a.start, b.start))
        let shorter = min(a.end.timeIntervalSince(a.start), b.end.timeIntervalSince(b.start))
        return overlap >= 0 && overlap * 2 >= shorter
    }

    private static func dayName(_ day: LocalDateTime, today: LocalDateTime) -> String {
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = TimeZone(identifier: "UTC")
        format.dateFormat = "EEEE d MMMM"
        let name = format.string(from: day.date(in: TimeZone(identifier: "UTC")!))
        if day == today { return "today (\(name))" }
        if day == today.plusDays(-1) { return "yesterday (\(name))" }
        return name
    }

    /// "7 hours 16 minutes", "1 hour", "25 minutes": said aloud as it's written.
    public static func say(_ seconds: TimeInterval) -> String {
        let minutes = Int((seconds + 30) / 60)
        let h = minutes / 60
        let m = minutes % 60
        let hours = h == 1 ? "1 hour" : "\(h) hours"
        let mins = m == 1 ? "1 minute" : "\(m) minutes"
        if h == 0 { return mins }
        return m == 0 ? hours : "\(hours) \(mins)"
    }

    private static func session(_ samples: [SleepSample], _ source: String) -> SleepSession {
        SleepSession(start: samples.map(\.start).min()!, end: samples.map(\.end).max()!, stages: samples, source: source)
    }

    private static let nightGap: TimeInterval = 3 * 3600
    private static let stageNames: [(SleepSample.Stage, String)] = [(.deep, "deep"), (.core, "core"), (.rem, "REM"), (.asleep, "sleep of no known stage")]
}
