package tr.okul.kiosk.sync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import tr.okul.kiosk.KioskApp

/**
 * Yalnızca "veri" mesajları kullanılır (bildirim göstermeyiz; durum çubuğu zaten kapalı).
 * Mesajın içeriği güvenilmez kabul edilir: sadece "senkronize ol" sinyalidir, asıl talimat
 * yetki kurallarıyla korunan Firestore'dan okunur.
 */
class KioskMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        KioskApp.from(this).sync.onPush(message.data)
    }

    override fun onNewToken(token: String) {
        KioskApp.from(this).sync.updateFcmToken(token)
    }
}
