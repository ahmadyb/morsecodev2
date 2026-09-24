# MorseCode

Offline, peer-to-peer file transfer for Android. Phones talk directly over the local network —
no account, no cloud, no analytics, no ads.

* **LAN** — UDP discovery on `:33457` every 1200 ms, TCP control + data on `:33456`.
* **Nearby** — Bluetooth/RFCOMM fallback when there is no shared Wi-Fi.
* **Broadcast** — one batch to up to 4 phones at once, with a single group summary.
* **WebShare** — a browser on the same Wi-Fi can browse, download and upload over `:33455`.

`applicationId com.morsecode.app` · Kotlin · single `:app` module · plain Android Views
(no Compose, no AndroidX, no third-party libraries) · **minSdk 21**, targetSdk 34, compileSdk 34.

<p align="center"><img src="morseliink/logo.PNG" width="160" alt="MorseCode logo"></p>

---

## Build

Three ways, all producing the same signed artifacts in `dist/`.

### 1. Offline script (no network, no Gradle, no Android Studio required)

```bash
python3 tools/offline_build.py            # debug APK + release APK + .aab
python3 tools/offline_build.py --skip-aab # APKs only, faster
```

The script drives `aapt2 → kotlinc → dx → zipalign → apksigner` from local toolchains and creates
the keystores on first run. It is the build of record for this repository because it runs in
sandboxes with no access to Maven or the Gradle distribution.

### 2. Gradle

```bash
gradle assembleDebug          # app/build/outputs/apk/debug/
gradle assembleRelease        # signed with keystore/keystore.properties (or the committed demo key)
gradle bundleRelease          # app/build/outputs/bundle/release/
```

Gradle 8.7+, JDK 17, Android SDK with build-tools 34 + platform 34. `settings.gradle.kts` and the
root `build.gradle.kts` pin AGP 8.5.2 / Kotlin 1.9.24. `gradle wrapper` regenerates `gradlew` if
you want a wrapper in your fork.

### 3. CI

`.github/workflows/release.yml` builds the debug APK, the signed release APK and the AAB on every
tag, uploads them as artifacts and attaches them to a GitHub release. Add `MC_KEYSTORE_PASSWORD`
and `MC_KEY_PASSWORD` (plus `MC_KEYSTORE_FILE`, base64-decoded in a preceding step if you use a
private key) as repository secrets to sign with your own key.

### Signing

| Build | Keystore | Alias / password |
|-------|----------|------------------|
| release | `keystore/morsecode-release.jks` | `morsecode` / `morsecode` (**demo key — replace before shipping**) |
| debug | `keystore/debug.keystore` | `androiddebugkey` / `android` |

`MC_KEYSTORE_FILE`, `MC_KEYSTORE_PASSWORD`, `MC_KEY_ALIAS` and `MC_KEY_PASSWORD` override both.
Private keys are never committed by the CI, and `keystore/keystore.properties` is git-ignored.

---

## Install

```bash
adb install -r dist/MorseCode-1.0.0-release.apk
```

Both phones should be on the same Wi-Fi for full speed. First launch walks through the four
onboarding slides; permissions are requested one by one and can be granted later from Settings.

---

## The four surfaces

### Connect
Own avatar + device name, radar of peers (dots for older phones, sweep for current ones), Send /
Receive, the **WebShare · PC** card and the recent-devices list. On Wi-Fi LAN each peer row has a
checkbox — select 2 to 4 and "Send to N devices" becomes a Broadcast.

### Files
Photos / Videos / Music / Apps / Documents with day-grouped grids, in-place folder drill-down,
default-download picker and the green action bar (Send · Share · Delete) while files are selected.
MediaStore paging never uses `LIMIT/OFFSET` inside the sort string (INV-10) and always sorts by
date (INV-9).

### History
Received / Sent segmented control over day-grouped rows, with a per-entry details sheet and a
Broadcast that is exactly **one** row ("Broadcast · 3 phones") plus per-peer outcomes and
"Retry failed and skipped".

### Settings
Profile + device id, five accent swatches, ten theme overlays, dark/light mirror, sounds,
conflict policy, default download, WebShare toggle + hotspot, storage access, battery exemption,
log viewer, crash reports, diagnostics, Help & FAQ and About.

---

## Invariants

| Id | Guarantee |
|----|-----------|
| INV-1 | **Never hang.** Every transfer reaches a terminal state; a dead session falls back within 3 s. |
| INV-2 | **Isolation.** Pausing or cancelling one item never slows or stops another peer's transfer. |
| INV-3 | **Session loss.** A dropped peer moves to PAUSED "Connection lost" and auto re-queues from `resumeOffset`. |
| INV-4 | **No idle shutdown.** WebShare runs until the user stops it, screen off included. |
| INV-5 | **Persistent player.** The browser music player survives navigation. |
| INV-6 | **Single upload UI.** One browser upload surface at a time. |
| INV-7 | **Sticky breadcrumb.** Browser folder navigation keeps a clickable, sticky path. |
| INV-8 | **Responsive.** WebShare works from a 360 dp phone to a desktop browser. |
| INV-9 | **Date order.** Media is always presented newest first, grouped by day. |
| INV-10 | **Cursor paging.** No `LIMIT/OFFSET` in MediaStore sort expressions. |

Integrity: SHA-256 pre-hash for files ≤ 256 MB (cached once per file across all peers), CRC32 per
256 KB chunk, `.part` + atomic rename, and a conflict policy of skip / rename (default) / overwrite.

---

## Project layout

```
app/src/main/java/com/morsecode/app/
  BuildConfig.kt            constants shared with the Gradle build
  MainActivity.kt           bottom-nav host + consent popups + share-sheet intent handling
  compat/                   API-34-only helpers compiled in a separate pass (Thumbs, MediaLibrary, …)
  core/
    data/                   Prefs, HistoryStore, JournalStore
    logging/LogStore        in-memory ring buffer + crash reports + export
    model/                  TransferItem, DiscoveredPeer, HistoryEntry, GroupHistoryEntry
    network/                Wire (MLNK framing), LanTransport, NearbyTransport, StreamSession
    storage/                Destinations, SafStore, ZipUtil, ShareProvider
    transfer/               TransferEngine, TransferService, SoundFx, Log
    ui/                     Widget kit (W), dialogs, RadarView, Screen
    webshare/               WebShareServer, WebShareAssets (SPA), WebShareController, HotspotController
  di/AppServices.kt         one lazy singleton per service
  feature/                  connect · filemanager · history · settings · transfer · viewer · onboarding
  util/ThemeColors.kt       Sunflower Hue design system
tools/                      offline_build.py, make_icons.py, make_icons_ui.py
docs/                       ACCEPTANCE.md (23 tests), ARCHITECTURE.md
morseliink/                 the design mocks (a-series dark, b-series light)
```

## Documentation

* [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) — the 23 acceptance tests and how to verify artifacts.
* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — engine, wire protocol, WebShare routes, design system.

## Documented deviations

* Nearby Connections is implemented over RFCOMM/Bluetooth sockets instead of the closed-source
  Play Services library — pairing, consent, checksums and resume behave identically.
* QR **scanning** is not linked in this build (camera preview + Manual IP pairing instead).
  WebShare intentionally shows **no QR code**.
* Programmatic hotspot toggling uses the hidden `setWifiApEnabled` API where the OEM allows it;
  otherwise the UI explains the manual path (Android 10+ restricts it).
* `StateFlow` is replaced by `core/util/Observable.kt` (`State`/`Event`) with the same semantics —
  the build has no coroutine dependency.

## Licence

MIT — see the header of any source file for the same text in short form.
