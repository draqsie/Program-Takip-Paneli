import Foundation
import UIKit
import FamilyControls
import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions

let CONSENT_VERSION = "2026-11-v1"

/// iOS ↔ Firebase. iOS arka planda sürekli çalışmaya izin vermediği için durum raporu
/// uygulama açıldığında, bildirime dokunulduğunda ve sessiz push ile uyandırıldığında gönderilir.
/// Kısıtlamanın kendisi sunucuya bağlı değildir: sistem (DeviceActivity) uygular.
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

    // MARK: Kayıt

    func enroll(code: String, name: String) async throws {
        let result = try await Functions.functions(region: "europe-west1").httpsCallable("enrollStudent").call([
            "schoolId": schoolId, "code": code, "name": name,
            "platform": "ios", "consentVersion": CONSENT_VERSION,
        ])
        guard let data = result.data as? [String: Any],
              let token = data["customToken"] as? String,
              let id = data["studentId"] as? String else {
            throw NSError(domain: "OkulIzin", code: 1, userInfo: [NSLocalizedDescriptionKey: "Sunucu yanıtı anlaşılamadı"])
        }
        try await Auth.auth().signIn(withCustomToken: token)
        UserDefaults.standard.set(id, forKey: "studentId")
        UserDefaults.standard.set(name, forKey: "studentName")
        AppState.shared.enrolled = true
        startListening()
    }

    // MARK: Dinleme ve uygulama

    func startListening() {
        guard let id = studentId, Auth.auth().currentUser != nil, listeners.isEmpty else { return }
        listeners.append(school.collection("config").document("settings").addSnapshotListener { snap, _ in
            guard let data = snap?.data() else { return }
            Task { @MainActor in self.applySettings(data) }
        })
        listeners.append(school.collection("students").document(id).addSnapshotListener { snap, _ in
            guard let data = snap?.data() else { return }
            Task { @MainActor in self.applyStudent(data) }
        })
    }

    func refresh() async {
        guard let id = studentId, Auth.auth().currentUser != nil else { return }
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
        if let c = data["className"] as? String { UserDefaults.standard.set(c, forKey: "className") }
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

    // MARK: İstek

    /// iOS uygulamanın adını bize vermez; öğrenci, ekranda sistemin gösterdiği adı kendisi yazar.
    /// Bu yüzden panelde "öğrenci beyanı" etiketiyle görünür. Onay yine de yalnızca dokunulan
    /// uygulamanın anahtarına verilir.
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

    func updateFcmToken(_ token: String) {
        guard let id = studentId else { return }
        school.collection("students").document(id).setData(["fcmToken": token], merge: true)
    }

    func report() async {
        guard let id = studentId else { return }
        let authorized = AuthorizationCenter.shared.authorizationStatus == .approved
        let ref = school.collection("students").document(id)
        let skew = UserDefaults.standard.integer(forKey: "clockSkewSec")
        try? await ref.updateData([
            "status": [
                "lastSeen": FieldValue.serverTimestamp(),
                "protection": authorized ? "ACTIVE" : "DISABLED",
                "missing": authorized ? [] : ["Ekran Süresi izni"],
                "clockSkewSec": skew,
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
