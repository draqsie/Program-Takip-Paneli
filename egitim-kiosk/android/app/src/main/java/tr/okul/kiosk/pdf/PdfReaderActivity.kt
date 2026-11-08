package tr.okul.kiosk.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tr.okul.kiosk.KioskApp
import tr.okul.kiosk.policy.KioskMode
import java.io.File
import kotlin.math.min

/**
 * Entegre PDF okuyucu + test çözme ekranı.
 *
 * Görüntüleme: Android'in yerleşik PdfRenderer'ı (ek kütüphane yok, lisans yok).
 * İşaretleme : PdfRenderer bitmap'inin üstünde InkCanvasView katmanı.
 * Kayıt      : AnnotationStore (vektör JSON), Teslim: AnnotationExporter (PDFBox) → Firebase Storage.
 *
 * Bilinçli olarak OLMAYANLAR: paylaş, yazdır, metin kopyala, "başka uygulamada aç". PDF dışarı çıkamaz.
 */
class PdfReaderActivity : ComponentActivity() {

    private lateinit var bookId: String
    private lateinit var bookFile: File
    private lateinit var store: AnnotationStore
    private lateinit var annotations: MutableMap<Int, List<InkCanvasView.Stroke>>

    // PdfRenderer iş parçacığı güvenli değildir: tüm erişim tek iş parçacıklı bu dağıtıcıdan yapılır.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var saveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = KioskApp.from(this)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        bookFile = app.library.fileFor(bookId)
        if (!bookFile.exists()) {
            Toast.makeText(this, "Kitap henüz indirilmedi", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        store = AnnotationStore(this)
        annotations = store.load(bookId)
        fd = ParcelFileDescriptor.open(bookFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(fd!!)
        renderer = r

        // Öğretmen ekranları kilitlerse okuyucu kapanır.
        lifecycleScope.launch {
            app.policyManager.policy.collect { if (it.mode == KioskMode.KILITLI) finish() }
        }

        setContent {
            MaterialTheme {
                ReaderScreen(
                    title = app.library.titleOf(bookId),
                    pageCount = r.pageCount,
                    render = ::renderPage,
                    strokesFor = { page -> annotations[page].orEmpty() },
                    onStrokesChanged = { page, strokes ->
                        annotations[page] = strokes
                        scheduleSave()
                    },
                    onSubmit = ::submit,
                    onClose = ::finish,
                )
            }
        }
    }

    /** Sayfayı, verilen kutuya sığacak en büyük boyutta çizer. */
    private suspend fun renderPage(index: Int, maxW: Int, maxH: Int): Bitmap = withContext(renderDispatcher) {
        val r = renderer ?: error("PDF kapalı")
        r.openPage(index).use { page ->
            val scale = min(maxW.toFloat() / page.width, maxH.toFloat() / page.height)
            // 2048 px üstü bellek israfı; tabletlerde netlik için yeterli.
            val w = (page.width * scale).toInt().coerceIn(1, 2048)
            val h = (w.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    private fun scheduleSave() {
        val snapshot = annotations.toMap()
        saveJob?.cancel()
        saveJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(600)
            store.save(bookId, snapshot)
        }
    }

    private fun submit() {
        val snapshot = annotations.toMap()
        lifecycleScope.launch {
            val msg = runCatching {
                withContext(Dispatchers.IO) {
                    store.save(bookId, snapshot)
                    val out = File(cacheDir, "teslim-$bookId.pdf")
                    AnnotationExporter.export(this@PdfReaderActivity, bookFile, snapshot, out)
                    KioskApp.from(this@PdfReaderActivity).sync.uploadSubmission(out, bookId)
                    out.delete()
                }
            }.fold(onSuccess = { "Çözümün öğretmene gönderildi" }, onFailure = { "Gönderilemedi: ${it.message}" })
            Toast.makeText(this@PdfReaderActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::store.isInitialized) {
            saveJob?.cancel()
            store.save(bookId, annotations.toMap()) // küçük dosya; senkron yazmak güvenli
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Devam eden bir çizim bitene kadar bekle, sonra kapat (aynı tek iş parçacığında).
        runBlocking(renderDispatcher) {
            renderer?.close()
            fd?.close()
            renderer = null
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        fun intent(ctx: Context, bookId: String) =
            Intent(ctx, PdfReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}

private val PEN_COLORS = listOf(
    android.graphics.Color.rgb(26, 71, 184),   // mavi
    android.graphics.Color.rgb(198, 40, 40),   // kırmızı
    android.graphics.Color.rgb(33, 33, 33),    // siyah
    android.graphics.Color.rgb(46, 125, 50),   // yeşil
)
private val HIGHLIGHT_COLOR = android.graphics.Color.rgb(255, 214, 0)

@Composable
private fun ReaderScreen(
    title: String,
    pageCount: Int,
    render: suspend (page: Int, maxW: Int, maxH: Int) -> Bitmap,
    strokesFor: (Int) -> List<InkCanvasView.Stroke>,
    onStrokesChanged: (Int, List<InkCanvasView.Stroke>) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(InkCanvasView.Tool.PEN) }
    var penColor by remember { mutableIntStateOf(PEN_COLORS[0]) }
    var stylusOnly by rememberSaveable { mutableStateOf(false) }
    var inkView by remember { mutableStateOf<InkCanvasView?>(null) }

    Column(Modifier.fillMaxSize().background(Color(0xFFE6E8EB))) {
        // ---------------- araç çubuğu
        Row(
            Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onClose) { Text("‹ Kitaplar") }
            Text(title, style = MaterialTheme.typography.titleMedium)
            FilterChip(selected = tool == InkCanvasView.Tool.PEN, onClick = { tool = InkCanvasView.Tool.PEN }, label = { Text("Kalem") })
            FilterChip(selected = tool == InkCanvasView.Tool.HIGHLIGHTER, onClick = { tool = InkCanvasView.Tool.HIGHLIGHTER }, label = { Text("Fosforlu") })
            FilterChip(selected = tool == InkCanvasView.Tool.ERASER, onClick = { tool = InkCanvasView.Tool.ERASER }, label = { Text("Silgi") })
            PEN_COLORS.forEach { c ->
                Box(
                    Modifier.size(28.dp)
                        .background(Color(c), CircleShape)
                        .border(if (c == penColor) 3.dp else 0.dp, Color(0xFF90A4AE), CircleShape)
                        .clickable { penColor = c; if (tool == InkCanvasView.Tool.ERASER) tool = InkCanvasView.Tool.PEN },
                )
            }
            TextButton(onClick = { inkView?.undo() }) { Text("Geri al") }
            TextButton(onClick = { inkView?.redo() }) { Text("İleri al") }
            Text("Sadece kalem")
            Switch(checked = stylusOnly, onCheckedChange = { stylusOnly = it })
            TextButton(enabled = page > 0, onClick = { page-- }) { Text("‹") }
            Text("${page + 1} / $pageCount")
            TextButton(enabled = page < pageCount - 1, onClick = { page++ }) { Text("›") }
            TextButton(onClick = onSubmit) { Text("Teslim et") }
        }

        // ---------------- sayfa + çizim katmanı
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val maxW = constraints.maxWidth
            val maxH = constraints.maxHeight
            val bitmap by produceState<Bitmap?>(null, page, maxW, maxH) { value = render(page, maxW, maxH) }
            val density = LocalDensity.current

            bitmap?.let { bmp ->
                val wDp = with(density) { bmp.width.toDp() }
                val hDp = with(density) { bmp.height.toDp() }
                Box(Modifier.size(wDp, hDp)) {
                    Image(bmp.asImageBitmap(), contentDescription = "Sayfa ${page + 1}", Modifier.fillMaxSize())
                    AndroidView(
                        factory = { ctx -> InkCanvasView(ctx).also { inkView = it } },
                        update = { v ->
                            v.tool = tool
                            v.color = if (tool == InkCanvasView.Tool.HIGHLIGHTER) HIGHLIGHT_COLOR else penColor
                            v.stylusOnly = stylusOnly
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // Sayfa değişince o sayfanın çizimlerini yükle ve değişiklikleri doğru sayfaya yaz.
    LaunchedEffect(page, inkView) {
        val v = inkView ?: return@LaunchedEffect
        v.setStrokes(strokesFor(page))
        val current = page
        v.onStrokesChanged = { strokes -> onStrokesChanged(current, strokes) }
    }
}
