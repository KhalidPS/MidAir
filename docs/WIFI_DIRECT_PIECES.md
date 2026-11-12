# Pieces over Wi-Fi Direct: the full version of the direct path

Written 12 Nov 2026. Branch: `feature/2026-11-12/pieces-over-wifi-direct`, off `main`.
**Status: built, unit tests pass, two rounds compared with `main` on the phones (Section 7). Round two is promising; not merged.**

Read `docs/LIVE_VIDEO_HISTORY.md` first if the words below are new (piece, lift, pull-back, index, Nearby, Wi-Fi
Direct group).

---

## 1. The idea in one minute

- **Today:** phone B reads a video that plays on phone A in pieces of 1 MB. Every piece travels over Nearby, on the
  one connection that also carries every other payload. A piece took 0.1–1.1 s to start coming on 11 Nov, and
  0.2–1.5 s on 12 Nov. A first lift needs several pieces before it can show a frame, so it costs 1.4–2.8 s on a good
  day.
- **The first try (11 Nov, parked):** B's video player read the file directly over HTTP on the Wi-Fi Direct group.
  It was not faster, and a second lift became much slower. The reason: the player went around everything B keeps
  (the index read ahead, the pieces kept on a pull-back), and opened a new connection for every range it read.
  Report: `docs/reports/fix/12-2026-11-11-video-over-wifi-direct-report.md`.
- **This version:** nothing changes in what B keeps or in how the player reads. Only the way one piece travels
  changes: it goes over a TCP connection of our own on the Wi-Fi Direct group, and that connection is already open
  when the piece is asked for. Nearby stays as the fallback.

So this version keeps what made the Nearby path good (0.3–0.4 s on a second lift) and takes away what made it slow
(Nearby's cost per piece).

---

## 2. Before and after, for one piece

| Step | Over Nearby (before) | Over our own connection (this branch) |
| --- | --- | --- |
| B asks | a `RangeRequest` frame, as a Nearby payload | 3 fields on an open TCP connection: offset, length, file id |
| A answers | a `RangeOpened` frame, then a stream payload | the length (8 bytes), then the bytes |
| Shares the connection with | card messages on Nearby, files, every other piece | nothing: one piece per connection at a time |
| Connection set up | by Nearby, once | by us, once, 4 opened at pairing and kept open |
| Stopping a piece early | never (it can stall the whole Nearby link) | closing its connection is safe |
| When it fails | the piece is lost | B asks the same piece over Nearby |

What stays exactly the same:

- B drains every piece into a file and keeps it (`DrainedPiece`, `KeptPiece`).
- The index is read ahead before the lift, and what was read is kept on a pull-back.
- Before a card is lifted, A lets B read only the header and index of a playing video.
- After the drop, the rest of the file is fetched in pieces, put together and checked against A's SHA-256.
- A fetch still waits while a card is in the air, and lets the player's pieces go first.

---

## 3. How it works

```
        phone A (shares the video)                     phone B (plays it)
        --------------------------                     ------------------
 pairing  listens on its Wi-Fi Direct address
          sends PieceOffer(address, port, token)  -->  opens 4 TCP connections, each starts with the token
          (over Nearby, which is encrypted)            keeps them open and idle

 a piece                                          <--  on an idle connection: offset, length, file id
          checks: shared? part readable yet?
          length, then the bytes                  -->  drains them into a file, keeps the piece
                                                       the connection goes back to the idle ones

 refused  -1                                      -->  B gives that piece up (Nearby would refuse it too)
 broken   (no answer, or the connection died)          B tries once on a new connection, then asks over Nearby
```

- **Both directions:** each phone listens and each connects, so either phone can be the one that shares.
- **Which network:** the Wi-Fi Direct group when Nearby has opened one, otherwise the Wi-Fi network both phones are
  on. This is the same rule, and the same address, as the card messages' connection.
- **Security:** the address and a random 16-byte token go over Nearby only. A connection that does not start with
  the token is dropped. What may be read is decided by the same rule as over Nearby.
- **Old builds:** a phone that does not know `PieceOffer` ignores it, and both phones go on over Nearby.

---

## 4. The code

All of it is in `core/transfer/nearby`. Nothing changes in the shelf screen or its ViewModel.

| File | What |
| --- | --- |
| `LanPieceLink.kt` (new) | The listener, the pool of open connections, one request and its answer. |
| `Reconnect.kt` | New frame `LinkFrame.PieceOffer` (type 11), same shape as `LanOffer`. |
| `NearbyMediaChannel.kt` | `openPiece` asks over Wi-Fi first, then over Nearby. `servePiece` answers with the same sharing rule. |
| `NearbyPeerLink.kt` | Listens for pieces where it listens for card messages, sends the offer, connects on the other's offer. |
| `LanPieceLinkTest.kt` (new) | 11 tests on the loopback: whole pieces, pieces at once, the file's end, refusals, a wrong token, a lost listener. |
| `LanOfferFrameTest.kt` | 2 tests for the new frame. |

---

## 5. What to expect, and what not

- **Expected:** a piece starts coming after about one round trip on the Wi-Fi Direct link. The 11 Nov experiment
  measured 83–261 ms for a connection set-up plus one request on that link, so a request alone should cost less.
  A first lift needs several pieces one after the other, so it should come down, but by how much is not known.
- **Not expected to change:** a second lift (0.3–0.4 s on 11 Nov), because it already reads from what B kept.
- **Possible gain:** the file after a drop should arrive faster. Throughput was never measured on either path.
- **Possible loss:** the pieces now share the radio, not the connection, with the card messages. The 11 Nov
  experiment saw no extra card lag (39–56 ms), but that was 7 loads.
- **Not addressed here:** the hole left by a refused piece after a pull-back, the stalls after a drop, and the slow
  link of 12 Nov. If the link itself is slow, this path is slow too.

---

## 6. How to test on the phones

Both phones need this build. Plug both in.

1. **Check the path is used.** After pairing, the log shows `AirLan: Pieces go over Wi-Fi to …`. During a lift it
   shows `AirLink: Piece of … opened in … ms over Wi-Fi`. Without "over Wi-Fi", the piece went over Nearby.
2. **First lift:** play a video on A, lift it across, hold until B plays. Five times, a fresh video each time if
   possible (or restart both apps between lifts).
3. **Second lift:** lift, pull back, let it settle, lift again. Five times.
4. **Drop:** lift, drop on B, wait until the file is kept. Note the time from the drop to "kept". Twice.
5. **Card smoothness:** while a file is still arriving on B, slide another card across. Does B's half follow?

Numbers to compare with (11 Nov, Nearby path): first frame on a first lift 1.4–2.8 s; on a second lift 0.3–0.4 s;
piece opened in 0.1–1.1 s; card lag typical 37–48 ms.

---

## 7. Phone results (12 Nov 2026, one afternoon, one pair of phones)

A = M2101K6G, B = Redmi Note 8, both on the Wi-Fi Direct group. Times are B's clock.

### A bug found by the first run (14:08), fixed in `9844f77`

B never used the new path: all 434 of its pieces went over Nearby. The group's owner (A) offers its listeners about
0.3 s before B has joined the group, and offers them once; B's connect failed and was never tried again. The same
was true, before this branch, of B's card messages to A. A failed connect is now tried again over the next seconds.

### One piece

| Path | Run | Pieces | Typical time to open | 9 in 10 under | Slowest |
| --- | --- | --- | --- | --- | --- |
| Nearby, B reading from A | 14:08 | 434 | 622 ms | 1,209 ms | 2,813 ms |
| Wi-Fi, A reading from B | 14:08 | 44 | 73 ms | 113 ms | 281 ms |
| Wi-Fi, B reading from A | 14:15, after the fix | 1,189 | 94 ms | 197 ms | 741 ms |

No piece went over Nearby in the second run, and none broke.

### Lifts on B, second run (4 lifts, no jump in any)

| Lift | First frame after |
| --- | --- |
| First lift of video 14 | 2.3 s |
| Second lift | 0.49 s |
| Second lift | 0.46 s |
| Second lift | 0.47 s |

- **The first lift did not get faster** (one lift; 1.4–2.8 s on 11 Nov). Its pieces each opened in about 0.1 s, but
  the player read about ten of them, mostly one after the other, before its first frame. The wait is now the bytes
  themselves and the player's hops, not the requests.
- **Second lifts were steady** at just under half a second. On Nearby the same morning they ranged from 0.3 to 1.5 s.

### Whole files after a drop

| File | Size fetched | Time | Speed |
| --- | --- | --- | --- |
| Video 14, on B | 470 MB (97 MB were already here) | 96.3 s | 4.9 MB/s |
| Video 2, on B | 676 MB | 123.8 s | 5.5 MB/s |
| Video 10, on A | 42.6 MB | 8.9 s | 4.8 MB/s |

- The two fetches on B overlapped for about a minute, so the link carried more than one file's speed then: 1,146 MB
  in 159 s, about 7.2 MB/s.
- **No proof that whole files are faster.** There is no fetch over Nearby from the same day to compare with. The
  only earlier figure is "the link carried about 4.5 to 5.5 MB/s" (report 05, 11 Nov), which is the same range.
- A fetch asks for 3 pieces of 1 MB and waits for all three before the next round. That, not the link, may be what
  holds it near 5 MB/s. Larger pieces are the first thing to try (Section 8).

### Cards

- Card lag typical 35–56 ms in six slides (37–48 ms on 11 Nov): no change.
- A card slid across while a file was arriving: the fetch held back each time (0.6–3.0 s), as before.
- One pause of the card messages (333 ms) in the run, at a lift.

### Side by side with `main` (14:32 and 14:40, twelve lifts each)

The same video (567 MB, about 2.5 MB/s), the same phones and roles, `main` first, then this branch with 4 MB
pieces for the fetch (`WifiFetchPieceBytes`). A "first lift" is one after switching to another video and back.

| Measure | `main` (Nearby) | This branch (Wi-Fi pieces) |
| --- | --- | --- |
| A piece, typical time to open | 520 ms (227 pieces) | 112 ms (475 pieces) |
| First lifts, first frame | 5 lifts: 1.9–3.6 s, typical 2.6 s | 3 lifts: 2.3, 4.1 and 4.4 s |
| First lifts that jumped | 1 of 5 | 2 of 3 |
| Second lifts, first frame | 7 lifts: 0.33–1.19 s, typical 0.46 s | 7 lifts: 0.35–0.60 s, typical 0.41 s |
| Second lifts that were slow and jumped | 0 | 2 more (2.9 and 3.6 s) |
| Card lag, typical per slide | 28–47 ms | 52–79 ms |
| Card lag, worst 1 in 20 moves per slide | 84–171 ms | 137–461 ms |
| Pauses of the card messages (over 0.3 s) | 3 | 13 |
| The file after a drop | 3.9 MB/s (537 MB in 137 s, at 14:27) | 4.9 MB/s (515 MB in 105 s) |

What this says:

- **Pieces start about five times sooner, and that did not make a lift faster.** The wait before a first frame is
  the bytes: the player read 12 to 13 pieces (up to 13 MB) before its first frame on a first lift, and the link
  carried about 3 MB/s while it did. Most of those pieces are ones the player only glanced at before hopping on;
  each is still sent whole.
- **The two slow second lifts** came after a pull-back made while B's player was already out of data ("buffering").
  Nothing was kept ahead of the playing position, and pieces at it had just been refused. This is the refused-piece
  hole of `docs/COMING_IMPROVEMENTS.md`, not something this branch added, but this branch did not avoid it either.
- **The card got worse.** Its lag went up by about half and its pauses from 3 to 13. The likely cause, not proven:
  the video's bytes now reach the radio sooner and in larger bursts, and the card messages share that radio.
- **Whole files are about a quarter faster** than on `main`. Pieces of 4 MB were no faster than pieces of 1 MB
  (4.9 against 4.9–5.5 MB/s), so the piece size is not what limits a fetch.
- **Small numbers:** one run of twelve lifts on each build, one after the other, one drop each.

**Verdict of that run: not ready to merge.** It was faster for files and no better for lifts, and it cost card
smoothness, which is the product.

### Round two (15:22), commit `291989c`

Four changes, made because of the run above:

- **The card messages go first.** Their connection is marked "expedited" (`Socket.trafficClass = 0xB8`), which
  Wi-Fi sends from its voice queue, and a piece connection may queue at most 128 KB to send.
- **A read over Wi-Fi starts with a 256 KB piece**, not 1 MB (`FirstWifiPieceBytes`). The pieces a starting player
  glances at are mostly read again a moment later, so they are not cancelled; they are made smaller.
- **An offered listener is tried again** when this phone's own address changes, and for a little longer.
- **A fetch is back on 1 MB pieces.**

Fifteen slides on B, nine of them of a playing video (the 676 MB one this time; the runs above used the 567 MB one):

| Measure | `main` (14:32) | Round one (14:40) | Round two (15:22) |
| --- | --- | --- | --- |
| A piece, typical time to open | 520 ms | 112 ms | 80 ms (777 pieces) |
| First lifts, first frame | 5: 1.9–3.6 s, typical 2.6 s | 3: 2.3, 4.1, 4.4 s | 3: 0.94, 1.08, 2.50 s |
| Second lifts, first frame | 7: 0.33–1.19 s, typical 0.46 s | 7: 0.35–0.60 s, and 2 slow | 6: 0.38–0.53 s, typical 0.43 s |
| Lifts that jumped | 1 of 12 | 4 of 12 | 0 of 9 |
| Card lag, typical per slide | 28–47 ms | 52–79 ms | 40–55 ms |
| Card lag, worst 1 in 20 moves per slide | 84–171 ms | 137–461 ms | 96–477 ms |
| Pauses of the card messages (over 0.3 s) | 3 in 12 slides | 13 in 12 slides | 4 in 15 slides |
| The file after a drop | 3.9 MB/s | 4.9 MB/s | 6.5 MB/s (600 MB in 92 s) |

- **Round two is better than `main` on lifts and files, and about level with it on the card.** The typical card lag
  is back near `main`'s; its worst moves are still a little worse in some slides.
- **Which change did what is not separated.** All four went in together.
- **Small numbers, and not a clean comparison:** three first lifts; another video than in the two runs before; the
  runs were 40 minutes apart on a link whose speed changed during the day.
- **Before this run the phones did not link for about two minutes** after the install (A was not searching). It did
  not happen on the four installs before. Cause unknown; it may or may not have to do with this build.

**Verdict now: promising, worth one repeat of the side-by-side run on the same video before merging.**

### Not tested

A lift on Bluetooth before Wi-Fi Direct is up, a phone on an older build, a long video, the phones off the home
Wi-Fi, and what happens when the Wi-Fi connection breaks in the middle of a piece.

---

## 8. Next, if it pays off

- **Repeat the side-by-side run** on the same video, `main` and round two back to back, with more first lifts.
- **Test the fallbacks:** a lift on Bluetooth before Wi-Fi Direct is up, one phone on an older build, and the Wi-Fi
  connection breaking in the middle of a piece.
- **Find out why the phones did not link** for two minutes before the round-two run.
- Larger pieces for the fetch were tried (4 MB) and brought nothing.
- Hold the Wi-Fi radio awake while a card is in the air (a low-latency Wi-Fi lock), if the round trip turns out to
  be the radio's power saving.
