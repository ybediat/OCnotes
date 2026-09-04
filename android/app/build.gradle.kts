plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.opennote"
    compileSdk = 35

    // Le NDK sert à gomobile, qui construit le cœur Go *avant* Gradle : ce
    // module n'a aucune source native. La révision de référence est celle du
    // script Linux et de la CI.
    //
    // Elle reste surchargeable, parce qu'un empaqueteur — F-Droid en
    // particulier — fournit sa propre image et n'a aucune raison de porter
    // exactement celle-ci. Sans cette porte, AGP exigerait la révision
    // épinglée et ferait échouer un build par ailleurs sain :
    //
    //   ./gradlew -Popennote.ndkVersion=27.2.12479018 assembleRelease
    //
    // `scripts/build-android-linux.sh` la passe automatiquement, avec la
    // révision qu'il a réellement trouvée dans le SDK.
    ndkVersion = providers.gradleProperty("opennote.ndkVersion").getOrElse("27.3.13750724")

    defaultConfig {
        applicationId = "eu.opennote"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"

        // Le .aar de gomobile n'embarque que les ABI passées au bind : cette
        // liste et la commande `gomobile bind` bougent ensemble. Une ABI
        // ajoutée ici seule donne un APK qui plante au premier appel JNI ;
        // ajoutée au bind seul, un `.so` embarqué que rien ne charge.
        //
        // arm64-v8a couvre les appareils récents. x86_64 couvre les
        // émulateurs, ChromeOS et Waydroid — et se vérifie sur poste de
        // développement, puisque c'est la même GOARCH=amd64 que celle sur
        // laquelle tourne `go test ./...`.
        //
        // armeabi-v7a est absente faute de pouvoir la tester : les images
        // système ARM 32 bits s'arrêtent à l'API 25, et minSdk vaut 26. Le
        // cœur Go, lui, y est propre — ni sync/atomic, ni unsafe, ni cgo,
        // toutes les tailles en int64 — et `GOARCH=386 go test ./... -short`
        // passe. Il ne manque qu'un appareil pour l'éprouver.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
