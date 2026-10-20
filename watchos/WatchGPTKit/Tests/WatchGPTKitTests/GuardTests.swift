@testable import WatchGPTKit
import XCTest

/// The Android app's RedactorTest, case for case. Synthetic texts only: made-up codes, test card numbers, the standard
/// example IBAN.
final class RedactorTests: XCTestCase {
    private func clean(_ text: String) -> String {
        Redactor.clean(text).text
    }

    func testOneTimeCodesAreHidden() {
        XCTAssertEqual(clean("G-482913 is your Google verification code."), "[code hidden] is your Google verification code.")
        XCTAssertEqual(clean("Your login code: 482 913"), "Your login code: [code hidden]")
        XCTAssertEqual(clean("Use X7K9PQ to sign in"), "Use [code hidden] to sign in")
        XCTAssertEqual(clean("Your code is 4829. Don't share it."), "Your code is [code hidden]. Don't share it.")
        XCTAssertEqual(clean("您的验证码是482913，请勿泄露"), "您的验证码是[code hidden]，请勿泄露")
        XCTAssertEqual(clean("Ihre TAN lautet 4829"), "Ihre TAN lautet [code hidden]")
    }

    func testDatesTimesPricesAndYearsStay() {
        let text = "See you at 18:30 on 28.09.2026, table for 4, it was 2024 when we met, costs 12,50 €"
        XCTAssertEqual(clean(text), text)
        XCTAssertEqual(clean("it costs 1500 €"), "it costs 1500 €")
    }

    func testInALongMessageAShortNumberStaysASixDigitOneDoesNot() {
        let long = "Hi! We moved the team dinner to Friday, so please bring the photos from the trip, and tell everyone that the " +
            "restaurant is the one near the station, room 1204 upstairs, and that we expect about 1500 guests at the fair next week."
        XCTAssertEqual(clean(long), long)
        XCTAssertTrue(clean("\(long) Your code: 482913").contains("[code hidden]"))
    }

    func testCardsAndIBANsOnlyWhenTheyPassTheCheck() {
        XCTAssertEqual(clean("my card 4111 1111 1111 1111 exp 12/29"), "my card [card number hidden] exp 12/29")
        XCTAssertFalse(clean("order 4111 1111 1111 1112").contains("card number hidden"))
        XCTAssertEqual(clean("pay to DE89 3704 0044 0532 0130 00 please"), "pay to [IBAN hidden] please")
        XCTAssertEqual(clean("pay to DE89370400440532013000"), "pay to [IBAN hidden]")
        XCTAssertFalse(Redactor.clean("ref DE00 1234 5678 9012 3456 78").hidden.contains(.iban))
    }

    func testPasswordsAreHiddenByTheirShape() {
        XCTAssertEqual(clean("wifi password: hunter2!"), "wifi password: [hidden]")
        XCTAssertEqual(clean("Das Passwort ist Sonne123"), "Das Passwort ist [hidden]")
        XCTAssertEqual(clean("PIN: 1234"), "PIN: [code hidden]")
        XCTAssertEqual(clean("the pin is on the map"), "the pin is on the map")
        XCTAssertEqual(clean("User's Wi-Fi password is Sonne123."), "User's Wi-Fi password is [hidden].")
        XCTAssertFalse(clean("the code for the door is \"Sonne123\", ok?").contains("Sonne123"))
        XCTAssertFalse(clean("(hunter22) is the new one!").contains("hunter22"))
        XCTAssertEqual(clean("open file2.txt at 10:30, version 2.0.1"), "open file2.txt at 10:30, version 2.0.1")
    }

    func testWordsWithDigitsThatAreNotSecretsStay() {
        let text = "COVID-19 rules, version 2.0.1, meet at 5pm on the 2nd, 4K video, 明天下午3点在2号门见"
        XCTAssertEqual(clean(text), text)
    }

    func testLinksKeepOnlyTheirSite() {
        XCTAssertEqual(clean("Reset here: https://evil.example/reset?token=abc123XYZ."), "Reset here: [link to evil.example].")
        XCTAssertEqual(clean("look at www.dhl.de/track/123"), "look at [link to www.dhl.de]")
    }

    func testPhoneNumbersAndEmailAddressesAreHidden() {
        XCTAssertEqual(clean("call me on +49 151 23456789 or 0151 2345 6789"), "call me on [phone number hidden] or [phone number hidden]")
        XCTAssertEqual(clean("write to anna.test@example.com"), "write to [email address hidden]")
        XCTAssertEqual(clean("(555) 123-4567"), "[phone number hidden]")
    }

    func testKeysAndTokensAreHidden() {
        let token = jwt(["sub": "1234567890", "name": "Synthetic Person"])
        XCTAssertEqual(clean("token \(token)"), "token [key hidden]")
        XCTAssertEqual(clean("key sk-proj-AbCdEfGhIjKlMnOpQrStUv123"), "key [key hidden]") // SYNTHETIC
        XCTAssertEqual(clean("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"), "[key hidden]") // SYNTHETIC
        XCTAssertEqual(clean("-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg\n-----END PRIVATE KEY-----"), "[private key hidden]") // SYNTHETIC
        XCTAssertEqual(clean("secret 9fQ2xL7pVb3KmZt8RwN4yH6cJd1sGa5E"), "secret [key hidden]") // SYNTHETIC
    }

    func testOrdinaryWordsStay() {
        let text = "Anna: are we still on for dinner tonight? Bring the Donaudampfschifffahrtsgesellschaftskapitän"
        XCTAssertEqual(clean(text), text)
        XCTAssertTrue(Redactor.clean(text).hidden.isEmpty)
    }
}

private final class FakeReply: PrivateReply {
    var told: [String] = []
    var works = true

    func tell(_ text: String, language _: String?) async -> Bool {
        told.append(text)
        return works
    }
}

final class GuardTests: XCTestCase {
    private let specs = [
        ToolSpec("list_notes", "", "{}"),
        ToolSpec("add_note", "", "{}"),
        ToolSpec("device_status", "", "{}"),
        ToolSpec("end_conversation", "", "{}"),
        ToolSpec("unknown_tool", "", "{}"),
    ]
    private let levels: [String: Level] = ["list_notes": .private, "add_note": .local, "device_status": .lookup, "end_conversation": .lookup]

    func testToolsWithoutALevelAreNeitherOfferedNorRun() async {
        let inner = RecordingTools(specs)
        let gate = Guard(inner: inner, levels: levels, reply: FakeReply())
        XCTAssertEqual(gate.tools().map(\.name), ["list_notes", "add_note", "device_status", "end_conversation"])
        let result = await gate.run(name: "unknown_tool", arguments: "{}", context: ToolContext(question: ""))
        XCTAssertTrue(result.hasPrefix("error"))
        XCTAssertTrue(inner.calls.isEmpty)
    }

    func testPrivateDataIsReadOutOnTheWatchAndNeverReachesChatGPT() async {
        let inner = RecordingTools(specs) { _, _ in "Your newest notes: door code 482913 | buy milk" }
        let reply = FakeReply()
        let gate = Guard(inner: inner, levels: levels, reply: reply)
        let result = await gate.run(name: "list_notes", arguments: "{}", context: ToolContext(question: "notes?"))
        XCTAssertEqual(result, toldOnWatch)
        XCTAssertEqual(reply.told, ["Your newest notes: door code [code hidden]. buy milk"])
        XCTAssertFalse(gate.sharedPrivateData)

        _ = await gate.run(name: "list_notes", arguments: #"{"details":true}"#, context: ToolContext(question: "the code?"))
        XCTAssertEqual(reply.told.last, "Your newest notes: door code 482913. buy milk")
    }

    func testWhenTheUserLetsChatGPTReadItItIsCleanedAndKeepsSearchOffTheLiveWeb() async {
        let inner = RecordingTools(specs) { _, _ in "Your newest notes: door code 482913" }
        let reply = FakeReply()
        let gate = Guard(inner: inner, levels: levels, reply: reply, toCloud: { ["list_notes"] })
        let result = await gate.run(name: "list_notes", arguments: "{}", context: ToolContext(question: ""))
        XCTAssertEqual(result, "Your newest notes: door code [code hidden]")
        XCTAssertTrue(reply.told.isEmpty)
        XCTAssertTrue(gate.sharedPrivateData)
        XCTAssertEqual(gate.sentToCloud, ["list_notes"])
    }

    func testAPrivateAnswerTheWatchCouldNotSayIsNotGuessed() async {
        let reply = FakeReply()
        reply.works = false
        let gate = Guard(inner: RecordingTools(specs) { _, _ in "notes" }, levels: levels, reply: reply)
        let result = await gate.run(name: "list_notes", arguments: "{}", context: ToolContext(question: ""))
        XCTAssertEqual(result, Guard.notSaid)
    }

    func testGoodbyeStopsEverythingButLookups() async {
        var stopped = false
        let gate = Guard(inner: RecordingTools(specs), levels: levels, reply: FakeReply(), stopTool: "end_conversation", onStop: { stopped = true })
        _ = await gate.run(name: "end_conversation", arguments: "{}", context: ToolContext(question: ""))
        XCTAssertTrue(stopped)
        let after = await gate.run(name: "add_note", arguments: "{}", context: ToolContext(question: ""))
        XCTAssertEqual(after, Guard.stoppedText)
        let lookup = await gate.run(name: "device_status", arguments: "{}", context: ToolContext(question: ""))
        XCTAssertEqual(lookup, "ok: 15:00")
    }

    func testAYesOnlyCountsInALaterTurnOfTheUserAndInTime() async {
        let clock = TestClock()
        let turns = UserTurns(now: { clock.now })
        let pending = Pending(turns: turns, now: { clock.now })
        turns.heard()
        clock.advance(1)
        var done = 0
        let asked = pending.propose("Call Anna (mobile)?") { done += 1; return "ok: calling" }
        XCTAssertTrue(asked.hasPrefix("not done yet: Call Anna (mobile)?"))
        let early = await pending.decide(yes: true)
        XCTAssertTrue(early.hasPrefix("error: the user hasn't answered yet"))
        clock.advance(5)
        turns.heard()
        let yes = await pending.decide(yes: true)
        XCTAssertEqual(yes, "ok: calling")
        XCTAssertEqual(done, 1)

        _ = pending.propose("Call Ben?") { "ok" }
        clock.advance(Pending.ttl + 1)
        turns.heard()
        let late = await pending.decide(yes: true)
        XCTAssertTrue(late.hasPrefix("error: that was too long ago"))
    }
}
