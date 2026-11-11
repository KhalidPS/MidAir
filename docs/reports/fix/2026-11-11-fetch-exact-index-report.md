# 2026-11-11: Read ahead exactly a video's index, wherever it is: before and after

This report puts the old read-ahead next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-11/1618-fetch-exact-index`. Two commits:
  - `1850d4e`, "feat: read where an MP4 file keeps its index and its pictures" (`Mp4Boxes.kt`, 20 unit tests).
  - `cc49273`, "fix: read ahead exactly a video's index, wherever it is and however long" (the read-ahead uses it).
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). All slides on Wi-Fi Direct.
- Video "2": a 676,252,973-byte phone recording, about 2.5 MB/s, about 4.5 minutes. Its index (`moov` box, about
  1.2 MB) is at the end, from byte 675,051,444. Its picture data (`mdat`) header is at byte 377,092. Video "14" was
  also used.
- **Phone result: confirmed** in the 17:06 to 17:09 test, on `fix/2026-11-11/1645-keep-index-on-pull-back`, which
  also holds the next two fixes (index-only before lift, keep on pull-back).

---

## 1. The picture in one minute

**Before (index read-ahead fix):** while a video played on A, B read ahead a fixed part of the file: the first 1 MB
and the last 4 MB. That works for a phone recording of a few minutes, with its index at the end.

**What was wrong with it:**

- **An index grows with the video.** Video 2 has 1.2 MB of index for about 4.5 minutes: about 0.27 MB a minute. At
  that rate a video longer than about 15 minutes has an index bigger than 4 MB, and B would read only part of it.
  This is an estimate from that one video, not tested.
- **Some videos keep the index at the start.** A video saved that way (often a downloaded one) has its index right
  after the header. The last 4 MB would then be picture data, and any index past the first 1 MB would be missed.
- **It read more than needed.** Up to 4 MB at the end, when video 2 needs 1.2 MB.

**After:** B walks the file's boxes. An MP4 file is a row of boxes, and each box starts with a short header that
says its type and its length. B reads the header at byte 0, skips to the next box, and so on. It reads the whole
`moov` box (the index) wherever it is, and the header of the `mdat` box (the pictures and sound). It stops once it
has both.

---

## 2. The fix

**`Mp4Boxes.kt`** (`core/transfer/nearby`, common code):

- `mp4BoxAt()` reads one box from its header bytes: its type, start and length. It handles a length over 4 GB and a
  box that runs to the end of the file. Bytes that are not a box give null.
- `mp4MediaSpans()` lists where the pictures and sound are (the content of the `mdat` boxes). `openSpan()` cuts a
  request short before such a part. Both are used by the next fix (index-only before lift).

**`NearbyMediaChannel.prefetch()`:**

- It walks the boxes from offset 0. For each box it needs only the first 16 bytes. If they are already on B, it
  reads them there; if not, it asks A for a piece at that offset.
- For the `moov` box it reads every byte not yet on B, one piece (at most 1 MB) at a time.
- As before, it waits while a card is in the air or a player is waiting for pieces.
- It logs "Index of ... fetched ahead in N ms", or "not found ahead" when it found no index.
- `indexSpans()` (the fixed first 1 MB and last 4 MB) and its 4 tests are removed.

**Not MP4:** a file whose first bytes are not a box is not read ahead. Its card starts as every card did before the
index read-ahead.

---

## 3. Before and after

The read-ahead itself, in the background, measured on B:

| | Fixed spans (before) | Exact index (after) |
|---|---|---|
| Video 2 | 1.3 to 3.4 s, 5 pieces | 1.05 s, 3 pieces |
| Video 14 | not measured | 0.55 s, then 0.6 to 1.1 s on later runs |
| Bytes read at the end of video 2 | up to 4 MB | 1.2 MB (the index) |
| Index longer than 4 MB | partly missed | read whole |
| Index at the start of the file | read only within the first 1 MB | read whole |

For video 2 the three pieces were at byte 0, at 675,051,444 (the start of the index) and at 676,100,020 (the rest
of the index). Each was answered in 116 to 202 ms.

**First frame on B** in the six slides of that test: 1.4 to 2.6 s, and 0.4 s for a second lift after a pull-back.
The slide table is in the benchmark comparison, Stage 6. These times include a small cost from the index-only fix:
one extra request at the lift (213 ms on the 17:07:45 slide). See its report.

---

## 4. What makes the new approach better

- **It reads what the player needs, not a guess.** The index is read whole, however long, wherever it is.
- **Less to read, fewer pieces.** 3 pieces instead of 5 for video 2, done in 1.05 s instead of 1.3 to 3.4 s. The
  index is ready sooner after a video starts playing on A.
- **It makes the next fix possible.** Knowing where the pictures are is what lets A serve only the header and index
  before a lift.

---

## 5. Limits

1. **Not tested on the phones:** a video longer than about 15 minutes, and a video with its index at the start.
   Both cases are covered by the parsing tests only.
2. **The fetching part has no unit test.** The walk inside `NearbyMediaChannel` was checked on the phones only.
3. **Not MP4, not read ahead.** Other video formats start as before the index read-ahead.
4. **Small sample:** one pair of phones, two videos.

---

## 6. Tests

- `Mp4BoxesTest.kt`: 20 new tests. One box: type, start and length; a length over 4 GB; a length of zero; bytes that
  reach past the file's end, a name that is not four printable characters, a length shorter than the header, and too
  few bytes are no box. A whole file: pictures between header and index; index first; several `mdat` boxes; none;
  not MP4, unreadable, broken half way, empty. `openSpan()`: cut where a closed part starts, nothing from inside one,
  everything past them, everything with none closed, nothing for zero bytes.
- `FileSpansTest.kt`: the 4 tests of `indexSpans()` are removed with it.

The fetching inside `NearbyMediaChannel` was checked on the phones (Section 3).

### Lesson

> **Read the file's own map instead of guessing where things are.** The box headers say exactly where the index is
> and how long it is.
