@testable import WatchGPTKit
import XCTest

final class AuthTests: XCTestCase {
    func testClaimsAreReadFromATokenAndEmailsMasked() throws {
        let claims = try XCTUnwrap(JWTClaims.parse(accessToken(account: "acct-9", exp: 2_000_000_000, plan: "pro", email: "anna@example.com")))
        XCTAssertEqual(claims.accountId, "acct-9")
        XCTAssertEqual(claims.planType, "pro")
        XCTAssertEqual(claims.expiresAt, 2_000_000_000)
        XCTAssertEqual(maskEmail(claims.email), "a***@example.com")
        XCTAssertNil(JWTClaims.parse("not.a"))
        XCTAssertNil(JWTClaims.parse("a.%%%.c"))
    }

    func testDeviceCodeSignInPollsUntilTheUserEnteredTheCode() async throws {
        let clock = TestClock()
        let access = accessToken(exp: Int(clock.now.timeIntervalSince1970) + 3600, email: "anna@example.com")
        let transport = FakeTransport([
            .body(200, #"{"device_auth_id":"dev-1","user_code":"ABCD-1234","interval":"7"}"#),
            .body(403, ""),
            .body(400, #"{"error":"slow_down"}"#),
            .body(200, #"{"authorization_code":"code-1","code_verifier":"verifier-1"}"#),
            .body(200, tokens(access: access)),
        ])
        let changes = Recorder<AuthState>()
        let oauth = OAuthClient(http: transport)
        let session = AuthSession(store: MemoryStore(), oauth: oauth, now: { clock.now }, onChange: { changes.add($0) })
        let waits = Recorder<Int>()
        let signIn = DeviceSignIn(oauth: oauth, session: session, now: { clock.now }, sleep: { waits.add($0) })

        let (code, expires) = try await signIn.start()
        XCTAssertEqual(code.userCode, "ABCD-1234")
        XCTAssertEqual(code.verificationURL, "https://auth.openai.com/codex/device")
        let state = try await signIn.finish(code, expiresAt: expires)

        XCTAssertEqual(state, .signedIn(email: "a***@example.com", plan: "plus"))
        XCTAssertEqual(waits.items, [7, 7, 12])
        let exchange = String(decoding: try XCTUnwrap(transport.requests.last?.body), as: UTF8.self)
        XCTAssertTrue(exchange.contains("grant_type=authorization_code"))
        XCTAssertTrue(exchange.contains("redirect_uri=https%3A%2F%2Fauth.openai.com%2Fdeviceauth%2Fcallback"))
        XCTAssertTrue(exchange.contains("code_verifier=verifier-1"))
        XCTAssertEqual(changes.items.last, .signedIn(email: "a***@example.com", plan: "plus"))
        let accountId = await session.accountId()
        XCTAssertEqual(accountId, "acct-1")
    }

    func testAnExpiredDeviceCodeStops() async throws {
        let clock = TestClock()
        let transport = FakeTransport([.body(200, #"{"device_auth_id":"dev-1","usercode":"ABCD","interval":5}"#), .body(400, #"{"error":"deviceauth_expired"}"#)])
        let oauth = OAuthClient(http: transport)
        let signIn = DeviceSignIn(oauth: oauth, session: AuthSession(store: MemoryStore(), oauth: oauth), now: { clock.now }, sleep: { _ in })
        let (code, expires) = try await signIn.start()
        do {
            _ = try await signIn.finish(code, expiresAt: expires)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? SignInError, .deviceCodeExpired)
        }
    }

    func testASignInWithoutAnAccountIsRefused() async {
        let session = AuthSession(store: MemoryStore(), oauth: OAuthClient(http: FakeTransport([])))
        do {
            try await session.completeSignIn(TokenResponse(accessToken: jwt(["exp": 1]), refreshToken: "r"))
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? SignInError, .accountClaimMissing)
        }
    }

    func testABearerAboutToExpireIsRefreshedOnceEvenWhenAskedTwiceAtOnce() async throws {
        let clock = TestClock()
        let fresh = accessToken(exp: Int(clock.now.timeIntervalSince1970) + 7200)
        let transport = FakeTransport([.body(200, tokens(access: fresh, refresh: "refresh-2"))])
        let store = MemoryStore()
        let refreshing = AuthSession(store: store, oauth: OAuthClient(http: transport), now: { clock.now })
        try await refreshing.completeSignIn(TokenResponse(accessToken: accessToken(exp: Int(clock.now.timeIntervalSince1970) + 60), refreshToken: "refresh-1"))

        async let a = refreshing.bearer()
        async let b = refreshing.bearer()
        let (first, second) = try await (a, b)
        XCTAssertEqual(first.accessToken, fresh)
        XCTAssertEqual(second.accessToken, fresh)
        XCTAssertEqual(transport.requests.count, 1)
        let kept = try XCTUnwrap(try store.read("chatgpt_tokens"))
        XCTAssertEqual(try JSONDecoder().decode(TokenSet.self, from: kept).refreshToken, "refresh-2")
    }

    func testADeadRefreshTokenSignsOut() async throws {
        let clock = TestClock()
        let transport = FakeTransport([.body(400, #"{"error":{"code":"refresh_token_reused"}}"#)])
        let session = AuthSession(store: MemoryStore(), oauth: OAuthClient(http: transport), now: { clock.now })
        try await session.completeSignIn(TokenResponse(accessToken: accessToken(exp: Int(clock.now.timeIntervalSince1970) + 10), refreshToken: "r"))
        do {
            _ = try await session.bearer()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? BrainError, .authExpired(reason: "refresh_token_reused"))
        }
        let signedIn = await session.isSignedIn()
        XCTAssertFalse(signedIn)
    }

    func testOfflineRefreshKeepsTheSignIn() async throws {
        let clock = TestClock()
        let session = AuthSession(store: MemoryStore(), oauth: OAuthClient(http: FakeTransport([.offline])), now: { clock.now })
        try await session.completeSignIn(TokenResponse(accessToken: accessToken(exp: Int(clock.now.timeIntervalSince1970) + 10), refreshToken: "r"))
        do {
            _ = try await session.bearer()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? BrainError, .offline)
        }
        let signedIn = await session.isSignedIn()
        XCTAssertTrue(signedIn)
    }
}

final class BrainTests: XCTestCase {
    private func brain(_ transport: FakeTransport, models: [String] = ["model-a", "model-b"]) async throws -> ChatGPTBrain {
        let session = try await signedIn(FakeTransport([]))
        return ChatGPTBrain(session: session, transport: transport, settings: ChatGPTSettings(models: models), pause: { _ in })
    }

    func testAnAnswerStreamsAndTheRequestKeepsTheCodexContract() async throws {
        let transport = FakeTransport([.lines(200, [": keep-alive", ""] + Codex.text("Hello", " there."))])
        let request = ChatRequest(conversationId: "conv-1", history: [ChatTurn(.user, "Hi"), ChatTurn(.assistant, "Hey")], userText: "How are you?", context: "Local time.", webSearch: true)
        let (text, end) = await collect(try await brain(transport).stream(request))

        XCTAssertEqual(text, "Hello there.")
        guard case let .done(stats)? = end else { return XCTFail("expected done, got \(String(describing: end))") }
        XCTAssertEqual(stats.model, "model-a")
        let body = try XCTUnwrap(transport.bodies().first)
        XCTAssertEqual(body["store"], false)
        XCTAssertEqual(body["stream"], true)
        XCTAssertEqual(body["prompt_cache_key"], "conv-1")
        XCTAssertTrue(body["instructions"]?.string?.hasSuffix("\nLocal time.") == true)
        XCTAssertEqual(body["input"]?.array?.count, 3)
        XCTAssertEqual(body["input"]?.array?.last?["content"]?.array?.first?["type"], "input_text")
        XCTAssertEqual(body["tools"]?.array?.first, ["type": "web_search", "external_web_access": true])
        let headers = try XCTUnwrap(transport.requests.first?.headers)
        XCTAssertEqual(headers["ChatGPT-Account-ID"], "acct-1")
        XCTAssertEqual(headers["originator"], "watchai")
        XCTAssertEqual(headers["session-id"], "conv-1")
    }

    func testAToolCallRunsAndTheItemsAreReplayedWithItsResult() async throws {
        let tools = RecordingTools()
        let transport = FakeTransport([
            .lines(200, Codex.call("get_time", arguments: ["zone": "local"]) + Codex.completed()),
            .lines(200, Codex.text("It's three.")),
        ])
        let request = ChatRequest(conversationId: "c", history: [], userText: "What time is it?", tools: tools)
        let (text, _) = await collect(try await brain(transport).stream(request))

        XCTAssertEqual(text, "It's three.")
        XCTAssertEqual(tools.calls.map(\.0), ["get_time"])
        let second = try XCTUnwrap(transport.bodies().last?["input"]?.array)
        XCTAssertEqual(second.suffix(2).first?["type"], "function_call")
        XCTAssertEqual(second.last, ["type": "function_call_output", "call_id": "call_1", "output": "ok: 15:00"])
        XCTAssertEqual(transport.bodies().first?["tools"]?.array?.first?["name"], "get_time")
    }

    func testPrivateDataKeepsSearchOffTheLiveWeb() async throws {
        let tools = RecordingTools()
        tools.privateData = true
        let transport = FakeTransport([.lines(200, Codex.text("Ok."))])
        _ = await collect(try await brain(transport).stream(ChatRequest(conversationId: "c", history: [], userText: "x", webSearch: true, tools: tools)))
        XCTAssertEqual(transport.bodies().first?["tools"]?.array?.first?["external_web_access"], false)
    }

    func testAn401RefreshesOnceAndAsksAgain() async throws {
        let clock = TestClock()
        let fresh = accessToken(exp: Int(clock.now.timeIntervalSince1970) + 7200)
        let transport = FakeTransport([.body(401, ""), .body(200, tokens(access: fresh)), .lines(200, Codex.text("Fine."))])
        let session = AuthSession(store: MemoryStore(), oauth: OAuthClient(http: transport), now: { clock.now })
        try await session.completeSignIn(TokenResponse(accessToken: accessToken(exp: Int(clock.now.timeIntervalSince1970) + 3600), refreshToken: "r"))
        let brain = ChatGPTBrain(session: session, transport: transport, pause: { _ in })
        let (text, _) = await collect(brain.stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        XCTAssertEqual(text, "Fine.")
        XCTAssertEqual(transport.requests.last?.headers["Authorization"], "Bearer \(fresh)")
    }

    func testAnUnavailableModelMovesToTheNextAndStays() async throws {
        let transport = FakeTransport([
            .body(400, #"{"detail":"The 'model-a' model is not supported when using Codex with a ChatGPT account."}"#),
            .lines(200, Codex.text("Ok.")),
            .lines(200, Codex.text("Again.")),
        ])
        let brain = try await brain(transport)
        _ = await collect(brain.stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        _ = await collect(brain.stream(ChatRequest(conversationId: "c", history: [], userText: "y")))
        XCTAssertEqual(transport.bodies().map { $0["model"]?.string }, ["model-a", "model-b", "model-b"])
    }

    func testSearchIsDroppedWhenTheEndpointRefusesIt() async throws {
        let transport = FakeTransport([.body(400, #"{"detail":"bad tool"}"#), .lines(200, Codex.text("Ok."))])
        let (text, _) = await collect(try await brain(transport).stream(ChatRequest(conversationId: "c", history: [], userText: "x", webSearch: true)))
        XCTAssertEqual(text, "Ok.")
        XCTAssertNil(transport.bodies().last?["tools"])
    }

    func testAConnectionErrorIsRetriedOnceBeforeAnyWords() async throws {
        let ok = FakeTransport([.offline, .lines(200, Codex.text("Ok."))])
        let (text, _) = await collect(try await brain(ok).stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        XCTAssertEqual(text, "Ok.")

        let down = FakeTransport([.offline, .offline])
        let (_, end) = await collect(try await brain(down).stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        guard case let .failed(error, _)? = end else { return XCTFail("expected failed") }
        XCTAssertEqual(error, .offline)
    }

    func testABrokenStreamKeepsWhatWasSaid() async throws {
        let transport = FakeTransport([.broken(Codex.event(["type": "response.output_text.delta", "delta": "Half"]))])
        let (text, end) = await collect(try await brain(transport).stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        XCTAssertEqual(text, "Half")
        guard case let .failed(error, partial)? = end else { return XCTFail("expected failed") }
        XCTAssertEqual(error, .offline)
        XCTAssertEqual(partial, "Half")
    }

    func testAStreamThatStopsWithoutCompletingIsTruncated() async throws {
        let transport = FakeTransport([.lines(200, Codex.event(["type": "response.output_text.delta", "delta": "Half"]))])
        let (_, end) = await collect(try await brain(transport).stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        guard case let .failed(error, _)? = end else { return XCTFail("expected failed") }
        XCTAssertEqual(error, .unknown(code: "truncated"))
    }

    func testUsageLimitsAndStreamErrorsAreMapped() async throws {
        let limit = FakeTransport([.body(429, #"{"error":{"type":"usage_limit_reached","resets_at":1790003600}}"#)])
        let (_, end) = await collect(try await brain(limit).stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        guard case let .failed(error, _)? = end else { return XCTFail("expected failed") }
        XCTAssertEqual(error, .usageLimitReached(resetsAtEpochSeconds: 1_790_003_600, notIncluded: false))

        let failed = FakeTransport([.lines(200, Codex.event(["type": "response.failed", "response": ["error": ["code": "rate_limit_exceeded", "message": "Please try again in 2.5s."]]]))])
        let (_, end2) = await collect(try await brain(failed).stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        guard case let .failed(error2, _)? = end2 else { return XCTFail("expected failed") }
        XCTAssertEqual(error2, .rateLimited(retryAfterMillis: 2500))
    }

    func testANonStreamAnswerMeansTheProviderChanged() async throws {
        let transport = FakeTransport([.lines(200, ["{}"], headers: ["Content-Type": "application/json"])])
        let (_, end) = await collect(try await brain(transport).stream(ChatRequest(conversationId: "c", history: [], userText: "x")))
        guard case let .failed(error, _)? = end else { return XCTFail("expected failed") }
        XCTAssertEqual(error, .providerChanged(kind: "non_sse", status: 200, requestId: nil))
    }

    func testErrorBodiesAreMappedByCodeNotText() {
        let mapper = ErrorMapper()
        XCTAssertEqual(mapper.fromHTTP(status: 429, headers: ["retry-after": "3"], body: "{}"), .rateLimited(retryAfterMillis: 3000))
        XCTAssertEqual(mapper.fromHTTP(status: 429, headers: [:], body: #"{"error":{"type":"usage_not_included"}}"#), .usageLimitReached(resetsAtEpochSeconds: nil, notIncluded: true))
        XCTAssertEqual(mapper.fromHTTP(status: 400, headers: [:], body: #"{"detail":"Store must be set to false"}"#), .providerChanged(kind: "contract", status: 400, requestId: nil))
        XCTAssertEqual(mapper.fromHTTP(status: 404, headers: ["x-request-id": "req-1"], body: ""), .providerChanged(kind: "endpoint", status: 404, requestId: "req-1"))
        XCTAssertEqual(mapper.fromStream(["code": "cyber_policy"]), .refused(code: "cyber_policy"))
    }

    func testServerSentEventsAreParsedLikeTheStandardSays() {
        var parser = SSEParser()
        XCTAssertNil(parser.feed(": comment"))
        XCTAssertNil(parser.feed("event: one"))
        XCTAssertNil(parser.feed("data: a"))
        XCTAssertNil(parser.feed("data:b"))
        XCTAssertEqual(parser.feed(""), SSEEvent(event: "one", data: "a\nb"))
        XCTAssertNil(parser.feed(""))
        XCTAssertNil(parser.feed("data: c"))
        XCTAssertEqual(parser.feed(""), SSEEvent(event: nil, data: "c"))
    }

    func testJSONKeepsWholeNumbersWhole() {
        XCTAssertEqual(JSON.number(5).text, "5")
        XCTAssertEqual(JSON.parse(#"{"a":[1,2.5,true,null,"x"]}"#), ["a": [1, .number(2.5), true, nil, "x"]])
        XCTAssertEqual(JSON.parse("true")?.bool, true)
        XCTAssertNil(JSON.parse("{"))
    }
}
