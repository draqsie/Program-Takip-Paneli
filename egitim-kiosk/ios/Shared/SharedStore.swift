// Ana uygulama ve üç uzantı (Monitor, ShieldConfiguration, ShieldAction) bu dosyayı paylaşır.
// Uzantılar Firebase'e erişmez; aralarındaki tek köprü App Group'taki UserDefaults'tur.

import Foundation
import CryptoKit
import ManagedSettings

/// Panel ile aynı biçim: days 1=Pazartesi … 7=Pazar, dakikalar gece yarısından itibaren.
struct SchoolSchedule: Codable, Equatable {
    var days: [Int] = [1, 2, 3, 4, 5]
    var startMin: Int = 8 * 60 + 30
    var endMin: Int = 15 * 60 + 30
    var paused: Bool = false

    func isSchoolTime(_ date: Date) -> Bool {
        let c = Calendar.istanbul.dateComponents([.weekday, .hour, .minute], from: date)
        guard let weekday = c.weekday, let hour = c.hour, let minute = c.minute else { return false }
        let isoDay = ((weekday + 5) % 7) + 1          // Calendar: 1=Pazar → ISO: 1=Pazartesi
        let m = hour * 60 + minute
        return !paused && days.contains(isoDay) && m >= startMin && m < endMin
    }
}

extension Calendar {
    static var istanbul: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Istanbul")!
        return c
    }
}

enum SharedStore {
    static let groupID = "group.tr.okul.izin"
    private static var defaults: UserDefaults { UserDefaults(suiteName: groupID)! }

    // MARK: Uygulama anahtarları
    //
    // Apple, hangi uygulamanın engellendiğini bize yalnızca opak bir ApplicationToken olarak verir;
    // paket adı ya da uygulama adı alınamaz. Token'ın özetini "appKey" olarak sunucuya göndeririz.
    // Sunucu bu anahtarı onaylar, cihaz anahtarı tekrar token'a çevirir.

    static func key(for token: ApplicationToken) -> String {
        let data = (try? JSONEncoder().encode(token)) ?? Data()
        let digest = SHA256.hash(data: data)
        return "ios:" + digest.prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    @discardableResult
    static func remember(_ token: ApplicationToken) -> String {
        let k = key(for: token)
        var map = tokenMap
        map[k] = try? JSONEncoder().encode(token)
        tokenMap = map
        return k
    }

    static func token(for key: String) -> ApplicationToken? {
        guard let data = tokenMap[key] else { return nil }
        return try? JSONDecoder().decode(ApplicationToken.self, from: data)
    }

    private static var tokenMap: [String: Data] {
        get { defaults.dictionary(forKey: "tokenMap") as? [String: Data] ?? [:] }
        set { defaults.set(newValue, forKey: "tokenMap") }
    }

    // MARK: Sunucudan gelen izinler

    static var permanentKeys: Set<String> {
        get { Set(defaults.stringArray(forKey: "permanentKeys") ?? []) }
        set { defaults.set(Array(newValue), forKey: "permanentKeys") }
    }

    static var grants: [String: Date] {
        get { defaults.dictionary(forKey: "grants") as? [String: Date] ?? [:] }
        set { defaults.set(newValue, forKey: "grants") }
    }

    static var schedule: SchoolSchedule {
        get {
            guard let d = defaults.data(forKey: "schedule"), let s = try? JSONDecoder().decode(SchoolSchedule.self, from: d) else { return SchoolSchedule() }
            return s
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: "schedule") }
    }

    /// Okul saatinde açılabilecek uygulamalar (kalıcı + süresi dolmamış geçici izinler).
    static func allowedTokens(at date: Date) -> Set<ApplicationToken> {
        let temp = grants.filter { $0.value > date }.map(\.key)
        return Set(permanentKeys.union(temp).compactMap(token(for:)))
    }

    // MARK: Engel ekranında "İzin iste"ye basılmış ama henüz gönderilmemiş istekler

    static var unsentRequests: [String] {
        get { defaults.stringArray(forKey: "unsentRequests") ?? [] }
        set { defaults.set(newValue, forKey: "unsentRequests") }
    }
}
