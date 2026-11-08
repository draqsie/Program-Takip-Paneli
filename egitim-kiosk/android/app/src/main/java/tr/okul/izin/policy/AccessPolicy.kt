package tr.okul.izin.policy

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Okul saatleri. days: 1=Pazartesi … 7=Pazar; dakikalar gece yarısından itibaren. */
data class SchoolSchedule(
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val startMin: Int = 8 * 60 + 30,
    val endMin: Int = 15 * 60 + 30,
    val zone: String = "Europe/Istanbul",
) {
    fun isSchoolTime(epochMs: Long): Boolean {
        val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zone))
        val minute = t.hour * 60 + t.minute
        return t.dayOfWeek.value in days && minute >= startMin && minute < endMin
    }

    companion object {
        /** "08:30" → 510 */
        fun parseHm(s: String?, fallback: Int): Int {
            val parts = s?.split(":") ?: return fallback
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return fallback
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return fallback
            return (h * 60 + m).coerceIn(0, 24 * 60)
        }
    }
}

/**
 * Cihazda geçerli erişim kuralı.
 *   - Okul saati dışında, duraklatılmışsa veya kayıt yoksa: hiçbir kısıtlama yok.
 *   - Okul saatinde: yalnızca herkese açık + öğrenciye kalıcı izinli + süresi dolmamış geçici izinli
 *     uygulamalar açılabilir (sistem için zorunlu paketler Exemptions'ta ayrıca muaftır).
 */
data class AccessPolicy(
    val enrolled: Boolean = false,
    val paused: Boolean = false,
    val schedule: SchoolSchedule = SchoolSchedule(),
    val defaultAllowed: Set<String> = emptySet(),
    val allowed: Set<String> = emptySet(),
    val grants: Map<String, Long> = emptyMap(), // paket → izin bitişi (epoch ms)
) {
    fun isRestrictedNow(now: Long): Boolean = enrolled && !paused && schedule.isSchoolTime(now)

    fun isAllowed(pkg: String, now: Long): Boolean =
        pkg in defaultAllowed || pkg in allowed || (grants[pkg]?.let { it > now } ?: false)

    fun toJson(): String = JSONObject()
        .put("enrolled", enrolled)
        .put("paused", paused)
        .put("days", JSONArray(schedule.days.toList()))
        .put("start", schedule.startMin)
        .put("end", schedule.endMin)
        .put("zone", schedule.zone)
        .put("defaultAllowed", JSONArray(defaultAllowed.toList()))
        .put("allowed", JSONArray(allowed.toList()))
        .put("grants", JSONObject(grants.mapValues { it.value }))
        .toString()

    companion object {
        fun fromJson(s: String?): AccessPolicy {
            if (s == null) return AccessPolicy()
            return runCatching {
                val o = JSONObject(s)
                val grants = o.optJSONObject("grants")
                AccessPolicy(
                    enrolled = o.optBoolean("enrolled"),
                    paused = o.optBoolean("paused"),
                    schedule = SchoolSchedule(
                        days = o.optJSONArray("days").ints().toSet(),
                        startMin = o.optInt("start", 510),
                        endMin = o.optInt("end", 930),
                        zone = o.optString("zone", "Europe/Istanbul"),
                    ),
                    defaultAllowed = o.optJSONArray("defaultAllowed").strings().toSet(),
                    allowed = o.optJSONArray("allowed").strings().toSet(),
                    grants = grants?.keys()?.asSequence()?.associateWith { grants.getLong(it) }.orEmpty(),
                )
            }.getOrDefault(AccessPolicy())
        }

        /**
         * Firestore belgelerinden kural üretir.
         *   settings : schools/{s}/config/settings   student : schools/{s}/students/{id}
         *   grants   : Timestamp'lar epoch ms'ye çevrilmiş halde (Firebase tipi bu sınıfa sızmasın diye)
         */
        fun fromMaps(settings: Map<String, Any?>, student: Map<String, Any?>, grants: Map<String, Long>): AccessPolicy {
            val sch = settings["schedule"] as? Map<*, *>
            return AccessPolicy(
                enrolled = true,
                paused = settings["paused"] as? Boolean ?: false,
                schedule = SchoolSchedule(
                    days = (sch?.get("days") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toSet() ?: setOf(1, 2, 3, 4, 5),
                    startMin = SchoolSchedule.parseHm(sch?.get("start") as? String, 510),
                    endMin = SchoolSchedule.parseHm(sch?.get("end") as? String, 930),
                ),
                defaultAllowed = (settings["defaultAllowedAndroid"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
                allowed = (student["allowed"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
                grants = grants,
            )
        }

        private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
        private fun JSONArray?.ints() = if (this == null) emptyList() else (0 until length()).map { getInt(it) }
    }
}
