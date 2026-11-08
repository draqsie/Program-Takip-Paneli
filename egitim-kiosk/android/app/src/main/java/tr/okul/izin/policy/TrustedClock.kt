package tr.okul.izin.policy

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Öğrenci cihaz saatini ileri/geri alarak "okul saati bitti" dedirtemesin diye sunucu saatine
 * bağlı saat. Sunucudan alınan son zaman, açılıştan beri geçen monoton süreyle (elapsedRealtime)
 * ilerletilir; bu süre kullanıcı tarafından değiştirilemez. Cihaz yeniden başlarsa bir sonraki
 * senkrona kadar son ölçülen sapma (skew) ile duvar saati kullanılır.
 */
class TrustedClock(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("trusted_clock", Context.MODE_PRIVATE)

    fun onServerTime(serverMs: Long) {
        prefs.edit()
            .putLong("serverMs", serverMs)
            .putLong("elapsedMs", SystemClock.elapsedRealtime())
            .putInt("boot", bootCount())
            .putLong("skewMs", System.currentTimeMillis() - serverMs)
            .apply()
    }

    fun now(): Long {
        val serverMs = prefs.getLong("serverMs", 0)
        val sameBoot = serverMs > 0 && prefs.getInt("boot", -1) == bootCount()
        return if (sameBoot) {
            serverMs + (SystemClock.elapsedRealtime() - prefs.getLong("elapsedMs", 0))
        } else {
            System.currentTimeMillis() - prefs.getLong("skewMs", 0)
        }
    }

    /** Cihaz saatinin sunucudan sapması (sn). Panel 5 dakikadan büyük sapmayı uyarı olarak gösterir. */
    fun skewSeconds(): Long = (System.currentTimeMillis() - now()) / 1000

    private fun bootCount(): Int =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
