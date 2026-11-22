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

// Standalone ENET development workspace. The Penelope application source stays in the
// repository, but this experimental branch does NOT import/build/run the :app module.
rootProject.name = "BMW-ENET-Development"
include(":enet-core")
include(":enet-tester")
