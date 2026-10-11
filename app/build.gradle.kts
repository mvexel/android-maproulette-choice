plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time configuration: public identifiers and URLs, not credentials. Override with
// -P<name>=<value>, e.g. ./gradlew assembleStageDebug -PmaprouletteAllowLiveWrites=true
fun prop(name: String, default: String) = providers.gradleProperty(name).orElse(default).get()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The same public OAuth client and callback as the iOS app (registered on dev, stage and prod).
val oauthClientId = prop("maprouletteOAuthClientId", "maproulette-ios-choice")
val callbackScheme = prop("maprouletteOAuthCallbackScheme", "lol.osm.maproulette.choice")
// true lets stage and prod (which edit the real OpenStreetMap) receive answers. Off by default.
val allowLiveWrites = prop("maprouletteAllowLiveWrites", "false").toBoolean()
// true lets participants answer before signing in (deferred sign-up, like iOS MAPROULETTE_GUESTS).
// Guests save only where this build takes writes. Off by default.
val guests = prop("maprouletteGuests", "false").toBoolean()

android {
    namespace = "lol.osm.streettally"
    compileSdk = 36
    defaultConfig {
        applicationId = "lol.osm.maproulette.choice"
        minSdk = 26
        targetSdk = 36
        // CI passes its run number so each test APK installs over the last one.
        versionCode = prop("versionCode", "1").toInt()
        // The release workflow passes the tag's version (v0.2.0 -> 0.2.0).
        versionName = prop("versionName", "0.1.0")
        manifestPlaceholders["appAuthRedirectScheme"] = callbackScheme
        buildConfigField("String", "MAPROULETTE_OAUTH_CLIENT_ID", quoted(oauthClientId))
        buildConfigField("String", "MAPROULETTE_OAUTH_CALLBACK_SCHEME", quoted(callbackScheme))
        buildConfigField("boolean", "MAPROULETTE_ALLOW_LIVE_WRITES", allowLiveWrites.toString())
        buildConfigField("boolean", "MAPROULETTE_GUESTS", guests.toString())
        buildConfigField("String", "MAP_STYLE_URL", quoted(prop("mapStyleUrl", "https://tiles.openfreemap.org/styles/positron")))
        buildConfigField("String", "CAMPAIGN_LINK_HOST", quoted(prop("campaignLinkHost", "streettally.osm.lol")))
    }
    // A shared debug key (standard public debug credentials, not a secret), so APKs from
    // different CI runs and machines install over each other and keep the sign-in.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The Street Tally release key, only in the release workflow: a keystore file and its
        // passwords from repository secrets (.github/workflows/release.yml). Never committed.
        val releaseStore = System.getenv("STREET_TALLY_KEYSTORE")
        if (!releaseStore.isNullOrEmpty()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("STREET_TALLY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STREET_TALLY_KEY_ALIAS")
                keyPassword = System.getenv("STREET_TALLY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            // Unsigned without the release key; the release workflow refuses to publish that.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // One flavor per backend. All share the application id and the OAuth callback scheme,
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
        // The pilot backend (openstreetmap.org). Answers only with -PmaprouletteAllowLiveWrites=true.
        create("prod") {
            dimension = "backend"
            buildConfigField("String", "MAPROULETTE_BASE_URL", quoted(prop("maprouletteProdBaseUrl", "https://mr-prod.osm.lol")))
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    // Robolectric (the report screen snapshots) needs the merged resources and manifest. The
    // property makes Roborazzi write the PNGs (without its Gradle plugin it records nothing).
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
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
    // Custom Tabs, with private (ephemeral) tabs where the browser supports them, for sign-in.
    implementation("androidx.browser:browser:1.9.0")
    // Map: MapLibre with OpenFreeMap tiles (style URL per flavor).
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    // LocationManagerCompat.getCurrentLocation for the nearest-first list.
    implementation("androidx.core:core-ktx:1.16.0")
    // QR codes for sharing a campaign link.
    implementation("com.google.zxing:core:3.5.3")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Screen snapshots on the JVM: Compose rendered by Robolectric's native graphics, saved as
    // PNGs by Roborazzi.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
