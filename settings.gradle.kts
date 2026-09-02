pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "latch-android"

// :parser and :recipes are kotlin("jvm") modules on purpose. The Android SDK is not on
// their compile classpath, so FR-501 ("all parsing shall be performed on device", no
// platform coupling) and NFR-502 (separately testable) are enforced by the build itself
// rather than by review.
//
// :ocr is a module for the same kind of reason, from the other direction. ML Kit is the
// largest thing this app ships by an order of magnitude (+12.83 MB per device, FR-215), and
// putting it behind a module boundary is what makes that visible in the dependency graph
// rather than buried in :app's dependency list. Nothing above it names an ML Kit type: :app
// sees OcrReader and OcrResult, so the blast radius is one build file. It does not depend on
// :parser — it returns text, and what that text means is the parser's business.
// :wire is the §7.2 write contract, compiled. §4.1 has three independent clients — Android,
// Windows, a browser extension — sharing nothing but a Google account, and AC-07 requires all
// three to derive the same `latch.source_hash` and `latch.item_key` from the same text. §7.2
// warns where that is most likely to break: the `item_key` clause "is the one part of §7.2
// that depends on parser behaviour rather than on arithmetic over the text, and it is
// therefore where three independently written clients are most likely to drift".
//
// The specification's own remedy is a file of conformance vectors, which manages the risk by
// detecting drift after it happens. Sharing the code removes the risk instead: two clients
// running the same compiled derivation cannot disagree about a hash. That is available here
// and was not assumed to be — it is only true because :parser, :recipes and :core-model were
// kept free of Android from the first commit, so a desktop JVM can consume them unchanged.
//
// What belongs in here is exactly what determines a byte Google receives: §7.2's metadata and
// its hashes, FR-509/509a/509b's title derivation, FR-805's description, FR-1005's .ics. What
// does not is anything a client is free to do its own way — HTTP, JSON, storage, UI.
include(":wire")
include(":app")
include(":core-model")
include(":parser")
include(":recipes")
include(":data")
include(":ocr")
include(":desktop")
