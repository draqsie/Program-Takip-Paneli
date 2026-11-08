package tr.okul.izin

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tr.okul.izin.enforce.EnforcementService
import tr.okul.izin.policy.AccessPolicy
import tr.okul.izin.policy.TrustedClock
import tr.okul.izin.sync.StudentSync

class App : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var clock: TrustedClock
        private set
    lateinit var sync: StudentSync
        private set

    private val prefs by lazy { getSharedPreferences("policy", Context.MODE_PRIVATE) }

    /** Son bilinen kural; çevrimdışıyken de geçerli kalsın diye diske yazılır. */
    private val _policy = MutableStateFlow(AccessPolicy())
    val policy: StateFlow<AccessPolicy> = _policy.asStateFlow()

    /** Öğrencinin bekleyen isteklerinin paket adları (engel ekranında "bekleniyor" göstermek için). */
    val pendingRequests = MutableStateFlow<Set<String>>(emptySet())

    override fun onCreate() {
        super.onCreate()
        _policy.value = AccessPolicy.fromJson(prefs.getString("json", null))
        clock = TrustedClock(this)
        sync = StudentSync(this, scope)
        if (sync.isEnrolled) {
            sync.start()
            EnforcementService.start(this)
        }
    }

    fun updatePolicy(p: AccessPolicy) {
        if (p == _policy.value) return
        _policy.value = p
        prefs.edit().putString("json", p.toJson()).apply()
    }

    fun requestAccess(pkg: String, label: String) = sync.requestAccess(pkg, label)

    companion object {
        fun from(ctx: Context) = ctx.applicationContext as App
    }
}
