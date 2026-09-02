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
    // `api`: §7.2's metadata types appear in this module's own signatures — an insert takes a
    // RemoteMetadata and a duplicate probe returns one.
    api(project(":wire"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// FR-800 series and §4.1. Every request this project makes to Google is composed here, for
// both clients, and that is the point of the module rather than a convenience.
//
// FR-803's duplicate query is the argument. It was written once, was wrong in a way no test
// could see — a filtered `events.list` returns an empty page carrying a `nextPageToken`, so
// asking for one result almost never finds the match — and it wrote duplicates into a real
// calendar for five days before the cause was found. A second client with its own copy of
// that query is a second chance at exactly that defect, and AC-07 requires the two to find
// each other's items. One implementation cannot disagree with itself.
//
// Pure Kotlin, as :parser and :wire are, so a desktop JVM consumes it unchanged. The JSON is
// hand-written here for the same reason the REST is: `org.json` ships inside android.jar and
// nowhere else, so sharing the client meant either a dependency or a few hundred lines. See
// json/Json.kt.
