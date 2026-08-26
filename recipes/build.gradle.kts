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
}

// Pure Kotlin for the same reason as :parser — working-day arithmetic (FR-604/605) is the
// other half of the logic that AC-06 pins down, and it should be testable without a device.
// It does not depend on :parser: the two exchange core-model types only.
