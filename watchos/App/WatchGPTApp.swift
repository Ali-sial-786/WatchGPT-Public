import SwiftUI
import UserNotifications
import WatchKit

/// WatchGPT on the Apple Watch, on its own: no iPhone app. The watch signs in to ChatGPT, talks to it over its own
/// connection, and keeps reminders, notes and memory itself.
@main
struct WatchGPTApp: App {
    @WKApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.conversation)
                .onOpenURL { model.open($0) }
                .task { await model.refresh() }
        }
        .onChange(of: phase) { _, now in
            if now == .active { Task { await Notifier.reschedule() } }
        }
    }
}

final class AppDelegate: NSObject, WKApplicationDelegate {
    func applicationDidFinishLaunching() {
        UNUserNotificationCenter.current().delegate = NotificationHandler.shared
        Notifier.registerCategories()
        Self.scheduleRefresh()
    }

    /// Every few hours, the reminder alerts are planned again, so a series keeps ringing without the app being opened.
    func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
        for task in backgroundTasks {
            if let refresh = task as? WKApplicationRefreshBackgroundTask {
                Task {
                    await Notifier.reschedule()
                    Self.scheduleRefresh()
                    refresh.setTaskCompletedWithSnapshot(false)
                }
            } else {
                task.setTaskCompletedWithSnapshot(false)
            }
        }
    }

    static func scheduleRefresh() {
        WKApplication.shared().scheduleBackgroundRefresh(withPreferredDate: Date().addingTimeInterval(4 * 3600), userInfo: nil) { _ in }
    }
}
