import SwiftUI
import FamilyControls
import UserNotifications

struct RootView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if state.enrolled {
                        SetupView()
                    } else {
                        EnrollView()
                    }
                }
                .padding(24)
            }
            .navigationTitle("Okul İzin")
        }
        .sheet(item: Binding(
            get: { state.requestKey.map(RequestItem.init) },
            set: { state.requestKey = $0?.id }
        )) { item in
            RequestView(key: item.id)
        }
    }
}

private struct RequestItem: Identifiable { let id: String }

// MARK: - Kayıt + açık rıza

struct EnrollView: View {
    @State private var name = ""
    @State private var code = ""
    @State private var studentOk = false
    @State private var parentOk = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Bu uygulama ne yapar?").font(.headline)
            Text("• Yalnızca okul saatlerinde, okulun izin vermediği uygulamaları kapatır. Okul saati dışında hiçbir şeyi kısıtlamaz.")
            Text("• Kapalı bir uygulamayı açtığında öğretmenine izin isteği gönderebilirsin.")
            Text("• Okula gönderilenler: adın, sınıfın, izin isteklerin ve uygulamanın çalışıp çalışmadığı.")
            Text("• Gönderilmeyenler: tabletindeki uygulamaların listesi, mesajların, fotoğrafların, konumun, internet geçmişin.")
            Text("• Ekran Süresi iznini istediğin zaman kapatabilirsin; kapatırsan okul bunu görür.")
        }
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))

        Toggle("Okudum, kabul ediyorum (öğrenci)", isOn: $studentOk)
        Toggle("Velisi olarak okudum, onaylıyorum", isOn: $parentOk)
        TextField("Ad Soyad", text: $name).textFieldStyle(.roundedBorder)
        TextField("Kayıt kodu (öğretmeninden)", text: $code)
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
        if let error { Text(error).foregroundStyle(.red) }
        Button(busy ? "Kaydediliyor…" : "Kaydol") {
            busy = true
            error = nil
            Task {
                do {
                    let clean = code.uppercased().filter { $0.isLetter || $0.isNumber }
                    try await SyncService.shared.enroll(code: clean, name: name.trimmingCharacters(in: .whitespaces))
                } catch {
                    self.error = "Kayıt olmadı: \(error.localizedDescription)"
                }
                busy = false
            }
        }
        .buttonStyle(.borderedProminent)
        .disabled(busy || !studentOk || !parentOk || name.count < 3 || code.count < 10)
    }
}

// MARK: - İzinler ve durum

struct SetupView: View {
    @ObservedObject private var center = AuthorizationCenter.shared
    @State private var error: String?

    var body: some View {
        if center.authorizationStatus == .approved {
            Label("Koruma aktif", systemImage: "checkmark.shield.fill").foregroundStyle(.green).font(.title3)
        } else {
            Text("Son adım: Ekran Süresi izni").font(.headline)
            Text("Okul saatinde izin verilmeyen uygulamaların kapatılabilmesi için bu izin gerekir. Cihaz şifren veya Face ID istenecek.")
            Button("Ekran Süresi iznini ver") {
                Task {
                    do {
                        try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
                        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                        try? ShieldEnforcer.scheduleSchoolDays()
                        await SyncService.shared.refresh()
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            if let error { Text(error).foregroundStyle(.red) }
        }

        let s = SharedStore.schedule
        VStack(alignment: .leading, spacing: 6) {
            Text(s.paused ? "Kısıtlamalar okul tarafından duraklatıldı."
                 : s.isSchoolTime(Date()) ? "Şu an okul saati: yalnızca izinli uygulamalar açılır."
                 : "Şu an okul saati değil: tablet serbest.")
                .font(.headline)
            Text(String(format: "Okul saatleri: %02d:%02d – %02d:%02d", s.startMin / 60, s.startMin % 60, s.endMin / 60, s.endMin % 60))
            Text("Kapalı bir uygulamayı açtığında çıkan ekrandaki “İzin iste” düğmesine bas.")
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - İzin isteği

struct RequestView: View {
    let key: String
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var note = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                if let token = SharedStore.token(for: key) {
                    Section("İstediğin uygulama") {
                        // Adı ve simgeyi sistem çizer; uygulamamız bu bilgiyi okuyamaz.
                        Label(token)
                    }
                }
                Section("Uygulamanın adı (yukarıda gördüğün gibi yaz)") {
                    TextField("ör. Sözlük", text: $label)
                }
                Section("Neden lazım? (isteğe bağlı)") {
                    TextField("ör. Edebiyat ödevi için", text: $note)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("İzin iste")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Vazgeç") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(busy ? "Gönderiliyor…" : "Gönder") {
                        busy = true
                        Task {
                            do {
                                try await SyncService.shared.sendRequest(key: key, label: label, note: note)
                                dismiss()
                            } catch {
                                self.error = error.localizedDescription
                            }
                            busy = false
                        }
                    }
                    .disabled(busy || label.trimmingCharacters(in: .whitespaces).count < 2)
                }
            }
        }
    }
}
