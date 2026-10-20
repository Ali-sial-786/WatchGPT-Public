import Foundation

/// The few claims read from OpenAI's tokens. Tokens arrive over TLS straight from the token endpoint, so they're only
/// decoded; claims from anywhere else are never trusted.
public struct TokenClaims: Equatable, Sendable {
    public let expiresAt: Int?
    public let accountId: String?
    public let planType: String?
    public let email: String?
    public let fedramp: Bool
}

public enum JWTClaims {
    /// Nil for anything that isn't a well-formed three-part JWT with a JSON payload.
    public static func parse(_ jwt: String) -> TokenClaims? {
        let parts = jwt.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, !parts[1].isEmpty, let data = base64URL(String(parts[1])), let payload = JSON.parse(data),
              payload.object != nil
        else { return nil }
        let auth = payload[OpenAIAuth.authClaim]
        let profile = payload[OpenAIAuth.profileClaim]
        return TokenClaims(
            expiresAt: payload["exp"]?.int,
            accountId: auth?["chatgpt_account_id"]?.string,
            planType: auth?["chatgpt_plan_type"]?.string,
            email: payload["email"]?.string ?? profile?["email"]?.string,
            fedramp: auth?["chatgpt_account_is_fedramp"]?.bool == true
        )
    }

    static func base64URL(_ text: String) -> Data? {
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        return Data(base64Encoded: s)
    }
}

/// "a***@example.com": enough to recognise the account, not enough to identify someone.
public func maskEmail(_ email: String?) -> String? {
    guard let email else { return nil }
    guard let at = email.firstIndex(of: "@"), at != email.startIndex else { return "***" }
    return String(email[email.startIndex]) + "***" + email[at...]
}
