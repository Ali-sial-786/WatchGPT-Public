import Combine
import Foundation
import WatchGPTKit

/// One conversation: the user speaks (the watch's dictation), ChatGPT answers (streamed, with the watch's tools behind
/// the Guard), the watch says it sentence by sentence, then listens again, until the user says goodbye, taps the face
/// or stays quiet for the conversation timeout. Tapping again within the timeout picks the same conversation up.
@MainActor
final class Conversation: ObservableObject {
    enum Phase: Equatable { case idle, listening, thinking, speaking, error }

    @Published private(set) var phase: Phase = .idle
    /// Under the face: what was heard, then the answer, or what went wrong.
    @Published private(set) var caption = ""
    @Published private(set) var reaction: Reaction?
    @Published private(set) var reactionId = 0
    /// Dictation couldn't open: the screen offers typing.
    @Published private(set) var typeInstead = false
    @Published private(set) var active = false

    let speaker = Speaker()
    private let services = Services.shared
    private let turns = UserTurns()
    private let pending: Pending
    private var task: Task<Void, Never>?
    private var id = UUID().uuidString
    private var history: [ChatTurn] = []
    private var gate: Guard?
    private var ending = false
    private var endedAt: Date?
    private var healthShared = false

    init() {
        pending = Pending(turns: turns)
    }

    var act: Act {
        switch phase {
        case .idle: .rest
        case .listening: .listen
        case .thinking: .think
        case .speaking: .speak
        case .error: .error
        }
    }

    /// The face, the complication, Siri or a double tap: talk, or stop if a conversation is on.
    func toggle() {
        active ? stop() : start()
    }

    /// Starts listening; [typed] is the first thing the user said, when they typed it.
    func start(typed: String? = nil) {
        guard !active else {
            if let typed { continueTyped(typed) }
            return
        }
        let quiet = endedAt.map { Date().timeIntervalSince($0) } ?? .infinity
        if quiet > (Preferences.timeout ?? .infinity) || gate == nil || ending {
            startOver()
        }
        ending = false
        typeInstead = false
        active = true
        task = Task { await self.run(first: typed) }
    }

    func stop() {
        task?.cancel()
        task = nil
        speaker.stop()
        pending.cancel()
        active = false
        phase = .idle
        caption = ""
        endedAt = Date()
    }

    /// The model ended the conversation: it says goodbye, then WatchGPT stops listening.
    func endAfterReply() {
        ending = true
    }

    private func continueTyped(_ text: String) {
        task?.cancel()
        speaker.stop()
        task = Task { await self.run(first: text) }
    }

    private func startOver() {
        id = UUID().uuidString
        history = []
        healthShared = false
        pending.cancel()
        let reply = Reply()
        reply.conversation = self
        let watched = Reactions(inner: WatchActions(
            reminders: services.reminders,
            notes: services.notes,
            memory: services.memory,
            device: services.device,
            pending: pending,
            turns: turns,
            zone: { TimeZone.current }
        )) { name, result in
            Task { @MainActor in AppModel.shared.conversation.react(to: name, result: result) }
        }
        gate = Guard(
            inner: watched,
            levels: Safety.levels,
            reply: reply,
            stopTool: WatchActions.end,
            onStop: { [pending] in pending.cancel() },
            toCloud: { Safety.toCloud(privateOnWatch: Preferences.privateOnWatch, sleepToChatGPT: Preferences.sleepToChatGPT) }
        )
    }

    private func run(first: String?) async {
        var said = first
        var firstTurn = true
        while !Task.isCancelled {
            if said == nil {
                phase = .listening
                caption = "Listening…"
                // After an answer, silence ends the conversation. The first turn the user asked for themselves, so it
                // waits longer, but never forever: if dictation never showed, nothing else would end the wait.
                switch await Dictation.listen(timeout: firstTurn ? Self.firstTurnTimeout : Preferences.timeout) {
                case let .words(words):
                    said = words
                case .nothing:
                    break
                case .unavailable:
                    typeInstead = true
                }
            }
            guard let words = said, !Task.isCancelled else { break }
            said = nil
            firstTurn = false
            await answer(words)
            if ending || !Preferences.keepTalking { break }
        }
        guard !Task.isCancelled else { return }
        await speaker.finished()
        // Stopped, or replaced by a typed question, while the last words were said: the state is the new run's.
        guard !Task.isCancelled else { return }
        active = false
        task = nil
        endedAt = Date()
        if phase != .error {
            phase = .idle
            caption = typeInstead ? "Dictation didn't open: type instead" : ""
        }
    }

    private func answer(_ words: String) async {
        turns.heard()
        phase = .thinking
        caption = words
        shownPrivately = false
        await services.memory.add(conversation: id, user: true, text: words, health: healthShared)
        let remembered = await services.memory.context(except: id)
        let request = ChatRequest(
            conversationId: id,
            history: history,
            userText: words,
            style: .spoken,
            context: [DeviceContext.describe(), remembered].compactMap { $0 }.joined(separator: "\n"),
            webSearch: Preferences.webSearch,
            tools: gate
        )
        var splitter = SentenceSplitter()
        var text = ""
        var failure: BrainError?
        for await event in services.brain.stream(request) {
            // A stream still hands over what it had buffered after the conversation stopped.
            if Task.isCancelled { break }
            switch event {
            case let .delta(delta):
                text += delta
                splitter.add(delta).forEach { say($0) }
            case .done:
                break
            case let .failed(error, _):
                failure = error
            }
        }
        if Task.isCancelled { return }
        if let rest = splitter.flush() { say(rest) }
        if let failure {
            let message = Texts.error(failure)
            if text.isEmpty { say(message) }
            phase = .error
            caption = message
            show(.sad)
            ending = true
            switch failure {
            case .notSignedIn, .authExpired: AppModel.shared.showSignIn = true
            default: break
            }
            await speaker.finished()
            return
        }
        history += [ChatTurn(.user, words), ChatTurn(.assistant, text)]
        healthShared = healthShared || gate?.sentToCloud.contains(WatchActions.readSleep) == true
        await services.memory.add(conversation: id, user: false, text: text, health: healthShared)
        if !shownPrivately { caption = text }
        await speaker.finished()
    }

    private static let firstTurnTimeout: TimeInterval = 120

    /// Whether the watch read something private out in this turn: then the caption keeps it.
    private var shownPrivately = false

    fileprivate func say(_ piece: String, language: String? = nil, privately: Bool = false) {
        // A tool that finishes after the user stopped doesn't speak up.
        guard active else { return }
        speaker.say(piece, language: language)
        if privately {
            caption = piece
            shownPrivately = true
        }
        if phase != .error { phase = .speaking }
    }

    /// WatchGPT's face reacts to what a tool did.
    func react(to tool: String, result: String) {
        if result.hasPrefix("error") { return show(.confused) }
        let done = result.hasPrefix("ok")
        switch tool {
        case WatchActions.addReminder, WatchActions.addEvent, WatchActions.addNote, WatchActions.setTimer, WatchActions.changeReminder:
            if done { show(.happy) }
        case WatchActions.remember:
            if done { show(.love) }
        case WatchActions.end:
            show(.greet)
        case WatchActions.readSleep:
            show(.sleepy)
        default:
            break
        }
    }

    private func show(_ mood: Mood) {
        reaction = Reaction(mood)
        reactionId += 1
    }

    /// How the Guard tells a private answer: the watch says it and shows it.
    private final class Reply: PrivateReply {
        weak var conversation: Conversation?

        func tell(_ text: String, language: String?) async -> Bool {
            await MainActor.run { conversation?.say(text, language: language, privately: true) }
            return true
        }
    }
}

/// The watch's tools, telling the face what each one did.
final class Reactions: Toolbox {
    private let inner: Toolbox
    private let onResult: (String, String) -> Void

    init(inner: Toolbox, onResult: @escaping (String, String) -> Void) {
        self.inner = inner
        self.onResult = onResult
    }

    func tools() -> [ToolSpec] {
        inner.tools()
    }

    func run(name: String, arguments: String, context: ToolContext) async -> String {
        let result = await inner.run(name: name, arguments: arguments, context: context)
        onResult(name, result)
        return result
    }

    var sharedPrivateData: Bool { inner.sharedPrivateData }
}
