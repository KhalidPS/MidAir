# 2026-11-11: Every video slide measured today, by fix

This report collects every video slide measured on the phones on 2026-11-11, groups them by the build the phones
were running, and compares the builds.

- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver).
- Video: a 676 MB phone recording, about 2.5 MB/s, with its index at the end of the file. It is playing on A when
  the card is lifted.
- **A slide** is one lift of the video's card on A, dragged over the edge onto B, then either dropped on B or pulled
  back to A.
- All slides were on Wi-Fi Direct unless noted. All times are B's clock.

---

## 1. How to read the tables

Every column describes what happens **on phone B** (the receiver), counted from the moment the card is lifted on
phone A. At the lift, B starts loading the video so that its half of the card can keep playing it.

### Worked example: the 15:00:25 slide

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 15:00:25 | 1.1 s | held its frame 0.55 s | 1.8 s | 43 ms |

What happened on B, step by step:

- **0 s:** A's card is lifted. B starts loading the video. B's half shows only the card's still picture.
- **1.1 s:** B has a frame ready. The first video frame appears, and plays for a moment (0.15 s).
- **1.25 to 1.8 s:** B is ahead of A, so it waits on its frame for 0.55 s. The picture sits still.
- **1.8 s:** B starts playing, on the same frame as A's half.

### The columns

**First frame after:** the time from B starting to load until it can show a video frame. It measures how fast B
gets the data: the file's index, the picture data, and enough buffer to start.

**Then:** what B did once it had that frame. There are three outcomes:

- **"played straight on":** B's frame matched where A was. This is the ideal.
- **"held its frame N s":** B aimed a little ahead of A, got there early, and paused on its first frame until A
  caught up. The user sees a still frame for a moment.
- **"jumped":** B was ready too late. A had moved on too far, so B dropped what it loaded and loaded again further
  ahead. The user sees a frame, a stall, then a restart. This is the bad one. "Jumped twice" means it happened twice
  in a row.

A few rows show a variant of these (for example "caught up by playing faster", or "stalled" after the drop). They
are described in words in the row.

**In step with A after:** the time until both halves show the same moving video. It is about the first-frame time
plus any hold or second load (in the example: 1.1 s, 0.15 s of play, then the 0.55 s hold). This is the number that
matches what the user feels.

**Card lag (typical):** how far B's half of the card trailed the finger. It is the median over the slide's move
messages, in ms. It is independent of the video columns.

**"Landed during load":** the card was dropped on B before B's first frame was ready, so the full file transfer
started alongside the player. These slides are slower to start, and are marked.

**First lift and second lift (after a pull-back):** a first lift is a card lifted when B has read nothing of its
video yet, beyond the index read ahead. A second lift is the same card lifted again after a pull-back, while its
video still plays on A. From Stage 6 on, B keeps what it read for that case, so a second lift can start much sooner.

**In short:** the first column shows how good the loading is, the second how good the aim is, and the third the
result of both.

---

## 2. Stage 0: Baseline, mismatched builds

- Branch on B: `fix/2026-11-10/merge-all-fixes`. A still had the previous day's build (found later).
- Kept for reference only. The two phones did not run the same code.

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 12:36:18 | 3.1 s | jumped, card pulled back before it was ready again | never (pulled back at 4.7 s) | 356 ms |
| 12:36:24 | 2.5 s | played straight on | 2.5 s | 610 ms |
| 12:36:46 | 4.5 s | jumped twice | 9.1 s | 461 ms |
| 12:38:08 | 3.5 s | jumped | 5.6 s | 446 ms |
| 12:38:44 | 3.0 s | played straight on | 3.0 s | not recorded |
| 12:39:00 | 2.8 s | played straight on | 2.8 s | not recorded |

---

## 3. Stage 1: Before today's fixes, matched builds

- Branch: `fix/2026-11-10/merge-all-fixes` on both phones. This is the state merged into
  `feature/transfer-multi-files`.
- The starting point for today's fixes.

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 13:10:40 | 4.2 s (landed during load) | played straight on | 4.2 s | 322 ms |
| 13:12:02 | 2.6 s | played straight on | 2.6 s | 207 ms |
| 13:14:57 | 3.3 s | played straight on | 3.3 s | 502 ms |
| 13:04:39 (lifted on Bluetooth) | 15.7 s | about 1.5 s of play for every 10 to 30 s of buffering, until cancelled | never | not recorded |

Note: in the 13:04:39 slide the card was lifted while the link was still on Bluetooth. The link reached Wi-Fi
Direct 2 s later. It is not counted in the comparison below.

---

## 4. Stage 2: Lift before Wi-Fi

- Branch: `fix/2026-11-11/1324-lift-before-wifi`.
- Fix: "Move a followed video onto the pairing link once it reaches Wi-Fi Direct".
- It targets only the lifted-on-Bluetooth case. That case could not be reproduced in these tests: the link reached
  Wi-Fi Direct before the lift each time. These slides only show the normal path still works.

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 13:37:30 | 6.0 s (landed during load) | played straight on | 6.0 s | 252 ms |
| 13:39:14 | 4.0 s (landed during load) | played straight on | 4.0 s | not recorded |

---

## 5. Stage 3: Card lag during video

- Branch: `fix/2026-11-11/1340-card-lag-during-video`.
- Fix: "Carry the card messages over the Wi-Fi Direct group, apart from the video".
- The card messages get their own connection. The video path is unchanged.

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 13:44:14 | 3.2 s | jumped | 4.4 s | 66 ms |
| 13:44:29 | 2.6 s | played straight on | 2.6 s | 55 ms |
| 13:44:42 | 2.4 s | played straight on | 2.4 s | 58 ms |
| 13:44:57 | 3.6 s (landed during load) | played, then stalled 1.8 s after landing | 3.6 s | 41 ms |
| 13:45:47 | 3.4 s | jumped | 5.4 s | 54 ms |

Note: before this fix the typical card lag over seven slides was 207 to 610 ms, with worst single moves of 706 to
1483 ms. After it: 41 to 66 ms, with worst single moves of 99 to 360 ms.

---

## 6. Stage 4a: Video aim-ahead, first version

- Branch: `fix/2026-11-11/1408-video-aim-ahead`, first version.
- Fix: "Aim a followed video's first load by how long the last ones took". It aimed past the average of the last
  three starts.
- Status: not proven, parked, not merged.

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 14:16:34 | 2.9 s | played straight on | 2.9 s | 62 ms |
| 14:17:02 | 4.2 s | played, caught up by playing faster | 4.2 s | 57 ms |
| 14:17:33 | 2.8 s | held its frame 1.3 s | 4.4 s | 52 ms |
| 14:17:49 | 5.8 s (landed 0.4 s after the lift) | played, then stalled 1.0 s | 5.8 s | 44 ms |
| 14:18:14 | 2.5 s | held its frame 1.5 s | 4.0 s | 55 ms |

---

## 7. Stage 4b: Video aim-ahead, tuned version

- Same branch, `fix/2026-11-11/1408-video-aim-ahead`, tuned.
- It aimed at the middle one of the last three starts.

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 14:25:18 | 3.7 s | jumped twice, then held 0.6 s | 8.2 s | 42 ms |
| 14:25:44 | 3.6 s | played straight on | 3.6 s | 49 ms |
| 14:26:01 | 6.1 s | jumped twice | 13.0 s | 62 ms |

Note: in the 6.1 s start, 3.2 s went to four requests for the file's header and index (441 + 928 + 1035 + 414 ms),
and 2.2 s to picture data and buffer. That pointed to the next fix.

---

## 8. Stage 5: Index read-ahead

- Branch: `fix/2026-11-11/1431-video-index-prefetch`, built on Stage 3, without Stage 4.
- Fix: "Read a playing video's index ahead of its card".
- While a video plays on A, B reads the file's header and index in the background. At the lift, the player finds
  them already on B and goes straight to the picture data.

| Slide time | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|
| 14:59:49 | 2.4 s | played straight on | 2.4 s | 40 ms |
| 15:00:25 | 1.1 s | held its frame 0.55 s | 1.8 s | 43 ms |
| 15:00:46 | 2.3 s | played straight on | 2.3 s | 39 ms |
| 15:01:04 | 1.5 s | held its frame 0.4 s | 1.95 s | 41 ms |

Note: in the 15:00:46 slide the lift came 1 s after the previous pull-back, while B was still reading the index
again. A pull-back makes B let go of what it read.

---

## 9. Stage 6: Exact index, index-only before lift, keep on pull-back

- Branch: `fix/2026-11-11/1645-keep-index-on-pull-back`, built on Stage 5. It holds three fixes:
  - "Read ahead exactly a video's index, wherever it is and however long".
  - "Let the peer read only a playing video's header and index before its card is lifted".
  - "Keep what was read of a video when its card is pulled back and it plays on".
- Two videos: video 2 (the 676 MB recording above) and video 14. Test from 17:06 to 17:09. No crashes.

| Slide time | Video | First frame after | Then | In step with A after | Card lag typical |
|---|---|---|---|---|---|
| 17:07:45 | 2 | 2.6 s | jumped twice | 8.3 s | 37 ms |
| 17:08:10 | 14 | 2.0 s | played straight on | 2.0 s | 38 ms |
| 17:08:52 (second lift, 17 s after a pull-back) | 14 | 0.4 s | held its frame 0.8 s, then dropped on B | 1.2 s | 44 ms |
| 17:08:58 | 14 | 2.3 s (landed during load) | played straight on | 2.3 s | 48 ms |
| 17:09:10 | 14 | 1.4 s | held its frame 0.2 s, then dropped | 1.7 s | 36 ms |
| 17:09:17 (index not there yet) | 14 | 2.4 s | played straight on | 2.4 s | 41 ms |

Note: the 17:08:52 slide is fast because B kept the index and the frames it had played when the card was pulled
back. The 17:09:17 slide was lifted 1.4 s after a new read-ahead began, following a drop, so its index was not on B
yet.

---

## 10. Stage 7: Video over the Wi-Fi Direct group (experiment, parked)

- Branch: `feature/2026-11-11/1747-video-over-wifi-direct`, built on every fix up to the jump rule (Stage 8).
- Fix: "Play a followed video over the Wi-Fi Direct group, on a connection of its own". B's player reads the video
  from A's file server on the group, not over Nearby.
- Status: works, not faster, parked, not merged. Test from 17:58 to 18:01. Every load used the direct path.
- Card lag typical: 39 to 56 ms across the test.

| Slide time | First frame after | Then | In step with A after |
|---|---|---|---|
| 17:58:48 | 2.6 s | played straight on | 2.6 s |
| 17:59:00 | 1.9 s | played straight on | 1.9 s |
| 17:59:12 | 1.6 s | played straight on | 1.6 s |
| 17:59:21 | 3.4 s | jumped once | 5.7 s |
| 18:00:16 | 3.0 s | played straight on | 3.0 s |
| 18:00:52 | 3.4 s | jumped once | 6.1 s |
| 18:01:40 | 1.9 s | played straight on | 1.9 s |

Note: most of these were second lifts after a pull-back. Nothing is kept between lifts on this path, so each was a
fresh load: B's half showed the video's thumbnail first, then played.

---

## 11. Stage 8: Catch up before jumping

- Branch: `fix/2026-11-11/1740-catch-up-before-jumping`, built on Stage 6. Nearby path, no direct path.
- Fix: "Catch up by playing faster up to 1.5 s behind, and aim a jump 2 s ahead".
- Test from 18:10 to 18:12. Card lag typical: 37 to 48 ms across the test.

| Slide | First frame after | Then | In step with A after |
|---|---|---|---|
| First lift of video 2 | 2.5 s | played straight on | 2.5 s |
| First lift of video 14 | 1.7 s | played straight on | 1.7 s |
| Ten second lifts after a pull-back | 0.33 to 0.43 s | held its frame 1.3 to 1.6 s, every one | 1.8 to 2.1 s |

Note: after each pull-back B logged the index "fetched ahead" in 10 to 114 ms: all of it was still kept. The second
lifts held because they still aimed 2 s ahead, as a first lift does.

---

## 12. Stage 9: Aim closer on a second lift

- Branch: `fix/2026-11-11/1814-aim-closer-on-second-lift`, built on Stage 8.
- Fix: "Aim a video lifted again after a pull-back 0.6 s ahead, not 2 s".
- Test from 18:17 to 18:20. Nineteen loads, eighteen reached a first frame (one card was pulled back before it did).
  Card lag typical: 26 to 46 ms across the test.

| Slide | First frame after | Then | In step with A after |
|---|---|---|---|
| First lift | 2.8 s | played straight on | 2.8 s |
| Second lifts after a pull-back: 0.59, 0.86, 0.34, 0.53, 0.39, 0.97, 1.49, 1.44, 0.76, 0.85, 0.35, 0.44, 0.34, 0.30 s | 0.30 to 1.5 s | played straight on, no holds | 0.30 to 1.5 s |
| A lift after a drop | 1.86 s | played straight on | 1.86 s |
| One more lift in the series | 1.67 s | played straight on | 1.67 s |
| 18:19:46, lifted 3 s after a pull-back that came before its first frame | 1.88 s | jumped once | 4.7 s |

Note: at 18:19:46 nothing of the video's pictures had been kept, only its index, but it counted as a second lift and
aimed 0.6 s ahead. A follow-up (`fix/2026-11-11/1840-aim-closer-only-after-playing`) aims closer only when the
video had played on B before the pull-back. It awaits its phone test.

---

## 13. Comparison

The lifted-on-Bluetooth slide (13:04:39) is left out. Card lag ranges cover only the slides where it was recorded.

| Stage | Slides | First frame after | In step with A after | Jumps | Card lag typical |
|---|---|---|---|---|---|
| 0. Baseline, mismatched builds | 6 | 2.5 to 4.5 s | 2.5 to 9.1 s (one never) | 3 of 6 | 356 to 610 ms |
| 1. Before today's fixes | 3 | 2.6 to 4.2 s | 2.6 to 4.2 s | 0 of 3 | 207 to 502 ms |
| 2. Lift before Wi-Fi | 2 | 4.0 to 6.0 s | 4.0 to 6.0 s | 0 of 2 | 252 ms (one slide) |
| 3. Card lag during video | 5 | 2.4 to 3.6 s | 2.4 to 5.4 s | 2 of 5 | 41 to 66 ms |
| 4a. Aim-ahead, first | 5 | 2.5 to 5.8 s | 2.9 to 5.8 s | 0 of 5 (holds of 1.3 to 1.5 s in 2) | 44 to 62 ms |
| 4b. Aim-ahead, tuned | 3 | 3.6 to 6.1 s | 3.6 to 13.0 s | 2 of 3 | 42 to 62 ms |
| 5. Index read-ahead | 4 | 1.1 to 2.4 s | 1.8 to 2.4 s | 0 of 4 | 39 to 43 ms |
| 6. Exact index, index-only, keep on pull-back | 6 | 0.4 to 2.6 s (1.4 to 2.6 s without the second lift) | 1.2 to 8.3 s | 1 of 6 | 36 to 48 ms |
| 7. Video over Wi-Fi Direct (parked) | 7 | 1.6 to 3.4 s | 1.6 to 6.1 s | 2 of 7 (once each) | 39 to 56 ms |
| 8. Catch up before jumping | 12 | 1.7 to 2.5 s on first lifts, 0.33 to 0.43 s on second lifts | 1.7 to 2.5 s | 0 of 12 | 37 to 48 ms |
| 9. Aim closer on a second lift | 18 | 2.8 s on the first lift, 0.30 to 1.9 s after | 0.30 to 4.7 s | 1 of 18 | 26 to 46 ms |

### What each fix changed

- **Card lag fix (Stage 3):** card lag went from 207 to 610 ms down to 39 to 66 ms, in every later slide. Video
  start was unchanged.
- **Aim-ahead experiment (Stage 4a, 4b):** no clear gain. With starts ranging from 2.5 to 6.1 s, no single aim
  fits. The first version traded second loads for frozen frames. In the tuned version, second loads came back.
- **Index read-ahead (Stage 5):** first frame went from 2.4 to 6.1 s (all earlier stages) down to 1.1 to 2.4 s. In
  step went from 2.4 to 13 s down to 1.8 to 2.4 s. No jumps in 4 slides.
- **Lift-before-Wi-Fi fix (Stage 2):** its own case is not measured yet.
- **Exact index (Stage 6):** B walks the file's boxes and reads the whole index, wherever it is. The read-ahead of
  video 2 took 1.05 s in 3 pieces, instead of 1.3 to 3.4 s in 5.
- **Index-only before lift (Stage 6):** before a lift, A serves only the header and index, not the pictures and
  sound. It costs one extra request at the lift (213 ms at 17:07:45).
- **Keep on pull-back (Stage 6):** after a pull-back, B keeps what it read while the video plays on. The next lift,
  17 s later, had its first frame after 0.4 s, the fastest of the day.
- **The jump at 17:07:45** does not come from these three fixes. B was 1.01 s behind, just over the 1.0 s limit of
  the jump rule, and its reload, aimed 1 s ahead, landed behind again. The catch-up fix
  (`fix/2026-11-11/1740-catch-up-before-jumping`, Stage 8) addresses this.
- **Video over Wi-Fi Direct (Stage 7):** not faster. First frame 1.6 to 3.4 s on every lift, second lifts included.
  Even the tiny reachability check on that connection took 83 to 261 ms to answer: the delay per request comes from
  the Wi-Fi Direct link itself, not from Nearby. And nothing is kept between lifts on that path, so a second lift
  loads from scratch. Parked.
- **Catch up before jumping (Stage 8):** no double jumps since. 0 jumps in 12 loads on the Nearby path. In Stages 7
  and 9 the three loads that fell behind jumped once each (in step after 4.7 to 6.1 s, against 8.3 s for the double
  jump at 17:07:45).
- **Aim closer on a second lift (Stage 9):** no frozen frame on second lifts. In Stage 8 every second lift held its
  frame 1.3 to 1.6 s; in Stage 9 none did, and most were in step in under 0.9 s.

### How far to trust these numbers

- **Small samples.** 2 to 6 slides per stage up to Stage 6, 7 to 18 in Stages 7 to 9. One pair of phones, one video
  (two from Stage 6 on).
- **Measured in sequence.** The stages ran one after another on one afternoon. Loading speed varied between rounds
  for reasons not explained. Compare Stage 4a and 4b: the loading code is the same, but the first frame ranges
  differ.
- **"Landed during load" slides are slower,** and are marked in each table. Stage 2 had only such slides, which is
  why its starts look slow.

### What is still open

1. **The lifted-on-Bluetooth case** (Stage 2's target) is unverified on the phones.
2. **Stalls after a drop:** B still stalled for 1.0 to 1.8 s after the card landed in some slides.
3. **The remaining 1.1 to 2.4 s start:** picture data and the player's start-up buffer.
4. **The extra request caused by the index-only rule:** B's player needs bytes just inside the picture data at every
   start, no longer read ahead. One request at the lift, 213 ms measured.
5. **The follow-up for the closer aim** (`fix/2026-11-11/1840-aim-closer-only-after-playing`): a card pulled back
   before its first frame aims as a first lift does when lifted again. Unit tests only; it awaits its phone test.
