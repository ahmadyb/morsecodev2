# MorseCode — architecture

```
                    ┌──────────────────────────── MainActivity ───────────────────────────┐
                    │  bottom nav: Connect · Files · History · Settings                    │
                    │  pushed screens: Transfer/Broadcast · Viewer · Player · Log · Help  │
                    └───────────────┬──────────────────────────────────────────────────────┘
                                    │ observes State/Event
                       ┌────────────▼────────────┐        ┌──────────────────────────┐
                       │      TransferEngine     │◄──────►│    TransportSession      │
                       │  items · peers · phase  │        │  LanSession / BtLink     │
                       │  events (EngineEvent)   │        └──────────────────────────┘
                       └───┬───────┬────────┬────┘
                           │       │        │
              ┌────────────▼─┐ ┌───▼──────┐ ┌▼──────────────┐
              │ JournalStore │ │HistoryS. │ │ WebShareServer │──► browser SPA
              │  (resume)    │ │(records) │ │  + controller  │    (PAGE assets)
              └──────────────┘ └──────────┘ └───────────────┘
```

## Layers

| Package | Responsibility |
|---------|----------------|
| `core/network` | Wire framing (`MLNK`), LAN transport (UDP discovery + TCP sessions), Nearby transport (RFCOMM), the session/transport interfaces. |
| `core/transfer` | `TransferEngine` (single source of truth), per-peer `Master` worker, foreground `TransferService`, `SoundFx`, `Log`. |
| `core/data` | `Prefs` (single SharedPreferences), `HistoryStore` (JSONL), `JournalStore` (resume checkpoints). |
| `core/storage` | `Destinations` (path resolution, `.part` → atomic rename, MediaStore indexing), `SafStore`, `ShareProvider`. |
| `core/webshare` | Plain-socket HTTP server, the SPA assets, the consent/Wi-Fi-lock controller, the hotspot helper. |
| `core/ui` | The widget kit (`W`), dialogs (`Ui`), `RadarView`, the `Screen` contract. |
| `compat/` + `core/util` | API-34-only helpers compiled against `android-34.jar` in a separate Kotlin pass; the rest of the app compiles against `android-23.jar` so an API-23 device can never crash on a missing symbol. |
| `feature/*` | One `Screen` per tab plus the pushed screens. No business logic. |

## Engine

`TransferEngine` owns:

* `items: State<List<TransferItem>>` — every queued, running or finished transfer.
* `peers: State<List<DiscoveredPeer>>` — everyone seen in the last 30 s.
* `phase: State<SessionPhase>` — `IDLE → SEARCHING_SEND/SEARCHING_RECEIVE → CONNECTED → BATCH_DONE`.
* `events: Event<EngineEvent>` — `PeerConnected`, `PeerDisconnected`, `ConsentRequested`,
  `BatchCompleted`, `GroupCompleted`, `GroupSummary`, `ItemFinished`, `Message`.

One `Master` worker per peer serialises that peer's queue. Broadcasts additionally pass through a
`Semaphore(DeviceTier.maxParallelPeers)` so an old phone never opens more parallel streams than it
can pump (~2 on low tier, 4 on high tier).

* **Consent**: a session asks `Di.engine.consentHandler` and waits on a latch. A late answer is
  latched for 30 s so a re-connect does not re-prompt.
* **Session loss**: `onPeerDisconnected` marks the in-flight items of that peer PAUSED with
  "Connection lost" and lets the remaining peers continue (INV-2), then auto re-queues from
  `resumeOffset` when the peer reconnects (INV-3).
* **Terminal fallback**: every item has a 3 s terminal guard (INV-1) so no row can spin forever.
* **Summaries**: a per-(peer,batch) latch debounced by 2.5 s collapses a batch into exactly one
  dialog; Broadcasts collapse further into one group summary with per-peer outcomes.

## Wire protocol (`core/network/Wire.kt`)

```
MAGIC "MLNK" │ type │ JSON header line \n │ [payload]
```

Types: `HELLO, ACCEPT, REJECT, META, ACK, DATA, PAUSE_REQ, CANCEL, BYE, PROGRESS, DONE, DONE_OK, DONE_FAIL`.

* Chunk size 256 KB, CRC32 per chunk, max frame 1 MB, read timeout 25 s, idle watchdog 45 s.
* The JSON header line is read **byte by byte from the raw `InputStream`** — a regression guard
  against `BufferedReader` over-reading past the frame boundary.
* Data frames carry `offset`, so a resumed transfer continues mid-file without re-hashing.

## WebShare (`:33455`)

```
GET  /                 SPA (dark default, light mirror)
GET  /api/hello        device name, accent, counts
GET  /api/consent      consent state poll (waiting page)
POST /api/consent      accept/reject from the browser
GET  /api/counts       live sidebar counters
GET  /api/files        paged listing
GET  /api/fs           one folder  (breadcrumb, INV-7)
GET  /thumbnail        streamed, tier-sized
GET  /download         single file (attachment)
GET  /download-file    duplicate name materialised
GET  /download-folder  folder as a streamed zip
GET  /download-zip     multi-select zip
POST /upload           streaming multipart → receive folder (single UI, INV-6)
```

The browser may not touch anything until the phone accepts "Browser wants access", which is the
same consent popup the LAN sessions use. The URL is tokenless by design: the gate is the popup,
not a secret. The SPA keeps a persistent player across navigation (INV-5), shows day headers once,
and is responsive from 360 dp to desktop (INV-8). The server never shuts down on idle (INV-4).

## Design system — Sunflower Hue

* Surfaces: `BgDark #0B0B0B`, `CardDark #1A1A1A`, `BgLight #EEEDE7`, `CardLight #FFFFFF`.
* Five accents (`YELLOW` default, `GREEN`, `ORANGE`, `PURPLE`, `BLUE`) × dark/light = 10 theme
  overlays (`McTheme.Dark.*` / `McTheme.Light.*` in `res/values/styles.xml`).
* Status colours never move with the accent: green complete, red failure, orange paused.
* Peer avatar colours are derived from the device id (`avatarAccentFor`), so a peer keeps the same
  colour on every phone and in every session.
* Dark mode is the product default and matches the **a-series** mocks; light matches the
  **b-series** mocks. Icons are 24 dp vectors tinted at runtime.

## UI construction rules

The UI is built in Kotlin, not XML: the whole resource tree is 90 files of colours, strings and
vector drawables, and `main/res/layout/` does not exist. That is deliberate —

* **No AndroidX, no Material, no Compose.** The APK carries exactly one dex with the app's own 442
  classes next to the platform APIs it uses, which is why a complete app is ~450 KB instead of the
  ~4 MB an AndroidX + Material build weighs. Nothing here is throwaway: every screen, sheet, radar
  and browser SPA is hand-built against the platform.
* **One Activity** (`MainActivity`) hosts the four tabs; `TransferFragment` is pushed over a tab so
  a session survives minimising back to the nav bar.

Two rules keep that model safe, and both exist because they broke in v1.0.0:

1. **A view has exactly one parent.** Screens rebuild by clearing a container and re-adding their
   long-lived children (the radar, the action bar). Any view stored in a field must be passed
   through `View.detach()` before it is re-added, or `addView` throws
   `IllegalStateException: The specified child already has a parent`.
2. **Every screen has a terminal state.** A rebuild that cannot render shows an empty state; it
   never leaves the previous frame on screen with no way forward (INV-1).

The emulator smoke test (`tools/emulator_smoke.sh`, run by CI before any release) is what enforces
this in practice: it opens the release APK and walks all four tabs.

## Threading

* UI: main looper only. `State`/`Event` marshal worker updates onto the main thread and coalesce
  `State` bursts to one rebuild per frame.
* Workers: one cached pool (`Workers`) for socket setup, one `Master` thread per peer, one beacon
  thread, one HTTP accept thread, one ticker inside `TransferService`.
* Disk: hashing and thumbnails run on pooled threads; the UI never blocks on I/O.
