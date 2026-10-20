import SwiftUI
import WatchGPTKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(Preferences.Key.timeout) private var timeout = 30
    @AppStorage(Preferences.Key.keepTalking) private var keepTalking = true
    @AppStorage(Preferences.Key.webSearch) private var webSearch = true
    @AppStorage(Preferences.Key.privateOnWatch) private var privateOnWatch = true
    @AppStorage(Preferences.Key.sleepToChatGPT) private var sleepToChatGPT = false
    @State private var facts: [Memory.Fact] = []
    @State private var notifications = false
    @State private var sleepAsked = false
    @State private var calendar = false
    @State private var contacts = false
    @State private var confirmDelete = false

    var body: some View {
        List {
            Section("ChatGPT") {
                switch model.auth {
                case let .signedIn(email, plan):
                    VStack(alignment: .leading) {
                        Text(email ?? "Signed in")
                        if let plan { Text("Plan: \(plan)").font(.footnote).foregroundStyle(.secondary) }
                    }
                    Button("Sign out", role: .destructive) {
                        Task { await Services.shared.auth.signOut() }
                    }
                default:
                    Button("Sign in") { model.showSignIn = true }
                }
            }

            Section {
                Picker("Wait for me", selection: $timeout) {
                    ForEach(Preferences.timeouts, id: \.seconds) { option in
                        Text(option.label).tag(option.seconds)
                    }
                }
                Toggle("Keep talking after answers", isOn: $keepTalking)
                Toggle("Search the web", isOn: $webSearch)
            } header: {
                Text("Talking")
            } footer: {
                Text("After an answer WatchGPT listens again, and ends the conversation when you say nothing for this long.")
            }

            Section {
                Toggle("Private things stay on this watch", isOn: $privateOnWatch)
                Toggle("Sleep in ChatGPT's voice", isOn: $sleepToChatGPT)
            } header: {
                Text("Privacy")
            } footer: {
                Text("Your notes, reminders and calendar are read out by the watch itself; ChatGPT only hears that it did. " +
                    "Off: ChatGPT gets them, with codes, numbers and links taken out. Sleep has its own switch.")
            }

            Section {
                access("Notifications", allowed: notifications) { await Notifier.requestPermission() }
                access("Sleep", allowed: sleepAsked, asked: true) { await Services.shared.device.askForSleep() }
                access("Calendar", allowed: calendar) { await Services.shared.device.askForCalendar() }
                access("Contacts", allowed: contacts) { await Services.shared.device.askForContacts() }
            } header: {
                Text("What WatchGPT can use")
            } footer: {
                Text("Notifications ring reminders and timers. Sleep comes from Apple Health; contacts are for calls and texts you confirm.")
            }

            Section {
                if facts.isEmpty {
                    Text("Nothing yet. Say \"remember that…\" in a conversation.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(facts) { fact in
                    Text(fact.text).font(.footnote)
                }
                .onDelete { offsets in
                    let gone = offsets.map { facts[$0].id }
                    Task {
                        for id in gone { _ = await Services.shared.memory.forget(id) }
                        await reload()
                    }
                }
                Button("Delete everything", role: .destructive) { confirmDelete = true }
            } header: {
                Text("What WatchGPT remembers")
            } footer: {
                Text("Swipe to forget one. Conversations are kept on this watch for 30 days.")
            }

            NavigationLink("Licences") { LicencesView() }
        }
        .navigationTitle("Settings")
        .task { await reload() }
        .confirmationDialog("Delete everything?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                Task {
                    await Services.shared.deleteEverything()
                    model.remindersVersion += 1
                    await reload()
                }
            }
        } message: {
            Text("Reminders, notes, conversations and what WatchGPT remembers go. Your ChatGPT sign-in stays.")
        }
    }

    /// A permission: allowed, or a button that asks. HealthKit never says whether reading was allowed, only whether
    /// the user was asked ([asked]).
    private func access(_ name: String, allowed: Bool, asked: Bool = false, ask: @escaping () async -> Void) -> some View {
        HStack {
            Text(name)
            Spacer()
            if allowed {
                Text(asked ? "Asked" : "On").foregroundStyle(.secondary)
            } else {
                Button("Allow") {
                    Task {
                        await ask()
                        await reload()
                    }
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
            }
        }
    }

    private func reload() async {
        facts = await Services.shared.memory.kept().facts
        notifications = await Notifier.allowed()
        sleepAsked = await Services.shared.device.sleepAsked()
        calendar = Services.shared.device.calendarAllowed
        contacts = Services.shared.device.contactsAllowed
    }
}
