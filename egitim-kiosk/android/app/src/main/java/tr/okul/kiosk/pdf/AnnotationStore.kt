package tr.okul.kiosk.pdf

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * İşaretlemeler kitap başına bir JSON dosyasında, vektör olarak saklanır (PDF'in kendisi değişmez):
 *   {"v":1,"pages":{"0":[{"t":"PEN","c":-15054920,"w":0.0028,"p":[0.1234,0.5678,...]}]}}
 * AtomicFile: yazma sırasında pil biterse dosya bozulmaz.
 * İleride sunucuya yedeklemek (öğrenci tablet değiştirince işaretlemeleri geri almak) için ideal biçim.
 */
class AnnotationStore(ctx: Context) {

    private val dir = File(ctx.filesDir, "notlar").apply { mkdirs() }

    private fun file(bookId: String) =
        AtomicFile(File(dir, bookId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json"))

    fun load(bookId: String): MutableMap<Int, List<InkCanvasView.Stroke>> = runCatching {
        val root = JSONObject(String(file(bookId).readFully(), Charsets.UTF_8))
        val pages = root.getJSONObject("pages")
        val result = mutableMapOf<Int, List<InkCanvasView.Stroke>>()
        pages.keys().forEach { key ->
            val arr = pages.getJSONArray(key)
            result[key.toInt()] = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pts = o.getJSONArray("p")
                InkCanvasView.Stroke(
                    tool = InkCanvasView.Tool.valueOf(o.getString("t")),
                    color = o.getInt("c"),
                    width = o.getDouble("w").toFloat(),
                    points = FloatArray(pts.length()) { j -> pts.getDouble(j).toFloat() },
                )
            }
        }
        result
    }.getOrElse { mutableMapOf() }

    @Synchronized
    fun save(bookId: String, pages: Map<Int, List<InkCanvasView.Stroke>>) {
        val json = JSONObject().put("v", 1).put("pages", JSONObject().apply {
            pages.filterValues { it.isNotEmpty() }.forEach { (page, strokes) ->
                put(page.toString(), JSONArray().apply {
                    strokes.forEach { s ->
                        put(JSONObject()
                            .put("t", s.tool.name)
                            .put("c", s.color)
                            .put("w", s.width.toDouble())
                            .put("p", JSONArray().apply { s.points.forEach { put(round4(it)) } }))
                    }
                })
            }
        })
        val f = file(bookId)
        val out = f.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: IOException) {
            f.failWrite(out)
            throw e
        }
    }

    /** 4 basamak (~0,1 mm) yeterli hassasiyet; dosya boyutunu yarıya indirir. */
    private fun round4(v: Float): Double = (v * 10_000f).roundToInt() / 10_000.0
}
