#!/usr/bin/env bash
#
# CI wrapper around tools/emulator_smoke.sh.
#
# The emulator action runs its `script:` through /bin/sh, so this exists to make sure the test
# itself runs under bash (the smoke script uses arrays and PIPESTATUS) and to mirror the verdict
# into workflow annotations - the one channel that is readable even when job logs and artifacts
# are not (rate limits, expired artifacts, restricted sandboxes).
#
# Usage:  tools/ci_smoke.sh [version]
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG=/tmp/smoke.log

bash "$HERE/emulator_smoke.sh" "${1:-}" 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}

# Every check and every crash line is re-emitted as an annotation.
grep -E "FAIL|error:|IllegalState|FATAL|ANR in" "$LOG" 2>/dev/null | head -40 \
  | while IFS= read -r line; do
      echo "::error title=smoke::${line:0:800}"
    done

if [ "$STATUS" -eq 0 ]; then
  echo "::notice title=smoke::the release APK installed, opened and survived all four tabs"
else
  echo "::error title=smoke::the app under test did not pass - see the check lines above"
fi
exit "$STATUS"
