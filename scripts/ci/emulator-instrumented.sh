#!/usr/bin/env bash
# The demo build's instrumented tests on an emulator (no backend; nothing leaves the emulator):
# PinAccessTest (A11y1) and OpenTimeTest, which opens the demo's large campaign at 5,200 and 4,999
# places (L1, Tess's AE6b) and logs how long it took. Prints the open times and the frames the main
# thread skipped while loading, so the job log carries them without the artifact.
# Usage: scripts/ci/emulator-instrumented.sh <demoDebug apk> <demoDebug androidTest apk> [output dir]
set -uo pipefail

APK=${1:?usage: emulator-instrumented.sh <demoDebug apk> <androidTest apk> [output dir]}
TEST_APK=${2:?usage: emulator-instrumented.sh <demoDebug apk> <androidTest apk> [output dir]}
OUT=${3:-instrumented-out}
PKG=lol.osm.maproulette.choice
mkdir -p "$OUT"
log() { echo "[instrumented] $*"; }

adb install -r -g "$APK" >/dev/null || { log "FAIL: app didn't install"; exit 1; }
adb install -r -g "$TEST_APK" >/dev/null || { log "FAIL: test APK didn't install"; exit 1; }
# As in the smoke: the image's own background apps' ANR dialogs cover ours and swallow taps.
adb shell settings put global anr_show_background 0 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm disable-user --user 0 com.google.android.googlesdksetup >/dev/null 2>&1 || true
adb shell pm clear $PKG >/dev/null
# No location prompt over the map.
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb logcat -c

adb shell am instrument -w $PKG.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/instrument.txt" 2>&1
adb logcat -d >"$OUT/logcat.txt" 2>/dev/null

adb logcat -d -s OpenTime:I | grep " OpenTime" | sed 's/.*OpenTime: /[instrumented] open time: /'
# The app's own step times (CampaignScreen and PickerScreen trace them).
adb logcat -d -s StreetTallyTime:I | grep " StreetTallyTime" | sed 's/.*StreetTallyTime: /[instrumented]   step: /' | head -200
skipped=$(grep -E "Choreographer.*Skipped [0-9]+ frames" "$OUT/logcat.txt" | grep -oE "Skipped [0-9]+" | awk '{ s += $2 } END { print s + 0 }')
log "main thread skipped ${skipped:-0} frames in all ($(grep -cE "Choreographer.*Skipped [0-9]+ frames" "$OUT/logcat.txt") Choreographer warnings)"

if grep -q "^OK (" "$OUT/instrument.txt"; then
  log "PASS: $(grep '^OK (' "$OUT/instrument.txt")"
else
  log "FAIL"; sed 's/^/[instrumented]   /' "$OUT/instrument.txt" | head -60
  exit 1
fi
