// ZarkTV: ExoPlayer 2.13.2 (código aberto do Google, Apache 2.0 — veja LICENSE), o mesmo motor do XCIPTV.
// Usado só nos canais ao vivo (player/MotorClassico.kt). Essa versão não está mais nos repositórios de
// download (ficava no JCenter, que fechou), por isso o código vem junto: library/common, extractor, core,
// hls e extensions/okhttp da tag r2.13.2 de github.com/google/ExoPlayer, sem alterações.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.google.android.exoplayer2"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    api("com.google.guava:guava:27.1-android")
    implementation("androidx.annotation:annotation:1.1.0")
    api(libs.okhttp)
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("org.checkerframework:checker-qual:3.3.0")
    compileOnly("org.checkerframework:checker-compat-qual:2.5.0")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:1.3.70")
}
