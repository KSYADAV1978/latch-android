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
    implementation(project(":core-model"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// FR-501 / NFR-502. This module deliberately has no Android dependency and no third-party
// dependency of any kind. Date and time handling is java.time, which is available natively
// at minSdk 26 with no desugaring — so the same code runs on the JVM under test and on the
// device in production. Adding anything here needs a written justification (NFR-501).
