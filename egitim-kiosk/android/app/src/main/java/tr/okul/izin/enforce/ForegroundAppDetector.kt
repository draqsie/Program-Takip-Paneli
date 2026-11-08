package tr.okul.izin.enforce

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * "Kullanım erişimi" izniyle ekranda hangi uygulamanın açık olduğunu bulur.
 * Olaylar artımlı okunur (her seferinde yalnızca son sorgudan bu yana olanlar), bu yüzden
 * yarım saniyede bir çağırmak pil açısından ucuzdur.
 */
class ForegroundAppDetector(ctx: Context) {
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)!!
    private var lastQueryEnd = System.currentTimeMillis() - 60_000
    private var current: String? = null
    private var currentTs = 0L

    fun poll(): String? {
        val now = System.currentTimeMillis()
        // Sınırda kaçan olay olmasın diye 2 sn örtüşmeli okunur; daha eski olaylar currentTs ile elenir.
        val events = usm.queryEvents(lastQueryEnd - 2_000, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == RESUMED && e.timeStamp >= currentTs) {
                currentTs = e.timeStamp
                current = e.packageName
            }
        }
        lastQueryEnd = now
        return current
    }

    private companion object {
        @Suppress("DEPRECATION")
        val RESUMED = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else UsageEvents.Event.MOVE_TO_FOREGROUND
    }
}
