plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.matija.anrufreminder"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.matija.anrufreminder"
        minSdk = 26
        targetSdk = 35
        // Im GitHub-Build wird die Laufnummer übergeben, damit jede neue APK als Update installierbar ist.
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // Der Signaturschlüssel liegt nicht im Repo. Der GitHub-Build legt ihn aus den Secrets
    // KEYSTORE_BASE64 / KEYSTORE_PASSWORD an; lokal ohne Schlüssel entsteht eine unsignierte Release-APK.
    val keystore = file("release.keystore")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val canSign = keystore.exists() && !keystorePassword.isNullOrEmpty()
    signingConfigs {
        if (canSign) create("release") {
            storeFile = keystore
            storePassword = keystorePassword
            keyAlias = "reminder"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
