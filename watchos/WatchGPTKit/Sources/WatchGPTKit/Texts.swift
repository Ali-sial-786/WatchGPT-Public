import Foundation

/// What the user sees and hears when something goes wrong. Built from error codes only; provider text is never shown.
public enum Texts {
    public static func error(_ error: BrainError, now: Date = Date(), zone: TimeZone = .current) -> String {
        switch error {
        case .notSignedIn:
            return "Sign in to ChatGPT first, in WatchGPT's settings."
        case .authExpired:
            return "Your ChatGPT sign-in ended. Please sign in again."
        case let .rateLimited(ms):
            return ms.map { "ChatGPT is busy. Try again in \(max($0 / 1000, 1)) seconds." } ?? "ChatGPT is busy. Try again in a moment."
        case let .usageLimitReached(resets, notIncluded):
            if notIncluded { return "Your ChatGPT plan doesn't include this." }
            guard let resets else { return "You've used your ChatGPT allowance for now." }
            let at = LocalDateTime(Date(timeIntervalSince1970: TimeInterval(resets)), in: zone)
            return "You've used your ChatGPT allowance for now. It resets at \(ToolArgs.sayClock(at, hours24: true))."
        case .offline:
            return "No connection. Check that your watch is online and try again."
        case .providerChanged:
            return "ChatGPT changed something on their side. This needs an app update."
        case .quotaExceeded:
            return "Your ChatGPT plan's quota is used up."
        case .refused:
            return "ChatGPT won't answer that."
        case .unknown:
            return "Something went wrong with ChatGPT. Try again."
        }
    }

    public static func signIn(_ error: SignInError) -> String {
        switch error {
        case .missingEntitlement: return "This ChatGPT workspace doesn't include Codex, so its plan can't be used here."
        case .exchangeFailed: return "Sign-in didn't work. Try again."
        case .accountClaimMissing: return "OpenAI didn't say which account this is. Try again."
        case .offline: return "No connection. Check that your watch is online."
        case .deviceCodeDisabled: return "OpenAI turned off sign-in with a code for now."
        case .deviceCodeExpired: return "The code expired. Get a new one."
        case .secureStorageUnavailable: return "The watch couldn't keep the sign-in safely. Set a passcode on the watch and try again."
        }
    }
}
