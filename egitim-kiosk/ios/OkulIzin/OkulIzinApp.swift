import SwiftUI
import UIKit
import UserNotifications
import FirebaseCore
import FirebaseMessaging

@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()
    @Published var enrolled = UserDefaults.standard.string(forKey: "studentId") != nil
    /// Engel ekranından gelen ve öğrencinin göndermesini bekleyen istek (bildirime dokununca dolar).
    @Published var requestKey: String?
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        FirebaseApp.configure()
        UNUserNotificationCenter.current().delegate = self
        Messaging.messaging().delegate = self
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken else { return }
        Task { @MainActor in SyncService.shared.updateFcmToken(token) }
    }

    /// Onay/ret push'u "content-available" taşır: uygulama arka planda kısa süre uyanıp izni uygular
    /// (iOS bunu garanti etmez; öğrenci bildirime dokunduğunda da uygulanır).
    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        await SyncService.shared.refresh()
        return .newData
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    /// ShieldAction uzantısının gösterdiği "İzin isteği hazır" bildirimine dokunuldu.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if let key = response.notification.request.content.userInfo["appKey"] as? String {
            await MainActor.run { AppState.shared.requestKey = key }
        }
        await SyncService.shared.refresh()
    }
}

@main
struct OkulIzinApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var state = AppState.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(state)
        }
        .onChange(of: phase) { newPhase in
            guard newPhase == .active else { return }
            // Bildirime dokunmadan uygulamayı açan öğrenci için: gönderilmemiş ilk isteği göster.
            if state.requestKey == nil { state.requestKey = SharedStore.unsentRequests.first }
            Task { await SyncService.shared.refresh() }
        }
    }
}
