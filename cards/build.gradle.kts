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

// FR-1204's grammar, pure Kotlin for the reason :parser is: a vCard payload is a string with a
// right answer, so every case belongs in a corpus that runs without a device. It depends on
// :core-model for CardDraft and on nothing else — in particular not on :parser, which the FR-500
// series owns and which has nothing to say about a business card.
