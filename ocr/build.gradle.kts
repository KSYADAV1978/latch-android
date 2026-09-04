plugins {
    // Kotlin comes with AGP 9; see the note in app/build.gradle.kts.
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.latch.ocr"
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
    // implementation, not api. Nothing in Ocr.kt's contracts exposes an ML Kit type, which
    // is the point of this module existing: :app sees OcrReader and OcrResult and never a
    // com.google.mlkit class. The blast radius stops at this build file.
    // FR-1203's QR decode. Core only: `zxing-android-embedded` is a different artifact
    // carrying a camera Activity, and this app already has the image — it needs a decoder, not
    // a capture UI. Measured at +16 KB on the release APK with no native code at all, against
    // ML Kit's bundled barcode model at roughly +5.7 MB per device; `docs/DEPENDENCIES.md`
    // carries the figures and the reasoning.
    implementation(libs.zxing.core)

    implementation(libs.mlkit.text.recognition.latin)
    implementation(libs.mlkit.text.recognition.devanagari)

    // Recognition and PDF rendering are both blocking, and readImage/readPdf are `suspend`.
    // A suspend function that blocks its caller is the trap kotlinx-coroutines was approved
    // for in :data; the same reasoning applies here.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
}

// The decisions that can be tested without a device are deliberately pure functions in
// Ocr.kt — the page cap, the downscale arithmetic, the EXIF rotation mapping and the page
// join — for the same reason the setup reducer and the parser are: a device test that has to
// be run by hand catches nothing until someone runs it.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// The NFR-501 decision behind this module, in full in docs/DEPENDENCIES.md:
//   - On-device OCR (FR-215, FR-216): ML Kit Text Recognition v2, **bundled**. +12.83 MB per
//     device, measured. The unbundled Play-services variant is +325 KB and was rejected: its
//     models download on first use, so a first image capture with no network fails outright,
//     which is a hole in NFR-301 exactly where FR-806's queue exists to prevent one.
//   - **One artifact, both scripts.** text-recognition-devanagari ships a combined
//     `gocrdevanagari_and_latin` engine and the Latn, Deva and Beng models, so it satisfies
//     FR-215's "at minimum Latin and Devanagari" in a single recognition pass. Adding the
//     Latin-only artifact beside it was measured at +8,082 bytes and would buy a second,
//     dedicated Latin recogniser — declared and never called, which is what NFR-501 exists
//     to prevent. See OcrReader's KDoc for what to do if the device pass disagrees.
//   - PDFs (FR-207): no dependency. android.graphics.pdf.PdfRenderer is platform, present
//     since API 21 against a minSdk of 26.
