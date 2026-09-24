# MorseCode — acceptance tests

23 checks that decide whether a build ships. Each one is written so a tester can run it on two
physical phones without a debugger. Ports used: **UDP 33457** (discovery), **TCP 33456**
(control + data), **HTTP 33455** (WebShare).

| # | Test | Steps | Expected |
|---|------|-------|----------|
| 1 | Cold start | Install, launch with no permissions granted | Connect tab renders: avatar, radar sweeping, Send / Receive, WebShare card, "No phones discovered yet". No crash, no ANR. |
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
| 16 | Nearby fallback | No shared Wi-Fi: switch transport to Nearby on both | Peers appear over Bluetooth (RFCOMM) and a file transfers end to end. |
| 17 | WebShare start | A: toggle WebShare ON | Card shows the address `http://<ip>:33455`; a "WebShare is running" notification appears; the URL opens from a laptop on the same Wi-Fi. |
| 18 | Browser consent | Laptop opens the URL | Phone shows **"Browser wants access"** — "A browser session wants to browse your phone." Accept → SPA loads; Reject → "Session rejected on the phone."; no answer within 30 s → the waiting page. |
| 19 | WebShare SPA | Browse from the laptop | Sidebar counts live; day headers appear once; folders open with a sticky, clickable breadcrumb; music starts a persistent player that keeps playing across navigation (INV-5); one Upload UI at a time (INV-6). |
| 20 | No idle teardown (INV-4) | Start WebShare, leave it 30 minutes with no requests | Still running, still reachable. Nothing stops it except the user toggling it off. |
| 21 | Media paging (INV-10, INV-9) | Open Files with > 2 000 photos | Grid pages in without a `LIMIT/OFFSET` in the sort string; newest day first, day groups correct, no frozen scroll. |
| 22 | Theme + accents | Settings → pick each of the 5 accents, toggle dark/light, apply a theme overlay | Every surface recolours immediately; dark = a-series mocks, light = b-series mocks; no clipped or invisible text. |
| 23 | Diagnostics + release hygiene | Settings → Connection Doctor / Log viewer; then check the shipped artifacts | Doctor lists Wi-Fi / peers / multicast / Bluetooth / permissions / battery with colour lights; the log tails live, exports to .txt and filters errors. `dist/` contains a **signed** debug APK, release APK and `.aab`, all `minSdk 21`, `targetSdk 34`, `applicationId com.morsecode.app`. |

## How to verify the artifacts

```bash
python3 tools/offline_build.py               # produces dist/*.apk and dist/*.aab

# signature + manifest checks (build-tools 26+)
java -cp $ANDROID_HOME/build-tools/33.0.0/lib/apksigner.jar com.android.apksigner.ApkSignerTool \
     verify --print-certs dist/MorseCode-1.0.0-release.apk
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
