import AVFoundation
import Foundation
import NaturalLanguage
import WatchGPTKit

/// When the last word started, for WatchGPT's mouth. Read by the face on every frame, so it's not tied to an actor.
final class Pulse: @unchecked Sendable {
    private let lock = NSLock()
    private var at = Date.distantPast

    func beat() {
        lock.lock()
        at = Date()
        lock.unlock()
    }

    /// 0...1: open on each word, closing over a quarter of a second.
    func level(at now: Date) -> Float {
        lock.lock()
        let since = now.timeIntervalSince(at)
        lock.unlock()
        guard since >= 0, since < 0.6 else { return 0 }
        return Float(max(0, 1 - since / 0.25)) * 0.8 + 0.2 * Float(abs(sin(since * 14)))
    }
}

/// Says WatchGPT's answers with the watch's own voices (AVSpeechSynthesizer, on the watch, offline), one sentence after
/// another as they stream in, each in the language it is written in. Also how private answers are told: the watch says
/// them itself and ChatGPT only hears that it did.
@MainActor
final class Speaker: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published private(set) var speaking = false
    let pulse = Pulse()
    private let synthesizer = AVSpeechSynthesizer()
    private var queued = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Queues [text]; [language] (a BCP 47 tag) when the model named it, else it's recognised from the text.
    func say(_ text: String, language: String? = nil) {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return }
        if queued == 0 { activate() }
        let utterance = AVSpeechUtterance(string: words)
        utterance.voice = Self.voice(for: words, language: language)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        queued += 1
        speaking = true
        synthesizer.speak(utterance)
    }

    /// Returns once everything queued has been said (or stopped).
    func finished() async {
        guard queued > 0 else { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        done(all: true)
    }

    nonisolated func speechSynthesizer(_: AVSpeechSynthesizer, willSpeakRangeOfSpeechString _: NSRange, utterance _: AVSpeechUtterance) {
        pulse.beat()
    }

    nonisolated func speechSynthesizer(_: AVSpeechSynthesizer, didFinish _: AVSpeechUtterance) {
        Task { @MainActor in self.done(all: false) }
    }

    nonisolated func speechSynthesizer(_: AVSpeechSynthesizer, didCancel _: AVSpeechUtterance) {
        Task { @MainActor in self.done(all: false) }
    }

    private func done(all: Bool) {
        queued = all ? 0 : max(0, queued - 1)
        guard queued == 0 else { return }
        speaking = false
        deactivate()
        let resumed = waiting
        waiting = []
        resumed.forEach { $0.resume() }
    }

    private func activate() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers])
        try? session.setActive(true)
    }

    private func deactivate() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private static func voice(for text: String, language: String?) -> AVSpeechSynthesisVoice? {
        if let language, let voice = AVSpeechSynthesisVoice(language: language) { return voice }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        // Short pieces are easily mistaken: only trust a clear call.
        if let best = recognizer.languageHypotheses(withMaximum: 1).first, best.value > 0.6,
           let voice = AVSpeechSynthesisVoice(language: best.key.rawValue)
        {
            return voice
        }
        return AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
    }
}

/// How the Guard tells a private answer: the watch says it in its own voice.
final class WatchReply: PrivateReply {
    private let speaker: Speaker

    init(speaker: Speaker) {
        self.speaker = speaker
    }

    func tell(_ text: String, language: String?) async -> Bool {
        await MainActor.run { speaker.say(text, language: language) }
        return true
    }
}
