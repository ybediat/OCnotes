// Racine du projet Gradle : on ne fait qu'y déclarer les plugins, sans les
// appliquer. Chaque module choisit ceux dont il a besoin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
