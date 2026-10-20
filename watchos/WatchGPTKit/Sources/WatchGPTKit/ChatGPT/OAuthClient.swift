import Foundation

public enum SignInError: Error, Equatable, Sendable {
    /// The ChatGPT workspace doesn't include Codex, so its plan can't be used here.
    case missingEntitlement
    case exchangeFailed(status: Int?)
    case accountClaimMissing
    case offline
    case deviceCodeDisabled
    case deviceCodeExpired
    /// The watch couldn't keep the sign-in in its keychain.
    case secureStorageUnavailable
}

public struct TokenResponse: Codable, Equatable, Sendable {
    public var idToken: String?
    public var accessToken: String?
    public var refreshToken: String?
    public var expiresIn: Int?

    public init(idToken: String? = nil, accessToken: String? = nil, refreshToken: String? = nil, expiresIn: Int? = nil) {
        self.idToken = idToken
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresIn = expiresIn
    }

    enum CodingKeys: String, CodingKey {
        case idToken = "id_token"
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
    }
}

public enum RefreshResult: Equatable, Sendable {
    case ok(TokenResponse)
    /// The refresh token is dead: sign in again.
    case permanent(reason: String)
    case transient(status: Int?)
}

public struct DeviceCode: Equatable, Sendable {
    public let deviceAuthId: String
    public let userCode: String
    public let intervalSeconds: Int
    public let verificationURL: String
}

public enum DevicePoll: Equatable, Sendable {
    case pending(slowDown: Bool)
    case authorized(code: String, verifier: String)
}

/// Talks to auth.openai.com. A port of the Android app's CodexOAuthClient.
public struct OAuthClient: Sendable {
    private let http: HTTPTransport
    public let endpoints: OpenAIEndpoints

    public init(http: HTTPTransport, endpoints: OpenAIEndpoints = OpenAIEndpoints()) {
        self.http = http
        self.endpoints = endpoints
    }

    public func exchangeCode(_ code: String, verifier: String, redirectURI: String) async throws -> TokenResponse {
        let request = HTTPRequest.form(endpoints.token, [
            ("grant_type", "authorization_code"),
            ("client_id", OpenAIAuth.clientId),
            ("code", code),
            ("redirect_uri", redirectURI),
            ("code_verifier", verifier),
        ])
        guard let (response, data) = try await call(request) else { throw SignInError.offline }
        guard response.ok, let tokens = try? JSONDecoder().decode(TokenResponse.self, from: data) else {
            throw SignInError.exchangeFailed(status: response.status)
        }
        return tokens
    }

    public func refresh(_ refreshToken: String) async -> RefreshResult {
        let request = HTTPRequest.json(endpoints.token, [
            "client_id": .string(OpenAIAuth.clientId),
            "grant_type": "refresh_token",
            "refresh_token": .string(refreshToken),
        ])
        guard let (response, data) = try? await call(request) else { return .transient(status: nil) }
        if response.ok {
            guard let tokens = try? JSONDecoder().decode(TokenResponse.self, from: data), !(tokens.accessToken ?? "").isEmpty else {
                return .transient(status: response.status)
            }
            return .ok(tokens)
        }
        let code = Self.errorCode(data)
        if response.status == 401 { return .permanent(reason: code ?? "unauthorized") }
        if let code, Self.permanentRefreshErrors.contains(code) { return .permanent(reason: code) }
        return .transient(status: response.status)
    }

    /// Best effort: signing out on the watch happens whether or not this reaches OpenAI.
    public func revoke(_ refreshToken: String) async -> Bool {
        let request = HTTPRequest.json(endpoints.revoke, [
            "token": .string(refreshToken),
            "token_type_hint": "refresh_token",
            "client_id": .string(OpenAIAuth.clientId),
        ])
        guard let (response, _) = try? await call(request) else { return false }
        return response.ok
    }

    public func requestDeviceCode() async throws -> DeviceCode {
        let request = HTTPRequest.json(endpoints.deviceUserCode, ["client_id": .string(OpenAIAuth.clientId)])
        guard let (response, data) = try await call(request) else { throw SignInError.offline }
        if response.status == 404 { throw SignInError.deviceCodeDisabled }
        guard response.ok else { throw SignInError.exchangeFailed(status: response.status) }
        let body = JSON.parse(data)
        guard let id = body?["device_auth_id"]?.string, let userCode = body?["user_code"]?.string ?? body?["usercode"]?.string else {
            throw SignInError.exchangeFailed(status: response.status)
        }
        // OpenAI sends the interval as a string; accept a number too.
        let interval = body?["interval"]?.string.flatMap { Int($0) } ?? body?["interval"]?.int ?? 5
        return DeviceCode(deviceAuthId: id, userCode: userCode, intervalSeconds: min(max(interval, 1), 60), verificationURL: endpoints.deviceVerificationPage)
    }

    public func pollDeviceCode(_ code: DeviceCode) async throws -> DevicePoll {
        let request = HTTPRequest.json(endpoints.deviceToken, [
            "device_auth_id": .string(code.deviceAuthId),
            "user_code": .string(code.userCode),
        ])
        guard let (response, data) = try await call(request) else { return .pending(slowDown: false) }
        if response.status == 403 || response.status == 404 { return .pending(slowDown: false) }
        let error = Self.errorCode(data)
        if error == "slow_down" { return .pending(slowDown: true) }
        if error == "deviceauth_authorization_pending" || error == "authorization_pending" { return .pending(slowDown: false) }
        if error == "expired_token" || error == "deviceauth_expired" { throw SignInError.deviceCodeExpired }
        guard response.ok else { throw SignInError.exchangeFailed(status: response.status) }
        let body = JSON.parse(data)
        guard let authCode = body?["authorization_code"]?.string, let verifier = body?["code_verifier"]?.string else {
            throw SignInError.exchangeFailed(status: response.status)
        }
        return .authorized(code: authCode, verifier: verifier)
    }

    public var deviceRedirectURI: String { endpoints.deviceRedirect }

    /// Nil when nothing came back.
    private func call(_ request: HTTPRequest) async throws -> (HTTPResponse, Data)? {
        do {
            return try await http.send(request)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return nil
        }
    }

    /// Handles both `{"error":"invalid_grant"}` and `{"error":{"code":"refresh_token_expired"}}`.
    static func errorCode(_ data: Data) -> String? {
        guard let body = JSON.parse(data) else { return nil }
        switch body["error"] {
        case let .string(code)?: return code
        case let .object(error)?: return error["code"]?.string ?? error["type"]?.string
        default: return body["code"]?.string
        }
    }

    private static let permanentRefreshErrors: Set<String> = [
        "invalid_grant", "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated",
    ]
}
