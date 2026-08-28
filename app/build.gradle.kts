plugins {
    // AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android must NOT be applied
    // here — it is an error. The Compose compiler plugin is still applied separately.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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

    buildTypes {
        release {
            // NFR-103: APK under 40 MB. Shrinking is on from the start so the budget is
            // measured against something real rather than checked at the end.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
