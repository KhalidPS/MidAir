# 2026-11-10: Count a sent file as done only once the receiver says it kept it

This report puts the old behaviour next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-10/1849-confirm-file-kept`. One commit: `97c6009`, "fix: count a sent file as done only once
  the receiver says it kept it".
- Merged with `--no-ff` into `fix/2026-11-10/merge-all-fixes` (10 Nov), and from there into
  `feature/transfer-multi-files` (11 Nov), together with reports 01 and 02.
- Phones: A = M2101K6G, B = Redmi Note 8.
- **Needs the same build on both phones.** See Section 5.
- **Phone result: not measured on matched builds.** See Section 5.

---

## 1. The picture in one minute

**The aim:** a file must never end up on neither phone.

**Before:** a sent file counted as done as soon as its bytes were through. But the bytes could reach the receiver
without anything there taking them in: no screen listening (the case in report 02), or saving failed. The sender
removed the item anyway. The file was then on neither phone.

**After:** the receiver answers every sent file it takes in. The sender keeps the item until it hears that the file
was kept. If it never hears it, the item comes back on the sender's shelf. At worst a file is on both phones, never on
neither.

---

## 2. The fix

- **New message `FileKept(itemId)`** (`SlideMessage`, with its DTO, mapper and validation).
- **Receiver** (`LinkedSlidePeer.receive`): after it tries to keep the file, it answers:
  - `FileKept` when the file is saved and on its shelf;
  - `FileCancelled(bySender = false)` when it could not keep it.
- **Sender** (`LinkedSlidePeer.send`): after the last bytes, it waits (`awaitKept`). Its progress moves to the saving
  step meanwhile. The send:
  - succeeds on `FileKept`;
  - fails on a refusal, or if this user stopped it;
  - fails if the link goes;
  - fails if no word comes within 3 minutes (`KeptWait`).
  A failed send brings the item back on the sender's shelf.
- **The next file may start** while the receiver still saves the previous one. The send slot is freed when the bytes
  are through, not when the word comes.
- Pulled and fetched files already waited for the receiver's word. They are unchanged.

---

## 3. Before and after

| | Before | After |
|---|---|---|
| Send counts as done | when the bytes are through | when the receiver says `FileKept` |
| Receiver could not keep the file | item removed on sender; file on neither phone | item comes back on sender |
| Link drops after the bytes | item removed on sender | item comes back on sender |
| Receiver never answers | item removed on sender | item comes back after 3 minutes |

There are no timing numbers. This is a correctness fix.

---

## 4. What makes the new approach better

- **The sender no longer guesses.** It removes its copy only when the other phone says it has one.
- **The safe failure is a copy, not a loss.** Any doubt ends with the file on both phones.
- **No slower queue.** The next file does not wait for the receiver to finish saving.

---

## 5. Limits

1. **Both phones need this build.** An older receiver never sends `FileKept`. Then every sent file fails after
   3 minutes and comes back on the sender's shelf.
2. **Not measured on matched builds.** On the evening of 10 Nov the user tested and said everything worked. On 11 Nov
   the Redmi Note 8 was found still on a 10 Nov 17:35 build, without this fix. So that test did not run it. On
   11 Nov, with the same build on both phones, a drop was confirmed to work. The failure cases (no answer, a
   refusal, the link going after the bytes) have no recorded result on the phones.
3. **No message when the app is closed.** A send that fails while the app is closed tells the user nothing.
4. **One fixed wait for every size.** The 3 minutes are the same for a small image and a large video. A planned
   "still saving" signal from the receiver would replace this fixed wait. It is part of a planned transfer
   coordinator, which is not started.

---

## 6. Tests

`LinkedSlidePeerTest.kt` (`feature/shelf/data`), 7 new tests:

- A sent file is done only once the peer says it kept it.
- A file the peer couldn't keep fails the send.
- The link going before the peer says it kept the file fails the send.
- A peer that never says it kept the file fails the send in the end.
- A word that the file was kept, heard before its send, doesn't end a later send.
- A received file is reported kept to the sender.
- A received file that can't be kept is reported to the sender as stopped.

`SlideMessageCodecTest.kt`: `FileKept` added to the messages that must encode and decode.

### Lesson

> **Bytes sent are not a file kept.** Only the receiver knows that, so only the receiver can say it.
