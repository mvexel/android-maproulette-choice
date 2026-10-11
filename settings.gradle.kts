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
        // The MapRoulette Mobile SDK (Kotlin) is served by JitPack from mvexel/maproulette-mobile-sdk.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mvexel") }
        }
    }
}
rootProject.name = "street-tally-android"
include(":app")
