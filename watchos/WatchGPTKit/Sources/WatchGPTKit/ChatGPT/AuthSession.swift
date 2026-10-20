import Foundation

/// Bytes kept under a name: the keychain for the sign-in, protected files for the rest.
public protocol DataStore: Sendable {
    func read(_ name: String) throws -> Data?
    func write(_ name: String, _ data: Data) throws
    func delete(_ name: String) throws
}

/// What's kept after sign-in. The raw id_token is dropped once its claims are read. Same fields as the Android app.
public struct TokenSet: Codable, Equatable, Sendable, CustomStringConvertible {
    public var accessToken: String
    public var refreshToken: String
    public var expiresAtEpochSeconds: Int
    public var accountId: String
    public var planType: String?
    public var emailMasked: String?
    public var fedramp: Bool
    public var lastRefreshEpochSeconds: Int

    public var description: String { "TokenSet(account=\(accountId.prefix(8))…, plan=\(planType ?? "-"), exp=\(expiresAtEpochSeconds))" }
}

public enum AuthState: Equatable, Sendable {
    case unknown
    case signedOut
    case signedIn(email: String?, plan: String?)
}

/// What a request needs to authenticate.
public struct Bearer: Equatable, Sendable, CustomStringConvertible {
    public let accessToken: String
    public let accountId: String
    public let fedramp: Bool

    public var description: String { "Bearer(account=\(accountId.prefix(8))…)" }
}

/// Owns the ChatGPT tokens. Refresh tokens rotate and a reused one is fatal, so only one refresh runs at a time and
/// its new tokens are saved before anyone can use them; a refresh, once started, finishes even if its caller gives up.
public actor AuthSession {
    private let store: DataStore
    private let oauth: OAuthClient
    private let now: @Sendable () -> Date
    private let onChange: @Sendable (AuthState) -> Void
    private var cached: TokenSet?
    private var loaded = false
    private var refreshing: Task<TokenSet, Error>?
    public private(set) var state: AuthState = .unknown

    public init(
        store: DataStore,
        oauth: OAuthClient,
        now: @escaping @Sendable () -> Date = { Date() },
        onChange: @escaping @Sendable (AuthState) -> Void = { _ in }
    ) {
        self.store = store
        self.oauth = oauth
        self.now = now
        self.onChange = onChange
    }

    public func isSignedIn() -> Bool {
        load() != nil
    }

    /// The account id, for WatchGPT's look: the same account gets the same WatchGPT as on Android.
    public func accountId() -> String? {
        load()?.accountId
    }

    /// A valid bearer, refreshed first if it expires within five minutes.
    public func bearer() async throws -> Bearer {
        guard let current = load() else { throw BrainError.notSignedIn }
        if current.expiresAtEpochSeconds > seconds() + Self.earlyRefresh { return current.bearer }
        return try await refreshed(current).bearer
    }

    /// Called after a 401. Refreshes once, unless another caller already did.
    public func onUnauthorized(_ rejected: Bearer) async throws -> Bearer {
        guard let current = load() else { throw BrainError.notSignedIn }
        if current.accessToken != rejected.accessToken { return current.bearer }
        return try await refreshed(current).bearer
    }

    /// Turns the token response of a sign-in into kept tokens.
    @discardableResult
    public func completeSignIn(_ response: TokenResponse) throws -> AuthState {
        guard let access = response.accessToken, !access.isEmpty, let refresh = response.refreshToken, !refresh.isEmpty else {
            throw SignInError.exchangeFailed(status: nil)
        }
        let idClaims = response.idToken.flatMap(JWTClaims.parse)
        let accessClaims = JWTClaims.parse(access)
        guard let accountId = accessClaims?.accountId ?? idClaims?.accountId else { throw SignInError.accountClaimMissing }
        if let a = idClaims?.accountId, let b = accessClaims?.accountId, a != b { throw SignInError.accountClaimMissing }
        let set = TokenSet(
            accessToken: access,
            refreshToken: refresh,
            expiresAtEpochSeconds: expiry(accessClaims?.expiresAt, response.expiresIn),
            accountId: accountId,
            planType: accessClaims?.planType ?? idClaims?.planType,
            emailMasked: maskEmail(idClaims?.email ?? accessClaims?.email),
            fedramp: accessClaims?.fedramp == true || idClaims?.fedramp == true,
            lastRefreshEpochSeconds: seconds()
        )
        do {
            try save(set)
        } catch {
            throw SignInError.secureStorageUnavailable
        }
        cached = set
        loaded = true
        publish(.signedIn(email: set.emailMasked, plan: set.planType))
        return state
    }

    /// Revokes (best effort) and forgets everything, even if revoking fails.
    public func signOut() async {
        if let current = load() { _ = await oauth.revoke(current.refreshToken) }
        wipe()
    }

    @discardableResult
    private func load() -> TokenSet? {
        if loaded { return cached }
        // Keychain trouble: behave as signed out rather than crash; the user can sign in again.
        cached = (try? store.read(Self.name)).flatMap { $0 }.flatMap { try? JSONDecoder().decode(TokenSet.self, from: $0) }
        loaded = true
        publish(cached.map { .signedIn(email: $0.emailMasked, plan: $0.planType) } ?? .signedOut)
        return cached
    }

    private func refreshed(_ current: TokenSet) async throws -> TokenSet {
        if let running = refreshing { return try await running.value }
        let task = Task { try await self.refresh(current) }
        refreshing = task
        defer { refreshing = nil }
        return try await task.value
    }

    private func refresh(_ current: TokenSet) async throws -> TokenSet {
        switch await oauth.refresh(current.refreshToken) {
        case let .ok(response):
            let access = response.accessToken ?? ""
            let claims = JWTClaims.parse(access)
            if let account = claims?.accountId, account != current.accountId {
                wipe()
                throw BrainError.authExpired(reason: "account_changed")
            }
            var next = current
            next.accessToken = access
            next.refreshToken = response.refreshToken ?? current.refreshToken
            next.expiresAtEpochSeconds = expiry(claims?.expiresAt, response.expiresIn)
            next.planType = claims?.planType ?? current.planType
            next.lastRefreshEpochSeconds = seconds()
            try? save(next)
            cached = next
            return next
        case let .permanent(reason):
            wipe()
            throw BrainError.authExpired(reason: reason)
        case let .transient(status):
            throw status == nil ? BrainError.offline : BrainError.unknown(status: status)
        }
    }

    private func save(_ tokens: TokenSet) throws {
        try store.write(Self.name, JSONEncoder().encode(tokens))
    }

    private func wipe() {
        try? store.delete(Self.name)
        cached = nil
        loaded = true
        publish(.signedOut)
    }

    private func publish(_ next: AuthState) {
        guard next != state else { return }
        state = next
        onChange(next)
    }

    private func expiry(_ jwtExp: Int?, _ expiresIn: Int?) -> Int {
        [jwtExp, expiresIn.map { seconds() + $0 }].compactMap { $0 }.min() ?? seconds() + Self.defaultLifetime
    }

    private func seconds() -> Int {
        Int(now().timeIntervalSince1970)
    }

    private static let name = "chatgpt_tokens"
    private static let earlyRefresh = 300
    private static let defaultLifetime = 3600
}

extension TokenSet {
    var bearer: Bearer { Bearer(accessToken: accessToken, accountId: accountId, fedramp: fedramp) }
}

/// Signing in on the watch itself: it shows a code, the user enters it on OpenAI's page on any phone or computer, and
/// the watch polls until that's done. The watch has no browser for the other way (a loopback redirect).
public struct DeviceSignIn: Sendable {
    public static let lifetimeSeconds = 15 * 60

    private let oauth: OAuthClient
    private let session: AuthSession
    private let now: @Sendable () -> Date
    private let sleep: @Sendable (Int) async throws -> Void

    public init(
        oauth: OAuthClient,
        session: AuthSession,
        now: @escaping @Sendable () -> Date = { Date() },
        sleep: @escaping @Sendable (Int) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0) * 1_000_000_000) }
    ) {
        self.oauth = oauth
        self.session = session
        self.now = now
        self.sleep = sleep
    }

    /// The code to show, and when it stops working.
    public func start() async throws -> (code: DeviceCode, expiresAt: Date) {
        let code = try await oauth.requestDeviceCode()
        return (code, now().addingTimeInterval(TimeInterval(Self.lifetimeSeconds)))
    }

    /// Polls until the user entered the code (then signs in) or it expired. Cancel the task to stop.
    public func finish(_ code: DeviceCode, expiresAt: Date) async throws -> AuthState {
        var interval = code.intervalSeconds
        while true {
            if now() >= expiresAt { throw SignInError.deviceCodeExpired }
            try await sleep(interval)
            switch try await oauth.pollDeviceCode(code) {
            case let .pending(slowDown):
                if slowDown { interval = min(interval + 5, 60) }
            case let .authorized(authCode, verifier):
                let tokens = try await oauth.exchangeCode(authCode, verifier: verifier, redirectURI: oauth.deviceRedirectURI)
                return try await session.completeSignIn(tokens)
            }
        }
    }
}
