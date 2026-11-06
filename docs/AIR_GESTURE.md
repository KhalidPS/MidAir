# Air gestures in the KMP project

Ported on 2026-11-06 from the Android-only copy (`../TransferingFileByAirAndroid`, branch `feature/air-gesture`,
commit `81f9b77`: V1 plus the V2 fixes from the device videos). Branch here: `feature/air-gesture`, on top of the
smoother card half and the live video handoff. Not tried on phones yet.

## What it does

- **Grab:** with the hand button on, close an open hand into a fist over the shelf. The nearest card squeezes into
  the fist and becomes a breathing ball of light that follows the hand.
- **Catch:** open the hand in front of the paired phone. The card bursts out of the palm there, flies into its slot,
  and its file follows. A hand that comes in already open counts too, and so does tapping the hint at the top.
- **Put back:** open the hand over this phone again (after 0.8 s, in case the other phone catches it), tap the
  "Holding …" hint, or wait 12 s.
- **Both phones saw the same fist:** the phone that saw it bigger keeps its grab.

## A grabbed video travels like a slid one

A grab is treated as a slide whose "in the air" part lasts as long as the fist stays closed. The live video handoff
works the same way through it:

| Moment | Sender (the fist) | Receiver (where the hand opens) |
|---|---|---|
| Grab | `DragStart`, then `PlaybackOffer` (position, playing or paused, Wi-Fi URL), then `Grabbed`. The video keeps playing, with sound, inside the shrinking card | Loads the video from the peer's file, muted, at the peer's frame, and keeps it in step. Nothing is shown yet, only the "Open your hand to catch" hint |
| Held | `PlaybackClock` every 250 ms while held | Follows the clocks (speed nudges, holds, seeks), same as during a slide |
| Catch | `Released` arrives. The card leaves the shelf and its video stops; the file is sent with `peerMayFetch = true`, and the share stays open so the peer can keep reading it | The card bursts out of the palm **already playing**, unmuted, at the same frame. The file is taken from the pieces already read (`Delivery.FETCH`), or sent if the video couldn't be played here (`ASK_TO_SEND`) |
| Put back / held too long | Share withdrawn, `DragCancel` | Video stopped and released, read pieces forgotten, hint gone |

Code: `ShelfViewModel.holdCard` → `announce` (shared with `startMirroring`) and `reportPlayback(peer, id) { held === card }`.
The receiver keeps the held card's `IncomingSlide` as `catchable`, so `videoOffered` and the followed video survive
the switch from "incoming" to "held". `land()` is the one landing path for a slid card (`DragCommit`) and a caught
one (`Released`). On screen, `GrabState.isAloft` hands the video picture to the grabbed copy while it's in the fist,
and back to its slot when it's put back (like `SlideState.isAloft`).

## Where each piece went

| Android copy | KMP |
|---|---|
| `core/gesture/domain/HandTracker.kt`, `AirGestureDetector.kt`, `HandSmoother.kt` | `:feature:shelf:domain` `commonMain`, package `feature.shelf.domain.gesture` |
| `core/gesture/camera/CameraHandTracker.kt` | `:feature:shelf:data` `androidMain`, package `feature.shelf.data.gesture` |
| `core/gesture/camera/GestureModule.kt` | A `single<HandTracker>` in `platformShelfDataModule` (Android). Other platforms bind none, and the hand button doesn't show |
| `core/presentation/permission/CameraPermission.kt` | `:feature:shelf:presentation`: interface + `expect` in `commonMain`, `actual` in `androidMain` (like `NearbyPermissions`) |
| `feature/shelf/presentation/air/*`, `model/AirGestureUi.kt` | Same packages in `:feature:shelf:presentation` `commonMain` |
| `SlideMessage.Grabbed/Released`, DTO, mapper, validation | `:core:transfer:domain` / `:core:transfer:data` (wire names `grab`, `release`, as in the copy) |
| `DataError.Local.CAMERA_UNAVAILABLE` | `:core:domain` |
| `AirIcons.Hand` | `:core:designsystem` |
| `assets/gesture_recognizer.task` (8 MB) | `app/src/main/assets/` |
| CameraX 1.6.2, MediaPipe tasks-vision 0.10.35 | `gradle/libs.versions.toml`; used by `:feature:shelf:data` `androidMain` |
| `CAMERA` + `uses-feature camera.front` (not required) | `:app` manifest |

The gesture code is used by the shelf only, so it stays in the shelf feature (`CLAUDE.md` placement rule). It moves
to a `:core:gesture` module if a second feature needs it.

Not ported: the water ripple (`waterRipple` on the shelf). It isn't on this branch, and air gestures don't need it.

## Tests

- `feature/shelf/domain`: `AirGestureDetectorTest` (13), `HandSmootherTest` (5).
- `feature/shelf/presentation`: `ShelfViewModelAirGestureTest` (23, from the copy) and `ShelfViewModelAirVideoTest`
  (11, new: a grabbed video on both phones). `FakeHandTracker` is shared by both.
- `core/transfer/data`: `SlideMessageCodecTest` round-trips `Grabbed`/`Released` and rejects an impossible hand size.

`./gradlew testAndroidHostTest`: 364 tests, all passing (2026-11-06).

## To check on the phones

The list from the copy's `docs/AIR_GESTURE_V2.md` still applies (cursor, grab and catch delays, heat, accidental
catches, the 12 s limit). On top of it, for video:

- [ ] Grab a playing video: it keeps playing, with sound, as it shrinks into the fist.
- [ ] On the other phone, catch it: it comes out of the palm already moving, on the same frame, and the sound
      switches phones at the catch.
- [ ] Put it back: it plays on in its slot, and the other phone stops reading it.
- [ ] Both while the link is amber (Bluetooth: only a shared Wi-Fi can carry the video) and cyan (Wi-Fi Direct).
- [ ] Both apps use the same Nearby service id: close the Android-only copy before testing this one.
