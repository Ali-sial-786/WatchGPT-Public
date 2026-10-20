import AppIntents

/// "Talk to WatchGPT": from Siri, the Shortcuts app, a Smart Stack suggestion or the Action button on Apple Watch
/// Ultra (Settings → Action Button → Shortcut). Opens WatchGPT listening.
struct TalkIntent: AppIntent {
    static let title: LocalizedStringResource = "Talk to WatchGPT"
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        AppModel.shared.requestTalk()
        return .result()
    }
}

struct WatchGPTShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: TalkIntent(),
            phrases: ["Talk to \(.applicationName)", "Ask \(.applicationName)"],
            shortTitle: "Talk",
            systemImageName: "bubble.left.fill"
        )
    }
}
