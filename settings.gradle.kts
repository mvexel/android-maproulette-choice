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
    }
}
rootProject.name = "street-tally-android"
include(":app")
// The MapRoulette Mobile SDK (Kotlin) builds from the `sdk` submodule, pinned to an SDK commit.
// It substitutes com.github.mvexel:maproulette-mobile-sdk. Run `git submodule update --init` first.
includeBuild("sdk/kotlin")
