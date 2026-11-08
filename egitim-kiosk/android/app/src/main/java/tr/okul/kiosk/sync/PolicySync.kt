package tr.okul.kiosk.sync

import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import tr.okul.kiosk.BuildConfig
import tr.okul.kiosk.KioskApp
import tr.okul.kiosk.KioskEvent
import tr.okul.kiosk.admin.EnrollmentStore
import tr.okul.kiosk.launcher.LauncherActivity
import tr.okul.kiosk.pdf.PdfLibrary
import tr.okul.kiosk.policy.AppInstaller
import tr.okul.kiosk.policy.KioskMode
import tr.okul.kiosk.policy.KioskPolicy
import tr.okul.kiosk.policy.PolicyManager
import java.io.File

/**
 * Cihaz ↔ sunucu senkronizasyonu.
 *
 *  - Firestore anlık dinleyicileri (snapshot listener): launcher hep ön planda olduğu için bağlantı açık
 *    kalır; öğretmen panelde bir kutucuğu işaretlediğinde değişiklik ~1 sn içinde cihaza düşer.
 *  - FCM (yüksek öncelikli veri mesajı): cihaz uykudaysa/bağlantı koptuysa uyandırma sinyali.
 *  - Firestore'un çevrimdışı önbelleği + DPM'in kalıcı politikaları: ağ yokken cihaz son politikada kalır.
 */
class PolicySync(
    private val app: KioskApp,
    private val pm: PolicyManager,
    private val library: PdfLibrary,
    private val scope: CoroutineScope,
) {
    data class Identity(val schoolId: String, val deviceId: String)

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance("europe-west1")

    private val regs = mutableListOf<ListenerRegistration>()
    private var groupReg: ListenerRegistration? = null
    private var groupId: String? = null
    private var groupResolved = false

    private var configDoc: Map<String, Any?>? = null
    private var groupDoc: Map<String, Any?>? = null
    private var deviceDoc: Map<String, Any?>? = null

    @Volatile var identity: Identity? = null
        private set
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val id = signInWithRetry()
            identity = id
            listen(id)
            heartbeatLoop()
        }
    }

    // ------------------------------------------------------------------ kimlik

    private suspend fun signInWithRetry(): Identity {
        var wait = 5_000L
        while (true) {
            try {
                return signIn()
            } catch (e: Exception) {
                Log.w(TAG, "Oturum açılamadı, ${wait / 1000} sn sonra tekrar", e)
                delay(wait)
                wait = (wait * 2).coerceAtMost(5 * 60_000L)
            }
        }
    }

    /**
     * İlk açılışta QR'daki tek kullanımlık kayıt anahtarı, enrollDevice fonksiyonunda Firebase
     * "custom token"a çevrilir. Token içindeki claim'ler (role=device, schoolId, deviceId) kalıcıdır;
     * güvenlik kuralları cihazın yalnızca kendi belgelerine erişmesini bu claim'lerle sağlar.
     */
    private suspend fun signIn(): Identity {
        auth.currentUser?.let { user ->
            val claims = user.getIdToken(false).await().claims
            val school = claims["schoolId"] as? String
            val device = claims["deviceId"] as? String
            if (school != null && device != null) return Identity(school, device)
        }
        val enrollment = EnrollmentStore.load(app) ?: error("Kayıt bilgisi yok (QR ile kurulmamış)")
        val result = functions.getHttpsCallable("enrollDevice").call(
            mapOf(
                "schoolId" to enrollment.schoolId,
                "token" to enrollment.token,
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        ).await()
        val data = result.getData() as Map<*, *>
        auth.signInWithCustomToken(data["customToken"] as String).await()
        EnrollmentStore.clearToken(app)
        return Identity(enrollment.schoolId, data["deviceId"] as String)
    }

    // ------------------------------------------------------------------ dinleyiciler

    private fun school(id: Identity) = db.collection("schools").document(id.schoolId)
    private fun deviceRef(id: Identity) = school(id).collection("devices").document(id.deviceId)

    private fun listen(id: Identity) {
        regs += school(id).collection("config").document("kiosk").addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "config", err)
                return@addSnapshotListener
            }
            configDoc = snap?.data.orEmpty()
            recompute()
        }

        regs += deviceRef(id).addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            deviceDoc = snap.data.orEmpty()
            val newGroup = snap.getString("groupId")
            if (!groupResolved || newGroup != groupId) {
                groupResolved = true
                switchGroup(id, newGroup)
            }
            recompute()
        }

        regs += deviceRef(id).collection("commands").whereEqualTo("ackAt", null)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                snap.documentChanges
                    .filter { it.type == DocumentChange.Type.ADDED && !it.document.metadata.hasPendingWrites() }
                    .forEach { handleCommand(it.document) }
            }

        FirebaseMessaging.getInstance().token.addOnSuccessListener { updateFcmToken(it) }
    }

    /** Cihaz başka sınıfa taşındığında eski grubu bırak, yenisini dinle ve FCM konusunu değiştir. */
    private fun switchGroup(id: Identity, newGroup: String?) {
        groupReg?.remove()
        groupId?.let { FirebaseMessaging.getInstance().unsubscribeFromTopic(topic(id, it)) }
        groupId = newGroup
        groupDoc = null
        if (newGroup == null) {
            groupDoc = emptyMap()
            return
        }
        FirebaseMessaging.getInstance().subscribeToTopic(topic(id, newGroup))
        groupReg = school(id).collection("groups").document(newGroup).addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            groupDoc = snap.data.orEmpty()
            recompute()
        }
    }

    private fun topic(id: Identity, group: String) = "grp_${id.schoolId}_$group"

    /** Üç belge de geldiğinde etkin politikayı hesapla ve uygula (dinleyiciler ana iş parçacığında çalışır). */
    private fun recompute() {
        val config = configDoc ?: return
        val group = groupDoc ?: return
        val device = deviceDoc ?: return
        val policy = KioskPolicy.fromFirestore(config, group, device)
        if (policy == pm.current) return

        val previousMode = pm.current.mode
        pm.apply(policy)

        // Sınav/kilit moduna geçildiyse öğrenci başka uygulamada olsa bile ana ekrana getir.
        if (policy.mode != KioskMode.NORMAL && previousMode != policy.mode) bringLauncherToFront()

        scope.launch {
            runCatching { library.sync(policy.books) }.onFailure { Log.w(TAG, "Kitap senkronu", it) }
            runCatching { installMissingApps(policy) }.onFailure { Log.w(TAG, "Uygulama kurulumu", it) }
            reportStatus()
        }
    }

    /** Beyaz listede olup cihazda olmayan uygulamaları okulun katalogundan (apps/{paket}) kur. */
    private suspend fun installMissingApps(policy: KioskPolicy) {
        val id = identity ?: return
        for (pkg in policy.allowedApps) {
            if (pm.isInstalled(pkg)) continue
            val entry = school(id).collection("apps").document(pkg).get().await()
            val url = entry.getString("apkUrl") ?: continue
            val sha = entry.getString("sha256") ?: continue
            AppInstaller.installFromUrl(app, url, sha)
        }
    }

    // ------------------------------------------------------------------ komutlar

    private fun handleCommand(doc: DocumentSnapshot) {
        val type = doc.getString("type") ?: return
        val payload = (doc.get("payload") as? Map<*, *>).orEmpty()
        Log.i(TAG, "Komut: $type")

        fun ack(result: String = "ok") = doc.reference.update(
            mapOf("ackAt" to FieldValue.serverTimestamp(), "result" to result)
        )

        when (type) {
            "SYNC" -> {
                // Önbelleği atla, sunucudan taze oku (dinleyiciler değişikliği yayacaktır).
                identity?.let { id -> deviceRef(id).get(Source.SERVER) }
                ack()
            }
            "MESSAGE" -> {
                bringLauncherToFront()
                app.emit(KioskEvent.Message(payload["text"] as? String ?: ""))
                ack()
            }
            "OPEN_BOOK" -> {
                val bookId = payload["bookId"] as? String
                if (bookId != null) {
                    bringLauncherToFront()
                    app.emit(KioskEvent.OpenBook(bookId))
                }
                ack(if (bookId != null) "ok" else "bookId yok")
            }
            "MAINTENANCE" -> {
                // Uzaktan bakım: launcher öne gelir ve kilidi kendisi kaldırır.
                bringLauncherToFront()
                app.emit(KioskEvent.Maintenance((payload["minutes"] as? Number)?.toInt() ?: 10))
                ack()
            }
            "REBOOT" -> ack().addOnCompleteListener { pm.reboot() }
            else -> ack("bilinmeyen komut")
        }
    }

    /** FCM'den gelen uyandırma: dinleyiciler bağlantıyı zaten tazeler, ek olarak durumu raporla. */
    fun onPush(data: Map<String, String>) {
        Log.i(TAG, "Push: $data")
        val id = identity ?: return
        deviceRef(id).get(Source.SERVER)
        scope.launch { reportStatus() }
    }

    fun updateFcmToken(token: String) {
        val id = identity ?: return
        deviceRef(id).set(mapOf("fcmToken" to token), SetOptions.merge())
    }

    // ------------------------------------------------------------------ durum raporu

    private suspend fun heartbeatLoop() {
        while (true) {
            reportStatus()
            delay(HEARTBEAT_MS)
        }
    }

    private fun reportStatus() {
        val id = identity ?: return
        val battery = app.getSystemService(BatteryManager::class.java)!!
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val p = pm.current
        val status = mapOf(
            "lastSeen" to FieldValue.serverTimestamp(),
            "battery" to battery,
            "mode" to p.mode.name,
            "appliedPolicyVersion" to p.version,
            "foreground" to app.lastLaunchedPackage,
            "appVersion" to BuildConfig.VERSION_NAME,
            "android" to Build.VERSION.SDK_INT,
        )
        // Kurallar cihazın yalnızca "status" ve "fcmToken" alanlarını yazmasına izin verir.
        deviceRef(id).update("status", status)
    }

    /** İşaretlenmiş PDF'i öğretmene "teslim" eder: teslimler/{okul}/{cihaz}/... */
    suspend fun uploadSubmission(file: File, bookId: String): String {
        val id = identity ?: error("Cihaz henüz kayıtlı değil")
        val path = "teslimler/${id.schoolId}/${id.deviceId}/${bookId}_${System.currentTimeMillis()}.pdf"
        FirebaseStorage.getInstance().reference.child(path).putFile(android.net.Uri.fromFile(file)).await()
        return path
    }

    private fun bringLauncherToFront() {
        app.startActivity(
            Intent(app, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private companion object {
        const val TAG = "PolicySync"
        const val HEARTBEAT_MS = 5 * 60_000L
    }
}
