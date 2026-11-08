package tr.okul.izin.enforce

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * Okul saatinde de asla engellenmeyen paketler: ana ekran (öğrenci engelden kaçabilsin),
 * klavyeler, sistem arayüzü, izin diyalogları ve acil arama. Ayarlar ve uygulama kaldırma
 * ekranı bilerek muaf DEĞİLDİR: okul saatinde izni kapatmak / uygulamayı silmek engellenir.
 */
object Exemptions {
    private val ALWAYS = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.phone",
        "com.android.emergency",
    )

    fun compute(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        val keyboards = ctx.getSystemService(InputMethodManager::class.java)!!.enabledInputMethodList.map { it.packageName }
        val dialer = runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        return ALWAYS + homes + keyboards + listOfNotNull(dialer, ctx.packageName)
    }
}
