plugins {
    // Kotlin comes with AGP 9; see the note in app/build.gradle.kts.
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.latch.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-model"))
}

// Storage contracts only, no implementation yet. The three decisions this module is waiting
// on all carry a dependency, so none of them is made here:
//   - Capture Inbox persistence (FR-701) with encryption at rest (NFR-204) — Room + SQLCipher,
//     or a smaller hand-rolled store
//   - the write queue (FR-806) surviving process death (NFR-302) — WorkManager, or a plain
//     table plus a boot receiver
//   - OAuth token and webhook URL storage (NFR-203) — androidx.security, or EncryptedFile
//     on the Keystore directly
