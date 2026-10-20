import Foundation

/// How much can go wrong if a call runs without the user wanting it (docs/security/agent-safety.md).
public enum Level: Sendable {
    /// Time, battery: nothing personal, nothing changes.
    case lookup
    /// Small, on the watch, can be undone: a reminder, a note, a timer.
    case local
    /// Reads the user's private data: notes, reminders, the calendar, their sleep.
    case `private`
    /// Leaves the watch or can't be undone: a call, a text.
    case outbound
    /// Never, whatever the model says.
    case never
}

/// Where a private answer goes instead of ChatGPT: said by the watch in its own voice. False when it couldn't be.
public protocol PrivateReply: AnyObject {
    func tell(_ text: String, language: String?) async -> Bool
}

/// All ChatGPT hears when the watch told the user something private itself.
public let toldOnWatch =
    "done: the watch is telling the user this itself, in its own voice; it stays on the watch and never comes to you. " +
    "You don't know what it says, so don't guess or repeat anything. Reply only with: Here you go."

/// The one gate every tool call of a conversation passes through. The model only proposes; this decides, in plain code
/// the model can't talk its way past. The Android app's Guard, without the phone's own model:
///
/// - A tool without a [Level], or [Level.never], isn't offered and is refused.
/// - Private results never go to ChatGPT: the watch reads them out itself (cleaned of codes, numbers and links unless
///   the user asked for one) and ChatGPT only hears that it did. For the tools in [toCloud] (the user turned that on;
///   asked at each call, so a switch counts at once) the data goes to ChatGPT through the [Redactor] instead, and the
///   conversation counts as holding private data ([sharedPrivateData]), which keeps ChatGPT off the live web.
/// - The [stopTool] ("bye") stops everything that isn't a lookup for the rest of the conversation, and [onStop] drops
///   what's waiting for a yes.
public final class Guard: Toolbox {
    private let inner: Toolbox
    private let levels: [String: Level]
    private let reply: PrivateReply?
    private let stopTool: String?
    private let onStop: () -> Void
    private let toCloud: () -> Set<String>
    private var shared = false
    private var stopped = false
    public private(set) var sentToCloud: Set<String> = []

    public init(
        inner: Toolbox,
        levels: [String: Level],
        reply: PrivateReply?,
        stopTool: String? = nil,
        onStop: @escaping () -> Void = {},
        toCloud: @escaping () -> Set<String> = { [] }
    ) {
        self.inner = inner
        self.levels = levels
        self.reply = reply
        self.stopTool = stopTool
        self.onStop = onStop
        self.toCloud = toCloud
    }

    public var sharedPrivateData: Bool { shared || inner.sharedPrivateData }

    public func tools() -> [ToolSpec] {
        inner.tools().filter { spec in levels[spec.name].map { $0 != .never } ?? false }
    }

    public func run(name: String, arguments: String, context: ToolContext) async -> String {
        let level = levels[name] ?? .never
        if level == .never { return Self.notAllowed }
        if stopped, level != .lookup { return Self.stoppedText }
        let result = await inner.run(name: name, arguments: arguments, context: context)
        if name == stopTool {
            stopped = true
            onStop()
        }
        guard level == .private, !result.hasPrefix("error") else { return result }
        guard let reply, !toCloud().contains(name) else {
            shared = true
            sentToCloud.insert(name)
            return Redactor.clean(result).text
        }
        let said = Self.detailsAsked(arguments) ? result : Redactor.clean(result).text
        return await reply.tell(Self.spoken(said), language: Self.language(arguments)) ? toldOnWatch : Self.notSaid
    }

    /// Lists put " | " between items; read out, each is a sentence.
    static func spoken(_ text: String) -> String {
        text.replacingOccurrences(of: " | ", with: ". ")
    }

    /// The user asked for a code, a number or a link itself: the model says so.
    static func detailsAsked(_ arguments: String) -> Bool {
        JSON.parse(arguments)?["details"]?.bool == true
    }

    /// The language the model says the user is speaking (a BCP 47 tag), for the voice that says it.
    static func language(_ arguments: String) -> String? {
        guard let tag = JSON.parse(arguments)?["language"]?.string,
              tag.range(of: #"^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$"#, options: .regularExpression) != nil
        else { return nil }
        return tag
    }

    static let notAllowed = "error: WatchGPT isn't allowed to do that."
    static let stoppedText = "error: the user stopped WatchGPT, so nothing more is done in this conversation."
    static let notSaid = "error: this stays on the watch, and the watch couldn't say it aloud right now. Tell the user to look at their watch; don't guess what it says."
}

/// When the user last said something, to tell their yes from the model's own words.
public final class UserTurns: @unchecked Sendable {
    private let now: () -> Date
    private let lock = NSLock()
    private var at = Date.distantPast

    public init(now: @escaping () -> Date = { Date() }) {
        self.now = now
    }

    public func heard() {
        lock.lock()
        at = now()
        lock.unlock()
    }

    public var lastAt: Date {
        lock.lock()
        defer { lock.unlock() }
        return at
    }
}

/// One call or text waiting for the user's yes. Nothing goes out when asked, it's only read back: it goes ahead when
/// the user says yes in a later turn than the one it was proposed in (so the model can't confirm its own proposal),
/// within two minutes. A new proposal replaces the old one. Tool calls use it while the app may cancel it from the main
/// thread, so it's locked.
public final class Pending: @unchecked Sendable {
    private struct Proposal {
        let summary: String
        let at: Date
        let go: () async -> String
    }

    public static let ttl: TimeInterval = 120
    private let turns: UserTurns
    private let now: () -> Date
    private let lock = NSLock()
    private var current: Proposal?

    public init(turns: UserTurns, now: @escaping () -> Date = { Date() }) {
        self.turns = turns
        self.now = now
    }

    /// Keeps [go] for later and returns what the model should ask.
    public func propose(_ summary: String, go: @escaping () async -> String) -> String {
        locked { current = Proposal(summary: summary, at: now(), go: go) }
        return "not done yet: \(summary) Read it back to the user and ask if you should go ahead; only if they clearly say yes, call confirm_action with yes."
    }

    public func decide(yes: Bool) async -> String {
        enum Decision {
            case nothing, tooLate, notYet
            case go(() async -> String)
        }
        let decision: Decision = locked {
            guard let proposal = current else { return .nothing }
            if now().timeIntervalSince(proposal.at) > Self.ttl {
                current = nil
                return .tooLate
            }
            if turns.lastAt <= proposal.at { return .notYet }
            current = nil
            return .go(proposal.go)
        }
        switch decision {
        case .nothing: return "error: nothing is waiting for a yes"
        case .tooLate: return "error: that was too long ago and was dropped; ask the user again if they still want it"
        case .notYet: return "error: the user hasn't answered yet; ask them first and wait for their answer"
        case let .go(go): return yes ? await go() : "ok: cancelled, nothing was done"
        }
    }

    /// Drops whatever waits for a yes: the user said stop.
    public func cancel() {
        locked { current = nil }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
