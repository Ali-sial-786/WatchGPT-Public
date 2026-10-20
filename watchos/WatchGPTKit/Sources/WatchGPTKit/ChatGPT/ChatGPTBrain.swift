import Foundation

public struct ChatGPTSettings: Sendable {
    /// Tried in order; the first one this account can use is remembered.
    public var models: [String]
    public var reasoningEffort: String
    public var userAgent: String

    public init(
        models: [String] = ["gpt-6-luna", "gpt-6-sol", "gpt-5.6-luna"],
        reasoningEffort: String = "low",
        userAgent: String = "WatchGPT/0.1 (watchOS)"
    ) {
        self.models = models
        self.reasoningEffort = reasoningEffort
        self.userAgent = userAgent
    }
}

/// ChatGPT answers on the user's own plan, through the Codex Responses endpoint: the Android app's ChatGptBrain in
/// Swift. Answers stream as text; tool calls run between rounds, and the stateless endpoint gets the model's items and
/// the results replayed.
public final class ChatGPTBrain: @unchecked Sendable {
    private let session: AuthSession
    private let transport: HTTPTransport
    private let endpoints: OpenAIEndpoints
    private let settings: ChatGPTSettings
    private let errors: ErrorMapper
    private let lock = NSLock()
    private var modelIndex = 0
    private let pause: @Sendable (Int) async throws -> Void

    public init(
        session: AuthSession,
        transport: HTTPTransport,
        endpoints: OpenAIEndpoints = OpenAIEndpoints(),
        settings: ChatGPTSettings = ChatGPTSettings(),
        now: @escaping @Sendable () -> Date = { Date() },
        pause: @escaping @Sendable (Int) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0) * 1_000_000) }
    ) {
        self.session = session
        self.transport = transport
        self.endpoints = endpoints
        self.settings = settings
        errors = ErrorMapper(now: now)
        self.pause = pause
    }

    /// Collecting starts the turn; cancelling the consumer stops it. Ends with `.done` or `.failed`.
    public func stream(_ request: ChatRequest) -> AsyncStream<ChatEvent> {
        AsyncStream { continuation in
            let task = Task {
                let started = Date()
                var firstToken: Int?
                var text = ""
                do {
                    let model = try await self.runTurn(request) { delta in
                        if firstToken == nil { firstToken = Int(Date().timeIntervalSince(started) * 1000) }
                        text += delta
                        continuation.yield(.delta(delta))
                    }
                    let total = Int(Date().timeIntervalSince(started) * 1000)
                    continuation.yield(.done(TurnStats(model: model, firstTokenMillis: firstToken, totalMillis: total, outputChars: text.count)))
                } catch is CancellationError {
                    // The consumer went away: nothing to tell it.
                } catch let error as BrainError {
                    continuation.yield(.failed(error, partialText: text))
                } catch {
                    continuation.yield(.failed(.unknown(code: "\(type(of: error))"), partialText: text))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    struct FunctionCall: Equatable {
        let callId: String
        let name: String
        let arguments: String
    }

    private enum Attempt {
        /// [calls]: tools the model wants run; [items]: its output items, replayed with the results.
        case finished(model: String, calls: [FunctionCall], items: [JSON])
        case retryAuth
        case retryNetwork
        case nextModel
        case withoutSearch
    }

    /// Returns the model that answered. Throws [BrainError].
    private func runTurn(_ request: ChatRequest, onDelta: @escaping (String) -> Void) async throws -> String {
        var bearer = try await session.bearer()
        var authRetried = false
        var networkRetried = false
        var search = request.webSearch
        var spoke = false
        var replay: [JSON] = []
        var toolRounds = 0
        let relay: (String) -> Void = {
            spoke = true
            onDelta($0)
        }
        while true {
            try Task.checkCancellation()
            let index = min(currentModel(), settings.models.count - 1)
            let model = settings.models[index]
            // Checked every round: a tool result in the last one may have brought private data in.
            let liveWeb = request.tools?.sharedPrivateData != true
            var asked = request
            asked.webSearch = search
            let body = ResponsesRequest.body(asked, model: model, effort: settings.reasoningEffort, extraInput: replay, liveWeb: liveWeb)
            let http = ResponsesRequest.http(url: endpoints.responses, body: body, bearer: bearer, sessionId: request.conversationId, userAgent: settings.userAgent)
            let attempt = try await attempt(
                http,
                model: model,
                canRetryAuth: !authRetried,
                canRetryNetwork: !networkRetried && !spoke,
                hasNextModel: index < settings.models.count - 1 && !spoke,
                canDropSearch: search && !spoke,
                onDelta: relay
            )
            switch attempt {
            case let .finished(served, calls, items):
                guard !calls.isEmpty, let tools = request.tools, toolRounds < Self.maxToolRounds else { return served }
                toolRounds += 1
                var results: [JSON] = []
                for call in calls {
                    let output = await tools.run(name: call.name, arguments: call.arguments, context: ToolContext(question: request.userText))
                    results.append(["type": "function_call_output", "call_id": .string(call.callId), "output": .string(output)])
                }
                replay += items + results
            case .retryAuth:
                authRetried = true
                bearer = try await session.onUnauthorized(bearer)
            case .retryNetwork:
                networkRetried = true
                try await pause(Self.retryBaseMillis + Int.random(in: 0 ..< Self.retryBaseMillis))
            case .nextModel:
                advanceModel(from: index)
            case .withoutSearch:
                search = false
            }
        }
    }

    private func attempt(
        _ request: HTTPRequest,
        model: String,
        canRetryAuth: Bool,
        canRetryNetwork: Bool,
        hasNextModel: Bool,
        canDropSearch: Bool,
        onDelta: @escaping (String) -> Void
    ) async throws -> Attempt {
        let response: HTTPResponse
        let lines: AsyncThrowingStream<String, Error>
        do {
            (response, lines) = try await transport.lines(request)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try Task.checkCancellation()
            if canRetryNetwork { return .retryNetwork }
            throw BrainError.offline
        }
        if response.status == 401 {
            if canRetryAuth { return .retryAuth }
            throw BrainError.authExpired(reason: "unauthorized_after_refresh")
        }
        if !response.ok {
            var body = ""
            do {
                for try await line in lines {
                    body += line + "\n"
                    if body.count >= Self.maxErrorBody { break }
                }
            } catch is CancellationError {
                throw CancellationError()
            } catch {}
            let error = errors.fromHTTP(status: response.status, headers: response.headers, body: String(body.prefix(Self.maxErrorBody)))
            if case let .providerChanged(kind, _, _) = error, kind == "model_unavailable", hasNextModel { return .nextModel }
            // Search is an extra: if the endpoint refuses the request with it, ask again without.
            if response.status == 400, canDropSearch { return .withoutSearch }
            if response.status >= 500, case .unknown = error, canRetryNetwork { return .retryNetwork }
            throw error
        }
        // The Codex endpoint streams without a Content-Type header, so only a declared non-stream type is wrong.
        if let type = response.headers["content-type"], !type.contains("event-stream") {
            throw BrainError.providerChanged(kind: "non_sse", status: response.status, requestId: ErrorMapper.requestId(response.headers))
        }
        return try await readEvents(lines, model: response.headers["openai-model"] ?? model, onDelta: onDelta)
    }

    private func readEvents(_ lines: AsyncThrowingStream<String, Error>, model: String, onDelta: (String) -> Void) async throws -> Attempt {
        var parser = SSEParser()
        var produced = false
        var calls: [FunctionCall] = []
        var items: [JSON] = []
        do {
            for try await line in lines {
                guard let event = parser.feed(line), !event.data.isEmpty, event.data != "[DONE]", let obj = JSON.parse(event.data), obj.object != nil else {
                    continue
                }
                switch obj["type"]?.string ?? event.event {
                case "response.output_text.delta", "response.refusal.delta":
                    if let delta = obj["delta"]?.string, !delta.isEmpty {
                        produced = true
                        onDelta(delta)
                    }
                case "response.output_item.done":
                    guard let item = obj["item"], item.object != nil else { break }
                    items.append(item)
                    if item["type"]?.string == "function_call", let callId = item["call_id"]?.string, let name = item["name"]?.string {
                        calls.append(FunctionCall(callId: callId, name: name, arguments: item["arguments"]?.string ?? "{}"))
                    }
                case "response.completed":
                    return .finished(model: model, calls: calls, items: items)
                case "response.incomplete":
                    let reason = obj["response"]?["incomplete_details"]?["reason"]?.string
                    if produced, reason != "content_filter" { return .finished(model: model, calls: calls, items: items) }
                    throw BrainError.unknown(code: "incomplete_\(reason ?? "unknown")")
                case "response.failed":
                    throw errors.fromStream(obj["response"]?["error"])
                case "error":
                    throw errors.fromStream(obj["error"] ?? obj)
                default:
                    break
                }
            }
        } catch let error as BrainError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try Task.checkCancellation()
            throw BrainError.offline
        }
        throw BrainError.unknown(code: "truncated")
    }

    private func currentModel() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return modelIndex
    }

    private func advanceModel(from index: Int) {
        lock.lock()
        if modelIndex == index { modelIndex = index + 1 }
        lock.unlock()
    }

    private static let retryBaseMillis = 400
    private static let maxErrorBody = 8192
    /// A few steps can follow each other: look something up, then add a reminder, then answer.
    private static let maxToolRounds = 8
}
