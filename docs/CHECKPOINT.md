# Checkpoint: 2026-11-01 (for the next session)

**Where we stand:** branch `feature/smooth-card-half` (stacked on `feature/live-video-one-transfer`, which is stacked
on `feature/live-video-handoff`; none merged). Part A of the smoother card half, **C0 to C4, is built and
unit-tested, not yet tried on the phones**. The user asked to build every phase first and test once (2026-11-01).

- **The plan, and what each phase does in the code:** [`get-smoother-card-half-plan.md`](get-smoother-card-half-plan.md)
  (each phase has a "Built" note).
- **Live video (LH-0 to LH-5):** [`LIVE_VIDEO_GUIDE.md`](LIVE_VIDEO_GUIDE.md), [`LIVE_VIDEO_HANDOFF.md`](LIVE_VIDEO_HANDOFF.md).
- **The app's overall plan:** [`PLAN.md`](PLAN.md).

Decisions the user confirmed (2026-11-01): Part A first (image cards included), `atMillis` on `DragMove`, C1's
playback delay from the measured p95, density correction from the line-up swipe with a ruler screen as fallback.

## Start here next time: the one test run on the phones (then C5, tune)

1. Install the build on **both** phones, `logcat -c` on both.
2. **Line up again** (swipe one finger across both screens): the density correction (C3) is measured by the line-up,
   and a line-up from before this build has none. Look for `Lined up: ... peer mm x S` (S near 1; exactly 1 means it
   wasn't used).
3. Right after linking (the dot on the devices icon is **amber**: still Bluetooth), slide a photo across 3 times.
4. When the dot turns **cyan** (`Link quality 3`), slide a photo 5 times slowly and steadily, 3 times fast, and drag 2
   back mid-way. Then a paused video and a playing one.
5. Save both logs: `"$ADB" -s <serial> logcat -d -v time -s AirSlide AirLink AirVideo > build/device-logs/card-run1-<A|B>.txt`.
6. Ruler on each phone (`adb shell am start -n com.kotlistine.transferingfilebyair/.RulerActivity`): measure both
   50 mm lines.

What to feel: B's half moving at the same even pace as A's while the finger moves steadily; no change of pace at the
let-go (the card keeps its speed across the edge and curves into its slot); both halves the same size. What to read:
the receiver's `AirSlide` lines (`late p95` is the unevenness the playback hides; `delay` is the trip).

What is **not** built: the ruler screen in settings (only if the swipe estimate turns out too noisy), and Part B (the
video half: warm player, faster first frame, fresh picture).

## Done: LH-4 on the two phones (kept for reference)

Install the build on both phones (steps below), slide a playing video across, and check the receiver's log for:

1. `Fetched <id> (S bytes, K already here) in N ms`: the file came from the pieces. Note N, and K/S.
2. `<id> is on this phone for good` then `<id> goes on from this phone's copy`, and **no** new `Load …` line.
3. `Piece … opened in` times after the drop: they should stay near 0.1–0.5 s (they were 1.7–3.3 s).
4. On the sender: the item leaves its shelf only after the receiver's `FETCHED`.
5. Then the failure paths: drag back mid-slide; tap the arriving card to stop it; turn Wi-Fi off on one phone mid-fetch.

Decisions taken in LH-4 (change them if the phones disagree):
- **The receiver decides.** It answers the drop with `FileRoute`: `FETCHING` → `FETCHED`, or `SEND`. A sender that
  hears nothing within 5 s sends the file as before (so an older build still works, 5 s slower).
- **A link that drops mid-fetch fails the transfer** (no resume), as a `FILE` transfer does today. A fetch that fails
  for any other reason (no room, hash mismatch) falls back to the `FILE` transfer.
- **Pieces live in `cacheDir/live-ranges`**; a fetch first checks free space (2 × the missing part + 64 MB).

## Then

1. **Safety net:** the receiver withdraws an incoming card when the sender goes silent mid-slide, instead of
   freezing (`docs/reference/freeze.png`). Careful not to drop a card that is still on its way.
2. **Codec check:** if the receiver can't decode the video, it shows the still card instead of an error.
3. **Sound cross-fade** on the drop instead of a hard switch.
4. Film both phones at 240 fps to measure the sync (frames between the halves at a scene cut).

## Branches

| Branch | Head | State |
|---|---|---|
| `master` | `77af4df` | Phases 0–7, all merged |
| `feature/live-video-handoff` | `08c70f9` | Live video handoff, LH-0 to LH-3 and the LH-5 fallback. Not merged; don't merge without asking |
| `feature/live-video-one-transfer` | `aa8b944` | LH-4, one transfer (stacked on the branch above). Not merged |
| `feature/smooth-card-half` | this checkpoint | Smoother card half, C0 to C4 (stacked on the branch above). Not merged |

## Done on this branch

| Commit | What |
|---|---|
| `0bd1533` | The plan (`LIVE_VIDEO_HANDOFF.md`) |
| `e01d5e8` | Playing card, `:core:media` (ExoPlayer, `VideoSurface`), offer and clock messages, reading the video from the other phone over Nearby, the follower that keeps both halves in step |
| `89c7a2c` | What the phones showed about Nearby (in the plan) |
| `ae56d42` | 3 pieces ahead (first frame 1.8 s instead of ~5 s), home Wi-Fi fallback while Nearby is still on Bluetooth (`LanFileServer`), follower learns its jump lead from jumps only |
| `ec2a196` | Fix: the video failed ~15 s after the file arrived (the sender had released its read access); the receiver now plays from its own copy. Piece draining on `Dispatchers.IO` |
| `bca1818` | Sync retune: start 2 s ahead, speed up to 1.5× within 1 s, slow down to 0.85× within 300 ms ahead, first jump 1 s ahead. The user found it a little better on the phones |
| `08c70f9` | `LIVE_VIDEO_GUIDE.md` and the previous checkpoint |
| (on `feature/live-video-one-transfer`) | LH-4: kept pieces, background fetch with SHA-256 check, `FileRoute` handshake, no reload |

**Tests:** 312 unit tests, all passing (`./gradlew testAndroidHostTest`), debug APK builds.

**Review debt:** the live video work, LH-4 included, was not put through `/verify` (to save usage); each commit was
built and unit-tested only.

## Last measurements (Redmi Note 8 receiving, before the retune)

| Moment | Time |
|---|---|
| Video load → first frame | 1.8 s |
| Settling into step while in the air | ~2.5 s (one jump, then a 1.25 s freeze; the retune targets this) |
| Pieces after the drop | 2–3 s each (LH-4 targets this; not measured since) |

The user's overall impression: the delay went from ~14 s to ~5–7 s, then "a little better" after the retune.

## How to test on two phones

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleDebug testAndroidHostTest
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices                                   # Note 10 Pro: b659121b, Note 8: 1411917c
"$ADB" -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s <serial> logcat -c                     # before a run
"$ADB" -s <serial> logcat -d -v time -s AirVideo AirLink AirLan ExoPlayerImplInternal > build/device-logs/runN.txt
```

- Both phones need this build (install on each). Both are on the home Wi-Fi.
- Wait for `Link quality 3` in the log before lifting the card (the link starts on Bluetooth).
- Check `adb devices` first: `adb -s <serial>` waits forever for a phone that isn't plugged in.
- What each log line means, and a checklist for reading a run: the guide, Section 12.
- `share_sample2.mp4` (the reference video, 24 MB) is untracked on purpose: keep it out of git.

## Still open from before this branch

- The Baseline Profile run (`./gradlew :app:generateBaselineProfile`, then
  `:baselineprofile:connectedBenchmarkReleaseAndroidTest`; details in git history of this file, commit `77af4df`).
- On the phones: a 1 GB file with the phone locked mid-way (6b), Retry from the snackbar (6a), "Remove animations"
  (7c).
- Phase 8, the desktop receiver: see `PLAN.md`.

## How to run the app

On both phones: turn on Bluetooth, Wi-Fi and Location. Open the app, tap the phone icon at the bottom left, and
allow the permissions. Hold the phones side by side, hold **Connect** on one, confirm the same code on both, then
swipe one finger across both screens to line them up. Back on the shelf, tap a video to play it, long-press it and
drag it off the edge towards the other phone.

Developer tool: the Twin Simulator has no launcher icon. Open it with
`adb shell am start -n com.kotlistine.transferingfilebyair/.SimulatorActivity`.
