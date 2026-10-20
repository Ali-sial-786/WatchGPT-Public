import Foundation

/// "Sign in with ChatGPT" through OpenAI's public Codex OAuth client, as the Android app does (docs/adr/0002). We
/// identify ourselves with our own originator and never pretend to be Codex.
public enum OpenAIAuth {
    public static let clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    public static let scopes = "openid profile email offline_access"
    public static let originator = "watchai"
    public static let authClaim = "https://api.openai.com/auth"
    public static let profileClaim = "https://api.openai.com/profile"

    /// Credentials may only ever go to OpenAI's two hosts: requests anywhere else are refused before they leave the watch.
    public static let hosts: Set<String> = ["auth.openai.com", "chatgpt.com"]
}

public struct OpenAIEndpoints: Sendable {
    public var issuer: String
    public var codexBase: String

    public init(issuer: String = "https://auth.openai.com", codexBase: String = "https://chatgpt.com/backend-api/codex") {
        self.issuer = issuer
        self.codexBase = codexBase
    }

    public var token: URL { URL(string: "\(issuer)/oauth/token")! }
    public var revoke: URL { URL(string: "\(issuer)/oauth/revoke")! }
    public var deviceUserCode: URL { URL(string: "\(issuer)/api/accounts/deviceauth/usercode")! }
    public var deviceToken: URL { URL(string: "\(issuer)/api/accounts/deviceauth/token")! }
    /// Where the user types the code, on any phone or computer.
    public var deviceVerificationPage: String { "\(issuer)/codex/device" }
    public var deviceRedirect: String { "\(issuer)/deviceauth/callback" }
    public var responses: URL { URL(string: "\(codexBase)/responses")! }
}
