package tr.okul.kiosk.launcher

import android.app.ActivityManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.okul.kiosk.KioskApp
import tr.okul.kiosk.KioskEvent
import tr.okul.kiosk.pdf.PdfReaderActivity
import tr.okul.kiosk.policy.BookRef
import tr.okul.kiosk.policy.KioskMode
import tr.okul.kiosk.policy.KioskPolicy

/**
 * Öğrencinin gördüğü tek ana ekran. Sistem launcher'ı yerine açılır (HOME + persistent preferred
 * activity) ve Lock Task moduna girer: geri/son uygulamalar çalışmaz, durum çubuğu inmez.
 */
class LauncherActivity : ComponentActivity() {

    private val app get() = KioskApp.from(this)
    private var teacherMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { /* ana ekranda geri tuşu hiçbir şey yapmaz */ }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.events.collect { ev ->
                    when (ev) {
                        is KioskEvent.Message -> teacherMessage = ev.text
                        is KioskEvent.Maintenance -> AdminSession.begin(this@LauncherActivity, ev.minutes)
                        is KioskEvent.OpenBook -> openBook(ev.bookId)
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                val policy by app.policyManager.policy.collectAsStateWithLifecycle()
                val books by app.library.books.collectAsStateWithLifecycle()
                LauncherScreen(
                    policy = policy,
                    books = books,
                    onApp = ::launchApp,
                    onBook = { openBook(it.id) },
                    message = teacherMessage,
                    onDismissMessage = { teacherMessage = null },
                    onAdminUnlocked = { AdminSession.begin(this@LauncherActivity, minutes = 10) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterLockTaskIfNeeded()
    }

    /** Manifestteki lockTaskMode="if_whitelisted" çoğu durumda yeter; bu, bakım dönüşü gibi durumlar için emniyet. */
    private fun enterLockTaskIfNeeded() {
        if (AdminSession.isActive || !app.policyManager.isDeviceOwner) return
        val am = getSystemService(ActivityManager::class.java)!!
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE && app.policyManager.isLockTaskPermitted()) {
            startLockTask() // device owner beyaz listesindeysek onay sormadan tam kiosk modu
        }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        app.lastLaunchedPackage = pkg
        // Beyaz listedeki uygulama, kilitli görevin içinde açılır; listede değilse sistem engeller.
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openBook(bookId: String) {
        app.lastLaunchedPackage = "$packageName/pdf:$bookId"
        startActivity(PdfReaderActivity.intent(this, bookId))
    }
}

private data class AppTile(val pkg: String, val label: String, val icon: ImageBitmap)

@Composable
private fun LauncherScreen(
    policy: KioskPolicy,
    books: List<BookRef>,
    onApp: (String) -> Unit,
    onBook: (BookRef) -> Unit,
    message: String?,
    onDismissMessage: () -> Unit,
    onAdminUnlocked: () -> Unit,
) {
    var showPin by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F6F8)) {
        if (policy.mode == KioskMode.KILITLI) {
            LockedScreen(onLongPress = { showPin = true })
        } else {
            val tiles = rememberAppTiles(if (policy.mode == KioskMode.NORMAL) policy.allowedApps else emptySet())
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = if (policy.mode == KioskMode.SINAV) "Sınav modu" else "Okul Tableti",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    // Gizli yönetici girişi: başlığa uzun bas → PIN
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { showPin = true }) },
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(132.dp),
                    modifier = Modifier.fillMaxSize().padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (books.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Kitaplarım") }
                        items(books, key = { "b:" + it.id }) { b -> Tile(title = b.title, icon = null, onClick = { onBook(b) }) }
                    }
                    if (tiles.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Uygulamalar") }
                        items(tiles, key = { "a:" + it.pkg }) { t -> Tile(title = t.label, icon = t.icon, onClick = { onApp(t.pkg) }) }
                    }
                }
            }
        }
    }

    if (showPin) PinDialog(policy, onDismiss = { showPin = false }, onSuccess = { showPin = false; onAdminUnlocked() })
    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Öğretmeninizden mesaj") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("Tamam") } },
        )
    }
}

@Composable
private fun rememberAppTiles(packages: Set<String>): List<AppTile> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tiles by produceState(initialValue = emptyList<AppTile>(), packages) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            packages.mapNotNull { pkg ->
                if (pkg == ctx.packageName) return@mapNotNull null
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
                val icon: Drawable = pm.getApplicationIcon(info)
                AppTile(pkg, pm.getApplicationLabel(info).toString(), icon.toBitmap(144, 144).asImageBitmap())
            }.sortedBy { it.label.lowercase() }
        }
    }
    return tiles
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF455A64))

@Composable
private fun Tile(title: String, icon: ImageBitmap?, onClick: () -> Unit) {
    Column(
        Modifier
            .background(Color.White, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, Modifier.size(64.dp))
        } else {
            Box(Modifier.size(64.dp).background(Color(0xFF1E7A58), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text("PDF", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun LockedScreen(onLongPress: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF263238)).pointerInput(Unit) {
            detectTapGestures(onLongPress = { onLongPress() })
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Ekranlar öğretmen tarafından kilitlendi.\nLütfen tahtaya bakın.",
            color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PinDialog(policy: KioskPolicy, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val verifier = remember { PinVerifier(ctx) }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yönetici girişi") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter(Char::isDigit).take(10) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color(0xFFC62828), modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pin.length >= 6, onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) { verifier.verify(pin, policy.pin) }
                    busy = false
                    pin = ""
                    when (result) {
                        PinVerifier.Result.Ok -> onSuccess()
                        PinVerifier.Result.Wrong -> error = "PIN hatalı"
                        PinVerifier.Result.NotConfigured -> error = "Bu okul için PIN tanımlanmamış (admin panelinden ayarlayın)"
                        is PinVerifier.Result.LockedOut -> error = "Çok fazla deneme. ${result.secondsLeft} sn bekleyin."
                    }
                }
            }) { Text("Aç") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
