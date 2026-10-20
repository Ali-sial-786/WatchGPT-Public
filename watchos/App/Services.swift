import Contacts
import EventKit
import Foundation
import HealthKit
import WatchGPTKit
import WatchKit

/// What lives as long as the app: ChatGPT, the stores and the watch's services. Not tied to the main thread, so
/// notification actions and background refreshes can use it too.
final class Services: @unchecked Sendable {
    static let shared = Services()

    let files = FileStore()
    let transport = URLSessionTransport()
    let oauth: OAuthClient
    let auth: AuthSession
    let brain: ChatGPTBrain
    let reminders: ReminderBook
    let notes: NoteBook
    let memory: Memory
    let device = WatchServices()

    private init() {
        oauth = OAuthClient(http: transport)
        auth = AuthSession(store: KeychainStore(), oauth: oauth, onChange: { state in
            Task { @MainActor in AppModel.shared.authChanged(state) }
        })
        brain = ChatGPTBrain(session: auth, transport: transport)
        reminders = ReminderBook(store: files)
        notes = NoteBook(store: files)
        memory = Memory(store: files)
    }

    /// Everything WatchGPT keeps on the watch, gone; the ChatGPT sign-in stays.
    func deleteEverything() async {
        await reminders.clear()
        await notes.clear()
        await memory.clear()
        await Notifier.reschedule()
    }
}

/// The watch's side of WatchGPT's tools: HealthKit, EventKit, Contacts, notifications and the system's Phone and
/// Messages.
final class WatchServices: WatchDevice, @unchecked Sendable {
    private let health = HKHealthStore()
    private let events = EKEventStore()
    private let contactStore = CNContactStore()
    private static let sleepType = HKCategoryType(.sleepAnalysis)

    var hours24: Bool { Preferences.hours24 }

    func battery() async -> (level: Double?, charging: Bool?) {
        await MainActor.run { () -> (level: Double?, charging: Bool?) in
            let device = WKInterfaceDevice.current()
            device.isBatteryMonitoringEnabled = true
            let level = device.batteryLevel
            let charging: Bool? = switch device.batteryState {
            case .charging, .full: true
            case .unplugged: false
            default: nil
            }
            return (level >= 0 ? Double(level) : nil, charging)
        }
    }

    func startTimer(seconds: Int, label: String?) async -> Bool {
        await Notifier.timer(seconds: seconds, label: label)
    }

    func remindersChanged() async {
        await Notifier.reschedule()
        await MainActor.run { AppModel.shared.remindersVersion += 1 }
    }

    func endConversation() {
        Task { @MainActor in AppModel.shared.conversation.endAfterReply() }
    }

    func call(_ number: String) async -> Bool {
        guard let url = URL(string: "tel:\(number.filter { $0.isNumber || $0 == "+" })") else { return false }
        await MainActor.run { WKApplication.shared().openSystemURL(url) }
        return true
    }

    func compose(to number: String, body: String) async -> Bool {
        // Everything but letters, digits and -._~ encoded, so "?", "&" or "#" in the text can't cut it short.
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let text = body.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
        guard let url = URL(string: "sms:\(number.filter { $0.isNumber || $0 == "+" })&body=\(text)") else { return false }
        await MainActor.run { WKApplication.shared().openSystemURL(url) }
        return true
    }

    // MARK: - Health

    /// Whether the user was asked about sleep yet; reading never says whether they said yes (HealthKit keeps that private).
    func sleepAsked() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        let status = try? await health.statusForAuthorizationRequest(toShare: [], read: [Self.sleepType])
        return status == .unnecessary
    }

    func askForSleep() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        try? await health.requestAuthorization(toShare: [], read: [Self.sleepType])
    }

    func sleep(from: Date, to: Date) async -> Access<[SleepSample]> {
        guard HKHealthStore.isHealthDataAvailable() else { return .unavailable }
        guard await sleepAsked() else { return .denied }
        return await withCheckedContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(withStart: from, end: to, options: [])
            let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
            let query = HKSampleQuery(sampleType: Self.sleepType, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: [sort]) { _, samples, _ in
                let out = (samples as? [HKCategorySample] ?? []).compactMap { sample -> SleepSample? in
                    guard let stage = Self.stage(sample.value) else { return nil }
                    return SleepSample(start: sample.startDate, end: sample.endDate, stage: stage, source: sample.sourceRevision.source.name)
                }
                continuation.resume(returning: .granted(out))
            }
            health.execute(query)
        }
    }

    /// In bed isn't asleep or awake: it only says the user was in bed, so it's left out.
    private static func stage(_ value: Int) -> SleepSample.Stage? {
        switch HKCategoryValueSleepAnalysis(rawValue: value) {
        case .asleepUnspecified: .asleep
        case .asleepCore: .core
        case .asleepDeep: .deep
        case .asleepREM: .rem
        case .awake: .awake
        default: nil
        }
    }

    // MARK: - Calendar

    var calendarAllowed: Bool {
        EKEventStore.authorizationStatus(for: .event) == .fullAccess
    }

    func askForCalendar() async {
        _ = try? await events.requestFullAccessToEvents()
    }

    func calendar(from: Date, to: Date) async -> Access<[CalendarItem]> {
        guard calendarAllowed else { return .denied }
        let predicate = events.predicateForEvents(withStart: from, end: to, calendars: nil)
        return .granted(events.events(matching: predicate).prefix(30).map {
            CalendarItem(title: $0.title ?? "Event", start: $0.startDate, end: $0.endDate, allDay: $0.isAllDay, location: $0.location?.isEmpty == false ? $0.location : nil)
        })
    }

    // MARK: - Contacts

    var contactsAllowed: Bool {
        CNContactStore.authorizationStatus(for: .contacts) == .authorized
    }

    func askForContacts() async {
        _ = try? await contactStore.requestAccess(for: .contacts)
    }

    func contacts() async -> Access<[Contact]> {
        guard contactsAllowed else { return .denied }
        let keys = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName), CNContactNicknameKey as CNKeyDescriptor, CNContactPhoneNumbersKey as CNKeyDescriptor]
        let request = CNContactFetchRequest(keysToFetch: keys)
        var out: [Contact] = []
        try? contactStore.enumerateContacts(with: request) { contact, _ in
            guard let name = CNContactFormatter.string(from: contact, style: .fullName), !name.isEmpty else { return }
            let phones = contact.phoneNumbers.map { labeled in
                Contact.Phone(label: labeled.label.map { CNLabeledValue<CNPhoneNumber>.localizedString(forLabel: $0) } ?? "phone", number: labeled.value.stringValue)
            }
            out.append(Contact(name: name, nickname: contact.nickname.isEmpty ? nil : contact.nickname, phones: phones))
        }
        return .granted(out)
    }
}
