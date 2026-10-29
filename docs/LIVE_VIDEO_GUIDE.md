# Live Video Handoff: Developer Guide

This guide explains, from zero, how a video that is **playing** on one phone keeps playing as its card slides
across to the phone next to it. It assumes no prior knowledge of Nearby Connections, video files, ExoPlayer or
playback sync. Every step names the file and function that does it, so you can read the code next to the guide.

- **Plan and phases:** `docs/LIVE_VIDEO_HANDOFF.md` (what we set out to build, and in what order).
- **This guide:** how it works today, why it is built this way, and how to debug it.
- **State described:** branch `feature/live-video-one-transfer` (LH-4, "one transfer"), 2026-10-29, on top of
  `feature/live-video-handoff`. What LH-4 changed and why: `docs/reports/2026-10-29-live-video-one-transfer-report.md`.

---

## Contents

1. [What the user sees](#1-what-the-user-sees)
2. [The big idea](#2-the-big-idea)
3. [Background you need](#3-background-you-need)
4. [Map of the code](#4-map-of-the-code)
5. [The whole journey, step by step](#5-the-whole-journey-step-by-step)
6. [Reading the video from the other phone, in depth](#6-reading-the-video-from-the-other-phone-in-depth)
7. [Keeping both halves on the same frame](#7-keeping-both-halves-on-the-same-frame)
8. [Showing one video on several cards](#8-showing-one-video-on-several-cards)
9. [Threads: what runs where, and why](#9-threads-what-runs-where-and-why)
10. [What the phones taught us](#10-what-the-phones-taught-us)
11. [Every number you can tune](#11-every-number-you-can-tune)
12. [Debugging on two phones](#12-debugging-on-two-phones)
13. [Tests](#13-tests)
14. [Known limits and what's next](#14-known-limits-and-whats-next)
15. [Glossary](#15-glossary)

---

## 1. What the user sees

1. Phone A (the **sender**) and phone B (the **receiver**) are paired and lying side by side.
2. On phone A, the user taps a video card on the shelf. It plays inside the card.
3. The user long-presses the card (it **lifts**) and drags it toward phone B. The video keeps playing.
4. When the card crosses the screen edge, part of it is drawn on phone A and the rest on phone B. **Both halves show
   the same moving picture**, as if it were one card lying across both phones.
5. The user lets go past the edge (the **drop**, or **commit**). The card lands on phone B's shelf, the sound moves to
   phone B, and the video keeps playing there.
6. In the background, phone B completes the file from the parts it already read to play the video, and asks for
   only the rest. Once it is all there (checked against phone A's), the video plays on from that copy, without a
   restart.

The hard part is step 4: phone B does not have the file yet, but it must already show the right frame.

---

## 2. The big idea

**A playing video is not sent as pictures.** Each phone decodes and draws the video itself. The two phones only
need to agree on two things:

1. **The bytes of the file.** Phone B reads the parts of the file it needs from phone A, while it plays.
2. **The time.** Phone A tells phone B, four times a second, "my video is at 12.345 s and playing". Phone B adjusts
   its own player to be at the same moment.

So there are three channels of information between the phones:

```
Phone A (sender)                                         Phone B (receiver)
────────────────                                         ──────────────────
card position, 60 times a second   ── DragMove ──────▶   draws its half of the card
"my video is at 12.345 s"          ── PlaybackClock ─▶   speeds up / slows down to match
bytes of the video file            ── pieces ────────▶   its player decodes them
```

Everything else in this guide is the detail of how each channel works and what went wrong on real phones.

---

## 3. Background you need

### 3.1 Nearby Connections

[Nearby Connections](https://developers.google.com/nearby/connections/overview) is a Google Play services library
that lets two phones find each other and talk without a router or the internet.

- **Advertise and discover.** Both phones advertise themselves and look for others at the same time. When one finds
  the other, they connect. In our app this is `NearbyPeerLink` (`core/transfer/nearby`), with strategy
  `P2P_POINT_TO_POINT` (exactly two phones).
- **Endpoint.** Nearby's name for "the other phone" on this connection: a short string id.
- **The connection starts on Bluetooth, then upgrades.** Right after connecting, the link runs over Bluetooth, which
  is slow (about 1–2 Mbit/s). A few seconds later Nearby moves it to **Wi-Fi Direct** (or a Wi-Fi hotspot one phone
  opens), which is much faster (we measured 150–220 Mbit/s).
- **`onBandwidthChanged`** tells the app how good the link is now, as a `BandwidthInfo.Quality`:
  `LOW` (1, Bluetooth Low Energy), `MEDIUM` (2, classic Bluetooth), `HIGH` (3, Wi-Fi). Live video is only possible
  at `HIGH`.
- **Payloads.** Everything sent over the connection is a *payload*. There are three types:

  | Type | What it is | We use it for |
  |---|---|---|
  | `BYTES` | A small byte array, at most 32 KB, delivered in one go | Card messages (`SlideMessage`) and link frames (`LinkFrame`) |
  | `FILE` | A whole file. The receiver can only use it once it has fully arrived | The full file after the drop |
  | `STREAM` | An open-ended stream of bytes (`InputStream` on both sides), readable while it arrives | Pieces of the video for live playback |

- **All payloads share one connection, in order, with no priorities.** A big payload can hold up the small ones
  behind it. This matters a lot: see [Section 10](#10-what-the-phones-taught-us).

### 3.2 What is inside a video file

A typical phone video is an **MP4** file. Inside it:

- **`ftyp`**: a few bytes at the very start saying "this is an MP4".
- **`moov`** (the *header* or *index*): the table of contents. It says where every frame of video and every chunk of
  audio lives in the file, and when each one should be shown. **A player cannot play anything until it has read the
  `moov`.** Phones often write it at the **end** of the file (they don't know the table until recording stops).
- **`mdat`**: the actual video frames and audio samples, interleaved: some video, some audio, some video...

So to start playing from 0:12, a player reads: the start (`ftyp`), then the `moov` (maybe at the end of the file),
then the part of `mdat` around 0:12. It never needs the whole file. It reads **byte ranges**: "give me bytes
1 048 624 to 2 097 199".

A real example from our logs, a 67 MB file whose `moov` is at the end:

```
Piece of 67 at 0           ← the start of the file (ftyp says "MP4", and where moov is)
Piece of 67 at 67391302    ← jump to the end: the moov, the index
Piece of 67 at 48          ← back to the start of mdat, where the frames are
Piece of 67 at 1048624     ← and onward, 1 MB at a time
```

**Bitrate** is how many bytes one second of video takes. A phone's 1080p video is about 8–20 Mbit/s, so 1 MB holds
about half a second to one second of video.

**Interleaving matters.** If a file's audio is stored far from its video (badly interleaved), the player must
download a lot before it has *both* the first video frame *and* the matching audio. One of our test files needed
13 MB before its first frame.

### 3.3 ExoPlayer (AndroidX Media3)

[ExoPlayer](https://developer.android.com/media/media3/exoplayer) is Android's standard video player library. The
parts we use:

- **`ExoPlayer`**: the player. You give it something to play, call `prepare()`, and set `playWhenReady = true`.
- **`MediaItem` / `MediaSource`**: what to play. `MediaItem.fromUri("content://…")` for a local file or
  `"http://…"` for a web address. For our own way of reading bytes we build a `ProgressiveMediaSource` ourselves.
- **`DataSource`**: the object that actually fetches bytes. ExoPlayer calls `open(range)`, then `read(...)` many
  times, then `close()`. There are ready-made ones for files and HTTP. **We wrote our own, `RemoteDataSource`, that
  fetches bytes from the other phone over Nearby.**
- **The loader thread.** ExoPlayer calls the `DataSource` on its own background thread, and expects `read()` to
  **block** (wait) until bytes are there. This is important for [Section 9](#9-threads-what-runs-where-and-why).
- **Buffering.** ExoPlayer reads ahead of what it is showing and keeps that in memory (the *buffer*).
  `DefaultLoadControl` sets how much: how much to have before starting, how much to keep ahead.
- **States.** `IDLE` → `BUFFERING` (waiting for data) → `READY` (can play) → `ENDED`. `playWhenReady` says whether
  it *should* play when ready. `isPlaying` is true only when it is ready, should play, and frames are moving.
- **Speed.** `setPlaybackSpeed(1.2f)` plays 20 % faster. We use this to catch up.
- **Seek.** `seekTo(ms)` jumps. After a jump the player usually has to fetch new bytes, so it buffers for a moment.

### 3.4 Where the picture is drawn: TextureView

A video player draws into a **`Surface`**. On Android you get a Surface from either:

- a **`SurfaceView`**: fast, but drawn in a separate layer *behind* the app. It does not move, scale, rotate or clip
  with the rest of the UI. Useless for a card that tilts and slides.
- a **`TextureView`**: drawn as a normal part of the UI, so it follows Compose's `graphicsLayer` (move, scale,
  rotate, rounded corners, clipping). **We use this.** Bonus: `TextureView.getBitmap()` returns the current frame
  as a picture, which we use to hide the moment when the video moves between two views.

### 3.5 HTTP Range requests

On a normal Wi-Fi network, the receiver can read the file straight from the sender like a web page. HTTP has a
standard way to ask for part of a file: the request header `Range: bytes=1000-1999`. The server answers
`206 Partial Content` with just those bytes. ExoPlayer's HTTP data source does this by itself. We wrote a small
server for it: `LanFileServer` (see [6.6](#66-the-wi-fi-fallback-lanfileserver)).

### 3.6 Coroutines and blocking code

Most of the app uses Kotlin **coroutines**: functions marked `suspend` that can wait without holding a thread.
Some code, though, is **blocking**: it holds its thread while it waits (Java's `InputStream.read`, ExoPlayer's
`DataSource.read`). The two worlds meet in a few places in this feature; [Section 9](#9-threads-what-runs-where-and-why)
explains each one.

---

## 4. Map of the code

The app follows the module rules in `CLAUDE.md`: `domain` modules are pure Kotlin, platform code lives in
`androidMain` behind interfaces.

| Module | File | Role in live video |
|---|---|---|
| `:core:domain` | `util/ByteStream.kt` | A blocking byte stream (`read` / `close`): what the player reads from |
| `:core:media` (new) | `VideoEngine.kt` | The player interface the view model talks to, and `VideoSource` (Local / Url / Remote) |
| | `VideoSurface.kt` (+ `.android.kt`) | The composable that draws the video, a `TextureView` |
| | `ExoVideoEngine.kt` | `VideoEngine` on ExoPlayer, and the routing of the picture between surfaces |
| | `RemoteDataSource.kt` | ExoPlayer `DataSource` that reads a `VideoSource.Remote` |
| | `di/MediaModule.kt` | Koin: a new `ExoVideoEngine` per consumer |
| `:core:transfer:domain` | `SlideMessage.kt` | All messages between the phones, including `PlaybackOffer` and `PlaybackClock` |
| | `MediaChannel.kt` | Interface: share a file, read the peer's file (over Nearby or Wi-Fi) |
| | `PeerLink.kt` | The link to the other phone; exposes `media: MediaChannel?` |
| `:core:transfer:data` | `SlideMessageDto.kt`, `SlideMessageMapper.kt`, `SlideMessageValidation.kt`, `SlideMessageCodec.kt` | Turning messages into bytes (CBOR) and back, and rejecting impossible values |
| `:core:transfer:nearby` | `NearbyPeerLink.kt` | The Nearby connection; routes every payload to the right place |
| | `NearbyMediaChannel.kt` | `MediaChannel` on Nearby: pieces, draining, serving |
| | `Reconnect.kt` | `LinkFrame`: the link's own binary frames, including `RangeRequest` / `RangeOpened` / `RangeRefused` |
| | `LanFileServer.kt` | The Wi-Fi fallback: a tiny HTTP range server, and the reachability check |
| | `HttpRange.kt` | Parsing the HTTP `Range` header |
| `:feature:shelf:domain` | `PlaybackFollower.kt` | Pure logic that keeps the receiver on the sender's frame |
| `:feature:shelf:data` | `LinkedSlidePeer.kt` | Sends and receives the full file after the drop |
| `:feature:shelf:presentation` | `ShelfViewModel.kt` | Orchestrates everything, on both phones |
| | `components/CardVideo.kt` | `LocalCardVideo`: tells cards which item is playing |
| | `components/ShelfCard.kt` | Grid card: draws the video if it is the playing item |
| | `slide/IncomingSlide.kt` | `IncomingCard`: the peer's card on this screen, with the video |
| | `components/ArrivingCard.kt` | The landed card while its file arrives, with the video and a progress ring |
| `:app` | `AndroidManifest.xml`, `res/xml/network_security_config.xml` | `INTERNET` permission, and plain HTTP allowed (for the Wi-Fi fallback) |

---

## 5. The whole journey, step by step

This section follows one video from the tap on phone A to the end on phone B. Each step says **what happens**,
**where in the code**, and **why**.

```
 Phone A (sender)                       link                      Phone B (receiver)
 ════════════════                                                 ══════════════════
 5.1 tap: video plays in the card
 5.2 long-press: card lifts
 5.3 DragStart ───────────────────────────────────────────────▶  5.4 shows the incoming card (still off-screen)
     PlaybackOffer(pos, url) ─────────────────────────────────▶      Ping ◀─▶ Pong (measures the delay)
                                                                  5.5 chooses how to read the video,
                                                                      loads it muted, a little ahead
     ◀───────────────── RangeRequest(piece at offset) ──────────  5.6 the player asks for bytes
 5.6 serves the piece ── RangeOpened + STREAM payload ────────▶      drains it, the player reads it
     PlaybackClock every 250 ms ──────────────────────────────▶  5.7 speeds up / slows down to match
     DragMove 60×/s ──────────────────────────────────────────▶  5.8 draws its half of the card
 5.9 let go past the edge
     DragCommit ──────────────────────────────────────────────▶  5.10 sound on, card lands
     its own video stops                                               the video keeps playing
     ◀──────────────────────────────── FileRoute(FETCHING) ─────  5.11 "I'll take the file from the pieces"
 serves the missing pieces and the SHA-256 ◀─▶ requests            fills the gaps, checks the hash,
     ◀──────────────────────────────── FileRoute(FETCHED) ──────       keeps the file; the player reads it
 5.12 item removed from the shelf
```

### 5.0 Before anything: paired, and on Wi-Fi

- The phones are paired (`feature/pairing`) and lined up, so each knows which edge faces the other.
- `NearbyPeerLink.connections.onBandwidthChanged` → `NearbyMediaChannel.onBandwidthChanged` stores the link quality
  and logs `Link quality 3 (3 is Wi-Fi): live video on`.
- `NearbyMediaChannel.isFast` is `quality >= HIGH`. Until then, live video over Nearby is off.

### 5.1 Tapping a video plays it in its card (phone A)

`ShelfViewModel.onPreviewClick(id)`:
1. If the item is a video and there is a `VideoEngine`, it calls
   `engine.load(VideoSource.Local(item.uri))`.
2. It sets `state.playing = PlayingVideoUi(id, isPaused = …)`.

`ShelfScreen` turns `state.playing` into a `CardVideo(engine, itemId, isPaused)` and provides it as
`LocalCardVideo`. Every card checks "is the playing item me?" (`LocalCardVideo.current?.takeIf { it.itemId == item.id }`)
and, if so, draws a `VideoSurface` instead of its thumbnail (`ShelfCard.VideoPreview`).

`ExoVideoEngine.load`:
- creates the `ExoPlayer` on first use (`newPlayer()`), with our buffer settings ([Section 11](#11-every-number-you-can-tune));
- resets volume to 1 and speed to 1;
- `setMediaItem(MediaItem.fromUri(uri), startAtMs)`, `prepare()`, `playWhenReady`;
- logs `Load from this phone at 0 ms`.

### 5.2 Lifting the card keeps the video playing (phone A)

When the user long-presses, `SlideState` (`slide/SlideState.kt`) sets `isAloft = true` and draws a **copy** of the
card in the overlay (`SlideOverlay`), which follows the finger. Now two composables show the same item: the grid
slot (hidden) and the lifted copy.

The **one** `ExoPlayer` keeps running. Only *where it draws* changes: the copy's `VideoSurface` is active and
composed last, so the engine moves the picture to it. The copy shows the slot's current frame until its own first
frame arrives, so there is no flash. [Section 8](#8-showing-one-video-on-several-cards) explains this routing.

### 5.3 The sender announces the card and its video (phone A)

The UI reports the lift as `ShelfAction.OnSlideLifted` → `ShelfViewModel.startMirroring(action)`:

1. Builds `SlideMessage.DragStart` (item id, name, type, size, card size in millimetres, a small thumbnail).
2. Calls `shareVideo(peer, item, slide)`. This only proceeds if the lifted card **is the playing video**:
   - `media.share(item.id, item.uri)` registers the file so the other phone may read it. It returns an `http://`
     address if this phone is on a Wi-Fi network (from `LanFileServer.share`), or `null`.
   - If there is no address **and** the Nearby link isn't fast yet, there is no way to carry the video: it unshares
     and returns `false` (the card goes over as a still picture, like any file).
3. Under `sendLock` (so messages go out in order) it sends the `DragStart`. If that is too big because of the
   thumbnail, it retries without it.
4. **Right behind the start**, if the start went out, it sends a `PlaybackOffer`, built by `playbackReport(...)`:

   ```kotlin
   PlaybackOffer(itemId, sizeBytes, positionMs = engine.positionMs, isPlaying, httpUrl)
   ```

   The position is read **at the moment of sending**, so it is as fresh as possible.
5. It starts `reportPlayback(peer, slide)`: a loop that, every `PlaybackClockMillis` (250 ms), sends
   `PlaybackClock(itemId, positionMs, isPlaying)` for as long as this card is the one in the air (`outgoing === slide`).

The messages are encoded with CBOR (`SlideMessageCodec`). On the wire, the offer is `"play"` and the clock is
`"clock"` with one-letter field names (`SlideMessageDto`), because clocks are sent often. The receiver checks every
value is plausible (`SlideMessageValidation`): a positive file size, no negative positions, and a URL that is plain
`http://` and not too long.

### 5.4 The receiver learns a card is coming (phone B)

`ShelfViewModel.onPeerMessage` handles each message:

- **`DragStart`**: remembers it as `incoming`, sends a `Ping` (to measure the link delay; the `Pong` updates
  `roundTripMillis`, a smoothed round-trip time), and emits `ShelfEvent.PeerCardLifted` so the UI prepares the card.
- **`PlaybackOffer`**: calls `followPeerVideo(peer, offer)`.

### 5.5 The receiver starts the video, muted and a little ahead (phone B)

`ShelfViewModel.followPeerVideo`:

1. Ignores the offer unless it is for the card that is coming in.
2. Creates a `PlaybackFollower` (the sync logic, [Section 7](#7-keeping-both-halves-on-the-same-frame)) and gives it
   the offer as its first report: "the sender was at `positionMs`, playing, as of now minus the one-way delay".
3. Chooses **how to read the video**, best first:

   | Condition | Source | Why |
   |---|---|---|
   | Nearby link is fast (`media.isFast`, Wi-Fi Direct) | `VideoSource.Remote` (pieces over Nearby) | Tested on phones: the shared home Wi-Fi was far slower while Wi-Fi Direct was up |
   | Not fast, but the offer has a URL and `media.canReach(url)` is true | `VideoSource.Url` (HTTP over the home Wi-Fi) | The Nearby link is still on Bluetooth, but both phones are on the same Wi-Fi |
   | Neither | Nothing: `following = null` | The card comes over with its still picture, as any file |

   `canReach` sends a `HEAD` request to the URL with a 600 ms timeout (`answersFromHere` in `LanFileServer.kt`),
   only to private local addresses (`192.168.x.x`, `10.x.x.x`…), never anywhere else.
4. Loads the video **ahead** of where the sender is: `engine.load(source, startAtMs = follower.startAt(now))`.
   Loading takes time (opening the file remotely, reading the index): about 1.8 s on a Redmi Note 8. If it started
   exactly at the sender's position, it would be 1.8 s late when it begins. So it aims `StartLeadMs` (2 s) ahead.
5. Mutes it (`setVolume(0f)`): the sound stays on the sender's phone while the card is in the air.
6. Sets `state.playing = PlayingVideoUi(offer.itemId)`. Now `IncomingCard` finds the video through `LocalCardVideo`
   and draws a `VideoSurface`. Until the first frame, it shows the sender's thumbnail (the `placeholder`).

### 5.6 The player asks for bytes, and the sender serves them

This is the heart of the feature, explained fully in [Section 6](#6-reading-the-video-from-the-other-phone-in-depth).
In short:

1. ExoPlayer calls `RemoteDataSource.open(position)`, which calls `MediaChannel.open(fileId, offset)`.
2. `NearbyMediaChannel` sends a `RangeRequest` frame: "send me 1 MB of file 67 from byte X".
3. Phone A's `serve()` opens the file at X and sends it as a `STREAM` payload, plus a `RangeOpened` frame naming it.
4. Phone B drains the stream into a temporary file as fast as it arrives, and the player reads from that file.
5. While the player reads one piece, the next 3 pieces are already on their way.

### 5.7 Keeping in step (phone B)

Every `PlaybackClock` goes to `ShelfViewModel.onPeerClock`, which feeds it to the `PlaybackFollower` and applies
its answer: play a little faster, a little slower, hold the frame, or jump. Fully explained in
[Section 7](#7-keeping-both-halves-on-the-same-frame).

### 5.8 Drawing the two halves

The two phones never send pictures of the card. Phone A sends `DragMove` messages (60 per second while the card is
near the edge, from `ShelfViewModel.mirrorMove`) with the card's position **in millimetres** relative to the shared
edge. Phone B converts that to its own pixels (`incomingCardPosition` in `core/transfer/domain/Crossing.kt`) and
moves `IncomingCard` with a `graphicsLayer`.

- Phone A clips the card at its screen edge (the whole shelf screen is `clipToBounds`, in `ShelfScreen`).
- Phone B draws the card starting off its screen edge, so only the part that has "crossed" is visible.
- Both draw the same video at (nearly) the same moment, so the picture continues across the gap.

### 5.9 The drop (phone A)

When the user lets go past the edge, the UI sends `ShelfAction.OnItemSent` → `ShelfViewModel.sendItem`:

1. Adds the item to `sentIds`, which **hides it from the shelf** (the grid closes the gap).
2. `publishItems()` sees the playing item is no longer on screen → `stopVideo()`: **phone A's video stops and its
   player is released.** From now on phone B is the only one playing.
3. `handOver(...)` sends `DragCommit`. If it fails, the card comes back with an "undo" option.
4. `deliverFile(item)` sends the full file (5.11).

### 5.10 The receiver takes over (phone B)

On `DragCommit` (`onPeerMessage`):

1. The card gets a slot at the top of the shelf (`arriving`, an `ArrivingItemUi`).
2. `takeOverPeerVideo(id)`: stops following (`following = null`), cancels any hold, sets **speed 1 and volume 1**,
   and makes sure it is playing. **The sound is now on phone B.**
3. Decides how the file comes over (`Delivery`, in `:feature:shelf:domain`): **`FETCH`** if it plays the video from
   pieces over Nearby, **`ASK_TO_SEND`** if the video was offered but isn't read that way (home Wi-Fi, or not loaded
   yet), **`SENT`** for any other card.
4. Starts `receive(peer, start, delivery, …)` in the background, to get the full file.

`ArrivingCard` draws the same playing video (it is still the same item id), with a small progress ring in the corner
showing how much of the file has arrived.

### 5.11 The file comes from the pieces already read (phone B), LH-4

Before LH-4, phone A sent the whole file again as a Nearby `FILE` payload right after the drop. That second
transfer competed with the pieces the player still needed: pieces slowed from ~0.2 s to 2–3 s, the video stalled,
and phone B had to reload its player onto the received copy. Now, for a card whose video phone B reads over Nearby:

1. **Who decides:** phone B. When phone A sent a `PlaybackOffer` with the card, it doesn't send the file at once:
   `LinkedSlidePeer.send` waits (up to `RouteWait`, 5 s) for phone B's `SlideMessage.FileRoute`.
2. **Phone B answers `FETCHING`** (`LinkedSlidePeer.fetch`) and calls `MediaChannel.fetch`:
   - every piece the player read arrived whole and was **kept** (`NearbyMediaChannel.keepPiece`), so a good part of the
     file is here already;
   - the missing parts (`missingSpans` in `FileSpans.kt`) are asked for 3 at a time, each round **only once no piece
     the player waits for is on its way** (`playerPieces`; at most `PlayerFirstMillis`, 2 s, of waiting);
   - meanwhile phone A hashes its file (`LinkFrame.DigestRequest` → `LinkFrame.Digest`, SHA-256);
   - the pieces are copied into one file (`assemblyPlan`, `assemble`), hashed on the way, and compared.
3. **Kept like any received file:** `ReceivedFileStore.keep` copies it to Downloads, then `MediaChannel.settle(id, uri)`
   tells the channel the file lives there now, then `repository.addFile` puts it on the shelf.
4. **Phone B answers `FETCHED`.** Only now does phone A's send count as done (5.12).
5. **The player never reloads.** After `settle`, `NearbyMediaChannel.open` and the open `PiecedStream` read from the
   kept file (`goOnFromHere`, at the next piece boundary). The same `ExoPlayer`, the same `VideoSource.Remote`.

`ShelfViewModel.receive` then remaps `state.playing` to the new item id, as before.

**If something goes wrong:**

| What | Phone B | Phone A |
|---|---|---|
| The fetch fails, the link is still up (no room, hash mismatch, pieces refused) | Lets go of what it kept (`forget`), answers `SEND`, and takes the file the usual way | Sends the `FILE` as before |
| The link drops | The receive fails (`DISCONNECTED`), the card is withdrawn | The send fails, the card comes back with Undo |
| Phone B's user taps the arriving card | `CANCELLED` | The send fails as cancelled, the card comes back |
| Phone B never answers (an older build) | n/a | After 5 s, sends the `FILE` as before |

**Video read over the home Wi-Fi (`VideoSource.Url`):** phone B answers `SEND`, the file comes as a `FILE` payload,
and `playLocalCopy()` reloads the player onto the received copy at the same position, as before LH-4. That path
reads over HTTP from phone A's `LanFileServer`, which stops serving once phone A lets go of the file.

### 5.12 The sender lets go of the file (phone A)

When the file has been delivered (phone B's `FETCHED`, or the end of the `FILE` transfer),
`ShelfViewModel.finishSend` removes the item from phone A's shelf (`RoomShelfRepository.removeItem`). That also gives
back the app's permission to read the original file (`releasePersistableUriPermission`). From then on, phone A
**cannot open the file any more**, so every piece request gets `RangeRefused`.

Phone B no longer needs phone A by then: with `FETCH` it sent `FETCHED` only after `settle`, so it already reads its
own copy; on the Wi-Fi path `playLocalCopy` moved the player. (Before `ec2a196`, phone B still read from phone A at
this point, and ExoPlayer failed after ~15 s with `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`.)

### 5.13 If the card is dragged back instead

- Phone A: `ShelfAction.OnSlideReturned` → `stopMirroring(id)`: `media.unshare(id)` (phone B may no longer read the
  file) and sends `DragCancel`. Its own video never stopped.
- Phone B: on `DragCancel`, `stopVideo()` releases its player, and the incoming card is withdrawn.

---

## 6. Reading the video from the other phone, in depth

### 6.1 The layers

```
ExoPlayer (loader thread)
   │  open(position) / read(buffer) / close()          blocking calls
   ▼
RemoteDataSource                (core/media)            Media3's DataSource contract
   │  source.open(offset) → ByteStream
   ▼
MediaChannel.open               (core/transfer/domain)  interface, pure Kotlin
   │
   ▼
NearbyMediaChannel              (core/transfer/nearby)
   │  PiecedStream: piece after piece, 3 ahead
   │  openPiece: RangeRequest → wait for the piece
   │  DrainedPiece: copies the STREAM into a temp file as it arrives
   ▼
Nearby Connections (BYTES frames + STREAM payloads)
   ▼
NearbyMediaChannel.serve on the other phone: opens the file, sends the piece
```

### 6.2 `RemoteDataSource`: what the player sees

File: `core/media/src/androidMain/.../RemoteDataSource.kt`. ExoPlayer builds one through
`ExoVideoEngine.remoteMediaSource`, a `ProgressiveMediaSource` with a made-up address `peer://video/<id>` (the address
is only a label; our data source ignores it).

- **`open(dataSpec)`**: the player asks for the bytes from `dataSpec.position`, maybe with a length.
  1. Checks the position is inside the file.
  2. Calls `source.open(position)` (a `suspend` function) through `runBlocking`, because `open` must block
     ([Section 9](#9-threads-what-runs-where-and-why)).
  3. If that fails, throws `DataSourceException(ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)`. ExoPlayer retries a few
     times, then reports the error (`VideoEngineEvent.Failed`), and the view model stops the video.
  4. Returns how many bytes it will deliver (to the end of the file, or the length asked).
- **`read(buffer, offset, length)`**: reads from the `ByteStream`, never past what was promised. If the stream ends
  early, the link broke: it throws.
- **`close()`**: closes the stream. The player does this when it jumps elsewhere or stops.

### 6.3 Why pieces, and not one stream per range

The first version opened **one STREAM payload per range**: from the position to the end of the file. Two things
went wrong on phones ([Section 10](#10-what-the-phones-taught-us)):

1. When the player's buffer was full, it stopped reading. The stream stopped being read, and **the whole Nearby
   connection froze**: card moves, commit, everything.
2. When the player jumped, we cancelled the old stream. **Cancelling a stream mid-way also froze the connection.**

So now the file is read in **pieces of 1 MB** (`PieceBytes`), each a separate request with a fixed length. A piece
always ends by itself, so nothing ever needs cancelling. And every piece is **drained into a file the moment it
arrives** (`DrainedPiece`), so Nearby never waits for the player.

### 6.4 One piece, from request to bytes

**Receiver, `openPiece(fileId, offset)`:**
1. Takes a new request id (`nextRequestId`), and registers a `CompletableDeferred` for it in `requests`: a box that
   will receive the piece.
2. On the main thread (Nearby wants it), sends a BYTES payload with
   `LinkFrame.RangeRequest(requestId, offset, length = 1 MB, fileId)`.
3. Waits up to `OpenTimeoutMillis` (10 s) for the box to be filled.
4. Logs `Piece of 67 at 1048624 opened in 102 ms`, or `… no answer (asked: true)` on failure.

**Sender, `serve(request)`** (called from `NearbyPeerLink`'s payload callback via `onFrame`):
1. Looks up the file id in `shared`. Not shared (never was, or `unshare`d) → sends `RangeRefused`.
2. On `Dispatchers.IO`, opens the file and positions it at `offset` (`openAt`), wrapped in a `LimitedInputStream`
   that ends after `length` bytes. Can't open (e.g. permission gone) → `RangeRefused`.
3. Creates a STREAM payload from that input stream, remembers it in `sending`, and sends **two** payloads:
   `RangeOpened(requestId, payloadId)` (BYTES) and the STREAM itself.
4. When Nearby reports the stream finished or failed (`onTransferUpdate`), it closes the file.

**Receiver, the stream arrives: `onStream(payload)`:**
1. Wraps it in a `DrainedPiece`, which immediately starts copying it into `cacheDir/live-ranges/piece-<payloadId>.part`.
2. Which request does this piece answer? The `RangeOpened` frame says so, but **Nearby can deliver the stream
   before the frame** (it calls back on several threads). So both orders are handled:
   - frame first: `onFrame(RangeOpened)` stores `answers[payloadId] = requestId`; when the stream comes,
     `onStream` finds it and `deliver`s.
   - stream first: `onStream` puts the piece in `early[payloadId]`; when the frame comes, `onFrame` finds it there
     and `deliver`s. A piece never claimed is dropped after 20 s.
3. `deliver(requestId, piece)` fills the request's box. If nobody is waiting any more (timed out, or the player went
   elsewhere), the piece is `close`d: it keeps draining to the end, then its file is deleted.

**`DrainedPiece` in detail** (bottom of `NearbyMediaChannel.kt`):
- A background job (`scope.launch(Dispatchers.IO) { drain() }`) loops `input.read(buffer)` → write to the file →
  `written += n` → wakes any reader.
- `read(...)` (called by the player's loader thread) waits on a `Condition` until there are bytes past its
  `position`, then reads them from the file with a `RandomAccessFile`. At the end it returns -1. If the drain failed,
  it throws.
- `close()` marks it unwanted by the reader. Bytes still arriving are still written (never cancelled!). If the
  piece arrives whole and its request is known (`claim`), its file is **kept** for LH-4 (6.7); otherwise it is
  deleted when the drain ends.
- A `ReentrantLock` protects `written` / `isEnded` / `failure`, because the drain and the reader are two threads.

### 6.5 `PiecedStream`: many pieces as one stream

`MediaChannel.open(fileId, offset)` returns a `PiecedStream`: to the player it is a single stream from `offset` to
the end of the file.

- It starts with the first piece (already asked for and arriving).
- **Pieces ahead.** Once the player has read half of the current piece, `fillAhead()` makes sure `PiecesAhead` (3)
  more pieces are requested, in order, each with `scope.async { openPiece(...) }`.
  - Why not one at a time? Each piece costs a round trip (request → answer). With one at a time, the link was idle
    most of the time: about 25 Mbit/s on a link that can do 200. With 3 ahead, first frame went from about 5 s to
    1.8 s.
  - Why wait for half? A player that only glances at a piece (reading the `ftyp` before jumping to the `moov` at the
    end) shouldn't trigger 3 MB of downloads it will throw away.
- When the current piece is fully read, it takes the next one from the queue (waiting for it if needed), closes the
  old one (deleting its temp file), and continues.
- A piece shorter than 1 MB is the last piece of the file: end of stream.
- `close()` closes the current piece and lets the pieces still on their way drain and be dropped as they arrive.

### 6.6 The Wi-Fi fallback: `LanFileServer`

Used only when the Nearby link is not fast yet (still Bluetooth) **and** both phones are on the same Wi-Fi.

**Sender:** `LanFileServer.share(fileId, uri)`:
1. Finds this phone's address on its Wi-Fi network (`wifiAddress()`: interface `wlan0` first, private IPv4 only).
2. Starts a server socket on any free port (once; it stays up). Each connection is served on its own thread.
3. Makes a random 128-bit **token** for this file and returns
   `http://192.168.1.23:41234/v/<fileId>?t=<token>`.

`serve(client)` reads one HTTP request: only `GET`/`HEAD`, only a shared file id **with the matching token** (compared in
constant time with `MessageDigest.isEqual`), otherwise `404`. It parses the `Range` header (`parseByteRange` in
`HttpRange.kt`: `bytes=a-`, `bytes=a-b`, `bytes=-n`) and answers `200` (whole file) or `206` (a part) with the
bytes, or `416` if the range is outside the file.

**Receiver:** gives the URL to ExoPlayer as `VideoSource.Url`. ExoPlayer's own HTTP data source does the range requests.

**Security:** plain HTTP (no TLS), because the phones have no certificates. Only files being shared right now are
served, only with their random token, and the receiver only connects to private local addresses. The app needs
`INTERNET` and `ACCESS_NETWORK_STATE`, and `network_security_config.xml` allows cleartext HTTP (Android blocks it by
default; Android has no setting for "local addresses only").

**Why only a fallback:** on phones, reading over the home Wi-Fi **while Nearby was also on Wi-Fi Direct** was very
slow (one load took 25 s): the phone has one Wi-Fi radio, which keeps switching between the two networks, and every
byte crosses the router twice.

### 6.7 Keeping pieces and fetching the rest (LH-4)

State in `NearbyMediaChannel`:

| Field | What it holds |
|---|---|
| `origins` | Request id → which file and offset a piece is for, until it arrives (then `DrainedPiece.claim`) |
| `kept` | File id → the pieces of it that arrived whole (`KeptPiece`: a byte span and its temp file) |
| `local` | File id → where that file is on this phone now (the fetched copy, then the Downloads copy after `settle`) |
| `digests` | Request id → the SHA-256 awaited from the other phone |
| `playerPieces` | How many pieces asked for by players haven't fully arrived: a fetch holds back while it's above 0 |

`fetch(fileId, size)`:
1. Checks there is room: twice the missing part plus `SpareBytes` (the fetched file, then its Downloads copy).
2. Asks for the digest (`askDigest`) in parallel.
3. `fillIn`: loops `missingSpans` → up to `FetchPiecesAhead` requests → waits for them to drain. A round that adds
   nothing counts as fruitless; after `FetchRetries` of those it gives up.
4. `assemble`: copies each byte once from the kept pieces into `cacheDir/live-ranges/fetched-N`, hashing as it goes.
5. Compares with the digest. On a match: `local[fileId]` = that file, and the pieces are deleted.

`settle(fileId, uri)` points `local` at the kept Downloads copy and deletes the fetched copy (a player that has it
open keeps reading: Linux keeps an open file readable after deleting it). `forget(fileId)` drops everything kept of a
file: on a drag-back, when a new offer arrives with the same id (ids come back once an item is gone), and when a fetch
fails. Pieces left over from a crash are wiped at the next start (`piecesDir`).

The pure arithmetic (`missingSpans`, `coveredBytes`, `assemblyPlan`) lives in `FileSpans.kt` in `commonMain` and is
unit tested.

### 6.8 Who calls `onFrame`, `onStream`, `onTransferUpdate`

`NearbyPeerLink.payloads` (the `PayloadCallback`) receives every payload:

- **BYTES that start with the frame marker** are `LinkFrame`s. Range frames go to `media.onFrame` (only once the link
  is trusted); the rest (hello, proof, trust, forget) are pairing.
- **Other BYTES** are `SlideMessage`s: decoded and emitted on `transport.incoming` (the view model listens).
  `noteGap` logs `Card messages paused 384 ms, then DragMove` when the stream of card messages stalls.
- **FILE**: the full file after the drop.
- **STREAM**: `media.onStream`: a piece.
- **Transfer updates** go to `media.onTransferUpdate` first (pieces this phone is sending), then to file transfers.

When the link drops, `media.onLinkDropped()` fails every waiting request, closes every piece, and unshares everything.

---

## 7. Keeping both halves on the same frame

File: `feature/shelf/domain/src/commonMain/.../PlaybackFollower.kt` (pure Kotlin, fully unit tested).
Used by: `ShelfViewModel.followPeerVideo`, `onPeerClock`, `takeOverPeerVideo`.

### 7.1 The problem

Phone B must show the frame phone A shows **right now**. But:
- phone B only hears where phone A **was**, when the message left (it takes time to arrive);
- phone B's own player needs time to start, and time again after every jump;
- the two phones' clocks are not the same, so "it was 12.3 s at 15:04:05.120" means nothing to the other phone.

### 7.2 Estimating where the sender is now

Each report is turned into an estimate **using only phone B's own clock**:

```
sender's position now = reported position + (now − when it arrived here) + one-way delay
```

- `now − when it arrived` is measured on phone B's clock: no shared clock needed.
- **one-way delay** = half the round-trip time measured by `Ping`/`Pong` (`ShelfViewModel.oneWayMillis()`).
- Messages can be **delayed** (queued behind video pieces) but never arrive *early*. A delayed message makes the
  sender look *behind* where it really is. So among the reports of the last 1.5 s (`WindowMs`), the one giving the
  **largest** estimate is the least delayed one, and the best (`targetAt`: `reports.maxOf { … }`).
- If a new report disagrees with the estimate by more than `DiscontinuityMs` (300 ms), the sender jumped or stalled:
  the old reports are thrown away and it starts over from this one (`report`).
- When the sender is paused, the estimate is simply the reported position.

### 7.3 Deciding what to do

`correct(ownPositionMs, nowMs)` compares where phone B's video is with the target. `error = own − target`:
positive means phone B is **ahead**, negative means **behind**.

```
     far behind        behind          close        a little ahead     ahead
  ◀──────────────┼───────────────┼─────────────┼─────────────────┼──────────────▶  error
              −1000 ms         −25 ms        +25 ms            +300 ms

      Seek           Speed up         Speed 1         Slow down          Hold
  (jump ahead)    (up to 1.5×)     (nothing)       (down to 0.85×)  (freeze, wait)
```

| Band | Action | How |
|---|---|---|
| \|error\| ≤ 25 ms (`ToleranceMs`) | Normal speed | One or two frames: invisible |
| Ahead by 25–300 ms (`SlowDownWithinMs`) | Slow down | `speed = 1 − error / 2000`, at least 0.85 (`MinSpeed`) |
| Ahead by more than 300 ms | **Hold** | Pause for `error` ms, then play on at normal speed |
| Behind by 25–1000 ms (`SpeedUpWithinMs`) | Speed up | `speed = 1 − error / 2000`, at most 1.5 (`MaxSpeed`). 1 s behind is made up in 2 s |
| Behind by more than 1000 ms | **Seek** | Jump to `target + lead`, see below |

The speed formula means: "close the gap over about 2 seconds" (`CatchUpWindowMs`). For example, 200 ms behind
gives speed 1.1: in 2 s it plays 2.2 s of video, catching up 0.2 s.

**Why speed changes are safe:** phone B is **muted** while it follows, so a faster or slower pace has no pitch to
give it away, and a few percent of speed is hard to see.

**Why holds and seeks are bad:** a hold is a frozen frame; a seek makes the player fetch new bytes and rebuffer,
which also shows as a frozen frame. So the bands are set to use speed for as much as possible.

### 7.4 Jumping ahead, not to the target

After a seek, the player needs some time (call it **T**) before it plays again. If it jumped exactly to the target,
it would be T late when it starts, and need another jump. So it jumps to `target + lead`, aiming to arrive slightly
early, then holds for the small remainder.

The **lead** is learnt:
- It starts at `InitialLeadMs` (1000 ms).
- When a seek is made, the follower notes the time (`loadingSinceMs`).
- When phone B's video is seen moving again (`onPlaying`, called from `onPeerClock` when `engine.isPlaying`), the jump
  took `T = now − loadingSince`. The new lead is `T × 1.25 + 150 ms`, kept between 300 and 2000 ms
  (`MinLeadMs`, `MaxLeadMs`).
- The **first load** is not used to learn: it takes far longer than a jump (the file and index are opened), so it
  has its own lead, `StartLeadMs` (2000 ms), used by `startAt`.

### 7.5 How the view model applies it: `onPeerClock`

For each `PlaybackClock`:
1. `follower.report(...)`.
2. Nothing to steer yet if the video isn't loaded, or during a hold.
3. If the sender paused or resumed, do the same (`engine.pause()` / `play()`).
4. If the video is buffering (`clock.isPlaying && !engine.isPlaying`), do nothing: its position stands still, which
   says nothing about how far off it is.
5. `follower.onPlaying(now)` (learns from a jump that just finished).
6. Apply `follower.correct(...)`: `Seek` → `engine.seekTo`; `Speed` → `engine.setSpeed`; `Hold` → speed 1, pause,
   and a coroutine that plays again after the hold.

On the drop, `takeOverPeerVideo` stops all of this: speed 1, sound on, play on from wherever it is.

### 7.6 A worked example (from a real run, before the retune)

Phone B's log (`AirVideo`), Redmi Note 8 receiving:

```
20:01:05.149  Load from the other phone over Nearby at 3850 ms     ← started 1.5 s ahead
20:01:06.961  State ready at 3854 ms                               ← 1.8 s later: already ~0.3 s behind
20:01:07.336  Speed 1.098                                          ← speeding up …
20:01:07.886  Speed 1.2315                                         ← … but capped at 1.25×, and falling further
20:01:07.968  Seek from 4749 to 6893 ms                            ← past 0.5 s behind: jump 2.1 s ahead
20:01:08.546  State ready at 6896 ms                               ← the jump took 0.58 s
20:01:08.630  Pause at 6896 ms                                     ← it overshot: hold …
20:01:09.879  Play at 6902 ms                                      ← … for 1.25 s (a frozen frame)
```

This is why the numbers were changed: start 2 s ahead (instead of 1.5 s), speed up to 1.5× within 1 s (instead of
1.25× within 0.5 s), slow down to 0.85× within 300 ms ahead (instead of holding past 120 ms), and a first jump of
1 s ahead (instead of 1.5 s).

---

## 8. Showing one video on several cards

During a slide the same video can be on screen in several places on one phone: the grid slot and its lifted copy
(phone A), the incoming card and then the arriving card (phone B). There is **one** `ExoPlayer` per engine, and a
player draws into **one** surface at a time. `ExoVideoEngine` decides which.

**Outputs.** Each `VideoSurface` (`VideoSurface.android.kt`) creates a `VideoOutput` (its `TextureView`, its
`Surface`, and whether it has shown a frame yet) with `engine.newOutput()`. While its `isActive` is true, it is
**activated**; when it leaves the screen, it is **deactivated**.

**Routing (`ExoVideoEngine.route()`):**
1. The **wanted** output is the last activated active output.
2. When the wanted output changes, it is given a **cover**: a bitmap of the frame the current output shows
   (`TextureView.getBitmap()`). It draws that until its own first new frame arrives (`onFrame`), so the switch never
   flashes black or shows an old frame.
3. **Make before break:** the player keeps drawing into the old output until the new one's surface is ready.
4. Then `player.setVideoSurface(newSurface)`. The decoder keeps running: nothing is prepared again, the video
   doesn't stop.

**Generations.** Every `load()` bumps `generation` and forgets all outputs. An output made before a load belongs to
the old video and is never used again. A surface composed after the load gets a fresh output.

**Cropping.** The `TextureView` is laid out at the video's own shape, just big enough to cover the card, and the card
clips it (`cropToFill`). So the picture is never stretched, and `getBitmap()` returns an unstretched frame.

---

## 9. Threads: what runs where, and why

| Code | Thread | Blocking? |
|---|---|---|
| View model, `VideoEngine` calls, Compose | Main | Never |
| Nearby callbacks (`onPayloadReceived`, …) | Main (Nearby calls back there) | Must not block |
| Sending a `RangeRequest` | Main (`withContext(Dispatchers.Main.immediate)`) | No |
| `serve`: opening the file | `Dispatchers.IO` | Yes, briefly |
| `DrainedPiece.drain` | `Dispatchers.IO` | Yes: waits for network bytes |
| `RemoteDataSource.open` / `read`, `PiecedStream.read`, `DrainedPiece.read` | ExoPlayer's loader thread | Yes, by design |
| `LanFileServer` accept loop and each request | Its own threads | Yes: socket I/O |

**Why `runBlocking` in `RemoteDataSource.open` and `PiecedStream.read`?** Media3's `DataSource` is a Java interface
whose methods must **block** until bytes are there; we can't make them `suspend`. Our piece logic is written with
coroutines (`openPiece` is `suspend`, pieces ahead are `Deferred`). `runBlocking` is the standard bridge from blocking
code to suspending code. It is only harmful on the main thread (freezes the UI) or inside a coroutine (starves its
dispatcher). Here it runs on ExoPlayer's loader thread, which exists to block. When ExoPlayer cancels a load, it
interrupts that thread, and `runBlocking` turns the interrupt into an exception, which is what ExoPlayer expects.

**Why `Dispatchers.IO` for draining?** `InputStream.read` on a Nearby stream blocks while waiting for bytes. A
coroutine doesn't make that non-blocking, but `Dispatchers.IO` is a thread pool made for blocking I/O: threads are
reused instead of created per piece. (Until `ec2a196` each piece started a new thread.) Stopping a drain still means
closing its input stream; cancelling a coroutine does not interrupt a blocked `read`.

**Why a `ReentrantLock` and `Condition` in `DrainedPiece`?** The reader (loader thread) must block until the drain
(IO thread) has written more bytes. A lock with a condition is the classic way for one thread to wait for another.

---

## 10. What the phones taught us

None of this is in Nearby's documentation. All of it came from running on a Redmi Note 10 Pro and a Redmi Note 8
(2026-10-25) and reading the Play services (GMS) log.

| # | What happened | Cause | Fix |
|---|---|---|---|
| 1 | After a few seconds the whole link froze: card moves stopped, then "Couldn't send". GMS log: `Write operation on WIFI_DIRECT … is timeout`, then `BROKEN_PIPE` | Nearby keeps only about 10 chunks of an incoming stream for the app (`BlockingPayloadChunkQueue with size 10`). When ExoPlayer's buffer was full it stopped reading, so Nearby's reader blocked, and **every** payload on the connection stalled | Drain every stream into a file the moment it arrives (`DrainedPiece`) |
| 2 | The link froze for about 35 s after the player jumped. GMS log: `short-circuiting Payload … failed to detach the next chunk` | We cancelled the old stream (`cancelPayload`) while it was still sending | Never cancel: fixed-length 1 MB pieces that end by themselves; unwanted pieces are drained and dropped |
| 3 | Some pieces were never matched to their request | The STREAM can arrive before the `RangeOpened` frame sent ahead of it (callbacks on several threads) | `early` / `answers` maps handle both orders |
| 4 | Video tried to stream over a link that couldn't carry it | Quality `MEDIUM` is classic Bluetooth, not Wi-Fi | `isFast` = quality `HIGH` only |
| 5 | Card moves paused 300–440 ms while pieces flowed | One connection, no priorities: moves wait behind pieces | Small pieces; still visible. Separate fast path is future work |
| 6 | First frame took about 5 s | One piece in flight at a time (~25 Mbit/s), and a badly interleaved test file needing 13 MB before its first frame | 3 pieces ahead: first frame in 1.8 s |
| 7 | Over the home Wi-Fi, one load took 25 s | One radio hopping between the home network and Wi-Fi Direct; the router carries every byte twice | Home Wi-Fi only while Nearby is still on Bluetooth |
| 8 | After the drop, pieces took 1.7–3.3 s each | The full-file `FILE` transfer shares the connection and takes most of it | Not fixed yet: LH-4 (one transfer) |
| 9 | A 4.7 s frozen frame after the drop | The follower learnt its jump lead from the first load (which is much slower than a jump) | Learn the lead only from jumps |
| 10 | About 15 s after the file arrived, the video failed with `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` | The sender removed the sent item and released its read permission; the receiver was still reading from it, and every request was refused | The receiver plays from its own copy once it has arrived (`playLocalCopy`) |
| 11 | A 1.25 s frozen frame while the card was in the air | The follower started late, could only speed up to 1.25×, jumped, overshot, and held (see 7.6) | Retuned bands: more speed, fewer jumps |

---

## 11. Every number you can tune

| Constant | Value | File | What it does | Raising it… / Lowering it… |
|---|---|---|---|---|
| `PieceBytes` | 1 MB | `NearbyMediaChannel.kt` | Size of one requested piece | Fewer round trips, but longer blocks of the link / More round trips |
| `PiecesAhead` | 3 | `NearbyMediaChannel.kt` | Pieces in flight past the one being read | Faster start, but card moves wait more / Slower start |
| `OpenTimeoutMillis` | 10 s | `NearbyMediaChannel.kt` | Wait for a piece before giving up | |
| `MaxRangeBytes` | 64 MB | `Reconnect.kt` | Biggest range a request may ask for (validation) | |
| `ReachTimeoutMillis` | 600 ms | `LanFileServer.kt` | Wi-Fi reachability check | |
| `RequestTimeoutMillis` | 15 s | `LanFileServer.kt` | HTTP request header timeout | |
| Buffer: min / max | 5 s / 10 s | `ExoVideoEngine.newPlayer` | How far ahead the player keeps reading | More resilience, more link use / Less |
| Buffer: to start / after rebuffer | 0.75 s / 1.5 s | `ExoVideoEngine.newPlayer` | Video needed before playing | Fewer stalls, slower start / Faster start, more stalls |
| `PlaybackClockMillis` | 250 ms | `ShelfViewModel.kt` | How often the sender reports its position | Finer sync, more messages / Coarser |
| `ToleranceMs` | 25 ms | `PlaybackFollower.kt` | "Same frame" | |
| `SlowDownWithinMs` | 300 ms | `PlaybackFollower.kt` | Ahead by more than this: hold instead of slowing | |
| `SpeedUpWithinMs` | 1000 ms | `PlaybackFollower.kt` | Behind by more than this: seek instead of speeding | Fewer jumps, longer catch-up / More jumps |
| `MinSpeed` / `MaxSpeed` | 0.85 / 1.5 | `PlaybackFollower.kt` | Speed limits while following (muted) | |
| `CatchUpWindowMs` | 2000 ms | `PlaybackFollower.kt` | Time over which a gap is closed | |
| `StartLeadMs` | 2000 ms | `PlaybackFollower.kt` | How far ahead the first load aims | |
| `InitialLeadMs` | 1000 ms | `PlaybackFollower.kt` | How far ahead the first jump aims | |
| `MinLeadMs` / `MaxLeadMs` | 300 / 2000 ms | `PlaybackFollower.kt` | Limits of the learnt jump lead | |
| `DiscontinuityMs` | 300 ms | `PlaybackFollower.kt` | A report this far off resets the estimate | |
| `WindowMs` | 1500 ms | `PlaybackFollower.kt` | Reports older than this are ignored | |
| `FetchPiecesAhead` | 3 | `NearbyMediaChannel.kt` | Background pieces in flight during a fetch | Faster fetch, more competition with the player / Slower |
| `PlayerFirstMillis` | 2 s | `NearbyMediaChannel.kt` | Longest a fetch round waits for the player's pieces | Player first for longer / Fetch progresses on slow links |
| `FetchRetries` | 3 | `NearbyMediaChannel.kt` | Fruitless rounds before a fetch gives up (then the file is sent) | |
| `DigestTimeoutMillis` | 60 s | `NearbyMediaChannel.kt` | Wait for the other phone's SHA-256 | |
| `SpareBytes` | 64 MB | `NearbyMediaChannel.kt` | Free space left after a fetch | |
| `RouteWait` | 5 s | `LinkedSlidePeer.kt` | How long the sender waits for the receiver's `FileRoute` before sending the file | |

---

## 12. Debugging on two phones

### 12.1 Setup

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleDebug testAndroidHostTest       # build + all unit tests
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices                                         # serials of the connected phones
"$ADB" -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s <serial> logcat -G 16M                       # bigger log buffer, so a whole run fits
"$ADB" -s <serial> logcat -c                           # clear the log before a run
```

After a run (the phone can be unplugged during the run and plugged in afterwards):

```bash
"$ADB" -s <serial> logcat -d -v time -s AirVideo AirLink AirLan ExoPlayerImplInternal > build/device-logs/runN.txt
```

`build/device-logs/` holds the logs of earlier runs. **Note:** `adb -s <serial>` waits forever if that phone isn't
connected; check `adb devices` first.

### 12.2 What each log line means

**`AirVideo`** (`ExoVideoEngine`):

| Line | Meaning |
|---|---|
| `Load from this phone / the other phone over Wi-Fi / the other phone over Nearby at N ms` | A video was loaded, starting at N |
| `State buffering / ready / ended at N ms` | Player state changes. Load → first `ready` = time to first frame |
| `Speed 1.2 at N ms` | The follower changed the speed |
| `Seek from A to B ms` | A jump |
| `Pause at N` … `Play at N` | A hold (while following) or a user pause |
| `Failed: ERROR_CODE_…` + stack trace | The player gave up. `IO_NETWORK_CONNECTION_FAILED` from `RemoteDataSource.open` = pieces refused or not coming |

**`AirLink`** (`NearbyMediaChannel`, `NearbyPeerLink`):

| Line | Meaning |
|---|---|
| `Link quality 3 (3 is Wi-Fi): live video on` | Link upgraded to Wi-Fi; `2` = classic Bluetooth, off |
| `Piece of <id> at <offset> opened in N ms` | A piece arrived N ms after it was asked for. 100–500 ms is normal; seconds means the link is busy |
| `Piece of <id> at <offset>: no answer (asked: true)` | Timed out or refused |
| `Piece request N refused by the other phone` | The sender couldn't serve it (not shared, or couldn't open the file) |
| `Piece of <id> refused: not shared` (sender's log) | The sender got a request for a file it isn't sharing |
| `Piece N ended: FAILURE after B bytes` (sender's log) | A piece being sent broke |
| `Card messages paused N ms, then DragMove` | The card's message stream stalled (something else held the link) |
| `Sending X took N ms` | One message took long to send |
| `Wi-Fi path reachable / not reachable (N ms)` | Result of the home-Wi-Fi check |
| `Fetched <id> (S bytes, K already here) in N ms` | LH-4: the file was completed from pieces and its hash matched. K = bytes the player had already read |
| `Fetching <id>: gave up with B bytes missing` | The fetch failed; the file is sent the usual way |
| `Fetched <id> doesn't match the other phone's (digest …)` | Hash mismatch or no digest; the file is sent the usual way |
| `<id> is on this phone for good: read from here` | `settle`: new reads come from the kept copy |
| `<id> goes on from this phone's copy at <offset>` | An open player stream switched to the local copy |
| `Digest of <id> refused: …` (sender's log) | The sender couldn't hash the file |

**`AirLan`** (`LanFileServer`): `Serving on port N`, `Couldn't start serving`, and why the other phone's address
doesn't answer.

**The GMS (Play services) log**, for link-level trouble: search the full `logcat -d` for `NearbyConnections` and
`NearbyMediums`: `Write operation on WIFI_DIRECT … is timeout`, `MEDIUM_ERROR … BROKEN_PIPE`,
`BlockingPayloadChunkQueue`, `short-circuiting Payload`.

### 12.3 Reading a run: a checklist

1. **Link quality** before the lift: was it `3`? If not, live video went over home Wi-Fi or not at all.
2. **Time to first frame:** `Load …` → first `State ready`. About 1.8 s today on a Note 8.
3. **Sync while in the air:** after `ready`, count `Seek` and `Pause`/`Play` pairs. Ideally only `Speed` lines.
4. **Card smoothness:** `Card messages paused` lines while pieces flow.
5. **After the drop:** `Piece … opened in` times should stay short (LH-4: no `FILE` transfer competes). Then
   `Fetched … in N ms`.
6. **File arrival:** `… is on this phone for good`, then `… goes on from this phone's copy`. There should be **no**
   new `Load …` line (that would be the old reload; it still happens on the home-Wi-Fi path).
7. **Errors:** `Failed:` lines, and `refused` / `no answer` before them.

---

## 13. Tests

All run with `./gradlew testAndroidHostTest`.

| Test file | What it checks |
|---|---|
| `feature/shelf/domain/.../PlaybackFollowerTest.kt` | Position estimate (delays, least-delayed report, pauses, stalls), the bands (speed, hold, seek), speed limits, start and jump leads, learning the lead |
| `feature/shelf/presentation/.../ShelfViewModelLiveVideoTest.kt` | Offer on lift (playing / paused / not offered when no path), clock reports, choosing Nearby vs Wi-Fi vs nothing, muted start ahead, applying corrections, holds, pause, takeover with sound, cancel, remap to the received item, the `Delivery` chosen at the drop, no reload after a fetch, moving to the local copy on the Wi-Fi path, failure |
| `feature/shelf/presentation/.../ShelfViewModelVideoTest.kt` | Playing a video in its card on one phone |
| `core/transfer/data/.../SlideMessageCodecTest.kt` | `PlaybackOffer` / `PlaybackClock` round trips and validation |
| `core/transfer/nearby/.../RangeFrameTest.kt` | Encoding and decoding range and digest frames, rejecting malformed ones |
| `core/transfer/nearby/.../FileSpansTest.kt` | Missing parts, covered bytes and the assembly plan from kept pieces |
| `feature/shelf/data/.../LinkedSlidePeerTest.kt` | The `FileRoute` handshake on both sides: fetched, sent, fallback, cancelled, link lost, no answer |
| `core/transfer/nearby/.../HttpRangeTest.kt` | Parsing `Range` headers |
| `core/media/.../PlaybackErrorTest.kt` | ExoPlayer error codes → `DataError` |

Fakes: `FakeVideoEngine` (records loads, seeks, speed, volume), `FakeMediaChannel` (records shares and opens),
`FakePeer`, `FakeShelfRepository`.

Not covered by unit tests (needs two phones): `NearbyMediaChannel`, `LanFileServer`, `ExoVideoEngine`,
`RemoteDataSource`, `VideoSurface`.

---

## 14. Known limits and what's next

**Limits today:**
- **LH-4 is built but not yet tried on the phones.** Its numbers are estimates until a two-phone run.
- **The whole file still crosses the link once after the drop**, minus what was played. A long video means a long
  fetch; it no longer slows the player, but it does keep the link busy.
- **Card moves pause** (300–440 ms) while pieces flow: one Nearby connection, no priorities.
- **On the home-Wi-Fi path only,** the switch to the local copy still reloads the player at the same position (the
  card shows its thumbnail for a moment).
- **No "sender went silent" safety net:** if phone A disappears mid-slide, phone B's incoming card can stay frozen
  on screen (`docs/reference/freeze.png`).
- **No codec check:** a video phone B can't decode fails with an error instead of falling back to the still card.
- **No audio cross-fade** on the drop: the sound switches.
- **Sync is measured by eye and logs only**, not yet filmed at 240 fps.

**Next (see `docs/LIVE_VIDEO_HANDOFF.md`):**
1. Try LH-4 on the two phones and measure it (the report lists what to look for).
2. The safety net for a silent sender.
3. `PlaybackReady` / `PlaybackUnsupported` replies and a codec check.

---

## 15. Glossary

| Term | Meaning |
|---|---|
| **Sender / receiver** | The phone the card leaves / the phone it arrives on |
| **Lift** | Long-press that picks the card up |
| **Drop / commit** | Letting go past the edge: the card is handed over (`DragCommit`) |
| **Cancel** | Letting go before the edge: the card springs back (`DragCancel`) |
| **Halves** | The parts of the card drawn on each phone while it crosses the edge |
| **Follower / following** | The receiver keeping its video on the sender's frame while the card is in the air |
| **Takeover** | On the drop, the receiver becomes the only player, with sound |
| **Offer** | `PlaybackOffer`: "the card I just lifted is this video, at this position" |
| **Clock report** | `PlaybackClock`: "my video is at this position", 4 times a second |
| **Piece** | A 1 MB part of the file, requested with `RangeRequest`, delivered as a Nearby STREAM payload |
| **Drain** | Reading a stream payload to its end straight away, into a temp file |
| **Pieces ahead** | Pieces requested before the player needs them |
| **Endpoint** | Nearby's id for the other phone on the connection |
| **Payload** | One thing sent over Nearby: BYTES, FILE or STREAM |
| **Link quality** | Nearby's `BandwidthInfo.Quality`: 3 = Wi-Fi (HIGH) |
| **`moov` / index** | The part of an MP4 that says where each frame is; needed before playing |
| **Range** | A span of bytes of a file, from an offset |
| **Buffer** | Video the player has read ahead of what it shows |
| **Rebuffer** | The player ran out of data and waits (frozen frame) |
| **Seek / jump** | Moving the player to another position |
| **Hold** | Pausing the receiver on a frame until the sender gets there |
| **Lead** | How far ahead of the target a load or jump aims |
| **Round trip / one-way delay** | Time for a ping to go and come back / half of it |
| **Output / surface** | Where a player draws; each `VideoSurface` is one output |
| **Cover** | A still picture of the current frame shown while the picture moves to a new output |
| **Generation** | A counter bumped by each load, so old outputs never show the new video |
| **Make before break** | Keep drawing in the old place until the new place is ready |
| **Loader thread** | ExoPlayer's background thread that calls the data source and blocks on it |
