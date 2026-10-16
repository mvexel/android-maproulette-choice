#!/usr/bin/env bash
# Emulator smoke check on the demo build (no backend; nothing leaves the emulator).
# The same steps Tess runs by hand on the Mac, scripted for CI:
#   1. For each Woodbine demo campaign: open it, wait for "Nearest …", answer that place,
#      expect "Saved", go back and expect "Nearest …" again (twice per campaign).
#   2. Fresh launch on buildings: clear data, answer the system location prompt, and expect
#      "Nearest …" without tapping "Update my location".
#   3. No ANR, no crash, no E/ lines under the StreetTally log tag.
# Locations come from test providers: `adb emu geo fix` doesn't reach the app on recent images.
# Usage: scripts/ci/emulator-smoke.sh <demoDebug apk> [output dir]
set -uo pipefail

APK=${1:?usage: emulator-smoke.sh <demoDebug apk> [output dir]}
OUT=${2:-smoke-out}
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=lol.osm.maproulette.choice
ACT=$PKG/lol.osm.streettally.MainActivity
WAIT=${SMOKE_WAIT:-30}   # seconds to wait for a screen; the CI emulator is slower than a phone
mkdir -p "$OUT"
FAILS=0
FEEDER=

log() { echo "[smoke] $*"; }
# On a failure, also print what the screen showed, so the job log says why without the artifact.
fail() {
  echo "[smoke] FAIL: $*"; FAILS=$((FAILS + 1))
  dump _fail; python3 "$HERE/ui.py" "$OUT/_fail.xml" texts | sed 's/^/[smoke]   screen: /' | head -40
}

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { adb exec-out screencap -p >"$OUT/$1.png"; dump "$1"; }
ui() { python3 "$HERE/ui.py" "$@"; }
tap() { [ -n "$1" ] && adb shell input tap $1; }   # $1 is "x y", split on purpose

# Wait until the screen shows text matching the regex $1, dismissing ANR dialogs (and counting them).
waitfor() {
  local deadline=$(( $(date +%s) + WAIT ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    dump _w
    grep -q -E "$1" "$OUT/_w.xml" 2>/dev/null && return 0
    if grep -q "Street Tally isn't responding" "$OUT/_w.xml" 2>/dev/null; then
      fail "ANR dialog while waiting for '$1'"; tap "$(ui "$OUT/_w.xml" find "Wait")"
    elif grep -q "isn't responding" "$OUT/_w.xml" 2>/dev/null; then
      # Another app's (the launcher's, just after boot): not ours, and it can hide our start. Dismiss
      # it and bring the app back to the front.
      log "another app's ANR dialog while waiting for '$1'; dismissed"
      tap "$(ui "$OUT/_w.xml" find "Wait")"
      [ -n "$LAST_DEMO" ] && adb shell am start -n $ACT --es demo "$LAST_DEMO" >/dev/null
    fi
    sleep 2
  done
  return 1
}

providers_on() {
  adb shell appops set com.android.shell android:mock_location allow
  for p in gps network fused; do
    adb shell cmd location providers add-test-provider $p >/dev/null 2>&1
    adb shell cmd location providers set-test-provider-enabled $p true
  done
}
providers_off() {
  for p in gps network fused; do adb shell cmd location providers remove-test-provider $p >/dev/null 2>&1; done
  adb shell appops set com.android.shell android:mock_location default
}
feed_start() {
  feed_stop
  ( while true; do
      for p in gps network fused; do
        adb shell cmd location providers set-test-provider-location $p --location "$1,$2" --accuracy 5 >/dev/null 2>&1
      done
      sleep 2
    done ) &
  FEEDER=$!
}
feed_stop() { [ -n "$FEEDER" ] && kill "$FEEDER" 2>/dev/null; wait "$FEEDER" 2>/dev/null; FEEDER=; }
fixes() { adb shell dumpsys location | grep -E "$PKG: min/max" | grep -o "locations = [0-9]*" | head -1 | grep -o "[0-9]*"; }
grant() {
  adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
  adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
}
LAST_DEMO=
launch() { LAST_DEMO=$1; adb shell pm clear $PKG >/dev/null; [ "${2:-}" = grant ] && grant; adb shell am start -n $ACT --es demo "$1" >/dev/null; }

# Close the welcome dialog: tap "Let's go" and check it went; the first tap can land before
# the dialog takes input. $1 is a dump name that already holds the dialog.
lets_go() {
  local f=$1
  # The tally can be on screen a moment before the dialog's window: give the dialog time to show.
  for _ in 1 2 3 4; do
    grep -q "text=\"Let's go\"" "$OUT/$f.xml" && break
    sleep 2; dump _lg; f=_lg
  done
  for _ in 1 2 3; do
    tap "$(ui "$OUT/$f.xml" exact "Let's go")"
    sleep 2
    dump _lg
    grep -q "text=\"Let's go\"" "$OUT/_lg.xml" || return 0
    f=_lg
  done
  log "Let's go still showing after 3 taps"
}

answer_place() {   # answers every question with its first option, then Done
  for _ in 1 2 3 4; do
    dump _q
    grep -q -E "Question [0-9]+ of" "$OUT/_q.xml" || break
    tap "$(ui "$OUT/_q.xml" option)"
    sleep 3
  done
  dump _d
  tap "$(ui "$OUT/_d.xml" exact "Done")"
}

cleanup() {
  feed_stop
  adb logcat -d -s StreetTally >"$OUT/streettally.log" 2>/dev/null
  adb logcat -d >"$OUT/logcat.txt" 2>/dev/null
  providers_off
}
trap cleanup EXIT

log "installing $APK"
adb install -r -g "$APK" >/dev/null || { echo "[smoke] FAIL: install"; exit 1; }
adb logcat -c
providers_on

# 1. Nearest survives answer → back.
for spec in "trees 40.751643 -111.902912" "crossings 40.7563224 -111.9026154" "buildings 40.7579552 -111.9059517"; do
  set -- $spec; C=$1
  feed_start "$2" "$3"
  launch "$C" grant
  if ! waitfor "Let's go|The tally"; then fail "$C: welcome or campaign screen didn't appear"; shot "$C-stuck"; continue; fi
  shot "$C-welcome"
  lets_go "$C-welcome"
  for round in 1 2; do
    if ! waitfor "Nearest "; then fail "$C round $round: no Nearest button (fixes=$(fixes))"; shot "$C-nonearest$round"; break; fi
    shot "$C-campaign$round"
    tap "$(ui "$OUT/$C-campaign$round.xml" starts "Nearest ")"
    if ! waitfor "Question [0-9]+ of"; then fail "$C round $round: task didn't open"; shot "$C-notask$round"; break; fi
    answer_place
    if ! waitfor "Saved"; then fail "$C round $round: no 'Saved' confirmation"; shot "$C-notsaved$round"; break; fi
    shot "$C-saved$round"
    P=$(ui "$OUT/$C-saved$round.xml" find "Back to all")
    if [ -n "$P" ]; then tap "$P"; else adb shell input keyevent KEYCODE_BACK; fi
    waitfor "The tally" || fail "$C round $round: campaign screen didn't come back"
  done
  waitfor "Nearest " && shot "$C-after" || { fail "$C: Nearest gone after answering (fixes=$(fixes))"; shot "$C-after"; }
  log "$C done, fixes=$(fixes)"
done

# 2. Fresh launch: the system prompt, then Nearest without "Update my location".
feed_start 40.7579552 -111.9059517
launch buildings
waitfor "Let's go|Allow Street Tally" || fail "fresh launch: no welcome screen"
dump _f; lets_go _f
sleep 3
if waitfor "Allow Street Tally|While using the app"; then
  shot fresh-prompt; tap "$(ui "$OUT/fresh-prompt.xml" exact "While using the app")"; how=prompt
else
  grant; how=pm
fi
t0=$(date +%s)
if WAIT=45 waitfor "Nearest "; then
  log "fresh launch: Nearest after $(( $(date +%s) - t0 )) s (grant by $how, fixes=$(fixes))"
else
  fail "fresh launch: no Nearest within 45 s (grant by $how, fixes=$(fixes))"
fi
shot fresh-buildings
feed_stop

# 3. Crashes, ANRs and app errors.
anrs=$(adb logcat -d -b events | grep -c "am_anr.*$PKG")
crashes=$(adb logcat -d -b crash | grep -c "$PKG")
errors=$(adb logcat -d -s StreetTally:E | grep -c " E StreetTally")
[ "$anrs" -eq 0 ] || fail "$anrs ANR(s)"
[ "$crashes" -eq 0 ] || fail "$crashes crash line(s)"
[ "$errors" -eq 0 ] || fail "$errors E/StreetTally line(s)"

if [ "$FAILS" -eq 0 ]; then log "PASS"; else log "$FAILS failure(s); screenshots and dumps in $OUT"; fi
exit $(( FAILS > 0 ))
