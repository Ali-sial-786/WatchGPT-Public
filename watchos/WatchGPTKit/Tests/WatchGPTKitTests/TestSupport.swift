import Foundation
@testable import WatchGPTKit

/// A keychain or file store in memory.
final class MemoryStore: DataStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: Data] = [:]

    func read(_ name: String) throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return items[name]
    }

    func write(_ name: String, _ data: Data) throws {
        lock.lock()
        items[name] = data
        lock.unlock()
    }

    func delete(_ name: String) throws {
        lock.lock()
        items[name] = nil
        lock.unlock()
    }
}

/// Answers requests in order from a script, and keeps what was asked.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    enum Reply {
        case body(Int, String, headers: [String: String] = [:])
        case lines(Int, [String], headers: [String: String] = [:])
        /// The stream breaks after these lines.
        case broken([String])
        case offline
    }

    private let lock = NSLock()
    private var script: [Reply]
    private(set) var requests: [HTTPRequest] = []

    init(_ script: [Reply]) {
        self.script = script
    }

    func bodies() -> [JSON] {
        lock.lock()
        defer { lock.unlock() }
        return requests.compactMap { $0.body.flatMap(JSON.parse) }
    }

    private func next(_ request: HTTPRequest) -> Reply {
        lock.lock()
        defer { lock.unlock() }
        requests.append(request)
        return script.isEmpty ? .offline : script.removeFirst()
    }

    func send(_ request: HTTPRequest) async throws -> (HTTPResponse, Data) {
        switch next(request) {
        case let .body(status, text, headers): return (HTTPResponse(status: status, headers: headers), Data(text.utf8))
        case let .lines(status, lines, headers): return (HTTPResponse(status: status, headers: headers), Data(lines.joined(separator: "\n").utf8))
        case .broken, .offline: throw HTTPOffline("fake")
        }
    }

    func lines(_ request: HTTPRequest) async throws -> (HTTPResponse, AsyncThrowingStream<String, Error>) {
        switch next(request) {
        case let .body(status, text, headers):
            return (HTTPResponse(status: status, headers: headers), Self.stream(text.components(separatedBy: "\n"), fail: false))
        case let .lines(status, lines, headers):
            return (HTTPResponse(status: status, headers: headers), Self.stream(lines, fail: false))
        case let .broken(lines):
            return (HTTPResponse(status: 200), Self.stream(lines, fail: true))
        case .offline:
            throw HTTPOffline("fake")
        }
    }

    private static func stream(_ lines: [String], fail: Bool) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            for line in lines {
                continuation.yield(line)
            }
            continuation.finish(throwing: fail ? HTTPOffline("reset") : nil)
        }
    }
}

/// Server-sent events the way the Codex endpoint streams them.
enum Codex {
    static func event(_ obj: JSON) -> [String] {
        ["event: \(obj["type"]?.string ?? "")", "data: \(obj.text)", ""]
    }

    static func text(_ deltas: String...) -> [String] {
        deltas.flatMap { event(["type": "response.output_text.delta", "delta": .string($0)]) } + completed()
    }

    static func completed() -> [String] {
        event(["type": "response.completed", "response": ["id": "resp_SYNTHETIC"]])
    }

    static func call(_ name: String, id: String = "call_1", arguments: JSON = [:]) -> [String] {
        event(["type": "response.output_item.done", "item": ["type": "function_call", "call_id": .string(id), "name": .string(name), "arguments": .string(arguments.text)]])
    }
}

/// A JWT with these claims; the signature isn't checked, so it's made up.
func jwt(_ claims: JSON) -> String {
    func b64(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    return [b64(Data(#"{"alg":"none"}"#.utf8)), b64(claims.data), "c2lnbmF0dXJl"].joined(separator: ".")
}

func accessToken(account: String = "acct-1", exp: Int, plan: String = "plus", email: String? = nil) -> String {
    var claims: [String: JSON] = [
        "exp": .number(Double(exp)),
        OpenAIAuth.authClaim: ["chatgpt_account_id": .string(account), "chatgpt_plan_type": .string(plan)],
    ]
    if let email { claims["email"] = .string(email) }
    return jwt(.object(claims))
}

func tokens(access: String, refresh: String = "refresh-1", expiresIn: Int = 3600) -> String {
    #"{"access_token":"\#(access)","refresh_token":"\#(refresh)","expires_in":\#(expiresIn)}"#
}

/// Collects what callbacks on any thread hand it.
final class Recorder<Item>: @unchecked Sendable {
    private let lock = NSLock()
    private var kept: [Item] = []

    func add(_ item: Item) {
        lock.lock()
        kept.append(item)
        lock.unlock()
    }

    var items: [Item] {
        lock.lock()
        defer { lock.unlock() }
        return kept
    }
}

/// A clock tests move by hand.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ start: Date = Date(timeIntervalSince1970: 1_790_000_000)) {
        current = start
    }

    var now: Date {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    func advance(_ seconds: TimeInterval) {
        lock.lock()
        current = current.addingTimeInterval(seconds)
        lock.unlock()
    }
}

/// A signed-in session whose access token is fresh.
func signedIn(_ transport: FakeTransport, clock: TestClock = TestClock()) async throws -> AuthSession {
    let session = AuthSession(store: MemoryStore(), oauth: OAuthClient(http: transport), now: { clock.now })
    let access = accessToken(exp: Int(clock.now.timeIntervalSince1970) + 3600)
    try await session.completeSignIn(TokenResponse(accessToken: access, refreshToken: "refresh-1", expiresIn: 3600))
    return session
}

func collect(_ stream: AsyncStream<ChatEvent>) async -> (text: String, end: ChatEvent?) {
    var text = ""
    var end: ChatEvent?
    for await event in stream {
        switch event {
        case let .delta(d): text += d
        default: end = event
        }
    }
    return (text, end)
}

/// Tools that record their calls.
final class RecordingTools: Toolbox {
    var calls: [(String, String)] = []
    var specs: [ToolSpec]
    var answer: (String, String) -> String
    var privateData = false

    init(_ specs: [ToolSpec] = [ToolSpec("get_time", "The time.", #"{"type":"object","properties":{}}"#)], answer: @escaping (String, String) -> String = { _, _ in "ok: 15:00" }) {
        self.specs = specs
        self.answer = answer
    }

    func tools() -> [ToolSpec] { specs }

    func run(name: String, arguments: String, context _: ToolContext) async -> String {
        calls.append((name, arguments))
        return answer(name, arguments)
    }

    var sharedPrivateData: Bool { privateData }
}
