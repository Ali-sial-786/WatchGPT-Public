import SwiftUI
import WatchGPTKit

/// Sign in with ChatGPT on the watch itself: the watch shows a code, the user enters it on OpenAI's page on their
/// iPhone or a computer, and the watch finishes on its own. No API key, and nothing in between but OpenAI.
struct SignInView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var attempt = 0
    @State private var code: DeviceCode?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                if let code {
                    Text("On your iPhone or a computer, open")
                        .font(.footnote)
                    Text(code.verificationURL.replacingOccurrences(of: "https://", with: ""))
                        .font(.footnote.bold())
                    Text("and enter")
                        .font(.footnote)
                    Text(code.userCode)
                        .font(.title3.monospaced().bold())
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                    ProgressView()
                    Text("Waiting for you to sign in…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Sign in with ChatGPT")
                        .font(.headline)
                    Text("WatchGPT talks to ChatGPT with your own plan. No API key.")
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                    if attempt == 0 || error != nil {
                        Button(error == nil ? "Get a code" : "Try again") { attempt += 1 }
                    } else {
                        ProgressView()
                    }
                }
                if let error {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                }
            }
        }
        .task(id: attempt) { await signIn() }
    }

    private func signIn() async {
        guard attempt > 0 else { return }
        error = nil
        code = nil
        let services = Services.shared
        let flow = DeviceSignIn(oauth: services.oauth, session: services.auth)
        do {
            let (shown, expires) = try await flow.start()
            code = shown
            _ = try await flow.finish(shown, expiresAt: expires)
            dismiss()
        } catch is CancellationError {
            // The screen closed.
        } catch let failure as SignInError {
            code = nil
            error = Texts.signIn(failure)
        } catch {
            code = nil
            self.error = "Sign-in didn't work. Try again."
        }
    }
}
