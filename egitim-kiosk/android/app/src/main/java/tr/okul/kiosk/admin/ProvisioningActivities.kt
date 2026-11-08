package tr.okul.kiosk.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import androidx.core.content.IntentCompat
import tr.okul.kiosk.KioskApp

/**
 * Android 12+ kurulum akışı:
 *   1) GET_PROVISIONING_MODE  → "tam yönetilen cihaz (fully managed) olarak kur" deriz
 *   2) ADMIN_POLICY_COMPLIANCE → kurulumun son ekranı; ilk politikaları burada uygularız
 * Bu iki aktivite yoksa Android 12+ cihazlarda QR ile kurulum başarısız olur.
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        setResult(RESULT_OK, result)
        finish()
    }
}

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = IntentCompat.getParcelableExtra(
            intent,
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            PersistableBundle::class.java,
        )
        EnrollmentStore.saveFromProvisioning(this, extras)
        KioskApp.from(this).onProvisioned()
        setResult(RESULT_OK)
        finish()
    }
}
