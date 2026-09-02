import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.latch.desktop.MainKt")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":parser"))
    implementation(project(":recipes"))
    implementation(project(":wire"))

    // FR-800's write path and AC-17's endpoint guard, shared with the Android client so the
    // two compose identical requests. See google/build.gradle.kts.
    implementation(project(":google"))
    implementation(libs.kotlinx.coroutines.core)

    // junit5 rather than the plain artifact: an environment-dependent test must report as
    // SKIPPED and not as passed. `CLAUDE.md` records why — an inconclusive run looks exactly
    // like a pass in a log — and `Assumptions` is what makes the distinction visible.
    testImplementation(libs.kotlin.test.junit5)
    // `runTest` for the save path, which is suspend all the way down. testImplementation
    // only, exactly as :app declares it; see docs/DEPENDENCIES.md.
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// FR-300 series. The Windows client, and it has the same dependency stance as every other
// module here: nothing third-party. The three things Windows is needed for — FR-302's hotkey,
// FR-303's OCR, NFR-203's encryption at rest — are reached through the PowerShell WinRT
// projection and the C# compiler that ships inside Windows, so there is no SDK to install and
// no library to justify. The UI is Swing, which is in the JDK; Compose Desktop would be an
// NFR-501 decision carrying tens of megabytes of Skia natives into NFR-103's 80 MB budget,
// and against that FR-304 wants full keyboard operability and NFR-401 wants a screen reader,
// which the Java Access Bridge already gives.
