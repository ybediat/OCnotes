plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.opennote"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.opennote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

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
        // Une clé absente d'une traduction, ou présente dans une traduction
        // sans exister dans `values/`, est une erreur et non un
        // avertissement : c'est le seul contrôle automatique une fois un
        // `values-<langue>/` créé, et un avertissement ne se voit pas.
        //
        // Tant qu'il n'y a qu'une langue, ces règles ne signalent rien. Elles
        // sont posées maintenant pour être en place le jour où elles servent.
        error.addAll(listOf("MissingTranslation", "ExtraTranslation"))
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
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
}
