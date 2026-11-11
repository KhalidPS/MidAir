# 2026-11-11: The card trails the finger while a video plays: before and after

This report puts the old approach next to the fix, explains **what was wrong**, **what we did instead**, and
**what it brings**.

- Branch: `fix/2026-11-11/1340-card-lag-during-video` (off `fix/2026-11-11/1324-lift-before-wifi`). Two commits:
  `b165af6`, a cherry-pick of `46df88d` ("Fix 4", from the side branch `fix/slide-first-wifi-messages`), and
  `1c5437f`, the new part.
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Video: 676 MB, about 2.5 MB/s, playing.
- **Phone result: confirmed.** Tested by the owner on 2026-11-11: "more quick than before, too much better". All
  unit tests pass and the app builds.

---

## 1. The picture in one minute

**Symptom:** while a playing video is dragged across, B's half of the card trails the finger.

**Cause:** Nearby carries everything on one connection, with no priorities. Two kinds of traffic share it:

- the card's small move messages, one about every 17 ms;
- the 1 MB video pieces B's player reads, up to 4 on their way at once.

Nearby's socket send buffer is several MB (its log shows 4 MB requested, 8 MB granted). A card message written
after video bytes waits until those bytes have gone out. The link carried about 4.5 to 5.5 MB/s, so a few MB
queued means hundreds of ms.

**Evidence that the video is the cause:** in a slide where the video had not started loading, card moves arrived
p50 60 ms late. In slides with the video loading, p50 was 250 to 610 ms.

**Before:** the existing mitigations (all still in place) limit how much is queued: 1 MB pieces, at most 3 pieces
ahead, whole-file fetches paused while a card is in the air, card moves batched. None of them lets a card message
overtake video bytes that are already queued. Fix 4 gave the card messages their own TCP connection, but only over
a Wi-Fi network both phones had joined (it needs a router), and it lived on a side branch.

**After:** the card messages get their own TCP connection on the Wi-Fi Direct group that Nearby itself opens
between the two phones. Video pieces and files stay on Nearby. The card no longer waits behind the video.

### Measured on B

"Late" is how far behind the sender's timeline each card move arrived, per slide, in ms.

| Before (builds without this fix) | p50 | p95 | max | | After (13:44 to 13:45) | p50 | p95 | max | moves |
|---|---|---|---|---|---|---|---|---|---|
| 12:36:22 | 356 | 1048 | 1157 | | slide 1 | 66 | 156 | 360 | 699 |
| 12:36:29 | 610 | 1007 | 1104 | | slide 2 | 55 | 105 | 152 | 480 |
| 12:37:02 | 461 | 1042 | 1414 | | slide 3 | 58 | 107 | 312 | 764 |
| 12:38:18 | 446 | 848 | 1044 | | slide 4 | 41 | 90 | 99 | 31 |
| 13:10:42 | 322 | 791 | 857 | | slide 5 | 54 | 100 | 181 | 373 |
| 13:12:05 | 207 | 643 | 706 | | | | | | |
| 13:15:11 | 502 | 1067 | 1483 | | | | | | |

Same day, same phones, same video.

| | Before | After |
|---|---|---|
| Median lateness | 207 to 610 ms | 41 to 66 ms |
| Worst case | 706 to 1483 ms | 99 to 360 ms |
| "Card messages paused" warnings | repeated, 300 to 615 ms each | none |

After the fix, no move arrived out of order in any slide. The "after" median matches the 60 ms measured with no video loading. The video no longer delays the card.

---

## 2. The fix

**From Fix 4 (`b165af6`, cherry-picked):** `LanMessageLink.kt` and the `LinkFrame.LanOffer` frame. Each phone
listens, sends its address, port and a random token over the encrypted Nearby link, and connects to the other's
listener. A connection without the token is dropped. Offers are only followed to local addresses. If the
connection breaks or cannot open, the messages go over Nearby as before.

**New (`1c5437f`), two files in `core/transfer/nearby`:**

- `LanFileServer.kt`: a new `directAddress()` finds this phone's address on the Wi-Fi Direct (`p2p`) interface. It
  returns null while the link is still on Bluetooth.
- `NearbyPeerLink.kt`, `offerLan()`:
  - prefers the Wi-Fi Direct address, and falls back to the shared Wi-Fi address;
  - skips the offer if it is already listening on that address;
  - is called again from `onBandwidthChanged`, so the connection moves from the shared Wi-Fi (if any) to Wi-Fi
    Direct when the group comes up;
  - the remembered address is cleared when the link drops.

In the test the group used interface `p2p0`, addresses 192.168.49.1 (A) and 192.168.49.63 (B). No router and no
internet are involved.

### The switch, as logged on A

```
 13:44:02.6  listening on 192.168.0.111 (home Wi-Fi)
 13:44:03.1  card messages go to 192.168.0.106
 13:44:05.6  Nearby moves from Bluetooth to Wi-Fi Direct
 13:44:05.7  listening on 192.168.49.1
 13:44:06.1  card messages go to 192.168.49.63
```

The direct connection was up 0.4 s after the upgrade.

---

## 3. Limits

1. **Before Wi-Fi Direct is up, with no shared Wi-Fi,** card messages are still on Nearby, as before. Today the
   group came up 1.7 to 12.6 s after pairing.
2. **Moves arrive in small bursts.** Arrival gaps still show p95 about 95 to 100 ms, although moves are sent every
   17 ms. Not investigated. The card's smoothing already hides it on screen.
3. **One pair of phones.** Whether every phone lets an app open a socket on Nearby's Wi-Fi Direct group is not
   known. If one refuses, the messages fall back to Nearby.
4. **Not changed by this fix, seen in the same test:** the video's first frame on B still takes 2.4 to 3.6 s. In 3
   of 5 slides B rebuffered once more right after becoming ready (1.2 to 1.8 s), because A had played on
   meanwhile. Those are the next fixes.

---

## 4. Tests

The tests come with the Fix 4 cherry-pick: `LanMessageLinkTest.kt` and `LanOfferFrameTest.kt`. The new commit adds
no tests; it changes how the address is picked and when it is offered, and was checked on the phones (Section 1). All unit tests pass and the app builds.

---

## 5. What makes the new approach better

- **The card stops sharing a queue with the video.** The old mitigations made the queue shorter. This takes the
  card out of it.
- **No router needed.** Fix 4 only worked on a shared Wi-Fi. This uses the group Nearby opens anyway, so it works
  outdoors too.
- **It stays fixed.** Card lateness no longer depends on how much video is queued, so later changes to video
  buffering will not bring the lag back.
- **Nothing is lost when it can't work.** Every failure path ends on Nearby, which is where the messages were
  before.

### Lesson

> **Small urgent messages need their own lane.** Making the big traffic smaller only shortens the wait; it never
> lets the small message go first.
