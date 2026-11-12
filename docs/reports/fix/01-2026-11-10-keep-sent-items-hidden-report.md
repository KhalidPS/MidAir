# 2026-11-10: Keep sent items hidden when the app is reopened during a send

This report puts the old behaviour next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-10/1843-keep-sent-items-hidden`. One commit: `2e088ad`, "fix: keep sent items hidden when the
  app is reopened during a send".
- Merged with `--no-ff` into `fix/2026-11-10/merge-all-fixes` (10 Nov), and from there into
  `feature/transfer-multi-files` (11 Nov), together with reports 02 and 03.
- Phones: A = M2101K6G, B = Redmi Note 8.
- **Phone result: not measured on matched builds.** See Section 5.

---

## 1. The picture in one minute

**Found:** in the phone test of 10 Nov. The sending phone's app was swiped away while a file was still going out.
When the app was opened again, the sent item was back on the shelf. It only went away when the send finished.

**Why:** the list of sent items lived in the shelf screen's `ShelfViewModel`, as a plain `mutableSetOf<String>()`.
Swiping the app away cleared the screen and its list. But the send itself runs in the app-wide transfer scope, so it
went on. The new screen had an empty list, so it showed the item as if it was never sent.

**After:** the list of sent items is app-wide. A screen opened in the middle of a send still hides the item.

---

## 2. The fix

- **New class `SentItems`** (`feature/shelf/domain`). It holds the ids of sent items in a `StateFlow<Set<String>>`.
  It can add an id, remove it (the item comes back), and clear all.
- **One for the whole app.** It is bound as a `single` in `shelfDataModule`, and every `ShelfViewModel` gets the same
  one.
- **`ShelfViewModel` watches it.** When the set changes, the screen publishes its items again. So a send started by an
  earlier screen, and ending while a new screen is open, updates the new screen: the item goes for good, or it comes
  back if the send failed.

Nothing else in the send changed.

---

## 3. Before and after

| | Before | After |
|---|---|---|
| Where sent items are kept | in one screen's ViewModel | app-wide `SentItems` |
| App swiped away mid-send, then opened | item shown again on the shelf | item stays hidden |
| That send then ends well | item goes away (late) | item stays gone |
| That send then fails | item was already shown | item comes back on the shelf |

There are no timing numbers. This is a correctness fix: the question is only whether the item is shown or not.

---

## 4. What makes the new approach better

- **The shelf tells the truth.** An item that is on its way to the other phone is not shown as if it were still here.
- **The list lives as long as the send.** The send was already app-wide. Now the list of sent items is too, so the two
  can no longer disagree.
- **A failed send still brings the item back,** also on a screen that did not start the send.

---

## 5. Limits

1. **Not measured on matched builds.** On the evening of 10 Nov the user tested and said everything worked. On 11 Nov
   the Redmi Note 8 was found still on a 10 Nov 17:35 build, without this fix. So that test did not run it. On
   11 Nov, with the same build on both phones, a drop was confirmed to work. The swipe-away case itself has no
   recorded result on matched builds.
2. **Only in memory.** `SentItems` is kept in memory, not on disk. It covers a closed screen, not a killed app
   process. What happens when the whole process is killed during a send was not looked at.
3. **Not tested by hand:** a send that fails after the app was reopened. Only the unit test covers it.

---

## 6. Tests

`SentItemsTest.kt` (new, `feature/shelf/domain`), 3 tests:

- An item is marked once.
- Bringing an item back says whether it was marked.
- Clearing brings every item back.

`ShelfViewModelSlideTest.kt`, 2 new tests:

- A screen opened while a file is still sent keeps its item hidden, and it goes once the send ends.
- A screen opened while a file is still sent shows its item again when the send fails.

### Lesson

> **State must live as long as the work it describes.** The send was app-wide, but the list of sent items was not.
