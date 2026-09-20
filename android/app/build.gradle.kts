plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableKeystorePath = System.getenv("TELEGRAMNOTIF_KEYSTORE_PATH")
val stableKeystorePassword = System.getenv("TELEGRAMNOTIF_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("TELEGRAMNOTIF_KEY_ALIAS")
val stableKeyPassword = System.getenv("TELEGRAMNOTIF_KEY_PASSWORD")

android {
    namespace = "com.dadubaiguy.telegramnotif"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dadubaiguy.telegramnotif"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (
            !stableKeystorePath.isNullOrBlank() &&
            !stableKeystorePassword.isNullOrBlank() &&
            !stableKeyAlias.isNullOrBlank() &&
            !stableKeyPassword.isNullOrBlank()
        ) {
            create("stable") {
                storeFile = file(stableKeystorePath)
                storePassword = stableKeystorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
