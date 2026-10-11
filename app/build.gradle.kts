plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time configuration: public identifiers and URLs, not credentials. Override with
// -P<name>=<value>, e.g. ./gradlew assembleStageDebug -PmaprouletteAllowLiveWrites=true
fun prop(name: String, default: String) = providers.gradleProperty(name).orElse(default).get()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The same public OAuth client and callback as the iOS app (registered on dev and stage).
val oauthClientId = prop("maprouletteOAuthClientId", "maproulette-ios-choice")
val callbackScheme = prop("maprouletteOAuthCallbackScheme", "lol.osm.maproulette.choice")
// true lets stage and prod (which edit the real OpenStreetMap) receive answers. Off by default.
val allowLiveWrites = prop("maprouletteAllowLiveWrites", "false").toBoolean()

android {
    namespace = "lol.osm.streettally"
    compileSdk = 36
    defaultConfig {
        applicationId = "lol.osm.maproulette.choice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appAuthRedirectScheme"] = callbackScheme
        buildConfigField("String", "MAPROULETTE_OAUTH_CLIENT_ID", quoted(oauthClientId))
        buildConfigField("String", "MAPROULETTE_OAUTH_CALLBACK_SCHEME", quoted(callbackScheme))
        buildConfigField("boolean", "MAPROULETTE_ALLOW_LIVE_WRITES", allowLiveWrites.toString())
        buildConfigField("String", "MAP_STYLE_URL", quoted(prop("mapStyleUrl", "https://tiles.openfreemap.org/styles/positron")))
        buildConfigField("String", "CAMPAIGN_LINK_HOST", quoted(prop("campaignLinkHost", "streettally.osm.lol")))
    }
    // One flavor per backend. Both share the application id and the OAuth callback scheme,
    // so install one at a time.
    flavorDimensions += "backend"
    productFlavors {
        create("dev") {
            dimension = "backend"
            versionNameSuffix = "-dev"
            buildConfigField("String", "MAPROULETTE_BASE_URL", quoted(prop("maprouletteDevBaseUrl", "https://mr-api.osm.lol")))
        }
        create("stage") {
            dimension = "backend"
            versionNameSuffix = "-stage"
            buildConfigField("String", "MAPROULETTE_BASE_URL", quoted(prop("maprouletteStageBaseUrl", "https://mr-stage.osm.lol")))
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // MapRoulette Mobile SDK (Kotlin), substituted by the `sdk` submodule build (settings.gradle.kts).
    // The submodule commit is the pin; the JitPack coordinate also works once a tag exists.
    implementation("com.github.mvexel:maproulette-mobile-sdk:0.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
