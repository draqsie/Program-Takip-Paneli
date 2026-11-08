package tr.okul.izin.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.okul.izin.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GitHub Releases'tan kendi kendini güncelleme.
 *
 * GitHub Actions her sürümde iki dosya yayımlar: `okul-izin.apk` ve `version.json`
 * ({"versionCode":12,"versionName":"1.2.0","sha256":"…","apk":"okul-izin.apk"}).
 * `…/releases/latest/download/<dosya>` adresi her zaman son sürümü verir. Bu bir API çağrısı değil
 * doğrudan indirme olduğu için, okulun tek IP'sinden yüzlerce tablet sorsa bile oran sınırına takılmaz.
 */
object Updater {
    data class Info(val versionCode: Int, val versionName: String, val sha256: String, val apkUrl: String)

    /** Yeni sürüm varsa bilgisini, yoksa null döner. */
    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        val base = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
        if (base.isEmpty()) return@withContext null
        val o = JSONObject(String(download("$base/version.json"), Charsets.UTF_8))
        val info = Info(
            versionCode = o.getInt("versionCode"),
            versionName = o.getString("versionName"),
            sha256 = o.getString("sha256"),
            apkUrl = "$base/" + o.optString("apk", "okul-izin.apk"),
        )
        if (info.versionCode > BuildConfig.VERSION_CODE) info else null
    }

    /** APK'yı indirir, özetini doğrular ve sistem kurulum ekranını açar (öğrenci "Güncelle"ye basar). */
    suspend fun install(ctx: Context, info: Info) = withContext(Dispatchers.IO) {
        val bytes = download(info.apkUrl)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual.equals(info.sha256, ignoreCase = true)) { "İndirilen dosya bozuk (özet uyuşmuyor)" }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("okul-izin.apk", 0, bytes.size.toLong()).use { out ->
                out.write(bytes)
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true // github.com → objects.githubusercontent.com
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Kurulum sonucu. Sistem onay isterse (yandan yüklenen uygulamada her zaman ister) onay ekranını açar. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updater", "Güncellendi")
            else -> Log.w("Updater", "Güncelleme başarısız ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
