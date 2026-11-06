# Multi-file lag: everything we tried

Last updated 6 Nov 2026. This is the one place for the work on "the receiver lags when several files are sent". It
covers what we saw, each fix (with its commit and branch), what the phone tests showed, and a comparison at the end.

## Where things stand

- **Base going forward:** `fix/slide-first-pull-pieces`, at `514b94d`.
- **Tested on the phones and working:** pulling files in pieces (`3be3def`) and card messages over Wi-Fi
  (`46df88d`). Wi-Fi felt smoother with a playing video, but it needs both phones on one Wi-Fi network.
- **Built, not tested yet:** batching the card moves (`514b94d`), which should bring back the smooth playing-video
  slide.
- **Still open:** a playing video starts later on the receiver than on `feature/smooth-card-half`. No cause has been
  found in the code yet (see [Open issue](#open-issue-the-video-starts-later-on-the-receiver)).

## Commit map

```
feature/smooth-card-half   4b9c2f3  (the baseline: smooth single slides, playing video included)
└─ 23cb2ec  air gestures, ported                                   feature/transfer-multi-files
   └─ b31b41b  Fix 1: files one at a time, per-file progress, stop, notifications
      └─ 2451102  Fix 2: send only the newest card move
         ├─ 3be3def  Fix 3: pull files in pieces, paused while a card is in the air
         │  └─ 514b94d  Fix 5: batch the card moves instead of dropping them   fix/slide-first-pull-pieces
         └─ 46df88d  Fix 4: card messages over the shared Wi-Fi                fix/slide-first-wifi-messages
```

Fix 3 and Fix 4 sit on separate branches on purpose, so each could be tested alone.

---

## The problem

### First video: `multi_file_problem.mp4` (both phones)

Several videos were slid over one after another. On the receiver:

- Each video took close to a minute, its card showing "Receiving · NN%" while it crawled.
- The arrival ring, a 1.1 s animation, took about **12 s** to spread (33 s to 45 s in the video).
- Cards ghosted while the grid reflowed.
- The sender's shelf was already empty while the receiver still showed a card coming in, so the receiver was seconds
  behind the sender.

### Second video: `share_multi_file_2.mp4` (receiver's screen, recorded after Fix 1)

We counted how often the receiver's picture actually changed, every half second:

- While a card really flew in (17.6–19 s), the phone drew about **28 frames a second**: it can draw the blur, the
  ring and the card at full speed.
- During the long blurred stretches with the ring (7.6–16 s, 20–25 s, 29–43 s), the picture changed only **2–8 times a
  second**, and the scene was nearly still: the ring crept forward in small steps.
- A card sat at the edge from 29 s to 43 s, then everything landed at once.

**What this told us:** the receiver wasn't failing to draw. It was waiting for the card's messages. The shelf stays
blurred while a card is coming in, so late messages show as a long blurred, frozen-looking screen.

### Why the messages were late

Nearby carries everything over one connection with no priorities: the file's bytes and the card's small messages (its
position about 60 times a second, and the let-go). While a file streams at full speed, the card's messages wait
behind its chunks, inside Nearby, where the app can't reach.

---

## Fix 1: files one at a time, per-file progress, stop and notifications

**Commit:** `b31b41b` · **Branch:** `feature/transfer-multi-files`

**What we thought:** every slid file started its own Nearby transfer at once. N videos shared one radio and the
receiver's storage, each went at 1/N speed, and the card messages waited behind a chunk of each. On top of that, every
1% of every file rebuilt the whole shelf state and redrew the grid.

**What changed:**

- **Send queue:** files go one at a time (`maxActiveSends = 1` in `LinkedSlidePeer`). A file waiting its turn is
  announced with `FileQueued`, so the receiver waits for it past the usual 15 s.
- **Per-file stop from either phone:** `FileCancelled(itemId, bySender)`. A stopped send puts the item back on the
  sender's shelf, with no error.
- **`TransferBoard`** (`:core:transfer:domain`): an app-wide list of every file moving either way, with status, bytes,
  speed and time left. Progress is coalesced to a few updates a second.
- **Progress kept out of the shelf state:** each arriving card reads its own transfer, so the grid no longer redraws
  on progress.
- **Transfers pill and sheet:** one row per file with its bar, speed, time left and ✕, plus Stop all and Clear.
- **Notifications:** a summary with "Stop all", one notification per moving file with its own "Stop", redrawn at most
  once a second, and "3 files received" at the end.
- **Saving:** received files are copied into Downloads one at a time, with 256 KB buffers.

**Phone test:** the files queued and arrived correctly, but the receiver's UI was **as laggy as before**.

**What we learned:** fixing how files compete with each other wasn't enough. Even one file at full speed held the
card's messages up.

---

## Fix 2: send only the newest card move

**Commit:** `2451102` · **Branch:** `feature/transfer-multi-files`

**What we thought:** the sender made one move message per drag frame, and each waited its turn behind a lock until
Nearby took it. With a file on the link, each took hundreds of milliseconds, so moves piled up by the thousand. The
receiver played the backlog back in slow motion, and the let-go waited behind all of it.

**What changed:** `ShelfViewModel.postMove` kept only the newest unsent move. While one went out, a newer one replaced
the waiting one, and none went out after the card was let go.

**Phone test:** the UI was **still laggy**, the same as before.

**What we learned:**

- Our own queue wasn't the real bottleneck. Even the newest move still waited behind the file's bytes inside Nearby,
  where this change couldn't reach.
- It had a side effect we only saw later: it threw away positions the receiver needs to draw the card's path evenly
  (see Fix 5).

---

## Fix 3 (option 1): pull files in pieces, paused while a card is in the air

**Commit:** `3be3def` · **Branch:** `fix/slide-first-pull-pieces`

**The idea:** don't let a file hog the link while a card is in the air. Since Nearby can't pause a file transfer, the
receiver asks for the file in small pieces, and simply stops asking while a card is moving.

**What changed:**

- On Wi-Fi (the cyan dot), the receiver pulls each file in **1 MB pieces** over the media channel. That path was
  already tested on the phones for playing videos; each piece is drained, and the whole file is checked against the
  sender's SHA-256.
- Files are pulled **one at a time** (`Delivery.PULL`).
- While any card is in the air, either way, the receiver asks for **no new piece** (`MediaChannel.holdFetches`,
  15 s at most). Only pieces already on their way come in.
- The sender shares the file, waits for the pull, and shows how much has been taken.
- If a pull fails, the file is sent the old way. On Bluetooth nothing changes.

**Phone test:** **works**. Slides stay smooth while files move. But a single playing video was less smooth than on
`feature/smooth-card-half`. That was later traced to Fix 2, which this branch is built on.

**Log line to look for:** `Fetching …: held N ms for a card in the air`.

**Costs:**

- Pieces may move a little slower overall than one big transfer.
- The receiver writes each pulled file twice: once as pieces, once put together. It is copied into Downloads
  after that.

---

## Fix 4 (option 3): card messages over the shared Wi-Fi

**Commit:** `46df88d` · **Branch:** `fix/slide-first-wifi-messages`

**The idea:** give the card's messages a connection of their own, apart from Nearby. This also tests the diagnosis:
if lag stayed even with messages off Nearby, the link wasn't the cause.

**What changed:**

- When the phones link up, each listens on its Wi-Fi address and hands the other its address and a random 16-byte
  token, over the encrypted Nearby link (`LinkFrame.LanOffer`).
- Each connects to the other's listener (`LanMessageLink`), and every slide message goes over that TCP connection.
  Files stay on Nearby.
- A connection that doesn't open with the token is dropped, and offers are only followed to local addresses.
- If the connection breaks, messages fall back to Nearby.

**Phone test:** **works**, and felt **smoother than Fix 3 with a playing video**. It was still less smooth than on
`feature/smooth-card-half`, because it is also built on Fix 2.

**Log lines to look for:** `AirLan: Card messages go over Wi-Fi to …`. If you see `doesn't answer from here`, the
router blocks phones from talking to each other.

**Limit:** both phones must be on the same Wi-Fi network. In public places there often is none, and public Wi-Fi
often blocks devices from reaching each other. So it can only be an extra, never the main fix.

---

## Option 2: the sender paces a stream (not built)

**The idea:** the sender sends each file as a stream it writes itself, and stops writing while a card is in the air.

**Why we skipped it:** it tests the same idea as Fix 3 (pause the file's bytes during a slide); only the phone doing
the pausing differs. If Fix 3 failed, this would fail for the same reason. It would also be new code on the
stream path, which caused link stalls before (cancelled or unread streams, see the 25 Oct notes).

---

## Fix 5: batch the card moves instead of dropping them

**Commit:** `514b94d` · **Branch:** `fix/slide-first-pull-pieces` (on top of Fix 3)

**What we found:** both Fix 3 and Fix 4 made a single playing-video slide less smooth than on
`feature/smooth-card-half`:

- The smooth-card work (C0–C4) makes the receiver's half follow by playing back every position your finger went
  through, at the pace it was drawn.
- A playing video keeps Nearby busy with its pieces, so position messages often wait a moment.
- On `feature/smooth-card-half` they all still arrived, a little late, so the playback had every point.
- Fix 2 threw away every position that had to wait. The receiver drew straight lines between the few left, so its half
  stepped and lagged behind the sender's.
- On the Wi-Fi branch fewer positions waited, so fewer were lost. That's why it felt better there.

**What changed:**

- Moves made while one message goes out all go **together** in the next message (`SlideMessage.DragMoves`, at most the
  last 30, about half a second of frames).
- The receiver plays each one back as if it had come alone.
- Every position arrives again, and never more than one message waits.
- A single move still goes as a plain `DragMove`, and nothing goes out after the card is let go or put back.

**Phone test:** not done yet. Expected: the receiver's half follows as smoothly as on `feature/smooth-card-half`, and
slides stay smooth while files move (Fix 3 underneath).

**Note:** the 1 MB piece size isn't involved. It is the same on `feature/smooth-card-half`; it went from 4 MB to 1 MB
earlier, in the live-video work (`e01d5e8`).

---

## Open issue: the video starts later on the receiver

On both test branches, a single playing video started playing on the receiver later than on
`feature/smooth-card-half`. The air-gesture camera was off.

**Checked in the code, and found no change in:**

- how the slide starts: the start message and the video offer go out the same way, in the same order. The air-gesture
  port moved that code into shared helpers without changing what it does;
- how the receiver opens the video: same pieces, same Wi-Fi check, same steering to the sender's frame;
- the transfer board and notifications: they only start once the card lands;
- Fix 2 and Fix 5: they don't delay the video's messages.

**Worth ruling out:** on the first transfer after installing, Android 13+ shows the notification-permission dialog over
the app, which briefly pauses the screen.

**How to find it:**

1. **Logs:** on the receiver, filter `AirLink|AirVideo|AirLan` while sliding one playing video, once on
   `feature/smooth-card-half` and once on the current branch. Compare `Piece of … at 0 opened in N ms` and
   `Wi-Fi path reachable (N ms)`.
2. **Two in-between builds:** install `23cb2ec` (air gestures only) and `b31b41b` (multi-file transfers). The first one
   that starts late is the commit to look at.

---

## Comparison

| | Fix 1 | Fix 2 | Fix 3 (option 1) | Fix 4 (option 3) | Option 2 | Fix 5 |
| --- | --- | --- | --- | --- | --- | --- |
| **Commit** | `b31b41b` | `2451102` | `3be3def` | `46df88d` | — | `514b94d` |
| **Branch** | `feature/transfer-multi-files` | `feature/transfer-multi-files` | `fix/slide-first-pull-pieces` | `fix/slide-first-wifi-messages` | — | `fix/slide-first-pull-pieces` |
| **What it targets** | Files competing with each other; progress redrawing the grid | Our own backlog of move messages | File bytes holding card messages up inside Nearby | Same, by moving card messages off Nearby | Same as Fix 3, paused by the sender | Too few positions for even playback |
| **How** | Queue, board, per-file stop, notifications | Keep only the newest move | Receiver pulls 1 MB pieces, pauses during slides | Token-guarded TCP socket on the shared Wi-Fi | Sender writes its own stream, pauses during slides | Send all waiting moves together |
| **Works without a shared Wi-Fi** | Yes | Yes | Yes | No | Yes | Yes |
| **Multi-file lag (phone test)** | Not fixed | Not fixed | Fixed | Fixed | Not built | Not tested (keeps Fix 3) |
| **Playing video, single slide** | Unchanged | Worse (lost positions) | Worse (inherits Fix 2) | Slightly worse (inherits Fix 2, fewer losses) | — | Expected back to the baseline |
| **Main cost or risk** | None found | Broke even playback | Slightly slower files; extra disk writes | Needs a shared network; extra open socket | Stream path stalled the link before | Messages a little bigger while busy |
| **Status** | Kept: needed for queue, progress, stop, notifications | Superseded by Fix 5 | Kept: the base | Optional extra | Skipped | Kept: on top of Fix 3 |

## Recommended path

1. **Test `fix/slide-first-pull-pieces` (Fix 3 + Fix 5)** on both phones: one playing video alone, then several files
   followed by a slide.
2. **If the video still starts late,** find the cause with the logs or the two builds in the open issue.
3. **Optionally, add Fix 4 on top** as an automatic extra, used only when both phones share a Wi-Fi network. Check
   that the phone being on both its Wi-Fi and Nearby's own link doesn't slow the card messages.
4. **Once the phones confirm it,** merge the chosen branch into `feature/transfer-multi-files`.

## Phone checklist

1. **One playing video:** does the receiver's half follow your finger as smoothly as on `feature/smooth-card-half`?
   Does the video start on the receiver as quickly?
2. **3–4 videos slid quickly:** does the receiver stay smooth? Does the arrival ring spread in about 1 s? Does the
   blur clear right after each landing?
3. **The pill on both phones:** one row per file, each with a bar, speed and time left.
4. **✕ on a waiting file on the receiver:** its card goes, and the item comes back on the sender's shelf.
5. **✕ on the moving file on the sender:** the receiver's card goes, and the next file starts.
6. **Lock the sender and pull down the shade:** the summary, one notification per file, and "Stop" on each.
7. **Logs worth saving:** `AirLink`, `AirSlide`, `AirVideo` and `AirLan`, plus `NearbyConnections` in the GMS
   process.
