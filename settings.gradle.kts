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

rootProject.name = "Rajdex-streamer"
include(":app", ":ausbc", ":ausbc-uvc")
project(":ausbc").projectDir = file("third_party/AndroidUSBCamera/libausbc")
project(":ausbc-uvc").projectDir = file("third_party/AndroidUSBCamera/libuvc")
