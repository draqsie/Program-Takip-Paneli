package tr.okul.izin.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tr.okul.izin.App
import tr.okul.izin.enforce.EnforcementService
import tr.okul.izin.enforce.Permissions
import tr.okul.izin.update.Updater
import androidx.compose.runtime.LaunchedEffect

/** Bu metin değişirse sürümü artırın; sunucu öğrencinin hangi metni onayladığını saklar. */
private const val CONSENT_VERSION = "2026-11-v1"

class MainActivity : ComponentActivity() {

    private var missing by mutableStateOf<List<Permissions.Kind>>(emptyList())
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = App.from(this)
        setContent {
            MaterialTheme {
                val profile by app.sync.profile.collectAsStateWithLifecycle()
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Okul İzin", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (profile == null || !app.sync.isEnrolled) {
                        EnrollScreen(onEnroll = { code, name -> app.sync.enroll(code, name, CONSENT_VERSION) })
                    } else {
                        PermissionChecklist(missing, onFix = { kind ->
                            if (kind == Permissions.Kind.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startActivity(Permissions.settingsIntent(this@MainActivity, kind))
                            }
                        })
                        StatusScreen(app)
                        UpdateCard(app)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        missing = Permissions.missing(this)
        if (App.from(this).sync.isEnrolled) EnforcementService.start(this)
    }
}

@Composable
private fun EnrollScreen(onEnroll: suspend (code: String, name: String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var studentOk by remember { mutableStateOf(false) }
    var parentOk by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Google Play "belirgin açıklama" şartı: neyin toplandığı, izin istenmeden ÖNCE açıkça söylenir.
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bu uygulama ne yapar?", fontWeight = FontWeight.SemiBold)
            Text("• Yalnızca okul saatlerinde, okulun izin vermediği uygulamaları kapatır. Okul saati dışında hiçbir şeyi kısıtlamaz.")
            Text("• İzin verilmeyen bir uygulamayı açtığında öğretmenine izin isteği gönderebilirsin.")
            Text("• Okula gönderilenler: adın, sınıfın, tabletindeki uygulamaların adları, izin isteklerin ve uygulamanın çalışıp çalışmadığı.")
            Text("• Gönderilmeyenler: mesajların, fotoğrafların, konumun, internet geçmişin, uygulamaların içindeki hiçbir şey.")
            Text("• Uygulamayı istediğin zaman kaldırabilirsin; kaldırırsan okul bunu görür.")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(studentOk, { studentOk = it }); Text("Okudum, kabul ediyorum (öğrenci).")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(parentOk, { parentOk = it }); Text("Velisi olarak okudum, onaylıyorum.")
    }
    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Ad Soyad") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(10) }, label = { Text("Kayıt kodu (öğretmeninden)") }, modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = Color(0xFFC62828)) }
    Button(
        enabled = !busy && studentOk && parentOk && code.length == 10 && name.trim().length >= 3,
        onClick = {
            busy = true
            error = null
            scope.launch {
                runCatching { onEnroll(code, name.trim()) }.onFailure { error = "Kayıt olmadı: ${it.message}" }
                busy = false
            }
        },
    ) { Text(if (busy) "Kaydediliyor…" else "Kaydol") }
}

@Composable
private fun PermissionChecklist(missing: List<Permissions.Kind>, onFix: (Permissions.Kind) -> Unit) {
    if (missing.isEmpty()) {
        Text("✓ Tüm izinler verildi, koruma aktif.", color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
        return
    }
    Text("Uygulamanın çalışması için şu izinleri ver:", fontWeight = FontWeight.SemiBold)
    missing.forEach { kind ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(kind.title, fontWeight = FontWeight.SemiBold)
                Text(kind.why)
                Button(onClick = { onFix(kind) }, modifier = Modifier.padding(top = 8.dp)) { Text("Ayarları aç") }
            }
        }
    }
}

@Composable
private fun StatusScreen(app: App) {
    val policy by app.policy.collectAsStateWithLifecycle()
    val pending by app.pendingRequests.collectAsStateWithLifecycle()
    val now = app.clock.now()
    val s = policy.schedule
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    policy.paused -> "Kısıtlamalar okul tarafından duraklatıldı."
                    policy.isRestrictedNow(now) -> "Şu an okul saati: yalnızca izinli uygulamalar açılır."
                    else -> "Şu an okul saati değil: tablet serbest."
                },
                fontWeight = FontWeight.SemiBold,
            )
            Text("Okul saatleri: ${hm(s.startMin)}–${hm(s.endMin)}")
            if (pending.isNotEmpty()) Text("Onay bekleyen isteğin: ${pending.size}")
        }
    }
}

/** GitHub'daki son sürümü denetler. Okul saatinde güncellenmez (kurulum ekranı o saatte kapalıdır). */
@Composable
private fun UpdateCard(app: App) {
    var info by remember { mutableStateOf<Updater.Info?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { info = runCatching { Updater.check() }.getOrNull() }

    val available = info ?: return
    val schoolTime = app.policy.value.isRestrictedNow(app.clock.now())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yeni sürüm: ${available.versionName}", fontWeight = FontWeight.SemiBold)
            if (schoolTime) {
                Text("Okul saati bitince güncelleyebilirsin.")
            } else {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        message = runCatching { Updater.install(ctx, available) }
                            .fold({ "Kurulum ekranında \"Güncelle\"ye bas." }, { "Güncellenemedi: ${it.message}" })
                        busy = false
                    }
                }) { Text(if (busy) "İndiriliyor…" else "Güncelle") }
            }
            message?.let { Text(it) }
        }
    }
}
