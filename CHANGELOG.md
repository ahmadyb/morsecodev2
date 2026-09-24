# Changelog

All notable changes to MorseCode. Releases are built and published by GitHub Actions:
`v1.2.3` becomes `versionName 1.2.3`, `versionCode 10203`, and the tag is created on the commit
the artifacts were built from.

## 1.0.3 — the popups say what they say, and the browser surface is proven

### Fixed
- **Consent popups showed no copy.** `Ui.consent()` added its Accept/Reject row inside the
  dialog's `onShow` with a second `setContentView`, which *replaces* the view the dialog was
  built with — so "Connection request" and "Browser wants access" came up as two lone buttons and
  none of the text the spec pins verbatim (title, body, `<device> · <transport> · <ip>`). The
  buttons are now part of the same content view, and the emulator gate asserts the words are on
  screen before it taps Accept.
- **WebShare carries no QR at all.** The browser page still had a "▦ QR" sidebar entry leading to
  a "QR codes are disabled" placeholder, and `/api/qr` answered with an empty QR-shaped response.
  The nav item, the page, the route and the endpoint are gone: WebShare shows the address in its
  header and nothing QR-shaped anywhere (as the product decision says).

### Added
- **The gate drives WebShare end to end.** After walking the tabs, the emulator run starts
  WebShare from the Connect card, reaches it over `adb forward` exactly as a laptop on the same
  Wi-Fi would, and checks the four things that matter: an unconsented browser gets only the
  waiting page; the phone asks *"Browser wants access"* with the exact copy; once accepted the
  browser gets the real file browser with no QR and working media counts (`/api/counts`); and
  Stop WebShare closes it again.

## 1.0.2 — the Files tab actually fills

### Fixed
- **Empty Files tab on Android 10 and newer.** The media library queried the unified
  `content://media/external/file` table with a projection that names `DATE_TAKEN`, a column only
  the per-type tables (images, video, audio) have. Android 10 turns that into
  `IllegalArgumentException: Invalid column DATE_TAKEN` and returns nothing at all, so Photos,
  Videos, Music and WebShare's file list were empty on a phone that was full of media — while the
  permission banner was gone and `all=3` showed the library really did hold rows. `MediaLibrary`
  now tries progressively plainer queries (optional columns dropped, `DATE_TAKEN` ordering replaced
  by `DATE_MODIFIED * 1000`) and remembers the one the device accepted; the sort fallback for
  `TITLE`, which the unified table also lacks, comes along with it.
- **An empty result and an unreadable row looked the same.** `MediaLibrary.page()` walked the
  cursor with `do { read } while (moveToNext())`, which reads row -1 before asking whether there is
  a row at all; on an empty cursor that threw `CursorIndexOutOfBoundsException`, was caught, and
  returned an empty list, so "the query matched nothing" could not be told apart from "the row
  could not be read". The cursor is positioned before the loop, and the library keeps a one-line
  account of what its query ladder did, printed by the grid:
  `query degraded to 12 columns after 13c/DATE_TAKEN=Invalid column DATE_TAKEN; page 3 row(s)`.
- **The empty state no longer hides a failure.** The grid logs what it got and what the library
  holds, and the Files tab names Android 14's partial-access state ("Only the photos and videos you
  selected are visible") instead of looking like a phone with no photos.

### Changed
- **The emulator gate asserts the Files tab.** An empty grid on a device that holds media now fails
  the run, and the test grants `READ_MEDIA_IMAGES`/`VIDEO`/`AUDIO` explicitly because
  `adb install -g` can leave a media app holding only `READ_MEDIA_VISUAL_USER_SELECTED`.
- **Annotations are curated.** The smoke test's routine output goes to `::debug` and only the facts
  that answer "what can the app see" become notices — a step keeps roughly ten of them. The test
  also leaves `/tmp/smoke.started` / `/tmp/smoke.exit` breadcrumbs and annotates its exit code, so
  an emulator that dies before the test can no longer fail the job silently.
- The smoke job pins `ubuntu-24.04` (22.04 entered deprecation with scheduled brownouts on
  2026-09-17; `ubuntu-latest` must not be able to change the emulator underneath a release).

## 1.0.1 — the app actually opens

### Fixed
- **Launch crash.** `ConnectFragment` (and the Broadcast view in `TransferFragment`) added the
  long-lived `RadarView` to a freshly built container on every refresh. Android gives a view one
  parent, so the first refresh after discovery started threw
  `IllegalStateException: The specified child already has a parent` and the app died on its first
  frame. Re-hosted views now go through `View.detach()` (`core/ui/UiExt.kt`); the rule is written
  down in `docs/ARCHITECTURE.md`.
- **Screenshots/launch evidence.** `LogStore` now mirrors its lines to logcat under the
  `MorseCode` tag, so `adb logcat -s MorseCode` shows the same trail as the in-app Log viewer and a
  bug report can say which screen was reached before a crash.

### Fixed
- **The offline build produced APKs that could not run.** `tools/offline_build.py` compiled the
  Kotlin sources but never dexed the Kotlin standard library, so the APK called into
  `kotlin.collections.*`, `kotlin.Result` and `kotlin.jvm.internal.Intrinsics` 101 times with
  nothing behind them — `NoClassDefFoundError` on launch. The runtime jars next to kotlinc are now
  dexed in, and `tools/dexcheck.py` (new) fails the build when any referenced class is neither
  bundled nor part of the platform. CI builds this path on every push to keep it working.

### Added
- **Emulator gate.** `tools/emulator_smoke.sh` installs the release APK on an API 34 emulator,
  opens it, walks all four tabs and fails on a crash, an ANR or a dead process. The `release.yml`
  workflow runs it between building and publishing, so nothing is released that has not been
  opened on a real Android runtime. Screenshots land in `docs/screenshots/`.
- **Icons traced from the brand artwork.** `tools/make_icons.py` now measures `morseliink/logo.PNG`
  (tile bounds, gradient, corner radius, the mark's bars, its 2x3 dot grid with the faded right
  column and the transmit bar), generates every launcher/adaptive/in-app/notification asset from
  that trace, and verifies the trace against the artwork (worst element edge deviation < 1.6 px,
  boundary-tolerant IoU > 0.97) before writing anything.

## 1.0.0 — first release

### Four surfaces
- **Connect** — own avatar and device id, live radar (dots for old phones, sweep for current ones),
  Send / Receive, the WebShare · PC card, and a recent-devices list where selecting 2–4 phones
  turns into a Broadcast.
- **Files** — Photos · Videos · Music · Apps · Documents with day-grouped grids, in-place folder
  drill-down, default-download picker and the green selection action bar (Send · Share · Delete).
- **History** — Received / Sent over day groups, per-entry details, and a Broadcast recorded as
  exactly one row with per-peer outcomes plus "Retry failed and skipped".
- **Settings** — profile, five accent swatches, ten theme overlays, dark/light mirror, sounds,
  conflict policy, default download, WebShare + hotspot, storage/battery, log viewer, crash
  reports, diagnostics, Help & FAQ accordion, About.

### Engine
- `TransferEngine` singleton with observable `State`/`Event`, one `Master` worker per peer,
  `LanSession` / `.part` resume, journal-backed recovery and per-item terminal fallback.
- **INV-1** never hang (3 s terminal fallback) · **INV-2** per-peer pause/cancel isolation ·
  **INV-3** session loss → PAUSED "Connection lost", auto re-queue from `resumeOffset`.
- SHA-256 pre-hash ≤ 256 MB (cached once per file across peers), CRC32 per 256 KB chunk,
  `.part` + atomic rename, conflict policy skip / rename (default) / overwrite.
- `MLNK` framing with a byte-by-byte JSON header read; 256 KB chunks; 45 s idle watchdog.
- UDP discovery on `:33457` every 1200 ms; TCP control + data on `:33456`;
  Bluetooth/RFCOMM fallback when there is no shared network.

### WebShare
- Plain-socket HTTP server on `0.0.0.0:33455`, consent-gated by the "Browser wants access" popup,
  no idle shutdown (**INV-4**), tokenless URL.
- Browser SPA: live sidebar counts, folder pills, single upload surface (**INV-6**), persistent
  music player (**INV-5**), sticky clickable breadcrumbs (**INV-7**), mobile responsive (**INV-8**).
- Intentionally shows **no QR code**.

### Build
- `tools/offline_build.py` — Gradle-free chain (`aapt2 → kotlinc → dx/d8 → zipalign → apksigner`)
  that also assembles the `.aab` by hand; the build of record where Maven is unreachable.
- Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 project files, with `assembleDebug`, `assembleRelease`
  and `bundleRelease` all green.
- GitHub Actions builds the three artifacts, verifies the signature, writes `SHA256SUMS.txt` and
  publishes the release.

### Known deviations
- Nearby Connections runs over RFCOMM/Bluetooth sockets rather than the Play Services library.
- QR decoding is not linked; pairing uses the Manual IP path (WebShare shows no QR by design).
- Programmatic hotspot toggling uses the hidden `setWifiApEnabled` API where the OEM allows it.
