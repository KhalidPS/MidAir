# 2026-11-10: Receive the peer's files while the shelf screen is closed

This report puts the old behaviour next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-10/1846-receive-with-screen-closed`. One commit: `faa08ca`, "fix: receive the peer's files
  while the shelf screen is closed".
- Merged with `--no-ff` into `fix/2026-11-10/merge-all-fixes` (10 Nov), and from there into
  `feature/transfer-multi-files` (11 Nov), together with reports 01 and 03.
- Phones: A = M2101K6G, B = Redmi Note 8.
- **Phone result: not measured on matched builds.** See Section 5.

---

## 1. The picture in one minute

**Found:** in the phone test of 10 Nov. An image was sent to a phone whose app had been swiped away. The image was
lost: it was not on that phone's shelf, and not in Downloads.

**Why:** receiving a file (taking the card, saving the file, adding it to the shelf) was done only by the shelf
screen's `ShelfViewModel`. After a swipe-away, the foreground service could keep the connection up, and the other
phone could still slide a card over. But no screen was there to listen for the card, so nobody took the file in.

**After:** an app-wide `UnattendedReceiver` takes in the file when no shelf screen is open.

---

## 2. The fix

- **New class `UnattendedReceiver`** (`feature/shelf/domain`). It is created when the app starts
  (`createdAtStart = true`, in `unattendedReceiverModule`, added in `AirApplication`). It runs in the app-wide
  transfer scope.
- **It follows the peer's cards** from their start (`DragStart`) to their let-go (`DragCommit`) or withdrawal
  (`DragCancel`). It remembers at most 8 cards.
- **It acts only when no screen is open.** Each `ShelfViewModel` calls `attend()` when it is made and releases it in
  `onCleared()`. While a screen is open, the screen takes the cards itself, as before.
- **It receives the file the same way a screen would,** without the card's flight:
  - on Wi-Fi, pulled in pieces;
  - if the card offered its video, it asks for the file to be sent (nothing plays it here);
  - otherwise, the file is sent as usual.
- **Progress is shown** on the transfer board, and the app is kept alive while the file comes in. The file lands on
  the shelf and in Downloads, and the next screen shows it.

---

## 3. Before and after

| | Before | After |
|---|---|---|
| Card let go while no screen is open | nobody takes the file; it is lost | `UnattendedReceiver` takes it in |
| File on the shelf and in Downloads | no | yes |
| Progress while it comes in | none | on the transfer board |
| Screen open | screen receives | screen receives (unchanged) |

There are no timing numbers. This is a correctness fix: the file is kept or it is not.

---

## 4. What makes the new approach better

- **The connection and the receiving now live equally long.** The link already outlived the screen. Now the part that
  takes files in does too.
- **No file received twice.** While a screen is open, it alone receives. A unit test checks this.
- **Together with report 03,** the sender also learns whether the file was really kept, so a file can no longer end
  up on neither phone.

---

## 5. Limits

1. **Not measured on matched builds.** On the evening of 10 Nov the user tested and said everything worked. On 11 Nov
   the Redmi Note 8 was found still on a 10 Nov 17:35 build, without this fix. So that test did not run it. On
   11 Nov, with the same build on both phones, a drop was confirmed to work. The swipe-away case itself has no
   recorded result on matched builds.
2. **A video has no arriving card.** A video sent to a phone whose app was swiped away is not shown while it arrives.
   It only shows once it is fully received.
3. **Only 8 cards are remembered** from their start. A card older than that, never let go nor withdrawn, is forgotten.

---

## 6. Tests

`UnattendedReceiverTest.kt` (new, `feature/shelf/domain`), 8 tests:

- With no screen open, a let-go card's file is received and listed.
- The app is kept alive while the file comes in.
- While a screen is open, the peer's cards are left to it.
- Once the screen is gone, a card it saw start is received here.
- A release called twice counts once.
- A card withdrawn before its let-go is not received.
- A card that offered its video asks for the file to be sent.
- A file that fails is listed as failed.

`ShelfViewModelSlideTest.kt`, 1 new test: while this screen is open, the peer's file is received once, here, not by
the unattended receiver.

### Lesson

> **If the link can outlive the screen, so must everything that answers the link.**
