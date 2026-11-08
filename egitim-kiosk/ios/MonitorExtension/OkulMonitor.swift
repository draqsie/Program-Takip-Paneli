import DeviceActivity
import ManagedSettings

/// Device Activity Monitor uzantısı: sistem, okul saati başladığında/bittiğinde ve geçici izin
/// süresi dolduğunda bu sınıfı uyandırır (ana uygulama kapalı olsa bile).
class OkulMonitor: DeviceActivityMonitor {

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        if activity.rawValue.hasPrefix("okul-") {
            ShieldEnforcer.apply()
        }
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        if activity.rawValue.hasPrefix("okul-") {
            ManagedSettingsStore(named: .okul).clearAllSettings() // okul bitti: tablet serbest
        } else if activity.rawValue.hasPrefix("izin-") {
            ShieldEnforcer.apply() // geçici izin bitti: hâlâ okul saatindeysek yeniden engelle
        }
    }
}
