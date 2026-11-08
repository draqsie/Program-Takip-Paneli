package tr.okul.izin.enforce

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * İzin verilmeyen uygulamanın üstüne çizilen tam ekran engel.
 * Ayrı bir Activity yerine pencere (overlay) kullanılır: Android 10+ arka plandan Activity
 * başlatmayı kısıtlar, görünür bir overlay penceresi ise her zaman gösterilebilir.
 */
class BlockOverlay(
    private val ctx: Context,
    private val onRequest: (pkg: String, label: String) -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)!!
    private var root: LinearLayout? = null
    private var shownFor: String? = null
    private var shownPending = false

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var requestButton: Button

    val isShowing get() = root != null

    fun show(pkg: String, label: String, pending: Boolean) {
        if (root == null) attach()
        if (shownFor == pkg && shownPending == pending) return
        shownFor = pkg
        shownPending = pending
        title.text = "$label okul saatinde kapalı"
        if (pending) {
            subtitle.text = "İsteğin öğretmene iletildi. Onaylanınca bildirim alacaksın."
            requestButton.text = "Onay bekleniyor…"
            requestButton.isEnabled = false
        } else {
            subtitle.text = "Bu uygulamayı derste kullanman gerekiyorsa öğretmeninden izin isteyebilirsin."
            requestButton.text = "İzin iste"
            requestButton.isEnabled = true
            requestButton.setOnClickListener {
                requestButton.isEnabled = false
                requestButton.text = "Gönderiliyor…"
                onRequest(pkg, label)
                // Ağ hatası vb. yüzünden istek "bekliyor" durumuna geçmezse düğmeyi geri aç.
                requestButton.postDelayed({
                    if (shownFor == pkg && !shownPending) {
                        requestButton.isEnabled = true
                        requestButton.text = "Tekrar dene"
                    }
                }, 15_000)
            }
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        shownFor = null
    }

    private fun attach() {
        val pad = (24 * ctx.resources.displayMetrics.density).toInt()
        title = TextView(ctx).apply { textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        subtitle = TextView(ctx).apply { textSize = 17f; setTextColor(Color.rgb(207, 216, 220)); gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad) }
        requestButton = Button(ctx)
        val homeButton = Button(ctx).apply {
            text = "Ana ekrana dön"
            setOnClickListener {
                // Kullanıcı overlay'e dokunduğu için bu başlatma arka plan kısıtına takılmaz.
                ctx.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(38, 50, 56))
            addView(title)
            addView(subtitle)
            addView(requestButton)
            addView(homeButton)
            // Engelin altındaki uygulamaya hiçbir dokunuş geçmesin.
            isClickable = true
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )
        wm.addView(view, params)
        root = view
    }
}
