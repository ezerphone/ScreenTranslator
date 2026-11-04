plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screentranslate.he"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.screentranslate.he"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "4.0"

        // Phones only: keeps the APK smaller (the translation engine has native code).
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

    // Models are memory-mapped straight from the APK, so they must be stored uncompressed.
    androidResources {
        noCompress.add("onnx")
        noCompress.add("traineddata")
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

    // Bundled English -> Hebrew translation (OPUS-MT on ONNX Runtime)
    implementation(project(":translator"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
