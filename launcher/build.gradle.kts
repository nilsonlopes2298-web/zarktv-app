// ZarkTV Launcher — tela inicial leve para TV Box (Android 4.4 até as mais novas).
// Sem AndroidX de propósito: APK pequeno e roda em MXQ antiga.
plugins {
    id("com.android.application") version "8.7.3"
}

val versaoNome = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
val versaoCodigo = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
val chave: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "cfd.zarktvqx.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "cfd.zarktvqx.launcher"
        minSdk = 19          // Android 4.4 (MXQ antiga)
        targetSdk = 28       // 28: deixa a box antiga e a nova funcionarem do mesmo jeito em segundo plano
        versionCode = versaoCodigo
        versionName = versaoNome
    }

    signingConfigs {
        if (chave != null) {
            create("release") {
                storeFile = file(chave)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.3.3") // QR Code do Pix na tela de bloqueio
}
