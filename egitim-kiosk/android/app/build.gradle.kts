plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") // app/google-services.json gerekir (Firebase konsolundan)
}

android {
    namespace = "tr.okul.kiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.okul.kiosk"
        minSdk = 28          // Android 9: setLockTaskFeatures ve modern DPC API'leri için alt sınır
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEV_SCHOOL_ID", "\"${findProperty("kiosk.devSchoolId") ?: ""}\"")
        buildConfigField("String", "DEV_ENROLL_TOKEN", "\"${findProperty("kiosk.devEnrollToken") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // AndroidX + Compose
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0") // Task.await()

    // Firebase (canlı politika, komut kanalı, push, dosya)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-storage")

    // İşaretlenmiş PDF'i "teslim" için dışa aktarma (Apache PDFBox'ın Android portu, Apache-2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
