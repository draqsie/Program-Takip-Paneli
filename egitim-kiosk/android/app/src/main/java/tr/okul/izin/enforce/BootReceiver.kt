package tr.okul.izin.enforce

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cihaz açılınca ve uygulama güncellenince izleme servisini yeniden başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Süreç bu yayınla ayağa kalkar; kayıtlıysa App.onCreate servisi zaten başlatmıştır.
            if (tr.okul.izin.App.from(context).sync.isEnrolled) EnforcementService.start(context)
        }
    }
}
