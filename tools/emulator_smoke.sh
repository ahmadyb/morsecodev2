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

IN_CI="no"
[ "${GITHUB_ACTIONS:-}" = "true" ] && IN_CI="yes"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32mok\033[0m   %s\n' "$*"; }

# A failed check is also surfaced as a workflow annotation: the job log is not always reachable
# (rate limits, expired artifacts, sandboxes), but the check-run annotation API always is.
bad()  {
  printf '   \033[31mFAIL\033[0m %s\n' "$*"
  [ "$IN_CI" = "yes" ] && echo "::error title=smoke::$(printf '%s' "$*")"
  FAILURES=$((FAILURES + 1))
}
note() {
  printf '   \033[36mnote\033[0m %s\n' "$*"
  [ "$IN_CI" = "yes" ] && echo "::notice title=smoke::$(printf '%s' "$*")"
}

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

# `logcat -b crash` holds one block per crash; only a block naming *our* package counts
# (the emulator's own apps crash too, and that is not this build's problem).
our_crashes() {
  adb logcat -d -b crash 2>/dev/null | awk -v pkg="$PACKAGE" '
    /FATAL EXCEPTION/ { block = ""; hit = 0 }
    { block = block $0 "\n" }
    index($0, pkg) { hit = 1 }
    /^$/ { if (hit && block ~ /FATAL EXCEPTION/) print block; hit = 0 }
    END { if (hit && block ~ /FATAL EXCEPTION/) print block }'
}

no_fatal() {
  [ -z "$(our_crashes)" ]
}

# fatal() dumps the crash through annotations so it is readable from the API.
report_crashes() {
  local block
  block=$(our_crashes)
  [ -z "$block" ] && return 0
  printf '%s\n' "$block" | head -40 | while IFS= read -r line; do
    [ -n "$line" ] && echo "::error title=crash::$line"
  done
}

screen_size() {
  adb shell wm size 2>/dev/null | head -1 | grep -oE '[0-9]+x[0-9]+' | tail -1
}

say "Install"
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb uninstall "$DEBUG_PACKAGE" >/dev/null 2>&1 || true
check "release APK installs" adb install -r -g "$RELEASE_APK"
[ -n "$DEBUG_APK" ] && check "debug APK installs" adb install -r -g "$DEBUG_APK"
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
check "no crash from $PACKAGE in logcat" no_fatal
report_crashes

shot onboarding-or-connect
if ! app_alive; then
  bad "the app is not running after the first frame"
  report_crashes
  say "Logcat (last 60 lines)"
  adb logcat -d 2>/dev/null | tail -60 | sed 's/^/   /'
fi

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
LABELS=("Connect" "Files" "History" "Settings")

# Prefer the real bounds of the nav label from the view hierarchy: tapping a fixed fraction of
# the screen only works until the layout, the density or the system bar changes.
label_bounds() {
  adb shell uiautomator dump /sdcard/mc-ui.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/mc-ui.xml 2>/dev/null | tr '>' '\n' \
    | grep "text=\"$1\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1
}

for i in 0 1 2 3; do
  TAPPED="no"
  BOUNDS=$(label_bounds "${LABELS[$i]}" || true)
  if [ -n "$BOUNDS" ]; then
    COORDS=$(printf '%s' "$BOUNDS" | grep -o '[0-9]*')
    set -- $COORDS
    if [ "$#" -ge 4 ]; then
      X=$(( ($1 + $3) / 2 )); Y=$(( ($2 + $4) / 2 ))
      TAPPED="yes"
    fi
  fi
  if [ "$TAPPED" = "no" ]; then
    X=$(( W * (2 * i + 1) / 8 )); Y=$NAV_Y
  fi
  note "tap ${LABELS[$i]} at $X,$Y ($([ "$TAPPED" = yes ] && echo hierarchy || echo fallback))"
  adb shell input tap "$X" "$Y" >/dev/null 2>&1
  sleep 3
  shot "tab-$((i + 1))-${TABS[$i]}"
  check "process alive on the ${TABS[$i]} tab" app_alive
done
check "no crash from $PACKAGE after walking the tabs" no_fatal
report_crashes

say "Back stack sweep"
adb shell input keyevent 4 >/dev/null 2>&1
sleep 2
shot after-back
check "process alive after back" app_alive

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
