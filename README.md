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

<p align="center">
  <a href="https://github.com/ahmadyb/morsecodev2/releases/latest"><b>Download the latest release</b></a>
  &nbsp;·&nbsp; signed APK, debug APK and AAB, built by GitHub Actions
</p>

---

## Build

CI builds and validates every commit; `tools/offline_build.py` reproduces the same bytes locally
without Gradle or Maven, and Gradle is the third path. The published artifacts are the ones on the
releases page — `dist/` is only ever a local scratch directory.

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
you want a wrapper in your fork. The Gradle debug build carries the `.debug` application-id suffix
(standard AGP behaviour, installs side by side); the offline script keeps the plain
`com.morsecode.app` id for both APKs.

### Download instead of building

Every push is built on GitHub Actions, opened on an emulator and then published, so you can install
straight from [the releases page](https://github.com/ahmadyb/morsecodev2/releases/latest):

```bash
adb install -r MorseCode-1.0.1-release.apk         # phones
# MorseCode-1.0.1.aab -> Play Console -> Internal testing -> upload
```

> **v1.0.0 could not open.** The first release compiled but died on the first frame
> (`IllegalStateException: The specified child already has a parent` — the radar was re-added to a
> freshly built container on every refresh, and the Connect screen refreshes as soon as discovery
> starts). v1.0.1 fixes it and, more importantly, adds the emulator gate below so this class of bug
> cannot ship again.

### 3. CI — builds and publishes the release

`.github/workflows/release.yml` runs on every push to `main` / `arena/**` and on `v*` tags:

1. installs JDK 17, the Android SDK (platforms 23 + 34, build-tools 34.0.0) and Gradle 8.7,
   and derives the version from the tag (`v1.2.3` → `versionName 1.2.3`, `versionCode 10203`),
2. builds `assembleDebug`, `assembleRelease` and `bundleRelease`,
3. **if Gradle cannot reach Google Maven**, automatically falls back to `tools/offline_build.py`
   (it downloads the Kotlin compiler and drives aapt2 → kotlinc → d8 → zipalign → apksigner),
4. verifies the release signature with `apksigner` (the signer and signing schemes are reported
   as a workflow notice) and writes `SHA256SUMS.txt`,
5. uploads the three artifacts and **publishes them on a GitHub release** (`v1.0.0` for branch
   pushes, or the pushed tag). The tag is (re)pointed at the built commit, never at `main`, so a
download always matches a source tree..
   Manual runs: *Actions → Build & Release → Run workflow*.

Signing uses `keystore/keystore.properties` (or the committed demo key) in CI. Set the
`MC_KEYSTORE_PASSWORD` / `MC_KEY_PASSWORD` repository secrets — and provide your own keystore —
before you ship to a store.

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
adb install -r MorseCode-1.0.1-release.apk      # from the releases page
# or, after a local build:
adb install -r dist/MorseCode-1.0.1-release.apk
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
CHANGELOG.md                what each release contains
morseliink/                 the design mocks (a-series dark, b-series light)
```

## Documentation

* [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) — the 23 acceptance tests and how to verify artifacts.
* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — engine, wire protocol, WebShare routes, design system.
* [`CHANGELOG.md`](CHANGELOG.md) — what each release contains.

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
