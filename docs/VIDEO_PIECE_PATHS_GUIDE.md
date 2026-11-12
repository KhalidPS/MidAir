# Video pieces: the Nearby path and the direct path

Written 12 Nov 2026. It describes two versions of the same code:

- the **Nearby path**, as it is on `main`;
- the **direct path**, as it is on branch `feature/2026-11-12/pieces-over-wifi-direct` (last commit `436306e`).

**Status: the direct path is built, its unit tests pass, and it was compared with `main` on two phones in three
runs. It is not merged.**

## How to read this document

This document is about one question: how do the bytes of a video travel from the phone that plays it (phone A) to
the phone that receives its card (phone B), while the card is in the air?

- **If you only want the result,** read Section 1. It puts the two paths side by side first, and then explains every
  row in plain words.
- **If the words in Section 1 are new to you,** read Section 2 (background) first, then come back to Section 1.
- **If you will change the code,** read Sections 3 to 5. Section 3 is the journey of one lift, the same on both
  paths. Section 4 is the Nearby path. Section 5 is the direct path.
- **If you test on phones,** read Section 7 (logs) and Section 8 (weak points).

Every class, function and constant named here exists in the code. Most of them are in `core/transfer/nearby`.
Every number comes from a phone run written down in `docs/WIFI_DIRECT_PIECES.md` or in the reports in
`docs/reports/fix/`. Where something was not tested, this document says so.

## Contents

1. [The comparison first](#1-the-comparison-first)
2. [Background a newcomer needs](#2-background-a-newcomer-needs)
3. [The journey of one lift, end to end](#3-the-journey-of-one-lift-end-to-end)
4. [The Nearby path in detail](#4-the-nearby-path-in-detail)
5. [The direct path in detail](#5-the-direct-path-in-detail)
6. [How the direct path got where it is](#6-how-the-direct-path-got-where-it-is)
7. [How to read the logs](#7-how-to-read-the-logs)
8. [Known weak points, and what was not tested](#8-known-weak-points-and-what-was-not-tested)
9. [Where to change what](#9-where-to-change-what)
10. [Glossary](#10-glossary)
11. [Related documents](#11-related-documents)

---

## 1. The comparison first

### 1.1 What was measured

**The run.** 12 Nov 2026. `main` at 17:32, then the direct path at 17:38, back to back, a few minutes apart.

- Phones: phone A is a M2101K6G (it plays the video and sends it). Phone B is a Redmi Note 8 (it receives the card).
  Both phones were on the Wi-Fi Direct group.
- Video: the same phone recording, "video 20", 567 MB.
- **A "first lift"** is a lift where B has to start the video afresh. To make one, the tester played another video on A
  for about a second before each lift, and then the big video again.

| Measure | `main` (Nearby path) | Direct path |
| --- | --- | --- |
| First frame on a first lift | 14 lifts: 1.6–3.1 s, middle 2.3 s | 8 lifts: 0.79–3.9 s, middle 1.3 s |
| First lifts under 1.5 s | 0 of 14 | 5 of 8 |
| First lifts that jumped | 0 of 14 | 1 of 8 (3.9 s, then a jump) |
| Second lifts | none in this run | 1, 0.48 s (see below for other runs) |
| A piece, typical time to open | 547 ms | 86 ms (896 pieces, none fell back to Nearby) |
| Card lag, typical per slide | 32–79 ms | 28–50 ms |
| Card lag, worst 1 in 20 moves per slide | 84–329 ms | 79–210 ms |
| Pauses of the card messages over 0.3 s | 5 in 15 slides | 5 in 10 slides |
| Whole file after a drop | 3.9 MB/s (14:27 run) | 6.6 MB/s (517 MB in 79 s) |

**Second lifts in other runs.** The direct path at 15:22: 6 second lifts, 0.38–0.53 s. `main` at 14:32: 7 second
lifts, 0.33–1.19 s, typical 0.46 s.

**Caveats. Read these before you quote the table.**

- One pair of phones, one afternoon, small numbers (14 and 8 first lifts).
- The spread on the direct path is wider. Its two slowest first lifts (3.1 s and 3.9 s) were as slow as the slowest
  on `main`, and one of them jumped.
- `main` was not dropped in this run. Its file speed (3.9 MB/s) comes from an earlier run on the same day, at 14:27.

### 1.2 Each row in plain words

- **First frame on a first lift.** The time from the moment B starts to load the video until B can show its first
  picture. During this time B's half of the card shows only a still picture. Shorter is better. The "middle" is the
  median: half of the lifts were faster, half were slower. On the direct path the middle lift is about 1 s faster.
- **First lifts under 1.5 s.** How often a first lift felt quick. None did on `main`; five of eight did on the direct
  path.
- **First lifts that jumped.** A **jump** is when B was ready too late: A's video had moved on too far, so B threw
  away what it had loaded and loaded again further ahead. The user sees a still picture, a stop, and a restart. This
  is the worst thing that can happen to the video during a slide. One of eight first lifts did this on the direct path.
- **Second lifts.** A second lift is the same card lifted again after a pull-back, while its video still plays on A.
  B kept what it read during the first lift, so it starts much faster. Both paths are at about half a second here.
  The direct path does not change this much, because the bytes are mostly on B already.
- **A piece, typical time to open.** B reads the video in **pieces** (parts of the file, usually 1 MB). This is the
  time from B asking for one piece until its first bytes arrive. "Typical" is the median. This is the row where the
  direct path changes the most: about six times faster.
- **Card lag, typical per slide.** While the card is in the air, A sends B the card's moves many times a second. Card
  lag is how much later than the quickest move each move arrived. It is measured per slide; the table gives the range
  of the per-slide medians. Lower is smoother. This row matters a lot: the smooth card **is** the product.
- **Card lag, worst 1 in 20 moves.** The same, but the 95th percentile: one move in twenty was this late or later.
  This shows the bad moments a median hides.
- **Pauses of the card messages over 0.3 s.** How often the stream of card messages stopped for more than 0.3 s. A
  pause shows as the card freezing on B for a moment. The count is the same (5), but the direct path had fewer slides
  (10 against 15), so a pause came a little more often per slide.
- **Whole file after a drop.** After the user drops the card on B, B takes in the rest of the file. This is the speed
  of that. The direct path is faster here, but `main` was measured in another run.

### 1.3 What the developer felt on the phones, and why

**The observation (on both builds).** When a first lift is a very quick slide to the edge, B's half of the card lags
behind A's half while the video plays. The lag is a few hundred milliseconds, up to about 1.5 s. Then B catches up.
With a normal, smooth slide there is no cut: both halves look like one card. If the video played about 3 s on A
before a quick slide, the slide is sometimes smooth and sometimes cut.

**The explanation.** This is a reading of the timings. The log does not record how fast the finger moved.

- On a first lift, B needs 0.8–3.9 s (direct path) or 1.6–3.1 s (`main`) before its first picture.
- A normal slide takes about that long to reach the edge. By then, B's picture is ready, and the two halves match.
- A quick slide reaches the edge first. B's half has to wait for its picture, and then catch up with A (see
  "catch up by speed" in Section 2.7).
- During the 3 s wait on A, B reads ahead only the video's header and index. A allows nothing more before the lift
  (Section 3, step 2). B does not read the pictures yet. So whether a quick slide is smooth depends on how fast the
  first pictures come at that moment.
- The direct path makes the gap shorter. It does not remove it.

**What would remove it (not built; it would work the same on both paths).**

- At the lift, A sends the frame it shows as a still image, and B shows that image until its own video starts.
- B reads ahead the first seconds of pictures around A's playing position, not only the index.

Both ideas are item 5 of `docs/COMING_IMPROVEMENTS.md`.

### 1.4 What each path costs a developer

**`main` (Nearby path).** There is one way for pieces to travel: Nearby. It is already tricky:

- every piece must be read to its end ("drained"), even a piece nobody wants any more;
- a piece is never cancelled, because cancelling can stall the whole Nearby connection;
- a piece's bytes can arrive before the small message that says which request they answer.

Section 4 explains each of these.

**The direct path.** It does not replace the Nearby path. Nearby stays as the fallback. The direct path adds a second
way, with our own TCP connections on the Wi-Fi Direct group:

- about 340 lines in a new file, `LanPieceLink.kt` (341 lines), and about 190 more lines changed in
  `NearbyMediaChannel.kt`, `NearbyPeerLink.kt`, `Reconnect.kt` and `LanMessageLink.kt`;
- a pool of open connections;
- a secret token that every connection must start with;
- retries when the phones change network;
- a choice made for every piece: over Wi-Fi first, over Nearby if Wi-Fi does not work;
- a priority mark on the card-message connection.

Everything stays inside `core/transfer/nearby`. The shelf screen and `ShelfViewModel` do not change. It has unit
tests: 11 in `LanPieceLinkTest.kt` (new file) and 2 new ones in `LanOfferFrameTest.kt` (which now has 6).

There are more ways for it to fail. Three fallbacks were **never tested on phones**:

1. a lift while the link is still on Bluetooth;
2. one phone on an older build;
3. Wi-Fi breaking in the middle of a piece.

> **Recommendation:** stay on the direct path, because it shortens exactly the gap users see (the first frame on a
> first lift). Then build the still frame at the lift (Section 1.3).

---

## 2. Background a newcomer needs

### 2.1 The two phones and what the user does

- **Phone A** plays the video first. It **shares** the file. In the code and logs the other phone is often called
  the **peer**.
- **Phone B** lies next to phone A. It **receives** the card, and plays the video too.
- The **shelf** is the app's main screen: a grid of **cards**, one card per file.
- **Lift:** a long press on a card. The card rises and follows the finger.
- **Slide:** the user drags the lifted card over the screen edge that faces phone B. Phone A draws the part of the
  card still on its screen. Phone B draws the part that has crossed: **B's half of the card**. Together they look
  like one card lying across both phones. When the card is a playing video, B's half plays the same video.
- **Pull-back:** the user drags the card back onto A and lets go there. Nothing is sent. In code this is a
  `DragCancel` message.
- **Drop:** the user lets go of the card past the edge. The card lands on B and the file is sent. In code this is a
  `DragCommit` message.
- **First lift:** a lift where B has nothing of the video's pictures yet. It has at most the video's index, read
  ahead (Section 2.5).
- **Second lift:** the same card lifted again after a pull-back, while its video still plays on A. B still has what
  it read the first time.

### 2.2 Nearby Connections and the Wi-Fi Direct group

- **Nearby Connections** is a library in Google Play services. It lets two phones find each other and talk without a
  router or the internet. The app's code for it is in `core/transfer/nearby`: `NearbyPeerLink` owns the connection,
  and `NearbyMediaChannel` reads files over it.
- **The link starts on Bluetooth.** Right after the phones connect, Nearby carries everything over Bluetooth, which
  is slow.
- **Then Nearby upgrades the link to a Wi-Fi Direct group.** A **Wi-Fi Direct group** is a small Wi-Fi network that
  the two phones make between themselves, with no router. One phone is the **group owner**: it creates the network,
  and the other phone joins it. Each phone gets an address (an IP address) on this network. On 11 Nov the upgrade
  came 1.7 to 12.6 s after pairing.
- **Link quality.** Nearby tells the app how good the link is, through `onBandwidthChanged`. `NearbyMediaChannel`
  keeps it in `quality`, and `isFast` is true only at `HIGH` (3, Wi-Fi). A video is read from the peer over Nearby
  only when the link is fast.
- **Payload.** Everything Nearby carries is a **payload**. There are three kinds:
  - **BYTES**: one small message (at most 32 KB). The app uses it for its own small messages, called **frames**
    (`LinkFrame` in `Reconnect.kt`).
  - **STREAM**: a stream of bytes that the receiver can read while it arrives. The Nearby path sends each piece of a
    video as one STREAM payload.
  - **FILE**: a whole file, usable only once it has all arrived. The app uses it to send a normal file.

### 2.3 Why one shared connection is a problem

Nearby sends every payload over **one connection, in order, with no priorities**. A small message sent after a lot of
video bytes waits until those bytes have gone out.

This matters because two very different things travel while a card is in the air:

- **the card messages** (`DragMove`, `PlaybackClock` and others): small, many per second, and they must arrive on
  time, or the card stutters on B;
- **the video's bytes**: large, and they come in bursts.

On `main` the card messages already have their own connection on the Wi-Fi Direct group (`LanMessageLink`, added on
11 Nov). Only the video's bytes still travel over Nearby. The direct path moves the video's bytes off Nearby too.
But both connections still go through the same Wi-Fi radio in each phone. That is why the direct path also had to
give the card messages a priority (Section 5.7).

### 2.4 TCP, sockets and the words around them

- **TCP** is the standard way two computers send a stream of bytes to each other over a network. TCP makes sure
  every byte arrives, in order.
- A **socket** is one end of a TCP connection, as a program sees it. In Kotlin on Android it is `java.net.Socket`.
- A **port** is a number (1 to 65,535) that says which program on a phone a connection is for. An address plus a port
  names one place to connect to.
- A **listener** is a program that waits for others to connect to it, on a port (`java.net.ServerSocket`). The other
  side **connects** to it.
- A **token** is a secret: here, 16 random bytes. A phone that listens gives its token only to the paired phone, over
  the encrypted Nearby link. A connection that does not start with the token is dropped. This stops any other device
  on the network from reading a file.
- A **round trip** is the time from sending a question until the answer starts to come back. Every request for a piece
  costs at least one round trip, whatever the size of the piece.

### 2.5 How an MP4 video file is laid out

Phone videos are usually **MP4** files. An MP4 file is a row of **boxes**. Each box starts with a short header (8 or
16 bytes) that gives its type (four letters) and its length, so a reader can skip from one box to the next.

```
 byte 0                                                                         end of file
 +------+----------------------------------------------------------+--------------------+
 | ftyp | mdat: the pictures and the sound, mixed together         | moov: the index    |
 +------+----------------------------------------------------------+--------------------+
  "this is an MP4"                                                   where every picture
                                                                     and sound is, and when
```

- **`ftyp`**: a few bytes at the start that say "this is an MP4".
- **`moov`**: the **index**. It says where in the file every picture and every piece of sound is, and when to show
  it. **A player cannot show anything before it has read the index.** A phone writes the index at the **end** of a
  recording, because it only knows the table once recording stops. Downloaded videos often have it at the start.
- **`mdat`**: the **pictures and sound** themselves.

To start playing at, say, 0:12, a player reads the start of the file, then the index (often at the end), then the
part of `mdat` around 0:12. Pictures and sound for the same moment are stored a little apart, so the player hops
between a few places in the file before it settles. It never needs the whole file.

The code that reads boxes is in `core/transfer/nearby/.../Mp4Boxes.kt`: `mp4BoxAt` reads one box header,
`mp4MediaSpans` lists where the pictures and sound are, and `openSpan` cuts a request short where pictures and
sound start.

### 2.6 ExoPlayer and its DataSource

- **ExoPlayer** (AndroidX Media3) is Android's standard video player library. The app wraps it in `ExoVideoEngine`
  (module `:core:media`).
- A player gets its bytes from a **DataSource**. ExoPlayer calls `open(position)` to start reading at a byte, then
  `read` many times, on its own loader thread. It expects `read` to wait until bytes are there.
- The app's own DataSource is `RemoteDataSource` (`core/media/.../RemoteDataSource.kt`). Its `open` calls
  `MediaChannel.open` on the other phone's file, and its `read` reads from the stream that returns.
- ExoPlayer reads ahead of what it shows. `ExoVideoEngine.newPlayer` sets it to start playing after 0.75 s of video
  and to keep 5 to 10 s ahead.
- When the player hops to another place in the file, it closes its DataSource and opens a new one at the new
  position. Each hop is a new `MediaChannel.open`.

### 2.7 Following a playing video

B must show the frame A shows **right now**. But B only learns where A's video **was** when a message left A, and
B's player needs time to start. So A reports its position four times a second (`PlaybackClock`, every
`PlaybackClockMillis` = 250 ms), and B runs a **follower**: `PlaybackFollower`, in
`feature/shelf/domain/.../PlaybackFollower.kt`. `ShelfViewModel` creates it in `followPeerVideo` and feeds it in
`onPeerClock`.

| B compared with A | What the follower does |
| --- | --- |
| Within 25 ms | nothing: a frame or two cannot be seen |
| Ahead by up to 300 ms | play a little slower (not below 0.85 times) |
| Ahead by more | **hold**: stay on one frame until A gets there |
| Behind by up to 1.5 s | **catch up by speed**: play faster (at most 1.5 times); B is muted, so no one hears it |
| Behind by more | **jump**: seek ahead and load again |

- **Aim ahead.** Loading takes time, so B never starts at the frame A shows now. It starts **ahead** of A, by about as
  long as the load will take, and holds for what is left over. A first lift aims 2 s ahead (`StartLeadMs`). A second
  lift aims 0.6 s ahead (`SecondLiftLeadMs`), but only if B's video had played before the pull-back.
- **Catch up.** If B's first frame comes later than the aim, B is behind. Up to 1.5 s behind, it plays faster until it
  is level. This is the lag the developer saw on a quick slide (Section 1.3).
- **Jump.** More than 1.5 s behind, B seeks ahead. This is the bad case in Section 1.
- **Card lag** is a different thing: how late the card's **moves** arrive, not the video. Section 1.2 defines it.

### 2.8 What B keeps, and why

B keeps every piece that arrived whole, in a file in its cache (`KeptPiece`, in the folder `live-ranges`). This is the
same on both paths. Kept pieces are used three ways:

- **Read ahead (prefetch).** Before any lift, B reads the video's header and index and keeps them. At the lift, the
  player finds them on B and does not wait for them.
- **Second lift.** After a pull-back, B keeps what its player read. A second lift reads it from B.
- **Fetch after a drop.** After the drop, B asks only for the parts it does not have yet, puts the whole file together
  from the kept pieces (`assemble`), and checks it.

The check is a **SHA-256 check**. SHA-256 turns a whole file into a 32-byte number (a **digest**). If even one byte
differs, the number differs. A computes the digest of its file; B computes it of the file it put together. If they
match, B has an exact copy.

---

## 3. The journey of one lift, end to end

This part is the same on both paths. Only step 6 ("pieces travel") is different, and Sections 4 and 5 describe it.

```
 Phone A (plays the video)                                  Phone B (lies next to it)
 =========================                                  =========================
 1. a video plays in its card
    hintPlaying(): shareIndex + PlaybackHint  ------------>  2. prefetch(): read header + index ahead,
    A serves only header + index      <-- pieces asked ---      keep them
 3. lift: DragStart, PlaybackOffer  ---------------------->  4. followPeerVideo(): PlaybackFollower,
    share(): the whole file is readable                         load muted, aimed ahead
                                                             5. ExoPlayer -> RemoteDataSource.open
                                                                -> MediaChannel.open -> PiecedStream
    serves pieces                     <-- pieces asked ---   6. pieces travel (Nearby or direct)
    PlaybackClock every 250 ms  ---------------------------->   follower: slower / hold / faster / jump
 7a. pull-back: DragCancel, back to header + index ------->  7b. keeps what it read (keptForRelift)
 8. drop: DragCommit  ------------------------------------>  9. sound on; FileRoute(FETCHING)
    serves the missing pieces, SHA-256  <-- asked --------   10. fetch(): fill in, assemble, check,
                                                                 keep the file, settle()
```

**1. A says which video plays.** `ShelfViewModel.hintPlaying()` calls `MediaChannel.shareIndex(id, uri, size)` and
sends `SlideMessage.PlaybackHint(id, size)` to B. It does this only once the link is fast (on Wi-Fi).

- `shareIndex` is the **rule before the lift**: B may read only the header and index, not the pictures and sound. In
  `NearbyMediaChannel.shareIndex`, A finds where the pictures and sound are (`mp4MediaSpans`) and stores them in
  `closedParts`. These parts stay closed until the lift.

**2. B reads the index ahead.** On a `PlaybackHint`, `ShelfViewModel` calls `MediaChannel.prefetch(id, size)`.
`NearbyMediaChannel.prefetch` walks the boxes from byte 0, reads the whole `moov` box wherever it is, and keeps the
pieces. It waits while a card is in the air or a player is waiting (`pieceAhead`).

- When A serves a request (`serve` on the Nearby path, `servePiece` on the direct path), it applies the rule with
  `openSpan`: a request is cut short where pictures and sound start, and a request that starts inside them is
  **refused** (log: `refused: its card isn't lifted`).

**3. The lift on A.** A sends `DragStart`, then `PlaybackOffer(id, size, position, isPlaying)`. `ShelfViewModel`
calls `MediaChannel.share` in `shareVideo`. `NearbyMediaChannel.share` removes the file from `closedParts`: now the
whole file is readable.

**4. B starts following.** On the `PlaybackOffer`, `ShelfViewModel.followPeerVideo` creates a `PlaybackFollower` and
loads the video into the player, muted, as `VideoSource.Remote`, aimed ahead (Section 2.7). The source's reader is
`media.open(id, position)`.

**5. The player opens the file.** ExoPlayer calls `RemoteDataSource.open`, which calls `NearbyMediaChannel.open`.
That function tries three places, in order:

1. `openLocal`: the whole file is already on B (after a drop). Read from there.
2. `keptPart`: this byte is in a kept piece (the index read ahead, or a piece kept from an earlier lift). Read from
   there, with no request.
3. Otherwise: ask the other phone for a first piece (`openPiece`), and wrap it in a `PiecedStream`.

**6. Pieces travel.** `PiecedStream` reads one piece after the other. Once half of the current piece has been read,
it asks for the next pieces (`fillAhead`), up to `PiecesAhead` = 3 on their way at once. A piece already kept is
taken from B instead. How one piece travels is the only thing that differs between the two paths.

**7. A pull-back.** A stops sharing the whole file (`unshare`), sends `DragCancel`, and goes back to the rule of
step 1 (`hintPlaying`: header and index only). B releases its
player. If A's video still plays, B keeps what it read (`keptForRelift`) and notes whether its video had played
(`playedBeforePullBack`). A second lift then starts from what B kept.

**8. The drop on A.** A sends `DragCommit`.

**9. B takes over.** `ShelfViewModel.takeOverPeerVideo` stops following, sets speed 1, and turns the sound on. B tells
A it will fetch the file (`FileRoute(FETCHING)`, sent in `LinkedSlidePeer.fetch`, `feature/shelf/data`).

**10. The fetch.** `NearbyMediaChannel.fetch`:

- asks A for the file's SHA-256 (`askDigest`), while it works;
- fills in the parts not kept yet (`fillIn`), `FetchPiecesAhead` = 3 pieces of 1 MB at a time. It waits while a
  player waits for a piece (`playerPieces`), and asks for no new piece while a card is in the air (`cardInAir`, set by
  `holdFetches`);
- puts the file together from the kept pieces (`assemble`), computing its SHA-256 on the way;
- compares the two digests. If they differ, it throws the copy away;
- logs `Fetched … in … ms`.

Then `LinkedSlidePeer.keep` stores the file and calls `MediaChannel.settle`. From then on, `open` reads from B's own
copy, also for a stream that is already open (`PiecedStream.goOnFromHere`).

---

## 4. The Nearby path in detail

This is how one piece travels on `main`. The code is in `NearbyMediaChannel.kt` and the frames in `Reconnect.kt`.

### 4.1 The frames

A **frame** is a small app message sent as a BYTES payload. It starts with the marker byte `0xFF` and a type number
(`LinkFrame.encode`, `decodeLinkFrame`).

| Frame | Type | Sent by | Meaning |
| --- | --- | --- | --- |
| `LinkFrame.RangeRequest(requestId, offset, length, fileId)` | 5 | B | "Send me `length` bytes of this file from byte `offset`." |
| `LinkFrame.RangeOpened(requestId, payloadId)` | 6 | A | "That request is on its way as the STREAM payload `payloadId`." |
| `LinkFrame.RangeRefused(requestId)` | 7 | A | "I won't send it": the file is not shared, or that part is closed. |

A request always has a length, so the stream **ends by itself**. This matters (Section 4.3).

### 4.2 One piece, step by step

```
 Phone B                                  Nearby connection (one, shared)              Phone A
 -------                                  -------------------------------              -------
 openPiece(): requests[id] = answer
 RangeRequest(id, offset, 1 MB, file) ------------ BYTES payload ------------------->  serve():
                                                                                        shared? rule?
                                                                                        openAt(uri, ...)
                                      <----------- BYTES payload -------------------  RangeOpened(id, p)
                                      <----------- STREAM payload p -----------------  the bytes
 onFrame(RangeOpened): answers[p] = id
 onStream(p): DrainedPiece starts writing to a file
   deliver(id, piece): answer.complete(piece)
 openPiece returns; the player reads from the piece's file
```

Three messages travel for every piece, all on the one shared connection.

### 4.3 The three tricky parts

**Every piece is drained.** Nearby keeps only a few chunks of a stream for the app. A stream the app stops reading
blocks **the whole connection**: every other payload behind it waits, including the frame that would answer the next
request. So every piece is copied into a file as fast as it arrives, by `DrainedPiece` (its `drain` loop runs on a
background thread). The player then reads from that file, at its own pace.

**A piece is never cancelled.** Cancelling a stream while it is still sending can also stall the whole connection
(seen on the phones). So a piece is never cancelled. If nobody wants it any more (the player hopped away, or the asker
gave up waiting), it still drains to its end and is only kept (`deliver`: "Not cancelled … it drains, and is only
kept").

**A stream can arrive before its frame.** Nearby calls the app back on more than one thread, so the STREAM payload can
arrive before the `RangeOpened` frame that names it. `onStream` handles both orders:

- if `answers` already knows which request the payload answers, it delivers the piece at once;
- otherwise it puts the piece in the `early` map, where it drains meanwhile. When `RangeOpened` comes, `onFrame` takes
  it from `early` and delivers it. A piece nobody claims is dropped after `OpenTimeoutMillis * 2` (20 s).

### 4.4 Sizes and priorities

| Constant or field | Value | What it does |
| --- | --- | --- |
| `PieceBytes` | 1 MB | The size of a piece. About half a second of a phone's video. |
| `PiecesAhead` | 3 | Pieces on their way at once, past the one being read. More start a video faster but hold up the card's moves. |
| (half a piece) | 512 KB | `PiecedStream` asks for the pieces ahead once half of the current one is read. A player that only glances at a place does not make it fetch more. |
| `playerPieces` | a counter | Pieces asked for by a player that have not all arrived. A fetch and a read-ahead wait while it is above 0, for at most `PlayerFirstMillis` = 2 s. |
| `cardInAir` | true / false | Set by `holdFetches`. While a card is in the air, a fetch asks for no new pieces, for at most `CardHoldMaxMillis` = 15 s. |
| `FetchPiecesAhead` | 3 | Pieces a fetch asks for at once. It waits for all three before the next round. |
| `FetchRetries` | 3 | Rounds in a row that may bring nothing new before a fetch gives up. |

### 4.5 Timeouts

- `OpenTimeoutMillis` = 10 s: how long A has to start sending a piece. After that B gives up (`no answer`). The piece
  may still arrive later; it is then kept, but nobody reads it.
- `DigestTimeoutMillis` = 60 s: how long A has to compute the SHA-256 of the whole file.

### 4.6 What it costs

- Three messages per piece, all on the one shared connection.
- A piece took **0.1–1.1 s** to start coming on 11 Nov, and **0.2–1.5 s** on 12 Nov. In the runs of 12 Nov the
  typical time on `main` was 520–622 ms (Sections 1 and 6).
- A first lift needs many pieces, mostly one after the other. So the round trips add up, and a first lift took
  1.6–3.1 s in the confirming run.

---

## 5. The direct path in detail

### 5.1 What changes, and what stays

**Only the way one piece travels changes.** A piece goes over a TCP connection of our own on the Wi-Fi Direct group,
and that connection is already open when the piece is asked for. Nearby stays as the fallback.

What stays exactly the same:

- B drains every piece into a file and keeps it (`DrainedPiece`, `KeptPiece`).
- B reads the index ahead before the lift, and keeps what it read on a pull-back.
- Before a card is lifted, A lets B read only the header and index of a playing video.
- After the drop, the rest of the file is fetched in pieces, put together and checked against A's SHA-256.
- A fetch still waits while a card is in the air, and lets the player's pieces go first.

The changed files, all in `core/transfer/nearby`:

| File | What changed |
| --- | --- |
| `LanPieceLink.kt` (new, 341 lines) | The listener, the pool of open connections, one request and its answer. |
| `Reconnect.kt` | New frame `LinkFrame.PieceOffer` (type 11); `decodeOffer` shared with `LanOffer`. |
| `NearbyMediaChannel.kt` | `openPiece` asks over Wi-Fi first, then over Nearby. `servePiece` answers with the same rule as `serve`. `FirstWifiPieceBytes`. `PieceOrigin.length`. |
| `NearbyPeerLink.kt` | Listens for pieces where it listens for card messages, sends both offers, connects with retries. |
| `LanMessageLink.kt` | `CardTrafficClass` on the card-message socket. |

### 5.2 The offer: how each phone learns where the other listens

**The frame.** `LinkFrame.PieceOffer(address, port, token)`, type 11. It has the same shape as `LinkFrame.LanOffer`
(type 10, the card-message connection's offer):

```
 0xFF | 11 | port (8 bytes, big-endian) | token (16 bytes) | address (text, an IPv4 address like 192.168.49.1)
```

`decodeOffer` reads both kinds. It accepts only a port from 1 to 65,535 and a dotted IPv4 address, never a name to
look up. A build that does not know type 11 returns null for it and ignores it, so an older phone simply goes on
asking over Nearby.

**Sending the offers.** `NearbyPeerLink.offerLan(endpointId)` runs when the link starts to count, and again every
time the link's speed changes (`onBandwidthChanged`). It:

1. finds this phone's address: on the Wi-Fi Direct group (`directAddress`), or else on a shared Wi-Fi network
   (`wifiAddress`);
2. does nothing if it already listens on that address;
3. otherwise starts both listeners there, `lan.listen` (card messages) and `media.listenForPieces` (pieces), and sends
   both offers over Nearby;
4. then retries the other phone's offers that are not connected yet (Section 5.3).

```kotlin
address?.let { listOfNotNull<LinkFrame>(lan.listen(it), media.listenForPieces(it)) }
```

Each phone listens and each phone connects. So either phone can be the one that shares a video.

**Receiving an offer.** In `NearbyPeerLink.onFrame`, a `PieceOffer` is used only if the link is confirmed or proven
(`shake.isConfirmed || shake.isProven`) and the address is on the local network (`isLocalAddress`). Then it calls
`media.connectPieces(offer)` through `connectWithRetries`.

### 5.3 Connecting with retries, and why

```kotlin
private val OfferRetryWaitsMillis = listOf(0L, 700L, 1_500L, 3_000L, 6_000L, 10_000L)
```

`connectWithRetries` tries to connect now, and if that fails, again after each wait in this list (about 21 s in all).
It stops when it connects, when the link goes, or when a newer offer has arrived (`isLatest`).

**Why.** The first phone run (14:08) found that B never used the new path: all 434 of B's pieces went over Nearby.
The group owner (A) sends its offers about 0.3 s **before** B has joined the group, and sends them only once. B's
connect failed, and was never tried again. The same was true, before this branch, for B's card messages to A. On
`main`, a `LanOffer` is still tried only once (`scope.launch(Dispatchers.IO) { lan.connect(frame) }`). The fix is
commit `9844f77`.

**Retry when the own address changes.** Round two (`291989c`) added one more case. When `offerLan` finds that this
phone is somewhere new (for example, it has just joined the Wi-Fi Direct group), it tries again the other phone's
offers that are not up yet (`peerLanOffer`, `peerPieceOffer`). A listener that did not answer from the old address may
answer from the new one.

In the confirming run, B's first try failed as at 14:08, and the retry 0.7 s later connected both ways.

### 5.4 `LanPieceLink`: the connections

`LanPieceLink` is created once inside `NearbyMediaChannel` (the field `pieces`). It has two halves.

**The listening half (phone A, the one that shares).**

- `listen(address)` closes any listener from before, opens a `ServerSocket` on that address with a free port, makes a
  new 16-byte random token (`SecureRandom`), and returns the `PieceOffer`. Log: `Listening for piece requests on …`.
- `accept` waits for connections. Each connection is answered on its own background coroutine by `answer`.
- `answer` first reads 16 bytes and compares them with the token (`MessageDigest.isEqual`, which takes the same time
  whatever the bytes). A wrong token closes the connection. Log: `Dropped a piece connection from …: not the paired
  phone`. The client has `HelloTimeoutMillis` = 3 s to send the token.
- Then it answers requests one after the other, for as long as the connection is open.

**The asking half (phone B, the one that reads).**

- `connect(offer)` closes old connections, opens one, and if that works opens more, up to `WarmConnections` = 4. A
  lifted video asks for about that many pieces at once, so none of them waits for a connection to be set up. Log:
  `Pieces go over Wi-Fi to …:… (N connections open)`. From now on `isUp` is true.
- Connections that nothing is asked on wait in the **idle pool** (`idle`). At most `MaxIdleConnections` = 8 are kept.
  If more are needed at once, `open` opens a new one (`connectTo`).
- Every new connection sends the token first, with `tcpNoDelay` on (small writes go out at once).

**One request and its answer (the wire format).**

```
 B -> A:  offset (8 bytes) | length (8 bytes) | file id (Java "UTF" string: 2-byte length, then the text)
 A -> B:  how many bytes come (8 bytes)                -1 means refused; nothing follows
          then exactly that many bytes
```

- A checks the request: offset not negative, length from 1 byte to `MaxRangeBytes` (64 MB), a file id of reasonable
  length. A bad request closes the connection.
- A sends fewer bytes than asked at the end of the file, or where the rule cuts the request short. The length goes
  first, so B always knows where the piece ends.
- If A's file ends before the promised length, A closes the connection rather than send a short piece. B must not
  take the next answer for the rest of this piece.

**What `open` returns: `PieceAnswer`.**

| Answer | Meaning | What B does |
| --- | --- | --- |
| `PieceAnswer.Coming(input, length)` | the piece is on its way | drains it like any piece |
| `PieceAnswer.Refused` | A will not send it (not shared, or the part is closed) | gives that piece up. It does **not** ask over Nearby: A applies the same rule there. |
| `PieceAnswer.Broken` | this way does not work (any more) | asks the same piece over Nearby |

`open` first takes an idle connection. If it fails (it may have died while idle, for example because A's listener
moved), it tries once more on a new connection. If that fails too, it logs `Pieces over Wi-Fi broke: back on Nearby`,
sets `isUp` to false, closes the pool and returns `Broken`. A later offer from A can bring the path back.

**`PieceInput` gives the connection back.** The stream in a `Coming` answer is a `PieceInput`. It ends by itself after
`length` bytes. At that moment it puts the connection back in the idle pool (`release`), ready for the next piece.

**Closing a piece early closes its connection.** If a `PieceInput` is closed before its end, it closes the whole
connection: the rest of the piece stops coming, and the connection is not reused. Unlike Nearby, this is safe here,
because the connection carries only this one piece. (Note: `NearbyMediaChannel` still drains every piece to its end
in `DrainedPiece`, as on the Nearby path, so in normal use a piece is read whole. Early closing happens on errors.)

**The served-bytes count.** After a piece has gone out whole, `answer` calls `onServed`, which adds to `served`. This
is how A knows how much of its file B has taken (`servedBytes`).

### 5.5 `servePiece`: the same rule on A

`NearbyMediaChannel.servePiece(fileId, offset, length)` is what A sends when asked over Wi-Fi. It applies the same
rule as `serve` on the Nearby path:

1. the file must be shared (`shared`), else refuse (`Piece of … refused: not shared`);
2. if the file is in `closedParts` (no lift yet), cut the request with `openSpan`, or refuse
   (`Piece of … at … refused: its card isn't lifted`);
3. open the file at that byte, and never promise more bytes than the file has (`descriptor.statSize`).

It runs on the connection's background thread, so it may block (it waits with `runBlocking` until `closedParts` is
known).

### 5.6 `openPiece`: over Wi-Fi first, Nearby if not

The choice is made for **every piece**, in `NearbyMediaChannel.openPiece`:

```kotlin
when (val overWifi = if (pieces.isUp) withContext(Dispatchers.IO) { pieces.open(fileId, offset, length) } else PieceAnswer.Broken) {
    is PieceAnswer.Coming -> { /* DrainedPiece, claim(PieceOrigin(fileId, offset, length)), log "… over Wi-Fi" */ }
    PieceAnswer.Refused -> { /* log "refused by the other phone", return null */ }
    PieceAnswer.Broken -> Unit  // go on below: RangeRequest over Nearby, as on main
}
```

- If the Wi-Fi way is not up (yet), the piece goes over Nearby, exactly as on `main`.
- Player pieces, read-ahead pieces (`prefetch`) and fetch pieces (`fetch`) all go through `openPiece`, so all of them
  use the Wi-Fi way when it is up.
- A piece that came over Wi-Fi is drained by the same `DrainedPiece` class, into a file named `piece-w….part`.

### 5.7 The small first piece, and how a short piece is read

**`FirstWifiPieceBytes` = 256 KB.** When the Wi-Fi way is up, the **first** piece of each `NearbyMediaChannel.open`
is 256 KB, not 1 MB. Over Nearby it stays 1 MB.

- Why: a player starting a video looks at several places before it settles. In round one it read 12 to 13 pieces
  (up to 13 MB) before its first frame. Every piece is sent whole, even one the player only glanced at. A small first
  piece makes each glance cost a quarter of the bytes.
- Why only over Wi-Fi: over Nearby a piece's round trip costs more than its bytes, so a smaller piece would only add
  round trips.
- With a 256 KB first piece, `PiecedStream` asks for the pieces ahead after 128 KB (`fillAfter`, half the first
  piece). From the second piece on, it is 1 MB pieces and half a piece again.

**`PieceOrigin.length` and `isLast`.** `PieceOrigin` now remembers how many bytes were asked for. `DrainedPiece.isLast`
says the piece is the file's last when fewer bytes came than were asked for:

```kotlin
override val isLast: Boolean get() = lock.withLock { written < (origin?.length ?: PieceBytes) }
```

On `main` it compared with `PieceBytes`. That would make a whole 256 KB first piece look like the end of the file.

### 5.8 Keeping the card messages first

Round one showed that the card got worse with the direct path (Section 6). The likely cause, not proven: the video's
bytes now reached the radio sooner and in larger bursts, and the card messages waited behind them in the same phone's
radio. Round two (`291989c`) added two things:

- **`CardTrafficClass` = 0xB8** (in `LanMessageLink.kt`), set with `socket.trafficClass` on the card-message
  connection. 0xB8 means "expedited forwarding". Wi-Fi can send such packets from its **voice queue**, ahead of other
  traffic waiting in the same phone. Whether a phone's Wi-Fi driver honours it was not checked.
- **`PieceSendBufferBytes` = 128 KB** (in `LanPieceLink.kt`), set with `sendBufferSize` on each piece connection on
  A. At most this much of a piece waits in the phone to be sent. What waits there would also wait in front of the
  card messages at the radio.

### 5.9 Timeouts on the direct path

| Constant | Value | Meaning |
| --- | --- | --- |
| `ConnectTimeoutMillis` | 1.5 s | Connecting to the other phone's listener; longer counts as "not there". |
| `HelloTimeoutMillis` | 3 s | A new connection has this long to send the token. |
| `StallTimeoutMillis` | 10 s | A piece (or its length) that stops coming for this long counts as broken. |

### 5.10 One piece on the direct path

```
 Phone B                                  own TCP connection (one of 4, open already)    Phone A
 -------                                  -------------------------------------------    -------
 openPiece(): pieces.isUp
 LanPieceLink.open(): take an idle connection
   offset | length | file id  ---------------------------------------------------->  answer():
                                                                                      servePiece():
                                                                                      shared? rule?
                                          <--------------------------------------  length (8 bytes)
 PieceAnswer.Coming(PieceInput)           <--------------------------------------  the bytes
 DrainedPiece writes them to a file
 claim(PieceOrigin(...)); the player reads from the file
 PieceInput reaches its end: the connection goes back to the idle pool
```

### 5.11 Before and after, for one piece

| Step | Nearby path (`main`) | Direct path |
| --- | --- | --- |
| B asks | a `RangeRequest` frame, as a Nearby payload | 3 fields on a TCP connection that is already open |
| A answers | a `RangeOpened` frame, then a STREAM payload | the length (8 bytes), then the bytes |
| Messages per piece | 3, on the one shared Nearby connection | 1 request and 1 answer, on a connection that carries only this piece |
| Shares the connection with | card messages that fell back to Nearby, files, every other piece | nothing (it shares only the radio) |
| Connection set-up | by Nearby, once | by us, once: 4 opened when the offer is accepted, kept open |
| First piece of a read | 1 MB | 256 KB |
| Stopping a piece early | never: it can stall the whole Nearby link | closes its connection; safe |
| No answer from A | after 10 s (`OpenTimeoutMillis`) B gives up; if the piece comes later, it drains and is kept, but nobody reads it | if the length does not come within 10 s (`StallTimeoutMillis`), B tries one new connection, then counts the way as broken and asks over Nearby |
| When the way fails | the piece is lost (`no answer`) | B asks the same piece over Nearby |
| Typical time to open (confirming run) | 547 ms | 86 ms |

### 5.12 Tests

- `LanPieceLinkTest.kt` (`core/transfer/nearby/src/androidHostTest`): 11 tests on the loopback network of the test
  machine. They cover a whole piece byte for byte, pieces reusing the connections opened at the start, pieces asked
  for at once, a short piece at the file's end, a refused piece followed by one that comes, a piece closed early, the
  served-bytes count, a connection without the token, no listener to connect to, a listener that is gone, and a
  listener that moved.
- `LanOfferFrameTest.kt` (`commonTest`): 2 new tests. A `PieceOffer` survives an encode and decode and is not taken
  for a `LanOffer`; an offer with a name instead of an address is dropped.
- No test covers `openPiece` falling back to Nearby inside `NearbyMediaChannel`, because that class needs Nearby and
  Android.

Run them with:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :core:transfer:nearby:testAndroidHostTest
```

---

## 6. How the direct path got where it is

The commits on the branch (`git log main..HEAD`), with the phone runs between them. All times are 12 Nov 2026.

| Time | Commit or run | What happened |
| --- | --- | --- |
| 14:04 | `69d31e7` feat | The first version: pieces over our own connections, Nearby as fallback. |
| 14:08 | run | B used Nearby only: 434 pieces, typical 622 ms. A read 44 pieces from B over Wi-Fi, typical 73 ms. The connect bug was found (Section 5.3). |
| 14:13 | `9844f77` fix | Retry the connect to an offered listener. |
| 14:15 | run | 1,189 pieces over Wi-Fi, typical 94 ms, none over Nearby, none broke. Files 4.9–5.5 MB/s. One first lift: 2.3 s. Second lifts 0.46–0.49 s. |
| 14:24 | `ab668ef` feat | A fetch uses 4 MB pieces over Wi-Fi. |
| 14:32 / 14:40 | round one, side by side | See below. Verdict: not ready. |
| 15:03 | `291989c` feat | Round two: traffic class, 128 KB send buffer, 256 KB first piece, retry on address change, fetch back to 1 MB. |
| 15:22 | run | See below. Verdict: promising. |
| 17:32 / 17:38 | confirming run | Section 1. Verdict: ready to merge. |

### Round one (14:32 `main`, 14:40 direct path)

The same 567 MB video, twelve lifts on each build.

- Pieces opened about five times sooner: 520 ms against 112 ms.
- **First lifts were not faster:** 2.3, 4.1 and 4.4 s on the direct path, against 1.9–3.6 s (typical 2.6 s) on `main`.
- 4 of 12 lifts jumped on the direct path (2 first lifts, 2 second lifts), against 1 of 12 on `main`.
- **The card got worse:** typical lag 52–79 ms against 28–47 ms on `main`; pauses over 0.3 s: 13 against 3.
- Files: 4.9 MB/s against 3.9 MB/s.

Why:

- **The wait before a first frame was the bytes, not the requests.** The player read 12 to 13 pieces (up to 13 MB)
  before its first frame, and the link carried about 3 MB/s while it did. Most of those pieces were only glanced at,
  but each was sent whole.
- **The video's bytes waited at the radio in front of the card messages.**
- **4 MB fetch pieces brought nothing:** 4.9 MB/s, the same as with 1 MB pieces at 14:15 (4.9–5.5 MB/s).
- The two slow second lifts came after a pull-back made while B's player was out of data. This is the refused-piece
  hole (Section 8), not something the branch added.

### Round two (15:22), commit `291989c`

| Measure | `main` (14:32) | Round one (14:40) | Round two (15:22) |
| --- | --- | --- | --- |
| A piece, typical time to open | 520 ms | 112 ms | 80 ms (777 pieces) |
| First lifts, first frame | 5: 1.9–3.6 s | 3: 2.3, 4.1, 4.4 s | 3: 0.94, 1.08, 2.50 s |
| Second lifts, first frame | 7: 0.33–1.19 s | 7: 0.35–0.60 s, and 2 slow | 6: 0.38–0.53 s |
| Lifts that jumped | 1 of 12 | 4 of 12 | 0 of 9 |
| Card lag, typical per slide | 28–47 ms | 52–79 ms | 40–55 ms |
| Card lag, worst 1 in 20 moves | 84–171 ms | 137–461 ms | 96–477 ms |
| Pauses over 0.3 s | 3 in 12 slides | 13 in 12 slides | 4 in 15 slides |
| The file after a drop | 3.9 MB/s | 4.9 MB/s | 6.5 MB/s |

Round two used the 676 MB video, not the 567 MB one, and ran 40 minutes after `main`. That is why the confirming run
(Section 1) was done: the same video, back to back.

### The earlier, parked experiment

On 11 Nov a different idea was tried: commit `314b6a7`, on branch `feature/2026-11-11/1747-video-over-wifi-direct`.
B's **player** read the video directly over HTTP from A, on the Wi-Fi Direct group (`LanFileServer`, `VideoSource.Url`).

- It was not faster on a first lift (1.6–3.4 s).
- A second lift became much slower (1.6–3.4 s, against 0.33–0.43 s on the Nearby path).
- The reason: the player went around everything B keeps (the index read ahead, the pieces kept on a pull-back), and
  opened a new connection for every range it read. Every lift became a fresh load.

Report: `docs/reports/fix/12-2026-11-11-video-over-wifi-direct-report.md`.

**Why the direct path is different.** It keeps everything B keeps, and the player reads exactly as before. Only the
way one piece travels changes. So second lifts stay fast, and first lifts gain from faster pieces.

The design document with every run table is `docs/WIFI_DIRECT_PIECES.md`.

---

## 7. How to read the logs

Read the logs of both phones with `adb logcat`. Filter on the tags `AirLan`, `AirLink`, `AirSlide` and `AirVideo`.
Times are each phone's own clock; the runs in this document used B's clock.

### 7.1 Which path a piece took

| Log line (tag) | Phone | Meaning |
| --- | --- | --- |
| `Listening for piece requests on <address>:<port>` (`AirLan`) | both | This phone listens for pieces and has sent its offer. |
| `Pieces go over Wi-Fi to <address>:<port> (N connections open)` (`AirLan`) | the reader | The direct path is up. |
| `The other phone's piece listener doesn't answer from here: pieces stay on Nearby` (`AirLan`) | the reader | One connect try failed. A retry may follow (Section 5.3). |
| `Pieces over Wi-Fi broke: back on Nearby` (`AirLan`) | the reader | The direct path went down. |
| `Dropped a piece connection from <address>: not the paired phone` (`AirLan`) | the sharer | A connection without the right token. |
| `Piece of <file> at <offset> opened in N ms over Wi-Fi` (`AirLink`) | the reader | One piece over the direct path. |
| `Piece of <file> at <offset> opened in N ms` (no "over Wi-Fi") (`AirLink`) | the reader | One piece over Nearby. |
| `Piece of <file> at <offset>: no answer (asked: …)` (`AirLink`) | the reader | A Nearby piece did not come within 10 s. |
| `Piece of <file> at <offset> refused by the other phone` (`AirLink`) | the reader | Refused over Wi-Fi. |
| `Request N refused by the other phone` (`AirLink`) | the reader | Refused over Nearby. |
| `Piece of <file> at <offset> refused: its card isn't lifted` (`AirLink`) | the sharer | The rule before the lift refused a request. |
| `Link quality N (3 is Wi-Fi): live video on/off` (`AirLink`) | both | The link's speed changed. |

To get "a piece, typical time to open", collect the `opened in N ms` numbers of a run and take their median.

### 7.2 Measuring a lift

| Log line (tag) | Phone | Meaning |
| --- | --- | --- |
| `Index of <file> fetched ahead in N ms` (`AirLink`) | B | The read-ahead found and kept the index. |
| `Index of <file> not found ahead (…)` (`AirLink`) | B | Not an MP4, or A stopped sharing it. |
| `<item> lifted on the peer, link quality N (3 is Wi-Fi)` (`AirSlide`) | B | A card was lifted on A. |
| `Load from the other phone in pieces at N ms` (`AirVideo`) | B | B's player starts loading the peer's video, aimed at N ms. Builds before 12 Nov wrote "over Nearby" here. |
| `State buffering at N ms` / `State ready at N ms` (`AirVideo`) | B | The player waits for data / can show a frame. |
| `Speed X at N ms` (`AirVideo`) | B | The follower is catching up or slowing down. |
| `Pause at N ms` / `Play at N ms` (`AirVideo`) | B | A hold starts or ends. |
| `Seek from X to Y ms` (`AirVideo`) | B | A seek; while following, a jump. |

- **Which path:** `Load from the other phone in pieces` means "read through `MediaChannel`". Whether the pieces go over
  Wi-Fi or over Nearby shows on the `AirLink` piece lines. (Until 12 Nov this line said "over Nearby" for both paths.)
- **First frame:** report 00 defines it as the time from B starting to load until it can show a frame. To measure it
  from the log, take the time from the `Load from` line to the first `State ready` line after it. (Report 00 does not
  say which log lines it used; this is the closest match in the code.)
- **A jump:** a `Seek from … to …` line after the first `State ready` of the lift, usually followed by
  `State buffering`.
- **First or second lift:** on a second lift the player reads mostly kept pieces, so few or no `Piece … opened` lines
  appear before `State ready`.

### 7.3 The card and the file

| Log line (tag) | Phone | Meaning |
| --- | --- | --- |
| `<item> landed, link quality N. Moves: …, late p50 X / p95 Y / max Z ms, …` (`AirSlide`) | B | One line per slide, at the drop. `withdrawn` instead of `landed` for a pull-back. |
| `Card messages paused N ms, then <message>` (`AirLink`) | B | The card messages stopped for more than 0.3 s (`SlowSendMillis` = 300 ms). |
| `Sending <message> took N ms` (`AirLink`) | A | A card message sent over Nearby took more than 0.3 s. |
| `Fetching <file>: held N ms for a card in the air` (`AirLink`) | B | The fetch waited for a card. |
| `Fetched <file> (S bytes, K already here) in N ms` (`AirLink`) | B | The whole file is on B and checked. Speed = (S − K) / N. |
| `Fetched <file> doesn't match the other phone's (…)` (`AirLink`) | B | The SHA-256 check failed. |

- In the `AirSlide` line, `late` is how much later than the slide's quickest move each move arrived. As far as this
  document can tell from the run tables, "card lag, typical" is its `p50` and "worst 1 in 20 moves" is its `p95`.

---

## 8. Known weak points, and what was not tested

### 8.1 Three fallbacks never tested on phones

The unit tests cover the Wi-Fi side of these. The phones did not.

1. **A lift while the link is still on Bluetooth.** Then `isFast` is false. B does not read the index ahead, and
   reads the video over a shared Wi-Fi network (`VideoSource.Url`) if there is one. Whether the piece way is up at
   that moment (it can also listen on a shared Wi-Fi address) and what it does then was not tried.
2. **One phone on an older build.** The older build ignores `PieceOffer` (type 11), so the newer phone should go on
   asking over Nearby. Not tried.
3. **Wi-Fi breaking in the middle of a piece.** The piece's `PieceInput` throws, and `DrainedPiece` ends as broken (not
   kept). The next piece should find the connections dead, log `Pieces over Wi-Fi broke: back on Nearby`, and go over
   Nearby. What the player does with the broken piece (it gets a read error) was not seen on phones.

Also not tested: a long video, a downloaded video (index at the start), and the phones off the home Wi-Fi.

### 8.2 The refused-piece hole after a pull-back

After a pull-back, B goes on reading ahead until A refuses a piece (`refused: its card isn't lifted`). The refused
piece is missing from what B keeps. A lift that starts exactly on that piece is slow and jumps.

- On `main`, 12 Nov 12:58: the lift took 2.3 s, then B jumped 3.8 s ahead and buffered 3.3 s more.
- On the direct path, round one: two second lifts took 2.9 s and 3.6 s and jumped.

It happens on both paths. No fix is chosen yet. Two ideas: B stops reading ahead at the pull-back, or B asks for the
refused piece first at the next lift (`docs/COMING_IMPROVEMENTS.md`, item 3).

### 8.3 Other open points

- **Two minutes without a link.** Before the 15:22 run, the phones did not link for about two minutes after the
  install (A was not searching). It did not happen on the four installs before. The cause is unknown. It may or may
  not have to do with this build.
- **Worst card moves in round two.** The worst 1 in 20 moves reached 96–477 ms in round two, against 84–171 ms on
  `main` at 14:32. In the confirming run the direct path was better (79–210 ms against 84–329 ms). Small numbers.
- **Which round-two change did what is not known.** The traffic class, the 128 KB send buffer, the 256 KB first piece,
  the retry and the 1 MB fetch pieces went in together. Whether the phones' Wi-Fi drivers honour the traffic class
  was not checked.
- **The first-lift gap is shorter, not gone** (Section 1.3).

---

## 9. Where to change what

| To change … | Look at … |
| --- | --- |
| The size of a piece, or how many are on their way | `PieceBytes`, `PiecesAhead` in `NearbyMediaChannel.kt` |
| The size of the first piece over Wi-Fi | `FirstWifiPieceBytes` in `NearbyMediaChannel.kt` |
| When pieces ahead are asked for | `PiecedStream.fillAhead`, `fillAfter` |
| Whether a piece goes over Wi-Fi or Nearby | `NearbyMediaChannel.openPiece` |
| What A may send before the lift | `shareIndex`, `closedParts`, `openSpan` (`Mp4Boxes.kt`), used by `serve` and `servePiece` |
| The read-ahead of the index | `NearbyMediaChannel.prefetch`, `headerAhead`, `readAhead` |
| The fetch after a drop | `NearbyMediaChannel.fetch`, `fillIn`, `assemble`, `FetchPiecesAhead` |
| How long a fetch waits for a player or a card | `PlayerFirstMillis`, `CardHoldMaxMillis` |
| The wire format of the direct path | `LanPieceLink.open` and `LanPieceLink.answer` (change both together) |
| The number of open connections | `WarmConnections`, `MaxIdleConnections` in `LanPieceLink.kt` |
| Timeouts of the direct path | `ConnectTimeoutMillis`, `HelloTimeoutMillis`, `StallTimeoutMillis` in `LanPieceLink.kt` |
| The offer frame | `LinkFrame.PieceOffer`, `encode`, `decodeOffer` in `Reconnect.kt` |
| When offers are sent and retried | `NearbyPeerLink.offerLan`, `connectWithRetries`, `OfferRetryWaitsMillis` |
| The priority of the card messages | `CardTrafficClass` in `LanMessageLink.kt`, `PieceSendBufferBytes` in `LanPieceLink.kt` |
| How far ahead B aims, and when it jumps | `PlaybackFollower` (`StartLeadMs`, `SecondLiftLeadMs`, `SpeedUpWithinMs`) |
| When B follows a video, and what it keeps on a pull-back | `ShelfViewModel.followPeerVideo`, `keptForRelift`, `playedBeforePullBack` |
| The player's buffer | `ExoVideoEngine.newPlayer` (`DefaultLoadControl`) |

---

## 10. Glossary

| Word | Meaning |
| --- | --- |
| Aim ahead | B starts or jumps to a point ahead of A's frame, by about as long as the load will take, and holds for what is left over. |
| B's half of the card | The part of the card that has crossed onto phone B during a slide. |
| Bluetooth | The slow radio link Nearby starts on, before the Wi-Fi Direct upgrade. |
| BYTES payload | A small Nearby message (at most 32 KB). Used for frames. |
| Card lag | How much later than a slide's quickest card move each move arrived on B. |
| Card messages | The small messages that say what the card does: `DragStart`, `DragMove`, `PlaybackClock` and others. |
| Catch up | B plays faster (up to 1.5 times) to close a gap of up to 1.5 s behind A. |
| DataSource | Where ExoPlayer gets its bytes. The app's is `RemoteDataSource`. |
| Digest | A short number computed from a whole file (here SHA-256, 32 bytes). |
| Direct path | Pieces over our own TCP connections on the Wi-Fi Direct group (this branch). |
| Drain | Read a piece to its end into a file as fast as it arrives (`DrainedPiece`). |
| Drop | The user lets go of the card past the edge; it lands on B (`DragCommit`). |
| ExoPlayer | Android's standard video player library (AndroidX Media3). Wrapped by `ExoVideoEngine`. |
| Fetch | After a drop, B takes in the rest of the file and checks it (`MediaChannel.fetch`). |
| First lift | A lift where B has nothing of the video's pictures yet. |
| Frame (link frame) | A small app message on the Nearby link (`LinkFrame`). Not a video frame. |
| Frame (video) | One picture of the video. |
| Group owner | The phone that created the Wi-Fi Direct group. |
| Index (`moov`) | The part of an MP4 that says where every picture and sound is. Needed before anything can play. |
| Jump | B is too far behind, so it seeks ahead and loads again. The user sees a stop and a restart. |
| Lift | A long press on a card; it rises and follows the finger. |
| Listener | A program that waits for connections on a port (`ServerSocket`). |
| MP4 header (`ftyp`) | The first box of an MP4: "this is an MP4". |
| Nearby Connections | A Google Play services library that links two phones without a router. |
| Nearby path | Pieces over Nearby, as STREAM payloads (`main`). |
| Payload | Anything Nearby carries: BYTES, STREAM or FILE. |
| Pictures and sound (`mdat`) | The part of an MP4 with the media itself. |
| Piece | A part of a file asked for in one request: usually 1 MB, 256 KB for the first one over Wi-Fi. |
| Port | A number that says which program on a phone a connection is for. |
| Prefetch (read ahead) | B reads a video's header and index before its card is lifted. |
| Pull-back | The user drags the card back onto A; nothing is sent (`DragCancel`). |
| Round trip | The time from sending a question until the answer starts to come back. |
| Second lift | The same card lifted again after a pull-back, while its video still plays on A. |
| SHA-256 check | B compares the digest of the file it put together with A's digest. |
| Socket | One end of a TCP connection, as a program sees it. |
| STREAM payload | A Nearby payload the receiver reads while it arrives. Must always be read to its end. |
| TCP | The standard way to send an ordered stream of bytes between two computers. |
| Token | A random 16-byte secret that every connection to a listener must start with. |
| Traffic class | A mark on a socket's packets. 0xB8 asks Wi-Fi to send them from its voice queue. |
| Wi-Fi Direct group | A small Wi-Fi network the two phones make between themselves, with no router. |

---

## 11. Related documents

- `docs/WIFI_DIRECT_PIECES.md` (this branch): the design document of the direct path, with every run table.
- `docs/LIVE_VIDEO_HISTORY.md`: how the sliding live video was built, improved and fixed, commit by commit, with a
  glossary. On branch `docs/2026-11-12/live-video-history` (commit `2efdefa`), not yet on `main` or this branch.
- `docs/COMING_IMPROVEMENTS.md`: what to work on next, in order. Item 3 is the refused-piece hole; item 5 is the
  first-lift ideas. On the same branch as the history.
- `docs/LIVE_VIDEO_GUIDE.md` (`main`): the earlier developer guide to the live video, as it was on 29 Oct.
- `docs/reports/fix/`:
  - `00-2026-11-11-benchmark-comparison.md`: every video slide of 11 Nov, by build, and how to read the tables.
  - `07-2026-11-11-video-index-prefetch-report.md`: read a playing video's index ahead of its card.
  - `08-2026-11-11-fetch-exact-index-report.md`: read ahead exactly the index, wherever it is.
  - `09-2026-11-11-index-only-before-lift-report.md`: A lets B read only the header and index before the lift.
  - `10-2026-11-11-keep-index-on-pull-back-report.md`: keep what was read when the card is pulled back.
  - `11-2026-11-11-catch-up-before-jumping-report.md`: catch up by speed up to 1.5 s behind, aim a jump 2 s ahead.
  - `12-2026-11-11-video-over-wifi-direct-report.md`: the parked experiment of the player reading over HTTP.
  - `13-2026-11-11-aim-closer-on-second-lift-report.md`: aim a second lift 0.6 s ahead.
  - `14-2026-11-11-aim-closer-only-after-playing-report.md`: only when the video had played before the pull-back.
