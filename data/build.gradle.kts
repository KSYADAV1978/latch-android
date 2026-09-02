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

    // `api`: §7.2's metadata types are in this module's own signatures — an insert takes a
    // RemoteMetadata and a duplicate probe returns one. See settings.gradle.kts for why the
    // contract is a shared module rather than a shared document.
    api(project(":wire"))

    // `api` for the same reason: the repositories in this module hand a GoogleApi to :app.
    // The client lives in its own module so both clients of §4.1 compose identical requests —
    // FR-803's paging query above all. See google/build.gradle.kts.
    api(project(":google"))

    // implementation, not api: the storage contracts are `suspend` and nothing more, which
    // the stdlib already covers. Only the implementations need a dispatcher to move to.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    // org.json ships in android.jar, but the unit-test stub of it throws. See
    // docs/DEPENDENCIES.md — test-only, no APK impact.
    testImplementation(libs.org.json)
}

// The stored-record format is pure Kotlin, so its round trip is a plain JVM test — the
// same reason AC-15 and AC-16 are unit tests in :app. The Keystore half needs a device
// and has no test here.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// Where the dependency decisions landed, in full in docs/DEPENDENCIES.md:
//   - Account defaults (FR-110): persisted here, in EncryptedPreferences.kt. AES-GCM under
//     an Android Keystore key, written to app-private preferences. No dependency: this is
//     what androidx.security-crypto would have done, and that library is deprecated in
//     favour of the platform APIs it wraps.
//   - Storage work off the caller's thread: kotlinx-coroutines-core, approved. A `suspend`
//     function that blocks is a trap, and this module needs a dispatcher to keep the
//     promise its own contracts make. Already on the app classpath transitively, so the
//     APK is unchanged.
//   - OAuth token and webhook URL storage (NFR-203): unbuilt, but no longer an open
//     question — SecretStore reuses KeystoreCipher rather than minting a second scheme.
//   - Capture Inbox persistence (FR-701): Room is deferred — it needs KSP, which AGP 9's
//     built-in Kotlin does not support. Hand-rolled SQLite is the interim if the Inbox
//     lands first.
//   - Encryption at rest (NFR-204): no library. Platform encryption at minSdk 26 plus
//     allowBackup=false satisfies it; the reading is recorded against NFR-204 in the SRS.
//   - The write queue surviving process death (FR-806, NFR-302): WorkManager, approved.
