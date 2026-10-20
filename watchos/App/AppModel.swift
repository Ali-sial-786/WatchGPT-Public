import Foundation
import WatchGPTKit

/// What the screens show: the sign-in, WatchGPT's look, the conversation, and which screen to open.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var auth: AuthState = .unknown
    /// The same ChatGPT account gets the same WatchGPT as on Android; before sign-in, one for this install.
    @Published private(set) var genes: Genes
    @Published var showSignIn = false
    @Published var showReminders = false
    /// Goes up whenever reminders change, so lists reload.
    @Published var remindersVersion = 0

    let conversation = Conversation()

    private init() {
        Preferences.register()
        genes = Genes.forUser(Preferences.installId)
    }

    /// At launch and when the app comes back.
    func refresh() async {
        _ = await Services.shared.auth.isSignedIn()
        auth = await Services.shared.auth.state
        await updateGenes()
    }

    func authChanged(_ state: AuthState) {
        auth = state
        if case .signedIn = state { showSignIn = false }
        Task { await updateGenes() }
    }

    /// A tap on the face: talk (or stop), or sign in first.
    func talkTapped() {
        if case .signedOut = auth, !conversation.active {
            showSignIn = true
            return
        }
        conversation.toggle()
    }

    /// From the complication, Siri or a shortcut: start listening (never stop a conversation that's on).
    func requestTalk() {
        guard !conversation.active else { return }
        if case .signedOut = auth {
            showSignIn = true
            return
        }
        conversation.start()
    }

    func open(_ url: URL) {
        switch url.host {
        case "talk": requestTalk()
        case "reminders": showReminders = true
        default: break
        }
    }

    private func updateGenes() async {
        let account = await Services.shared.auth.accountId()
        let next = Genes.forUser(account ?? Preferences.installId)
        if next != genes { genes = next }
    }
}
