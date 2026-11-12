# 2026-11-11: Keep what was read of a video when its card is pulled back and it plays on

This report puts the old behaviour next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-11/1645-keep-index-on-pull-back`. One commit: `5c1e228`, "fix: keep what was read of a video
  when its card is pulled back and it plays on". The branch also holds the exact-index and index-only fixes.
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). All slides on Wi-Fi Direct. Video "14" in the
  measured case.
- **Phone result: confirmed** in the 17:06 to 17:09 test, one sample (Section 3).

---

## 1. The picture in one minute

**Before:** when a card was pulled back to A, B let go of everything it had read of that video, its index included.
The video still played on A, so B then read the index again in the background: 1.3 to 2.7 s, measured earlier.

**What went wrong:** a second lift inside that time started without the index. In the index read-ahead test, the
15:00:46 lift came 1 s after a pull-back. Its first frame came after 2.3 s, when other slides took 1.1 to 1.5 s.

**After:** while the video still plays on A, B keeps what it read when the card is pulled back. It knows the video
plays there from A's `PlaybackHint`.

---

## 2. The fix

All in `ShelfViewModel`, on B:

- **On a pull-back:** if the card's video is the one A said is playing, B keeps what it read and notes it
  (`keptForRelift`). Otherwise it lets go, as before.
- **On a new `PlaybackHint`:** if another video now plays on A, what was kept of the pulled-back one is let go.
- **On a new lift of that video:** the note is cleared. From then on it is handled like any card's video, and the
  same rules apply at its next pull-back or drop.

What is kept is not only the index. Any pieces B's player read while the card was over the edge are kept too.

---

## 3. Before and after

| | Before | After |
|---|---|---|
| Index after a pull-back | let go, read again in 1.3 to 2.7 s | kept |
| Read-ahead after the pull-back at 17:08:35 | a full read (0.55 to 1.1 s for video 14) | "fetched ahead in 60 ms" |
| Frames B already played | let go | kept |
| First frame, second lift soon after a pull-back | 2.3 s (15:00:46) | 0.4 s (17:08:52) |

**The measured case:** after the pull-back at 17:08:35, B logged the index of video 14 "fetched ahead in 60 ms".
Nothing had to be read: it was all still on B. A real read of that index took 0.55 to 1.1 s.

The next lift of that video came 17 s later, at 17:08:52. Its first frame came after 0.4 s, the fastest start of
the day. It is faster than any other start because the frames B had already played were kept too, so the player
found picture data on B as well as the index.

| Slide | Video | First frame after | Then | In step with A after | Card lag |
|---|---|---|---|---|---|
| 17:08:52, second lift after a pull-back | 14 | 0.4 s | held 0.8 s, then dropped on B | 1.2 s | 44 ms |

Note: the 15:00:46 and 17:08:52 lifts are not the same case. The first came 1 s after its pull-back, the second
17 s after. The 0.4 s shows what a kept video gives; it does not show a lift 1 s after a pull-back.

---

## 4. What makes the new approach better

- **No work thrown away.** The index and the frames read stay on B while the video still plays on A, which is
  exactly when it may be lifted again.
- **A second lift can start at once.** No window of 1.3 to 2.7 s in which it starts without the index.
- **It is let go when it stops being useful:** as soon as A plays another video.

---

## 5. Limits

1. **Only after a pull-back.** After a drop, what was read belongs to the finished transfer. B starts a new
   read-ahead if the video plays on. A card lifted right after that (17:09:17, 1.4 s after the new read-ahead began)
   started without its index: first frame after 2.4 s.
2. **One sample** of a second lift after a pull-back, one pair of phones, one video.
3. **Storage:** what was read stays on B until another video plays on A or the app restarts. For a long video
   played over the edge for a while, that can be a lot more than the index.
4. **Not tested on the phones:** a lift a second or two after a pull-back, the case the fix was made for.

---

## 6. Tests

`ShelfViewModelLiveVideoTest.kt`: 3 new tests.

- What was read of a video that still plays on the peer is kept when its card is pulled back.
- What was kept of a pulled-back video is let go once another video plays on the peer.
- A video lifted again and pulled back is not let go twice.

### Lesson

> **Don't throw away what is about to be needed again.** A pulled-back card whose video still plays is the card most
> likely to be lifted next.
