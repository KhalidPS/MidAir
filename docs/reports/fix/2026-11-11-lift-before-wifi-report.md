# 2026-11-11: A video lifted before Wi-Fi Direct: before and after

This report puts the old approach next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-11/1324-lift-before-wifi` (off `feature/transfer-multi-files`, after
  `fix/2026-11-10/merge-all-fixes` was merged into it). Commit `bb02b53`.
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Video: 676 MB, playing.
- **Phone result: pending.** The build is installed on both phones. The "after" below is what the code and the
  tests do, not yet what the phones showed. All 425 unit tests pass and the app builds.

---

## 1. The picture in one minute

**Symptom (reported 2026-11-11):** after dragging the playing 676 MB video to B and leaving it there, B "keeps
pausing then resuming".

B picks how it reads the video once, when A's `PlaybackOffer` arrives (this part is unchanged):

- Link already on Wi-Fi Direct ("fast"): B reads the file in pieces over the pairing link (`VideoSource.Remote`,
  logged "over Nearby"). The pieces are kept, so on landing the full file is taken from them (`Delivery.FETCH`) and
  the player never reloads.
- Link still on Bluetooth: B reads it over HTTP from A through the home router (`VideoSource.Url`, logged "over
  Wi-Fi"). A code comment already said this path is far slower once the link is on Wi-Fi Direct: the one radio hops
  between the two networks, and the router carries every byte twice.

**Before:** the choice was never revisited. A slide started on Bluetooth stayed on the router path for good.

```
 13:04:28     reconnect on Bluetooth ("Link quality 2: live video off")
 13:04:38     card lifted on A (10 s after reconnecting, link still Bluetooth)
 13:04:39.85  B loads the video over the shared Wi-Fi (Url path)
 13:04:41     link upgrades to Wi-Fi Direct (12.5 s after reconnecting)
 13:04:42     card lands on B: file pulled over Wi-Fi Direct (Delivery.PULL)
              while the player still reads through the router
```

B's player: first frame 15.7 s after the lift. Then play 0.9 s, buffer 27.5 s, play 1.6 s, buffer 9.8 s, play
1.5 s, buffer 11.1 s, play 1.5 s, buffer 9.0 s, play 2.0 s, buffer 7.0 s, play 1.7 s, buffer 10.9 s, play 1.4 s,
buffer 32.1 s, and so on until the owner cancelled. Roughly 1.5 s of play per 10 to 30 s of buffering.

**For comparison,** the same day, same phones, same video, but lifted after the link was already on Wi-Fi Direct
(Remote path from the start): first frame 2.6 s, 4.2 s and 3.5 s after the lift in three slides, and no rebuffering
after that.

**After:** as soon as the link is fast, a video still on the router path moves onto the pairing link.

| | Before | After |
|---|---|---|
| Path once the link is on Wi-Fi Direct | stays on the router (Url) | moves to the pairing link (Remote) |
| Transfers after landing | 2: player through the router + full PULL | 1: FETCH from the pieces already read |
| Playback on B (measured / expected) | 1.5 s play per 10 to 30 s buffering | same as a normal Remote slide |
| Player reload onto a local copy at the end | yes | no |
| Extra reload while playing | none | one, when the link upgrades |
| Lift in the first ~12 s after pairing | different, worse path | same path as a later lift |

---

## 2. The fix

All of it is in `ShelfViewModel.kt` (shelf presentation). A new private function, `readOverLink()`:

```kotlin
private fun readOverLink(peer: SlidePeer, video: FollowedVideo) {
    val engine = videoEngine ?: return
    val media = peer.media ?: return
    if (!video.isLoaded || video.viaLink || !media.isFast) return
    video.viaLink = true
    val source = VideoSource.Remote(video.itemId, video.sizeBytes) { media.open(video.itemId, it) }
    val startAtMs = video.follower.startAt(nowMillis()) ?: engine.positionMs
    engine.load(source, startAtMs = startAtMs, playWhenReady = engine.playWhenReady.value)
    // A load starts at full volume: the peer's speaker still plays it while the card is over there.
    engine.setVolume(0f)
}
```

It is called from two places:

1. **`talkTo()`, when the link reports it became fast.** This covers a card still in the air.
2. **`land()`, before the delivery is chosen.** This covers a card that lands after the upgrade but before the
   speed report reached the video. Because it runs first, `viaLink` is already true when `land()` picks the
   delivery, so it picks `FETCH` instead of `PULL`.

The reload starts on the frame A is at (the follower's prediction, or the player's own position), keeps play or
pause, and stays muted while the card is in the air (see Section 4). The `viaLink` guard means a video already on
the link is not reloaded when the speed is reported again. `FollowedVideo` now carries `sizeBytes` (from the
offer), because `VideoSource.Remote` needs the file size.

---

## 3. Limits

1. **Not verified on the phones yet.** Owner's test pending.
2. **Card lands while the link is still on Bluetooth, and the link upgrades only afterwards:** the file is already
   being sent the slow way. This fix does not change that case.
3. **The upgrade time itself is not shortened.** It took 12.5 s here; a video lifted in that window still starts
   on the router path, then moves.
4. **One visible reload** when the link upgrades: a brief rebuffer, expected about as long as a normal start
   (2.6 to 4.2 s measured).

---

## 4. Tests

Added to `ShelfViewModelLiveVideoTest.kt`:

- `a video read over the shared Wi-Fi moves onto the pairing link once that gets fast`
- `a video that moved onto the pairing link has its file taken from what was read`
- `a video read over the pairing link is not loaded again when the link's speed is reported again`

`FakeMediaChannel.kt`: setting `isFast` now also feeds `isFastUpdates`, so a test can make the link get fast in
the middle of a slide.

The first test caught a real defect during the work. Loading a source resets the volume to full, so the reload
would have unmuted B's half while A's speaker was still playing. The fix mutes again after the reload.

---

## 5. What makes the new approach better

- **One path, whatever the timing.** A slide started in the first ~12 s after pairing ends on the same path as a
  slide started later.
- **One transfer instead of two competing.** Before, the player read through the router while a full pull ran over
  Wi-Fi Direct. Now the player's pieces are the transfer, and the file is assembled from them.
- **No reload at the end.** Read over the link, the player never needs moving onto a local file.
- **The cost is bounded and early.** One reload, once, when the link upgrades, instead of rebuffering for as long
  as the video plays.

### Lesson

> **A choice made under one condition must be revisited when the condition changes.** The app already knew which
> path was right for a fast link; it only asked the question once.
