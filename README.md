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

Debug APKs are signed with the shared `app/debug.keystore` (the standard public debug
credentials) and numbered with the CI run, so a newer test APK installs over the last one and keeps
the sign-in. A manual run of the CI workflow with `live_writes` also builds a stage APK that sends
answers to stage (`street-tally-stage-live-writes-apk`); stage edits the real OpenStreetMap.

## Releases

Pilot installs come from GitHub Releases: the `street-tally.apk` asset at
`https://github.com/mvexel/android-maproulette-choice/releases/latest/download/street-tally.apk`.
Pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`, which builds the `prod` flavor
(live writes, guests on), signs it with the release key from the repository secrets, and creates a
draft release with the notes from `.github/release/notes.md` (the tag message's body becomes
"What's new"). Publishing the draft makes it the latest. Without the key secrets or the
`MAPROULETTE_PROD_OAUTH_CLIENT_ID` variable the workflow stops before building.

## Campaign links

`mrchoice://challenge/<id>` opens a campaign. The share screen's QR code points at
`https://<campaignLinkHost>/challenge/<id>`, the participant page, as on iOS. On Android that link
opens the page, not the app, until App Links are set up (an `assetlinks.json` on
`streettally.osm.lol` and a verified intent filter here); `CampaignLink` already parses it.

## Backends

| Flavor | API | OpenStreetMap | Answers sent |
| --- | --- | --- | --- |
| `dev` | `https://mr-api.osm.lol` | development OSM | yes |
| `stage` | `https://mr-stage.osm.lol` | openstreetmap.org | only with `-PmaprouletteAllowLiveWrites=true` |
| `prod` | `https://mr-prod.osm.lol` | openstreetmap.org | only with `-PmaprouletteAllowLiveWrites=true` |

Both flavors share the application id `lol.osm.maproulette.choice` and the OAuth callback
`lol.osm.maproulette.choice:/oauth2redirect` of the iOS app's public client
`maproulette-ios-choice`, so install one at a time. Override with `-PmaprouletteOAuthClientId`,
`-PmaprouletteOAuthCallbackScheme`, `-PmaprouletteDevBaseUrl`, `-PmaprouletteStageBaseUrl`,
`-PmapStyleUrl` and `-PcampaignLinkHost`. These are public identifiers and URLs, not credentials.

The write rule matches the iOS app (`AppConfig.writesAllowed`): dev always, stage and prod only in
a live-writes build, anything else read-only. The SDK enforces it too.

Deferred sign-up (answering before signing in, as on iOS with `MAPROULETTE_GUESTS`) is off by
default; turn it on with `-PmaprouletteGuests=true`. A guest's answers are saved on the server as
pending answers (never an OSM edit, never a lock) and go on the map once the guest finishes signing
up. Guests answer only where the build takes writes.

## SDK

The [MapRoulette Mobile SDK](https://github.com/mvexel/maproulette-mobile-sdk) (Kotlin) is the
`sdk` git submodule, built with the app through a Gradle composite build, so no package
repository is involved. The submodule commit is the pin: today the `0.2.0` tag (`b6f3177`).
Clone with `--recurse-submodules`, or run `git submodule update --init`.

To move to a new SDK release: `git -C sdk fetch --tags && git -C sdk checkout <tag>`, set the
same version in the SDK dependency in `app/build.gradle.kts` (it only labels the substituted
module), and commit `sdk` with that change.
