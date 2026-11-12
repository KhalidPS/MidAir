# 2026-11-11: Aim a followed video's first load by how long the last ones took: an experiment

This report describes an experiment that did not pay off. It puts the old aim next to the learnt one, shows what the
phones measured, and explains why it was set aside.

- Branch: `fix/2026-11-11/1408-video-aim-ahead`. One commit: `e85dbc4`, "fix: aim a followed video's first load by
  how long the last ones took". The commit holds the tuned version (Section 2).
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Video: a 676 MB phone recording, about 2.5 MB/s, with
  its index at the end of the file. All slides on Wi-Fi Direct.
- **Status: not proven, parked, not merged.** It stays on its branch. The index read-ahead fix that came after it
  does not include it.

---

## 1. The picture in one minute

**Problem:** B's half of the card starts loading the video at the lift, while A keeps playing. So B cannot load the
frame A shows now: by the time it is ready, A has moved on. `PlaybackFollower` therefore aims the first load ahead
of A's frame.

**Before:** the aim was a fixed 2 s ahead. When the start took longer, B was ready behind A and had to jump: drop
what it loaded and load again further ahead. When it took less, B held its first frame until A caught up.

**The idea:** learn the aim from how long the last first loads really took on this pair of phones.

**Result:** no clear gain. Starts ranged from 2.5 to 6.1 s on the same code and the same video. No aim learnt from
the last three fits the next one.

---

## 2. The change

**New class `StartLead`** (`feature/shelf/domain`): it remembers how long the last three first loads took and gives
the aim for the next one.

- First version: the average of the last three, times 1.1, plus 150 ms.
- Tuned version (the commit): the middle one of the last three, with no margin. One slow start then does not push
  the next aims out.
- Before any start was timed: 3 s. Never below 1.5 s, never above 5 s.

**`PlaybackFollower`** takes the aim in its constructor instead of the fixed 2 s. It times its first load, from the
start to the video moving, and hands that time out once (`takeStartMillis()`).

**`ShelfViewModel`** keeps one `StartLead`, passes its aim to each new `PlaybackFollower`, and feeds the measured
start time back to it.

---

## 3. Before and after

Before is Stage 3 of the benchmark comparison (card lag fix, fixed 2 s aim).

| Build | Slides | First frame after | In step with A after | Jumps | Holds |
|---|---|---|---|---|---|
| Stage 3: fixed 2 s aim | 5 | 2.4 to 3.6 s | 2.4 to 5.4 s | 2 of 5 | none |
| Stage 4a: learnt aim, first version | 5 | 2.5 to 5.8 s | 2.9 to 5.8 s | 0 of 5 | 1.3 s and 1.5 s |
| Stage 4b: learnt aim, tuned | 3 | 3.6 to 6.1 s | 3.6 to 13.0 s | 2 of 3 | 0.6 s (after two jumps) |

Stage 4a, slide by slide:

| Slide time | First frame after | Then | In step with A after |
|---|---|---|---|
| 14:16:34 | 2.9 s | played straight on | 2.9 s |
| 14:17:02 | 4.2 s | played, caught up by playing faster | 4.2 s |
| 14:17:33 | 2.8 s | held its frame 1.3 s | 4.4 s |
| 14:17:49 | 5.8 s (landed 0.4 s after the lift) | played, then stalled 1.0 s | 5.8 s |
| 14:18:14 | 2.5 s | held its frame 1.5 s | 4.0 s |

Stage 4b, slide by slide:

| Slide time | First frame after | Then | In step with A after |
|---|---|---|---|
| 14:25:18 | 3.7 s | jumped twice, then held 0.6 s | 8.2 s |
| 14:25:44 | 3.6 s | played straight on | 3.6 s |
| 14:26:01 | 6.1 s | jumped twice | 13.0 s |

---

## 4. Why it did not pay off

- **The starts were too spread out.** 2.5 to 6.1 s on the same code. The last three starts did not tell how long
  the next one would take.
- **Version one traded jumps for frozen frames.** It aimed past the average, so it never jumped, but it held a still
  frame for 1.3 to 1.5 s in two of five slides. A still frame that long looks stuck.
- **Version two brought the jumps back.** Aiming at the middle start, without a margin, landed behind on the slow
  starts: 2 of 3 slides jumped, both twice.
- **The real cause was elsewhere.** In the 6.1 s start, 3.2 s went to four requests for the file's header and index.
  That pointed to the index read-ahead fix instead.

**What came after:** the index read-ahead made starts shorter and steadier (1.1 to 2.4 s). With that, the fixed 2 s
aim worked: 0 jumps in 4 slides, holds of 0.4 to 0.55 s at most. So this experiment was set aside.

---

## 5. Limits

1. **Small samples.** 5 and 3 slides, one pair of phones, one video.
2. **Loading speed varied between rounds** for reasons not explained. Stage 4a and 4b run the same loading code, yet
   their first-frame ranges differ. Part of the difference is that, not the aim.
3. **Learnt per app run.** `StartLead` lives in the ViewModel. Every app start begins again at 3 s.

---

## 6. Tests

- `StartLeadTest.kt`: 6 tests. 3 s before any start; the last start's time; the middle one of the last three; never
  below 1.5 s; never above 5 s; a start that took no time teaches nothing.
- `ShelfViewModelLiveVideoTest.kt`: 1 test. The next video's first load aims by how long this one took to start.

---

## 7. What it is still good for

If starts become variable again, the learnt aim is ready on its branch. It would need a margin chosen between the
two versions, and a new test round on the phones.

### Lesson

> **Fix the spread before tuning the aim.** No aim fits starts that range from 2.5 to 6.1 s. Making the start
> itself shorter and steadier did more than any aim.
