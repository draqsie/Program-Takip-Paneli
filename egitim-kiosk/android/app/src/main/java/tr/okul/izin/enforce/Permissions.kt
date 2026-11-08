package tr.okul.izin.enforce

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/** Uygulamanın çalışması için öğrencinin Ayarlar'dan vermesi gereken izinler. */
object Permissions {
    enum class Kind(val title: String, val why: String) {
        USAGE("Kullanım erişimi", "Okul saatinde hangi uygulamanın açıldığını görmek için."),
        OVERLAY("Diğer uygulamaların üzerinde göster", "İzin verilmeyen uygulamanın üstüne \"İzin iste\" ekranı koymak için."),
        NOTIFICATIONS("Bildirimler", "Öğretmenin onay/ret kararını size iletmek için."),
    }

    fun missing(ctx: Context): List<Kind> = buildList {
        if (!hasUsageAccess(ctx)) add(Kind.USAGE)
        if (!Settings.canDrawOverlays(ctx)) add(Kind.OVERLAY)
        if (!ctx.getSystemService(NotificationManager::class.java)!!.areNotificationsEnabled()) add(Kind.NOTIFICATIONS)
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)!!
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(ctx: Context, kind: Kind): Intent = when (kind) {
        Kind.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Kind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        Kind.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
