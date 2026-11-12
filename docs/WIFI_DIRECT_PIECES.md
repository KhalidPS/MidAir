# Pieces over Wi-Fi Direct: the full version of the direct path

Written 12 Nov 2026. Branch: `feature/2026-11-12/pieces-over-wifi-direct`, off `main`.
**Status: built, unit tests pass, not yet tested on the phones. Section 7 is filled in after the phone test.**

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

## 7. Phone results

Not tested yet.

---

## 8. Next, if it pays off

- Larger pieces for the fetch after a drop (the 1 MB size was chosen for Nearby's shared connection).
- Stop a piece nobody wants any more by closing its connection (safe here, never safe over Nearby).
- Hold the Wi-Fi radio awake while a card is in the air (a low-latency Wi-Fi lock), if the round trip turns out to
  be the radio's power saving.
