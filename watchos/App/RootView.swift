import SwiftUI
import WatchGPTKit
import WatchKit

/// One screen, as on Wear OS: WatchGPT and a line of text. Tap the face (or double tap, on watches that have it) to
/// talk; reminders and settings are a scroll away.
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var conversation: Conversation

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 6) {
                    face
                    Text(caption)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .lineLimit(4)
                        .foregroundStyle(conversation.phase == .error ? Color.orange : Color.primary)
                        .frame(maxWidth: .infinity)
                        .accessibilityAddTraits(.updatesFrequently)
                    if conversation.typeInstead || !conversation.active {
                        TextFieldLink(prompt: Text("Ask WatchGPT")) {
                            Label("Type instead", systemImage: "keyboard")
                        } onSubmit: { text in
                            let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !words.isEmpty else { return }
                            if case .signedOut = model.auth { model.showSignIn = true } else { conversation.start(typed: words) }
                        }
                    }
                    if !conversation.active {
                        NavigationLink {
                            RemindersView()
                        } label: {
                            Label("Reminders", systemImage: "checklist")
                        }
                        NavigationLink {
                            SettingsView()
                        } label: {
                            Label("Settings", systemImage: "gearshape")
                        }
                    }
                }
                .padding(.horizontal, 4)
            }
            .navigationDestination(isPresented: $model.showReminders) { RemindersView() }
            .sheet(isPresented: $model.showSignIn) { SignInView() }
        }
    }

    private var face: some View {
        let side = WKInterfaceDevice.current().screenBounds.width * 0.72
        return Button {
            model.talkTapped()
        } label: {
            FaceView(genes: model.genes, act: conversation.act, reaction: conversation.reaction, reactionId: conversation.reactionId, pulse: conversation.speaker.pulse)
                .frame(width: side, height: side)
        }
        .buttonStyle(.plain)
        .modifier(DoubleTapToTalk())
        .accessibilityLabel(conversation.active ? "End the conversation" : "Talk to WatchGPT")
    }

    private var caption: String {
        if !conversation.caption.isEmpty { return conversation.caption }
        switch model.auth {
        case .signedOut: return "Tap to sign in to ChatGPT"
        default: return "Tap to talk"
        }
    }
}

/// Double tap (watchOS 11, Series 9 and Ultra 2 and later) presses the face: the hands-free way to start talking.
private struct DoubleTapToTalk: ViewModifier {
    func body(content: Content) -> some View {
        if #available(watchOS 11.0, *) {
            content.handGestureShortcut(.primaryAction)
        } else {
            content
        }
    }
}
