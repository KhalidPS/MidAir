# Checkpoint: 2026-10-25 (for the next session)

**Where we stand:** we're on branch `feature/live-video-handoff` (not merged). A video **playing** on one phone keeps
playing as its card slides onto the phone next to it: both halves of the card show the same moving picture while it
crosses, the sound moves on the drop, and the video plays on in the received card. Tested on a Redmi Note 10 Pro
(sender) and a Redmi Note 8 (receiver).

- **How it all works, from zero, and how to debug it:** [`LIVE_VIDEO_GUIDE.md`](LIVE_VIDEO_GUIDE.md). Read this first.
- **The plan and its phases (LH-0 to LH-5):** [`LIVE_VIDEO_HANDOFF.md`](LIVE_VIDEO_HANDOFF.md).
- **The app's overall plan:** [`PLAN.md`](PLAN.md).

## Start here next time: LH-4, one transfer

**The problem.** After the drop, the receiver gets the video twice at once over the same Nearby link: the 1 MB
pieces the player needs, and the full file as a separate `FILE` transfer. They compete, pieces slow from about 0.2 s
to 2–3 s each, and the video can stall until the file has arrived. Then the player reloads from the local copy.

**The plan.** The receiver builds the file from the pieces:
1. Keep the pieces the player has read, instead of deleting them (`DrainedPiece` / `PiecedStream` in
   `core/transfer/nearby/.../NearbyMediaChannel.kt`).
2. When the player has enough buffered, fetch the missing pieces in the background; the player's requests always
   go first.
3. When every piece is in, put the file together, check it against the original (size, then a hash), and add it to
   the shelf as any received file (`LinkedSlidePeer.receive` → `ReceivedFileStore.keep` → `repository.addFile`).
4. No second `FILE` transfer, and no reload of the player (which also removes the fragile reload described in the
   guide, Section 14).

Open questions to settle first:
- Which side decides it's a "live" transfer, so the sender skips its `FILE` send? (Probably: the receiver answers
  the `DragCommit` with "I'll fetch it myself" when it is following the video.)
- What if the link drops mid-way: resume from the pieces kept, or fall back to the `FILE` transfer?
- Where the pieces live meanwhile (`cacheDir` is fine for a phone video; a 1 GB file needs a free-space check).

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
| `feature/live-video-handoff` | this checkpoint | Live video handoff, LH-0 to LH-3 and the LH-5 fallback. Not merged; don't merge without asking |

## Done on this branch

| Commit | What |
|---|---|
| `0bd1533` | The plan (`LIVE_VIDEO_HANDOFF.md`) |
| `e01d5e8` | Playing card, `:core:media` (ExoPlayer, `VideoSurface`), offer and clock messages, reading the video from the other phone over Nearby, the follower that keeps both halves in step |
| `89c7a2c` | What the phones showed about Nearby (in the plan) |
| `ae56d42` | 3 pieces ahead (first frame 1.8 s instead of ~5 s), home Wi-Fi fallback while Nearby is still on Bluetooth (`LanFileServer`), follower learns its jump lead from jumps only |
| `ec2a196` | Fix: the video failed ~15 s after the file arrived (the sender had released its read access); the receiver now plays from its own copy. Piece draining on `Dispatchers.IO` |
| `bca1818` | Sync retune: start 2 s ahead, speed up to 1.5× within 1 s, slow down to 0.85× within 300 ms ahead, first jump 1 s ahead. The user found it a little better on the phones |
| (the commit with this file) | `LIVE_VIDEO_GUIDE.md` and this checkpoint |

**Tests:** about 260 unit tests, all passing (`./gradlew testAndroidHostTest`).

**Review debt:** the live video work was not put through `/verify` (to save usage); each commit was built and
unit-tested only.

## Last measurements (Redmi Note 8 receiving, before the retune)

| Moment | Time |
|---|---|
| Video load → first frame | 1.8 s |
| Settling into step while in the air | ~2.5 s (one jump, then a 1.25 s freeze; the retune targets this) |
| Pieces after the drop | 2–3 s each (LH-4 targets this) |

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
