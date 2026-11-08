import ManagedSettings
import UserNotifications

/// Engel ekranındaki düğmelere basılınca çalışır. Uzantı ana uygulamayı doğrudan açamaz ve
/// Screen Time uzantılarının ağ erişimi kısıtlıdır (güvenilmez); bu yüzden isteği App Group'a
/// yazar ve öğrenciye bir bildirim gösterir.
/// Bildirime dokununca ana uygulama açılır ve isteği sunucuya gönderir.
class OkulShieldAction: ShieldActionDelegate {

    override func handle(action: ShieldAction, for application: ApplicationToken,
                         completionHandler: @escaping (ShieldActionResponse) -> Void) {
        switch action {
        case .primaryButtonPressed:
            let key = SharedStore.remember(application)
            var unsent = SharedStore.unsentRequests
            if !unsent.contains(key) { unsent.append(key) }
            SharedStore.unsentRequests = unsent

            let content = UNMutableNotificationContent()
            content.title = "İzin isteği hazır"
            content.body = "Öğretmenine göndermek için dokun."
            content.userInfo = ["appKey": key]
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: key, content: content, trigger: nil))
            completionHandler(.defer)
        case .secondaryButtonPressed:
            completionHandler(.close)
        @unknown default:
            completionHandler(.close)
        }
    }
}
