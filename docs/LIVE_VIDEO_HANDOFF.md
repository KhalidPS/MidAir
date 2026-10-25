# Live Video Handoff: Study and Build Plan

A video that is **playing** on one phone is lifted as a card, slid off the edge, and keeps playing on the phone
next to it. It never pauses or freezes, and no frame repeats or jumps. Branch: `feature/live-video-handoff` (off
`master`). This document is the whole plan; `docs/PLAN.md` on the working branch points here. **How it works
today, step by step, and how to debug it on two phones: `docs/LIVE_VIDEO_GUIDE.md`.**

- **Reference app:** drooop (https://drooop.app/, by InsaneArts), iPhone ↔ Mac. It isn't released yet (as of
  2026-10), so there is no code or technical documentation to look at.
- **Reference video:** `share_sample2.mp4` (57 s, 60 fps; kept out of git because it is 24 MB). The key frames are
  in `docs/reference/08-live-video-straddle.jpg`.

---

## 1. What the video shows

| Time | Beat |
|---|---|
| 0–7 s | A video plays in a window on the Mac |
| 7–8 s | The window shrinks into a small card, still playing |
| 8–10 s | The card is dragged toward the iPhone (iPhone's shelf blurs behind it) |
| **10.3–11.9 s** | **The card straddles the bezel: half on the Mac, half on the iPhone. Both halves show the same frame. At ~11.0 s the shot cuts (crowd → climber) on both halves at once, and the rope lines continue straight across the gap.** |
| 12.5 s | The iPhone shows a full player: scrubber at −0:45, then −0:44 at 14.5 s. No restart, no jump |
| 31–35 s, 40–55 s | The same thing in both directions, ending on "Think different." with no frozen frame |

What drooop's page says that matters here:
- "Drag a playing video to the other screen and keep watching from the same frame."
- "An iPhone and a Mac on the same Wi-Fi", "its own direct connection", "No upload, no server and no account."
- "Hold your iPhone near your Mac once. They find each other from then on."

## 2. How it works (inferred from the frames; we can't see their code)

1. **A playing video is a timestamp, not pixels.** The handoff message is tiny: which file, position, the shared
   clock time of that position, playing or paused, speed. The receiver predicts the position for "now" and starts
   there.
2. **The bytes are already there, or arrive much faster than they play.** A 1080p video is ~8 Mbps, while a
   local Wi-Fi link carries hundreds of Mbps. The receiver streams the file from the sender using range requests:
   it needs only the header (`moov`) and a few seconds of data to start, and fetches the rest as it plays. The drag
   takes ~3 s, so it starts loading **when the card is lifted**, not when it reaches the edge.
3. **Both devices share a clock.** A few timestamped pings give the offset between the two clocks (we already
   do this for the line-up: `peerClockAheadMillis` in `LineUp.kt`). Each device plays to the shared time. Being off
   by 1–2 frames (16–33 ms) can't be seen across a bezel.
4. **Each device draws its own slice** of the card, from its own decoder, at the same playback time. The receiver's
   slice is the part of the card that has crossed, which our slide geometry already computes.
5. **Make before break.** The sender keeps playing until the receiver says "first frame rendered". Only after the
   card is committed does the sender stop. Dragging back cancels the receiver and the sender never stopped.

---

## 3. Scope

**v1:** Android ↔ Android, both phones running our app, already paired and lined up (Phases 4–7 of
`docs/PLAN.md`). Video only (audio follows the video). Over our existing Nearby link, with a same-Wi-Fi fast path
later in this plan.

**Not in v1:** documents, apps or editing state ("keep editing there"); live streams; DRM-protected video;
handoff to desktop/iOS (Section 9 says how it extends).

---

## 4. Tech stack

| Need | Choice | Notes |
|---|---|---|
| Player | **AndroidX Media3 ExoPlayer** (`media3-exoplayer` 1.11.1) | The standard Android player. Version in `libs.versions.toml` only |
| Video surface in Compose | **Our own `VideoSurface`**: a `TextureView` in an `AndroidView` | A TextureView follows `graphicsLayer` transforms, clipping and rounded corners. A SurfaceView doesn't, so it can't be a sliding card. Not `media3-ui-compose`'s `PlayerSurface`: it can't hand the picture between two surfaces (see the next row) |
| One player across window → card → drag | Keep **one `ExoPlayer` instance**; swap surfaces only | Swapping the surface doesn't re-prepare, so the sender's own lift never pauses. The video goes to the active surface composed last; the new one shows the old one's current frame (`TextureView.getBitmap`) until its own first frame, so a swap never flashes. The view keeps the video's shape and is cropped by its parent, so that picture is never stretched |
| Streaming before the file has arrived | **Custom Media3 `DataSource`** over our link: `open(DataSpec)` sends a range request, `read()` reads the answer | A Nearby FILE payload can't be read while it is still arriving, so streaming needs its own channel |
| Range answers over Nearby | **Nearby STREAM payloads** (`Payload.fromStream`), one per open range | BYTES payloads stay for control messages. Seeking closes the old stream and opens a new range |
| Keeping the file after the handoff | **Media3 `SimpleCache` + `CacheDataSource`**, then **`CacheWriter`** to fetch what wasn't played | One pipeline: what was played is already saved; the rest is filled in, then moved to Downloads and the shelf like a normal received file |
| Shared clock | Existing `Ping`/`Pong` + `peerClockAheadMillis`, sampled again (8 pings) when a card is lifted | Use a **monotonic** clock for these timestamps (`TimeSource.Monotonic` / `SystemClock.elapsedRealtime`). Wall-clock time can jump when the phone syncs its time |
| Link-speed gate | **Nearby `onBandwidthChanged` → `BandwidthInfo.Quality`** | Live handoff only at `HIGH` (Wi-Fi after the upgrade). Below that, the normal file transfer is used instead |
| Codec check on the receiver | `MediaCodecList` / Media3 `MediaCodecUtil` | The sender sends the codec string (e.g. `hvc1…`). If the receiver can't decode it, it falls back to the file transfer |
| Same-Wi-Fi fast path (LH-5) | **`NsdManager` (mDNS) + Ktor server (CIO) with HTTP Range**, player reads via **`DefaultHttpDataSource`** | The drooop way. It's also the seed of Phase 8's `LanTransport`. Guarded by a per-pairing token |
| Protocol encoding | kotlinx.serialization, same codec as `SlideMessage` | |
| Tests | kotlin.test, AssertK, Turbine, `UnconfinedTestDispatcher`, hand-written fakes | As `CLAUDE.md` says |

Nothing new is needed for the slide itself: `SlideGeometry`, `IncomingSlide`, `Crossing` and the line-up already
place the card on both screens.

---

## 5. Architecture

Same rules as the rest of the app: domain is pure Kotlin, platform APIs sit behind interfaces in common code with
`androidMain` implementations, and Koin binds them.

```
:core:transfer:domain    (KMP)  + PlaybackMessage types in SlideMessage, MediaChannel interface (range reads),
                                  LinkQuality on PeerLink
:core:transfer:data      (KMP)  + codec for the new messages, LoopbackMediaChannel (simulator / tests)
:core:transfer:nearby    (Android) + NearbyMediaChannel (STREAM payloads), bandwidth quality
:core:media              (KMP, new)  common: VideoEngine interface, VideoSurface composable (expect)
                                     androidMain: ExoVideoEngine, ChannelDataSource (Media3), cache setup
:feature:shelf:domain    + PlaybackHandoff: the handoff state machine, position prediction, drift control
:feature:shelf:presentation  + playing card (open a video in the shelf), live card on both sides of the slide
:feature:simulator       + two engines over LoopbackMediaChannel
```

`:core:media` is new because both the shelf and the simulator need the engine, and it is platform code that the
domain must not see. It gets created in LH-0, when its first code exists.

### 5.1 Key interfaces (sketch)

```kotlin
// :core:transfer:domain
interface MediaChannel {
    /** Opens [length] bytes of [fileId] from [offset] (length -1 = to the end) on the peer. */
    suspend fun open(fileId: String, offset: Long, length: Long): Result<ByteSource, DataError.Network>
}

// :core:media (common)
interface VideoEngine {
    val position: StateFlow<Long>         // ms, updated per rendered frame
    val events: Flow<VideoEngineEvent>    // Ready, FirstFrameRendered, Buffering, Ended, Failed(DataError)
    fun load(source: VideoSource, startAtMs: Long, playWhenReady: Boolean)
    fun play(); fun pause(); fun seekTo(ms: Long)
    fun setSpeed(speed: Float); fun setVolume(volume: Float)
    fun release()
}
sealed interface VideoSource { data class Local(val uri: String) : VideoSource
                               data class Peer(val fileId: String, val sizeBytes: Long) : VideoSource }
```

### 5.2 New protocol messages (in `SlideMessage`)

| Message | From → to | When / meaning |
|---|---|---|
| `PlaybackOffer(itemId, fileId, mimeType, codecs, sizeBytes, durationMs, positionMs, atPeerClockMs, playing, speed)` | sender → receiver | Sent with `DragStart` when the lifted card is a playing video. The receiver opens the stream and prepares, muted |
| `PlaybackClock(itemId, positionMs, atPeerClockMs)` | sender → receiver | Every 500 ms while the card is in flight, so a stall or rebuffer on the sender is followed |
| `PlaybackReady(itemId)` | receiver → sender | The receiver has rendered its first frame at the predicted position |
| `PlaybackUnsupported(itemId, reason)` | receiver → sender | Codec, bandwidth or storage won't allow it; the slide falls back to a normal file send |
| `PlaybackTookOver(itemId, positionMs)` | receiver → sender | After `DragCommit`: the receiver is now the one playing, so the sender fades out and releases |
| (existing) `DragCancel` | sender → receiver | Receiver releases its engine; the sender never stopped |

Range requests and their data travel on the `MediaChannel`, not as `SlideMessage`s.

### 5.3 The handoff state machine (`PlaybackHandoff`, pure Kotlin, in shelf domain)

```
Sender:   Playing ──lift──▶ Offering ──PlaybackReady──▶ Straddling ──commit──▶ HandingOver ──TookOver──▶ Released
                               │ Unsupported / timeout          │ DragCancel
                               ▼                                ▼
                         FileFallback                      Playing (never stopped)

Receiver: Idle ──Offer──▶ Preparing ──first frame──▶ Following ──DragCommit──▶ Leading (audible) ──▶ Playing
                              │ fail                     │ DragCancel
                              ▼                          ▼
                        Unsupported                   Released
```

**Following** (the receiver while the card straddles):
- target position = `positionMs + (nowOnSenderClock − atPeerClockMs) × speed`
- error = its own position − target
- |error| ≤ 20 ms: nothing to do
- 20–250 ms: play at speed 0.97–1.03 until it catches up (it can't be seen)
- above 250 ms: seek to target + the decoder's start delay (measured, ~100–300 ms), then hold until the clock gets
  there

**Audio** stays on the sender while the card straddles (the receiver is muted). On commit the two volumes
cross-fade over ~150 ms. If the sender is on Bluetooth headphones, the sound moves to the receiver's speaker, which
is expected.

**If the receiver isn't ready at commit** (slow link): the receiver shows the last frame it has with the shimmer.
It resumes **at the commit frame** when ready, so nothing is skipped and the pause is as short as possible.
(Decision 1 below; the alternative is to jump ahead to "live".)

---

## 6. Phases

Each phase is one or more commits on `feature/live-video-handoff` (or a sub-branch off it), with tests in the same
commit as the code.

| Phase | Deliverable | Done when… |
|---|---|---|
| **LH-0. Playing card** (built, to check on a phone) | Tapping a video on the shelf plays it in a card (`:core:media`, `ExoVideoEngine`, `VideoSurface`, TextureView). The card can be lifted and dragged, keeping one player | On one phone, a video keeps playing without a hitch while it is lifted, dragged and dropped back |
| **LH-1. Protocol + simulator** | New messages and codec, `MediaChannel` + `LoopbackMediaChannel` (with fake latency, bandwidth and clock offset), `PlaybackHandoff` state machine, Twin Simulator with two engines | In the simulator the two halves cut scenes on the same frame, commit never pauses, and drag-back cancels cleanly. State machine and predictor fully unit tested |
| **LH-2. Streaming over Nearby** | `NearbyMediaChannel` (STREAM payloads), `ChannelDataSource` + `SimpleCache`, `LinkQuality` from `onBandwidthChanged`, codec check, the `PlaybackUnsupported` fallback | On a `HIGH` link, the receiving phone shows its first frame within 300 ms of the offer, for a 1 GB video, including one with its header at the end |
| **LH-3. Two-phone handoff** | Live slice on both phones, drift control, audio cross-fade, make-before-break, the slow-link path at commit, link loss | Filmed at 240 fps slow motion: ≤ 2 frames offset across the bezel and no frozen frame in 20 runs each way |
| **LH-4. Keep the file** | `CacheWriter` fills what wasn't played, then the file is finalized into Downloads + the shelf (same repository call as a normal received file), foreground service, cancel | After a handoff, the full file is in the receiver's shelf and byte-identical to the original (hash check) |
| **LH-5. Same-Wi-Fi fast path** | `NsdManager` advertising, Ktor (CIO) range server on the sender, `DefaultHttpDataSource` on the receiver, token from pairing, preferred when both phones share a network | With both phones on the same Wi-Fi, the video's bytes travel over the LAN and Nearby only carries the control messages |

**Status (2026-10-25):** LH-0 is built. A first two-phone slice of LH-1–LH-3 is built and works on two phones (a
Redmi Note 10 Pro and a Redmi Note 8): the video keeps playing across the gap, through the drop and the file's arrival.
- `PlaybackOffer`/`PlaybackClock` (4×/s)
- `MediaChannel` on Nearby STREAM payloads, used only at link quality HIGH (Wi-Fi)
- `RemoteDataSource`
- `PlaybackFollower`: speed nudges, holds when ahead, and jumps ahead by a lead learnt from jumps only
- the incoming card plays muted, gets sound on the drop, and keeps playing in its slot and in the received item

What the phones showed about Nearby (none of it is documented; all of it came from the Play services log):
1. **An unread STREAM payload blocks the whole connection.** Nearby keeps about 10 chunks of each incoming stream
   for the app. A player whose buffer is full stops reading, and then the card's moves, the commit and the file all
   stall until Wi-Fi Direct breaks ("Broken pipe"). So every stream is drained into a file the moment it arrives.
2. **Cancelling a STREAM payload while it is still sending stalls the connection too** ("short-circuiting Payload …
   failed to detach the next chunk"). So nothing is cancelled: the file is read in 1 MB pieces, each with a length,
   and a piece nobody wants any more is drained and dropped.
3. **A stream can arrive before the frame sent ahead of it**, because Nearby calls back on several threads. So pieces
   start draining before the frame naming their request is in.
4. **Quality MEDIUM is classic Bluetooth, not Wi-Fi.** Live video waits for HIGH.
5. **Video bytes and the card's moves share one connection with no priorities.** While pieces flow, moves pause for
   300–440 ms. The full-file transfer after the drop starves the pieces (1.7–3.3 s each instead of 0.2 s).

Measured: Wi-Fi Direct carries about 150–220 Mbit/s. But one piece in flight at a time gives about 25 Mbit/s, and a
file whose audio is far from its video needed 13 MB before its first frame: about 5 s to start.

**Next, in this order:**
1. LH-5, moved ahead: the video's bytes go over our own connection on the shared Wi-Fi (an HTTP range server on the
   sender, read by the player), so they never hold up the card's moves, and many ranges can be read at once.
   Nearby keeps finding the phones and carrying the slide.
2. LH-4: one transfer. The receiver keeps what it plays and fills in the rest while the player isn't loading.

Not yet: the Twin Simulator path, the `PlaybackReady`/`PlaybackUnsupported` replies, a codec check, an audio
cross-fade, and a receiver that lets go of a card whose sender went silent (`docs/reference/freeze.png`).

**Measuring sync:** film both screens at 240 fps and count the frames between the two halves at a scene cut. The
Twin Simulator also shows each engine's position and the error from the target live on screen.

---

## 7. Tests

- **commonTest:** `PlaybackHandoff` for every transition, including cancel at each state, timeouts, the slow link
  at commit and link loss; position prediction with clock offset and speed; drift controller (speed vs seek bands);
  codec round trip of each new message; `LoopbackMediaChannel` ranges (offset, to end, seek while open).
- **Android host tests:** `ChannelDataSource` against a fake `MediaChannel` (open, read, close, seek, error →
  `DataError`).
- **ViewModel tests** (State/Action/Event): opening a video card, lifting a playing card, receiving an offer,
  fallback event when unsupported.
- **On two phones:** the LH-3 slow-motion check; a 1 GB file; Wi-Fi turned off mid-handoff; a codec the receiver
  can't decode; drag-back at each moment.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| Link still on Bluetooth (~1–2 Mbps) right after connecting | Live handoff only at `LinkQuality.HIGH`; otherwise the normal file send |
| Two decoders + TextureView + blur + bloom on a mid-range phone janks the slide | Fix the existing slide jank first (`docs/PLAN.md` What's next, item 2); cap card video size; profile with Macrobenchmark and a Perfetto trace before LH-3 |
| Receiver can't decode the codec (HEVC, HDR, 10-bit) | `codecs` in the offer, check before preparing, `PlaybackUnsupported` → file send |
| Video header at the end of the file | Range requests handle it (one extra round trip); measured in LH-2 |
| Decoder start delay differs per phone | Measure it at prepare (first-frame time), add it to the seek lead |
| Clock offset drifts during a long straddle | `PlaybackClock` every 500 ms; re-sample the offset on each lift |
| Nearby callbacks on the main thread (the open issue on `fix/multi-file-transfer`) | Do the media channel's work on `Dispatchers.IO`; land that fix first |
| An HTTP range server on the LAN can be reached by other devices | Per-pairing random token on every request, bound to the pairing; TLS with a certificate exchanged at pairing is a later hardening |
| Android blocks cleartext HTTP by default | Network security config allowing cleartext only for the local server, or TLS |

---

## 9. KMP later

- **Shared already:** the protocol, `PlaybackHandoff`, prediction and drift control, `MediaChannel`, the range
  protocol (kotlinx-io), and Ktor client/server (multiplatform).
- **iOS:** `AVPlayer`, loading from the channel through `AVAssetResourceLoaderDelegate` (or a local HTTP URL).
  `Network.framework` for the LAN path. Google's Nearby Connections for iOS (in the open-source `google/nearby`
  repo) needs checking before we rely on it.
- **Desktop:** no Nearby, so LH-5's LAN path is the only link (it becomes Phase 8's `LanTransport`). Video in
  Compose Desktop is the weak spot: VLCJ, or something based on FFmpeg/JavaCV, behind the same `VideoEngine`.

---

## 10. Order and dependencies

Start LH-0 only after, on the main line:
1. `fix/multi-file-transfer` is merged (the same Nearby callback and main-thread code).
2. The slide jank fix (`docs/PERFORMANCE_FIXES.md` on `chore/generate-baseline-profile`) is merged, so the GPU
   budget is known before adding live video to the card.
3. Air Gesture from the Android-only copy is ported (it changes `SlideMessage` and `ShelfViewModel` too, so it is
   easier to port before this lands than after).

Then rebase `feature/live-video-handoff` onto `master` and begin.

## 11. Decisions to confirm

1. **Receiver not ready at commit:** resume at the commit frame (short freeze, nothing skipped; **default**) or
   jump ahead to keep time (no freeze, skips content).
2. **Which link first:** Nearby streaming (LH-2) before the same-Wi-Fi path (LH-5) (**default**, since it works
   with no router), or LAN first like drooop (simpler, faster, needs the same Wi-Fi).
3. **Where the received video ends up:** in the shelf + Downloads like any received file (**default**), or only in
   the shelf until the user saves it.
