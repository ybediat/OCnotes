// Configuration du dépôt de plugins et de dépendances.
//
// FAIL_ON_PROJECT_REPOS interdit à un module de déclarer ses propres dépôts :
// tout passe par ce bloc, ce qui rend les builds reproductibles.
pluginManagement {
    repositories {
        google {
            content {
                // Doubler l'antislash : dans une chaîne Kotlin, « \. » n'est
                // pas une séquence d'échappement valide. Le point doit arriver
                // échappé jusqu'au moteur d'expressions régulières.
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

rootProject.name = "OCnotes"
include(":app")
