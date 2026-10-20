import SwiftUI
import WatchGPTKit

typealias Reminder = WatchGPTKit.Entry

/// The reminders and events on this watch, each by its next time. Works without ChatGPT and without the iPhone.
struct RemindersView: View {
    @EnvironmentObject private var model: AppModel
    @State private var entries: [Reminder] = []

    var body: some View {
        let now = Date()
        let open = entries.filter { !$0.completed }.sorted { Agenda.shown($0, now: now, zone: .current) < Agenda.shown($1, now: now, zone: .current) }
        let done = entries.filter(\.completed)
        List {
            NavigationLink {
                ReminderEditor(original: nil)
            } label: {
                Label("New reminder", systemImage: "plus")
            }
            if open.isEmpty {
                Text("No reminders. Add one here, or ask WatchGPT.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(open) { entry in
                NavigationLink {
                    ReminderEditor(original: entry)
                } label: {
                    ReminderRow(entry: entry, now: now)
                }
                .swipeActions(edge: .leading) {
                    Button {
                        Task { await change { await $0.complete(entry.id, occurrence: Agenda.current(entry, now: now, zone: .current)) } }
                    } label: {
                        Label("Done", systemImage: "checkmark")
                    }
                    .tint(.green)
                }
            }
            if !done.isEmpty {
                Section("Done") {
                    ForEach(done) { entry in
                        Text(entry.title).strikethrough().foregroundStyle(.secondary)
                    }
                    .onDelete { offsets in
                        let gone = offsets.map { done[$0].id }
                        Task { await change { book in for id in gone { await book.delete(id) } } }
                    }
                }
            }
        }
        .navigationTitle("Reminders")
        .task(id: model.remindersVersion) {
            entries = await Services.shared.reminders.all()
        }
    }

    private func change(_ edit: (ReminderBook) async -> Void) async {
        await edit(Services.shared.reminders)
        await Notifier.reschedule()
        model.remindersVersion += 1
    }
}

struct ReminderRow: View {
    let entry: Reminder
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(entry.title).lineLimit(2)
            Text(when)
                .font(.footnote)
                .foregroundStyle(Agenda.overdue(entry, now: now, zone: .current) ? Color.orange : Color.secondary)
        }
    }

    private var when: String {
        let shown = Agenda.shown(entry, now: now, zone: .current)
        let day = ToolArgs.sayDay(shown, now: now, zone: .current).capitalizedFirst
        let time = entry.allDay ? "all day" : ToolArgs.sayClock(shown, hours24: Preferences.hours24)
        let repeats = entry.repeat == .none ? "" : " · \(RepeatText.label(entry.repeat))"
        return "\(day), \(time)\(repeats)"
    }
}

enum RepeatText {
    static func label(_ repeats: Repeat) -> String {
        switch repeats {
        case .none: "Never"
        case .daily: "Daily"
        case .weekdays: "Weekdays"
        case .weekly: "Weekly"
        case .monthly: "Monthly"
        }
    }
}

/// A new reminder, or one to change. Saving a completed one opens it again.
struct ReminderEditor: View {
    let original: Reminder?
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var date = Date().addingTimeInterval(3600)
    @State private var allDay = false
    @State private var repeats = Repeat.none
    @State private var notes = ""
    @State private var event = false
    @State private var loaded = false
    @State private var confirmDelete = false

    var body: some View {
        Form {
            TextField("Title", text: $title)
            DatePicker("Date", selection: $date, displayedComponents: allDay ? [.date] : [.date, .hourAndMinute])
            Toggle("All day", isOn: $allDay)
            Toggle("Event", isOn: $event)
            Picker("Repeat", selection: $repeats) {
                ForEach(Repeat.allCases, id: \.self) { option in
                    Text(RepeatText.label(option)).tag(option)
                }
            }
            TextField("Notes", text: $notes)
            Button("Save") { Task { await save() } }
                .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if original != nil {
                Button("Delete", role: .destructive) { confirmDelete = true }
            }
        }
        .navigationTitle(original == nil ? "New" : "Edit")
        .onAppear(perform: load)
        .confirmationDialog("Delete this \(repeats == .none ? "item" : "series")?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) { Task { await delete() } }
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let original else { return }
        title = original.title
        notes = original.notes
        allDay = original.allDay
        repeats = original.repeat
        event = original.kind == .event
        date = Agenda.current(original, now: Date(), zone: .current).date(in: original.effectiveZone(.current))
    }

    private func save() async {
        let local = LocalDateTime(date, in: .current)
        let at = allDay ? local.startOfDay : local
        var entry = original ?? Reminder(title: title, localTime: at)
        let moved = original.map { $0.localTime != at || $0.repeat != repeats || $0.allDay != allDay } ?? true
        entry.title = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200))
        entry.notes = String(notes.prefix(8000))
        entry.allDay = allDay
        entry.repeat = repeats
        entry.kind = event ? .event : .reminder
        entry.zone = TimeZone.current.identifier
        if moved {
            entry.localTime = at
            entry.monthlyDay = nil
            entry.snoozeUntil = nil
        }
        // Saving a completed item reopens it.
        entry.completed = false
        await Services.shared.reminders.save(entry)
        if !(await Notifier.allowed()) { await Notifier.requestPermission() }
        await Notifier.reschedule()
        model.remindersVersion += 1
        dismiss()
    }

    private func delete() async {
        guard let original else { return }
        await Services.shared.reminders.delete(original.id)
        await Notifier.reschedule()
        model.remindersVersion += 1
        dismiss()
    }
}

private extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
