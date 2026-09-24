#!/usr/bin/env bash
#
# CI wrapper around tools/emulator_smoke.sh.
#
# The emulator action runs its `script:` through /bin/sh, so this exists to make sure the test
# itself runs under bash (the smoke script uses arrays and PIPESTATUS) and to mirror the verdict
# into workflow annotations - the one channel that is readable even when job logs and artifacts
# are not (rate limits, expired artifacts, restricted sandboxes).
#
# Because that channel has a budget (a step keeps roughly its first ten annotations and drops the
# rest), the annotation set here is deliberately small and ordered: one entry notice, the errors
# the test itself raised, and - if bash never got as far as the test - nothing at all, which is
# itself the answer (see the "Smoke diagnostics" step in the workflow).
#
# Usage:  tools/ci_smoke.sh [version]
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG=/tmp/smoke.log
STARTED=/tmp/smoke.started
FINISHED=/tmp/smoke.exit

# Breadcrumbs for the diagnostics step: whether this shell started, and how it ended.
printf 'started %s pwd=%s args=%s\n' "$(date -u +%H:%M:%S)" "$PWD" "$*" > "$STARTED"
rm -f "$FINISHED"
echo "::notice title=smoke-entry::smoke test starting (pwd=$PWD, seed=${MORSE_MEDIA_DIR:-script-relative})"

# Anything at all that goes wrong after this point leaves a readable trace: the exit code and the
# last lines of the test output, annotated so they survive even when the job log is unreachable.
finish() {
  local rc=$?
  printf '%s\n' "$rc" > "$FINISHED"
  [ "$rc" -eq 0 ] && return 0
  echo "::error title=smoke-exit::the smoke script exited rc=$rc"
  if [ -s "$LOG" ]; then
    tail -12 "$LOG" | while IFS= read -r line; do
      [ -n "$line" ] && echo "::error title=smoke-tail::${line:0:400}"
    done
  else
    echo "::error title=smoke-tail::no output was captured in $LOG"
  fi
  return 0
}
trap finish EXIT

bash "$HERE/emulator_smoke.sh" "${1:-}" 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}

# Crashes are reported line by line by the smoke script itself; this catches anything that got
# printed but not annotated, so a FATAL line never disappears into an unreadable log.
if [ "$STATUS" -ne 0 ]; then
  grep -E "FATAL EXCEPTION|ANR in|IllegalState|ClassNotFound|VerifyError" "$LOG" 2>/dev/null | head -4 \
    | while IFS= read -r line; do
        echo "::error title=smoke::${line:0:800}"
      done
fi

if [ "$STATUS" -eq 0 ]; then
  echo "::notice title=smoke::the release APK installed, opened and survived all four tabs"
else
  echo "::error title=smoke::the app under test did not pass - see the check lines above"
fi

trap - EXIT
printf '%s\n' "$STATUS" > "$FINISHED"
exit "$STATUS"
