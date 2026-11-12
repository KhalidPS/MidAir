# 2026-11-01: Smoother card half, Part A (C0 to C4): report

| | |
|---|---|
| **Work** | Part A of `docs/get-smoother-card-half-plan.md`: every card crossing from phone A to phone B should feel like one object across two screens |
| **Branch** | `feature/smooth-card-half`, stacked on `feature/live-video-one-transfer` (stacked on `feature/live-video-handoff`; none merged) |
| **State** | C0 to C4 built and committed, 312/312 unit tests pass, debug APK builds. **Tried once on the phones without notes or logs**: the user felt it was better |
| **Decisions** | Confirmed by the user on 2026-11-01 (Section 3.6) |

---

## 1. What you won't see yet

- **No measured numbers.** The one try on the phones (2026-11-01) left an impression ("better") but no logs and no
  notes. Everything below about how much smoother it is comes from the unit tests and the design, not from a
  measurement. Section 8 lists what to note and save on the next run.
- **C5 (tuning) hasn't happened.** Every constant (playback delay bounds, how far ahead to predict, how fast a card
  slows in a gap, exit speed) is a first guess. It is tuned from the next run.
- **The ruler screen in settings isn't built.** The size correction comes from the line-up swipe only. The settings
  ruler is the fallback in case the swipe estimate turns out too noisy, which the next run's ruler measurements will
  show.
- **Part B (the video half) isn't started:** warm player on a drag-back, faster first frame, fresh picture.
- **The 60 Hz and 120 Hz difference stays.** The Note 8 shows 60 frames a second and the Note 10 Pro 120. This work
  makes sure B draws every one of its own frames at the right place; it can't give B more frames.
- **Not reviewed with `/verify`** (to save usage, as on the earlier branches).

---

## 2. The problem

What the user noticed on 2026-10-29, with a photo and a non-playing video: the slide from A's edge onto B works, but
it feels like a handover between two phones, not one card. Five causes, from the plan:

| # | What you see | Cause (before this work) |
|---|---|---|
| 1 | B's half moves in small uneven steps | Moves arrive bunched and irregular over the air; B moved toward each one *when it arrived*, so uneven arrival became uneven motion |
| 2 | Early slides lag more | Every Nearby link starts on Bluetooth and moves to Wi-Fi a few seconds later |
| 3 | B's half a little bigger or smaller, or faster or slower | Sizes are in millimetres from each phone's reported density, and phones report it slightly wrong |
| 4 | B's half less fluid | Note 8 at 60 Hz, Note 10 Pro at 120 Hz (hardware) |
| 5 | The card changes pace at the let-go | A's card ran a spring that slowed it to a stop just past its edge, then B started its landing from rest |

---

## 3. What changed

### 3.1 C0: measure (commit `d2b3caf`)

- **Every move now says when it was made:** `DragMove.atMillis`, the sender's clock. On the wire it is one short
  field (`a`); an older build leaves it out and the receiver treats it as unknown.
- **One log line per slide on the receiver** (tag `AirSlide`, from `NearbyPeerLink` and `SlideMoveTimings`):
  `<id> landed|withdrawn, link quality Q. Moves: N (K out of order), arrival gaps …, sent gaps …, late …, delay …`,
  each as `p50 / p95 / max ms`.
  - *arrival gaps*: the time between moves arriving on B.
  - *sent gaps*: how evenly A sent them.
  - *late*: how much later than the slide's quickest move each move came. It doesn't depend on the two clocks, and
    its p95 is the unevenness C1 has to hide.
  - *delay*: the one-way trip, using the clock comparison from the `Ping`/`Pong` messages.
- **The link quality at every lift**, on both phones (`3` is Wi-Fi, lower is still Bluetooth).
- **A ruler screen (debug builds only):** `adb shell am start -n com.kotlistine.transferingfilebyair/.RulerActivity`
  draws a 50 mm line across and one down from the density the phone reports. A line that isn't 50 mm means that
  phone's density is off by that much.

### 3.2 C1: play the moves back evenly (commit `8cdba51`), the heart of it

The technique online games use to show another player moving smoothly.

- **A stamps each move with the time its frame was drawn** (`SlideFrame.frameTimeNanos`), not the time the message
  went out. Frames are evenly spaced, so the stamps are too, even when sending is uneven.
- **B draws at a time, not on arrival** (`MovePlayback`): every frame, B shows the card where it was a short moment
  ago by A's clock, interpolated between the two moves around that moment. It then projects that position forward
  along the card's speed by the same moment plus the trip between the phones, so B's half isn't behind the finger.
- **That moment is measured, not fixed:** the 95th percentile of how late moves arrive, plus the time between two
  moves (so there is a move on each side of the time shown). It stays between 12 and 150 ms and is measured over the
  last 120 moves, so it grows by itself on Bluetooth and shrinks on Wi-Fi.
- **No clock comparison needed:** "now" on A's clock is worked out from the slide's quickest move. The round trip
  is only used for the forward prediction.
- **Gaps:** when no move has come for the time being shown, the card goes on along its last speed and slows down
  over about 150 ms, so a real stop doesn't overshoot far.
- **Never a snap:** whenever the path changes under the card (moves resume after a gap, a quicker move shifts the
  timing, the delay changes), the jump is taken up by a correction that fades out over 80 ms.
- **The view model no longer predicts.** It hands the screen where the card was, its speed in this screen's pixels,
  `atMillis` and the one-way trip. All per-frame work stays in the frame loop (no recomposition).

### 3.3 C2: the link before the card moves (commit `8cdba51`)

- **A dot on the devices icon** at the bottom left of the shelf: amber while the link is still on Bluetooth, cyan
  once it's on Wi-Fi (`ShelfState.isLinkSlow`, from the new `MediaChannel.isFastUpdates`).
- **Fewer moves on Bluetooth:** every other frame is sent. B's playback fills in between. The first move and the
  last one always go.
- **The playback delay follows the link by itself** (C1), so Bluetooth slides stay even, just a little more behind.
- **Not changed: Nearby's connection type.** The more aggressive setting (`DISRUPTIVE`) would move to Wi-Fi sooner,
  but it may take the phone off its home Wi-Fi network, which the live video's Wi-Fi path needs. It stays on the
  default.

### 3.4 C3: the same millimetres on both phones (commit `deb3dff`)

- **From the line-up swipe:** the finger crosses both screens at about the same speed, and each phone measures that
  speed in its own millimetres. If both densities were right, the two speeds would match; their ratio
  (`SlideLink.peerMmScale`) says how far one phone's millimetres are from the other's.
- **The receiver turns everything the peer measured into its own millimetres:** card size, position, speeds. The
  line-up itself uses it too, so the gap and the vertical offset come out in this phone's millimetres.
- **Kept with the paired phone** (`PeerMemory`), and logged: `Lined up: gap … mm, offset … mm, peer mm x S`.
- **Guarded:** the ratio is only used when both crossings are over 40 mm/s and the two speeds differ by under 10%.
  Otherwise it stays 1: a bigger difference is more likely the finger speeding up or slowing down than a density
  error.

### 3.5 C4: one motion through the let-go (commit `8cdba51`)

Built differently from the plan's bullets, more simply. The plan had both phones run the same fling. But B already
sees every frame of A's exit through the streamed moves, so it was enough to make A's exit steady and have B carry
on from what it shows.

- **A (`SlideState.flyOut`):** a sent card now leaves at a steady speed (its release speed, at least 1,400 dp/s,
  with sideways drift dying out over 80 ms) until it is fully off screen. Only then does the commit go. Before, a
  spring carried it to a stop just past the edge, so B saw it slow down and stop before landing.
- **B (`IncomingSlideState.onArrived`):** the landing starts from the speed the card was drawn moving. A `momentum`
  offset starts at that speed and springs back to 0 by the slot, so the card keeps its speed across the edge, then
  curves into its slot. When B never drew the card (a fast flick), it uses the speed in the commit message.

### 3.6 Decisions (confirmed by the user, 2026-11-01)

| Question | Decision |
|---|---|
| Part A before Part B | Yes, image cards included |
| Add `atMillis` to `DragMove` | Yes, with a default for older builds |
| Playback delay | From the measured p95 (it came out as p95 + the time between moves; see 3.2) |
| Density correction | From the line-up swipe, with a ruler screen as the fallback (the fallback isn't built yet) |
| Testing | Build every phase, then test once on the phones |

### 3.7 Files

| File | What |
|---|---|
| `core/transfer/domain/.../SlideMessage.kt` | `DragMove.atMillis` |
| `core/transfer/data/.../SlideMessageDto.kt`, `Mapper`, `Validation` | The wire field `a`, mapped and checked (never negative) |
| `core/transfer/domain/.../Crossing.kt` | `SlideLink.peerMmScale`, `fromPeer()`, `incomingCardVelocity()`, the scale in `incomingCardPosition` and `arrival` |
| `core/transfer/domain/.../LineUp.kt` | `peerMmScale()` from the two crossings, applied to the peer's crossing |
| `core/transfer/domain/.../MediaChannel.kt` | `isFastUpdates` (a flow of `isFast`) |
| `core/transfer/nearby/.../SlideMoveTimings.kt` | C0's per-slide summary (pure, tested) |
| `core/transfer/nearby/.../NearbyPeerLink.kt` | Feeds the summary, keeps clock samples, logs `AirSlide` |
| `core/transfer/nearby/.../NearbyMediaChannel.kt` | Exposes the link quality and `isFastUpdates` |
| `core/transfer/nearby/.../PeerMemory.kt` | Stores and logs `peerMmScale` |
| `feature/shelf/presentation/.../slide/MovePlayback.kt` | C1's playback (pure, tested) |
| `feature/shelf/presentation/.../slide/IncomingSlide.kt` | Draws from the playback each frame; carries the drawn speed into the landing |
| `feature/shelf/presentation/.../slide/SlideState.kt` | The steady exit (`flyOut`) |
| `feature/shelf/presentation/.../slide/SlideGeometry.kt` | `SlideFrame.frameTimeNanos` |
| `feature/shelf/presentation/.../ShelfViewModel.kt` | Frame-time stamps, half the moves on Bluetooth, raw positions and speeds to the screen, sizes in this phone's millimetres, the let-go speed |
| `feature/shelf/presentation/.../ShelfEvent.kt`, `ShelfState.kt`, `ShelfScreen.kt` | The new event fields, `isLinkSlow`, the wiring |
| `feature/shelf/presentation/.../components/ShelfBottomBar.kt` | The link dot |
| `app/src/debug/.../RulerActivity.kt`, debug manifest | The ruler screen |

About 1,150 lines added and 80 removed across 28 files (tests included).

---

## 4. What it should do (to be confirmed on the phones)

| | Before | Expected now |
|---|---|---|
| Unevenness of B's half while the finger moves steadily | Followed each arrival: uneven | Even, as on one phone, as long as the link's lateness stays under 150 ms |
| B's half behind the finger | One-way trip + 30 ms smoothing, roughly predicted | Predicted away from an even signal |
| Size and speed mismatch between halves | Unknown | Under 2% when the swipe estimate holds (the ruler tells) |
| Let-go | Slowed to a stop at the edge, then a landing from rest | One motion: same speed across the edge, then curving into the slot |
| Early slides (Bluetooth) | Most uneven | Even, a little more behind; the amber dot says why |

The user's first impression on 2026-11-01: **better.**

### What it costs

- **Wire:** about 9 bytes more per move (the timestamp), and half as many moves on Bluetooth.
- **CPU:** a few additions and a search over at most 64 moves per frame on B; nothing on the main thread beyond what
  the frame loop already did.
- **Memory:** at most 64 moves and 120 lateness values held.

---

## 5. Risks

1. **Prediction can overshoot when the finger stops suddenly.** B predicts by the delay plus the trip (capped at
   200 ms), and A's reported speed is smoothed over 60 ms, so a sudden stop can push B's half a few millimetres past
   and back. That is what to watch for on the run; the cap and the prediction share are the C5 knobs.
2. **A very uneven link makes B run further behind.** The delay goes up to 150 ms, so with the prediction B stays in
   step at steady speeds, but direction changes show later.
3. **The density estimate may be noisy.** Fingers speed up and slow down. The 10% guard rejects the worst cases, but
   a wrong correction under 10% would make the halves *less* alike. The ruler measurements will say whether to
   trust it or build the settings ruler.
4. **The steady exit changes how a send looks on A:** no more spring easing at the end, just a straight flight off
   screen. If it feels abrupt, the minimum speed is the knob.
5. **Older builds** on one of the phones send moves without a timestamp. B then plays them back by arrival with the
   smallest delay, which is close to the old behaviour, not the new one.

---

## 6. Tests

312 unit tests, all passing (`./gradlew testAndroidHostTest`); there were 285 before this branch (27 new, 1
rewritten).

| Suite | New tests |
|---|---|
| `MovePlaybackTest` (8) | A steady card plays back where it really is and moves the same distance every frame, however unevenly its moves came; the delay follows the measured lateness and stays within bounds; during a gap the card slows and stops within its momentum; moves resuming after a gap never snap and land back on the real path; an older sender without timestamps still plays; a new card starts clean |
| `SlideMoveTimingsTest` (8) | Gaps, lateness independent of the clocks, the delay with the clock lead, an older sender, moves out of order, a new lift, the log line |
| `SlideMessageCodecTest` (+1, +1 case) | A move without a timestamp reads as untimed; a negative timestamp is rejected |
| `LineUpTest` (+3) | Disagreeing densities are corrected on both phones; implausible differences and slow swipes leave the scale at 1 |
| `CrossingTest` (+2) | The peer's millimetres turned into the receiver's; the card's speed points the way it travels on the receiver |
| `ShelfViewModelSlideTest` (+5, 1 rewritten) | Moves stamped by frame time; every other move on a slow link, the last always; the slow-link state; the card in this screen's millimetres; the let-go speed handed to the landing; the moved event carries position, speed, timestamp and trip |

Not unit-tested: the Compose side (the frame loop drawing from the playback, the landing's momentum curve, the
steady exit, the link dot). These are animation code; the phone run checks them.

---

## 7. How it degrades

| Situation | What happens |
|---|---|
| The peer runs an older build (no timestamp) | Played back by arrival with the smallest delay: about the old behaviour |
| No `Pong` yet (no round trip measured) | No forward prediction of the trip; the playback still evens the motion |
| A long gap on the link | The card slows to a stop over about 150 ms, then eases back onto the real path when moves resume |
| The line-up is older than this build, or the swipe was slow or odd | The scale stays 1: sizes as before |
| The commit never arrives | As before: the card is withdrawn |
| A fast flick that B never drew | The landing starts from the speed in the commit message |

---

## 8. How to check it on the two phones (the run still to do)

Full steps are in `docs/CHECKPOINT.md`, "Start here". In short:

1. Install on both phones; `logcat -c` on both.
2. **Line up again**, and note the `Lined up: … peer mm x S` value.
3. While the dot is amber (Bluetooth): a few photo slides.
4. While the dot is cyan (Wi-Fi): photo slides slow and steady, fast, and a couple dragged back; then a paused and a
   playing video.
5. **Note for each:**
   - Does B's half move as evenly as A's?
   - Does it lag behind the finger, or overshoot when the finger stops?
   - Does the card keep its speed across the edge at the let-go?
   - Are both halves the same size?
6. Save both logs (`-s AirSlide AirLink AirVideo`) and measure the two 50 mm ruler lines on each phone.

What to read in the receiver's log: `late p95` (the unevenness hidden; it sets the delay), `delay p50` (the trip),
`sent gaps` (should be 8 ms or 16 ms, even), and `out of order` (should be 0).

---

## 9. Next

1. **The noted run above** (a reminder is set for the start of the next session).
2. **C5, tune:** the delay bounds, the prediction cap and share, the gap slowdown, the exit speed; then fill in the
   "Before / Expected now" table with measured numbers.
3. **If the ruler disagrees with the swipe estimate:** build the ruler screen in settings (C3's fallback).
4. **Then Part B, the video half:** S0 measure, S1 warm player on a drag-back, S2 faster first frame, S3b smaller
   pieces while the card is in the air, S4 fresh picture.

## Commits on this branch

| Commit | What |
|---|---|
| `aa8b944` | docs: the plan, and the checkpoint pointing at it |
| `d2b3caf` | C0: timestamps on moves, the `AirSlide` per-slide summary, the ruler screen |
| `08e5217` | docs: how to run the measurements |
| `deb3dff` | C3: the peer's millimetres turned into this phone's; card speeds on the receiver; `isFastUpdates` |
| `8cdba51` | C1 even playback, C2 half the moves and the link dot, C4 the steady exit and the carried landing |
| `68f391c` | docs: what was built and how to test it once |
| `5d40f2f` | docs: the first try, and the run still to do |
