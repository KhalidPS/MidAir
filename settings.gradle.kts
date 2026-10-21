pluginManagement {
    includeBuild("build-logic")
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

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "TransferingFileByAir"
include(":app")
include(":baselineprofile")
include(":shared")
include(":core:domain")
include(":core:presentation")
include(":core:designsystem")
include(":core:transfer:domain")
include(":core:transfer:data")
include(":core:transfer:nearby")
include(":feature:pairing:presentation")
include(":feature:shelf:domain")
include(":feature:shelf:data")
include(":feature:shelf:presentation")
include(":feature:simulator")
 