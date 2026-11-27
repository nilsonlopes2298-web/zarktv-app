plugins {
    id("com.android.application") version "8.7.3"
}

val versao = (findProperty("appVersionName") as String?) ?: "1.0.0"
val codigo = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "cfd.zarktvqx.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "cfd.zarktvqx.launcher"
        minSdk = 21
        targetSdk = 34
        versionCode = codigo
        versionName = versao
    }

    signingConfigs {
        create("release") {
            val caminho = System.getenv("SIGNING_KEYSTORE_PATH")
            if (caminho != null && file(caminho).exists()) {
                storeFile = file(caminho)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val chave = signingConfigs.getByName("release")
            signingConfig = if (chave.storeFile != null) chave else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
