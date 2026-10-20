import Foundation

public struct ChatTurn: Equatable, Sendable, Codable {
    public enum Role: String, Sendable, Codable { case user, assistant }

    public let role: Role
    public let text: String

    public init(_ role: Role, _ text: String) {
        self.role = role
        self.text = text
    }
}

/// How the answer will reach the user: read on the screen, or spoken aloud.
public enum ReplyStyle: Sendable { case text, spoken }

public struct ChatRequest {
    public var conversationId: String
    public var history: [ChatTurn]
    public var userText: String
    public var style: ReplyStyle
    /// Facts from the watch the model can't know, e.g. the local time ([DeviceContext]) and what WatchGPT remembers.
    public var context: String?
    /// Let ChatGPT look things up on the web.
    public var webSearch: Bool
    /// Actions ChatGPT may take on the watch.
    public var tools: Toolbox?

    public init(
        conversationId: String,
        history: [ChatTurn],
        userText: String,
        style: ReplyStyle = .spoken,
        context: String? = nil,
        webSearch: Bool = false,
        tools: Toolbox? = nil
    ) {
        self.conversationId = conversationId
        self.history = history
        self.userText = userText
        self.style = style
        self.context = context
        self.webSearch = webSearch
        self.tools = tools
    }
}

public struct TurnStats: Sendable, Equatable {
    public let model: String
    public let firstTokenMillis: Int?
    public let totalMillis: Int
    public let outputChars: Int
}

public enum ChatEvent: Sendable {
    case delta(String)
    case done(TurnStats)
    case failed(BrainError, partialText: String)
}

/// Everything ChatGPT can fail with. What the user hears is built from these ([Texts]), never from provider text.
public enum BrainError: Error, Equatable, Sendable {
    case notSignedIn
    case authExpired(reason: String)
    case rateLimited(retryAfterMillis: Int?)
    case usageLimitReached(resetsAtEpochSeconds: Int?, notIncluded: Bool)
    case offline
    /// The provider answered in a way this app version doesn't understand. Needs an app update.
    case providerChanged(kind: String, status: Int? = nil, requestId: String? = nil)
    case quotaExceeded
    case refused(code: String)
    case unknown(status: Int? = nil, code: String? = nil, requestId: String? = nil)

    /// A short, stable name for logs.
    public var code: String {
        switch self {
        case .notSignedIn: "not_signed_in"
        case .authExpired: "auth_expired"
        case .rateLimited: "rate_limited"
        case .usageLimitReached: "usage_limit"
        case .offline: "offline"
        case let .providerChanged(kind, _, _): "provider_changed_\(kind)"
        case .quotaExceeded: "quota"
        case let .refused(code): "refused_\(code)"
        case let .unknown(status, code, _): "unknown_\(code ?? status.map(String.init) ?? "")"
        }
    }
}

/// [JSON Schema] of a tool's arguments in [parameters].
public struct ToolSpec: Sendable {
    public let name: String
    public let description: String
    public let parameters: String

    public init(_ name: String, _ description: String, _ parameters: String) {
        self.name = name
        self.description = description
        self.parameters = parameters
    }
}

/// What a call is for: the user's question in this turn.
public struct ToolContext: Sendable {
    public let question: String

    public init(question: String) {
        self.question = question
    }
}

/// Things ChatGPT may do on the user's behalf. The model decides when to call a tool; the app runs it and the result goes
/// back to the model. Model output is untrusted: implementations check every argument.
public protocol Toolbox: AnyObject {
    /// Tools usable right now.
    func tools() -> [ToolSpec]

    /// Runs one call and returns what to tell the model: a short result or an error. Never throws.
    func run(name: String, arguments: String, context: ToolContext) async -> String

    /// True once a result carried the user's private data in this conversation: from then on ChatGPT's web search stays
    /// on its cached index, so a poisoned item can't make it open a link with that data in it.
    var sharedPrivateData: Bool { get }
}

public extension Toolbox {
    var sharedPrivateData: Bool { false }
}
