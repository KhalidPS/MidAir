# 2026-11-11: A lifted video is slow to show on B: before and after

This report puts the old approach next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-11/1431-video-index-prefetch` (off `fix/2026-11-11/1340-card-lag-during-video`, the last
  state confirmed on the phones). One commit: `22acb9f`, "fix: read a playing video's index ahead of its card".
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Video: a 676 MB phone recording, about 2.5 MB/s, with
  its index at the end of the file.
- **Phone result: confirmed.** Tested by the owner on 2026-11-11: "it starts sooner". All unit tests pass and the
  app builds.

---

## 1. The picture in one minute

**Symptom:** after lifting a playing video on A, B's half of the card took seconds to show a frame.

**Cause:** B learnt about the video only at the lift (`PlaybackOffer`). Its player then reads the file over the
Nearby link in 1 MB pieces, each a request and an answer. Before any picture data, the player reads the file's
header and index: four requests, one after another (offset 0, offset 377092, then two near the end of the file).

- Those four cost about 1.2 s in the morning, and 3.2 s in the slowest start of the day.
- That start (14:26:01) waited 441 + 928 + 1035 + 414 ms for first bytes. It took 6.1 s to the first frame in
  total, of which 2.2 s was picture data and buffer.
- A keeps playing meanwhile. So a late start also made B jump ahead and load again, 1.2 to 3.4 s each time.

**Before:** nothing of the file was on B when the card was lifted. Every start paid the four round trips.

**After:** as soon as a video plays on A and the link is on Wi-Fi, A tells B. B reads the file's header and index
in the background. At the lift, the player finds them already on B and goes straight to the picture data.

### Measured on B

First frame is the time from the player's load start to ready. "In step" is the time until B played in step with
A, including second loads and holds.

Before, same day, same phones, same video, all on Wi-Fi Direct. First frame in s: 3.3, 2.5, 4.5, 3.5, 3.0, 2.8,
4.2, 2.6, 3.5, 4.0, 3.1, 2.6, 2.4, 3.6, 3.4, 2.9, 4.1, 2.8, 2.5, 3.7, 3.6, 6.1. In step, where measured: 2.9 to
13 s. The two worst were 8.5 s and 13 s, at 14:25 to 14:26.

After, 14:59 to 15:01, four full slides:

| Slide | First frame | Hold, waiting for A | Second load | In step after |
|---|---|---|---|---|
| 14:59:49 | 2.4 s | none | no | 2.4 s |
| 15:00:25 | 1.1 s | 0.55 s | no | 1.8 s |
| 15:00:46 | 2.3 s | none | no | not measured |
| 15:01:04 | 1.5 s | 0.4 s | no | 1.95 s |

The 15:00:46 lift came 1 s after the previous card was pulled back, while the index was still being read again.
That read finished 0.15 s after the lift (see Limit 2).

| | Before | After |
|---|---|---|
| First frame on B | 2.4 to 6.1 s | 1.1 to 2.4 s |
| In step with A | 2.9 to 13 s | 1.8 to 2.4 s |
| Second loads (jumps) | 3 of 5, then 2 of 3 (earlier the same afternoon) | 0 of 4 |
| Card lateness, p50 | 41 to 66 ms (card-lag fix) | 39 to 43 ms |

The index read-ahead itself took 1.3 to 3.4 s in the background: 5 pieces, each answered in 84 to 410 ms. The card
lateness shows the read-ahead does not bring back the card lag.

---

## 2. The fix

**New message:** `SlideMessage.PlaybackHint(itemId, sizeBytes)`, with its DTO, mapper and validation.

**Sender, `ShelfViewModel.hintPlaying()`:** when a video starts playing on the shelf and the link is on Wi-Fi, it
shares the file with the peer and sends the hint. It also runs when the link becomes fast while a video plays, and
when a lifted card comes back. A video that is itself followed from the peer is not hinted.

**Receiver, `ShelfViewModel`:** on a hint, and only on Wi-Fi, it calls `MediaChannel.prefetch(fileId, sizeBytes)`.
At the lift, if the `PlaybackOffer` matches the hint's id and size, the file's kept pieces are no longer forgotten.
What was read ahead is used.

**`NearbyMediaChannel`, in `core/transfer/nearby`:**

- `prefetch()` reads the parts from `indexSpans()` (`FileSpans.kt`): the first 1 MB piece and the last 4 MB. It
  reads one piece at a time, and waits while a card is in the air or a player is waiting for pieces. The pieces are
  kept like any other.
- `open()` and its `PiecedStream` now serve a read from a kept piece when one covers the offset. A new `PiecePart`
  interface covers both `DrainedPiece` (arriving over the link) and `KeptPart` (already on the phone).
- They know where the file ends from the hinted size. So the player's header and index reads cost no round trip.

---

## 3. Limits

1. **Behaviour change.** The paired phone can now read the file of any video that is playing on the other phone,
   before its card is lifted. Before, only while the card was lifted. Two existing tests that stated the old rule
   were changed. The owner has been asked whether to restrict this to the index parts until the lift. Not decided
   yet.
2. **A pull-back drops the index.** When a card is pulled back, B lets go of everything it read of that video and
   reads the index again (1.3 to 2.7 s). A lift within that time starts without the full index (the 2.3 s case).
   Keeping the index across a pull-back is a possible follow-up.
3. **Needs Wi-Fi and a little time.** It only helps once the link is on Wi-Fi and the video has played long enough
   for the read-ahead to finish (1.3 to 3.4 s).
4. **Not changed here:** the remaining 1.1 to 2.4 s is picture data and the player's start-up buffer.
5. **Not changed here:** after a drop, B still stalled once or twice for 1.0 to 1.4 s in earlier tests.
6. **Small sample:** four slides, one pair of phones, one video.

---

## 4. Tests

- `FileSpansTest.kt`: 4 new tests for `indexSpans`.
- `SlideMessageCodecTest.kt`: `PlaybackHint` added to the existing valid and invalid lists.
- `ShelfViewModelLiveVideoTest.kt`: 7 new tests. The hint is sent when a video starts playing; not on a slow link;
  sent once the link gets fast. The index is read ahead on a hint; not on a slow link. The read-ahead is kept at the
  lift; kept data under another file's id is let go.
- Two tests that stated the old "only while lifted" rule were changed (Limit 1): one in `ShelfViewModelLiveVideoTest.kt`
  (a card that springs back), one in `ShelfViewModelAirVideoTest.kt` (a held card put back).

The piece-serving change inside `NearbyMediaChannel` (`KeptPart`, `PiecedStream`) has no unit test. It was checked
on the phones (Section 1). All unit tests pass and the app builds.

---

## 5. What makes the new approach better

- **The wait moves before the lift.** The four round trips still happen, but while the video plays on A, not while
  the user watches B's half of the card.
- **A shorter and steadier start.** First frame 1.1 to 2.4 s instead of 2.4 to 6.1 s, and the spread is much
  narrower.
- **The existing aim-ahead now lands close.** `PlaybackFollower` aims a fixed 2 s ahead of A. With a steady start
  that aim holds 0.4 to 0.55 s at most, with no jumps. So the "learnt aim" experiment
  (`fix/2026-11-11/1408-video-aim-ahead`, commit `e85dbc4`, not proven) is not needed for now and is not part of
  this branch.
- **It stays out of the card's way.** The read-ahead pauses while a card is in the air or a player waits, and card
  lateness stayed at p50 39 to 43 ms.

### Lesson

> **Fetch what is always needed before it is asked for.** Every start needed the same header and index. Reading
> them early turns a wait the user sees into one they don't.
