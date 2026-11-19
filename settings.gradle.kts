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

rootProject.name = "VidMateX"

include(":app")
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:network")
include(":source:adapters")
include(":media:processor")
include(":feature:home")
include(":feature:downloads")
include(":feature:subtitles")
include(":feature:history")
include(":feature:settings")
