// Projeto separado: tela inicial (launcher) ZarkTV para Android TV / Google TV / TV Box.
// Não faz parte do app ZarkTV; compila sozinho com: ./gradlew -p launcher assembleRelease
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ZarkLauncher"
