plugins {
    // AGP 9 compiles Kotlin itself; the Compose compiler plugin follows the Kotlin version.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
