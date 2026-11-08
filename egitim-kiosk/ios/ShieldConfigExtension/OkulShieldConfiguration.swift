import ManagedSettings
import ManagedSettingsUI
import UIKit

/// Engellenen uygulama açıldığında sistemin gösterdiği ekranın görünümü.
class OkulShieldConfiguration: ShieldConfigurationDataSource {

    override func configuration(shielding application: Application) -> ShieldConfiguration {
        make(appName: application.localizedDisplayName)
    }

    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration {
        make(appName: application.localizedDisplayName)
    }

    private func make(appName: String?) -> ShieldConfiguration {
        ShieldConfiguration(
            backgroundBlurStyle: .systemThickMaterialDark,
            title: .init(text: "\(appName ?? "Bu uygulama") okul saatinde kapalı", color: .white),
            subtitle: .init(text: "Derste kullanman gerekiyorsa öğretmeninden izin isteyebilirsin.", color: .lightGray),
            primaryButtonLabel: .init(text: "İzin iste", color: .white),
            primaryButtonBackgroundColor: .systemBlue,
            secondaryButtonLabel: .init(text: "Kapat", color: .white)
        )
    }
}
