# 2026-11-11: Aim a second lift closer only when the video had played on B before the pull-back

This report puts the old rule next to the fix, explains **what went wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-11/1840-aim-closer-only-after-playing`. One commit: `25e0f99`, "fix: aim a second lift closer
  only when the video had played here before the pull-back". Built on `fix/2026-11-11/1814-aim-closer-on-second-lift`
  (report 13).
- **Not merged yet.** Whether to merge on the unit tests alone is still open.
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Same build of this branch on both phones.
- **Phone result: the targeted case was not reproduced.** No regression seen in the usual second lift (Section 3).

---

## 1. The picture in one minute

**Background:** B plays a video that is playing on A while its card is dragged across. Loading takes time, so B starts
("aims") a little ahead of A's position. A first load aims 2 s ahead. Report 13 made a card lifted again after a
pull-back aim only 0.6 s ahead: what B read of the video is kept across a pull-back (report 10), and such a second
start took only 0.3 to 0.4 s.

**What went wrong:** a card pulled back **before** B showed its first frame, then lifted again, also counted as a
second lift. It aimed 0.6 s ahead. But none of its pictures were kept, because none had been read yet. The load took
as long as a first load, 1.9 s. B was then more than 1.5 s behind A, so it jumped (report 11). Seen once, in the last
phone test of 11 Nov.

**After:** the closer aim is used only when the video had really played on B before the pull-back.

---

## 2. The fix

All in `ShelfViewModel`, on B:

- **`FollowedVideo.hasPlayed`:** set to true once B's player moves while A plays. From then on, some of its pictures
  are read on B.
- **On a pull-back:** if the video is kept for a new lift (`keptForRelift`), B also notes whether it had played
  (`playedBeforePullBack`).
- **On the next lift of that video:** it counts as a second lift, and aims 0.6 s ahead, only if
  `playedBeforePullBack` is true. Otherwise it aims 2 s ahead, as a first load does.

What was read of the video (its index) is still kept in both cases. Only the aim changes.

---

## 3. Before and after

| | Before (733dbb0) | After (25e0f99) |
|---|---|---|
| Pulled back after B played, lifted again | aims 0.6 s ahead | aims 0.6 s ahead (unchanged) |
| Pulled back before B's first frame, lifted again | aims 0.6 s ahead, load takes 1.9 s, jumps | aims 2 s ahead, as a first load |
| Index kept across the pull-back | yes | yes (unchanged) |

**Phone test, 12 Nov 2026**, build of this branch on both phones, 23 lifts in three sessions:

- **The targeted case was not reproduced** in any session. No lift of the same video came after a pull-back made
  before B's first frame. So the fix is covered by its unit test only.
- **Session 1 (12:29 to 12:32):** 11 second lifts. First frame after 0.3 to 1.5 s (one at 3.3 s). No jump on any of
  them. 4 first lifts, 1.7 to 4.2 s, one jump: a first lift that took 2.9 s to show a frame, then jumped 3.5 s ahead.
- **Session 2 (12:57 to 12:58):** second lifts at 0.5 s, 0.5 s and 2.3 s. The 2.3 s one then jumped 3.8 s ahead and
  buffered another 3.3 s. Cause, read from the logs, not linked to this fix: after a pull-back, B keeps reading ahead
  until A refuses a request ("its card isn't lifted"). The refused piece (at 96.8 MB) was exactly where the next lift
  started (39 s into the video). Fetching it again took 1.4 s.
- **Session 3 (13:05 to 13:06):** two first lifts took 3.9 s and 3.3 s. Each then jumped about 4 s ahead.

**The link was slower than the day before.** A piece took 0.2 to 1.5 s to open on 12 Nov, against 0.1 to 0.25 s on
11 Nov. In session 3 the connection was also set up three times within two minutes. The cause was not looked into.

---

## 4. What it brings

- **The closer aim is used only when it is true.** A second lift aims close only when there is something on B to
  start from.
- **No change for the usual case.** The 11 second lifts of session 1 had no jump.
- **It removes one cause of a jump** right after a quick pull-back. This is expected from the 11 Nov log and the unit
  test, not measured.

---

## 5. Limits

1. **The targeted case was not seen on the phones.** Only the unit test covers it.
2. **The case behind the fix was seen once** (11 Nov).
3. **Not fixed: the refused piece after a pull-back** (session 2). A piece A refused can be exactly the one the next
   lift needs, and fetching it again costs time.
4. **First lifts still jump** when the load takes longer than 2 s (sessions 1 and 3). This fix does not touch them.
5. **The slower link on 12 Nov** makes the timings of that day hard to compare with 11 Nov.

---

## 6. Tests

`ShelfViewModelLiveVideoTest.kt`:

- New: a video pulled back before it played here aims as a first load does when lifted again. B loads at
  7,000 ms, 2 s ahead. Its index is still kept.
- Updated: the existing second-lift test is renamed to "a video that played here and is lifted again after a pull-back
  aims closer". It now makes the video play on B before the pull-back. It still loads at 5,600 ms.

### Lesson

> **"Lifted again" is not the same as "has something to start from."** Check the thing the shortcut depends on, not
> the event that usually comes with it.
