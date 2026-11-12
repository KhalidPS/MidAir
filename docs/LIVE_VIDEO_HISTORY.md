# Live video slide: how it was built, improved and fixed

This document tells the whole story of one feature: **a video that is playing on phone A keeps playing while its
card slides onto phone B, and goes on playing on phone B after the card is dropped there.**

It follows every commit from the plan of 24 Oct 2026 to the state of `main` on 12 Nov 2026, in order. For each change
it says what was seen on the phones, what the code did before, what the new code does, what was measured, and what
stayed weak. It is meant for a developer who is new to the project and was not there.

The detailed documents and reports still exist and are linked from each step. Section 9 lists all of them. You do
not need them to follow this document, but they hold the full tables, the logs and the exercises.

**How to read it:**

- Section 1 is a one-page summary: a table with every step.
- Section 2 explains the background words and ideas. Read it first if you have never worked with Nearby Connections,
  ExoPlayer or MP4 files. Every later section uses these words.
- Section 3 describes the app just before the feature.
- Section 4 is the history, phase by phase, one subsection per code commit.
- Section 5 covers two experiments that were built but not merged.
- Section 6 walks through a slide of a playing video as it works today, step by step, with today's timings.
- Section 7 lists what is still open, weak or untested.
- Section 8 is a glossary. Section 9 is an index of the related documents.

**About the numbers:** every number comes from a commit message, a document or report in the repository, or the
phone test notes of 12 Nov 2026. Most were measured on one pair of phones, with one or two videos, a few slides at a
time. Where something was not measured or a cause is not known, this document says so.

---

## Contents

1. [Summary](#1-summary)
2. [Background you need](#2-background-you-need)
3. [Before the feature (state at `0bd1533`)](#3-before-the-feature-state-at-0bd1533)
4. [The history, phase by phase](#4-the-history-phase-by-phase)
5. [Tried and parked](#5-tried-and-parked)
6. [Where things stand now](#6-where-things-stand-now)
7. [Still open, weak or untested](#7-still-open-weak-or-untested)
8. [Glossary](#8-glossary)
9. [Index of related documents](#9-index-of-related-documents)

---

## 1. Summary

### 1.1 The story in short

- **24 to 25 Oct: the first build.** A plan was written from a reference video of another app (drooop). The next day
  a first version worked on two phones. Phone B reads phone A's video file in 1 MB pieces over the Nearby link while
  it plays, and a "follower" keeps B's video on the same frame as A's. The phones taught several hard lessons about
  Nearby: an unread or cancelled stream freezes the whole connection. By the evening the delay before B played was
  about 5 to 7 s instead of about 14 s, and the first frame on B came after about 1.8 s instead of about 5 s.
- **29 Oct: one transfer.** After the drop, B no longer receives the whole file a second time. It builds the file from
  the pieces it already played and asks only for the rest.
- **1 Nov: a smoother card half.** Not about video alone: every card crossing the edge now moves evenly on B, at the
  right size, and keeps its speed at the let-go. This is the frame the video sits in.
- **6 Nov: several files, and the lag they caused.** Air gestures were ported, several files can be sent one after
  the other, and the lag this caused on the shared link was fought in several steps. Files are now pulled in pieces
  that pause while a card is in the air.
- **10 Nov: transfers outlive the screen.** Three correctness fixes for when the app is swiped away during a send.
- **11 Nov: the start-speed and card-lag day.** The card's messages got their own connection (card lag from 207 to
  610 ms down to 41 to 66 ms). B now reads the video's index before the card is lifted (first frame from 2.4 to 6.1 s
  down to 1.1 to 2.4 s). The index is read exactly, only the index is open before a lift, what was read is kept on a
  pull-back, B catches up by speed before it jumps, and a second lift aims closer (in step after 0.30 to 1.5 s).
- **12 Nov: phone test and merge.** The last fix was tested (its exact case could not be reproduced by hand),
  everything was merged into `main`, and two new weak points were found and left open.

### 1.2 Every step, in order

"B" is the receiving phone. "First frame" is the time from B starting to load the video until it shows a frame. "In
step" is the time until both halves of the card show the same moving video. "Card lag" is how far B's half of the
card trails the finger (median per slide). Docs-only commits are marked *(docs)*.

| Date | Commit | What changed | Why | Measured result |
|---|---|---|---|---|
| 24 Oct | `0bd1533` | *(docs)* The plan: `LIVE_VIDEO_HANDOFF.md` | Study the reference video, choose the stack, plan phases LH-0 to LH-5 | n/a |
| 25 Oct | `e01d5e8` | First build: video plays in the card; B reads A's file in 1 MB pieces over Nearby and follows A's frame | The feature itself | Works on two phones; first frame about 5 s (one piece at a time) |
| 25 Oct | `89c7a2c` | *(docs)* What the phones showed about Nearby | Record the five undocumented Nearby behaviours | n/a |
| 25 Oct | `ae56d42` | 3 pieces in flight; home Wi-Fi fallback (`LanFileServer`) while Nearby is on Bluetooth | One piece at a time left the link idle | Delay about 14 s → 5 to 7 s; first frame about 5 s → 1.8 s |
| 25 Oct | `ec2a196` | B plays from its own copy once the file has arrived | The video failed about 15 s after the drop | Failure gone |
| 25 Oct | `bca1818` | Follower retuned: start 2 s ahead, speed up to 1.5×, slow down instead of freezing | B started late, jumped, then froze 1.25 s | "A little better" (user); not measured |
| 25 Oct | `08c70f9` | *(docs)* Developer guide `LIVE_VIDEO_GUIDE.md`, checkpoint | Explain the feature from zero | n/a |
| 29 Oct | `b02c846` | One transfer: B builds the file from kept pieces, checks SHA-256, no reload | The full file after the drop starved the player's pieces (1.7 to 3.3 s each) | "Worked on the phones"; no numbers recorded |
| 29 Oct | `7a5a3cf` | *(docs)* Report and before/after guide for one transfer | | n/a |
| 1 Nov | `aa8b944` | *(docs)* Plan for a smoother card half | Any card crossing felt like a handover, not one object | n/a |
| 1 Nov | `d2b3caf` | C0: moves carry a time stamp; per-slide timing log; ruler screen | Measure before changing | n/a |
| 1 Nov | `08e5217` | *(docs)* How to run the card-motion measurements | | n/a |
| 1 Nov | `deb3dff` | C3: the peer's millimetres turned into this phone's, from the line-up swipe | Phones report their density slightly wrong | Not measured |
| 1 Nov | `8cdba51` | C1, C2, C4: even playback of moves, half the moves on Bluetooth, link dot, steady let-go | B's half moved in uneven steps and stopped at the edge | Tried once: "better"; no logs |
| 1 Nov | `68f391c` | *(docs)* What was built and how to test it | | n/a |
| 1 Nov | `5d40f2f` | *(docs)* The first try, and the run still to do | | n/a |
| 1 Nov | `4b9c2f3` | *(docs)* Report on the smoother card half | | n/a |
| 6 Nov | `23cb2ec` | Air gestures; a grabbed video travels like a slid one | Port from the Android-only copy | Not tried on the phones then |
| 6 Nov | `b31b41b` | Several files, one at a time, with progress, stop and notifications | Several videos at once made B crawl | Files queue; B still laggy |
| 6 Nov | `2451102` | Send only the newest card move while the link is busy | Moves piled up by the thousand behind a file | Still laggy; later found to hurt a playing-video slide |
| 6 Nov | `3be3def` | On Wi-Fi, files are pulled in 1 MB pieces that pause while a card is in the air | File bytes held the card's messages up for seconds | Works; slides stay smooth while files move |
| 6 Nov | `514b94d` | Moves made while the link is busy go together (`DragMoves`) | `2451102` threw away points B needs | Not tested then |
| 6 Nov | `9eb4e44` | *(docs)* `MULTI_FILE_LAG_FIXES.md`: every fix tried for the lag | | n/a |
| 10 Nov | `2e088ad` | Sent items stay hidden after the app is reopened | The list of sent items died with the screen | Correctness only; not measured on matched builds |
| 10 Nov | `faa08ca` | Files are received while the shelf screen is closed | A file sent to a swiped-away app was lost | Correctness only; not measured on matched builds |
| 10 Nov | `97c6009` | A sent file counts as done only when B says it kept it | A file could end up on neither phone | Correctness only; not measured on matched builds |
| 11 Nov | `bb02b53` | A video lifted on Bluetooth moves onto the pairing link once Wi-Fi Direct is up | It played 1.5 s per 10 to 30 s of buffering | Case never reproduced on the phones |
| 11 Nov | `4f5e4c2` | *(docs)* Report 04 | | n/a |
| 6/11 Nov | `b165af6` | Card messages over the shared Wi-Fi (side branch from 6 Nov, brought in on 11 Nov) | Base for `1c5437f` | See next row |
| 11 Nov | `1c5437f` | Card messages over their own TCP connection on the Wi-Fi Direct group | Moves waited behind queued video bytes | Card lag 207 to 610 ms → 41 to 66 ms |
| 11 Nov | `28d91c9` | *(docs)* Report 05 | | n/a |
| 11 Nov | `22acb9f` | A says which video plays; B reads its header and index ahead | Four round trips for the index at every start | First frame 2.4 to 6.1 s → 1.1 to 2.4 s |
| 11 Nov | `7ea8ffc` | *(docs)* Report 07 | | n/a |
| 11 Nov | `f714c6a` | *(docs)* Benchmark comparison; reports moved to `docs/reports/fix/` | | n/a |
| 11 Nov | `1850d4e` | `Mp4Boxes.kt`: read where an MP4 keeps its index and its pictures | Needed by the next two fixes | 20 unit tests |
| 11 Nov | `cc49273` | Read ahead exactly the index, wherever it is | A fixed "first 1 MB + last 4 MB" guess misses long or index-first videos | Read-ahead of video 2: 1.3 to 3.4 s → 1.05 s |
| 11 Nov | `94c5146` | Before a lift, B may read only the header and index | The read-ahead had opened the whole file | Works; one extra request at the lift (213 ms) |
| 11 Nov | `5c1e228` | Keep what was read when the card is pulled back | A second lift had to start without the index | Second lift first frame 0.4 s |
| 11 Nov | `94c829d` | Catch up by speed up to 1.5 s behind; a jump aims 2 s ahead | A 1.01 s gap made B jump twice (8.3 s to be in step) | 0 jumps in 12 loads; no double jump in 37 loads |
| 11 Nov | `4e5e30f` | *(docs)* Reports 06, 08 to 11, comparison updated | | n/a |
| 11 Nov | `1dc3c33` | Stop tracking IDE files under `core/.idea` | `cc49273` had added them by mistake | n/a |
| 11 Nov | `733dbb0` | A second lift aims 0.6 s ahead, not 2 s | Every second lift froze its first frame 1.3 to 1.6 s | No frozen frame in 18 loads; in step 0.30 to 1.5 s |
| 11 Nov | `25e0f99` | Aim closer only if the video had played on B before the pull-back | One second lift with nothing kept jumped | Unit test only; case not reproduced on 12 Nov |
| 11 Nov | `a15d168` | *(docs)* Reports 12 and 13, evening benchmarks | | n/a |
| 12 Nov | `c738f7b` | *(docs)* Report 14, with the 12 Nov phone test | | n/a |
| 12 Nov | `1a4db69` | *(docs)* Reports numbered in reading order; reports 01 to 03 | | n/a |
| 12 Nov | `4b51cdb` | *(docs)* `CLAUDE.md`: the main branch is called `main` | `master` was renamed | n/a |
| (parked) | `e85dbc4` | Learnt aim for the first load | Starts varied | Not proven; not merged |
| (parked) | `314b6a7` | The player reads the video over its own connection on the Wi-Fi Direct group | Expected a faster start | Not faster (1.6 to 3.4 s); not merged |

---

## 2. Background you need

### 2.1 The two phones and the words for what the user does

- **Phone A** is the **sender**: the phone the video plays on first. **Phone B** is the **receiver**: the phone lying
  next to it. In code and logs the other phone is often called the **peer**.
- The **shelf** is the app's main screen: a grid of **cards**, one per file.
- **Lift:** a long press on a card. The card rises and follows the finger. A copy of the card is drawn in an overlay;
  the grid slot is hidden.
- **Slide:** dragging a lifted card toward the screen edge that faces phone B. When it crosses the edge, phone A
  draws the part still on its screen, and phone B draws the part that has crossed. Together they look like one card
  lying across both phones. This document calls the part on phone B **B's half of the card**.
- **Drop**, **let-go** or **land:** the user lets go with the card past the edge. The card lands on B's shelf and the
  file is sent. In code this is the **commit** (`DragCommit`).
- **Pull back:** the user drags the card back onto A's screen and lets go there. Nothing is sent. In code this is a
  **cancel** (`DragCancel`) or "spring back".
- **First lift** and **second lift:** a first lift is a card lifted when B has nothing of its video yet (apart from
  the index read ahead, see 2.4). A second lift is the same card lifted again after a pull-back, while its video still
  plays on A.
- **Pairing** and **line-up:** before any of this, the two phones are paired once and lined up with one finger swipe
  across both screens. The line-up tells each phone which of its edges faces the other phone, the gap between the
  screens, and their offset. This existed before the feature.

### 2.2 The card's messages

While a card is lifted, phone A tells phone B what the card does, in small messages (`SlideMessage` in
`core/transfer/domain`):

| Message | When | Meaning |
|---|---|---|
| `DragStart` | At the lift | A card is lifted: its name, type, size, its size in millimetres, a small thumbnail |
| `DragMove` | About 60 times a second while dragging | Where the card is, in millimetres from the shared edge |
| `DragCommit` | At the drop | The card was let go past the edge: it lands on B |
| `DragCancel` | At a pull-back | The card went back to A |

Positions are in **millimetres**, not pixels, because the two phones have different screens. Each phone turns
millimetres into its own pixels. The video adds its own messages (Section 4 introduces each one): `PlaybackOffer`,
`PlaybackClock`, `FileRoute`, `PlaybackHint`, and the batched moves `DragMoves`.

### 2.3 The link between the phones

- **Nearby Connections** is a Google Play services library. It lets two phones find each other and talk without a
  router or the internet. The app's code for it is in `core/transfer/nearby` (`NearbyPeerLink`, and later
  `NearbyMediaChannel`).
- **The link starts on Bluetooth and is upgraded.** Right after the phones connect, the link runs over Bluetooth, which
  is slow (about 1 to 2 Mbit/s). A few seconds later Nearby moves it to **Wi-Fi Direct**: a small Wi-Fi network that
  the two phones make between themselves, with no router. This network is called the **Wi-Fi Direct group**. On
  11 Nov the upgrade came 1.7 to 12.6 s after pairing. Wi-Fi Direct carried about 150 to 220 Mbit/s when measured on
  25 Oct.
- **Link quality.** Nearby reports how good the link is through `onBandwidthChanged`: `LOW` (1), `MEDIUM` (2, classic
  Bluetooth) or `HIGH` (3, Wi-Fi). The app calls the link **fast** (`MediaChannel.isFast`) only at `HIGH`. Live video
  over Nearby is only possible then. Since 1 Nov a dot on the devices icon shows it: amber on Bluetooth, cyan on Wi-Fi.
- **Payloads.** Everything Nearby carries is a payload, of three kinds: `BYTES` (a small message, at most 32 KB; used
  for card messages and the link's own frames), `FILE` (a whole file, usable only once complete), and `STREAM` (a
  stream of bytes that can be read while it arrives).
- **One queue, no priorities.** All payloads share one connection, in order. A small card message sent after a large
  amount of file or video data waits until that data has gone out. This single fact is behind many of the problems
  in this history.
- **The shared Wi-Fi** (also "home Wi-Fi" or "the router path") is different from Wi-Fi Direct: it is the normal Wi-Fi
  network both phones may have joined at home. Data on it goes through the router. A phone has one Wi-Fi radio, so
  when it is on the shared Wi-Fi and on Wi-Fi Direct at the same time, the radio switches between the two.

### 2.4 How a video file is laid out

Phone videos are usually **MP4** files. An MP4 file is a row of **boxes**. Each box starts with a short header (8 or
16 bytes) that gives its type (four letters) and its length, so a reader can skip from one box to the next.

```
 byte 0                                                                         end of file
 ┌──────┬──────────────────────────────────────────────────────────┬────────────────────┐
 │ ftyp │ mdat: the pictures and the sound, mixed ("interleaved")  │ moov: the index    │
 └──────┴──────────────────────────────────────────────────────────┴────────────────────┘
   "this is an MP4"                                                  where every frame is,
                                                                    and when to show it
```

- **`ftyp`**: a few bytes at the start that say "this is an MP4".
- **`moov`**, the **index** (also called the header in some older documents): the table of contents. It says where
  in the file every video frame and every piece of sound is, and when each one should be shown. **A player cannot
  show anything until it has read the index.** Phones usually write it at the **end** of a recording, because they
  only know the table once recording stops. Downloaded videos often have it at the start.
- **`mdat`**: the media data, the frames and the sound.

To start playing at 0:12, a player reads the start of the file, then the index (often at the end), then the part of
`mdat` around 0:12. It never needs the whole file. It reads **byte ranges**: "bytes X to Y".

Two facts about the test videos matter later:

- The index grows with the length of the video. The main test video ("video 2", a 676 MB phone recording of about
  4.5 minutes, about 2.5 MB per second of video) has an index of about 1.2 MB, at the end, starting at byte
  675,051,444. Its `mdat` header is at byte 377,092.
- In the test recording the pictures and the sound are about 2 MB apart in the file, so the player hops between two
  places to start, one request each time.

### 2.5 The player and where it draws

- **ExoPlayer** (AndroidX Media3) is Android's standard video player library. The app wraps it in `ExoVideoEngine`
  behind a common interface, `VideoEngine` (module `:core:media`). The view model talks only to `VideoEngine`.
- A player gets its bytes from a **`DataSource`**. ExoPlayer calls `open`, then `read` many times, on its own loader
  thread, and expects `read` to wait until bytes are there. The app wrote its own, `RemoteDataSource`, which reads
  the other phone's file.
- ExoPlayer keeps a **buffer**: it reads ahead of what it shows. Today it starts playing once it has 0.75 s of video,
  and keeps 5 to 10 s ahead.
- **Speed and seek.** `setPlaybackSpeed(1.2f)` plays 20% faster. `seekTo(ms)` jumps; after a jump the player usually
  needs new bytes, so it waits ("buffers") for a moment.
- **TextureView.** The picture is drawn into a `TextureView`, not a `SurfaceView`, because only a `TextureView` moves,
  scales, clips and rounds its corners with the rest of the Compose UI. A sliding card needs that. The composable is
  `VideoSurface`.

### 2.6 Reading the other phone's file: requests and pieces

Phone B does not have the file. Its player reads phone A's file as it plays:

- B asks for a **piece**: 1 MB of the file from a given byte (a `RangeRequest` frame on the link).
- A opens its file at that byte and sends the piece back as a `STREAM` payload, with a `RangeOpened` frame that names
  it. If A cannot or may not serve it, it answers `RangeRefused`.
- B **drains** every piece into a temporary file the moment it arrives (`DrainedPiece`), and the player reads from
  that file. Section 4.1 explains why this matters so much.
- Each request costs a **round trip**: the time from asking until the bytes start coming. On 11 Nov that was 0.1 to
  0.25 s on the Wi-Fi Direct link; on 12 Nov, 0.2 to 1.5 s. Much of this history is about making fewer requests at
  the moment the user is watching.
- Pieces B has received whole can be **kept** on B and reused (from 29 Oct on).

### 2.7 Following a playing video

B must show the frame A shows **right now**, but:

- B only hears where A's video **was** when a message left A, and messages take time to arrive.
- B's player needs time to start, and time again after every jump.
- The two phones' clocks differ.

So A reports its position about four times a second (`PlaybackClock`), and B runs a **follower** (`PlaybackFollower`,
pure Kotlin in `:feature:shelf:domain`). The follower estimates where A is now, using only B's own clock: the reported
position plus the time since the report arrived plus half the measured round trip. It then compares B's own position
with that **target** and decides:

| B compared with A | What the follower does |
|---|---|
| Within 25 ms | Nothing: one or two frames cannot be seen |
| A little ahead | Play a little slower |
| Far ahead | **Hold**: stay on the frame until A gets there (the user sees a still picture) |
| A little behind | **Catch up by speed**: play faster (B is muted while it follows, so no one hears it) |
| Far behind | **Jump** (seek): drop what was loaded and load again further ahead |

Two more words:

- **Aim ahead** (also "lead"): loading takes time, so B never starts or jumps to where A is now. It starts or jumps to
  a point **ahead** of A, by about as long as the load will take, and then holds for whatever is left over. Being a
  little early costs a short hold; being late costs another jump.
- **Muted while following:** while the card is in the air, the sound stays on A. B's video is silent, which is why
  speed changes are safe. At the drop, B takes the sound.

### 2.8 The phones and the measurements

- **Phones.** In October: a Redmi Note 10 Pro and a Redmi Note 8. In the November reports phone A is given by its
  model number, M2101K6G, and phone B is the Redmi Note 8. The Note 8 shows 60 frames a second, the Note 10 Pro 120.
- **Logs.** Measurements come from log lines on the phones: `AirVideo` (the player: loads, states, speed, seeks),
  `AirLink` (pieces, link quality, fetches), `AirSlide` (one line per slide with how late the card's moves came),
  `AirLan` (the separate card connection). The developer guide (Section 12) explains each line.
- **How to read the November tables.** "First frame after", "Then" (played straight on / held its frame / jumped), "In
  step with A after", and "Card lag typical" are defined in report 00, Section 1.

---

## 3. Before the feature (state at `0bd1533`)

`0bd1533` is the parent of the first feature commit. At that point `master` held Phases 0 to 7 of `docs/PLAN.md`:
the shelf, the slide across two phones, pairing, the real file transfer, the line-up swipe and automatic reconnecting.
It had been tested on two phones.

**What a video card did then:**

- **On the shelf** it showed a still frame of the video (a thumbnail decoded by Coil) with a round play icon in the
  middle (`ShelfCard.VideoPreview`). It did not play inside the app. There was no video player in the project: no
  Media3 in the version catalog and no `:core:media` module.
- **A tap** (`ShelfAction.OnOpenClick`) sent `ShelfEvent.OpenFile`, and the screen opened the file in another app on
  the phone.
- **A slide** worked like any other card. At the lift, `DragStart` carried the small thumbnail. B drew its half of
  the card with that still picture while `DragMove` messages came in.
- **At the drop**, `DragCommit` was followed by `FileOffer` and the whole file as one Nearby `FILE` payload. B showed
  the landed card (`ArrivingCard`) with the still picture and a progress ring that filled as the file arrived. Only
  once the whole file was there could the user open it.

So a video crossed as a picture of a video. There was no `MediaChannel`, no `PlaybackOffer`, and nothing in
`PeerLink` that let one phone read part of the other's file.

### The plan: `0bd1533` (docs)

`0bd1533` added `docs/LIVE_VIDEO_HANDOFF.md` and a key frame of the reference video
(`docs/reference/08-live-video-straddle.jpg`). The reference is a video of drooop, an iPhone-to-Mac app that is not
released, so there is no code to look at. The plan inferred how it must work:

1. **A playing video is a time, not pixels.** Only "which file, at what position, playing or not" travels; each
   device decodes and draws the video itself.
2. **The bytes arrive faster than they play.** The receiver reads the file with range requests: it needs only the
   index and a few seconds of data to start.
3. **The two devices agree on the time** (the plan proposed a shared clock; the build later used B's own clock and
   the measured round trip instead).
4. **Each device draws its own part of the card.**
5. **Make before break:** the sender keeps playing until the card is committed.

It chose ExoPlayer and a `TextureView`, a custom `DataSource` over the link, Nearby `STREAM` payloads for the bytes,
link quality `HIGH` as the gate, and listed phases LH-0 (a playing card) to LH-5 (a same-Wi-Fi fast path), new
messages, a state machine, tests, risks and decisions. Several parts of that plan were never built: they are listed in
Section 7.

---

## 4. The history, phase by phase

Each code commit has five parts: **Problem** (what was seen or wanted), **Before** (what the code did), **Change**
(what the new code does, and where), **Result** (what was measured), and **Still weak**. Docs-only commits are listed
at the end of each phase.

### 4.1 Phase 1, 25 Oct: the first build

Branch `feature/live-video-handoff`. All of it was built and tried on two phones in one day.

#### `e01d5e8` feat: keep a playing video playing as its card slides to the other phone

**Problem.** Build the feature: a video that plays on A keeps playing on B's half of the card, and on B after the
drop.

**Before.** See Section 3: a video crossed as a still picture.

**Change.** About 2,800 lines in 51 files. The main parts:

- **A new module, `:core:media`.** `VideoEngine` (common interface: `load`, `play`, `pause`, `seekTo`, `setSpeed`,
  `setVolume`, position and duration) and `VideoSource` (a local file, or a file on the peer read through a function).
  `ExoVideoEngine` implements it on ExoPlayer. `VideoSurface` is a `TextureView` inside an `AndroidView`.
  `RemoteDataSource` is the Media3 `DataSource` that reads the peer's file.
- **One player, several surfaces.** During a slide the same video is on screen in more than one place (the grid slot
  and the lifted copy on A; the incoming card, then the landed card on B). There is one `ExoPlayer`, and it draws into
  the surface that is active and was composed last. When it moves to a new surface, the new one first shows a bitmap
  of the old one's current frame (`TextureView.getBitmap()`) until its own first frame arrives, so the switch never
  flashes. Nothing is prepared again, so the video never stops on A when the card is lifted.
- **The shelf.** A tap on a video card now plays or pauses it inside the card (`onPreviewClick`), with a thin
  progress line along its bottom edge. `LocalCardVideo` tells each card whether it is the playing one.
- **Two new messages.** `PlaybackOffer(itemId, sizeBytes, positionMs, isPlaying)` goes right after `DragStart` when
  the lifted card is the playing video. `PlaybackClock(itemId, positionMs, isPlaying)` goes every 250 ms (4 times a
  second) while the card is in the air.
- **Reading the peer's file: `MediaChannel`** (interface in `core/transfer/domain`: `isFast`, `share`, `unshare`,
  `open`) and **`NearbyMediaChannel`** (`core/transfer/nearby`). `share` lets the peer read a file by id; `open`
  returns a stream of the peer's file from a byte offset. On the link, new frames in `Reconnect.kt`: `RangeRequest`,
  `RangeOpened`, `RangeRefused`. The file is read in **1 MB pieces**, each a `STREAM` payload. Every piece is
  **drained into a temporary file as soon as it arrives**, and **no piece is ever cancelled**: a piece nobody wants
  any more is drained and thrown away. `PiecedStream` joins the pieces into one stream for the player. It is used
  only when the link is fast (`HIGH`).
- **The follower, `PlaybackFollower`** (pure Kotlin, 159 lines of tests). Its first rules: within 25 ms do nothing;
  up to 120 ms ahead slow down (not below 0.9×); further ahead hold; up to 500 ms behind speed up (at most 1.25×);
  further behind jump. A start or a jump aims 1.5 s ahead; the jump lead is then learnt from how long jumps really
  take (1.25 × the measured time + 150 ms, kept between 0.3 and 2 s). The first load is not used to learn, because it
  takes much longer than a jump.
- **On B:** at the offer B loads the video **muted**, ahead of A's position. On `DragCommit` it takes over
  (`takeOverPeerVideo`): speed 1, sound on. The landed card (`ArrivingCard`) keeps playing the video, with a small
  progress ring in its corner instead of the large one, and the received item plays on.

**Why it is built this way: what the phones showed.** Recorded the same day in `89c7a2c`. None of it is in Nearby's
documentation; it came from the Play services log on a Redmi Note 10 Pro and a Redmi Note 8.

| # | Seen on the phones | Cause | What the code does because of it |
|---|---|---|---|
| 1 | After a few seconds the whole link froze: card moves stopped, then "Broken pipe" | Nearby keeps only about 10 chunks of an incoming stream for the app. When the player's buffer was full it stopped reading, and every payload on the connection stalled | Drain every stream into a file the moment it arrives |
| 2 | The link froze for about 35 s after the player jumped | An earlier version cancelled the old stream while it was still sending | Fixed-length pieces that end by themselves; nothing is cancelled |
| 3 | Some pieces were never matched to their request | A stream can arrive before the `RangeOpened` frame sent ahead of it | Both orders are handled |
| 4 | Video tried to stream over a link that could not carry it | Quality `MEDIUM` is Bluetooth, not Wi-Fi | Live video only at `HIGH` |
| 5 | Card moves paused 300 to 440 ms while pieces flowed | One connection, no priorities | Small pieces; still visible (fixed for good on 11 Nov, `1c5437f`) |

An earlier attempt during this work opened one stream per range, from the position to the end of the file. Causes 1
and 2 come from that attempt; the committed version already uses pieces. (The piece size also went from 4 MB to 1 MB
during this work, according to `MULTI_FILE_LAG_FIXES.md`.)

**Result.** The video kept playing across the gap, through the drop and through the file's arrival, on two phones.
Measured: Wi-Fi Direct carried 150 to 220 Mbit/s, but with one piece in flight at a time only about 25 Mbit/s were
used, and a test file whose sound was stored far from its pictures needed 13 MB before its first frame: about 5 s to
start.

**Still weak.** The slow start (one piece at a time). Card moves pausing behind pieces. After the drop, the whole file
was sent again as a `FILE` payload on the same link, and the player's pieces then took 1.7 to 3.3 s each instead of
about 0.2 s. Live video only on Wi-Fi Direct.

#### `ae56d42` feat: bring the live video handoff to about 95% of the target

**Problem.** The receiver started playing far too late, and a video lifted while the link was still on Bluetooth
could not be shared at all.

**Before.** One piece in flight at a time: the next was asked for once half of the current one was read. Each piece
costs a round trip, so the link sat idle most of the time.

**Change.**

- **Three pieces ahead** (`PiecesAhead = 3` in `NearbyMediaChannel`). Once the player has read half of the current
  piece, `PiecedStream.fillAhead()` makes sure three more are on their way. It still waits for "half", so a player
  that only glances at a part of the file (the start, before jumping to the index at the end) does not trigger 3 MB of
  reads it will throw away.
- **A fallback over the shared Wi-Fi: `LanFileServer`** (new, with `HttpRange.kt`). When A shares a video, it also
  starts a tiny HTTP server on its shared-Wi-Fi address and hands out an address with a random token
  (`http://<address>:<port>/v/<id>?t=<token>`). The offer carries it (`PlaybackOffer.httpUrl`). B checks it can reach
  the address (`canReach`, a `HEAD` request with a 600 ms timeout, only to private local addresses) and gives it to
  ExoPlayer, whose own HTTP code makes the range requests. The app needed the `INTERNET` permission and a network
  security config that allows plain HTTP.
- **How B chooses** (`ShelfViewModel.followPeerVideo`): over Nearby if the link is fast; else over the shared Wi-Fi if
  the address answers; else not at all (the card crosses with its still picture, as before).

The commit message also lists "learn the follower's lead only from real jumps, which fixes a frozen frame of about
4.7 s after the drop". The follower in `e01d5e8` already contains this rule, and `ae56d42` does not change
`PlaybackFollower`, so that part was most likely already in place when `e01d5e8` was committed.

**Result.** The user's impression: the delay before B played went from about 14 s to about 5 to 7 s. The developer
guide gives the first frame as about 5 s before and 1.8 s after (Redmi Note 8 receiving).

**Still weak.** The shared-Wi-Fi path is only a fallback: tried while Nearby was also on Wi-Fi Direct, one load took
25 s (the one radio switches between two networks, and every byte crosses the router twice). So it is used only while
Nearby is still on Bluetooth. Nothing moved a video off that path later; that came on 11 Nov (`bb02b53`).

#### `ec2a196` fix: play a received video from its own file once it has arrived

**Problem.** About 15 s after the drop, B's video stopped with `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`.

**Before.** After the drop, A sent the whole file. When that send finished, A removed the item from its shelf, which
also gave back the app's permission to read the original file. B's player was still reading pieces from A, and every
request was now refused.

**Change.** In `ShelfViewModel`, once the received file is complete on B, B moves its player onto that file, at the
same frame (`playLocalCopy`). Also, draining a piece now runs on `Dispatchers.IO` instead of starting a new thread for
each piece.

**Result.** The failure was gone. The switch is a reload of the player, so the card shows its thumbnail for a moment.

**Still weak.** The reload, and the double transfer after the drop (the player's pieces and the whole file at once).
Both were removed on 29 Oct for the Nearby path.

#### `bca1818` feat: keep the receiving video in step by speed rather than jumps

**Problem.** On the phones (Redmi Note 8 receiving), B started about 0.3 s behind A, could not catch up at 1.25×,
jumped 2 s ahead, and then froze for 1.25 s waiting for A. The log, from the developer guide:

```
20:01:05.149  Load from the other phone over Nearby at 3850 ms     ← started 1.5 s ahead
20:01:06.961  State ready at 3854 ms                               ← 1.8 s later: already about 0.3 s behind
20:01:07.886  Speed 1.2315                                         ← speeding up, capped at 1.25x, still falling behind
20:01:07.968  Seek from 4749 to 6893 ms                            ← past 0.5 s behind: jump about 2.1 s ahead
20:01:08.546  State ready at 6896 ms                               ← the jump took 0.58 s
20:01:08.630  Pause at 6896 ms                                     ← it overshot: hold ...
20:01:09.879  Play at 6902 ms                                      ← ... for 1.25 s (a frozen frame)
```

**Before.** The first rules from `e01d5e8` (start 1.5 s ahead, speed up to 1.25× within 0.5 s, hold past 120 ms
ahead).

**Change.** Only constants in `PlaybackFollower`. B is muted while it follows, so a faster pace is not heard:

| | Before | After |
|---|---|---|
| First load aims ahead by | 1.5 s | 2 s (new `StartLeadMs`) |
| Speed up when behind by up to | 0.5 s, at most 1.25× | 1 s, at most 1.5× |
| Slow down when ahead by up to | 120 ms, not below 0.9× | 300 ms, not below 0.85× |
| First jump aims ahead by | 1.5 s | 1 s |

**Result.** The user found it "a little better" on the phones. No numbers were recorded.

**Still weak.** The first jump aim of 1 s turned out to be too short on 11 Nov, when jumps took 1.2 to 3.4 s
(`94c829d`).

#### Docs of phase 1

- `89c7a2c` updates `docs/LIVE_VIDEO_HANDOFF.md` with the status of 25 Oct and the five things the phones showed
  about Nearby (the table above), and adds `docs/reference/freeze.png`: a screenshot of B's incoming card frozen on
  screen after A went silent mid-slide, a problem that is still open.
- `08c70f9` adds `docs/LIVE_VIDEO_GUIDE.md`, the developer guide (about 930 lines): what the user sees, the
  background, a map of the code, the whole journey step by step, reading the file in depth, the follower, surfaces,
  threads, what the phones taught, every tunable number, and how to debug on two phones. It also rewrites the
  checkpoint.

### 4.2 Phase 2, 29 Oct: one transfer (LH-4)

Branch `feature/live-video-one-transfer`, stacked on `feature/live-video-handoff`.

#### `b02c846` feat: take a received video's file from the pieces already played

**Problem.** After the drop, B got the video twice at once over the same link: the pieces its player still needed,
and the whole file as a separate `FILE` transfer. On 25 Oct this slowed the player's pieces from about 0.2 s to 1.7 to
3.3 s each, the video could stall, and then the player reloaded onto the received copy.

**Before.** Pieces were deleted once read. A always sent the file after the drop.

**Change.** B builds the file from what it already has. About 1,200 lines in 18 files.

- **Pieces are kept.** Every piece that arrives whole is kept on B (`NearbyMediaChannel.keepPiece`), in
  `cacheDir/live-ranges`.
- **B decides how the file comes.** A new message, `FileRoute(itemId, route)` with `SEND`, `FETCHING`, `FETCHED`,
  `CANCELLED`. When A's card carried a video offer, A does not send the file at once: it waits up to 5 s for B's
  `FileRoute` (`LinkedSlidePeer`). B answers `FETCHING` if it was playing the video from pieces over Nearby.
- **Only the gaps are fetched.** `MediaChannel.fetch` asks for the missing parts (`missingSpans` in the new
  `FileSpans.kt`), three at a time, and each round waits until no piece the player waits for is on its way (at most
  2 s, so a very slow link still finishes).
- **Checked.** A computes the SHA-256 of its file meanwhile (new link frames `DigestRequest` and `Digest`). B puts the
  pieces together into one file, hashing as it goes, and compares.
- **Kept like any received file:** copied to Downloads, then `MediaChannel.settle` tells the channel the file now
  lives there, then it is added to the shelf, then B answers `FETCHED`. Only then does A count its send as done.
- **No reload.** After `settle`, the open `PiecedStream` goes on from the local copy at the next piece boundary
  (`goOnFromHere`). Same player, same source.
- **Fallbacks.** If the fetch fails while the link is up (no room, hash mismatch, pieces refused), B answers `SEND` and
  the file comes the old way. If A hears nothing within 5 s (an older build on B), it sends the file. On the
  shared-Wi-Fi path B answers `SEND`, and the old reload stays. In the view model the choice is a `Delivery`:
  `FETCH`, `ASK_TO_SEND` or `SENT`.

```
 A (sender)                                          B (was playing the video over Nearby)
 DragCommit ───────────────────────────────────────▶ chooses FETCH
            ◀────────────────────────── FileRoute(FETCHING)
 (does NOT send the file)                            asks only for missing pieces, player first
 serves pieces   ◀──── RangeRequest ───
 hashes its file ◀──── DigestRequest ──
                 ───── Digest(SHA-256) ─▶            assembles, hashes, compares
                                                     keeps in Downloads, settles, adds to shelf
            ◀────────────────────────── FileRoute(FETCHED)
 removes the item: the send is done
```

**Result.** The checkpoint of 1 Nov records that "LH-4 worked on the phones (2026-10-29)". No numbers were written
down. The report's performance table (for example "piece time after the drop about 0.1 to 0.5 s", "about 20 to 30%
fewer bytes for the sample video") is an **estimate** and was never replaced by measured numbers. 285 unit tests
passed.

**Still weak.** No resume if the link drops mid-fetch. The file is written about one extra time on disk (pieces,
assembled file, Downloads copy), and a fetch needs about twice the missing size in free space. The shared-Wi-Fi path
still sends the file and reloads.

#### Docs of phase 2

- `7a5a3cf` adds `docs/reports/2026-10-29-live-video-one-transfer-report.md` (what changed, decisions, estimated
  performance, risks, tests, how it degrades) and `docs/reports/2026-10-29-live-video-one-transfer-before-after.md`
  (a learning guide, step by step, with lessons and exercises). It updates the guide (Sections 5.10 to 5.12, 6.7, 11 to
  14) and the checkpoint.

### 4.3 Phase 3, 1 Nov: a smoother card half

Branch `feature/smooth-card-half`, stacked on the two branches above. This work is about **any** card, not only
videos. It matters for the video because the card is the frame the video plays in: if B's half of the card moves
unevenly or lags, a perfectly synchronised video inside it still looks wrong.

**Problem (seen on 29 Oct with a photo and a non-playing video).** A card crossing to B felt like a handover between
two phones, not one object. The plan named five causes: moves arrive bunched and uneven, and B moved toward each one
when it arrived; early slides run on Bluetooth; the two phones report their screen density slightly wrong, so
millimetres differ; the Note 8 draws 60 frames a second and the other phone 120; and at the let-go A's card slowed to
a stop just past its edge, and B then started its landing from rest.

#### `d2b3caf` feat: measure how a card's moves reach the other phone (C0)

- Every move now carries the time it was made on A (`DragMove.atMillis`; an older build leaves it out).
- B logs one line per slide (tag `AirSlide`, `SlideMoveTimings`): how many moves, how many out of order, the gaps
  between arrivals, how evenly A sent them, how **late** each came compared with the slide's quickest move (this does
  not depend on the two clocks), and the one-way delay. Each as median, 95th percentile and maximum. This is the
  "card lag" measured in every later report.
- The link quality is logged at every lift on both phones.
- A debug-only ruler screen (`RulerActivity`) draws 50 mm lines from the density the phone reports.

#### `deb3dff` feat: turn the peer's millimetres into this phone's from the line-up swipe (C3)

- During the line-up swipe the finger crosses both screens at about the same speed. Each phone measures that speed in
  its own millimetres. Their ratio (`SlideLink.peerMmScale`) says how far one phone's millimetres are from the
  other's. B turns everything the peer measured (card size, position, speed) into its own millimetres.
- Only used when both crossings are faster than 40 mm/s and differ by less than 10%; otherwise the ratio stays 1. Kept
  with the paired phone (`PeerMemory`).
- Card speeds are given in the receiver's directions, and the media channel now reports when the link becomes fast
  (`MediaChannel.isFastUpdates`, a flow). The video code later uses this flow (`bb02b53`, `22acb9f`).

#### `8cdba51` feat: play the peer's card moves back evenly and carry the let-go across the edge (C1, C2, C4)

- **C1, even playback** (`MovePlayback.kt`, the heart of it). A stamps each move with the time its frame was drawn,
  which is evenly spaced. B does not move the card when a move arrives; it draws, every frame, where the card was a
  short moment ago by A's clock, interpolated between the two moves around that moment, then projects it forward by
  that moment plus the trip so it is not behind the finger. The moment is measured: the 95th percentile of lateness
  plus the time between two moves, kept between 12 and 150 ms. When moves stop coming, the card goes on along its last
  speed and slows over about 150 ms. A change of path is smoothed out over 80 ms, never a snap.
- **C2, the link before the card moves.** On Bluetooth only every other move is sent (the first and last always go).
  A dot on the devices icon shows the link: amber on Bluetooth, cyan on Wi-Fi. Cyan is also when live video over
  Nearby is possible.
- **C4, one motion through the let-go.** A sent card now leaves A at a steady speed (at least 1,400 dp/s) until it is
  fully off screen, and only then is the commit sent (`SlideState.flyOut`). B's landing starts from the speed the card
  was drawn moving and curves into its slot.

**Result.** 312 unit tests passed. Tried once on the phones: the user felt it was better, but there were no logs and
no notes, so there are no numbers. The planned tuning step (C5) is not recorded as done. The multi-file document of
6 Nov later uses this branch as "the baseline: smooth single slides, playing video included".

**Still weak, and what the plan left for later.** The plan had a **Part B, the video half**: keep B's player warm on
a pull-back, prefetch the start and the index at the offer, read from kept pieces, start on less buffer, learn the
start lead, a fresh picture at the lift instead of the thumbnail. Part B was never built as planned. Some of its ideas
came back on 11 Nov in a different form: reading the index ahead and serving reads from kept pieces (`22acb9f`), and
keeping what was read on a pull-back (`5c1e228`, which keeps the pieces, not the player). A learnt start lead was tried
and parked (`e85dbc4`). A fresh picture at the lift is still only an idea.

#### Docs of phase 3

- `aa8b944` adds `docs/get-smoother-card-half-plan.md` (Part A: phases C0 to C5 for card motion; Part B: phases S0
  to S5 for the video half) and points the checkpoint at it.
- `08e5217` adds to the plan and checkpoint how to run the card-motion measurements on the phones.
- `68f391c` records in the plan and checkpoint what was built and how to test it once.
- `5d40f2f` notes in the checkpoint the first try ("better", no notes) and the noted run still to do.
- `4b9c2f3` adds `docs/reports/2026-11-01-smooth-card-half-report.md`: what C0 to C4 changed, decisions, expected
  effects, risks, tests and how to check it.

### 4.4 Phase 4, 6 Nov: air gestures, several files, and the lag they caused

Branch `feature/transfer-multi-files`, then two side branches (`fix/slide-first-pull-pieces` and
`fix/slide-first-wifi-messages`). None of these commits is about the video itself. They matter to it because the
video, the card's messages and every other file share one Nearby link, and several of these fixes change what travels
on it and when.

```
feature/smooth-card-half   4b9c2f3
└─ 23cb2ec  air gestures
   └─ b31b41b  files one at a time
      └─ 2451102  only the newest card move
         ├─ 3be3def  pull files in pieces, paused while a card is in the air
         │  └─ 514b94d  batch the card moves                       (this line became the main line)
         └─ 46df88d  card messages over the shared Wi-Fi            (side branch; reached the main line as b165af6)
```

#### `23cb2ec` feat: air gestures - grab a card with a fist, open the hand to drop it on the paired phone

**What.** Ported from a separate Android-only copy of the app. The front camera (CameraX with the MediaPipe gesture
model) recognises a fist and an open hand. A fist "grabs" a card; opening the hand over the other phone "catches" it.
New messages `Grabbed` and `Released`.

**Relation to the video.** A grabbed playing video travels like a slid one. It is offered and reported while held, B
plays it muted from the same frame, and at the catch B plays on with sound and takes the file from what it read
(`Delivery.FETCH`). The slide and the grab now share the code that announces the card, reports the playback and lands
it, so every later video fix applies to both. `docs/AIR_GESTURE.md` says it had not been tried on the phones yet when
it was ported.

#### `b31b41b` feat: send several files one at a time, each with its own progress, stop and notification

**Problem.** Several videos slid over one after another made B crawl (`multi_file_problem.mp4`): each file took close
to a minute, and the 1.1 s arrival animation took about 12 s.

**Change.** Files go one at a time (`maxActiveSends = 1` in `LinkedSlidePeer`); a waiting file is announced with
`FileQueued`, and either phone can stop one file (`FileCancelled`). An app-wide `TransferBoard` holds every file's
status, bytes, speed and time left. Progress no longer goes through the shelf state, so the grid stops redrawing on
every percent. A transfers pill, a sheet with one row per file, and notifications.

**Result.** Files queued correctly, but B's screen was as laggy as before.

**Relation to the video.** None directly. It showed that even one file at full speed holds the card's messages up,
which is the same mechanism that later made B's half lag behind a playing video.

#### `2451102` fix: send only the newest card move while the link is busy, so moves never pile up

**Problem.** Each drag frame made a `DragMove`, and each waited its turn until Nearby took it. With a file on the
link that took hundreds of milliseconds per message, so moves piled up by the thousand. B played the backlog back in
slow motion, and the let-go waited behind all of it (14 s at the edge in B's recording).

**Change.** `ShelfViewModel.postMove` keeps only the newest unsent move; none goes out after the card was let go or
put back.

**Result.** B was still laggy: the newest move still waited behind file bytes inside Nearby. And it had a side effect
for video, found later the same day: with a playing video keeping Nearby busy with pieces, many moves had to wait and
were thrown away, so B's even playback (`8cdba51`) had too few points. B's half of a playing-video card stepped and
lagged. `514b94d` fixed that.

#### `3be3def` fix: on Wi-Fi pull files in pieces that pause while a card is in the air

**Problem.** Nearby cannot pause a `FILE` payload, so while a file moved, the card's moves and its let-go waited
behind its bytes for seconds.

**Change.** On Wi-Fi, B now **pulls** each file in 1 MB pieces over the media channel: the same piece path the video
already used, with the same SHA-256 check (`Delivery.PULL`). A new `MediaChannel.holdFetches(hold)` tells the channel
a card is in the air in either direction; while it is, a fetch asks for no new piece (at most 15 s). Pieces already on
their way still come. A shares the file, waits for the pull, and reports how much was served. On Bluetooth nothing
changes; a failed pull falls back to sending the file.

**Result.** Works on the phones: slides stay smooth while files move. A single playing video was less smooth than on
the smooth-card-half branch; this was traced to `2451102`.

**Relation to the video.** The pause applies inside `fetch`, which is also how B fills in a video's file after the
drop (`b02c846`). So since this commit, the background fetch of a landed video also stops asking for pieces while
any card is in the air. The player's own pieces are not held.

#### `514b94d` fix: batch the card moves made while the link is busy instead of dropping them

**Problem.** See `2451102`: B's half of a playing-video card stepped and lagged because moves were thrown away.

**Change.** Moves made while one message is going out all go together in the next message (`SlideMessage.DragMoves`,
at most the last 30, about half a second of frames). B plays each one back as if it had come alone. Every point
arrives again, and never more than one message waits. A single move still goes as a `DragMove`.

**Result.** Not tested on the phones at the time. On 11 Nov the card lag during a playing video was still 207 to
610 ms, because video bytes already queued inside Nearby still went first. That was fixed by giving the card messages
their own connection (`1c5437f`).

#### The side branch of 6 Nov: `46df88d`, later `b165af6`

The same day a second option was built on its own branch, `fix/slide-first-wifi-messages`: carry the card messages
over a TCP connection on the shared Wi-Fi, away from Nearby. It felt smoother than the pull-in-pieces fix with a
playing video, but it needs both phones on the same Wi-Fi network, so it was kept as an optional extra and not merged.
On 11 Nov it was copied onto the main line as `b165af6` (author date 6 Nov, committed 11 Nov) and became the base of
the card-lag fix. It is described there, in Section 4.6.

#### Docs of phase 4

- `9eb4e44` adds `docs/MULTI_FILE_LAG_FIXES.md`: the problem as seen in two screen recordings, each fix tried
  (with commit, branch, idea, change, phone result and lesson), a skipped option, a comparison table, and an open
  question: a playing video started later on B than on the smooth-card-half branch, with no cause found in the code.
  The documents do not record an answer to that question; the 11 Nov work measured the start directly and shortened
  it. (`docs/MULTI_FILE_TRANSFERS.md` was written in `b31b41b` and `2451102`.)

### 4.5 Phase 5, 10 Nov: transfers outlive the screen

Three fixes, each on its own branch, merged together into `fix/2026-11-10/merge-all-fixes` and on 11 Nov into
`feature/transfer-multi-files`. They are about correctness when the app is swiped away during a send, not about video
timing. None has a recorded phone result on matched builds: on 11 Nov the Redmi Note 8 was found still running a build
of 10 Nov 17:35 without them, so the test of 10 Nov did not run them. A normal drop was confirmed on matched builds on
11 Nov.

#### `2e088ad` fix: keep sent items hidden when the app is reopened during a send

**Problem.** The list of sent items lived in the screen's `ShelfViewModel`. Swiping the app away lost it, but the send
went on in the app-wide scope, so the reopened screen showed the item again until the send ended.

**Change.** An app-wide `SentItems` (`feature/shelf/domain`, a `StateFlow` of ids, bound as a Koin `single`) shared
by every shelf screen. [Report 01](reports/fix/01-2026-11-10-keep-sent-items-hidden-report.md).

**Relation to the video.** A video card slid away stays hidden on A even if A's app is reopened while B is still
fetching the file.

#### `faa08ca` fix: receive the peer's files while the shelf screen is closed

**Problem.** Only the shelf screen took files in. With the app swiped away, the connection could stay up, but a file
slid over was lost.

**Change.** An app-wide `UnattendedReceiver`, created at start, follows the peer's cards and receives a let-go card's
file when no shelf screen is open (pulled in pieces on Wi-Fi), with progress on the transfer board.
[Report 02](reports/fix/02-2026-11-10-receive-with-screen-closed-report.md).

**Relation to the video.** Nothing plays on a closed screen, so for a card that offered its video the unattended
receiver asks A to send the file (`ASK_TO_SEND`). A video sent to a swiped-away phone has no arriving card; it shows
only once it is fully received.

#### `97c6009` fix: count a sent file as done only once the receiver says it kept it

**Problem.** A sent file counted as done when its bytes were through, even if nothing on B kept it. A then removed the
item, and the file was on neither phone.

**Change.** A new message `FileKept(itemId)`. A keeps the item until B says `FileKept`; a refusal, the link going, or
no word within 3 minutes fails the send and the item comes back. The next file may start while B still saves.
[Report 03](reports/fix/03-2026-11-10-confirm-file-kept-report.md).

**Relation to the video.** None for a fetched video: fetched and pulled files already waited for B's word
(`FileRoute(FETCHED)`, `b02c846`) and are unchanged. It covers a video whose file is sent the old way (the shared-Wi-Fi
path, or `ASK_TO_SEND`).

### 4.6 Phase 6, 11 Nov: start speed and card lag

This was the big day for the video. Every fix has its own branch named `fix/2026-11-11/<time>-<slug>`, its own report
in `docs/reports/fix/`, and a stage in the benchmark comparison (report 00). The test video was "video 2", the 676 MB
phone recording described in Section 2.4, playing on A. A second video, "video 14", was used from the late afternoon.

**The starting point (morning of 11 Nov, matched builds, all on Wi-Fi Direct):** first frame on B 2.6 to 4.2 s, card
lag 207 to 502 ms per slide. A first round on mismatched builds had first frames of 2.5 to 4.5 s, jumps in 3 of 6
slides, and up to 9.1 s until in step.

#### `bb02b53` fix: move a followed video onto the pairing link once it reaches Wi-Fi Direct

**Problem.** "B keeps pausing then resuming" after the 676 MB video was dropped on B. In that slide the card was
lifted 10 s after a reconnect, while the link was still on Bluetooth. The link reached Wi-Fi Direct 2 to 3 s later.
B's first frame came 15.7 s after the lift, and then B played about 1.5 s for every 10 to 30 s of buffering, until the
send was cancelled. In the same session, slides lifted after the upgrade had their first frame after 2.6 to 4.2 s
with no rebuffering.

**Before.** B chose how to read the video once, at the offer (`ae56d42`). Lifted on Bluetooth, it read over the
shared Wi-Fi (the router path) and stayed there for good, even after the link became fast. After the drop the file
was then pulled over Wi-Fi Direct (`Delivery.PULL`) while the player still read through the router: two transfers
competing, and one radio switching between two networks.

**Change.** In `ShelfViewModel`, a new `readOverLink()`. When the link becomes fast (`isFastUpdates`, called from
`talkTo()`) and also at the landing (`land()`, before the delivery is chosen), a video still on the router path is
reloaded over the pairing link, on the frame A is at, keeping play or pause, and muted again (a load resets the volume
to full; the unit test caught this). `FollowedVideo` now carries the file size and a `viaLink` flag, so a video already
on the link is not reloaded, and the landing chooses `FETCH` instead of `PULL`.

**Result.** The targeted case could not be reproduced on the phones: in every test the link reached Wi-Fi Direct
before the lift. Two normal slides on this build showed the normal path still works (first frame 4.0 and 6.0 s, both
"landed during load", which is slower). [Report 04](reports/fix/04-2026-11-11-lift-before-wifi-report.md).

**Still weak.** One visible reload when the link upgrades. A card **dropped** while still on Bluetooth is sent the
slow way and stays on it. The upgrade itself is not faster.

#### `b165af6` and `1c5437f`: the card's messages get their own connection

**Problem.** While a playing video was dragged across, B's half of the card trailed the finger. In slides where the
video was loading, card moves arrived 250 to 610 ms late (median); in a slide where the video had not started loading,
60 ms.

**Before.** Nearby carried the card's moves (one about every 17 ms) and the video's 1 MB pieces on one connection.
Nearby's socket send buffer is several MB (its log shows 4 MB asked for, 8 MB granted). A move written after video
bytes waits until they have gone out, and at about 4.5 to 5.5 MB/s a few MB queued means hundreds of milliseconds. The
existing mitigations (1 MB pieces, three ahead, fetches held while a card is in the air, batched moves) make the queue
shorter, but none lets a move overtake bytes already queued.

**Change, part 1: `b165af6`** (`fix: carry the card messages over the shared Wi-Fi, apart from the files`, the 6 Nov
side-branch commit copied onto the main line). New `LanMessageLink.kt` and a `LinkFrame.LanOffer` frame. When the
phones link up, each listens on its Wi-Fi address and sends the other its address, port and a random 16-byte token
over the encrypted Nearby link. Each connects to the other's listener, and every slide message goes over that TCP
connection (with Nagle's delay turned off). A connection without the token is dropped, offers are only followed to
local addresses, and a broken connection falls back to Nearby. Files and pieces stay on Nearby. On its own this needs
a shared Wi-Fi network (a router).

**Change, part 2: `1c5437f`** (`fix: carry the card messages over the Wi-Fi Direct group, apart from the video`). Two
files in `core/transfer/nearby`. `LanFileServer.directAddress()` finds this phone's address on the Wi-Fi Direct
interface (`p2p`), which exists once Nearby has upgraded the link. `NearbyPeerLink.offerLan()` prefers that address,
falls back to the shared-Wi-Fi address, and is called again on every `onBandwidthChanged`. So the card connection
starts on the shared Wi-Fi if there is one, and moves to the Wi-Fi Direct group when the group comes up. No router is
needed. As logged on A:

```
 13:44:02.6  listening on 192.168.0.111 (home Wi-Fi)
 13:44:03.1  card messages go to 192.168.0.106
 13:44:05.6  Nearby moves from Bluetooth to Wi-Fi Direct
 13:44:05.7  listening on 192.168.49.1
 13:44:06.1  card messages go to 192.168.49.63
```

**Relation to the video.** Every slide message takes this path, so `PlaybackOffer` and `PlaybackClock` travel there
too, away from the video's pieces. The pieces stay on Nearby.

**Result.** Confirmed on the phones ("more quick than before, too much better").

| Card moves, per slide | Before (7 slides) | After (5 slides) |
|---|---|---|
| Median lateness | 207 to 610 ms | 41 to 66 ms |
| Worst single move | 706 to 1,483 ms | 99 to 360 ms |
| "Card messages paused" warnings | repeated, 300 to 615 ms each | none |

No move arrived out of order. The video's start did not change: first frame 2.4 to 3.6 s, and 2 of 5 slides jumped.
[Report 05](reports/fix/05-2026-11-11-card-lag-during-video-report.md).

**Still weak.** Before Wi-Fi Direct is up and with no shared Wi-Fi, the messages are still on Nearby. Moves still
arrive in small bursts (arrival gaps p95 about 95 to 100 ms although they are sent every 17 ms; not investigated). Only
one pair of phones was tried; whether every phone allows a socket on Nearby's group is not known.

#### `22acb9f` fix: read a playing video's index ahead of its card

**Problem.** After the lift, B's half took seconds to show a frame. B learnt about the video only at the lift. Before
any picture its player read the header and the index: four requests, one after another (offset 0, offset 377,092,
then two near the end of the file). They cost about 1.2 s in the morning and 3.2 s in the slowest start of the day
(14:26:01: 441 + 928 + 1,035 + 414 ms). A keeps playing meanwhile, so a late start also made B jump.

**Before.** Nothing of the file was on B at the lift. Every start paid the four round trips.

**Change.**

- **A new message, `PlaybackHint(itemId, sizeBytes)`.** When a video starts playing on A and the link is on Wi-Fi, A
  shares the file and sends the hint (`ShelfViewModel.hintPlaying()`). Also when the link becomes fast while a video
  plays, and when a lifted card comes back.
- **B reads ahead.** On a hint, B calls the new `MediaChannel.prefetch(fileId, sizeBytes)`: the first 1 MB piece and
  the last 4 MB of the file (`indexSpans()` in `FileSpans.kt`), one piece at a time, and never while a card is in the
  air or a player waits for pieces. The pieces are kept.
- **Reads are served from kept pieces.** `open()` and `PiecedStream` now read from a kept piece when one covers the
  offset (a `PiecePart` interface over `DrainedPiece`, arriving, and `KeptPart`, already on B), and only ask the link
  for the rest. They know where the file ends from the hinted size.
- **At the lift**, if the offer matches the hint's id and size, B keeps what it read ahead instead of forgetting it.

**Result.** Confirmed ("it starts sooner").

| | Before (same day) | After (4 slides) |
|---|---|---|
| First frame on B | 2.4 to 6.1 s | 1.1 to 2.4 s |
| In step with A | 2.9 to 13 s | 1.8 to 2.4 s |
| Jumps | 3 of 5, then 2 of 3 | 0 of 4 |
| Card lag (median) | 41 to 66 ms | 39 to 43 ms |

The read-ahead itself took 1.3 to 3.4 s in the background (5 pieces). With a steady start, the fixed 2 s aim now
landed close: holds of 0.4 to 0.55 s at most. [Report 07](reports/fix/07-2026-11-11-video-index-prefetch-report.md).

**Still weak.** It opened the **whole** file of any playing video to the peer before its card was lifted (a
behaviour change; two old tests were changed). A fixed "first 1 MB, last 4 MB" guess. A pull-back still let go of
everything, so a lift soon after a pull-back started without the index (15:00:46: 2.3 s). The next three fixes address
exactly these.

#### `1850d4e` feat: read where an MP4 file keeps its index and its pictures

A small, pure building block for the next two fixes: `Mp4Boxes.kt` in `core/transfer/nearby`. `mp4BoxAt()` reads one
box header (type, start, length; also lengths over 4 GB and a box that runs to the end of the file; anything that is
not a box gives null). `mp4MediaSpans()` lists where the pictures and sound are (the content of the `mdat` boxes).
`openSpan()` cuts a request short where such a part starts. 20 unit tests.

#### `cc49273` fix: read ahead exactly a video's index, wherever it is and however long

**Problem.** The index grows with the video: about 0.27 MB a minute for video 2. A video longer than about 15 minutes
would have an index bigger than 4 MB, and B would read only part of it (an estimate from one video, not tested). A
video saved with its index at the start keeps it outside the last 4 MB. And for video 2 it read up to 4 MB where 1.2 MB
were needed.

**Change.** `NearbyMediaChannel.prefetch()` now walks the boxes from offset 0, reading only each box's first 16 bytes
(from kept pieces if they are there, otherwise one piece from A), reads the whole `moov` box wherever it is, notes the
`mdat` header, and stops. It logs "Index of … fetched ahead in N ms". A file that is not MP4 is not read ahead.
`indexSpans()` and its tests are removed.

(This commit also added the IDE folder `core/.idea/` by mistake; `1dc3c33` removed it from git.)

**Result.** Confirmed in the 17:06 to 17:09 test. The read-ahead of video 2 took 1.05 s in 3 pieces (at byte 0, at
675,051,444 and at 676,100,020; each answered in 116 to 202 ms) instead of 1.3 to 3.4 s in 5. Video 14: 0.55 s, then
0.6 to 1.1 s on later runs. [Report 08](reports/fix/08-2026-11-11-fetch-exact-index-report.md).

**Still weak.** A video of 15 minutes or more and a video with its index at the start were not tried on the phones.

#### `94c5146` fix: let the peer read only a playing video's header and index before its card is lifted

**Problem.** Since `22acb9f`, A opened the whole file of any video it played to the paired phone, before any lift.
The app on B only asks for the index, but a modified app could have copied a video the owner was only watching.

**Change.** A new `MediaChannel.shareIndex(fileId, uri, sizeBytes)`. Until the card is lifted, A finds where the
pictures and sound are (`mp4MediaSpans()`, off the main thread), serves a request that starts before them only up to
where they start (`openSpan()`), and refuses one that starts inside them ("Piece of … refused: its card isn't
lifted"). A non-MP4 file is not readable ahead at all. A file already shared in full (its card is in the air) is never
downgraded. `hintPlaying()` now calls `shareIndex()`. Lifting the card opens the whole file, as before.

**Result.** Works as intended. One cost: B's player needs some bytes about 28 KB into the picture data at every
start. They used to be inside the first megabyte read ahead; now the first read is cut at byte 377,108, so B asks for
them at the lift. One extra request, 213 ms when measured (17:07:45). In the test, A refused exactly one request, a
leftover from B's player right after a pull-back.
[Report 09](reports/fix/09-2026-11-11-index-only-before-lift-report.md).

**Still weak.** The extra request at every start. The serving code has no unit test.

#### `5c1e228` fix: keep what was read of a video when its card is pulled back and it plays on

**Problem.** On a pull-back B let go of everything it had read of the video, index included, and then read the index
again (1.3 to 2.7 s). A lift within that time started without it (15:00:46: first frame 2.3 s, against 1.1 to 1.5 s).

**Change.** In `ShelfViewModel` on B: on a pull-back, if the card's video is the one A says is playing (from its
`PlaybackHint`), B keeps what it read and notes it (`keptForRelift`). It lets go once A hints another video. A new lift
of that video clears the note. Not only the index is kept: every piece B's player read while the card was over the
edge is kept too.

**Result.** After a pull-back at 17:08:35 the index was "fetched ahead in 60 ms": nothing had to be read. The next lift
of that video, 17 s later, had its first frame after **0.4 s**, the fastest start of the day, because the frames B had
played were kept as well. [Report 10](reports/fix/10-2026-11-11-keep-index-on-pull-back-report.md).

**Still weak.** Only after a pull-back; after a drop, a new read-ahead starts (a lift 1.4 s after it began started
without its index: 2.4 s). What is kept stays on B until another video plays on A or the app restarts. A lift a second
or two after a pull-back, the exact case, was not tried.

#### The 17:06 to 17:09 test (`cc49273`, `94c5146`, `5c1e228` together)

| Slide | Video | First frame after | Then | In step after | Card lag |
|---|---|---|---|---|---|
| 17:07:45 | 2 | 2.6 s | jumped twice | 8.3 s | 37 ms |
| 17:08:10 | 14 | 2.0 s | played straight on | 2.0 s | 38 ms |
| 17:08:52 (second lift) | 14 | 0.4 s | held 0.8 s, then dropped | 1.2 s | 44 ms |
| 17:08:58 | 14 | 2.3 s (landed during load) | played straight on | 2.3 s | 48 ms |
| 17:09:10 | 14 | 1.4 s | held 0.2 s, then dropped | 1.7 s | 36 ms |
| 17:09:17 (index not there yet) | 14 | 2.4 s | played straight on | 2.4 s | 41 ms |

The double jump at 17:07:45 did not come from these fixes; it led to the next one.

#### `94c829d` fix: catch up by playing faster up to 1.5 s behind, and aim a jump 2 s ahead

**Problem (17:07:45).** B's first frame came after 2.6 s. It had aimed 2 s ahead, so it was 1.01 s behind A: just over
the 1.0 s limit, so it jumped (from 4,731 to 6,742 ms in the video). That reload took 2.1 s but had aimed only 1 s
ahead, so it was behind again and jumped a second time (7,043 to 10,071 ms; 1.9 s). In step only after 8.3 s. Jumps
measured that day took 1.2 to 3.4 s each, so a first jump aim of 1 s was shorter than any of them.

**Change.** Three constants in `PlaybackFollower`:

| | Before (`bca1818`) | After |
|---|---|---|
| Catch up by playing faster when behind by up to | 1.0 s | 1.5 s |
| A first jump aims ahead by | 1.0 s | 2.0 s |
| The learnt jump aim, at most | 2.0 s | 3.0 s |

At 1.5× B gains half a second every second, so 1.5 s behind is made up in 3 s, with a moving (muted) picture all the
time. A jump shows a still frame for 1.2 to 3.4 s and then still has to land.

**Result.** Confirmed ("it's good, not like the build before"). 0 jumps in 12 loads in the next test (18:10 to 18:12).
No double jump in 37 loads across three tests that included this rule; the three loads that did fall behind jumped
once each and were in step after 4.7 to 6.1 s. [Report 11](reports/fix/11-2026-11-11-catch-up-before-jumping-report.md).

**Still weak.** Up to 3 s at 1.5× may be noticed even when muted. An early jump now holds a little longer. The same
test showed a new problem: every second lift held its frame (next fix).

#### `733dbb0` fix: aim a video lifted again after a pull-back 0.6 s ahead, not 2 s

**Problem (18:10 to 18:12).** With what was read kept (`5c1e228`), a second lift had its first frame after 0.33 to
0.43 s. But it still aimed 2 s ahead, as a first lift does, so it arrived about 1.5 s early and stood still on its
first frame. Ten of ten second lifts held 1.3 to 1.6 s and were in step only after 1.8 to 2.1 s: no better than a
first lift.

**Change.** `PlaybackFollower` now takes how far ahead it aims as a constructor parameter, and a new constant
`SecondLiftLeadMs = 600`. `ShelfViewModel` uses it when the card is lifted again after a pull-back; otherwise 2 s. A
second lift that takes longer after all is made up by playing faster.

**Result.** Confirmed ("very very good"). Test from 18:17 to 18:20, 18 loads that reached a first frame:

| | Before (18:10 test) | After (18:17 test) |
|---|---|---|
| Second lifts that held their first frame | 10 of 10, 1.3 to 1.6 s | none |
| First frame, second lift | 0.33 to 0.43 s | 0.30 to 1.5 s, most under 0.9 s |
| In step, second lift | 1.8 to 2.1 s | 0.30 to 1.5 s, most under 0.9 s |
| Jumps | 0 of 12 | 1 of 18 |
| Card lag (median) | 37 to 48 ms | 26 to 46 ms |

[Report 13](reports/fix/13-2026-11-11-aim-closer-on-second-lift-report.md).

**Still weak.** The one jump (18:19:46): the card had been pulled back **before** B's first frame, then lifted again
3 s later. It counted as a second lift and aimed 0.6 s ahead, but none of its pictures were kept, only its index. The
load took 1.88 s, fell more than 1.5 s behind, and jumped once (in step after 4.7 s). Second lifts also spread more
(0.30 to 1.5 s): a card pulled back quickly leaves less to reuse.

#### `25e0f99` fix: aim a second lift closer only when the video had played here before the pull-back

**Problem.** The 18:19:46 case above.

**Change.** In `ShelfViewModel`: `FollowedVideo.hasPlayed` becomes true once B's player moves while A plays. On a
pull-back that keeps the video for a new lift, B also notes `playedBeforePullBack`. The next lift aims 0.6 s ahead
only if that is true; otherwise 2 s, as a first load. The index is kept in both cases; only the aim changes.

**Result.** One new unit test (B loads at 7,000 ms, 2 s ahead, and the index is still kept). Phone test on 12 Nov: see
Section 4.7. [Report 14](reports/fix/14-2026-11-11-aim-closer-only-after-playing-report.md).

#### The day in one table (from report 00)

| Stage (build) | Slides | First frame after | In step after | Jumps | Card lag |
|---|---|---|---|---|---|
| Before the day's fixes | 3 | 2.6 to 4.2 s | 2.6 to 4.2 s | 0 of 3 | 207 to 502 ms |
| `bb02b53` lift before Wi-Fi | 2 | 4.0 to 6.0 s (both landed during load) | 4.0 to 6.0 s | 0 of 2 | 252 ms (one slide) |
| `1c5437f` card lag | 5 | 2.4 to 3.6 s | 2.4 to 5.4 s | 2 of 5 | 41 to 66 ms |
| `e85dbc4` learnt aim, both versions (parked) | 8 | 2.5 to 6.1 s | 2.9 to 13.0 s | 2 of 8 | 42 to 62 ms |
| `22acb9f` index read-ahead | 4 | 1.1 to 2.4 s | 1.8 to 2.4 s | 0 of 4 | 39 to 43 ms |
| `cc49273` + `94c5146` + `5c1e228` | 6 | 0.4 to 2.6 s | 1.2 to 8.3 s | 1 of 6 | 36 to 48 ms |
| `314b6a7` video over Wi-Fi Direct (parked) | 7 | 1.6 to 3.4 s | 1.6 to 6.1 s | 2 of 7 | 39 to 56 ms |
| `94c829d` catch up | 12 | 1.7 to 2.5 s first lifts; 0.33 to 0.43 s second lifts | 1.7 to 2.5 s first lifts; 1.8 to 2.1 s second lifts | 0 of 12 | 37 to 48 ms |
| `733dbb0` closer second lift | 18 | 2.8 s first lift; 0.30 to 1.9 s after | 0.30 to 4.7 s | 1 of 18 | 26 to 46 ms |

How far to trust these: small samples (2 to 18 slides per stage), one pair of phones, one or two videos, all measured
one after another on one afternoon. Loading speed varied between rounds for reasons not explained: the two versions of
the learnt aim run the same loading code, yet their first frames differ. Slides "landed during load" are slower.

#### Docs and chores of phase 6

- `4f5e4c2` adds report 04 (lift before Wi-Fi), first as `docs/reports/2026-11-11-lift-before-wifi-report.md`.
- `28d91c9` adds report 05 (card lag during video).
- `7ea8ffc` adds report 07 (index read-ahead).
- `f714c6a` adds the benchmark comparison (later report 00), which groups every video slide of the day by build, and
  moves the reports into `docs/reports/fix/`.
- `4e5e30f` adds reports 06 (learnt aim, parked), 08 (exact index), 09 (index only before lift), 10 (keep on
  pull-back) and 11 (catch up), and adds their stages to the comparison.
- `1dc3c33` (chore) stops tracking `core/.idea/`, which `cc49273` had added by mistake.
- `a15d168` adds reports 12 (video over Wi-Fi Direct, parked) and 13 (closer second lift), with the evening's stages
  in the comparison, and updates report 11.

### 4.7 Phase 7, 12 Nov: phone test, merge, and two new weak points

#### The phone test of `25e0f99`

Phone A (M2101K6G) and phone B (Redmi Note 8), the same build on both, 23 lifts in three sessions:

- **The targeted case was never reproduced by hand:** no lift of the same video came after a pull-back made before
  B's first frame. The fix was merged on its unit test alone.
- **No regression in the usual second lift.** Session 1 (12:29 to 12:32): 11 second lifts, first frame 0.3 to 1.5 s
  (one at 3.3 s), no jump. 4 first lifts, 1.7 to 4.2 s, one jump (a first lift that took 2.9 s, then jumped 3.5 s
  ahead).
- **New, not fixed (1): a hole after a pull-back.** Session 2 (12:57 to 12:58), second lifts at 0.5, 0.5 and 2.3 s. The
  2.3 s one then jumped 3.8 s ahead and buffered another 3.3 s. From the logs: after a pull-back, B's player keeps
  reading ahead until A refuses a request ("its card isn't lifted"). The refused piece (at 96.8 MB) leaves a hole in
  what B kept, and the next lift started exactly there (39 s into the video). Fetching it again took 1.4 s. Seen once.
- **New, not fixed (2): a slower link.** Session 3 (13:05 to 13:06): two first lifts took 3.9 and 3.3 s, and each then
  jumped about 4 s ahead. A piece took 0.2 to 1.5 s to open, against 0.1 to 0.25 s on 11 Nov, and the connection was
  set up three times within two minutes in one session. The cause was not looked into.

#### Merges and docs of phase 7

- `c738f7b` adds report 14 with the phone test above.
- `1a4db69` numbers the fix reports in reading order (`00` to `14`) and adds reports 01 to 03 for the three 10 Nov
  swipe-away fixes.
- `4b51cdb` changes `CLAUDE.md` to call the main branch `main`.
- The fixes of 11 Nov had been merged into `feature/transfer-multi-files` branch by branch (merge commits `3433a5b`,
  `d94ebfe`, `afd67e2`), then the docs (`9d328e5`), then `feature/transfer-multi-files` into the main branch
  (`de96f50`). `master` was renamed `main` that day. The repository has no remote: all of this exists only on the
  development PC.

---

## 5. Tried and parked

Two experiments were built, tested on the phones, and **not merged**. Their branches still exist.

### 5.1 `e85dbc4`: a learnt aim for the first load

Branch `fix/2026-11-11/1408-video-aim-ahead`. [Report 06](reports/fix/06-2026-11-11-video-aim-ahead-report.md).

**Idea.** B's first load aimed a fixed 2 s ahead. When the start took longer, B landed behind and jumped; when it took
less, B held its first frame. So learn the aim from how long the last first loads really took on this pair of phones.

**What it did.** A new `StartLead` class (`feature/shelf/domain`) remembers the last three first-load times.
`PlaybackFollower` takes the aim in its constructor and reports how long its first load took. First version: the
average of the last three × 1.1 + 150 ms. Tuned version (the commit): the middle one of the last three, with no
margin. 3 s before any start was timed; never below 1.5 s or above 5 s. Learnt per app run only.

**Measured.** First version, 5 slides: no jumps, but frozen frames of 1.3 and 1.5 s in two of them. Tuned version, 3
slides: 2 jumped, both twice (one in step only after 13.0 s).

**Why parked.** Starts ranged from 2.5 to 6.1 s on the same code and video; no aim learnt from the last three fits the
next one. The cause of the spread was elsewhere: in the 6.1 s start, 3.2 s went to four requests for the header and
index. That pointed to the index read-ahead (`22acb9f`), which made starts shorter and steadier (1.1 to 2.4 s), after
which the fixed 2 s aim worked. The learnt aim stays on its branch in case starts become variable again; it would need
a margin between the two versions and a new test round.

### 5.2 `314b6a7`: the player reads the video over its own connection on the Wi-Fi Direct group

Branch `feature/2026-11-11/1747-video-over-wifi-direct`, built on every fix up to `94c829d`.
[Report 12](reports/fix/12-2026-11-11-video-over-wifi-direct-report.md).

**Idea.** A piece asked for over Nearby took 0.1 to 1.1 s to start coming, and the recording makes the player hop
between its pictures and its sound. The card messages had just become fast on their own connection on the Wi-Fi
Direct group (`1c5437f`). So, expected: the video would start well under a second if the player read it the same way.

**What it did.** A's existing HTTP server (`LanFileServer`) hands out its address on the Wi-Fi Direct group when there
is one; a new `MediaChannel.isDirect(url)` says whether an address is on that group; B's player reads that address
directly (`VideoSource.Url`) even when the pairing link is fast. An address that does not answer leaves the video on
the pairing link. After the drop the file still comes over the pairing link in pieces.

**Measured (17:58 to 18:01, 7 loads).** It works. But it is not faster: first frame 1.6 to 3.4 s on every lift,
**second lifts included** (on the Nearby path a second lift took 0.33 to 0.43 s), 2 of 7 jumped once, card lag 39 to
56 ms. On every second lift B's half showed the video's thumbnail first, then played.

**What it showed, and why parked.** Even the tiny reachability check on the direct connection took 83 to 261 ms to
answer. So the delay of about 0.1 to 0.25 s per request comes from the **Wi-Fi Direct link itself**, not from Nearby. A
different connection does not remove it; **fewer requests** do, which is what the read-ahead and the keeping of pieces
already achieve. And this path keeps nothing between lifts, because what is kept lives in the pairing link's channel,
which this path goes around. Throughput was not measured; it might still matter for moving whole files over the group.

---

## 6. Where things stand now

### 6.1 A slide of a playing video, end to end

This is the path on `main` on 12 Nov 2026, for the normal case: phones paired and lined up, link on Wi-Fi Direct.
Class names are where to look in the code; almost all of the orchestration is in `ShelfViewModel`
(`feature/shelf/presentation`, about 1,500 lines).

```
 Phone A (sender)                          links                          Phone B (receiver)
 ════════════════                                                         ══════════════════
 0. link upgraded to Wi-Fi Direct; card connection moves onto the group
 1. tap: video plays in its card
 2. PlaybackHint ─────────────────────────(card connection)─────────────▶ 3. read-ahead: walk the boxes,
    shareIndex: header + index only        ◀──── RangeRequest (Nearby) ──     read the whole index, keep it
 4. lift: DragStart, PlaybackOffer ───────(card connection)─────────────▶ 5. follower; load muted, aimed ahead
    share: whole file open                                                 6. player reads: kept pieces first,
    serves pieces ─────────────────────────── STREAM pieces (Nearby) ──▶      then 1 MB pieces, 3 ahead
    PlaybackClock every 250 ms ───────────(card connection)─────────────▶ 7. speed up / slow down / hold / jump
    DragMove(s) ──────────────────────────(card connection)─────────────▶ 8. draws its half, evenly
 9a. pull back: DragCancel, back to index-only ─────────────────────────▶ 9b. keeps what it read
 10. drop: steady exit, DragCommit ──────────────────────────────────────▶ 11. sound on, speed 1
     its own video stops                   ◀───────────── FileRoute(FETCHING)
 12. serves missing pieces, sends SHA-256  ◀──── RangeRequest / Digest ──     fetch gaps (held while a card
                                                                              is in the air), check, keep
     removes the item                      ◀───────────── FileRoute(FETCHED)   player goes on from the copy
```

**0. The link.** After pairing, the link runs on Bluetooth. Nearby upgrades it to Wi-Fi Direct (1.7 to 12.6 s after
pairing on 11 Nov). `onBandwidthChanged` makes the media channel fast (`isFast`), the dot turns cyan, and
`NearbyPeerLink.offerLan()` moves the card connection (`LanMessageLink`) onto the Wi-Fi Direct group. From then on
every slide message goes over that TCP connection; pieces and files stay on Nearby.

**1. A plays the video in its card.** A tap loads it into `ExoVideoEngine` from the local file. One player; the
picture moves between surfaces without stopping.

**2. A says which video plays.** `hintPlaying()` sends `PlaybackHint(itemId, sizeBytes)` and calls
`MediaChannel.shareIndex()`: until a lift, B may read only the parts that are not pictures or sound.

**3. B reads the index ahead.** `NearbyMediaChannel.prefetch()` walks the MP4 boxes from byte 0 (`Mp4Boxes.kt`), reads
the whole `moov` box wherever it is, and keeps the pieces. It waits while a card is in the air or a player waits. About
1 s for video 2 (3 pieces), 0.55 to 1.1 s for video 14.

**4. The lift on A.** `DragStart`, then right behind it `PlaybackOffer` with A's position read at the moment of
sending. `share()` opens the whole file to B. While the card is in the air, A sends `PlaybackClock` every 250 ms and
the card's moves, stamped with the time of their frame, batched as `DragMoves` when the connection is busy.

**5. B starts following.** `followPeerVideo()` creates a `PlaybackFollower` and loads the video **muted** as
`VideoSource.Remote` (pieces over the pairing link), aimed ahead of A's position: 2 s for a first lift, or 0.6 s for a
second lift whose video had played on B before the pull-back. (If the link is still on Bluetooth and both phones share
a Wi-Fi network, B reads over the router from `LanFileServer` instead, and moves onto the pairing link as soon as it is
fast; if neither way works, the card crosses with its still picture.)

**6. The player reads.** `RemoteDataSource` → `MediaChannel.open()` → `PiecedStream`. Parts already on B (the index,
and pieces kept from an earlier lift) are read from kept pieces with no request. Everything else is asked for in 1 MB
pieces, three ahead, each drained into a file as it arrives and never cancelled. The first request at a first lift
fetches the start of the picture data, which the index-only rule kept out of the read-ahead (213 ms when measured).

**7. B keeps in step.** On every `PlaybackClock`, the follower estimates where A is now and B applies its answer:

| B compared with A | Action |
|---|---|
| Within 25 ms | normal speed |
| Ahead by up to 300 ms | slow down (not below 0.85×) |
| Ahead by more | hold the frame until A gets there |
| Behind by up to 1.5 s | speed up (at most 1.5×), muted |
| Behind by more | jump: aim 2 s ahead the first time, then 1.25 × the measured jump + 150 ms (0.3 to 3 s) |

**8. B draws its half of the card.** `MovePlayback` plays the moves back evenly, a measured moment behind and
predicted forward, in B's own millimetres (`peerMmScale`). B clips the card at its edge; A clips its part at its own.

**9. If the card is pulled back.** A sends `DragCancel`, goes back to index-only sharing, and its video never stopped.
B releases its player. If A's video still plays, B keeps everything it read (`keptForRelift`) and notes whether its
video had played (`playedBeforePullBack`), so the next lift can start in a fraction of a second.

**10. The drop on A.** The card leaves at a steady speed until it is off screen, then `DragCommit` goes. The item is
hidden (`SentItems`), and A's own video stops and its player is released.

**11. B takes over.** `takeOverPeerVideo()`: following stops, speed 1, sound on. The landed card keeps playing with a
small progress ring in its corner. B answers `FileRoute(FETCHING)`.

**12. The file.** B's `fetch` asks only for the missing parts, three pieces at a time, only when its player is not
waiting (at most 2 s), and no new piece while any card is in the air (`holdFetches`). A sends the file's SHA-256. B puts
the file together, checks it, copies it to Downloads, calls `settle` so its player goes on from that copy at the next
piece boundary (no reload), adds it to the shelf and answers `FileRoute(FETCHED)`. Only then does A remove the item and
let go of the file.

### 6.2 Today's timings

| What | Value | Source |
|---|---|---|
| Card lag (median per slide) | 26 to 66 ms after `1c5437f`; before: 207 to 610 ms | Reports 05, 13 |
| Index read-ahead (background) | about 1 s for video 2; 0.55 to 1.1 s for video 14 | Report 08 |
| First lift, first frame | 1.4 to 2.8 s on 11 Nov (1.7 to 4.2 s on 12 Nov, slower link) | Notes of 11 Nov, reports 00 and 14 |
| Second lift, first frame and in step | 0.30 to 1.5 s, most under 0.9 s | Report 13 |
| Jumps | 1 of 18 loads (11 Nov evening); first lifts still jumped on 12 Nov when slower than 2 s | Reports 13, 14 |
| A request on the Wi-Fi Direct link | 0.1 to 0.25 s on 11 Nov; 0.2 to 1.5 s on 12 Nov | Reports 12, 14 |
| Extra request from index-only sharing | about 0.2 s (213 ms) per first lift | Report 09 |
| Link upgrade to Wi-Fi Direct after pairing | 1.7 to 12.6 s | Report 05 |
| Fetch of the file after the drop | not measured | (LH-4 estimates only) |

What a first lift still spends its 1.4 to 2.8 s on, according to the notes of 11 Nov: each request on the Wi-Fi Direct
link takes 0.1 to 1.1 s to start coming, and the test recording keeps its pictures and its sound about 2 MB apart, so
the player hops between two places, a request each time. The player's start-up buffer is already short (0.75 s) and is
not the cause.

### 6.3 Branches and tests

- Everything above is merged into `feature/transfer-multi-files` and into `main`. On 12 Nov both phones ran the same
  code as the merged state. Unit tests passed and the app built on the merged state.
- The two parked experiments (Section 5) are on their own branches and are not part of `main`.
- The unit tests that cover the video: `PlaybackFollowerTest` (the follower), `ShelfViewModelLiveVideoTest` and
  `ShelfViewModelAirVideoTest` (the view model on both phones, with `FakeVideoEngine` and `FakeMediaChannel`),
  `FileSpansTest`, `Mp4BoxesTest`, `RangeFrameTest`, `SlideMessageCodecTest`, `LinkedSlidePeerTest`,
  `LanMessageLinkTest`, `LanOfferFrameTest`. Run them with `./gradlew testAndroidHostTest` (see `CLAUDE.md` for the JDK).
- **Not covered by unit tests**, checked on the phones only: `NearbyMediaChannel` (pieces, draining, the read-ahead,
  serving and index-only refusals), `LanFileServer`, `ExoVideoEngine`, `RemoteDataSource` and `VideoSurface`.

---

## 7. Still open, weak or untested

### 7.1 Found on 12 Nov, not fixed

1. **A hole after a pull-back.** After a pull-back, B's player keeps reading ahead until A refuses a request ("its
   card isn't lifted"). The refused piece is missing from what B kept. When the next lift starts exactly there, the
   start is slow (2.3 s) and B jumps (3.8 s ahead, then 3.3 s more buffering). Fetching the refused piece again took
   1.4 s. Seen once. No fix has been chosen yet.
2. **A slower link that day.** A piece took 0.2 to 1.5 s to open (0.1 to 0.25 s on 11 Nov). First lifts took 3.3 to
   3.9 s and then jumped about 4 s ahead. The connection was set up three times within two minutes in one session. The
   cause was not looked into.

### 7.2 Known weak points

3. **First lifts still cost 1.4 to 2.8 s** (on a normal day), and a first lift slower than about 2 s still jumps.
   Ideas from the notes, none started: A sends the frame it is on as a still picture at the lift, so B shows a picture
   at once; B reads ahead the first seconds of pictures and sound around A's playing position, not only the index.
4. **The extra request of the index-only rule** (about 0.2 s per first lift). Idea: also allow the first megabyte of
   the file before a lift, so the start of the picture data is read ahead again.
5. **Stalls after a drop:** in several tests B stalled once or twice for 1.0 to 1.8 s after the card landed. Cause
   unknown. The notes suggest reading the logs of a drop on both phones first.
6. **A lift or drop on Bluetooth with no shared Wi-Fi** (the outdoors case). A card dropped before Wi-Fi Direct is up
   is sent the slow way and stays on it. The `bb02b53` case (lifted on Bluetooth, upgraded mid-slide) was never caught
   on the phones.
7. **The `25e0f99` case** (pull back before B's first frame, then lift again) was never reproduced by hand. It is
   covered by its unit test only.
8. **Storage.** What B keeps after a pull-back stays until another video plays on A or the app restarts. For a long
   video played over the edge for a while, that can be much more than the index. LH-4 also needs about twice the
   missing size in free space during a fetch.
9. **Arrival of card moves in small bursts** (arrival gaps p95 about 95 to 100 ms) on the card connection. Not
   investigated; the even playback hides it on screen.
10. **One pair of phones.** Whether every phone lets an app open a socket on Nearby's Wi-Fi Direct group is not known.
    If one refuses, the card messages fall back to Nearby.
11. **The receiving side of the swipe-away fixes.** A send that fails while the app is closed gives no message. A video
    sent to a swiped-away phone has no arriving card. The 3-minute wait for `FileKept` is the same for any size.
12. **A black card half on A** during two-way sending: seen once on 10 Nov, cause unknown.
13. **Both phones must run the same build.** On 11 Nov both were found on an older build, so the 10 Nov test never ran
    the swipe-away fixes. Nothing checks this at pairing.
14. **Security of the fallbacks.** The shared-Wi-Fi server uses plain HTTP (no TLS), guarded by a random token per file
    and local addresses only. After a lift the whole file is open to the peer, as it always was.
15. **The sync between the two halves** has only been judged by eye and from logs. The plan's 240 fps filming of both
    screens was never done.

### 7.3 Not tested

- A video of 15 minutes or more (index over 4 MB), and a downloaded video with its index at the start. Covered only by
  the `Mp4Boxes` parsing tests.
- The phones off the home Wi-Fi (Wi-Fi on but not connected to a network): the outdoors case.
- A lift a second or two after a pull-back (the exact case of `5c1e228`).
- LH-4's failure paths on the phones (drag back mid-slide, tapping the arriving card, Wi-Fi off mid-fetch), and its
  real fetch times. Its report's numbers are estimates.
- The smoother card half's measured run and tuning (C5).
- A non-MP4 video since `94c5146` (it is not read ahead and starts as before the read-ahead).

### 7.4 Parts of the original plan that were never built

From `docs/LIVE_VIDEO_HANDOFF.md` and the guide's "known limits":

- `PlaybackReady` and `PlaybackUnsupported` replies, and a **codec check** on B. A video B cannot decode fails with an
  error instead of falling back to the still card.
- A **sound cross-fade** at the drop; the sound switches from A to B at once.
- A **safety net for a silent sender:** if A disappears mid-slide, B's incoming card can stay frozen on screen
  (`docs/reference/freeze.png`).
- The Twin Simulator path with two engines (LH-1).
- LH-5 as planned (NsdManager discovery and a Ktor server). What exists instead is the small `LanFileServer`, used as a
  fallback while Nearby is on Bluetooth, and (parked) over the Wi-Fi Direct group.
- Part B of the smoother-card-half plan as written (see Section 4.3).

### 7.5 Planned next: the transfer coordinator

From the notes of 11 and 12 Nov, not started:

- **Today** the shelf screen's `ShelfViewModel` (about 1,500 lines) decides how a file travels, starts the send or the
  receive, tracks hidden items and reports errors. The 11 Nov fixes added more to it.
- **The plan:** one piece of code that lives as long as the app and owns every file moving in or out. The screen only
  says "send this item" or "the peer let go of this card", and draws what the coordinator reports.
- **With it:** a version check at pairing ("update the other phone"), and a "still saving" signal from the receiver to
  replace the fixed 3-minute wait.
- **Size:** about a day, with a full retest, on its own branch.

The notes list the order of the next steps as: phone-test `25e0f99` (done on 12 Nov, see 4.7), look at the stalls after
a drop, win back the 0.2 s of the index-only rule, test what was not tested, then the coordinator.

---

## 8. Glossary

| Word | Meaning in this project |
|---|---|
| A, phone A, sender | The phone the video plays on first, and where the card is lifted |
| B, phone B, receiver | The phone next to it, which shows the other half of the card and gets the file |
| Peer | "The other phone", seen from either one |
| Shelf | The app's main screen: a grid of file cards |
| Card | One file on the shelf; the thing that is lifted and slid |
| Lift | A long press that picks the card up; for a playing video, the moment B starts loading it |
| Slide | Dragging a lifted card over the edge toward the other phone |
| B's half (of the card) | The part of the card that has crossed and is drawn on B |
| Drop, let-go, land, commit | Letting go past the edge: the card lands on B and the file is sent (`DragCommit`) |
| Pull back, spring back, cancel | Letting go on A's side: nothing is sent (`DragCancel`) |
| First lift / second lift | A lift with nothing of the video on B yet / the same card lifted again after a pull-back |
| Grab / catch | The air-gesture version of lift / drop (fist / open hand), since `23cb2ec` |
| Nearby Connections | Google's library that finds the other phone and carries all data between the phones |
| Bluetooth phase | The first seconds of a Nearby link, slow (about 1 to 2 Mbit/s) |
| Wi-Fi Direct, the group | The direct Wi-Fi network Nearby opens between the two phones after the upgrade; no router |
| Shared Wi-Fi, home Wi-Fi, router path | A normal Wi-Fi network both phones joined; data goes through the router |
| Link quality | Nearby's rating: 1 (BLE), 2 (classic Bluetooth), 3 (Wi-Fi). "Fast" means 3 |
| Payload (BYTES, FILE, STREAM) | One unit Nearby carries: a small message, a whole file, or a readable stream |
| Card messages, slide messages | `DragStart`, `DragMove(s)`, `DragCommit`, `DragCancel`, and the playback messages |
| Card connection | The TCP connection the card messages use since `b165af6` / `1c5437f` (`LanMessageLink`) |
| Card lag | How late B's card moves arrive, median per slide (logged by `AirSlide`) |
| Media channel | `MediaChannel`: reading the peer's file, sharing a file, fetching and keeping pieces |
| Piece | 1 MB of the file, asked for with one request and sent as one STREAM payload |
| Drain | Copying an arriving piece into a file at once, so Nearby never waits for the player |
| Kept piece | A piece that arrived whole and is kept on B for reuse |
| Round trip, request cost | Time from asking for a piece until its bytes start coming |
| Share / share index | Let the peer read the whole file / only its header and index |
| Read-ahead, prefetch | B reading a playing video's index before its card is lifted (`22acb9f`, `cc49273`) |
| Fetch | After the drop, B asking only for the missing parts of a video it played (`b02c846`) |
| Pull | B fetching any file in pieces on Wi-Fi (`3be3def`) |
| `FileRoute` | B's answer after the drop: `FETCHING`, `FETCHED`, `SEND` or `CANCELLED` |
| `FileKept` | B's word that a sent file is saved and on its shelf (`97c6009`) |
| MP4, box | The video file format, made of boxes, each with a type and a length |
| `moov`, index | The box that says where every frame and sound sample is; needed before anything plays |
| `mdat` | The box with the pictures and sound themselves |
| `ftyp` | The first box: "this is an MP4" |
| ExoPlayer, Media3 | Android's video player library; wrapped as `ExoVideoEngine` |
| `VideoEngine` | The app's player interface, in `:core:media` |
| `TextureView`, `VideoSurface` | Where the video is drawn; moves and clips with the card |
| First frame | Time from B starting to load until it can show a frame |
| In step | Both halves show the same moving video |
| Follower | `PlaybackFollower`: keeps B's video on A's frame |
| Target | The follower's estimate of where A's video is now |
| Aim ahead, lead | Starting or jumping ahead of A by about how long the load takes |
| Catch up by speed | Playing faster (muted) to close a gap |
| Hold | Staying on a frame until A gets there (a still picture) |
| Jump, seek, second load | Dropping what was loaded and loading again further ahead |
| "Landed during load" | A slide dropped on B before B's first frame; slower to start |
| `PlaybackOffer` | Sent with the lift: the video's id, size, position and whether it plays |
| `PlaybackClock` | A's position, every 250 ms while the card is in the air |
| `PlaybackHint` | "This video plays on me now", before any lift (`22acb9f`) |
| `LanFileServer` | A's small HTTP range server, used over the shared Wi-Fi while Nearby is on Bluetooth |
| Make before break | The sender keeps playing until the card is committed |

---

## 9. Index of related documents

| Document | What it holds |
|---|---|
| [`LIVE_VIDEO_HANDOFF.md`](LIVE_VIDEO_HANDOFF.md) | The original plan (`0bd1533`): study of the reference video, stack, messages, state machine, phases LH-0 to LH-5, risks; plus the status of 25 Oct and the five Nearby lessons (`89c7a2c`) |
| [`LIVE_VIDEO_GUIDE.md`](LIVE_VIDEO_GUIDE.md) | The developer guide (`08c70f9`, updated in `7a5a3cf`): the journey step by step, pieces in depth, the follower, surfaces, threads, every tunable number, debugging on two phones. Describes the state of 29 Oct |
| [`reports/2026-10-29-live-video-one-transfer-report.md`](reports/2026-10-29-live-video-one-transfer-report.md) | LH-4 (`b02c846`): what changed, decisions, estimated performance, risks, tests |
| [`reports/2026-10-29-live-video-one-transfer-before-after.md`](reports/2026-10-29-live-video-one-transfer-before-after.md) | LH-4 as a learning guide, with lessons and exercises |
| [`get-smoother-card-half-plan.md`](get-smoother-card-half-plan.md) | Plan for the smoother card half: Part A (C0 to C5) and the unbuilt Part B (the video half) |
| [`reports/2026-11-01-smooth-card-half-report.md`](reports/2026-11-01-smooth-card-half-report.md) | What C0 to C4 built, expected effects, risks, tests |
| [`AIR_GESTURE.md`](AIR_GESTURE.md) | Air gestures, including how a grabbed video travels |
| [`MULTI_FILE_TRANSFERS.md`](MULTI_FILE_TRANSFERS.md) | Several files at once: why it was slow, the queue, board, stop and notifications |
| [`MULTI_FILE_LAG_FIXES.md`](MULTI_FILE_LAG_FIXES.md) | Every fix tried for the multi-file lag on 6 Nov, with a comparison |
| [`CHECKPOINT.md`](CHECKPOINT.md) | Session checkpoint, last updated 1 Nov (before the November fixes) |
| [`reports/fix/00-2026-11-11-benchmark-comparison.md`](reports/fix/00-2026-11-11-benchmark-comparison.md) | Every video slide of 11 Nov, grouped by build, with a comparison and how far to trust it |
| [`reports/fix/01-2026-11-10-keep-sent-items-hidden-report.md`](reports/fix/01-2026-11-10-keep-sent-items-hidden-report.md) | `2e088ad`: sent items stay hidden after a reopen |
| [`reports/fix/02-2026-11-10-receive-with-screen-closed-report.md`](reports/fix/02-2026-11-10-receive-with-screen-closed-report.md) | `faa08ca`: receiving with the screen closed |
| [`reports/fix/03-2026-11-10-confirm-file-kept-report.md`](reports/fix/03-2026-11-10-confirm-file-kept-report.md) | `97c6009`: a send is done only on `FileKept` |
| [`reports/fix/04-2026-11-11-lift-before-wifi-report.md`](reports/fix/04-2026-11-11-lift-before-wifi-report.md) | `bb02b53`: a video lifted on Bluetooth moves onto the pairing link |
| [`reports/fix/05-2026-11-11-card-lag-during-video-report.md`](reports/fix/05-2026-11-11-card-lag-during-video-report.md) | `b165af6` and `1c5437f`: card messages on their own connection |
| [`reports/fix/06-2026-11-11-video-aim-ahead-report.md`](reports/fix/06-2026-11-11-video-aim-ahead-report.md) | `e85dbc4` (parked): learnt aim for the first load |
| [`reports/fix/07-2026-11-11-video-index-prefetch-report.md`](reports/fix/07-2026-11-11-video-index-prefetch-report.md) | `22acb9f`: index read-ahead |
| [`reports/fix/08-2026-11-11-fetch-exact-index-report.md`](reports/fix/08-2026-11-11-fetch-exact-index-report.md) | `1850d4e` and `cc49273`: the exact index from the MP4 boxes |
| [`reports/fix/09-2026-11-11-index-only-before-lift-report.md`](reports/fix/09-2026-11-11-index-only-before-lift-report.md) | `94c5146`: header and index only before a lift |
| [`reports/fix/10-2026-11-11-keep-index-on-pull-back-report.md`](reports/fix/10-2026-11-11-keep-index-on-pull-back-report.md) | `5c1e228`: keep what was read on a pull-back |
| [`reports/fix/11-2026-11-11-catch-up-before-jumping-report.md`](reports/fix/11-2026-11-11-catch-up-before-jumping-report.md) | `94c829d`: catch up by speed up to 1.5 s, jump 2 s ahead |
| [`reports/fix/12-2026-11-11-video-over-wifi-direct-report.md`](reports/fix/12-2026-11-11-video-over-wifi-direct-report.md) | `314b6a7` (parked): the video over its own connection on the group |
| [`reports/fix/13-2026-11-11-aim-closer-on-second-lift-report.md`](reports/fix/13-2026-11-11-aim-closer-on-second-lift-report.md) | `733dbb0`: a second lift aims 0.6 s ahead |
| [`reports/fix/14-2026-11-11-aim-closer-only-after-playing-report.md`](reports/fix/14-2026-11-11-aim-closer-only-after-playing-report.md) | `25e0f99`: the closer aim only after the video had played; the 12 Nov phone test |
| [`PLAN.md`](PLAN.md) | The whole app's plan and phases, for product context |
