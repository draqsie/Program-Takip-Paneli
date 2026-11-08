import Foundation
import ManagedSettings
import DeviceActivity

extension ManagedSettingsStore.Name {
    static let okul = Self("okul")
}

extension DeviceActivityName {
    static func okulGunu(_ isoDay: Int) -> Self { Self("okul-\(isoDay)") }
    static func izinBitisi(_ key: String) -> Self { Self("izin-\(key)") }
}

/// Kısıtlamaları uygular. Hem ana uygulama hem Monitor uzantısı çağırır.
enum ShieldEnforcer {

    /// Okul saatindeysek: izinliler hariç TÜM uygulamaları engelle. Değilsek: her şeyi kaldır.
    static func apply(now: Date = Date()) {
        let store = ManagedSettingsStore(named: .okul)
        guard SharedStore.schedule.isSchoolTime(now) else {
            store.clearAllSettings()
            return
        }
        store.shield.applicationCategories = .all(except: SharedStore.allowedTokens(at: now))
        // Okul saatinde uygulama silmeyi engelle (Ekran Süresi iznini kapatmayı engellemez;
        // o durum panele "Koruma kapatılmış" olarak yansır).
        store.application.denyAppRemoval = true
    }

    /// Okul günleri için haftalık tekrar eden izleme aralıkları kurar. Uygulama kapalıyken de
    /// sistem, aralık başında/sonunda Monitor uzantısını uyandırır.
    static func scheduleSchoolDays() throws {
        let center = DeviceActivityCenter()
        center.stopMonitoring(center.activities.filter { $0.rawValue.hasPrefix("okul-") })
        let s = SharedStore.schedule
        for day in s.days {
            let weekday = day % 7 + 1 // ISO (1=Pzt) → Calendar (1=Paz)
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: s.startMin / 60, minute: s.startMin % 60, weekday: weekday),
                intervalEnd: DateComponents(hour: s.endMin / 60, minute: s.endMin % 60, weekday: weekday),
                repeats: true
            )
            try center.startMonitoring(.okulGunu(day), during: schedule)
        }
    }

    /// Geçici izin bittiğinde (uygulama kapalı olsa bile) kısıtlamanın geri gelmesi için tek seferlik aralık.
    /// DeviceActivity aralıkları en az 15 dakika olmalıdır.
    static func scheduleGrantEnd(key: String, until: Date) {
        let cal = Calendar.istanbul
        let fields: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        let end = max(until, Date().addingTimeInterval(15 * 60))
        let schedule = DeviceActivitySchedule(
            intervalStart: cal.dateComponents(fields, from: Date()),
            intervalEnd: cal.dateComponents(fields, from: end),
            repeats: false
        )
        try? DeviceActivityCenter().startMonitoring(.izinBitisi(key), during: schedule)
    }
}
