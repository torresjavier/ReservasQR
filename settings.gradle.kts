pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Solo para la librería de gráficas (MPAndroidChart); nada más se busca ahí
        maven("https://jitpack.io") {
            content { includeGroup("com.github.PhilJay") }
        }
    }
}

rootProject.name = "ReservasQR"
include(":app")
