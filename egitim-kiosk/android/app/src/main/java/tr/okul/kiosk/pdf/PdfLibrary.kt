package tr.okul.kiosk.pdf

import android.content.Context
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import tr.okul.kiosk.policy.BookRef
import tr.okul.kiosk.policy.sha256Hex
import java.io.File

/**
 * Kitaplar uygulamanın özel depolamasında (filesDir) durur: öğrenci dosya yöneticisiyle göremez,
 * paylaşamaz, başka uygulamada açamaz. İşaretlemeler ayrı tutulur; kitap güncellense bile kaybolmaz.
 */
class PdfLibrary(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "kitaplar").apply { mkdirs() }
    private val _books = MutableStateFlow(scanLocal())
    val books: StateFlow<List<BookRef>> = _books.asStateFlow()

    private var known: List<BookRef> = emptyList()

    fun fileFor(bookId: String) = File(dir, "${safe(bookId)}.pdf")
    fun titleOf(bookId: String) = known.firstOrNull { it.id == bookId }?.title ?: bookId

    suspend fun sync(refs: List<BookRef>) = withContext(Dispatchers.IO) {
        known = refs
        for (ref in refs) {
            val target = fileFor(ref.id)
            val upToDate = target.exists() && (ref.sha256 == null || target.sha256Hex().equals(ref.sha256, true))
            if (upToDate) continue
            val tmp = File(dir, "${safe(ref.id)}.part")
            FirebaseStorage.getInstance().reference.child(ref.storagePath).getFile(tmp).await()
            if (ref.sha256 != null && !tmp.sha256Hex().equals(ref.sha256, true)) {
                tmp.delete()
                continue // bozuk/yanlış dosya: eski sürüm (varsa) kalır
            }
            tmp.renameTo(target)
        }
        // Artık atanmamış kitapları kaldır (işaretlemeler AnnotationStore'da kalır).
        val keep = refs.map { "${safe(it.id)}.pdf" }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }

        _books.value = refs.filter { fileFor(it.id).exists() }
    }

    private fun scanLocal(): List<BookRef> =
        dir.listFiles { f -> f.extension == "pdf" }.orEmpty().map {
            BookRef(id = it.nameWithoutExtension, title = it.nameWithoutExtension, storagePath = "", sha256 = null)
        }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
