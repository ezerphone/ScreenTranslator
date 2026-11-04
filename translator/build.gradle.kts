import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM module: the English -> Hebrew translator (ONNX Runtime + SentencePiece).
// Kept free of Android APIs so it can be tested on a normal JVM in CI.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The app supplies onnxruntime-android at runtime (same Java API).
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("modelDir", providers.gradleProperty("modelDir").getOrElse(""))
    systemProperty("referenceDir", providers.gradleProperty("referenceDir").getOrElse(""))
    maxHeapSize = "3g"
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
