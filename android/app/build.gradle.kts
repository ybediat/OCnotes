plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.opennote"
    compileSdk = 35
    // Doit rester aligné sur le script Linux, la CI et le build local :
    // gomobile utilise ce NDK pour construire le cœur Go avant Gradle.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "eu.opennote"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        // Le .aar de gomobile n'embarque que les ABI passées au bind.
        // arm64-v8a couvre tous les appareils récents ; ajouter armeabi-v7a
        // ici ET dans la commande gomobile pour les vieux appareils 32 bits.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"

            // Génère `en-XA` (accentuée et allongée) et `ar-XB` (écrite de
            // droite à gauche) à partir de `values/`, sans qu'aucun
            // `values-<langue>/` soit à maintenir. C'est ce qui rend visible
            // un texte resté en dur : il s'affiche en français lisible au
            // milieu de ce qui est traduit.
            //
            // Sur cette ROM Xiaomi, le sélecteur de langue système n'expose
            // pas les pseudo-langues. On les applique à l'application seule :
            //   adb shell cmd locale set-app-locales eu.opennote.debug \
            //       --locales en-XA
            // et on revient avec `--locales ""`.
            isPseudoLocalesEnabled = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Une clé présente dans une traduction mais absente de `values/` est
        // une erreur : elle signale une clé supprimée de la référence que la
        // traduction traîne encore. Le cas est rare, il ne se produit jamais
        // par accident, et la règle ne fait aucun bruit au quotidien.
        error.add("ExtraTranslation")

        // `MissingTranslation` est EN PAUSE, le temps que l'interface se
        // stabilise. L'anglais et l'espagnol ont été traduits en cours de
        // route, pour éprouver le dispositif ; du coup chaque chaîne ajoutée
        // à `values/` faisait échouer `lintDebug` jusqu'à ce que les deux
        // langues suivent.
        //
        // Le rappel n'apprenait rien — on sait que la traduction est en
        // retard, c'est le principe même de traduire à la fin — et un
        // avertissement qu'on s'habitue à ignorer finit par masquer ceux qui
        // comptent. Une chaîne sans traduction retombe sur le français, ce
        // qu'Android fait de toute façon.
        //
        // À RETIRER en rouvrant le chantier de traduction : cette seule ligne
        // rétablit l'inventaire complet des manques, langue par langue, et
        // c'est exactement l'outil qu'il faudra à ce moment-là.
        disable.add("MissingTranslation")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ------------------------------------------------------------------
    // Le cœur métier Go, lié par gomobile bind.
    //
    // Le fichier n'est PAS versionné (voir .gitignore : *.aar). Il se
    // régénère depuis la racine du dépôt avec :
    //
    //   gomobile bind -target=android -androidapi 26 \
    //       -o android/app/libs/opennote.aar ./mobile
    //
    // Tant que ce fichier n'existe pas, la compilation échoue sur des
    // symboles `mobile.App` / `mobile.Mobile` introuvables : c'est attendu.
    // ------------------------------------------------------------------
    implementation(files("libs/opennote.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
}
