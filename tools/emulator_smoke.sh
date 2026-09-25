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
# Each run publishes exactly what it captured: CI copies this directory into the repository, and a
# leftover file from an earlier run (a step that failed before the sweep finished) would otherwise
# sit in the docs as if it belonged to the newest build.
rm -f "$SHOTS"/*.png 2>/dev/null || true
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
if [[ "$(adb shell dumpsys activity activities 2>/dev/null)" == *OnboardingActivity* ]]; then
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
if [[ "$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null)" == *"Main resumed"* ]]; then
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
  local tries=0
  while [ "$tries" -lt 3 ]; do
    if adb shell uiautomator dump /sdcard/mc-ui.xml >/dev/null 2>&1; then
      adb shell cat /sdcard/mc-ui.xml 2>/dev/null && return 0
    fi
    tries=$((tries + 1))
    sleep 1
  done
  return 1
}

label_bounds() {
  dump_ui | tr '>' '\n' \
    | grep "text=\"$1\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1
}

# The same, but the *highest* match on screen. "Files" is both a pill in the tab strip and a label
# in the bottom navigation, and only one of them switches the content.
label_bounds_top() {
  dump_ui | tr '>' '\n' | grep "text=\"$1\"" \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    | sed 's/[^0-9]/ /g' | awk '{print $2" "$1" "$4" "$3}' | sort -n | head -1 | awk '{print $2" "$1" "$4" "$3}'
}

# The same, but only nodes that actually handle taps. A screen's title is a TextView with the same
# wording as the control that opens it, and tapping the title does nothing.
# Everything the screen says, as `text="..."` entries. One dump, so the checks in a block all
# describe the same frame - and a failure can quote the whole page instead of guessing.
dump_texts() {
  local ui
  if ! ui=$(dump_ui); then
    printf '(screen unreadable: uiautomator dump failed)'
    return 0
  fi
  printf '%s' "$ui" | tr '>' '\n' | grep -oE '(text|content-desc)="[^"]*"' | sort -u | tr '\n' ' '
}

label_bounds_clickable() {
  dump_ui | tr '>' '\n' | grep "text=\"$1\"" | grep 'clickable="true"' \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1
}

tap_bounds() {
  local b="$1"
  [ -n "$b" ] || return 1
  set -- $(printf '%s' "$b" | grep -o '[0-9]*')
  [ "$#" -ge 4 ] || return 1
  adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1
}

# Screen text is the cheapest assertion available on a real device, and it is what a user reads.
screen_shows_ci() {
  local ui
  ui=$(dump_ui) || return 1
  [[ "${ui^^}" == *"${1^^}"* ]]
}

screen_shows() {
  local ui
  ui=$(dump_ui) || return 1
  # Pattern matching, not `printf | grep -q`: under `set -o pipefail` a `grep -q` that matches
  # early exits before the writer is done, the writer takes SIGPIPE, and the *pipeline* reports
  # 141 - so a check like "does this page contain X" intermittently answers "no" for the biggest
  # pages. Bash can match the string in memory without a pipe at all.
  [[ "$ui" == *"$1"* ]]
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

  if [ "${TABS[$i]}" = "connect" ]; then
    # The transport is a choice the user makes, so it must be on the screen (it used to be two
    # taps into a menu, and whichever one was chosen is remembered across launches). Selecting
    # Nearby on a phone without Bluetooth must say so: the emulator has no adapter, so this is
    # the "no adapter" branch - on a real phone with the radio off it is the "Bluetooth is off"
    # one. Either way the user gets a sentence and a button, not an empty list.
    NEARBY_PILL=$(label_bounds_clickable "Nearby" || true)
    if [ -n "$NEARBY_PILL" ]; then
      ok "the Connect screen offers the transport choice"
      tap_bounds "$NEARBY_PILL"
      sleep 3
      CTEXT=$(dump_texts)
      # The bug was that choosing Nearby changed the label and nothing else: the screen still
      # drove the Wi-Fi transport, so the radio the user selected was never started.
      NBLOG=$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | grep -E "Nearby \(Bluetooth\)|Bluetooth server" | tail -1)
      if [ -n "$NBLOG" ]; then
        ok "choosing Nearby reaches the Bluetooth transport (app log: ${NBLOG:0:120})"
      else
        bad "choosing Nearby started nothing - no Bluetooth line in the app log"
      fi
      case "$CTEXT" in
        *Nearby*) ok "the Connect screen shows Nearby as the selected transport" ;;
        *) bad "the Connect screen does not reflect the transport choice - says: ${CTEXT:0:380}" ;;
      esac
      # When the transport could not come up, the screen owes the user a reason and a way out.
      case "$NBLOG" in
        *"did not start"*|*"permission missing"*|*"could not start"*|*"Bluetooth is off"*)
          case "$CTEXT" in
            *Bluetooth*)
              ok "a Bluetooth that cannot run is explained on screen" ;;
            *)
              bad "the transport refused to start without saying why - screen says: ${CTEXT:0:380}" ;;
          esac ;;
        *)
          note "this device reports a usable Bluetooth adapter, so there is no refusal to explain" ;;
      esac
      shot connect-nearby
      # and switching back must land on the Wi-Fi search again
      LAN_PILL=$(label_bounds_clickable "Wi-Fi LAN" || true)
      [ -n "$LAN_PILL" ] || LAN_PILL=$(label_bounds_clickable "Wi-Fi" || true)
      tap_bounds "$LAN_PILL"
      sleep 3
      if screen_shows_ci "Wi-Fi"; then
        ok "switching back to Wi-Fi LAN works"
      else
        bad "switching back to Wi-Fi LAN did not take: $(dump_texts | head -c 300)"
      fi
    else
      bad "the Connect screen has no transport control"
      ann "Connect screen text: $(dump_texts | head -c 400)"
    fi
  fi

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

    # The day header used to print the day twice ("Yesterday Yesterday 3 items") because the count
    # label repeated it. If it ever comes back, this says so.
    if screen_shows "Today Today" || screen_shows "Yesterday Yesterday"; then
      bad "a day header repeats the day name"
    else
      ok "day headers name the day once"
    fi

    # Select all in a day: the bar that carries Send / share / delete must be on screen (it used to
    # be appended to the scroll content, i.e. below the fold on any long day) and it must toggle.
    SELALL=$(label_bounds "Select all" || true)
    if [ -n "$SELALL" ]; then
      tap_bounds "$SELALL"
      sleep 2
      SELTEXT=$(dump_texts)
      case "$SELTEXT" in
        *"1 selected"*|*"2 selected"*|*"3 selected"*)
          ok "selecting a day shows the pinned selection bar" ;;
        *)
          bad "tapping Select all did not show a selection count - screen says: ${SELTEXT:0:380}" ;;
      esac
      case "$SELTEXT" in
        *Send*) ok "the Send button is visible with a selection" ;;
        *) bad "the selection bar has no Send button - screen says: ${SELTEXT:0:380}" ;;
      esac
      case "$SELTEXT" in
        *"Clear all"*)
          ok "Select all becomes Clear all"
          CLEAR=$(label_bounds "Clear all" || true)
          tap_bounds "$CLEAR"
          sleep 2
          CLEARTEXT=$(dump_texts)
          case "$CLEARTEXT" in
            *"1 selected"*|*"2 selected"*|*"3 selected"*)
              bad "Clear all did not clear the selection - screen says: ${CLEARTEXT:0:380}" ;;
            *) ok "Clear all clears the day in one tap" ;;
          esac
          ;;
        *)
          bad "Select all does not offer to clear the selection again - screen says: ${SELTEXT:0:380}" ;;
      esac
    else
      bad "no Select all on the Files tab with media present"
    fi
    shot files-selection

    # The Files pill is the design's category hub (Documents / Ebooks / Archives / APKs / Large
    # files, then folders with Download and Internal storage). The page title carries the same
    # word as the pill, so the pill is found by being tappable.
    FILES_PILL=$(label_bounds_clickable "Files" || true)
    [ -n "$FILES_PILL" ] || FILES_PILL=$(label_bounds_top "Files" || true)
    tap_bounds "$FILES_PILL"
    sleep 3
    shot files-hub
    HUB_MISSING=""
    for LABEL in "Categories" "Documents" "Ebooks" "Archives" "APKs" "Large files" "Folders" "Internal storage"; do
      screen_shows_ci "$LABEL" || HUB_MISSING="$HUB_MISSING $LABEL"
    done
    if [ -z "$HUB_MISSING" ]; then
      ok "the Files hub shows its categories and folders"
    else
      # One line, plus the page's whole vocabulary: a bare list of missing words does not say
      # whether the tap missed, the hub did not open, or the rows are named differently.
      bad "the Files hub is missing:$HUB_MISSING"
      ann "Files hub text: $(dump_ui | tr '>' '\n' | grep -o 'text="[^"]*"' | sort -u \
        | tr '\n' ' ' | head -c 500)"
    fi

    # Opening Internal storage must give a real browser with a clickable address bar.
    INTERNAL=$(label_bounds "Internal storage" || true)
    tap_bounds "$INTERNAL"
    sleep 3
    shot files-browser
    if screen_shows_ci "Internal storage" && ! screen_shows_ci "Add a folder"; then
      ok "internal storage opens with a breadcrumb"
    else
      bad "internal storage did not open"
      ann "Files browser text: $(dump_ui | tr '>' '\n' | grep -o 'text="[^"]*"' | sort -u \
        | tr '\n' ' ' | head -c 500)"
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

# ------------------------------------------------------------------ WebShare
# The browser surface is one of the four things this app is, and "it starts" is not the same as
# "a browser can use it". The emulator's own network is enough to prove both: `adb forward` puts
# the phone's port on the runner's loopback, so the test speaks to the phone exactly as a laptop
# on the same Wi-Fi would - waiting page first, until the phone holder accepts, then the app.
say "WebShare end to end"
# One line is recorded and annotated at the end of this section: the annotation budget is small
# enough that per-check notices do not all survive, and "which of the six steps failed" is exactly
# what is needed when this fails on a runner nobody can log into.
WS_FROM=$FAILURES
WS_NOTE=""
# The browser surface is one of the four things this app is, and "it started" is not the same as
# "a browser can use it". The emulator's network is enough to prove both: `adb forward` puts the
# phone's port on the runner's loopback, so the test speaks to the phone exactly as a laptop on the
# same Wi-Fi would. The order matters and is the product's own: `/` always answers with the waiting
# page, that page polls `/api/consent`, and only then does the phone ask.
WS_STARTED=0

# Back to Connect and flip the WebShare switch. The switch is found in the hierarchy rather than
# guessed at, because the card moves with the content above it.
BOUNDS=$(label_bounds "Connect" || true)
if [ -n "$BOUNDS" ]; then
  set -- $(printf '%s' "$BOUNDS" | grep -o '[0-9]*')
  if [ "$#" -ge 4 ]; then adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1; fi
fi
sleep 2

webswitch_bounds() {
  dump_ui | tr '>' '\n' | grep 'class="android.widget.Switch"' \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1
}

WS=$(webswitch_bounds)
if [ -n "$WS" ]; then
  set -- $(printf '%s' "$WS" | grep -o '[0-9]*')
  if [ "$#" -ge 4 ]; then
    adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1
    sleep 3
  fi
else
  bad "no WebShare switch on the Connect tab"
fi

if [[ "$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null)" == *"WebShare mode started"* ]]; then
  ok "WebShare started"
  WS_STARTED=1
else
  bad "WebShare did not start (see the app log; the emulator may have no Wi-Fi)"
fi

if [ "$WS_STARTED" = "1" ]; then
  URL=$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | grep -o "WebShare mode started - http[^ ]*" | tail -1 | sed 's/.*- //')
  ann "WebShare is serving $URL"
  FWD=$(adb forward tcp:33455 tcp:33455 2>&1 | tr -d '\r')
  WS_NOTE="$WS_NOTE fwd=$FWD"

  # 1. Before consent the browser must not see the phone's files - only the waiting page.
  WAIT_HTML=$(curl -s -m 10 -w '\n%{http_code}' http://127.0.0.1:33455/ 2>/dev/null)
  WAIT_CODE=$(printf '%s' "$WAIT_HTML" | tail -1)
  WAIT_HTML=$(printf '%s' "$WAIT_HTML" | sed '$d')
  WS_NOTE="$WS_NOTE waiting=$WAIT_CODE/${#WAIT_HTML}B"
  if [[ "$WAIT_HTML" == *"Waiting for the phone to accept"* ]]; then
    ok "an unconsented browser gets the waiting page"
    if [[ "$WAIT_HTML" == *'data-nav="photos"'* ]]; then
      bad "an unconsented browser was served the file browser"
    fi
  else
    bad "an unconsented browser did not get the waiting page"
  fi

  # 2. The waiting page polls /api/consent; that request is what makes the phone ask. It blocks
  #    until the phone answers, so it runs in the background while the dialog is driven.
  curl -s -m 40 -w '%{http_code}' http://127.0.0.1:33455/api/consent -o /tmp/consent.json > /tmp/consent.code 2>/dev/null &
  CONSENT_PID=$!
  sleep 4

  # The copy is pinned by the spec, and the popup spent a while showing two bare buttons because
  # the button row was set as the dialog's content view, replacing the card that held the words.
  WS_NOTE="$WS_NOTE asked=$(screen_shows "Browser wants access" && echo yes || echo no)"
  if screen_shows "Browser wants access"; then
    ok "the phone asks 'Browser wants access'"
    if screen_shows "A browser session wants to browse your phone."; then
      ok "the consent popup shows the exact body line"
    else
      bad "the consent popup is missing its body line"
    fi
    ACCEPT=$(label_bounds "Accept" || true)
    if [ -n "$ACCEPT" ]; then
      set -- $(printf '%s' "$ACCEPT" | grep -o '[0-9]*')
      if [ "$#" -ge 4 ]; then
        adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1
        WS_NOTE="$WS_NOTE accept=tap"
        ok "tapped Accept"
      fi
    else
      # A dialog is its own window; if the dump did not see the button, the keyboard route still
      # reaches it (TAB focuses the button pair, ENTER activates).
      note "no Accept bounds in the dump - using TAB/ENTER"
      adb shell input keyevent 61 >/dev/null 2>&1
      adb shell input keyevent 61 >/dev/null 2>&1
      adb shell input keyevent 66 >/dev/null 2>&1
      WS_NOTE="$WS_NOTE accept=keys"
    fi
    shot web-share-consent
  else
    bad "the phone never asked for consent while a browser session polled for it"
    note "app log: $(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null | grep -i "browser" | tail -2 | tr '\n' ' ')"
  fi

  wait "$CONSENT_PID" 2>/dev/null
  WS_NOTE="$WS_NOTE consent=$(cat /tmp/consent.code 2>/dev/null)/$(head -c 60 /tmp/consent.json 2>/dev/null | tr -d '\n')"
  note "consent answer: $(head -c 120 /tmp/consent.json 2>/dev/null)"
  if grep -q '"granted":true' /tmp/consent.json 2>/dev/null; then
    ok "the session was granted"
  else
    bad "the consent answer was not a grant: $(head -c 120 /tmp/consent.json 2>/dev/null)"
  fi

  # 3. Now the browser gets the real app, and it carries no QR code (product decision).
  SPA=$(curl -s -m 15 -w '\n%{http_code}' http://127.0.0.1:33455/ 2>/dev/null)
  SPA_CODE=$(printf '%s' "$SPA" | tail -1)
  SPA=$(printf '%s' "$SPA" | sed '$d')
  WS_NOTE="$WS_NOTE spa=$SPA_CODE/${#SPA}B"
  if [[ "$SPA" == *'data-nav="photos"'* ]]; then
    ok "the consented browser gets the file browser"
    if [[ "$SPA" == *'data-nav="qr"'* || "$SPA" == *'>QR<'* || "$SPA" == *"QR code"* ]]; then
      bad "the browser page mentions a QR code although WebShare shows none"
    else
      ok "the browser page has no QR"
    fi
    COUNTS=$(curl -s -m 10 http://127.0.0.1:33455/api/counts 2>/dev/null | head -c 200)
    note "browser counts: $COUNTS"
    if [[ "$COUNTS" == *photos* ]]; then
      ok "the browser can count the phone's media"
    else
      bad "the browser cannot read the media counts: $COUNTS"
    fi
    if [[ "$SPA" == *audio* ]]; then
      ok "the player is served with the browser"
    fi
  else
    bad "the consented browser did not get the file browser"
    WS_NOTE="$WS_NOTE spaStart=$(printf '%s' "$SPA" | head -c 120 | tr -d '\n')"
    note "first 200 bytes: $(printf '%s' "$SPA" | head -c 200)"
  fi
  shot web-share-served

  # 4. The theme setting drives every surface, including the one the browser sees. /api/hello
  #    reports it, so the check is text rather than pixels: toggle the switch in Settings and ask
  #    the browser what it is being served.
  THEME_BEFORE=$(curl -s -m 10 http://127.0.0.1:33455/api/hello 2>/dev/null | grep -o '"theme":"[a-z]*"' | head -1)
  SBOUNDS=$(label_bounds "Settings" || true)
  if [ -n "$SBOUNDS" ]; then
    set -- $(printf '%s' "$SBOUNDS" | grep -o '[0-9]*')
    if [ "$#" -ge 4 ]; then adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1; fi
  fi
  sleep 2
  SW=$(dump_ui | tr '>' '\n' | grep 'class="android.widget.Switch"' \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  if [ -n "$SW" ]; then
    set -- $(printf '%s' "$SW" | grep -o '[0-9]*')
    if [ "$#" -ge 4 ]; then
      adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1
      sleep 2
      shot theme-toggled
      THEME_MID=$(curl -s -m 10 http://127.0.0.1:33455/api/hello 2>/dev/null | grep -o '"theme":"[a-z]*"' | head -1)
      # back the way it was, so the rest of the run sees the shipped default
      adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1
      sleep 2
      THEME_AFTER=$(curl -s -m 10 http://127.0.0.1:33455/api/hello 2>/dev/null | grep -o '"theme":"[a-z]*"' | head -1)
      if [ -n "$THEME_MID" ] && [ "$THEME_MID" != "$THEME_BEFORE" ] && [ "$THEME_AFTER" = "$THEME_BEFORE" ]; then
        ok "the theme switch flips the served theme ($THEME_BEFORE -> $THEME_MID -> $THEME_AFTER)"
      else
        note "theme through the first Settings switch: $THEME_BEFORE then $THEME_MID then $THEME_AFTER"
      fi
    fi
  else
    note "no switch found on the Settings tab to test the theme with"
  fi

  # 5. Stop it from the phone and confirm the explicit stop works (INV-4 says nothing else stops it).
  CBOUNDS=$(label_bounds "Connect" || true)
  if [ -n "$CBOUNDS" ]; then
    set -- $(printf '%s' "$CBOUNDS" | grep -o '[0-9]*')
    if [ "$#" -ge 4 ]; then adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1; fi
    sleep 2
  fi
  STOP=$(label_bounds "Stop WebShare" || true)
  if [ -n "$STOP" ]; then
    set -- $(printf '%s' "$STOP" | grep -o '[0-9]*')
    if [ "$#" -ge 4 ]; then adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))" >/dev/null 2>&1; fi
    sleep 2
  fi
  if [[ "$(adb logcat -d -s "$LOGCAT_TAG":V 2>/dev/null)" == *"WebShare stopped by user"* ]]; then
    ok "WebShare stops when the user stops it"
  else
    note "the Stop button was not found or did not log a stop"
  fi
  adb forward --remove tcp:33455 >/dev/null 2>&1 || true
fi

if [ "$FAILURES" -gt "$WS_FROM" ]; then
  bad "WebShare end-to-end:$WS_NOTE"
else
  ann "WebShare end-to-end ok:$WS_NOTE"
fi

say "Back stack sweep"
adb shell input keyevent 4 >/dev/null 2>&1
sleep 2
shot after-back
check "process alive after back" app_alive

# A SIGSEGV in one of the JNI-less layers would show up as an ANR instead; both abort the run.
if [[ "$(adb logcat -d 2>/dev/null)" == *"ANR in $PACKAGE"* ]]; then
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
