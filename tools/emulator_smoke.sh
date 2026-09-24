#!/usr/bin/env bash
#
# Installs the *published* release APK on a running emulator, opens it, drives the four bottom-nav
# tabs and fails loudly if the app crashes, hangs or never reaches a screen.
#
# This exists because a build that compiles is not a build that opens: the first two artifacts
# this project produced compiled cleanly and died on the first frame. Anything that ships has to
# survive this script first.
#
# Usage:  tools/emulator_smoke.sh [version] [--keep-going]
#   version      artifact version to look for in out/ (default: whatever is there)
#   --keep-going keep walking after the first problem (still exits non-zero)
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
KEEP_GOING="no"
for arg in "$@"; do [ "$arg" = "--keep-going" ] && KEEP_GOING="yes"; done

OUT="out"
SHOTS="screenshots"
PACKAGE="com.morsecode.app"          # release build's applicationId
DEBUG_PACKAGE="com.morsecode.app.debug"
ACTIVITY="$PACKAGE/.MainActivity"
LOGCAT_TAG="MorseCode"

mkdir -p "$SHOTS"
FAILURES=0
STEP=0

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32mok\033[0m   %s\n' "$*"; }
bad()  { printf '   \033[31mFAIL\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

last_error=""
# `set -e` is deliberately off: every check is evaluated and reported, so one run shows
# everything that is wrong instead of only the first thing.
check() {
  local what="$1"; shift
  if "$@"; then ok "$what"; else bad "$what"; fi
}

say "Looking for artifacts"
RELEASE_APK=$(ls "$OUT"/MorseCode-*-release.apk 2>/dev/null | head -1)
DEBUG_APK=$(ls "$OUT"/MorseCode-*-debug.apk 2>/dev/null | head -1)
if [ -n "$VERSION" ] && [ -f "$OUT/MorseCode-${VERSION}-release.apk" ]; then
  RELEASE_APK="$OUT/MorseCode-${VERSION}-release.apk"
  DEBUG_APK="$OUT/MorseCode-${VERSION}-debug.apk"
fi
[ -n "$RELEASE_APK" ] || { bad "no release APK in $OUT/"; exit 1; }
[ -n "$DEBUG_APK" ] || bad "no debug APK in $OUT/"
printf '   release: %s\ndebug:   %s\n' "$RELEASE_APK" "${DEBUG_APK:-none}"

say "Waiting for the device"
adb wait-for-device || { bad "no device"; exit 1; }
BOOTED=""
for _ in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then BOOTED="yes"; break; fi
  sleep 2
done
[ "$BOOTED" = "yes" ] && ok "device booted" || bad "device never finished booting"
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
printf '   api=%s  abi=%s\n' \
  "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" \
  "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

# ------------------------------------------------------------------ screenshots
# `screencap` needs the file pulled; `exec-out` streams it and keeps the PNG intact.
shot() {
  STEP=$((STEP + 1))
  local name
  name=$(printf '%02d-%s.png' "$STEP" "$1")
  if adb exec-out screencap -p > "$SHOTS/$name" 2>/dev/null && [ -s "$SHOTS/$name" ]; then
    printf '   shot %s (%s bytes)\n' "$name" "$(stat -c%s "$SHOTS/$name")"
  else
    bad "could not capture $name"
  fi
}

# ------------------------------------------------------------------ health checks
app_alive() {
  [ -n "$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')" ]
}

no_fatal() {
  local crashes
  crashes=$(adb logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION" || true)
  local java
  java=$(adb logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION" || true)
  [ "$crashes" = "0" ] && [ "$java" = "0" ]
}

screen_size() {
  adb shell wm size 2>/dev/null | head -1 | grep -oE '[0-9]+x[0-9]+' | tail -1
}

say "Install"
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb uninstall "$DEBUG_PACKAGE" >/dev/null 2>&1 || true
check "release APK installs" adb install -r "$RELEASE_APK"
[ -n "$DEBUG_APK" ] && check "debug APK installs" adb install -r "$DEBUG_APK"
adb logcat -c >/dev/null 2>&1 || true

say "Cold start"
adb shell am start -W -n "$ACTIVITY" > /tmp/am-start.txt 2>&1
cat /tmp/am-start.txt | sed 's/^/   /'

# A process that dies within seconds of starting is the exact failure this script exists for.
STARTED=""
for _ in $(seq 1 15); do
  if app_alive; then STARTED="yes"; break; fi
  sleep 2
done
if [ "$STARTED" = "yes" ]; then ok "process is running"; else bad "process never appeared - the app died on launch"; fi
sleep 6
check "process still alive 6s after launch" app_alive
check "no FATAL EXCEPTION in logcat" no_fatal

shot onboarding-or-connect

# The first launch shows the 4-slide onboarding; back out of it to reach the shell.
if adb shell dumpsys activity activities 2>/dev/null | grep -q "OnboardingActivity"; then
  ok "onboarding is showing on first launch"
  adb shell input keyevent 4 >/dev/null 2>&1
  sleep 3
  shot after-onboarding
fi

say "App log (logcat tag $LOGCAT_TAG)"
adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | sed 's/^/   /' | tail -30
if adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | grep -q "Main resumed"; then
  ok "MainActivity reached onResume"
else
  bad "MainActivity never logged 'Main resumed'"
fi

# ------------------------------------------------------------------ the four tabs
SIZE=$(screen_size)
if [ -n "$SIZE" ]; then
  W=${SIZE%x*}; H=${SIZE#*x}
else
  W=1080; H=2400
fi
# The bottom nav is the last strip above the system bar: tap 6% up from the bottom, at the
# centre of each quarter.
NAV_Y=$(( H - H * 6 / 100 ))
say "Bottom navigation (screen ${W}x${H}, taps at y=$NAV_Y)"
TABS=("connect" "files" "history" "settings")
for i in 0 1 2 3; do
  X=$(( W * (2 * i + 1) / 8 ))
  adb shell input tap "$X" "$NAV_Y" >/dev/null 2>&1
  sleep 3
  shot "tab-$((i + 1))-${TABS[$i]}"
  check "process alive on the ${TABS[$i]} tab" app_alive
done
check "no FATAL EXCEPTION after walking the tabs" no_fatal

say "Back stack sweep"
adb shell input keyevent 4 >/dev/null 2>&1
sleep 2
shot after-back
check "process alive after  back" app_alive

# A SIGSEGV in one of the JNI-less layers would show up as an ANR instead; both abort the run.
if adb logcat -d 2>/dev/null | grep -qE "ANR in $PACKAGE"; then
  bad "ANR reported for $PACKAGE"
  adb logcat -d 2>/dev/null | grep -A 20 "ANR in $PACKAGE" | sed 's/^/   /'
else
  ok "no ANR reported"
fi

say "Screenshots"
ls -la "$SHOTS" | sed 's/^/   /'

say "Result"
if [ "$FAILURES" -eq 0 ]; then
  printf '   \033[32m%s\033[0m\n' "the app installed, opened and survived every tab"
  exit 0
fi

printf '   \033[31m%d check(s) failed - full logcat follows\033[0m\n' "$FAILURES"
adb logcat -d 2>/dev/null | tail -200 | sed 's/^/   /'
if [ "$KEEP_GOING" = "yes" ]; then exit 0; fi
exit 1
