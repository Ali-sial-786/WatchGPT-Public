import WatchKit

/// What the user says, through the watch's own dictation (the same as in Messages): WatchGPT never records or sends
/// audio itself, only the words dictation gives it.
@MainActor
enum Dictation {
    enum Heard: Equatable {
        case words(String)
        /// Cancelled, or the timeout passed without any words.
        case nothing
        /// Dictation couldn't open: the screen offers typing instead.
        case unavailable
    }

    static func listen(timeout: TimeInterval?) async -> Heard {
        let app = WKApplication.shared()
        guard let controller = app.visibleInterfaceController ?? app.rootInterfaceController else { return .unavailable }
        return await withCheckedContinuation { continuation in
            let once = Once(continuation)
            controller.presentTextInputController(withSuggestions: nil, allowedInputMode: .plain) { results in
                let text = (results?.first as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                once.finish(text.isEmpty ? .nothing : .words(text))
            }
            if let timeout {
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                    guard !once.done else { return }
                    controller.dismissTextInputController()
                    once.finish(.nothing)
                }
            }
        }
    }

    /// Resumes the caller once, whichever comes first: the words or the timeout.
    private final class Once {
        private var continuation: CheckedContinuation<Heard, Never>?

        init(_ continuation: CheckedContinuation<Heard, Never>) {
            self.continuation = continuation
        }

        var done: Bool { continuation == nil }

        func finish(_ heard: Heard) {
            continuation?.resume(returning: heard)
            continuation = nil
        }
    }
}
