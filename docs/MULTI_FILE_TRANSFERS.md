# Several files at once

What `multi_file_problem.mp4` showed (6 Nov 2026): several videos slid over one after another made the receiving
phone crawl. The arrival ring, a 1.1 s animation, took about 12 s to spread (33 s to 45 s in the video). Each file
took close to a minute, and cards ghosted while the grid reflowed.

## Why it was slow

1. **Every slide started its own transfer at once.** N videos shared one radio and one socket, so each went at 1/N
   speed and none finished early. The receiver's storage took N writes at once (Nearby writes in the GMS process).
   Every card message (`DragMove`, the commit) waited behind a chunk of each file.
2. **Every 1% of every file rebuilt the whole shelf state.** Each reading launched a coroutine, copied the item,
   arriving and arrivedFrom lists, and recomposed the staggered grid. With several videos, that came to hundreds of
   grid passes a minute on a low-end phone.
3. **Each received file was then copied in 8 KB steps** to Downloads, while the next files were still being written.

## What changed

| Piece | Where | What it does |
| --- | --- | --- |
| `TransferBoard` | `:core:transfer:domain` | App-wide list of every file moving either way: status (queued, active, saving, done, failed, stopped), bytes, smoothed speed, time left, and a stop per file. Coalesces readings (≥ 250 ms apart unless the whole percent changes). Thread-safe. |
| Send queue | `LinkedSlidePeer` | Files go **one at a time** (`maxActiveSends = 1`). A waiting file is announced as `FileQueued`, so the receiver waits for it without the 15 s limit. |
| Per-file stop | `LinkedSlidePeer`, `SlideMessage.FileCancelled(itemId, bySender)` | Either phone stops one file, queued or moving. The other side is told and drops it. A stopped send puts the item back on the sender's shelf, with no error. |
| Progress in bytes | `PeerLink.sendFile/receiveFile` | Both directions report bytes, so the sender sees its own progress too. |
| Saves one at a time | `LinkedSlidePeer.keep`, `AndroidReceivedFileStore` | One copy into Downloads at a time, with 256 KB buffers. The card shows "Saving" meanwhile. |
| Progress off the shelf state | `ShelfViewModel.fileTransfers`, `ArrivingCard` | The grid no longer changes on progress. Each arriving card reads its own transfer and redraws alone. |
| Transfers pill and list | `components/Transfers.kt` | The pill shows "Sending 2 · Receiving 1" with an overall ring. A tap opens a sheet: one row per file with its bar, "34 MB of 80 MB · 4.1 MB/s · 12 s left" and a ✕, plus Stop all and Clear. |
| Notification | `app/.../TransferService.kt` | Summary (all files together, "Stop all") plus one notification per moving file (its own bar and "Stop"), at most 4. Redrawn at most once a second (Android drops faster updates). When everything stops: "3 files received" / "Sent “x”" / "A file didn't make it". The permission is asked the first time a file moves (Android 13+). |

Protocol: `FileQueued` and `FileCancelled` are new. An older build skips them as unknown, and its files still
arrive, but it sends files all at once as before.

## Check on the two phones

1. Slide 3–4 videos over quickly, one after another.
   - The receiver should stay smooth: the arrival ring spreads in about 1 s, not 10.
   - The first card counts up while the others say "Waiting its turn", then each starts in order.
2. Open the pill on both phones: one row per file, a bar each, speed and time left.
3. Tap ✕ on a waiting file on the **receiver**. Its card goes, and the sender's item comes back to its shelf.
4. Tap ✕ on the moving file on the **sender**. The receiver's card goes and the next file starts.
5. Lock the sender and pull down the shade: the summary, one notification per file, and Stop on each. When all are
   done: "3 files received" on the receiver.
6. Logs worth saving: `AirLink` (card messages paused), `AirSlide`, and `NearbyConnections` in the GMS process.

## Second run (share_multi_file_2.mp4): still laggy on the receiver

The files queued correctly, but the receiver still felt slow. The recording shows the phone draws at full rate when
there is something to draw: about 28 frames a second while a card flies in. During the long blurred stretches with
the ring (7.6–16 s, 20–25 s, 29–43 s), the picture changes only 2–8 times a second, and a card sits at the edge for
14 s before everything lands at once.

The cause was the sender, not the receiver's animations. Every drag frame made one `DragMove`, and each one waited its
turn behind a lock until Nearby took it. With a file on the same link, that takes hundreds of milliseconds per message,
so moves piled up by the thousand. The receiver played the backlog back evenly, which looked like slow motion. The shelf
stayed blurred the whole time (it blurs while a card is coming), and the let-go waited behind the whole backlog.

Fix: `ShelfViewModel.postMove` keeps only the newest unsent move. While one goes out, newer ones replace the waiting
one, and none goes out after the card was let go or put back.

Still expected: while a file moves, the receiver's half gets fewer positions per second (as many as the link lets
through), so it can look steppier than with no file moving. But it stays current, and the let-go arrives at once.
Making it fully smooth during a transfer would need the file's bytes to pause while a card is in the air. Nearby can't
pause a file payload, so that means sending files through a stream this app paces.
