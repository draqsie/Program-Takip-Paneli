package tr.okul.kiosk.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.IntentCompat
import tr.okul.kiosk.KioskApp
import tr.okul.kiosk.launcher.LauncherActivity

/**
 * DPC'nin kalbi. Sistem bu bileşeni "device owner" olarak tanır; tüm
 * DevicePolicyManager çağrılarında `admin` parametresi bu bileşendir.
 *
 * Geliştirme:   adb shell dpm set-device-owner tr.okul.kiosk/.admin.KioskAdminReceiver
 * Üretim:       Fabrika ayarlı cihazda karşılama ekranına 6 kez dokun → QR okut
 *               (bkz. provisioning/qr-payload.json)
 */
class KioskAdminReceiver : DeviceAdminReceiver() {

    /** Android 11 ve öncesinde kurulum bu yayınla biter (12+'da PolicyComplianceActivity de çalışır). */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val extras = IntentCompat.getParcelableExtra(
            intent,
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            PersistableBundle::class.java,
        )
        EnrollmentStore.saveFromProvisioning(context, extras)
        KioskApp.from(context).onProvisioned()

        // Device owner uygulamaları arka plan aktivite başlatma kısıtından muaftır.
        context.startActivity(
            Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx.applicationContext, KioskAdminReceiver::class.java)
    }
}
