# TransferingFileByAir: Video Study and Build Plan

> Goal: an Android-first file transfer app where you **lift a file and slide it off the edge of your
> phone onto the device next to it**. The card should look like it physically crosses the gap between
> the two screens. The codebase must be ready to move to **KMP + Compose Multiplatform** later
> (desktop receiver, iOS).

Reference frames extracted from `transfer sample.mp4` are in [`docs/reference/`](reference/).

---

## 1. What the video shows (beat by beat)

The demo is iPhone → MacBook. Timestamps refer to `transfer sample.mp4`.

### A. Pairing ritual (0.0s – 2.6s) - `01-pairing.jpg`, `06-detail-pairing-grid-lift.jpg`
| Time | What happens |
|---|---|
| 0.0–1.2s | Black screen with an **animated, flowing multicolor glow around the phone's rounded screen edge** (pink → magenta → orange → blue, slowly rotating/breathing, like the iOS Siri edge glow). Text: *"Hold iPhone next to* **Tornike's MacBook Pro** *(pink→orange gradient text). Then press and hold Connect."* A white circular button with a broadcast `((•))` glyph sits in the lower-middle. |
| ~1.2s | User **presses and holds** the button. The glow intensifies. |
| 1.5s | Hold completes: a **white light bloom bursts out of the button** and floods the phone screen. |
| 1.8s | The bloom **reaches the laptop too**: a white wash with an **iridescent rainbow streak** across the laptop screen. Both devices "light up together", which tells the user they are now linked. |
| 2.1–2.4s | Bloom fades; the pairing UI **cross-fades into the file shelf**. |

### B. The shelf (file grid) - `06-detail-pairing-grid-lift.jpg`
- Dark background, **2-column staggered (masonry) grid** of rounded cards.
- Image files show a photo thumbnail. Documents show a white page preview with a type badge (`MD`, `PDF`).
- Under each card: **file name** (middle-ellipsized, e.g. `Meetin…otes.md`), `size · time` (`367 KB · 13:25`) and a `•••` menu.
- Floating bottom bar: target-device icon (laptop), `+` (add files), item count chip **"15 items"**, `•••`.
- This is a **staging shelf**: you add files with `+`, then fling them away. When a file is sent it **leaves the shelf and the grid reflows** (see 25.0s: *River Valley.jpg* is gone and the remaining cards close the gap).

### C. Lift → drag → cross the edge (3.8s – 7.2s, and close-up 23.4s – 25.4s) - `02`, `03`, `04`, `07`
| Phase | Sender (phone) | Receiver (laptop) |
|---|---|---|
| **Lift** (long-press ~0.2s) | Card **scales up** (~1.1×) with a soft shadow. **Every other card blurs and dims.** | nothing yet |
| **Drag toward the edge facing the other device** | Card follows the finger and **tilts slightly with velocity**. | As the card approaches, the **receiver screen blurs** and a **white light bloom grows from the edge that faces the phone**. The rim of the bloom has a faint **rainbow / chromatic fringe**. |
| **Crossing** (the key moment, 24.6s / 6.2s) | Card is **clipped at the phone's screen edge**. Only the part still "on" the phone is drawn. | The **missing part of the card appears at the laptop's facing edge**, aligned with it. **One physical object spanning two screens.** |
| **Hand-off** (release / fling) | Card is gone; bloom on phone fades; grid un-blurs and **reflows** to close the slot. | Card fully enters, **springs toward the middle with a 3D perspective tilt** (rotationY), and sits inside the brightest part of the bloom for ~0.2s. |
| **Land** (25.2s, 27.2s) | Shelf is back to normal. | Card **shrinks and flies into the received stack** (top right on macOS). The new item **pushes the existing ones down** (*River Valley* lands above *Strawberries*). Bloom and blur fade. |

The same thing works for a photo (*River Valley.jpg*) and a document (*Q4 Report.pdf*), with the same choreography.

### D. Measured timings (approximate, from 60 fps frames)
| Beat | Duration |
|---|---|
| Hold-to-connect | ~1.0–1.2s |
| Pairing bloom burst → fade | ~0.6s up, ~0.5s down |
| Long-press lift | ~0.2s |
| Drag across the phone to the edge | ~0.4–0.8s (user-driven) |
| Receiver bloom ramp | starts ~0.4s before the card reaches the edge, proportional to distance |
| Card enters → centre | ~0.3s (spring, slight overshoot) |
| Hold in bloom | ~0.2s |
| Shrink + fly to stack | ~0.35s |
| Bloom/blur fade-out | ~0.5s |

---

## 2. Why it feels magical (the four ingredients)

1. **Physical continuity.** The card stays the same *physical* size (millimetres, not pixels) on both
   screens and is lined up on the shared edge, so the eye reads it as one object moving through the air
   gap. This is the core of the effect. Everything else adds to it.
2. **A light source that connects the two devices.** The bloom at the facing edges acts like a portal. It
   grows *before* the card arrives, so the receiver visibly anticipates the transfer.
3. **Depth and focus.** Lifting the card (scale and shadow) while everything else blurs makes it the only
   sharp thing on either screen.
4. **A clear ending.** The card lands somewhere obvious and the list shuffles to make room, and the
   sender's slot closes. This tells the user the transfer actually happened.

---

## 3. Translating it to our app

The video is phone → laptop. Our v1 target is **Android ↔ Android**, with both sides running our app.
A **Compose Desktop receiver** in the KMP phase reproduces the video's phone → laptop setup exactly.

- **Sender**: the shelf (section 1B) with lift and drag.
- **Receiver**: its own shelf in "listening" state. When a card comes in, the receiver blurs its shelf,
  shows the bloom at the facing edge, renders the incoming card continuing from the sender, then drops it
  into its own shelf at the top (grid pushes down). This matches the macOS desktop stack.
- **Semantics**: the shelf is a *staging area*. Sending **moves the item off the sender's shelf**. The
  original file in the user's storage is never deleted. The receiver saves the file to
  `Downloads/TransferingFileByAir/` and adds it to its shelf.
- **Decision (Phase 2):** a card slid off the edge leaves the shelf at once, but it is only removed from storage
  once the "“Q4 Report.pdf” left the shelf · Undo" snackbar goes away without Undo (however long accessibility
  settings keep it up). Until a peer actually receives files (Phase 5/6), a slip never loses a staged file. Later,
  the item comes back if the transfer fails.
- **Decisions (Phase 3):**
  - A card that leaves through the edge facing a linked peer is handed over, and the send is final (no Undo); one
    that leaves through any other edge behaves as in Phase 2.
  - The sender captures the lifted card as drawn (a ≤320 px WebP in `DragStart`), so the receiver shows the same
    card for any file type.
  - The receiver pings the sender when a card is lifted; the round trip is known before the card reaches the edge.
  - The Twin Simulator is one debug-only module (`:feature:simulator`, its own launcher entry). Its twins run the
    real shelf screen and ViewModel on in-memory shelves; files picked in it are also staged on the real shelf.
- **Decisions (Phase 4):**
  - Pairing opens from the shelf's device button (the shelf stays the start screen); after pairing it returns there.
  - Both phones advertise and discover at once. The user who holds Connect asks; then **both** phones show
    "Pair with <name>" and the same 4-digit code, and each pairs only when its own user holds Connect to confirm
    (or declines / cancels), like Bluetooth pairing. Nothing pairs without a person checking the code on each phone,
    so a device that copies a name gets nowhere, on either side. If both people press Connect at once, it is one
    attempt. While pairing with one device, requests from any other are turned down. The bloom plays on each phone as
    its link comes up.
  - Permissions: besides the Bluetooth/Wi-Fi ones, Nearby's discovery demands **precise location** on every Android
    version (verified on Android 13: it fails with `MISSING_PERMISSION_ACCESS_FINE_LOCATION` otherwise), so the app
    asks for it and says why.
  - The Nearby link lives in `:core:transfer:nearby` (Android only, Play services) behind `PeerLink`; the app shell
    binds it. Shelf slides don't use it yet (Phase 5).

---

## 4. Technical study: what we need

### 4.1 Discovery and connection (transport)
| Option | Pros | Cons |
|---|---|---|
| **Google Nearby Connections** (`play-services-nearby`) | Works offline with no shared Wi-Fi; BT/BLE discovery plus automatic upgrade to Wi-Fi Direct/hotspot for bandwidth; built-in FILE payloads with progress; little code | Android-only (Google Play services; no Huawei/AOSP); not usable from KMP common code |
| Own LAN stack (mDNS/NSD + TCP via Ktor sockets) | Pure Kotlin, so it moves to KMP common and works with desktop/iOS | Both devices must be on the same network; we write framing, resume and progress ourselves |
| Raw Wi-Fi Direct | No router needed | Bad UX (system dialogs), fragile across OEMs |

**Recommendation:** define a `Transport` interface in shared code.
- **v1:** a `NearbyTransport` implementation on Android using strategy `P2P_POINT_TO_POINT`, which gives
  the highest bandwidth for one-to-one transfers.
- **KMP phase:** add a `LanTransport` implementation (Ktor sockets + mDNS) so the desktop/iOS receiver works.
- **Dev/test:** add a `LoopbackTransport` (in-memory). See 4.7.

Nearby details that affect the design:
- `BYTES` payloads are capped at **32 KB**, so drag-state messages fit easily. The card **thumbnail must
  be compressed** (e.g. ≤256 px WebP, about 10–20 KB) or sent as a `STREAM`.
- The real file goes as a `FILE` payload built from a `ParcelFileDescriptor` of the content URI.
  Progress comes from `PayloadTransferUpdate`.
- Permissions depend on API level:
  - API ≤30: `ACCESS_FINE_LOCATION` and `BLUETOOTH`/`BLUETOOTH_ADMIN`
  - API 31+: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
  - API 33+: `NEARBY_WIFI_DEVICES`
  - Also: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`

### 4.2 "Hold next to" and pairing
- Both devices **advertise and discover** while on the pairing screen. The peer's name appears in the
  gradient headline ("Hold your phone next to **Khalid's Pixel 9**").
- Proximity gate (nice-to-have): scan BLE RSSI and enable the Connect button only when the peer is
  close (strong RSSI). Without it, discovery alone is fine for v1.
- **Press-and-hold Connect** (~1s, with a progress ring and haptic ramp). When the hold completes,
  request the connection and auto-accept on the peer, because the peer is on the same screen and opted
  in. The bloom burst plays **on both devices at the same time**, triggered by a `Paired` message.
- Remember the paired peer so the next session reconnects automatically.

### 4.3 Spatial geometry (making the card line up)
This is what turns "send a file" into "slide a file".

- Each device sends its `DisplayMetrics.xdpi/ydpi` and screen size in the `Hello` message, so **both
  sides work in millimetres**.
- **Exit edge** is whichever edge the user drags the card off (left/right/top). The receiver assumes the
  devices are side by side and makes the **opposite edge** the entry edge.
- **Along-edge position:** the sender sends the card's position in mm from the top of its screen. The
  receiver uses `senderMm + verticalOffsetMm`. The offset defaults to 0 (screen tops aligned).
- **Penetration:** if the card's leading edge is `p` mm past the sender's edge, the receiver draws it
  `p − gapMm` mm into its own screen. `gapMm` covers both bezels plus the air gap, default about 8 mm and
  tunable. The receiver card is drawn at the **same mm size**.
- **v2 calibration ("swipe across both screens")**: in pairing, the user swipes one finger from device A
  onto device B. The exit point, entry point and timing give the exact side, vertical offset and gap.

### 4.4 Real-time drag sync (protocol and latency)
Messages are serialized with `kotlinx.serialization` (CBOR or ProtoBuf, which stay small):

```
Hello(deviceName, model, widthPx, heightPx, xdpi, ydpi, appVersion)
Paired / Unpaired
DragStart(itemId, name, mime, sizeBytes, cardWidthMm, cardHeightMm, thumbnail: ByteArray)  // sent on lift, before the card nears the edge
DragMove(seq, tMillis, exitEdge, alongEdgeMm, penetrationMm, vxMmPerS, vyMmPerS, tiltDeg)   // ~60 Hz, only while within ~40 mm of the edge
DragCancel(itemId)                       // card sprang back
DragCommit(itemId, vxMmPerS, vyMmPerS)   // released past threshold / flung, so receiver plays hand-off and landing
TransferProgress(itemId, bytes, total) / TransferDone(itemId) / TransferFailed(itemId, reason)
Ping(t) / Pong(t)                        // RTT estimate for prediction
```

- Send `DragStart` with the thumbnail **as soon as the card is lifted**. By the time it reaches the
  edge, the receiver already has the artwork, so there is no pop-in.
- The receiver **predicts ahead by about RTT/2 using the last velocity**, and smooths with a short spring.
  This hides the 15–40 ms link latency.
- Bloom intensity on both sides comes from the card's **distance to the exit edge**, so both sides glow
  in sync without extra messages.

### 4.5 Rendering the effects (Compose)
| Effect | Implementation | API notes |
|---|---|---|
| **Pairing edge glow** | `Canvas`: several stacked `drawRoundRect` strokes (matching the display corner radius) with a rotating `Brush.sweepGradient` (pink/orange/purple/blue), animated phase and "breathing" width. API 33+: an **AGSL `RuntimeShader`** with flowing noise for the organic look in the video. | Blur on API 31+ (`RenderEffect`). On older APIs, use layered alpha strokes. |
| **Gradient headline** | `TextStyle(brush = Brush.linearGradient(...))` | all APIs |
| **Hold-to-connect** | `pointerInput` long-press with `Animatable` progress ring and `Vibrator` ramp; release before done springs back | haptic compositions on API 30+ |
| **Bloom burst / portal** | Radial-gradient white core plus a thin **iridescent ring** (rainbow sweep gradient, low alpha, additive `BlendMode.Plus`) anchored at the button (pairing) or the facing edge (transfer). Intensity is an `Animatable`. | all APIs |
| **Background blur** | `Modifier.blur(radius)` on the shelf, animated | **API 31+ only**. Below that, use a dim scrim and slight scale-down. |
| **Shelf grid** | `LazyVerticalStaggeredGrid` + `Modifier.animateItem()` for reflow when an item leaves or arrives | all APIs |
| **Lift and drag** | Card is drawn in a **top-level overlay layer** (not inside the lazy grid), so it can leave the grid and be clipped at the screen edge. `graphicsLayer { scale, shadowElevation, rotationZ = f(velocity) }`. Source slot keeps a ghost placeholder. Fling detection with `VelocityTracker`. | all APIs |
| **Receiver entry** | Same overlay card, positioned from 4.3, with `graphicsLayer { rotationY, cameraDistance }` for the 3D tilt, then a spring to centre | all APIs |
| **Land in stack** | Shared-bounds style animation from centre to the first grid slot (scale and position spring), then `animateItem` pushes the rest down | all APIs |
| **Haptics** | Tick on lift, rising texture near the edge, confirm on commit, gentle tick on landing (receiver) | — |

We follow the **`compose-motion-design`** skill guidelines: springs over tweens, no work in composition
on every frame (read animated values inside `graphicsLayer`/`drawBehind`), stable keys, Baseline Profile.

### 4.6 File handling
- Add files: **SAF** `OpenMultipleDocuments` only, which also lists photos.
  - Persistable URI permissions are kept, so staged files survive restarts.
  - **Decision (Phase 1):** the Photo Picker (`PickMultipleVisualMedia`) is left out. Its grants are not
    reliably persistable across API levels and the backported picker, so a photo staged today could be
    unreadable tomorrow. Revisit if users find SAF too heavy for photos.
  - Shelf entries (URI, name, mime, size, image size, text preview, addedAt) are stored in **Room KMP**.
- Thumbnails: **Coil 3** (KMP-ready) with app-wide decoders:
  - images, video frames (`coil-video`), and the first PDF page (`PdfRenderer` decoder)
  - for MD/TXT, the first ~600 characters, read when the file is added
  - otherwise a type badge
- Receive: write to `MediaStore.Downloads` (API 29+) or the app folder (older), then insert into the shelf.
- Large files: run the transfer under a **foreground service** (`dataSync` type) so it survives leaving
  the app. Show progress as a ring on the receiver's landing card. Support cancellation.

### 4.7 Developing without two phones nearby: the Twin Simulator
A **debug-only screen showing two virtual devices side by side** on one screen, connected by
`LoopbackTransport` with configurable fake latency and bezel gap. We can build and tune **all** the
animation, geometry and protocol logic on one emulator. Real devices are then only needed to verify
the Nearby transport. Nearby does **not** work reliably on emulators, so final testing needs **two
physical Android phones**.

---

## 5. Architecture (KMP-ready from day one)

Clean Architecture with feature-layered modules and MVI presentation (State / sealed Action / Event via
`Channel`). The full conventions are in `CLAUDE.md`. Shared modules use **`kotlin-multiplatform` with
only the Android target** for now, through the `build-logic` convention plugins. Migration then means
adding `jvm()`/`iosArm64()` targets in one place, not rewriting code.

```
:app                              Thin Android shell: Application (starts Koin), Activity, permissions, foreground service
:shared                   (KMP)   App() root composable, root NavHost, list of Koin modules.
                                  Desktop/iOS entry points will just call App().
:core:domain              (KMP)   ShelfItem, Peer, DeviceGeometry… + Result / DataError
:core:presentation        (KMP)   ObserveAsEvents and other shared presentation helpers
:core:designsystem        (KMP)   Theme, tokens, EdgeGlow, Bloom, GradientText, LiftCard (Android-only bits
                                  like RenderEffect/AGSL behind small interfaces)
:core:transfer:domain     (KMP)   Transport interface, protocol messages
:core:transfer:data       (KMP)   Protocol codec (kotlinx.serialization), LoopbackTransport
:core:transfer:nearby     (Android) NearbyTransport (Play services)
:feature:pairing:{domain,data,presentation}
:feature:shelf:{domain,data,presentation}   Shelf grid, drag/lift, send/receive choreography
:feature:simulator        (debug) Twin Simulator
```

Libraries (KMP-friendly choices; exact versions checked when we add them):
- **Koin** for DI (not Hilt; Hilt is Android-only)
- **kotlinx.coroutines/serialization/datetime**
- **androidx ViewModel + Navigation Compose** (both have KMP artifacts)
- **Coil 3**, **Okio / kotlinx-io** for file IO in common code
- **play-services-nearby** (Android only)

Rule for future migration: **no `android.*` or `java.io.File` imports in shared modules.** Platform
capabilities (file access, haptics, blur support, display metrics) go behind interfaces with
Android implementations.

Each module is created in the phase that first needs it, never as an empty placeholder.

**Toolchain (Phase 0):**
- AGP 9.4.1, with `com.android.kotlin.multiplatform.library` for the shared modules
- **Kotlin 2.4.21** (the current libraries need 2.4 metadata)
- Compose Multiplatform 1.12.2 (Material3 1.9.0)
- JetBrains Lifecycle 2.11.0 / Navigation 2.9.2
- Koin 4.2.2, coroutines 1.11.0, serialization 1.12.0
- minSdk 26, targetSdk 37

The build needs JDK 17+. Use Android Studio's bundled JBR (the `java` on PATH here is JDK 8).

---

## 6. Roadmap

| Phase | Deliverable | Done when… |
|---|---|---|
| **0. Foundation** | Module split, version catalog entries, Koin, theme and design tokens (dark), Navigation | App builds and launches into an empty shelf |
| **1. Shelf** | Staggered grid, add files (SAF; see 4.6), thumbnails, bottom bar, item menu (remove/open) | Shelf looks like the video's grid and persists across restarts |
| **2. Single-device choreography** | Lift, blur others, drag, edge clipping, bloom at the edge, spring-back, commit animation, grid reflow, haptics | Dragging a card off the edge on one phone looks and feels right |
| **3. Twin Simulator + protocol** | `LoopbackTransport`, protocol and codec, receiver rendering, geometry mapping, prediction | In the simulator a card crosses from the left device to the right one with no visible seam, and lands in the receiver's shelf |
| **4. Pairing** | Edge-glow screen, gradient headline, hold-to-connect, dual bloom burst, `NearbyTransport` discovery and connection, permissions flow | Two real phones pair with the video's ritual |
| **5. Real cross-device slide** | `DragStart/Move/Commit` over Nearby, latency compensation, tuning gap/offset defaults | The card visibly crosses between two physical phones |
| **6. Real file transfer** | FILE payloads, progress ring, save to Downloads, foreground service, cancel and retry, errors | 1 GB video transfers reliably; app can go to background |
| **7. Polish and quality** | AGSL glow (33+), calibration swipe, reduced-motion mode, TalkBack "Send to …" action, Baseline Profile, unit tests (geometry, codec, ViewModels), UI tests | 60 fps on a mid-range phone; accessibility checks pass |
| **8. KMP/CMP migration** | Add `jvm()` target, `LanTransport`, **Compose Desktop receiver** (the video's phone → laptop setup), then iOS | A phone slides a file onto a laptop |

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| Link latency makes the crossing look laggy | Send the thumbnail early, predict with velocity, start drag streaming only near the edge, and use springs to hide jitter |
| Bezel/gap differences between phone models break alignment | mm-based geometry, a sensible default gap, and the calibration swipe (Phase 7) |
| Blur missing below API 31 | Scrim + scale fallback (or raise minSdk; see decisions) |
| Nearby needs Play services | `Transport` interface; LAN transport in Phase 8 |
| OEM background limits kill transfers | Foreground service with `dataSync` type |
| Overdraw/GPU cost of blur + bloom + glow | Draw the effects in a single `drawBehind` layer, cap blur radius, profile with Macrobenchmark |

---

## 8. Decisions (confirmed 2026-10-21)

1. **v1 target:** Android ↔ Android. Desktop receiver comes in Phase 8.
2. **Transport:** Nearby Connections for v1, behind the `Transport` interface.
3. **minSdk 26.** Real blur on API 31+, scrim fallback below that.
4. **Send semantics:** the item moves off the sender's shelf. The original file is untouched.

### Does Nearby need both phones on the same Wi-Fi? No.
Nearby **finds** the other phone over Bluetooth/BLE and **connects** over Bluetooth first. It then
**upgrades** the link by itself to Wi-Fi Direct, a temporary hotspot that one phone creates, or the
shared Wi-Fi network if both phones are already on one.

No router or internet is needed. The user needs Bluetooth on and **Wi-Fi turned on** (but not connected
to anything), plus Location on for API ≤ 30. On some devices the hotspot upgrade briefly disconnects the
phone from its current Wi-Fi during the transfer.

"Same network" only matters for the **Phase 8 laptop receiver**. Laptops can't use Nearby, so phone →
laptop uses the LAN transport.
