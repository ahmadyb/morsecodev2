# Changelog

All notable changes to MorseCode. Releases are built and published by GitHub Actions:
`v1.2.3` becomes `versionName 1.2.3`, `versionCode 10203`, and the tag is created on the commit
the artifacts were built from.

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
