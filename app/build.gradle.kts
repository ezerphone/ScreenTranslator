plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screentranslate.he"
    compileSdk = 35

    defaultConfig {
        // Fixed for good: a different id would install as a separate app.
        applicationId = "com.screentranslate.he.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "6.0"

        // Phones only: keeps the APK smaller (OCR has native code).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // A fixed key stored in the project, so every build can be installed
    // over the previous one without uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Stored uncompressed: OCR data is opened straight from the APK; the language pack is
    // already compressed (and its size marks its version).
    androidResources {
        noCompress.add("traineddata")
        noCompress.add("zip")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // On-device OCR: ML Kit Chinese model (reads Chinese and Latin), Tesseract for Arabic
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // In-app camera (photos stay in memory)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Translation: Google ML Kit; its models come inside the APK (see LanguagePack)
    implementation("com.google.mlkit:translate:17.0.3")
}
