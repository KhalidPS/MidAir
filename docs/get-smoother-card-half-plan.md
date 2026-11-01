# Get a smoother card half: plan

**Goal:** any card (a photo, a PDF, a paused or playing video) sliding from phone A's edge onto phone B should feel
like **one object across two screens**, as smooth as dragging it on a single phone. Nothing should give away that
the two halves are drawn by two different devices. Nothing here is built yet.

- **Part A, one card across two screens:** every card type. Done first: it is what the user feels on every slide.
- **Part B, the video half:** what is left for a playing video (its picture, not its motion). After Part A.
- **Branch (when started):** `feature/smooth-card-half`, off `feature/live-video-one-transfer`.
- **Builds on:** the slide protocol (`DragMove` in millimetres, `docs/PLAN.md`), the line-up (clock comparison and
  screen geometry), the live video handoff (`docs/LIVE_VIDEO_GUIDE.md`) and LH-4
  (`docs/reports/2026-10-29-live-video-one-transfer-report.md`).

---

# Part A: one card across two screens

## A1. Why a plain card doesn't feel like one object today

What the user noticed on 2026-10-29, with a photo and a non-playing video: the slide from A's edge onto B works, but
it feels like a handover between two phones, not one card.

| # | What you see | Cause (code) | Known so far |
|---|---|---|---|
| 1 | B's half moves in small uneven steps while A's half follows the finger perfectly | A sends one `DragMove` per frame (`ShelfViewModel.mirrorMove`), but they arrive bunched and irregular over the air. B sets each arrival as the new target (`IncomingSlideState.onMoved`) and eases toward it (`follow()`, `FollowSmoothingSeconds` 30 ms). Uneven arrival → uneven motion | Gaps over 300 ms are logged (`Card messages paused`, 2–7 per run on 2026-10-25); smaller unevenness isn't measured yet |
| 2 | Early slides lag more | Nearby starts every link on Bluetooth and upgrades to Wi-Fi Direct after a few seconds. Plain cards don't wait for Wi-Fi (only live video does, `isFast`) | Bluetooth: tens to hundreds of ms, very uneven |
| 3 | B's half is a little bigger or smaller, or moves at a different speed | Sizes and moves are in millimetres from each phone's reported density (`displayMetrics.xdpi/ydpi`, `PlatformTransferDataModule`). Some makers report it slightly wrong | Not checked on the two Redmis |
| 4 | B's half is less fluid | Note 10 Pro at 120 Hz, Note 8 at 60 Hz | Hardware; can only make sure B never drops its own frames |
| 5 | The card changes pace at the edge when let go | A's card flies out with its own exit animation, B's lands with its own (`IncomingSlideState` arrival, `Motion.exit`) | Visible on let-go |

**Targets** (to confirm on the phones):

| | Now | Target |
|---|---|---|
| Unevenness of B's half (frame-to-frame speed change while the finger moves steadily) | not measured | same as a card dragged on one phone |
| Delay of B's half behind the finger | ~one-way trip + 30 ms smoothing | not noticeable (predicted away) |
| Size and speed mismatch between halves | unknown | < 2% |
| Let-go | two separate animations | one motion, same speed across the edge |

What can't reach zero: the physical gap between the screens, the 60 vs 120 Hz difference, and the trip between the
phones. The plan hides the last one with prediction.

## A2. Phases

### C0. Measure (half a day)

- Log, on B, for every `DragMove`: arrival time, the time between arrivals, and the one-way delay (the sender's
  timestamp, see C1, corrected by the clock offset from the line-up). Summarise per slide:
  `Moves: N, gaps p50/p95/max, delay p50/p95` (tag `AirSlide`).
- Log the link quality at the lift (Bluetooth or Wi-Fi).
- Check the densities: a debug screen that draws a 50 mm line on each phone; measure it with a ruler.
- Film both phones at 240 fps during a steady slide: count frames where B's half stands still or jumps.
- Save as `build/device-logs/card-baseline.txt` and the video next to it.

### C1. Play moves back evenly: timestamps, interpolation, prediction (2–3 days)

The technique online games use to show another player moving smoothly.

- **Timestamp every move:** add `atMillis` (the sender's clock when the frame was drawn) to `DragMove`. B converts it
  to its own clock with the offset already measured during the line-up (`peerClockAheadMillis`), refreshed by the
  `Ping`/`Pong` sent at every `DragStart`.
- **Keep a short history** of the last moves (time, position, velocity) in `IncomingSlideState`.
- **Draw at a time, not at an arrival:** every frame, B computes where the card was at `now − delay`, where the delay
  is small and fixed (about the p95 of the arrival unevenness, e.g. 30–50 ms), interpolating between the two moves
  around that time. Uneven arrival no longer shows: the card moves at an even pace.
- **Predict the delay away:** then project that position forward by `delay + one-way trip` with the move's velocity
  (what `predictedAhead` does today, but on an even signal), so B's half isn't behind the finger.
- **Gaps:** if no move covers `now − delay`, keep going along the last velocity, slowing over ~150 ms (so a real stop
  doesn't overshoot), and ease back onto the real path when moves resume. Never snap.
- Pure math in a small testable file (`MoveTimeline`: add, positionAt, with interpolation and capped extrapolation);
  `IncomingSlideState.follow()` only reads it per frame (inside the existing `withFrameNanos` loop, no recomposition).
- Protocol: `atMillis` with a default for older builds (they then fall back to arrival time, as today).

### C2. A fast link before the card moves (1 day)

- Show the link's state on the shelf (a subtle mark while it is still on Bluetooth).
- While still on Bluetooth: send moves at half the rate (they cost more there), and let C1's delay grow to match the
  measured unevenness, so the card stays even, just a little more behind.
- Check whether the Wi-Fi upgrade can be asked for earlier (Nearby's connection options) and how long it takes on
  the two phones.

### C3. Same millimetres on both phones (1–2 days)

- **From the line-up swipe:** the finger crosses both screens at a nearly steady speed. Each phone already measures
  its crossing speed in mm/s (`EdgeCrossing.acrossMmPerS`). If both densities were right, the two speeds would match;
  their ratio estimates how far off one phone's density is. Store a correction factor per paired phone (next to
  `LinkGeometry`), apply it in `ScreenDensity`.
- **Fallback:** a one-time ruler screen in settings ("drag until the line is 50 mm"), if the swipe estimate is too
  noisy.
- Tests: the correction from two crossings; a card's size in px on B with and without it.

### C4. One motion through the let-go (1–2 days)

- At the let-go, both phones run **the same fling** in millimetres: the same starting velocity (sent in `DragCommit`
  already), the same decay or spring, the same duration. A's exit and B's arrival become one curve cut at the edge.
- B's landing into its shelf slot starts from that motion (velocity carried into the spring), not from rest.
- Tests: A's exit and B's arrival positions agree at the edge for the same commit.

### C5. Measure again and tune (half a day)

Repeat C0. Tune the playback delay, the extrapolation decay and the move rate. Fill in the "Now" column above.

## A3. Order and effort

| Phase | Fixes | Effort | Gain |
|---|---|---|---|
| C0 Measure | — | ½ day | Shows which cause hurts most |
| C1 Even playback + prediction | #1, part of #2 | 2–3 days | Largest: B's half moves like A's |
| C2 Link check | #2 | 1 day | Early slides as smooth as later ones |
| C3 Density calibration | #3 | 1–2 days | Halves the same size and speed |
| C4 One let-go | #5 | 1–2 days | No change of pace at the edge |
| C5 Tune | — | ½ day | |

About 6–9 days. C1 is the heart of it.

---

# Part B: the video half

After Part A, the card itself moves as one. What is left for a **playing** video is its picture: the right frame,
at once, also when slid back and forth. Phase S3 below (motion during gaps) is mostly covered by Part A's C1; what
remains of it is S3b (smaller pieces while the card is in the air).

## 1. Where the lag comes from today

Measured on a Redmi Note 10 Pro (sender) and Redmi Note 8 (receiver), 2026-10-25.

| # | What you see on phone B | Cause (code) | Measured |
|---|---|---|---|
| 1 | A still picture first, then the video starts | B loads the video from nothing at the offer: opens it over Nearby, reads the header, jumps to the index (at the **end** of `share_sample2.mp4`), then buffers 0.75 s before the first frame (`ShelfViewModel.followPeerVideo`, `ExoVideoEngine.newPlayer`) | ~1.8 s from the offer |
| 2 | Each slide back and forth restarts the video | On `DragCancel`, B calls `stopVideo()` (releases the player) and `media.forget(id)` (deletes the pieces). The next lift starts from zero | 1.8 s every time |
| 3 | B's half lags or jumps behind the finger | `DragMove` and video pieces share one Nearby connection with no priorities. During a gap, `IncomingSlideState.follow()` eases toward a target that has stopped moving, then catches up | gaps of 300–440 ms while pieces flow |
| 4 | The halves show different frames for a moment | The first load lands off target; `PlaybackFollower` speeds up or jumps to close the gap | ~1–2.5 s to settle |
| 5 | The still picture shown meanwhile is from the moment of the lift | `DragStart.thumbnail` is captured once, at the lift | stale by however long the drag took |

**Targets** (to confirm on the phones; numbers are estimates until measured):

| | Now | Target |
|---|---|---|
| Lift → first frame on B | ~1.8 s | ≤ 0.6 s (usually before the card reaches the edge, so never seen) |
| Back on B after a drag-back | 1.8 s (full reload) | ≤ 0.2 s |
| Longest pause in B's card motion | 300–440 ms | no visible pause (motion keeps going during gaps) |
| Same frame on both halves | 1–2.5 s | < 0.5 s |
| Picture before the first frame | lift-time thumbnail | a frame from the last ~200 ms |

What can't reach zero: starting a decoder (~0.2–0.5 s on a Note 8) and the trip between phones (~10–50 ms over
Wi-Fi Direct). The plan **hides** those: it starts earlier, keeps things warm, and predicts.

---

## 2. Phases

Each phase is one or more commits with its tests, and is useful on its own. Order: cheapest and biggest gain first.

### S0. Measure first (half a day)

Without numbers we can't tell which fix helped.

- New log lines (`AirVideo` tag):
  - receiver: `Offer → first frame N ms`, from the offer to the player's first `ready` (`ExoVideoEngine` already logs
    states; add the offer time);
  - receiver: `Card motion gap N ms` when two consecutive `PeerCardMoved` are more than 50 ms apart (in
    `ShelfViewModel`, next to `DragMove` handling; the link already logs `Card messages paused` on its side);
  - receiver: `In step after N ms` when `PlaybackFollower` first reports an error under `ToleranceMs`.
- One baseline run on the phones: a single slide, then three slides back and forth. Save as
  `build/device-logs/smooth-baseline.txt`.

### S1. Keep B's player warm on a drag-back (fixes #2), about 1 day

**What:** when the sender takes the card back, B doesn't release its player: it pauses it, mutes it, hides the card,
and keeps the pieces. If the same card comes back within `WarmMillis` (15 s), B resumes and the follower jumps to the
sender's frame: no reload. After 15 s, or when another card comes, it is released as today.

**How:**
- `ShelfViewModel`, `DragCancel`: instead of `stopVideo()` + `media.forget(id)`, move the `FollowedVideo` into a new
  `warm: WarmVideo?` (item id, follower, a `Job` that releases it after `WarmMillis`). Keep `state.playing` pointing
  at it, so `LocalCardVideo` keeps the surface alive, but hide the card as today.
- `followPeerVideo`: if `warm?.itemId == offer.itemId`, reuse it: cancel the release job, feed the offer to the
  follower, `engine.seekTo(follower.startAt(now))`, `play()`. Skip `media.forget` and `engine.load`.
- `VideoEngine`: nothing new. It already has `pause`, `seekTo` and `setVolume`.
- The sender side doesn't change: its own player never stopped.

**Memory and battery:** one paused ExoPlayer, its decoder and up to ~10 s of buffer (a few MB) for 15 s. Acceptable.
Released at once when the app goes to the background (`OnScreenHidden`).

**Tests** (`ShelfViewModelLiveVideoTest`): a card taken back keeps its player (no `release`), and its pieces (no
`forget`); the same card offered again within 15 s seeks instead of loading; after 15 s it is released and forgotten;
another card's offer releases the warm one first.

### S2. A much faster first frame (fixes #1 and #4), about 2–3 days

Four changes, each measurable on its own.

**S2a. Prefetch at the offer.** The player asks for bytes one round trip at a time: header, then the index at the
end, then the data at the position. B can ask for all three at once as soon as the offer arrives, before the player
even starts:
- `MediaChannel.prefetch(fileId, sizeBytes, around: Long)`: requests the first piece, the last piece (where the index
  often is), and the piece around byte `around`, in parallel. They are kept (LH-4 already keeps whole pieces).
- `around` is estimated as `sizeBytes × positionMs / durationMs`. That needs the duration: add `durationMs` to
  `PlaybackOffer` (the sender's engine knows it). The estimate is rough (bitrate varies), so also fetch the piece
  before it.

**S2b. Read from kept pieces.** Today `open()` always asks the other phone. With S2a, the bytes are often already
here. `NearbyMediaChannel.open` and `PiecedStream` serve a range from kept pieces when they cover it (a `KeptStream`
over the pieces, using the same span arithmetic as `assemblyPlan`), and only ask the link for what isn't covered.
This also speeds up seeks back to a part already played.

**S2c. Start playing sooner.** For a muted follower, starting on less buffer is fine, because the follower catches
up anyway:
- `VideoEngine.load(..., quickStart = true)` for `VideoSource.Remote`: `bufferForPlaybackMs` 750 → 250 ms (a second
  `DefaultLoadControl` in `ExoVideoEngine.newPlayer`, chosen per load).
- Seek to the nearest keyframe while following: `ExoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)` during
  following, back to `EXACT` at the takeover. The follower's speed control absorbs the small offset (a keyframe is
  usually ≤ 1 s away; the follower already handles that size of error).

**S2d. Learn the start lead per phone.** `StartLeadMs` is fixed at 2 s because the Note 8 took ~1.8 s to load. With
S2a–c, loads get faster, and a 2 s lead would leave B waiting ahead. Measure the load time of each follow
(`Offer → first frame`) and use a smoothed value for the next one (like the jump lead the follower already learns),
kept in memory for the session.

**Tests:** `FileSpansTest` for "covered or not" over kept pieces; `PlaybackFollowerTest` for the learnt start lead;
`ShelfViewModelLiveVideoTest`: an offer triggers a prefetch around the right byte, and follower loads use
`quickStart`; codec test for `durationMs` in the offer (with a default, so older senders still decode).

### S3. B's half follows the finger with no visible pause (fixes #3), about 2 days

**S3a. Keep moving during a gap.** Today `IncomingSlideState.follow()` eases toward the last target, and when moves
stop arriving, the card stops. Then it jumps when they resume. Instead:
- keep the last move's velocity (`DragMove.outwardMmPerS`, `alongEdgeMmPerS`; already sent and already used by
  `predictedAhead`);
- while no move has come for more than one frame, advance the target along that velocity, slowing it down over
  ~150 ms (so a real stop doesn't overshoot far);
- when the next move arrives, ease onto it with the existing smoothing, not a snap.

All in `IncomingSlideState` (presentation, `graphicsLayer` reads only), plus pure math in a small testable function
(`extrapolate(target, velocity, sinceLastMoveMs)`).

**S3b. Fewer bytes in the way while the card is near the edge.** Moves wait behind pieces on the link. While the card
is in the air (between `DragStart` and the commit or cancel), the receiver asks for **smaller pieces, one at a time**
(for example 256 KB, `PiecesAhead` 1), and switches back to 1 MB × 3 after the drop. A smaller piece blocks the link
for ~4× less time. The prefetch from S2a runs right at the offer, before the card reaches the edge.
- `MediaChannel.setPace(Pace.SLIDING / Pace.FULL)` called by the view model on `DragStart` / commit / cancel.

**Tests:** the extrapolation function (moves on during a gap, slows down, never more than N mm past the last real
position); `ShelfViewModel` sets the pace at the right moments.

### S4. A fresh picture instead of the lift-time thumbnail (fixes #5), about 1–2 days

**What:** as the card nears the edge, the sender sends a small picture of the video's **current** frame. B shows it
until its own first frame. Even when B is a little late, the picture matches.

**How:**
- `VideoEngine.snapshot(maxSide): ImageBitmap?` (common interface), implemented with `TextureView.getBitmap()` in
  `ExoVideoEngine` (the card already draws into a `TextureView`). Encoded with the existing
  `encodeWithinBudget()` (≤ 24 KB).
- New `SlideMessage.CardPicture(itemId, picture)`, sent once when the card first comes within `ApproachRangeMm` of
  the edge, and at most every 500 ms after that while B hasn't reported its first frame.
- B: `IncomingSlideState.onPicture` swaps the placeholder, with a short cross-fade.
- Optional, later: B tells A "first frame shown" (`PlaybackReady`, already planned in `LIVE_VIDEO_HANDOFF.md`), so A
  stops sending pictures.

**Cost:** one picture capture on A (a few ms, off the main thread) and ~24 KB per picture on the link.

**Tests:** codec round trip and size limit for `CardPicture`; the view model sends it once near the edge and not for a
card without a video.

### S5. Measure again, tune, document (half a day to a day)

Repeat the S0 run, fill in the "after" column of Section 1, tune `WarmMillis`, piece size while sliding and the
extrapolation decay, and update `LIVE_VIDEO_GUIDE.md` (Sections 5, 7, 11, 12) and the checkpoint. Write the report as
`docs/reports/<date>-smooth-incoming-video-report.md`.

---

## 3. Order and effort

| Phase | Fixes | Effort | Gain |
|---|---|---|---|
| S0 Measure | — | ½ day | Lets us prove each step |
| S1 Warm player | #2 back and forth | ~1 day | Large: back-and-forth becomes near instant |
| S2 Faster first frame | #1, #4 | 2–3 days | Large: video appears before the card reaches the edge |
| S3 Motion without pauses | #3 | ~2 days | Medium to large: the card never stops mid-slide |
| S4 Fresh picture | #5 | 1–2 days | Small to medium: hides what's left of #1 |
| S5 Tune and document | — | ½–1 day | |

Total: about 7–10 working days. **S1 and S2 alone** should remove most of what you feel.

---

## 4. Risks

1. **Keyframe seeks (S2c)** can land up to a keyframe interval away (often 1–2 s on phone videos). If the follower
   then needs a jump, the gain shrinks. Mitigation: measure; fall back to exact seeks if it doesn't help.
2. **Byte estimate for the position (S2a)** is rough for variable-bitrate videos. A miss only costs one extra piece;
   the player still asks for what it needs.
3. **Smaller pieces while sliding (S3b)** mean more round trips, and so a slower stream. Mitigation: the prefetch runs
   before the card reaches the edge, and the full pace comes back at the drop.
4. **Extrapolation (S3a)** can overshoot when the finger stops suddenly during a gap. Mitigation: the slow-down over
   ~150 ms and a cap in millimetres.
5. **A warm player (S1)** holds memory for 15 s. Mitigation: released on background, on another card, and on timeout.
6. **Nearby keeps surprising us** (`LIVE_VIDEO_GUIDE.md`, Section 10). Every phase is tried on the two phones before
   the next one starts.

---

## 5. Decisions to confirm

| Question | Proposal |
|---|---|
| Part A before Part B | Yes: the card's motion is felt on every slide, the video only on some |
| Add `atMillis` to `DragMove` (protocol change, with a default for older builds) | Yes |
| Playback delay on B (C1) | Start at the measured p95 of arrival unevenness, about 30–50 ms, then tune |
| Density correction from the line-up swipe, ruler screen as fallback (C3) | Yes |
| How long to keep B's player warm after a drag-back | 15 s |
| Add `durationMs` to `PlaybackOffer` (protocol change, with a default for older builds) | Yes |
| Piece size while the card is in the air | 256 KB, one at a time |
| New message `CardPicture` (S4) | Yes, after S1–S3 if a gap is still visible |
| Order | S0 → S1 → S2 → S3 → S4 → S5 |
