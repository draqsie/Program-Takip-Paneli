package tr.okul.izin.enforce

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tr.okul.izin.App

/**
 * Okul saatinde ön plandaki uygulamayı izleyen ön plan servisi.
 *  - Okul saati dışında / ekran kapalıyken neredeyse hiç iş yapmaz (uzun aralıklarla uyanır).
 *  - Okul saatinde ~0,6 sn'de bir ön plan uygulamasına bakar; izinsizse engel ekranını koyar.
 */
class EnforcementService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var detector: ForegroundAppDetector
    private lateinit var overlay: BlockOverlay
    private var exemptions: Set<String> = emptySet()
    private var exemptionsAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        detector = ForegroundAppDetector(this)
        overlay = BlockOverlay(this) { pkg, label -> App.from(this).requestAccess(pkg, label) }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        val app = App.from(this)
        val power = getSystemService(PowerManager::class.java)!!
        while (scope.isActive) {
            val now = app.clock.now()
            val policy = app.policy.value

            if (!policy.isRestrictedNow(now)) {
                overlay.hide()
                delay(30_000)
                continue
            }
            if (!power.isInteractive) {
                delay(2_000)
                continue
            }
            if (!Permissions.hasUsageAccess(this) || !Settings.canDrawOverlays(this)) {
                // Uygulayamıyoruz; durum raporu bunu panele "İzin eksik" olarak iletir.
                overlay.hide()
                delay(5_000)
                continue
            }

            if (System.currentTimeMillis() - exemptionsAt > 10 * 60_000) {
                exemptions = Exemptions.compute(this)
                exemptionsAt = System.currentTimeMillis()
            }

            val fg = runCatching { detector.poll() }.onFailure { Log.w(TAG, "poll", it) }.getOrNull()
            if (fg != null && fg !in exemptions && !policy.isAllowed(fg, now)) {
                overlay.show(fg, labelOf(fg), pending = fg in app.pendingRequests.value)
            } else {
                overlay.hide()
            }
            delay(600)
        }
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Okul modu", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Okul İzin çalışıyor")
            .setContentText("Okul saatlerinde yalnızca izin verilen uygulamalar açılır.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "Enforcement"
        private const val CHANNEL = "okul_modu"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            // Arka plandan ön plan servisi başlatmak Android 12+'da bazı durumlarda reddedilir;
            // açılış, güncelleme ve uygulama ekranından başlatma her zaman serbesttir.
            runCatching { ctx.startForegroundService(Intent(ctx, EnforcementService::class.java)) }
                .onFailure { Log.w(TAG, "Servis başlatılamadı", it) }
        }
    }
}
