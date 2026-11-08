package tr.okul.kiosk.policy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Device owner, PackageInstaller oturumu ile APK'yı kullanıcıya hiçbir onay ekranı göstermeden kurar.
 * Beyaz listeye eklenen ama cihazda olmayan uygulamalar ve kiosk uygulamasının kendi güncellemesi
 * bu yolla dağıtılır (Play Store gizli kalır).
 *
 * Not: Lisanslı Play uygulamaları (ör. ücretli test uygulamaları) için Managed Google Play / Android
 * Management API gerekir; kendi barındırdığınız APK'lar için bu sınıf yeterlidir.
 */
object AppInstaller {
    private const val TAG = "AppInstaller"

    suspend fun installFromUrl(ctx: Context, apkUrl: String, expectedSha256: String) = withContext(Dispatchers.IO) {
        val apk = File(ctx.cacheDir, "install-${System.currentTimeMillis()}.apk")
        try {
            download(apkUrl, apk)
            val actual = apk.sha256Hex()
            require(actual.equals(expectedSha256, ignoreCase = true)) { "APK özeti uyuşmuyor: $actual" }

            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    ctx, sessionId,
                    Intent(ctx, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // sistem sonucu extra olarak ekler
                )
                session.commit(callback.intentSender)
            }
        } finally {
            apk.delete()
        }
    }

    private fun download(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "İndirme hatası: HTTP ${conn.responseCode}" }
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i("AppInstaller", "Kuruldu: $pkg")
            // Yeni kurulan paket, politika yeniden uygulanınca lock-task listesine ve görünür hale gelir.
            val app = tr.okul.kiosk.KioskApp.from(context)
            app.policyManager.apply(app.policyManager.current, force = true)
        } else {
            Log.w("AppInstaller", "Kurulum başarısız ($status): $pkg $msg")
        }
    }
}

fun File.sha256Hex(): String {
    val md = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
