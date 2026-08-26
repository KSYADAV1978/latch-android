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
include(":app")
include(":core-model")
include(":parser")
include(":recipes")
include(":data")
