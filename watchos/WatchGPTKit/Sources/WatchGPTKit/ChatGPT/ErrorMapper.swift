import Foundation

/// Turns provider responses into [BrainError]s. Provider text is only matched, never shown or logged.
struct ErrorMapper {
    var now: @Sendable () -> Date = { Date() }

    func fromHTTP(status: Int, headers: [String: String], body: String) -> BrainError {
        let requestId = Self.requestId(headers)
        let obj = JSON.parse(body)
        let error = obj?["error"]
        let type = error?["type"]?.string ?? error?["code"]?.string ?? error?.string
        let detail = obj?["detail"]?.string ?? error?["message"]?.string ?? ""
        switch status {
        case 429 where type == "usage_limit_reached":
            let resets = error?["resets_at"]?.double.map { Int($0) } ?? headers["x-codex-primary-reset-at"].flatMap { Int($0) }
            return .usageLimitReached(resetsAtEpochSeconds: resets, notIncluded: false)
        case 429 where type == "usage_not_included":
            return .usageLimitReached(resetsAtEpochSeconds: nil, notIncluded: true)
        case 429 where type == "insufficient_quota":
            return .quotaExceeded
        case 429:
            return .rateLimited(retryAfterMillis: retryAfterMillis(headers, detail))
        case 503 where type == "server_is_overloaded" || type == "slow_down":
            return .rateLimited(retryAfterMillis: retryAfterMillis(headers, detail))
        case 400 where Self.matches(Self.modelUnavailable, detail):
            return .providerChanged(kind: "model_unavailable", status: status, requestId: requestId)
        case 400 where Self.contract.contains { detail.range(of: $0, options: .caseInsensitive) != nil }:
            return .providerChanged(kind: "contract", status: status, requestId: requestId)
        case 403 where body.range(of: "cloudflare", options: .caseInsensitive) != nil:
            return .providerChanged(kind: "blocked", status: status, requestId: requestId)
        case 404, 405, 410:
            return .providerChanged(kind: "endpoint", status: status, requestId: requestId)
        default:
            return .unknown(status: status, code: type, requestId: requestId)
        }
    }

    /// For `response.failed` (error inside `response`) and top-level `error` events.
    func fromStream(_ error: JSON?) -> BrainError {
        let code = error?["code"]?.string ?? error?["type"]?.string
        let message = error?["message"]?.string ?? ""
        switch code {
        case "rate_limit_exceeded": return .rateLimited(retryAfterMillis: Self.tryAgain(message))
        case "usage_limit_reached": return .usageLimitReached(resetsAtEpochSeconds: error?["resets_at"]?.double.map { Int($0) }, notIncluded: false)
        case "usage_not_included": return .usageLimitReached(resetsAtEpochSeconds: nil, notIncluded: true)
        case "insufficient_quota": return .quotaExceeded
        case "cyber_policy", "bio_policy", "invalid_prompt": return .refused(code: code!)
        case "server_is_overloaded", "slow_down": return .rateLimited(retryAfterMillis: nil)
        default: return .unknown(code: code)
        }
    }

    static func requestId(_ headers: [String: String]) -> String? {
        headers["x-request-id"] ?? headers["x-oai-request-id"] ?? headers["cf-ray"]
    }

    private func retryAfterMillis(_ headers: [String: String], _ detail: String) -> Int? {
        if let ms = headers["retry-after-ms"].flatMap({ Int($0) }) { return ms }
        if let value = headers["retry-after"] {
            if let seconds = Int(value) { return seconds * 1000 }
            let format = DateFormatter()
            format.locale = Locale(identifier: "en_US_POSIX")
            format.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
            if let date = format.date(from: value) { return max(0, Int(date.timeIntervalSince(now()) * 1000)) }
        }
        return Self.tryAgain(detail)
    }

    private static func tryAgain(_ message: String) -> Int? {
        guard let match = tryAgainPattern.firstMatch(in: message, range: NSRange(message.startIndex..., in: message)),
              let range = Range(match.range(at: 1), in: message), let seconds = Double(message[range])
        else { return nil }
        return Int(seconds * 1000)
    }

    private static func matches(_ pattern: NSRegularExpression, _ text: String) -> Bool {
        pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    private static let tryAgainPattern = try! NSRegularExpression(pattern: "try again in ([0-9]+(?:\\.[0-9]+)?)s", options: .caseInsensitive)
    private static let modelUnavailable = try! NSRegularExpression(pattern: "model.*(not supported|does not exist|not found|unsupported)", options: .caseInsensitive)
    private static let contract = ["Store must be set to false", "Stream must be set to true", "Unsupported parameter", "Instructions are required"]
}
