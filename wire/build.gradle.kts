import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    // `api`, not `implementation`: these types are in this module's own signatures. A client
    // that computes an item key holds a ParseResult to compute it from.
    api(project(":core-model"))
    api(project(":parser"))

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// SRS §7.2 and §4.1. This module is the wire contract as code rather than as prose, and it
// has the same standing constraint as :parser and :recipes: pure Kotlin, no Android
// dependency, no third-party dependency. `java.time`, `java.security` and `java.text` only,
// all of them available natively at minSdk 26 with no desugaring, so one compiled artefact is
// correct on a phone and on a desktop.
//
// Adding anything here is not a dependency decision (NFR-501) so much as a portability one:
// whatever goes in has to exist on every platform §4.1 names, or the clients stop sharing it
// and the drift this module exists to prevent comes back.
