package tr.okul.izin.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import tr.okul.izin.App
import tr.okul.izin.BuildConfig
import tr.okul.izin.enforce.Permissions
import tr.okul.izin.policy.AccessPolicy

/**
 * Cihaz ↔ Firebase. Sunucu kodu yoktur; her şey Firestore + güvenlik kurallarıyla yürür.
 *  - Kayıt: anonim oturum açılır, öğretmenin kayıt koduyla `students/{uid}` oluşturulur
 *    (kurallar kodun geçerliliğini ve izin listesinin boş olduğunu denetler).
 *  - Canlı dinleme: okul ayarları, kendi kaydı (izinler), kendi istekleri. Ön plan servisi süreci
 *    canlı tuttuğu için öğretmen onayladığı anda cihaza düşer; push sunucusu gerekmez.
 *  - Rapor: okul saatinde 10 dk'da, dışında saatte bir (Firebase ücretsiz kotasına sığması için).
 */
class StudentSync(private val app: App, private val scope: CoroutineScope) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val prefs = app.getSharedPreferences("student", Context.MODE_PRIVATE)
    private val regs = mutableListOf<ListenerRegistration>()
    private var started = false

    private var settingsDoc: Map<String, Any?>? = null
    private var studentDoc: Map<String, Any?>? = null

    data class Profile(val studentId: String, val name: String, val className: String) {
        companion object {
            fun load(p: SharedPreferences): Profile? {
                val id = p.getString("id", null) ?: return null
                return Profile(id, p.getString("name", "") ?: "", p.getString("class", "") ?: "")
            }
        }
    }

    private val _profile = MutableStateFlow(Profile.load(prefs))
    val profile: StateFlow<Profile?> = _profile

    val isEnrolled: Boolean
        get() = _profile.value != null && auth.currentUser?.uid == _profile.value?.studentId

    private fun school() = db.collection("schools").document(BuildConfig.SCHOOL_ID)
    private fun me(p: Profile) = school().collection("students").document(p.studentId)

    // ------------------------------------------------------------------ kayıt

    suspend fun enroll(code: String, name: String, consentVersion: String) {
        val user = auth.currentUser ?: auth.signInAnonymously().await().user ?: error("Oturum açılamadı")
        val codeDoc = school().collection("enrollmentCodes").document(code).get().await()
        if (!codeDoc.exists()) error("Kayıt kodu geçersiz")
        val expires = codeDoc.getTimestamp("expiresAt")
        if (expires != null && expires.toDate().time < System.currentTimeMillis()) error("Kodun süresi dolmuş")
        val className = codeDoc.getString("className") ?: ""

        school().collection("students").document(user.uid).set(
            mapOf(
                "name" to name,
                "className" to className,
                "platform" to "android",
                "enrollCode" to code,
                "consentVersion" to consentVersion,
                "allowed" to emptyList<String>(),
                "grants" to emptyMap<String, Any>(),
                "labels" to emptyMap<String, Any>(),
                "inventory" to emptyList<Any>(),
                "status" to emptyMap<String, Any>(),
                "enrolledAt" to FieldValue.serverTimestamp(),
            )
        ).await()

        prefs.edit().putString("id", user.uid).putString("name", name).putString("class", className).apply()
        _profile.value = Profile(user.uid, name, className)
        start()
    }

    // ------------------------------------------------------------------ dinleme

    fun start() {
        val p = _profile.value ?: return
        if (started || !isEnrolled) return
        started = true

        regs += school().collection("config").document("settings").addSnapshotListener { s, _ ->
            settingsDoc = s?.data.orEmpty()
            recompute()
        }
        regs += me(p).addSnapshotListener { s, _ ->
            if (s == null || !s.exists()) return@addSnapshotListener
            studentDoc = s.data.orEmpty()
            // Kendi raporumuzdaki sunucu zaman damgası onaylanınca güvenilir saati güncelle.
            if (!s.metadata.hasPendingWrites()) {
                (s.get("status.lastSeen") as? Timestamp)?.let { app.clock.onServerTime(it.toDate().time) }
            }
            recompute()
        }
        regs += school().collection("requests").whereEqualTo("studentId", p.studentId)
            .addSnapshotListener { s, _ ->
                if (s == null) return@addSnapshotListener
                app.pendingRequests.value = s.documents
                    .filter { it.getString("status") == "PENDING" }
                    .mapNotNull { it.getString("appKey") }
                    .toSet()
                s.documentChanges
                    .filter { it.type != DocumentChange.Type.REMOVED && !it.document.metadata.hasPendingWrites() }
                    .forEach { notifyIfDecided(it.document.id, it.document.data) }
            }

        scope.launch {
            while (true) {
                runCatching { report(p) }.onFailure { Log.w(TAG, "rapor", it) }
                val schoolTime = app.policy.value.isRestrictedNow(app.clock.now())
                delay(if (schoolTime) 10 * 60_000L else 60 * 60_000L)
            }
        }
    }

    private fun recompute() {
        val settings = settingsDoc ?: return
        val student = studentDoc ?: return
        val grants = (student["grants"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val ts = v as? Timestamp ?: return@mapNotNull null
            key to ts.toDate().time
        }.toMap()
        app.updatePolicy(AccessPolicy.fromMaps(settings, student, grants))
    }

    /** Karar verilmiş ve henüz bildirilmemiş (son 6 saatteki) istekler için bir kez bildirim gösterir. */
    private fun notifyIfDecided(id: String, data: Map<String, Any?>) {
        val status = data["status"] as? String ?: return
        if (status == "PENDING") return
        val decidedAt = (data["decidedAt"] as? Timestamp)?.toDate()?.time ?: return
        if (System.currentTimeMillis() - decidedAt > 6 * 3600_000L) return
        val notified = prefs.getStringSet("notified", emptySet()).orEmpty()
        if (id in notified) return
        Notifier.decision(app, id, data["appLabel"] as? String ?: "Uygulama", status == "APPROVED")
        prefs.edit().putStringSet("notified", (notified + id).toList().takeLast(200).toSet()).apply()
    }

    // ------------------------------------------------------------------ istek ve rapor

    fun requestAccess(pkg: String, label: String) {
        val p = _profile.value ?: return
        if (pkg in app.pendingRequests.value) return
        school().collection("requests").add(
            mapOf(
                "studentId" to p.studentId,
                "studentName" to p.name,
                "className" to p.className,
                "platform" to "android",
                "appKey" to pkg,
                "appLabel" to label.take(79),
                "labelVerified" to true, // ad, cihazın paket yöneticisinden gelir
                "note" to "",
                "status" to "PENDING",
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).addOnFailureListener { Log.w(TAG, "istek gönderilemedi", it) }
    }

    /** Durum + (değiştiyse) uygulama listesi. Simge, kullanım süresi vb. gönderilmez. */
    private fun report(p: Profile) {
        val missing = Permissions.missing(app)
        val status = mapOf(
            "lastSeen" to FieldValue.serverTimestamp(),
            "protection" to if (missing.isEmpty()) "ACTIVE" else "PERMISSION_MISSING",
            "missing" to missing.map { it.title },
            "clockSkewSec" to app.clock.skewSeconds(),
            "appVersion" to BuildConfig.VERSION_NAME,
            "os" to "Android ${Build.VERSION.RELEASE}",
        )
        val updates = mutableMapOf<String, Any>("status" to status)

        val inventory = installedApps()
        val hash = inventory.joinToString("|") { it["id"] + "=" + it["label"] }.hashCode()
        val inventoryChanged = hash != prefs.getInt("inventoryHash", 0)
        if (inventoryChanged) {
            updates["inventory"] = inventory
            updates["inventoryUpdatedAt"] = FieldValue.serverTimestamp()
        }
        me(p).update(updates).addOnSuccessListener {
            if (inventoryChanged) prefs.edit().putInt("inventoryHash", hash).apply()
        }
    }

    private fun installedApps(): List<Map<String, String>> {
        val pm = app.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != app.packageName }
            .map { pkg -> mapOf("id" to pkg, "label" to pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }
            .sortedBy { it["label"] }
    }

    private companion object { const val TAG = "StudentSync" }
}
