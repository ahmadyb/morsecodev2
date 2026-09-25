# MorseCode — acceptance tests

23 checks that decide whether a build ships. Each one is written so a tester can run it on two
physical phones without a debugger. Ports used: **UDP 33457** (discovery), **TCP 33456**
(control + data), **HTTP 33455** (WebShare).

| # | Test | Steps | Expected |
|---|------|-------|----------|
| 1 | Cold start | Install, launch with no permissions granted | Connect tab renders: avatar, radar sweeping, Send / Receive, WebShare card, "No phones discovered yet". No crash, no ANR. **Automated:** `tools/emulator_smoke.sh` runs this on an API 34 emulator for every build and blocks the release if the process dies. |
| 2 | Onboarding | First launch only | 4 slides: offline transfer, permissions, WebShare, all set. Swipe or Continue; Skip jumps straight in; "Replay onboarding" in Settings brings it back. |
| 3 | Discovery | Two phones on the same Wi-Fi, both on Connect | Each phone shows the other within ~2 s; the radar draws a dot per peer; "N devices nearby" matches the list. |
| 4 | Connection request | Phone A taps Connect on phone B | B shows the modal **"Connection request"** — "Ravi's Redmi wants to send you files." with Accept / Reject and a Phone · Wi-Fi LAN · IP subtitle. |
| 5 | One file, lan | A picks one file, taps Send | B shows Receiving with a byte-accurate bar; A's row shows live MB/s; both finish with "CRC verified" and DONE. |
| 6 | Many files | A picks 12 files | One batch id; rows stream QUEUED → SENDING → DONE; **one** coalesced summary dialog appears (not twelve) after the batch settles for ~2.5 s. |
| 7 | Pause isolation (INV-2) | A: 3 files in flight, pause the middle one | Only that row turns PAUSED and stops moving; the other two keep progressing at full speed. Resume continues from the paused byte, not the start. |
| 8 | Cancel isolation | Cancel one of three in-flight files | Only that row ends CANCELLED; the peers' other transfers are untouched. |
| 9 | Connection loss (INV-3) | Turn B's Wi-Fi off mid-transfer | A's row flips to PAUSED with "Connection lost". Turn Wi-Fi back on, resume: the transfer continues from `resumeOffset` (no re-send of completed bytes). |
| 10 | Never hang (INV-1) | Kill the app on B mid-send | A terminates that item within 3 s (FAILED/PAUSED with a reason), never spinning forever. |
| 11 | Duplicate policy | Send the same file twice with policy = Rename | Second copy lands as `name (1).ext`; with Skip it reports SKIPPED — "Identical file already on the receiving phone"; with Overwrite it replaces. |
| 12 | Atomic writes | Watch the receiving folder during a transfer | Only `.part` files exist mid-flight; the final name appears only after the last chunk, never a truncated file under the real name. |
| 13 | Broadcast | A selects 3 peers, sends one batch | Radar switches to topology (≤4 cards, dashed links). Per-peer strips show counts. One **"Broadcasting to N phones"** card with combined MB/s. |
| 14 | Broadcast summary | Let it finish | One group summary: "Sent to 3/3 devices" with PEERS / TOTAL MB / MB SENT cells; History shows **one** row "Broadcast · 3 phones". |
| 15 | Broadcast cap | Try to select a 5th peer | "Maximum 4 devices per group." and the selection is refused. |
| 16 | Nearby transfer | No shared Wi-Fi. Both phones: Connect → **Nearby** → Send on A, Receive on B | B's Receive screen asks the system to make it discoverable (accept the dialog) and stays open. A finds B within ~10 s and a file transfers end to end. **Prerequisites, in order — if A's list stays empty, one of these is missing:** (a) both phones on the same transport (the pill is filled on both); (b) B is on its **Receive** screen, not the home screen — a phone that is merely listening is not discoverable, which is what classic Bluetooth inquiry reads; (c) B's Bluetooth is on and Nearby's permissions are granted — Connect shows a card naming whichever is missing, with a button that fixes it; (d) if either phone has no Bluetooth adapter at all, the card says so and Wi-Fi LAN is the only option. Connection Doctor reports all four, including whether the phone is discoverable *right now*. |
| 17 | WebShare start | A: toggle WebShare ON | Card shows the address `http://<ip>:33455`; a "WebShare is running" notification appears; the URL opens from a laptop on the same Wi-Fi. |
| 18 | Browser consent | Laptop opens the URL | Phone shows **"Browser wants access"** — "A browser session wants to browse your phone." Accept → SPA loads; Reject → "Session rejected on the phone."; no answer within 30 s → the waiting page. |
| 19 | WebShare SPA | Browse from the laptop | Sidebar counts live; day headers appear once; folders open with a sticky, clickable breadcrumb; music starts a persistent player that keeps playing across navigation (INV-5); one Upload UI at a time (INV-6). |
| 20 | No idle teardown (INV-4) | Start WebShare, leave it 30 minutes with no requests | Still running, still reachable. Nothing stops it except the user toggling it off. |
| 21 | Media paging (INV-10, INV-9) | Open Files with > 2 000 photos | Grid pages in without a `LIMIT/OFFSET` in the sort string; newest day first, day groups correct, no frozen scroll. |
| 22 | Theme + accents | Settings → pick each of the 5 accents, toggle dark/light, apply a theme overlay | Every surface recolours immediately; dark = a-series mocks, light = b-series mocks; no clipped or invisible text. |
| 23 | Diagnostics + release hygiene | Settings → Connection Doctor / Log viewer; then check the shipped artifacts | Doctor lists Wi-Fi / peers / multicast / Bluetooth / permissions / battery with colour lights; the log tails live, exports to .txt and filters errors. The release page carries a **signed** debug APK, release APK and `.aab`, all `minSdk 21`, `targetSdk 34`, `applicationId com.morsecode.app`. |
| 24 | Launch gate (automated) | Any push or tag | `Build & Release` builds, installs the release APK on an Android 14 emulator, opens it, drives the whole app and fails the run on a crash, an ANR or a dead process. What it asserts is listed under the table. A red gate means no release is published. |

### What the launch gate checks (test 24)

Every item below is an assertion in `tools/emulator_smoke.sh`; the run publishes only the
screenshots it captured this time, into `docs/screenshots/`.

* **It opens and survives.** No crash, no ANR, no dead process, on any of the four tabs, and the
  Files tab never claims it needs storage permission (grants were made at install time).
* **It is reading the library.** Three seeded photos must come back out: the grid reports
  `N of M` in the app log and the gate fails if `M > 0` while `N = 0`, or if the screen says
  "No photos yet" with media on the device.
* **Selection.** `Select all` in a day shows a selection count and a visible `Send`; the same
  control flips to `Clear all` and clears the day in one tap; the screenshots are taken with a
  selection standing, since that is the state they document.
* **Day headers** do not print the day twice.
* **The Files hub** shows its categories with counts (Documents, Ebooks, Archives, APKs, Large
  files) and its folders (Download, Internal storage), and Internal storage opens as a browser.
* **The transport choice** is on the Connect screen. Choosing Nearby must reach the Bluetooth
  transport (the app log records which transport started and whether it is running); if it could
  not start, the screen must say why. The gate does not require a warning to appear, because that
  depends on the radio state of the machine it runs on.
* **WebShare**, end to end over `adb forward`: the waiting page before consent, the exact
  "Browser wants access" copy, the file browser after consent with `/api/counts`, no QR
  anywhere, then a clean stop.
* **The theme switch** in Settings, asked of the browser itself over `/api/hello`.

## How to verify the artifacts

The artifacts are built by GitHub Actions and published on
[the releases page](https://github.com/ahmadyb/morsecodev2/releases/latest) - the four jobs that
decide a release are `Build artifacts` → `Install and drive the app on an emulator` →
`Publish GitHub release`.

```bash
# local build (needs the toolchain on PATH; see README)
python3 tools/offline_build.py               # produces dist/*.apk and dist/*.aab

# the same launch check the release gate runs, against an already-running emulator
tools/emulator_smoke.sh 1.0.1

# icon assets are generated from morseliink/logo.PNG and verified against it
python3 tools/make_icons.py --check

# signature + manifest checks (build-tools 26+)
java -cp $ANDROID_HOME/build-tools/33.0.0/lib/apksigner.jar com.android.apksigner.ApkSignerTool \
     verify --print-certs dist/MorseCode-1.0.1-release.apk
aapt dump badging dist/MorseCode-1.0.0-release.apk | head -3   # sdkVersion 21, targetSdkVersion 34
```

## Known deviations (documented, not bugs)

* **Nearby Connections** is implemented over RFCOMM/Bluetooth sockets rather than the Google
  Play Services library (no external artifacts in this toolchain). Invariant behaviour — pairing,
  consent, checksums, resume — is identical.
* **QR decoding** is not linked; the scan screen shows the live camera preview plus the Manual IP
  path, which performs the same pairing handshake. WebShare intentionally shows **no QR code**.
* **Hotspot start** uses the hidden `setWifiApEnabled` API where the OEM allows it, otherwise the
  UI explains how to enable the hotspot manually (Android 10+ restricts programmatic toggling).
* `StateFlow` is replaced by `core/util/Observable.kt` (`State`/`Event`) with the same contract —
  replay for state, no replay for events, delivery on the main thread.
