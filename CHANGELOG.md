# Changelog

All notable changes to MorseCode. Releases are built and published by GitHub Actions:
`v1.2.3` becomes `versionName 1.2.3`, `versionCode 10203`, and the tag is created on the commit
the artifacts were built from.

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
