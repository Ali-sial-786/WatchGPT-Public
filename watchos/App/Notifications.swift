import Foundation
import UserNotifications
import WatchGPTKit

/// Reminder alerts and timers as local notifications on the watch. watchOS keeps at most 64 per app, so the nearest
/// reminder alerts are scheduled and the plan is redone whenever the app runs, an alert is acted on, or the background
/// refresh comes round.
enum Notifier {
    static let reminderCategory = "reminder"
    static let doneAction = "done"
    static let snoozeAction = "snooze"
    private static let timerPrefix = "timer."
    private static let center = UNUserNotificationCenter.current()

    static func registerCategories() {
        let done = UNNotificationAction(identifier: doneAction, title: "Done", options: [])
        let snooze = UNNotificationAction(identifier: snoozeAction, title: "Snooze 10 min", options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: reminderCategory, actions: [done, snooze], intentIdentifiers: [], options: [])])
    }

    static func allowed() async -> Bool {
        let status = await center.notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }

    @discardableResult
    static func requestPermission() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
    }

    /// Replaces the scheduled reminder alerts with the plan for the reminders as they are now.
    static func reschedule() async {
        let entries = await Services.shared.reminders.all()
        let pending = await center.pendingNotificationRequests()
        let old = pending.map(\.identifier).filter { $0.hasPrefix(AlertPlan.prefix) }
        let others = pending.count - old.count
        let plan = AlertPlan.upcoming(entries, now: Date(), zone: .current, limit: max(0, 60 - others))
        center.removePendingNotificationRequests(withIdentifiers: old)
        for alert in plan {
            let content = UNMutableNotificationContent()
            content.title = alert.title
            content.body = alert.body
            content.sound = .default
            content.categoryIdentifier = reminderCategory
            content.threadIdentifier = "reminders"
            content.userInfo = ["entry": alert.entryId, "key": alert.key]
            // Wall-clock components without a zone: an item that follows the watch's zone rings at its time after travel too.
            let when = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: alert.at)
            let trigger = UNCalendarNotificationTrigger(dateMatching: when, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: alert.id, content: content, trigger: trigger))
        }
    }

    /// A countdown that rings as a notification. False when notifications aren't allowed.
    static func timer(seconds: Int, label: String?) async -> Bool {
        guard await allowed() else { return false }
        let content = UNMutableNotificationContent()
        content.title = label ?? "Timer"
        content.body = "Time's up"
        content.sound = .default
        content.threadIdentifier = "timers"
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(seconds), repeats: false)
        do {
            try await center.add(UNNotificationRequest(identifier: timerPrefix + UUID().uuidString, content: content, trigger: trigger))
            return true
        } catch {
            return false
        }
    }

    /// Done or Snooze on a reminder's notification. One that is no longer current (the item moved on, was edited or
    /// completed) does nothing.
    static func handle(action: String, entryId: String, key: String) async {
        let book = Services.shared.reminders
        guard let entry = await book.entry(entryId), AlertPlan.current(entry, key: key) else { return }
        switch action {
        case doneAction:
            await book.complete(entryId, occurrence: LocalDateTime(key))
        case snoozeAction:
            await book.snooze(entryId, minutes: 10, fromNow: true)
        default:
            return
        }
        await reschedule()
    }
}

/// Shows reminders while WatchGPT is open too, and takes Done and Snooze from their notifications.
final class NotificationHandler: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationHandler()

    func userNotificationCenter(
        _: UNUserNotificationCenter,
        willPresent _: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        guard let entryId = info["entry"] as? String, let key = info["key"] as? String else {
            completionHandler()
            return
        }
        let action = response.actionIdentifier
        if action == UNNotificationDefaultActionIdentifier {
            Task { @MainActor in AppModel.shared.showReminders = true }
            completionHandler()
            return
        }
        Task {
            await Notifier.handle(action: action, entryId: entryId, key: key)
            completionHandler()
        }
    }
}
