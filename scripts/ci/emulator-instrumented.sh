#!/usr/bin/env bash
# The demo build's instrumented tests on an emulator (no backend; nothing leaves the emulator):
# PinAccessTest, ClusterAccessTest and PinFocusTest (A11y1), OfflineMapTest (pins without the map style), DarkSwitchTest (AE8: dark mode and rotation zoomed out over 5,200 places, no ANR) and OpenTimeTest, which opens the demo's large campaign at 5,200 and 4,999
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
adb logcat -c

# Each class from a fresh start: PinAccessTest expects the trees as a first launch leaves them.
: >"$OUT/instrument.txt"
for class in OpenTimeTest PinAccessTest ClusterAccessTest PinFocusTest DarkSwitchTest OfflineMapTest; do
  adb shell pm clear $PKG >/dev/null
  adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
  adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
  adb shell am instrument -w -e class lol.osm.streettally.$class $PKG.test/androidx.test.runner.AndroidJUnitRunner >>"$OUT/instrument.txt" 2>&1
done
adb logcat -d >"$OUT/logcat.txt" 2>/dev/null

adb logcat -d -s OpenTime:I | grep " OpenTime" | sed 's/.*OpenTime: /[instrumented] open time: /'
adb logcat -d -s DarkSwitch:I | grep " DarkSwitch" | sed 's/.*DarkSwitch: /[instrumented] /'
# The map style not loading (StreetTallyMap): expected once in OfflineMapTest, a finding anywhere else.
adb logcat -d -s StreetTallyMap:W | grep " StreetTallyMap" | sed 's/.*StreetTallyMap: /[instrumented] map: /'
# The app's own step times (CampaignScreen and PickerScreen trace them).
adb logcat -d -s StreetTallyTime:I | grep " StreetTallyTime" | sed 's/.*StreetTallyTime: /[instrumented]   step: /' | head -700
skipped=$(grep -E "Choreographer.*Skipped [0-9]+ frames" "$OUT/logcat.txt" | grep -oE "Skipped [0-9]+" | awk '{ s += $2 } END { print s + 0 }')
log "main thread skipped ${skipped:-0} frames in all ($(grep -cE "Choreographer.*Skipped [0-9]+ frames" "$OUT/logcat.txt") Choreographer warnings)"

# CI hides error dialogs, so an ANR of ours shows only in logcat.
anrs=$(grep -E "ANR in $PKG" "$OUT/logcat.txt")
if [ -n "$anrs" ]; then
  log "FAIL: the app stopped responding"; echo "$anrs" | sed 's/^/[instrumented]   /'
  grep -A12 -E "ANR in $PKG" "$OUT/logcat.txt" | head -40 | sed 's/^/[instrumented]   /'
  exit 1
fi
if [ "$(grep -c '^OK (' "$OUT/instrument.txt")" -eq 6 ]; then
  log "PASS: $(grep '^OK (' "$OUT/instrument.txt" | tr '\n' ' ')"
else
  log "FAIL"; sed 's/^/[instrumented]   /' "$OUT/instrument.txt" | head -250
  # The map's own errors (style or tiles not loading leaves no pins or clusters to reach).
  grep -E "Mbgl|MapLibre|ExploreService|AccessibilityManager" "$OUT/logcat.txt" | grep -E " [EW] " | head -40 | sed 's/^/[instrumented]   logcat: /'
  exit 1
fi
