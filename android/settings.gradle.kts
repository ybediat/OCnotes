// Configuration du dépôt de plugins et de dépendances.
//
// FAIL_ON_PROJECT_REPOS interdit à un module de déclarer ses propres dépôts :
// tout passe par ce bloc, ce qui rend les builds reproductibles.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\.android.*")
                includeGroupByRegex("com\.google.*")
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

rootProject.name = "OpenNote"
include(":app")
