package tr.okul.kiosk

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import tr.okul.kiosk.pdf.PdfLibrary
import tr.okul.kiosk.policy.PolicyManager
import tr.okul.kiosk.sync.PolicySync

/** Uzaktan gelen ve arayüzün tepki vermesi gereken olaylar. */
sealed interface KioskEvent {
    data class Message(val text: String) : KioskEvent
    data class Maintenance(val minutes: Int) : KioskEvent
    data class OpenBook(val bookId: String) : KioskEvent
}

/**
 * Uygulama tek süreçtir: DPC + ana ekran + PDF modülü aynı APK'dadır.
 * Böylece lock-task beyaz listesinde kendimizi tek paket olarak tutarız ve
 * öğrenci hiçbir ara ekrana düşmez.
 */
class KioskApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var policyManager: PolicyManager
        private set
    lateinit var library: PdfLibrary
        private set
    lateinit var sync: PolicySync
        private set

    /** Olaylar, ön plandaki ilk dinleyiciye bir kez teslim edilir (kaçırılmaz). */
    private val eventChannel = Channel<KioskEvent>(Channel.BUFFERED)
    val events: Flow<KioskEvent> = eventChannel.receiveAsFlow()
    fun emit(event: KioskEvent) { eventChannel.trySend(event) }

    /** Durum raporu için: öğrencinin en son açtığı uygulama. */
    @Volatile var lastLaunchedPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        policyManager = PolicyManager(this)
        library = PdfLibrary(this)
        sync = PolicySync(this, policyManager, library, appScope)

        if (policyManager.isDeviceOwner) {
            policyManager.applyBaseline()
            sync.start()
        }
    }

    /** Kurulum bittiğinde (KioskAdminReceiver / PolicyComplianceActivity) çağrılır. */
    fun onProvisioned() {
        policyManager.applyBaseline()
        policyManager.apply(policyManager.current, force = true)
        sync.start()
    }

    companion object {
        fun from(ctx: Context): KioskApp = ctx.applicationContext as KioskApp
    }
}
