# 2026-11-11: Let the peer read only a playing video's header and index before its card is lifted

This report puts the old sharing rule next to the fix, explains **what was wrong**, **what we did instead**, **what
it brings**, and **what it costs**.

- Branch: `fix/2026-11-11/1643-index-only-before-lift`. One commit: `94c5146`, "fix: let the peer read only a playing
  video's header and index before its card is lifted".
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). All slides on Wi-Fi Direct.
- Video "2": a 676,252,973-byte phone recording. Its picture data (`mdat`) header is at byte 377,092. Video "14" was
  also used.
- **Phone result: confirmed** in the 17:06 to 17:09 test, on `fix/2026-11-11/1645-keep-index-on-pull-back`, which
  also holds the exact-index and keep-on-pull-back fixes. It works as intended, with one small cost (Section 3).

---

## 1. The picture in one minute

**Before the index read-ahead fix:** A let the paired phone read a video's file only while its card was lifted.

**Since the index read-ahead fix:** to let B read the index ahead, A shared the whole file of any video playing on
it, before its card was lifted. B's app only asks for the header and index. But a modified app on a paired phone
could have copied a video the owner was only watching.

**After:** until the card is lifted, A serves only the parts that are not pictures and sound. Lifting the card opens
the whole file, as before the read-ahead.

---

## 2. The fix

**New: `MediaChannel.shareIndex(fileId, uri, sizeBytes)`** (`core/transfer/domain`). It lets the peer read only the
header and index. `share()` still opens the whole file.

**`NearbyMediaChannel`:**

- On `shareIndex()`, A finds where the file's pictures and sound are: the content of its `mdat` boxes, using
  `mp4MediaSpans()` from the exact-index fix. This runs off the main thread. A request that arrives meanwhile waits.
- A request that starts before the pictures is served only up to where they start (`openSpan()`).
- A request that starts inside them is refused, with a log line ("Piece of ... refused: its card isn't lifted").
- A file that is not MP4 is not readable ahead at all.
- A file already shared in full (its card is in the air) is never downgraded by a later `shareIndex()`.

**`ShelfViewModel.hintPlaying()`** calls `shareIndex()` instead of `share()`. A lifted or held card is left as it is.

---

## 3. Before and after

| | Before | After |
|---|---|---|
| What B may read before a lift | the whole file | header and index only |
| What B may read after a lift | the whole file | the whole file |
| Non-MP4 video before a lift | the whole file | nothing |
| Requests refused in the test | none | 1 (a leftover, see below) |
| Request at the lift for the start of the pictures | none (read ahead) | 1 extra, 213 ms (17:07:45) |

**Refused request:** in the test A refused exactly one request: "Piece of 14 at 106742070 refused: its card isn't
lifted". It was a leftover request from B's player, just after a pull-back. Nothing else was refused.

**The cost:** B's player needs some bytes about 28 KB into the picture data at every start. It asked for a piece at
byte 405,341; the picture data starts at 377,092. Before this fix, those bytes were inside the first megabyte B had
read ahead. Now that first read is cut at byte 377,108, the end of the `mdat` header. So B asks for them at the lift:
one extra request, answered in 213 ms on the 17:07:45 slide.

---

## 4. What makes the new approach better

- **A video stays on A until its card is lifted.** Before a lift, a paired phone gets the header and index (a list
  of where each frame is), not one frame of picture or sound.
- **Back to the rule from before the read-ahead.** Lifting the card is again what lets the other phone see a video.
- **The read-ahead still works.** B gets everything it reads ahead, so the start stays short.

---

## 5. Limits

1. **One extra request at every start** (213 ms measured once). A possible follow-up: also allow the first megabyte
   of the file before a lift, so that request is read ahead again. Not done.
2. **Not MP4, not readable ahead.** Such a video starts as before the index read-ahead.
3. **After a lift, the whole file is open,** as it always was. The rule only covers the time before a lift.
4. **The serving code has no unit test.** It was checked on the phones only.
5. **Small sample:** one test round, one pair of phones, two videos.

---

## 6. Tests

- `Mp4BoxesTest.kt` (from the exact-index fix): the `mp4MediaSpans()` and `openSpan()` tests cover what may be read.
- `ShelfViewModelLiveVideoTest.kt`:
  - a video that starts playing is said to the peer, who may read its header and index from then on (the hint
    shares only the index);
  - new: a lifted video stays readable in full when the link speed is reported again;
  - a video that springs back stops being readable in full, and the peer may read its index ahead again.
- `ShelfViewModelAirVideoTest.kt`: a held video put back stops being readable in full by the peer.
- The spring-back and put-back tests had been changed for the index read-ahead fix. They are put back to their
  original meaning: "stops being readable in full".

The serving code in `NearbyMediaChannel` has no unit test. It was checked on the phones (Section 3).

### Lesson

> **Open only what the next step needs.** The read-ahead needed the index, so sharing the whole file was more than
> it asked for.
