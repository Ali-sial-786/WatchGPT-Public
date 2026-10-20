import Foundation

/// The user's choices, in UserDefaults (thread-safe, so a tool call can read them as they are now). Views bind to the
/// same keys with @AppStorage.
enum Preferences {
    enum Key {
        static let timeout = "conversationTimeout"
        static let keepTalking = "keepTalking"
        static let privateOnWatch = "privateOnWatch"
        static let sleepToChatGPT = "sleepToChatGPT"
        static let webSearch = "webSearch"
        static let installId = "installId"
    }

    struct Timeout: Hashable {
        let seconds: Int
        let label: String
    }

    /// How long WatchGPT waits for the user to speak again before the conversation ends, in seconds; 0 means "until I stop".
    static let timeouts = [
        Timeout(seconds: 10, label: "10 seconds"), Timeout(seconds: 30, label: "30 seconds"), Timeout(seconds: 60, label: "1 minute"),
        Timeout(seconds: 120, label: "2 minutes"), Timeout(seconds: 300, label: "5 minutes"), Timeout(seconds: 900, label: "15 minutes"),
        Timeout(seconds: 0, label: "Until I stop"),
    ]

    static func register() {
        UserDefaults.standard.register(defaults: [
            Key.timeout: 30,
            Key.keepTalking: true,
            Key.privateOnWatch: true,
            Key.sleepToChatGPT: false,
            Key.webSearch: true,
        ])
    }

    static var timeout: TimeInterval? {
        let seconds = UserDefaults.standard.integer(forKey: Key.timeout)
        return seconds > 0 ? TimeInterval(seconds) : nil
    }

    static var keepTalking: Bool { UserDefaults.standard.bool(forKey: Key.keepTalking) }
    static var privateOnWatch: Bool { UserDefaults.standard.bool(forKey: Key.privateOnWatch) }
    static var sleepToChatGPT: Bool { UserDefaults.standard.bool(forKey: Key.sleepToChatGPT) }
    static var webSearch: Bool { UserDefaults.standard.bool(forKey: Key.webSearch) }

    /// A random id for this install: WatchGPT's look before the user signs in.
    static var installId: String {
        if let id = UserDefaults.standard.string(forKey: Key.installId) { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: Key.installId)
        return id
    }

    /// Whether the watch shows times on a 24-hour clock.
    static var hours24: Bool {
        !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }
}
