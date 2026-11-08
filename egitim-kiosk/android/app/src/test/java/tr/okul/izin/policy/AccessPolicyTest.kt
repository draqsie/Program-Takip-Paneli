package tr.okul.izin.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AccessPolicyTest {
    private val ist = ZoneId.of("Europe/Istanbul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ist).toInstant().toEpochMilli()

    @Test fun okulSaati() {
        val s = SchoolSchedule()
        assertTrue(s.isSchoolTime(at(2026, 11, 9, 8, 30)))   // Pazartesi açılış
        assertTrue(s.isSchoolTime(at(2026, 11, 9, 15, 29)))
        assertFalse(s.isSchoolTime(at(2026, 11, 9, 15, 30)))  // bitiş dakikası hariç
        assertFalse(s.isSchoolTime(at(2026, 11, 9, 8, 29)))
        assertFalse(s.isSchoolTime(at(2026, 11, 8, 10, 0)))   // Pazar
        assertEquals(510, SchoolSchedule.parseHm("08:30", 0))
        assertEquals(7, SchoolSchedule.parseHm("bozuk", 7))
    }

    @Test fun izinKararlari() {
        val now = at(2026, 11, 9, 10, 0)
        val p = AccessPolicy.fromMaps(
            mapOf(
                "schedule" to mapOf("days" to listOf(1L, 2L), "start" to "09:00", "end" to "12:00"),
                "defaultAllowedAndroid" to listOf("org.geogebra.android"),
            ),
            mapOf("allowed" to listOf("com.sozluk")),
            mapOf("com.video" to now + 60_000, "com.oyun" to now - 1),
        )
        assertTrue(p.isRestrictedNow(now))
        assertTrue(p.isAllowed("org.geogebra.android", now))   // herkese açık
        assertTrue(p.isAllowed("com.sozluk", now))             // kalıcı izin
        assertTrue(p.isAllowed("com.video", now))              // geçici izin sürüyor
        assertFalse(p.isAllowed("com.oyun", now))              // geçici izin bitmiş
        assertFalse(p.isAllowed("com.instagram.android", now))
        assertFalse(p.copy(paused = true).isRestrictedNow(now))
        assertFalse(AccessPolicy().isRestrictedNow(now))       // kayıtsız cihaz kısıtlanmaz
        assertEquals(p, AccessPolicy.fromJson(p.toJson()))     // çevrimdışı önbellek gidiş-dönüş
    }
}
