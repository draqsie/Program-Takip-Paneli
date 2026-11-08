package tr.okul.kiosk.policy

import org.json.JSONArray
import org.json.JSONObject

/**
 * NORMAL  : Beyaz listedeki uygulamalar + PDF kitaplar
 * SINAV   : Yalnızca PDF kitaplar (diğer her şey gizli)
 * KILITLI : "Tahtaya bakın" ekranı; hiçbir şey açılamaz
 */
enum class KioskMode { NORMAL, SINAV, KILITLI }

data class BookRef(
    val id: String,
    val title: String,
    val storagePath: String, // Firebase Storage yolu, ör. "okullar/ataturk-al/kitaplar/mat9.pdf"
    val sha256: String?,
)

data class PinHash(val saltB64: String, val hashB64: String, val iterations: Int)

/** Cihazda uygulanan "etkin" politika: okul ayarı + sınıf (grup) + cihaza özel istisnaların birleşimi. */
data class KioskPolicy(
    val version: Long = 0,
    val mode: KioskMode = KioskMode.NORMAL,
    val allowedApps: Set<String> = emptySet(),
    val books: List<BookRef> = emptyList(),
    val cameraDisabled: Boolean = true,
    val screenCaptureDisabled: Boolean = false,
    val pin: PinHash? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("mode", mode.name)
        put("allowedApps", JSONArray(allowedApps.toList()))
        put("books", JSONArray().apply {
            books.forEach { b ->
                put(JSONObject().put("id", b.id).put("title", b.title).put("path", b.storagePath).put("sha256", b.sha256))
            }
        })
        put("cameraDisabled", cameraDisabled)
        put("screenCaptureDisabled", screenCaptureDisabled)
        pin?.let { put("pin", JSONObject().put("salt", it.saltB64).put("hash", it.hashB64).put("iterations", it.iterations)) }
    }.toString()

    companion object {
        fun fromJson(s: String): KioskPolicy = runCatching {
            val o = JSONObject(s)
            KioskPolicy(
                version = o.optLong("version"),
                mode = KioskMode.valueOf(o.optString("mode", "NORMAL")),
                allowedApps = o.optJSONArray("allowedApps").strings().toSet(),
                books = o.optJSONArray("books")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val b = arr.getJSONObject(i)
                        BookRef(b.getString("id"), b.optString("title"), b.getString("path"), b.optString("sha256").ifEmpty { null })
                    }
                }.orEmpty(),
                cameraDisabled = o.optBoolean("cameraDisabled", true),
                screenCaptureDisabled = o.optBoolean("screenCaptureDisabled"),
                pin = o.optJSONObject("pin")?.let { PinHash(it.getString("salt"), it.getString("hash"), it.getInt("iterations")) },
            )
        }.getOrDefault(KioskPolicy())

        /**
         * Firestore belgelerinden etkin politikayı hesaplar.
         *   config : schools/{s}/config/kiosk
         *   group  : schools/{s}/groups/{g}
         *   device : schools/{s}/devices/{d}
         */
        fun fromFirestore(config: Map<String, Any?>, group: Map<String, Any?>, device: Map<String, Any?>): KioskPolicy {
            val allowed = group.strings("allowedApps") + device.strings("extraApps") - device.strings("blockedApps")
            val modeName = (device["modeOverride"] as? String) ?: (group["mode"] as? String)
            val mode = modeName?.let { runCatching { KioskMode.valueOf(it) }.getOrNull() } ?: KioskMode.NORMAL
            val books = (group["books"] as? List<*>).orEmpty().mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val path = m["path"] as? String ?: return@mapNotNull null
                BookRef(id, m["title"] as? String ?: id, path, m["sha256"] as? String)
            }
            val pin = (config["pin"] as? Map<*, *>)?.let { p ->
                val salt = p["salt"] as? String
                val hash = p["hash"] as? String
                val iter = (p["iterations"] as? Number)?.toInt()
                if (salt != null && hash != null && iter != null) PinHash(salt, hash, iter) else null
            }
            return KioskPolicy(
                version = (group["policyVersion"] as? Number)?.toLong() ?: 0L,
                mode = mode,
                allowedApps = allowed,
                books = books,
                cameraDisabled = group["cameraDisabled"] as? Boolean ?: true,
                screenCaptureDisabled = group["screenCaptureDisabled"] as? Boolean ?: false,
                pin = pin,
            )
        }

        private fun Map<String, Any?>.strings(key: String): Set<String> =
            (this[key] as? List<*>).orEmpty().filterIsInstance<String>().toSet()

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}
