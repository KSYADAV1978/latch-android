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
include(":app")
include(":core-model")
include(":parser")
include(":recipes")
include(":data")
include(":ocr")
