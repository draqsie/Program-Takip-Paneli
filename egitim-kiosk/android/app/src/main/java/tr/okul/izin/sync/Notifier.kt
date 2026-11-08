package tr.okul.izin.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import tr.okul.izin.ui.MainActivity

/** Öğretmenin onay/ret kararını yerel bildirim olarak gösterir (sunucudan push gerekmez). */
object Notifier {
    private const val CHANNEL = "kararlar"

    fun decision(ctx: Context, requestId: String, appLabel: String, approved: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "İzin kararları", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(if (approved) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_delete)
            .setContentTitle(if (approved) "İzin verildi" else "İzin verilmedi")
            .setContentText(if (approved) "$appLabel artık açılabilir." else "$appLabel için isteğin reddedildi.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(requestId.hashCode(), n)
    }
}
