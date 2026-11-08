package tr.okul.kiosk.admin

import android.content.Context
import android.os.PersistableBundle
import tr.okul.kiosk.BuildConfig

/** QR koddaki PROVISIONING_ADMIN_EXTRAS_BUNDLE içinden gelen okul kimliği + tek kullanımlık kayıt anahtarı. */
data class Enrollment(val schoolId: String, val token: String)

object EnrollmentStore {
    private const val PREFS = "enrollment"

    fun saveFromProvisioning(ctx: Context, extras: PersistableBundle?) {
        val school = extras?.getString("schoolId") ?: return
        val token = extras.getString("enrollToken") ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("schoolId", school)
            .putString("token", token)
            .apply()
    }

    fun load(ctx: Context): Enrollment? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val school = p.getString("schoolId", null)
        val token = p.getString("token", null)
        if (school != null && token != null) return Enrollment(school, token)
        // adb ile kurulan geliştirme cihazları için
        if (BuildConfig.DEBUG && BuildConfig.DEV_SCHOOL_ID.isNotEmpty()) {
            return Enrollment(BuildConfig.DEV_SCHOOL_ID, BuildConfig.DEV_ENROLL_TOKEN)
        }
        return null
    }

    /** Kayıt tamamlanınca anahtarı sil: tek kullanımlıktır, cihazda durmasına gerek yok. */
    fun clearToken(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("token").apply()
    }
}
