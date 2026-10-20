import Foundation

/// One chat turn against the Codex Responses endpoint, as the Android app sends it. Contract (measured 08.2026, matches
/// Codex): `store` must be false and `stream` true, `instructions` must be non-empty, and `temperature`,
/// `max_output_tokens` and `previous_response_id` are rejected, so history is replayed each turn.
enum ResponsesRequest {
    /// [extraInput]: earlier output items (reasoning, tool calls) and tool results, replayed after the question.
    /// [liveWeb]: search may open pages live. Off once the conversation holds the user's private data: then search
    /// only uses OpenAI's cached index, so no page is fetched with that data in its address.
    static func body(_ request: ChatRequest, model: String, effort: String, extraInput: [JSON] = [], liveWeb: Bool = true) -> JSON {
        let turns = PromptStyle.trim(request.history) + [ChatTurn(.user, request.userText)]
        let input: [JSON] = turns.map { turn in
            [
                "type": "message",
                "role": .string(turn.role == .user ? "user" : "assistant"),
                "content": [["type": .string(turn.role == .user ? "input_text" : "output_text"), "text": .string(turn.text)]],
            ]
        } + extraInput
        var body: [String: JSON] = [
            "model": .string(model),
            "instructions": .string(PromptStyle.system(request)),
            "input": .array(input),
            "store": false,
            "stream": true,
            "reasoning": ["effort": .string(effort)],
            "text": ["verbosity": "low"],
            "include": ["reasoning.encrypted_content"],
            "prompt_cache_key": .string(request.conversationId),
            "tool_choice": "auto",
            "parallel_tool_calls": false,
        ]
        let functions = request.tools?.tools() ?? []
        if request.webSearch || !functions.isEmpty {
            var tools: [JSON] = []
            // Same tool spec as Codex's web search: live, or cached only.
            if request.webSearch { tools.append(["type": "web_search", "external_web_access": .bool(liveWeb)]) }
            for spec in functions {
                tools.append([
                    "type": "function",
                    "name": .string(spec.name),
                    "description": .string(spec.description),
                    "strict": false,
                    "parameters": JSON.parse(spec.parameters) ?? ["type": "object"],
                ])
            }
            body["tools"] = .array(tools)
        }
        return .object(body)
    }

    static func http(url: URL, body: JSON, bearer: Bearer, sessionId: String, userAgent: String) -> HTTPRequest {
        var headers = [
            "Authorization": "Bearer \(bearer.accessToken)",
            "ChatGPT-Account-ID": bearer.accountId,
            "Accept": "text/event-stream",
            "originator": OpenAIAuth.originator,
            "User-Agent": userAgent,
            "session-id": sessionId,
        ]
        if bearer.fedramp { headers["X-OpenAI-Fedramp"] = "true" }
        // The read timeout acts as an idle timeout while the answer streams.
        return HTTPRequest.json(url, body, headers: headers, timeout: 120)
    }
}

struct SSEEvent: Equatable {
    let event: String?
    let data: String
}

/// Minimal server-sent-events reader: `event:` and multi-line `data:` fields, comments ignored. Fed one line at a time;
/// an empty line ends an event. A trailing event without its empty line is dropped.
struct SSEParser {
    private var event: String?
    private var data = ""
    private var hasData = false

    mutating func feed(_ line: String) -> SSEEvent? {
        if line.isEmpty {
            defer {
                event = nil
                data = ""
                hasData = false
            }
            return hasData ? SSEEvent(event: event, data: data) : nil
        }
        if line.hasPrefix(":") { return nil }
        let field: Substring
        var value: Substring
        if let colon = line.firstIndex(of: ":") {
            field = line[..<colon]
            value = line[line.index(after: colon)...]
            if value.hasPrefix(" ") { value = value.dropFirst() }
        } else {
            field = Substring(line)
            value = ""
        }
        switch field {
        case "event":
            event = String(value)
        case "data":
            if hasData { data += "\n" }
            data += value
            hasData = true
        default:
            break
        }
        return nil
    }
}
