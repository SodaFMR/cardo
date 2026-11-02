pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\..*")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions a JDK 17 for contributors who do not have one (jvmToolchain(17)).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\..*")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        mavenCentral()
    }
}

apply(from = "gradle/fetch-artifacts.settings.gradle.kts")

rootProject.name = "cardo"

include(":brain", ":voice", ":actions", ":app", ":third-party:sherpa-onnx")
