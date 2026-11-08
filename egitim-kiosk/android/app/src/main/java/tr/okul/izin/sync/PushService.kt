package tr.okul.izin.sync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import tr.okul.izin.App

/**
 * Onay/ret bildirimleri sunucudan "notification" olarak gelir; uygulama arka plandaysa sistem
 * kendisi gösterir. Asıl izin bilgisi Firestore'dan okunur, push yalnızca uyandırma sinyalidir.
 */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        App.from(this).sync.onPush()
    }

    override fun onNewToken(token: String) {
        App.from(this).sync.updateFcmToken(token)
    }
}
