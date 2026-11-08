package tr.okul.izin.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
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
 * Cihaz ↔ Firebase.
 *  - Kayıt: kayıt kodu → enrollStudent → claim'li custom token (öğrenci yalnızca kendi verisine erişir).
 *  - Dinleme: okul ayarları + öğrenci belgesi (izinli liste, geçici izinler) + kendi bekleyen istekleri.
 *  - Rapor: 5 dakikada bir durum (izinler tam mı, saat sapması) ve değiştiyse uygulama listesi.
 */
class StudentSync(private val app: App, private val scope: CoroutineScope) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val prefs = app.getSharedPreferences("student", Context.MODE_PRIVATE)
    private val regs = mutableListOf<ListenerRegistration>()
    private var started = false

    private var settingsDoc: Map<String, Any?>? = null
    private var studentDoc: Map<String, Any?>? = null

    private val _profile = MutableStateFlow(Profile.load(prefs))
    val profile: StateFlow<Profile?> = _profile

    data class Profile(val studentId: String, val name: String, val className: String) {
        companion object {
            fun load(p: android.content.SharedPreferences): Profile? {
                val id = p.getString("id", null) ?: return null
                return Profile(id, p.getString("name", "") ?: "", p.getString("class", "") ?: "")
            }
        }
    }

    val isEnrolled: Boolean get() = _profile.value != null && auth.currentUser != null

    private val schoolId = BuildConfig.SCHOOL_ID
    private fun school() = db.collection("schools").document(schoolId)
    private fun me(p: Profile) = school().collection("students").document(p.studentId)

    // ------------------------------------------------------------------ kayıt

    suspend fun enroll(code: String, name: String, consentVersion: String) {
        val res = FirebaseFunctions.getInstance("europe-west1").getHttpsCallable("enrollStudent").call(
            mapOf(
                "schoolId" to schoolId, "code" to code, "name" to name,
                "platform" to "android", "consentVersion" to consentVersion,
            )
        ).await()
        val data = res.getData() as Map<*, *>
        auth.signInWithCustomToken(data["customToken"] as String).await()
        val id = data["studentId"] as String
        prefs.edit().putString("id", id).putString("name", name).apply()
        _profile.value = Profile(id, name, "")
        start()
    }

    // ------------------------------------------------------------------ dinleme

    fun start() {
        val p = _profile.value ?: return
        if (started || auth.currentUser == null) return
        started = true

        regs += school().collection("config").document("settings").addSnapshotListener { s, _ ->
            settingsDoc = s?.data.orEmpty()
            recompute()
        }
        regs += me(p).addSnapshotListener { s, _ ->
            if (s == null || !s.exists()) return@addSnapshotListener
            studentDoc = s.data.orEmpty()
            val className = s.getString("className") ?: ""
            if (className != _profile.value?.className) {
                prefs.edit().putString("class", className).apply()
                _profile.value = p.copy(className = className)
            }
            // Kendi yazdığımız sunucu zaman damgası onaylandığında güvenilir saati güncelle.
            if (!s.metadata.hasPendingWrites()) {
                (s.get("status.lastSeen") as? Timestamp)?.let { app.clock.onServerTime(it.toDate().time) }
            }
            recompute()
        }
        regs += school().collection("requests")
            .whereEqualTo("studentId", p.studentId)
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { s, _ ->
                app.pendingRequests.value = s?.documents?.mapNotNull { it.getString("appKey") }?.toSet().orEmpty()
            }

        FirebaseMessaging.getInstance().token.addOnSuccessListener { updateFcmToken(it) }
        scope.launch {
            while (true) {
                runCatching { report(p) }.onFailure { Log.w(TAG, "rapor", it) }
                delay(5 * 60_000L)
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

    fun updateFcmToken(token: String) {
        val p = _profile.value ?: return
        me(p).set(mapOf("fcmToken" to token), SetOptions.merge())
    }

    /** Uygulama listesi değiştiyse gönderilir (ad + paket; simge ve kullanım süresi gönderilmez). */
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
        if (hash != prefs.getInt("inventoryHash", 0)) {
            updates["inventory"] = inventory
            updates["inventoryUpdatedAt"] = FieldValue.serverTimestamp()
        }
        me(p).update(updates).addOnSuccessListener {
            prefs.edit().putInt("inventoryHash", hash).apply()
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

    fun onPush() {
        val p = _profile.value ?: return
        scope.launch { runCatching { report(p) } }
    }

    private companion object { const val TAG = "StudentSync" }
}
