import Foundation
import UIKit
import FamilyControls
import UserNotifications
import FirebaseAuth
import FirebaseFirestore

let CONSENT_VERSION = "2026-11-v1"

/// iOS ↔ Firebase. Sunucu kodu ve push sunucusu yoktur.
///
/// Önemli iOS farkı: iOS uygulamaları arka planda sürekli çalışamaz. Kısıtlamanın kendisi buna
/// bağlı değildir (sistem uygular), ama öğretmenin onayı cihaza ancak uygulama açıkken ulaşır.
/// Bu yüzden istek ekranı onayı canlı bekler; öğrenci uygulamayı kapattıysa, onaylandıktan sonra
/// Okul İzin'i bir kez açması yeterlidir.
@MainActor
final class SyncService {
    static let shared = SyncService()

    private let db = Firestore.firestore()
    private var listeners: [ListenerRegistration] = []
    private let schoolId = Bundle.main.object(forInfoDictionaryKey: "OkulSchoolID") as? String ?? ""

    private var studentId: String? { UserDefaults.standard.string(forKey: "studentId") }
    private var studentName: String { UserDefaults.standard.string(forKey: "studentName") ?? "" }
    private var className: String { UserDefaults.standard.string(forKey: "className") ?? "" }
    private var school: DocumentReference { db.collection("schools").document(schoolId) }

    // MARK: Kayıt (anonim oturum + öğretmenin kayıt kodu; kurallar kodu denetler)

    func enroll(code: String, name: String) async throws {
        let uid: String
        if let current = Auth.auth().currentUser {
            uid = current.uid
        } else {
            uid = try await Auth.auth().signInAnonymously().user.uid
        }
        let codeDoc = try await school.collection("enrollmentCodes").document(code).getDocument()
        guard codeDoc.exists, let cls = codeDoc.get("className") as? String else {
            throw NSError(domain: "OkulIzin", code: 1, userInfo: [NSLocalizedDescriptionKey: "Kayıt kodu geçersiz"])
        }
        if let exp = codeDoc.get("expiresAt") as? Timestamp, exp.dateValue() < Date() {
            throw NSError(domain: "OkulIzin", code: 2, userInfo: [NSLocalizedDescriptionKey: "Kodun süresi dolmuş"])
        }
        try await school.collection("students").document(uid).setData([
            "name": name,
            "className": cls,
            "platform": "ios",
            "enrollCode": code,
            "consentVersion": CONSENT_VERSION,
            "allowed": [String](),
            "grants": [String: Any](),
            "labels": [String: Any](),
            "inventory": [Any](),
            "status": [String: Any](),
            "enrolledAt": FieldValue.serverTimestamp(),
        ])
        UserDefaults.standard.set(uid, forKey: "studentId")
        UserDefaults.standard.set(name, forKey: "studentName")
        UserDefaults.standard.set(cls, forKey: "className")
        AppState.shared.enrolled = true
        startListening()
    }

    // MARK: Dinleme ve uygulama

    func startListening() {
        guard let id = studentId, Auth.auth().currentUser?.uid == id, listeners.isEmpty else { return }
        listeners.append(school.collection("config").document("settings").addSnapshotListener { snap, _ in
            guard let data = snap?.data() else { return }
            Task { @MainActor in self.applySettings(data) }
        })
        listeners.append(school.collection("students").document(id).addSnapshotListener { snap, _ in
            guard let data = snap?.data() else { return }
            Task { @MainActor in self.applyStudent(data) }
        })
        listeners.append(school.collection("requests").whereField("studentId", isEqualTo: id).addSnapshotListener { snap, _ in
            guard let snap else { return }
            let decided = snap.documentChanges
                .filter { $0.type != .removed && !$0.document.metadata.hasPendingWrites }
                .map { ($0.document.documentID, $0.document.data()) }
            Task { @MainActor in decided.forEach { self.notifyIfDecided(id: $0.0, data: $0.1) } }
        })
    }

    func refresh() async {
        guard let id = studentId, Auth.auth().currentUser?.uid == id else { return }
        startListening()
        if let s = try? await school.collection("config").document("settings").getDocument(source: .server).data() { applySettings(s) }
        if let d = try? await school.collection("students").document(id).getDocument(source: .server).data() { applyStudent(d) }
        await report()
    }

    private func applySettings(_ data: [String: Any]) {
        let sch = data["schedule"] as? [String: Any] ?? [:]
        func hm(_ v: Any?, _ fallback: Int) -> Int {
            guard let s = v as? String else { return fallback }
            let p = s.split(separator: ":").compactMap { Int($0) }
            return p.count == 2 ? p[0] * 60 + p[1] : fallback
        }
        let new = SchoolSchedule(
            days: (sch["days"] as? [Int]) ?? [1, 2, 3, 4, 5],
            startMin: hm(sch["start"], 510),
            endMin: hm(sch["end"], 930),
            paused: data["paused"] as? Bool ?? false
        )
        if new != SharedStore.schedule {
            SharedStore.schedule = new
            try? ShieldEnforcer.scheduleSchoolDays()
        }
        ShieldEnforcer.apply()
    }

    private func applyStudent(_ data: [String: Any]) {
        SharedStore.permanentKeys = Set((data["allowed"] as? [String] ?? []).filter { $0.hasPrefix("ios:") })
        var grants: [String: Date] = [:]
        for (key, value) in data["grants"] as? [String: Any] ?? [:] {
            guard key.hasPrefix("ios:"), let ts = value as? Timestamp else { continue }
            grants[key] = ts.dateValue()
            if ts.dateValue() > Date() { ShieldEnforcer.scheduleGrantEnd(key: key, until: ts.dateValue()) }
        }
        SharedStore.grants = grants
        ShieldEnforcer.apply()
    }

    /// Karar verilmiş ve henüz bildirilmemiş (son 6 saatteki) istek için bir kez yerel bildirim.
    private func notifyIfDecided(id: String, data: [String: Any]) {
        guard let status = data["status"] as? String, status != "PENDING",
              let decidedAt = (data["decidedAt"] as? Timestamp)?.dateValue(),
              Date().timeIntervalSince(decidedAt) < 6 * 3600 else { return }
        var notified = Set(UserDefaults.standard.stringArray(forKey: "notified") ?? [])
        guard !notified.contains(id) else { return }
        notified.insert(id)
        UserDefaults.standard.set(Array(notified.suffix(200)), forKey: "notified")

        let label = data["appLabel"] as? String ?? "Uygulama"
        let content = UNMutableNotificationContent()
        content.title = status == "APPROVED" ? "İzin verildi" : "İzin verilmedi"
        content.body = status == "APPROVED" ? "\(label) artık açılabilir." : "\(label) için isteğin reddedildi."
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "karar-\(id)", content: content, trigger: nil))
    }

    // MARK: İstek

    /// iOS uygulamanın adını bize vermez; öğrenci, ekranda sistemin gösterdiği adı kendisi yazar.
    /// Panelde "öğrenci beyanı" etiketiyle görünür. Onay yine de yalnızca dokunulan uygulamayı açar.
    func sendRequest(key: String, label: String, note: String) async throws {
        guard let id = studentId else { return }
        _ = try await school.collection("requests").addDocument(data: [
            "studentId": id,
            "studentName": studentName,
            "className": className,
            "platform": "ios",
            "appKey": key,
            "appLabel": String(label.prefix(79)),
            "labelVerified": false,
            "note": String(note.prefix(200)),
            "status": "PENDING",
            "createdAt": FieldValue.serverTimestamp(),
        ])
        SharedStore.unsentRequests.removeAll { $0 == key }
    }

    // MARK: Durum

    func report() async {
        guard let id = studentId else { return }
        let authorized = AuthorizationCenter.shared.authorizationStatus == .approved
        let ref = school.collection("students").document(id)
        try? await ref.updateData([
            "status": [
                "lastSeen": FieldValue.serverTimestamp(),
                "protection": authorized ? "ACTIVE" : "DISABLED",
                "missing": authorized ? [] : ["Ekran Süresi izni"],
                "clockSkewSec": UserDefaults.standard.integer(forKey: "clockSkewSec"),
                "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?",
                "os": "iPadOS/iOS \(UIDevice.current.systemVersion)",
            ] as [String: Any],
        ])
        // Cihaz saatinin sunucudan sapmasını ölç (bir sonraki raporda gönderilir).
        if let ts = try? await ref.getDocument(source: .server).get("status.lastSeen") as? Timestamp {
            UserDefaults.standard.set(Int(Date().timeIntervalSince(ts.dateValue())), forKey: "clockSkewSec")
        }
    }
}
