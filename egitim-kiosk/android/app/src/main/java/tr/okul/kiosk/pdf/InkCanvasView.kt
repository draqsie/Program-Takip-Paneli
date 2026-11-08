package tr.okul.kiosk.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * PDF sayfasının üstüne binen saydam çizim katmanı.
 *
 * Tasarım kararları:
 *  - Koordinatlar 0..1 aralığında NORMALİZE saklanır → ekran boyutu, yön ve yakınlaştırma değişse de
 *    çizim sayfada aynı yere oturur; PDF'e aktarırken de doğrudan sayfa ölçüsüyle çarpılır.
 *  - Bitmiş çizgiler bir önbellek bitmap'ine bir kez çizilir; her karede sadece canlı çizgi çizilir.
 *  - Kalem (stylus) desteği: tarihsel noktalar (getHistoricalX) kullanılır → hızlı yazıda köşeli çizgi olmaz.
 *    "Sadece kalem" modunda parmak/avuç dokunuşları yok sayılır (avuç reddi).
 *  - Kalemin silgi ucu veya yan tuşu otomatik olarak silgiye geçer.
 *  - Geri al / ileri al, silme işlemlerini de kapsar.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Tool { PEN, HIGHLIGHTER, ERASER }

    /** width: sayfa genişliğine oranla kalınlık; points: [x0,y0,x1,y1,...] normalize. */
    class Stroke(val tool: Tool, val color: Int, val width: Float, val points: FloatArray)

    var tool: Tool = Tool.PEN
    var color: Int = Color.rgb(26, 71, 184)
    var stylusOnly: Boolean = false
    var onStrokesChanged: ((List<Stroke>) -> Unit)? = null

    private val strokes = ArrayList<Stroke>()
    private sealed interface Op {
        class Add(val stroke: Stroke) : Op
        class Erase(val stroke: Stroke, val index: Int) : Op
    }
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()

    private var live: FloatBuf? = null
    private var liveTool = Tool.PEN
    private var liveColor = color
    private var liveWidth = 0f
    private val livePath = Path()
    private var activePointer = MotionEvent.INVALID_POINTER_ID

    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eraserRadiusPx = 14f * resources.displayMetrics.density

    // ------------------------------------------------------------------ dış API

    fun setStrokes(list: List<Stroke>) {
        strokes.clear()
        strokes.addAll(list)
        undoStack.clear()
        redoStack.clear()
        rebuildCache()
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> strokes.remove(op.stroke)
            is Op.Erase -> strokes.add(op.index.coerceAtMost(strokes.size), op.stroke)
        }
        redoStack.addLast(op)
        rebuildCache()
        notifyChanged()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> strokes.add(op.stroke)
            is Op.Erase -> strokes.remove(op.stroke)
        }
        undoStack.addLast(op)
        rebuildCache()
        notifyChanged()
    }

    // ------------------------------------------------------------------ dokunma

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val type = e.getToolType(0)
                val isPen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
                if (stylusOnly && !isPen) return false
                activePointer = e.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)

                liveTool = when {
                    type == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
                    e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 -> Tool.ERASER
                    else -> tool
                }
                if (liveTool == Tool.ERASER) {
                    eraseAt(e.x, e.y)
                } else {
                    liveColor = color
                    liveWidth = if (liveTool == Tool.HIGHLIGHTER) HIGHLIGHTER_WIDTH else PEN_WIDTH
                    live = FloatBuf()
                    livePath.reset()
                    addPoint(e.x, e.y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activePointer)
                if (i < 0) return true
                for (h in 0 until e.historySize) handleMove(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                handleMove(e.getX(i), e.getY(i))
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activePointer) commitLive()
            }

            MotionEvent.ACTION_UP -> commitLive()

            // Sistem dokunuşu avuç olarak sınıflandırırsa CANCEL gönderir: çizgiyi at.
            MotionEvent.ACTION_CANCEL -> {
                live = null
                livePath.reset()
                activePointer = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
        }
        return true
    }

    private fun handleMove(x: Float, y: Float) {
        if (liveTool == Tool.ERASER) eraseAt(x, y) else if (live != null) addPoint(x, y)
    }

    private fun addPoint(x: Float, y: Float) {
        val buf = live ?: return
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val nx = (x / w).coerceIn(0f, 1f)
        val ny = (y / h).coerceIn(0f, 1f)
        if (buf.size == 0) {
            livePath.moveTo(x, y)
        } else {
            // Orta noktalara quadTo: titrek parmak/kalem çizgisini yumuşatır.
            val px = buf.lastX() * w
            val py = buf.lastY() * h
            livePath.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
        }
        buf.add(nx, ny)
        invalidate()
    }

    private fun commitLive() {
        val buf = live
        live = null
        activePointer = MotionEvent.INVALID_POINTER_ID
        livePath.reset()
        if (buf != null && buf.size > 0) {
            val stroke = Stroke(liveTool, liveColor, liveWidth, buf.toArray())
            strokes.add(stroke)
            undoStack.addLast(Op.Add(stroke))
            redoStack.clear()
            cacheCanvas?.let { drawStroke(it, stroke) }
            notifyChanged()
        }
        invalidate()
    }

    /** Çizgi bazlı silgi: dokunulan noktanın yakınından geçen çizginin tamamı silinir (test çözümünde en pratiği). */
    private fun eraseAt(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        var changed = false
        for (idx in strokes.indices.reversed()) {
            val s = strokes[idx]
            val hitRadius = eraserRadiusPx + s.width * w / 2f
            val p = s.points
            var k = 0
            while (k < p.size) {
                if (hypot(p[k] * w - x, p[k + 1] * h - y) <= hitRadius) {
                    strokes.removeAt(idx)
                    undoStack.addLast(Op.Erase(s, idx))
                    changed = true
                    break
                }
                k += 2
            }
        }
        if (changed) {
            redoStack.clear()
            rebuildCache()
            notifyChanged()
        }
    }

    // ------------------------------------------------------------------ çizim

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache?.recycle()
        if (w > 0 && h > 0) {
            cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cacheCanvas = Canvas(cache!!)
        }
        rebuildCache()
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (live != null) {
            configurePaint(liveTool, liveColor, liveWidth)
            canvas.drawPath(livePath, paint)
        }
    }

    private fun rebuildCache() {
        val c = cacheCanvas ?: return invalidate()
        cache?.eraseColor(Color.TRANSPARENT)
        strokes.forEach { drawStroke(c, it) }
        invalidate()
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = s.points
        configurePaint(s.tool, s.color, s.width)
        if (p.size == 2) {
            c.drawPoint(p[0] * w, p[1] * h, paint)
            return
        }
        val path = Path().apply {
            moveTo(p[0] * w, p[1] * h)
            var k = 2
            while (k < p.size) {
                val px = p[k - 2] * w
                val py = p[k - 1] * h
                val x = p[k] * w
                val y = p[k + 1] * h
                quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
                k += 2
            }
            lineTo(p[p.size - 2] * w, p[p.size - 1] * h)
        }
        c.drawPath(path, paint)
    }

    private fun configurePaint(t: Tool, color: Int, widthNorm: Float) {
        paint.color = color
        paint.alpha = if (t == Tool.HIGHLIGHTER) 90 else 255
        paint.strokeCap = if (t == Tool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        paint.strokeWidth = (widthNorm * width).coerceAtLeast(1f)
    }

    private fun notifyChanged() = onStrokesChanged?.invoke(strokes.toList())

    /** Kutulamasız büyüyen float dizisi (çizim sırasında GC baskısı yaratmamak için). */
    private class FloatBuf {
        private var a = FloatArray(256)
        var size = 0
            private set

        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x
            a[size++] = y
        }

        fun lastX() = a[size - 2]
        fun lastY() = a[size - 1]
        fun toArray(): FloatArray = a.copyOf(size)
    }

    companion object {
        const val PEN_WIDTH = 0.0028f
        const val HIGHLIGHTER_WIDTH = 0.018f
    }
}
