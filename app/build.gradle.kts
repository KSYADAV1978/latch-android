import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android must NOT be applied
    // here — it is an error. The Compose compiler plugin is still applied separately.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * FR-1108's first release gate: a signing configuration.
 *
 * **Read from a file that is not in the repository.** `keystore.properties` is git-ignored and
 * names the keystore's path and its passwords; `keystore.properties.example` beside it is
 * committed and shows the four keys with no values. A signing config with the passwords inline
 * would put the upload key's credentials in the history, where they cannot be removed.
 *
 * **Its absence is not an error, and that is deliberate.** Every debug build, every
 * `./gradlew build` and the launch canary have to work on a machine that has never seen a
 * keystore — including a fresh clone, which NFR-503 requires to build. So the config exists only
 * when the file does, and a release build without it is unsigned rather than failed. What makes
 * that safe is `docs/RELEASE.md`: shipping an unsigned artifact is caught by the gate, not by
 * the build.
 */
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "com.latch.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.latch.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // For the launch canary in src/androidTest. Instrumented tests are JUnit4 — the
        // runner requires it — while the JVM tests here run on the JUnit 5 platform. The
        // two do not meet: they are different source sets and different Gradle tasks.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // NFR-402: English (India) at v1.0. NFR-403 adds "hi" here.
        localeFilters += listOf("en")
    }

    signingConfigs {
        // Only where the file exists. See `keystoreProperties` above for why its absence is not
        // an error, and docs/RELEASE.md for what a release build actually needs.
        keystoreProperties?.let { properties ->
            create("release") {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                // v1 as well as v2/v3: minSdk is 26 and APK Signature Scheme v2 covers it, but
                // the JAR signature costs nothing and is what an older verification tool reads.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    /**
     * FR-1108's second gate, and the condition NFR-103's per-device reading rests on.
     *
     * Bundled ML Kit is one native library per ABI — arm64-v8a alone is 11 MB — so a universal
     * APK carries four copies of a thing any device needs one of. That is the difference between
     * 13.97 MB per device and 42.58 MB as a universal APK, and NFR-103's budget is 40 MB. The
     * per-device reading is only true if Play actually delivers a split, which is only true if
     * the shipping artifact is an App Bundle with these splits on.
     *
     * All three are on. ABI is the one that matters for the budget; density and language are on
     * because there is no reason for them not to be and turning one off later should be a
     * decision someone takes rather than a default nobody read.
     */
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }

    buildTypes {
        release {
            // NFR-103: APK under 40 MB. Shrinking is on from the start so the budget is
            // measured against something real rather than checked at the end.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null where no keystore.properties exists, which leaves the build unsigned rather
            // than failing it — see `keystoreProperties`.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        // GoogleAuthClient logs the Play services status code on debug builds only. Status
        // 10 (DEVELOPER_ERROR) carries no message and is the likeliest failure during
        // bring-up; without this it reaches the developer as nothing at all.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":parser"))
    implementation(project(":recipes"))
    implementation(project(":data"))
    implementation(project(":ocr"))

    // The OAuth grant only (FR-002). Every Google call is hand-written REST on the token
    // this returns; see docs/DEPENDENCIES.md for why the library stops there.
    implementation(libs.play.services.auth)

    // FR-806's queue: persisted work, a connectivity constraint, survival across reboot.
    // NFR-302 is its contract; see docs/DEPENDENCIES.md.
    implementation(libs.androidx.work)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit5)
    // Virtual time for CaptureSaverTest, so FR-807's undo window is exercised in
    // milliseconds. testImplementation only; see docs/DEPENDENCIES.md.
    testImplementation(libs.kotlinx.coroutines.test)

    // The launch canary, and deliberately nothing more — no Espresso, no Compose UI test.
    // See docs/DEPENDENCIES.md. These build the androidTest APK and never the app's.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// The setup state machine (FR-101 to FR-110) has no Android types, so AC-15 and AC-16 are
// plain JVM tests here rather than instrumented ones — the same reason :parser runs without
// a device.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
