# Street Tally for Android

Answer simple questions about things on the street (bus stops, benches) during a mapping
campaign. Your answers become OpenStreetMap edits under your own account. Android counterpart of
[ios-maproulette-choice](https://github.com/mvexel/ios-maproulette-choice), built on the
[MapRoulette Mobile SDK](https://github.com/mvexel/maproulette-mobile-sdk) (Kotlin) and the
[MapRoulette mobile backend](https://github.com/mvexel/maproulette-mobile-backend).

Kotlin, Jetpack Compose (Material 3), Android 8.0 (API 26) or later. Map: MapLibre with
OpenFreeMap tiles.

## Build

CI builds every pull request (with submodules) on GitHub's Ubuntu runners and uploads the debug APKs as the
`street-tally-debug-apks` artifact. Locally, with JDK 17 and the Android SDK (`ANDROID_HOME`):

```sh
./gradlew assembleDevDebug assembleStageDebug testDevDebugUnitTest lintDevDebug
adb install -r app/build/outputs/apk/stage/debug/app-stage-debug.apk
```

## Backends

| Flavor | API | OpenStreetMap | Answers sent |
| --- | --- | --- | --- |
| `dev` | `https://mr-api.osm.lol` | development OSM | yes |
| `stage` | `https://mr-stage.osm.lol` | openstreetmap.org | only with `-PmaprouletteAllowLiveWrites=true` |

Both flavors share the application id `lol.osm.maproulette.choice` and the OAuth callback
`lol.osm.maproulette.choice:/oauth2redirect` of the iOS app's public client
`maproulette-ios-choice`, so install one at a time. Override with `-PmaprouletteOAuthClientId`,
`-PmaprouletteOAuthCallbackScheme`, `-PmaprouletteDevBaseUrl`, `-PmaprouletteStageBaseUrl`,
`-PmapStyleUrl` and `-PcampaignLinkHost`. These are public identifiers and URLs, not credentials.

The write rule matches the iOS app (`AppConfig.writesAllowed`): dev always, stage and prod only in
a live-writes build, anything else read-only. The SDK enforces it too.

## SDK

The [MapRoulette Mobile SDK](https://github.com/mvexel/maproulette-mobile-sdk) (Kotlin) is the
`sdk` git submodule, built with the app through a Gradle composite build. The submodule commit is
the pin (today `07e192a`, SDK 0.2.0). Clone with `--recurse-submodules`, or run
`git submodule update --init`. To move the pin: `git -C sdk checkout <commit>` and commit `sdk`.
