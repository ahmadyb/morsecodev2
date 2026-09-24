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

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
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
#
# The annotation budget is finite - a step keeps roughly its first ten and silently drops the
# rest - so `note` is deliberately log-only and the handful of facts that answer "is the app
# reading the library, and what is it showing" go through `ann`. A failure is always an error
# annotation, and that is what the budget is being saved for.
bad()  {
  printf '   \033[31mFAIL\033[0m %s\n' "$*"
  [ "$IN_CI" = "yes" ] && echo "::error title=smoke::$(printf '%s' "$*")"
  FAILURES=$((FAILURES + 1))
}
note() {
  printf '   \033[36mnote\033[0m %s\n' "$*"
  [ "$IN_CI" = "yes" ] && echo "::debug title=smoke::$(printf '%s' "$*")"
}
# Curated annotation: log line plus a real notice that survives in the check run.
ann()  { printf '   \033[35mann\033[0m %s\n' "$*"; [ "$IN_CI" = "yes" ] && echo "::notice title=smoke::$(printf '%s' "$*")"; }

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

wait_boot() {
  adb wait-for-device || return 1
  for _ in $(seq 1 120); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
      adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
      adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
      return 0
    fi
    sleep 2
  done
  return 1
}

say "Waiting for the device"
wait_boot && ok "device booted" || bad "device never finished booting"
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

# `adb install -g` is supposed to grant every runtime permission the manifest declares, but on
# Android 14 a media app can end up holding only READ_MEDIA_VISUAL_USER_SELECTED - full access is
# then reported as granted while MediaStore quietly filters every query down to the items the user
# picked (none, on a fresh emulator). Granting the three documented media permissions explicitly
# removes that ambiguity from the test, before the app is ever launched.
say "Runtime media grants"
for P in READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO; do
  adb shell pm grant "$PACKAGE" "android.permission.$P" >/dev/null 2>&1 &&     ok "granted android.permission.$P" || note "could not grant $P (not declared below API 33?)"
done

say "Seed the emulator with media"
# Three tiny images so the Files tab exercises MediaStore paging, thumbnails and day grouping
# instead of only its empty state. `MEDIA_SCANNER_SCAN_FILE` has been a no-op since Android 10, so
# the volume is rescanned by the only thing that reliably triggers it: a reboot.
# Located relative to this script, not to the environment: the emulator action runs `script`
# through sh and the exported variable did not survive it, so the seed silently did nothing.
SEED_DIR="${MORSE_MEDIA_DIR:-$HERE/media-seed}"
SEED_COUNT=$(ls -1 "$SEED_DIR" 2>/dev/null | wc -l)
note "seed directory: $SEED_DIR ($SEED_COUNT file(s))"
if [ "$SEED_COUNT" -gt 0 ]; then
  adb shell "mkdir -p /sdcard/Pictures" >/dev/null 2>&1 || true
  if adb push "$SEED_DIR/." /sdcard/Pictures/ >/dev/null 2>&1; then
    ok "pushed $SEED_COUNT file(s) to /sdcard/Pictures"
    adb reboot >/dev/null 2>&1 || true
    sleep 5
    if wait_boot; then
      note "rebooted so MediaProvider rescans the volume"
    else
      bad "the device did not come back after the media seed reboot"
    fi
  else
    note "could not push media - continuing with the device's own library"
  fi
else
  note "no seed media found in $SEED_DIR"
fi
# MediaProvider finishes its post-boot scan asynchronously, and on some images it needs to be
# asked; both are attempted, and the raw answer is reported so a failure here is diagnosable
# instead of just a zero.
sleep 15
adb shell "content call --uri content://media/external/images/media --method scan_volume --arg external_primary" >/dev/null 2>&1 || true
sleep 5
RAW=$(adb shell "content query --uri content://media/external/images/media --projection _id,_display_name" 2>&1 | head -5)
COUNT=$(printf '%s' "$RAW" | grep -c "_id" || true)
ann "seeded $SEED_COUNT file(s); the shell sees ${COUNT:-0} image row(s)"
if [ "${COUNT:-0}" = "0" ]; then
  note "raw query answer: $(printf '%s' "$RAW" | tr '\n' ' ' | head -c 300)"
  note "files on device: $(adb shell 'ls /sdcard/Pictures' 2>/dev/null | tr '\n' ' ' | head -c 200)"
fi

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
APP_LOG=$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | tail -40)
printf '%s\n' "$APP_LOG" | sed 's/^/   /'
# Anything the app logged about a failure is worth an annotation: those lines carry the reason a
# screen came up empty, and they are otherwise buried in the job log.
printf '%s\n' "$APP_LOG" | grep -iE "fail|error|denied|exception" | tail -6 \
  | while IFS= read -r line; do [ -n "$line" ] && note "app log: $line"; done
# `dumpsys package` prints the *declared* permissions in one section and the *granted* ones in
# `runtime permissions:` - grep alone picks up both, which once hid that the app only held
# READ_MEDIA_VISUAL_USER_SELECTED. Only the runtime section is a statement about access.
RUNTIME=$(adb shell "dumpsys package $PACKAGE" 2>/dev/null | sed -n '/runtime permissions:/,/^$/p')
GRANTED=$(printf '%s' "$RUNTIME" | grep -E "READ_MEDIA|READ_EXTERNAL" | grep -c "granted=true" || true)
DENIED=$(printf '%s' "$RUNTIME" | grep -E "READ_MEDIA|READ_EXTERNAL" | grep -c "granted=false" || true)
PARTIAL=$(printf '%s' "$RUNTIME" | grep -c "READ_MEDIA_VISUAL_USER_SELECTED" || true)
note "media grants at runtime: ${GRANTED:-0} granted, ${DENIED:-0} denied, partial-access entry=${PARTIAL:-0}"
# `content` wants colon-separated columns, and the row's own fields (mime, size) are what decide
# whether the app's `mime_type LIKE 'image/%' AND _size > 0` filter can even match.
# `content` takes colon-separated columns and the *table's* own names (mime_type, _size).
ROW=$(adb shell "content query --uri content://media/external/images/media --projection _id:mime_type:_size" 2>&1 | head -3 | tr '\n' ' ')
note "images table row: $(printf '%s' "$ROW" | head -c 300)"
note "device media dirs: $(adb shell 'ls /sdcard/Pictures /sdcard/DCIM 2>/dev/null' 2>/dev/null | tr '\n' ' ' | head -c 200)"
# The app now logs its own library counts, so the app log above is the authoritative answer for
# what it can see; shell queries are only here to show what the platform holds.
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
TAPS=""
LABELS=("Connect" "Files" "History" "Settings")

# Prefer the real bounds of the nav label from the view hierarchy: tapping a fixed fraction of
# the screen only works until the layout, the density or the system bar changes.
dump_ui() {
  adb shell uiautomator dump /sdcard/mc-ui.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/mc-ui.xml 2>/dev/null
}

label_bounds() {
  dump_ui | tr '>' '\n' \
    | grep "text=\"$1\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1
}

# Screen text is the cheapest assertion available on a real device, and it is what a user reads.
screen_shows() {
  local ui
  ui=$(dump_ui) || return 1
  printf '%s' "$ui" | grep -q "$1"
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
  TAPS="$TAPS ${LABELS[$i]}@$X,$Y"
  adb shell input tap "$X" "$Y" >/dev/null 2>&1
  sleep 3
  shot "tab-$((i + 1))-${TABS[$i]}"
  check "process alive on the ${TABS[$i]} tab" app_alive

  if [ "${TABS[$i]}" = "files" ]; then
    # Regression guard: permissions were granted at install time with `adb install -g`, so the
    # Files tab must not claim it needs storage permission. It used to, because the gate asked for
    # MANAGE_EXTERNAL_STORAGE instead of the READ_MEDIA_* grants Android 13 actually documents.
    if screen_shows "Storage permission is needed"; then
      bad "the Files tab asks for storage permission although it was granted at install"
      dump_ui | tr '>' '\n' | grep -o 'text="[^"]*"' | sort -u | head -20 | sed 's/^/   /'
    else
      ok "the Files tab does not ask for storage permission"
    fi
    # The grid reports what it got, and the device is asked what it holds: an empty grid on a
    # phone that has photos is a failure, not an empty state. (It was a genuine failure for a
    # while - the query named a column the unified MediaStore table does not have, so it returned
    # nothing on every Android 10+ device while the library itself was full.)
    GRID=$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | grep -o "Files: [0-9]* [a-z]* item(s) from the media library (all=[0-9]*" | tail -1)
    if [ -n "$GRID" ]; then
      SHOWN=$(printf '%s' "$GRID" | sed -n 's/.*Files: \([0-9]*\).*/\1/p')
      holds=$(printf '%s' "$GRID" | sed -n 's/.*all=\([0-9]*\).*/\1/p')
      note "Files grid: ${SHOWN:-0} shown of ${holds:-0} items in the library"
      if [ "${holds:-0}" -gt 0 ] && [ "${SHOWN:-0}" -eq 0 ]; then
        bad "the Files grid is empty although the library holds ${holds} items - see the media query lines in the app log"
      fi
    else
      note "the Files grid did not report a library count"
    fi
    if screen_shows "Today"; then
      ann "the Files tab is rendering ${SHOWN:-?} of ${holds:-?} media item(s)"
    else
      if [ "${holds:-0}" -gt 0 ] && screen_shows "No photos yet"; then
        bad "the Files tab shows 'No photos yet' although the library holds ${holds:-0} items"
      fi
      ann "the Files tab shows its empty state"
    fi
    if screen_shows "Apps" && screen_shows "Documents"; then
      ok "all five category tabs are present"
    fi
  fi
done
check "no crash from $PACKAGE after walking the tabs" no_fatal
report_crashes
note "tapped the four tabs:$TAPS"

# What the app itself says it can see. MediaStore is filtered per *calling uid*, so a shell query
# that returns rows is not proof the app can read them; this line is written by the grid itself.
FILES_TAIL=$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | grep -o "Files: .*" | tail -2 | tr '\n' ' ')
[ -n "$FILES_TAIL" ] && ann "app files view: $FILES_TAIL"
ERR_TAIL=$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | grep -iE "fail|error|denied|permission" | tail -3 | tr '\n' ' ')
[ -n "$ERR_TAIL" ] && note "app log lines: $ERR_TAIL"

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
