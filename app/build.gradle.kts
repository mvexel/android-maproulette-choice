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
// true shows other people's not-here and moved reports (parity row 4). Off until the backend's
// reports read (E1/E2) exists; until then nothing feeds it but tests.
val serverReports = prop("maprouletteServerReports", "false").toBoolean()
// true (debug builds only) lets the base URL be http://127.0.0.1:<port>, for a test proxy reached
// through `adb reverse` (Tess's dead-spot runs; iOS MAPROULETTE_ALLOW_LOOPBACK). Release: never.
val allowLoopback = prop("maprouletteAllowLoopback", "false").toBoolean()

// Robolectric's Android 15 jar (the tests' sdk 35), fetched by Gradle, which caches it and retries
// Maven Central's rate limits, then handed to Robolectric offline. Its own download got 403/404/429
// from Central on CI (#45, #47). Keep the version in step with the Robolectric release.
val robolectricJars: Configuration by configurations.creating { isTransitive = false }
val robolectricDir = layout.buildDirectory.dir("robolectric-jars")
val syncRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricJars)
    into(robolectricDir)
}

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
        buildConfigField("boolean", "SERVER_REPORTS", serverReports.toString())
        buildConfigField("String", "MAP_STYLE_URL", quoted(prop("mapStyleUrl", "https://tiles.openfreemap.org/styles/positron")))
        buildConfigField("String", "CAMPAIGN_LINK_HOST", quoted(prop("campaignLinkHost", "streettally.osm.lol")))
        buildConfigField("boolean", "DEMO", "false")
        // PinAccessTest (the emulator smoke runs it on the demo build).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        getByName("debug") {
            // Test builds install next to the store app on a real phone (Tess, AE13). Release keeps
            // exactly lol.osm.maproulette.choice: the Play listing and assetlinks.json (A8) name it.
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_LOOPBACK", allowLoopback.toString())
        }
        getByName("release") {
            buildConfigField("boolean", "ALLOW_LOOPBACK", "false")
            // Unsigned without the release key; the release workflow refuses to publish that.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // One flavor per backend. Dev, stage and prod share the application id and the OAuth callback
    // scheme, so install one of their debug builds at a time; demo has its own id.
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
        // No backend: "Woodbine trees" served in-process (DemoBackend), for design review and field
        // checks on an emulator. Guest answers stay in memory; nothing is sent anywhere.
        create("demo") {
            dimension = "backend"
            versionNameSuffix = "-demo"
            applicationIdSuffix = ".demo"
            buildConfigField("String", "MAPROULETTE_BASE_URL", quoted("https://demo.invalid"))
            buildConfigField("boolean", "MAPROULETTE_GUESTS", "true")
            buildConfigField("boolean", "DEMO", "true")
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
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.dependsOn(syncRobolectricJars)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
            // Full failure messages in the CI log: a UI test's timeout names the text it waited for.
            it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
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
    // ExploreByTouchHelper: TalkBack nodes for the map's pins, which MapLibre draws as pixels.
    implementation("androidx.customview:customview:1.1.0")
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
    robolectricJars("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
