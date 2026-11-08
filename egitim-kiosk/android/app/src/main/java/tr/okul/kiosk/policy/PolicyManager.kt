package tr.okul.kiosk.policy

import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tr.okul.kiosk.BuildConfig
import tr.okul.kiosk.admin.KioskAdminReceiver
import tr.okul.kiosk.launcher.LauncherActivity

/**
 * Tüm DevicePolicyManager çağrıları burada toplanır.
 *
 * İki katmanlı koruma kullanılır:
 *   1) Lock Task (kiosk) modu   → setLockTaskPackages: yalnızca listedeki paketler ön plana gelebilir.
 *   2) Uygulama gizleme        → setApplicationHidden: listede olmayan uygulamalar cihazda "yok" gibi
 *                                 davranır (ikon yok, intent ile açılamaz, çalışıyorsa öldürülür).
 * Biri bir OEM hatası yüzünden delinirse diğeri tutar.
 *
 * DPM politikaları kalıcıdır (yeniden başlatmada silinmez); ağ olmasa bile cihaz kilitli kalır.
 */
class PolicyManager(private val ctx: Context) {

    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)!!
    private val admin: ComponentName = KioskAdminReceiver.component(ctx)
    private val prefs = ctx.getSharedPreferences("kiosk_policy", Context.MODE_PRIVATE)

    private val _policy = MutableStateFlow(loadCached())
    val policy: StateFlow<KioskPolicy> = _policy.asStateFlow()
    val current: KioskPolicy get() = _policy.value

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(ctx.packageName)

    // ------------------------------------------------------------------ temel sertleştirme

    /** Politikadan bağımsız, cihaz kurulduğu andan itibaren hep geçerli kurallar. */
    fun applyBaseline() {
        if (!isDeviceOwner) return

        // 1) Ana ekran tuşu her zaman bizim launcher'ımızı açsın (başka launcher seçtirilemez).
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, LauncherActivity::class.java))

        // 2) Kaçış yollarını kapatan kullanıcı kısıtları.
        BASE_RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
        // USB hata ayıklamayı geliştirmede açık bırak, yoksa adb ile cihaza erişemezsiniz.
        if (!BuildConfig.DEBUG) dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)

        // 3) Kendimizi kaldırılamaz yap; kilit ekranı ve durum çubuğunu kapat.
        dpm.setUninstallBlocked(admin, ctx.packageName, true)
        dpm.setKeyguardDisabled(admin, true)      // ekran şifresi yoksa kilit ekranı (ve oradaki kısayollar) gelmez
        dpm.setStatusBarDisabled(admin, true)     // bildirim paneli / hızlı ayarlar açılmaz

        // 4) Üçüncü taraf erişilebilirlik servislerine izin verme (ekran üstü menüler bilinen bir kiosk
        //    kaçış yoludur). Boş liste = yalnızca sistem servisleri. Engelli öğrenci varsa ilgili servisi ekleyin.
        dpm.setPermittedAccessibilityServices(admin, emptyList())

        // 5) Sistem güncellemeleri ders saatinde değil, gece 02:00–05:00 arasında.
        dpm.setSystemUpdatePolicy(admin, SystemUpdatePolicy.createWindowedInstallPolicy(120, 300))

        // 6) Kendi izinlerimizi öğrenciye sormadan ver.
        if (Build.VERSION.SDK_INT >= 33) {
            dpm.setPermissionGrantState(
                admin, ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    /**
     * Fabrika Sıfırlama Koruması: cihaz kurtarma menüsünden (donanım tuşlarıyla) sıfırlansa bile
     * yalnızca okulun Google hesabıyla yeniden kurulabilir. accountIds, Google hesabının sayısal
     * kimliğidir (e-posta değil). Android 11+.
     */
    fun applyFactoryResetProtection(accountIds: List<String>) {
        if (!isDeviceOwner || Build.VERSION.SDK_INT < 30 || accountIds.isEmpty()) return
        dpm.setFactoryResetProtectionPolicy(
            admin,
            FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accountIds)
                .setFactoryResetProtectionEnabled(true)
                .build(),
        )
    }

    // ------------------------------------------------------------------ canlı politika

    /** Sunucudan gelen politikayı uygular. Aynı politika tekrar gelirse hiçbir şey yapmaz. */
    fun apply(policy: KioskPolicy, force: Boolean = false) {
        if (!isDeviceOwner) return
        if (!force && policy == current) return

        val allowed = allowedPackages(policy)

        // Kiosk beyaz listesi. Listeden çıkarılan bir uygulama kilit modunda çalışıyorsa sistem onu kapatır.
        dpm.setLockTaskPackages(admin, allowed.toTypedArray())

        // Kilit modundayken görünen sistem öğeleri:
        //   HOME           → ana ekran tuşu (bizim launcher'a döner)
        //   GLOBAL_ACTIONS → güç menüsü (kapat/yeniden başlat)
        //   SYSTEM_INFO    → saat/pil göstergesi
        // NOTIFICATIONS ve OVERVIEW (son uygulamalar) bilinçli olarak kapalı.
        dpm.setLockTaskFeatures(
            admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
        )

        syncHiddenApps(allowed)
        allowed.forEach { pkg -> runCatching { dpm.setUninstallBlocked(admin, pkg, true) } }

        dpm.setCameraDisabled(admin, policy.cameraDisabled)
        dpm.setScreenCaptureDisabled(admin, policy.screenCaptureDisabled)

        _policy.value = policy
        prefs.edit().putString(KEY_POLICY, policy.toJson()).apply()
        Log.i(TAG, "Politika uygulandı v${policy.version} mod=${policy.mode} izinli=${allowed.size}")
    }

    fun allowedPackages(p: KioskPolicy): Set<String> = when (p.mode) {
        KioskMode.NORMAL -> p.allowedApps + ctx.packageName
        KioskMode.SINAV, KioskMode.KILITLI -> setOf(ctx.packageName)
    }

    fun isInstalled(pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /**
     * Başlatıcı ikonu olan ve izinli olmayan her uygulamayı gizler; artık izinli olanları geri açar.
     * Gizlediğimiz paketleri kaydederiz çünkü gizli paketler sorgularda görünmez.
     */
    private fun syncHiddenApps(allowed: Set<String>) {
        val hiddenByUs = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toMutableSet()

        val toUnhide = hiddenByUs.filter { it in allowed }
        toUnhide.forEach { pkg -> if (dpm.setApplicationHidden(admin, pkg, false)) hiddenByUs.remove(pkg) }

        val protected = protectedPackages()
        val toHide = launchablePackages().filter { it !in allowed && it !in protected }
        toHide.forEach { pkg -> if (dpm.setApplicationHidden(admin, pkg, true)) hiddenByUs.add(pkg) }

        prefs.edit().putStringSet(KEY_HIDDEN, hiddenByUs).apply()
    }

    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /** Asla gizlenmemesi gerekenler: kendimiz ve klavyeler (gizlenirse yazı yazılamaz). */
    private fun protectedPackages(): Set<String> {
        val imm = ctx.getSystemService(InputMethodManager::class.java)!!
        val keyboards = imm.enabledInputMethodList.map { it.packageName }
        return keyboards.toSet() + ctx.packageName
    }

    // ------------------------------------------------------------------ yönetici bakım modu

    /** PIN doğrulandıktan sonra: Ayarlar'ı ve durum çubuğunu geçici olarak açar. */
    fun enterMaintenance() {
        if (!isDeviceOwner) return
        MAINTENANCE_RELAXED.forEach { dpm.clearUserRestriction(admin, it) }
        dpm.setStatusBarDisabled(admin, false)
        dpm.setApplicationHidden(admin, SETTINGS_PKG, false)
    }

    /** Bakım bitince her şeyi geri kilitler (Ayarlar yeniden gizlenir ve kapanır). */
    fun exitMaintenance() {
        if (!isDeviceOwner) return
        MAINTENANCE_RELAXED.forEach { dpm.addUserRestriction(admin, it) }
        dpm.setStatusBarDisabled(admin, true)
        apply(current, force = true)
    }

    fun reboot() { if (isDeviceOwner) dpm.reboot(admin) }

    fun isLockTaskPermitted(): Boolean = dpm.isLockTaskPermitted(ctx.packageName)

    private fun loadCached(): KioskPolicy =
        prefs.getString(KEY_POLICY, null)?.let(KioskPolicy::fromJson) ?: KioskPolicy()

    companion object {
        private const val TAG = "PolicyManager"
        private const val KEY_POLICY = "policy_json"
        private const val KEY_HIDDEN = "hidden_by_us"
        /** AOSP ve çoğu OEM'de (Samsung dahil) Ayarlar paketi budur; hedef cihazda doğrulayın. */
        const val SETTINGS_PKG = "com.android.settings"

        private val BASE_RESTRICTIONS: List<String> = buildList {
            add(UserManager.DISALLOW_FACTORY_RESET)          // Ayarlar'dan sıfırlama
            add(UserManager.DISALLOW_SAFE_BOOT)              // güvenli mod ile DPC'yi atlatma
            add(UserManager.DISALLOW_ADD_USER)               // misafir/ikinci kullanıcı
            add(UserManager.DISALLOW_MODIFY_ACCOUNTS)        // Google hesabı ekleme
            add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)// APK yükleme
            add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)   // SD kart / USB bellek
            add(UserManager.DISALLOW_USB_FILE_TRANSFER)
            add(UserManager.DISALLOW_BLUETOOTH_SHARING)
            add(UserManager.DISALLOW_CONFIG_CREDENTIALS)
            add(UserManager.DISALLOW_CONFIG_VPN)             // VPN ile ağ filtresini aşma
            add(UserManager.DISALLOW_CONFIG_TETHERING)
            add(UserManager.DISALLOW_CREATE_WINDOWS)         // diğer uygulamaların üstüne çizim (overlay)
            add(UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS)
            add(UserManager.DISALLOW_AIRPLANE_MODE)          // uçak modu ile merkezden kopma
            add(UserManager.DISALLOW_CONFIG_WIFI)
            add(UserManager.DISALLOW_CONFIG_DATE_TIME)       // saat değiştirerek süre/sınav hilesi
            if (Build.VERSION.SDK_INT >= 29) {
                add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
                add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
        }

        /** Bakım modunda yöneticinin ihtiyaç duyabileceği, geçici olarak gevşetilen kısıtlar. */
        private val MAINTENANCE_RELAXED = listOf(
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        )
    }
}
