# 2026-11-11: Aim closer when a card is lifted again after a pull-back

This report puts the old aim next to the fix, explains **what went wrong**, **what we did instead**, and **what it
brings**.

- Branch: `fix/2026-11-11/1814-aim-closer-on-second-lift`. One commit: `733dbb0`, "fix: aim a video lifted again
  after a pull-back 0.6 s ahead, not 2 s". Built on `fix/2026-11-11/1740-catch-up-before-jumping`.
- Follow-up: `fix/2026-11-11/1840-aim-closer-only-after-playing`. One commit: `25e0f99`, "fix: aim a second lift
  closer only when the video had played here before the pull-back".
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Videos "2" and "14". All on Wi-Fi Direct (Nearby
  path). Times are B's clock.
- **Phone result: confirmed** for `733dbb0` in Test R, 18:17 to 18:20. Owner: "very very good".
- **Follow-up `25e0f99`: pending. Unit tests only, not tested on the phones.**

---

## 1. The picture in one minute

**First lift and second lift:** a first lift is a card lifted with nothing of its video read on B yet, beyond the
index read ahead. A second lift is the same card lifted again after a pull-back, while its video still plays on A.
Since the pull-back fix, B keeps what it read for that case.

**Old aim:** `PlaybackFollower` aimed every first load 2 s ahead of A's frame, because a first load takes about 2 s.

**What went wrong (Test Q, 18:10 to 18:12):** with what was read kept, a second lift was ready after 0.33 to
0.43 s. It still aimed 2 s ahead, so it arrived about 1.5 s early and stood still on its first frame. Ten of ten
second lifts held 1.3 to 1.6 s. They were in step with A after 1.8 to 2.1 s: no better than a first lift.

**After:** a second lift aims 0.6 s ahead. A first lift still aims 2 s ahead.

---

## 2. The fix

- **`733dbb0`:** `PlaybackFollower` takes how far ahead it aims. A new constant `SecondLiftLeadMs` = 600 ms. In
  `ShelfViewModel`, B uses it when the card is lifted again after a pull-back. Otherwise 2 s, as before.
- **`25e0f99` (follow-up):** the closer aim is used only when the video had actually played on B before the
  pull-back. A card pulled back before its first frame aims as a first load does. Its index is still kept.

A second lift that takes longer than 0.6 s after all is made up by playing faster, up to 1.5 s behind.

---

## 3. Before and after

| | Before (Test Q) | After (Test R) |
|---|---|---|
| Second lift aims ahead by | 2 s | 0.6 s |
| Second lifts that held their first frame | 10 of 10, for 1.3 to 1.6 s | none |
| First frame, second lift | 0.33 to 0.43 s | 0.30 to 1.5 s, most under 0.9 s |
| In step with A, second lift | 1.8 to 2.1 s | 0.30 to 1.5 s, most under 0.9 s |
| Jumps | 0 of 12 | 1 of 18, single |
| Card lag typical | 37 to 48 ms | 26 to 46 ms |

In Test R, B never stood still on its first frame. Every load was in step with A as soon as it showed its first
frame, except the one jump.

---

## 4. The weakness found in Test R

**18:19:46:** the card had been lifted, then pulled back before B showed a first frame. It was lifted again 3 s
later. That counted as a second lift, so it aimed 0.6 s ahead. But none of its pictures were kept: B had read only
the index. The load took 1.88 s, fell too far behind, and jumped once. It was in step after 4.7 s.

**The follow-up** (`25e0f99`) closes this case: B notes whether the video had played on it before the pull-back.
If not, the next lift aims 2 s ahead, like a first lift. This is not measured on the phones yet.

---

## 5. What it brings

- **No frozen frame on a second lift.** The video moves as soon as it appears.
- **In step sooner.** Most second lifts were in step in under 0.9 s, against 1.8 to 2.1 s before.
- **First lifts unchanged.** They keep the 2 s aim that matches how long they take.

---

## 6. Limits

1. **Wider spread of first frames.** Second lifts took 0.30 to 1.5 s in Test R, against 0.33 to 0.43 s in Test Q.
   When the card is pulled back quickly, B has read less to reuse.
2. **The follow-up is not tested on the phones.** Only its unit test shows it.
3. **One afternoon, one pair of phones.**

---

## 7. Tests

`ShelfViewModelLiveVideoTest.kt`: 3 tests in all.

- A video that played here and is lifted again after a pull-back aims closer.
- A video pulled back before it played here aims as a first load does when lifted again (follow-up).
- A video lifted again without having been said to play aims as a first load does.

### Lesson

> **A faster load needs a closer aim.** Once a second lift started in a third of a second, the 2 s aim only turned
> the gain into a frozen frame.
