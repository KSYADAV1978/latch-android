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

// Storage contracts only, no implementation yet. Where the dependency decisions landed, in
// full in docs/DEPENDENCIES.md:
//   - Capture Inbox persistence (FR-701): Room is deferred — it needs KSP, which AGP 9's
//     built-in Kotlin does not support. Hand-rolled SQLite is the interim if the Inbox
//     lands first.
//   - Encryption at rest (NFR-204): no library. Platform encryption at minSdk 26 plus
//     allowBackup=false satisfies it; the reading is recorded against NFR-204 in the SRS.
//   - The write queue surviving process death (FR-806, NFR-302): WorkManager, approved.
//   - OAuth token and webhook URL storage (NFR-203): still open — androidx.security, or
//     EncryptedFile on the Keystore directly.
