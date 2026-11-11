# 2026-11-11: Catch up by playing faster before jumping, and aim a jump further ahead

This report puts the old catch-up rule next to the fix, explains **what went wrong**, **what we did instead**, and
**what it should bring**.

- Branch: `fix/2026-11-11/1740-catch-up-before-jumping`. One commit: `94c829d`, "fix: catch up by playing faster up
  to 1.5 s behind, and aim a jump 2 s ahead". Built on `fix/2026-11-11/1645-keep-index-on-pull-back`.
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Video "2": a 676 MB phone recording, about 2.5 MB/s.
- **Phone result: pending.** Installed on both phones, not tested yet. Everything below about its effect is expected,
  not measured. All unit tests pass.

---

## 1. The picture in one minute

**Background:** B's half of the card loads the video while A keeps playing. Once B plays, `PlaybackFollower`
compares B's frame with A's and corrects the gap.

**Old rule:**

- Up to 1.0 s behind A: catch up by playing faster, up to 1.5 times. The video on B is muted while it follows, so
  the faster pace is not audible.
- More than 1.0 s behind: jump. B drops what it loaded and loads again further ahead. Until a jump has been timed,
  it aims 1.0 s ahead of A. After that, 1.25 times the measured jump plus 150 ms, at most 2.0 s.

**What went wrong, 17:07:45 (video 2):**

1. B's first frame came after 2.6 s. It had aimed 2 s ahead, so it was 1.01 s behind A: one hundredth of a second
   over the limit. It jumped, from 4,731 to 6,742 ms in the video.
2. That reload took 2.1 s, but it had aimed only 1 s ahead. So it was behind again, and jumped a second time, from
   7,043 to 10,071 ms. That reload took 1.9 s.
3. B played in step with A after 8.3 s. The other slides of that test took 1.2 to 2.4 s.

Jumps measured across the day took 1.2 to 3.4 s each. A first aim of 1.0 s was shorter than any of them.

---

## 2. The fix

Three constants in `PlaybackFollower` (`feature/shelf/domain`):

| | Old | New |
|---|---|---|
| Catch up by playing faster, up to | 1.0 s behind | 1.5 s behind |
| A first jump aims ahead by | 1.0 s | 2.0 s |
| The learnt aim, at most | 2.0 s | 3.0 s |

The rest is unchanged: up to 1.5 times speed, muted; the learnt aim is still 1.25 times the measured jump plus
150 ms.

**Why 1.5 s:** at 1.5 times speed, B gains half a second every second, so 1.5 s behind is made up in 3 s. During
those 3 s the picture moves. A jump shows a still frame for 1.2 to 3.4 s, then still has to land in step.

---

## 3. Before and after

| | Before | After (expected, not measured) |
|---|---|---|
| 17:07:45 start, 1.01 s behind | jumped | catches up by playing faster |
| A first jump, about 2 s long | aimed 1 s ahead, lands behind, jumps again | aimed 2 s ahead, lands close |
| Jumps in the 17:07:45 slide | 2 | 0 |
| In step with A, 17:07:45 slide | 8.3 s | about 2.6 s plus the time to catch up |
| First frame on B | unchanged | unchanged |

Measured before, in the 17:06 to 17:09 test: jumps 1 of 6 slides, in step 1.2 to 8.3 s.

---

## 4. What it brings

- **Fewer jumps.** A start up to 1.5 s late is made up by playing faster, with a moving picture all along.
- **One jump instead of two** when a jump does happen. The first aim now matches how long jumps took on these
  phones, so it should land close instead of behind.
- **Room for slow jumps.** The learnt aim may now go to 3.0 s, which covers most of the 1.2 to 3.4 s measured.

**What it does not do:** make the first frame come sooner. That is the loading, not the aim.

---

## 5. Limits

1. **Not tested on the phones yet.** The effect above is expected from the 17:07:45 log, not measured.
2. **A visible speed-up.** Up to 3 s at 1.5 times speed instead of up to 2 s. It is muted, but a faster picture may
   be noticed.
3. **An early jump holds longer.** When a jump is quicker than 2 s, B now holds its frame for the difference.
4. **A jump slower than 3.4 s** would still land behind. None was measured.
5. **The one case behind it is a single slide.** Other slides of the test played straight on or held.

---

## 6. Tests

`PlaybackFollowerTest.kt`, in `feature/shelf/domain`:

- Existing tests updated to the new numbers (1.5 s, 2.0 s, 3.0 s). The test on a slow jump is renamed to "a slow
  jump never makes it aim more than a few seconds ahead".
- New: a second and a half behind, it still catches up by playing faster.

All unit tests pass.

### What to look for in the phone test

- A start 1.0 to 1.5 s late: B should play faster for a few seconds, not jump.
- A jump: B should land in step or hold briefly, not jump a second time.

### Lesson

> **Set a limit from what was measured, not from a round number.** Jumps took about 2 s on these phones, so a jump
> aimed 1 s ahead was always going to land behind.
