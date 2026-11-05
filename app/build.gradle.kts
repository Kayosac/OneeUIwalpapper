plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kayo.infowall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kayo.infowall"
        minSdk = 29
        // 34: без принудительного edge-to-edge в экране настроек
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Постоянная подпись (чтобы новые сборки ставились поверх старых)
    // включается, только если CI положил ключ: INFOWALL_KEYSTORE — путь к .keystore.
    // Без ключа сборка подписывается стандартным debug-ключом.
    val ksPath = System.getenv("INFOWALL_KEYSTORE")
    val stable = ksPath != null && file(ksPath).exists()
    if (stable) {
        signingConfigs {
            create("infowall") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("INFOWALL_KEY_PASS") ?: "infowall"
                keyAlias = System.getenv("INFOWALL_KEY_ALIAS") ?: "infowall"
                keyPassword = System.getenv("INFOWALL_KEY_PASS") ?: "infowall"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (stable) "infowall" else "debug")
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

// Без внешних зависимостей: только Android SDK + Kotlin stdlib
