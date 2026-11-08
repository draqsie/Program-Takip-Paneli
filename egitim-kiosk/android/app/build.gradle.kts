plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") // app/google-services.json gerekir (Firebase konsolundan)
}

android {
    namespace = "tr.okul.izin"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.okul.izin"
        minSdk = 26          // java.time ve TYPE_APPLICATION_OVERLAY için alt sınır
        targetSdk = 35
        // GitHub Actions bunları etiketten ve çalıştırma numarasından verir (bkz. .github/workflows/okul-izin-android.yml).
        versionCode = (System.getenv("OKUL_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("OKUL_VERSION_NAME") ?: "0.1.0"
        buildConfigField("String", "SCHOOL_ID", "\"${findProperty("okul.schoolId") ?: ""}\"")
        // Güncelleme kaynağı: GitHub'daki son sürüm (API değil doğrudan indirme; oran sınırı yok).
        buildConfigField("String", "UPDATE_BASE_URL", "\"${findProperty("okul.updateBaseUrl") ?: ""}\"")
    }

    // Tüm sürümler AYNI anahtarla imzalanmalı, yoksa tabletler güncellemeyi kabul etmez.
    // Anahtar yalnızca GitHub Secrets'ta durur; repoya konmaz.
    val keystorePath = System.getenv("OKUL_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("okul") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OKUL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OKUL_KEY_ALIAS")
                keyPassword = System.getenv("OKUL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystorePath != null) signingConfig = signingConfigs.getByName("okul")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android'in org.json'u birim testte boş taslaktır
}
