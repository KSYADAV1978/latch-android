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
    api(project(":core-model"))
    // §7.2's metadata appears in the payload's own signature: a delivery describes the items a
    // save wrote, and their chain id and capture time come from there.
    api(project(":wire"))
    // For the hand-written JSON and nothing else. That writer lives in :google for a reason
    // that has nothing to do with Google — `org.json` ships inside android.jar and nowhere
    // else — and moving it again to satisfy a module name would be churn. Nothing in this
    // module calls `requireGoogleEndpoint`, and a test says so by name.
    implementation(project(":google"))
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

// FR-1004, FR-1004a, FR-1004b — the one place this project deliberately contacts something
// that is not Google, and it is a module of its own for exactly that reason.
//
// AC-17 rests on the sentence "every outbound request this app composes goes through
// `ALLOWED_HOSTS`", and that sentence has to stay true of :google or the guard stops being a
// guard. FR-1004 is the single documented exception (NFR-201, AC-18), so it is sent by a
// separate client with a separate and narrower set of rules — HTTPS only, no credentials in
// the authority, no redirects, one attempt, five-second timeouts. Widening the allowlist to
// carry a user endpoint would have destroyed the property it exists to hold; putting the
// exception inside :google would have made that module's own description false.
//
// The separation is also what makes FR-1004b's "not in the write queue" structural rather
// than a matter of care: every entry in that queue drains through the Google client and would
// be refused by the guard, so a webhook cannot end up there by accident.
