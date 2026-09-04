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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Thragg"
include(":app")

// Baseline profile generator: a com.android.test module that cold-starts the
// app on a Gradle-managed emulator and records which methods run. The output
// is committed under app/src/main/generated/baselineProfiles.
include(":baselineprofile")

// Termux's terminal emulator and view, vendored verbatim under vendor/.
// See vendor/VENDOR.md for the upstream commit and the list of local patches.
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("vendor/terminal-emulator")
include(":terminal-view")
project(":terminal-view").projectDir = file("vendor/terminal-view")
 