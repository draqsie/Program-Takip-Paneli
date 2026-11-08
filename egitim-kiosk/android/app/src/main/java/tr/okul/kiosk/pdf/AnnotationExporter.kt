package tr.okul.kiosk.pdf

import android.content.Context
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File

/**
 * Vektör işaretlemeleri orijinal PDF'in üstüne çizerek yeni bir PDF üretir ("teslim et").
 * Öğretmen, öğrencinin çözümünü herhangi bir PDF görüntüleyicide aynen görür.
 *
 * Koordinat dönüşümü: Android'de (0,0) sol üst; PDF'te sol alt → y ters çevrilir.
 * Not: /Rotate değeri 0 olmayan (döndürülmüş) sayfalar için ek dönüşüm gerekir; ders kitaplarında nadirdir.
 */
object AnnotationExporter {

    fun export(ctx: Context, source: File, pages: Map<Int, List<InkCanvasView.Stroke>>, out: File) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(source).use { doc ->
            for ((index, strokes) in pages) {
                if (strokes.isEmpty() || index >= doc.numberOfPages) continue
                val page = doc.getPage(index)
                val box = page.cropBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (s in strokes) {
                        if (s.tool == InkCanvasView.Tool.ERASER || s.points.size < 2) continue
                        cs.saveGraphicsState()
                        if (s.tool == InkCanvasView.Tool.HIGHLIGHTER) {
                            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { strokingAlphaConstant = 0.35f })
                        }
                        cs.setStrokingColor(Color.red(s.color) / 255f, Color.green(s.color) / 255f, Color.blue(s.color) / 255f)
                        cs.setLineWidth(s.width * box.width)
                        cs.setLineCapStyle(1)  // yuvarlak uç
                        cs.setLineJoinStyle(1) // yuvarlak birleşim

                        val p = s.points
                        fun px(i: Int) = box.lowerLeftX + p[i] * box.width
                        fun py(i: Int) = box.upperRightY - p[i + 1] * box.height
                        cs.moveTo(px(0), py(0))
                        if (p.size == 2) cs.lineTo(px(0) + 0.1f, py(0)) // tek nokta → görünür nokta
                        var k = 2
                        while (k < p.size) {
                            cs.lineTo(px(k), py(k))
                            k += 2
                        }
                        cs.stroke()
                        cs.restoreGraphicsState()
                    }
                }
            }
            doc.save(out)
        }
    }
}
