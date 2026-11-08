package tr.okul.kiosk.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tr.okul.kiosk.KioskApp
import tr.okul.kiosk.policy.PinHash
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Yönetici PIN'i cihazda düz metin olarak durmaz. Sunucu (setKioskPin fonksiyonu) PBKDF2-SHA256
 * ile tuz + özet üretir; cihaz yalnızca özeti bilir ve doğrulamayı ÇEVRİMDIŞI yapar.
 * 5 hatalı denemede 5 dakika kilit.
 */
class PinVerifier(ctx: Context) {

    sealed interface Result {
        data object Ok : Result
        data object Wrong : Result
        data object NotConfigured : Result
        data class LockedOut(val secondsLeft: Long) : Result
    }

    private val prefs = ctx.getSharedPreferences("pin_guard", Context.MODE_PRIVATE)

    /** Ağır işlem (~0,3–1 sn); ana iş parçacığında çağırmayın. */
    fun verify(pin: String, ref: PinHash?): Result {
        val now = SystemClock.elapsedRealtime()
        val lockedUntil = prefs.getLong("lockedUntil", 0)
        if (lockedUntil > now) return Result.LockedOut((lockedUntil - now) / 1000)
        if (ref == null) return Result.NotConfigured

        val salt = Base64.decode(ref.saltB64, Base64.DEFAULT)
        val expected = Base64.decode(ref.hashB64, Base64.DEFAULT)
        // PIN yalnızca rakamdır (sunucu zorunlu tutar); bu yüzden karakter kodlaması farkı oluşmaz.
        val spec = PBEKeySpec(pin.toCharArray(), salt, ref.iterations, expected.size * 8)
        val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()

        return if (MessageDigest.isEqual(actual, expected)) {
            prefs.edit().putInt("fails", 0).apply()
            Result.Ok
        } else {
            val fails = prefs.getInt("fails", 0) + 1
            val edit = prefs.edit().putInt("fails", fails)
            if (fails >= MAX_FAILS) edit.putLong("lockedUntil", now + LOCKOUT_MS).putInt("fails", 0)
            edit.apply()
            Result.Wrong
        }
    }

    private companion object {
        const val MAX_FAILS = 5
        const val LOCKOUT_MS = 5 * 60_000L
    }
}

/**
 * Yönetici bakım oturumu: kiosk kilidi geçici olarak kalkar, Ayarlar açılır,
 * süre dolunca otomatik olarak yeniden kilitlenir (öğretmen kapatmayı unutsa bile).
 */
object AdminSession {
    @Volatile private var expiresAt = 0L
    private var timer: Job? = null

    val isActive: Boolean get() = SystemClock.elapsedRealtime() < expiresAt

    /** Kilit modundan yalnızca kilit modundaki aktivite çıkabilir; bu yüzden Activity alır. */
    fun begin(activity: Activity, minutes: Int) {
        val app = KioskApp.from(activity)
        expiresAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        app.policyManager.enterMaintenance()
        runCatching { activity.stopLockTask() }

        timer?.cancel()
        timer = app.appScope.launch {
            delay(minutes * 60_000L)
            end(app)
        }
        activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun end(ctx: Context) {
        val app = KioskApp.from(ctx)
        expiresAt = 0
        timer?.cancel()
        app.policyManager.exitMaintenance()
        // Launcher öne gelince onResume'da tekrar startLockTask() çağrılır.
        app.startActivity(
            Intent(app, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
