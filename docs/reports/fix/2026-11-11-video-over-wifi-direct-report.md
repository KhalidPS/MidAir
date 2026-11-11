# 2026-11-11: Play a followed video over the Wi-Fi Direct group (experiment, parked)

This report puts the Nearby path next to the experiment, explains **what we expected**, **what we measured**, and
**why it did not pay off**.

- Branch: `feature/2026-11-11/1747-video-over-wifi-direct`. One commit: `314b6a7`, "feat: play a followed video over
  the Wi-Fi Direct group, on a connection of its own". Built on every fix up to the jump rule
  (`fix/2026-11-11/1740-catch-up-before-jumping`).
- Phones: A = M2101K6G (sender), B = Redmi Note 8 (receiver). Videos "2" (676 MB recording) and "14". All on Wi-Fi
  Direct. Times are B's clock.
- **Status: works, not faster. Parked, not merged.** Tested on the phones from 17:58 to 18:01 (Test P).

---

## 1. The picture in one minute

**The idea:** B's player reads the video in pieces over Nearby. A piece asked for over Nearby took 0.1 to 1.1 s to
start coming. The card messages had become fast on a connection of their own on the Wi-Fi Direct group (see the
card-lag report). So we expected the video to start well under a second if the player read it the same way.

**What we measured:** the video did not start sooner on a first lift (1.6 to 3.4 s), and started much later on a
second lift (1.6 to 3.4 s, against 0.33 to 0.43 s on the Nearby path).

**Why:** the delay per request comes from the Wi-Fi Direct link itself, not from Nearby's handling. A connection of
our own does not remove it.

---

## 2. The change

- **On A:** the small HTTP file server that already existed (`LanFileServer`, until now only used over a shared
  Wi-Fi network) hands out its address on the Wi-Fi Direct group when there is one.
- **New:** `MediaChannel.isDirect(url)` says whether an address is on that group.
- **On B:** the player reads that address directly (`VideoSource.Url`), even when the pairing link is fast.
- **Fallback:** an address that does not answer leaves the video on the pairing link.
- **No move back:** a video read this way is not moved onto the pairing link later.
- **After a drop:** the file itself still comes over the pairing link, in pieces. Phase two, moving the file over
  the group, was never built.

---

## 3. Before and after

| | Nearby path | Direct path (Test P) |
|---|---|---|
| First frame, first lift | 1.4 to 2.6 s (17:06 test); 1.7 and 2.5 s (Test Q) | 1.6 to 3.4 s |
| First frame, second lift after a pull-back | 0.4 s (17:06 test); 0.33 to 0.43 s (Test Q) | 1.6 to 3.4 s, like a first lift |
| What B shows on a second lift | the video, within half a second | the thumbnail first, then the video |
| Jumps | 0 of 12 (Test Q) | 2 of 7, once each |
| Card lag typical | 37 to 48 ms (Test Q) | 39 to 56 ms |

### Test P, 17:58 to 18:01

Every load used the direct path. B logged "Load from the other phone over Wi-Fi", and "Wi-Fi path reachable" in 83
to 261 ms. Seven loads reached a first frame. Most of them were second lifts after a pull-back.

| Slide time | First frame after | Then | In step with A after |
|---|---|---|---|
| 17:58:48 | 2.6 s | played straight on | 2.6 s |
| 17:59:00 | 1.9 s | played straight on | 1.9 s |
| 17:59:12 | 1.6 s | played straight on | 1.6 s |
| 17:59:21 | 3.4 s | jumped once | 5.7 s |
| 18:00:16 | 3.0 s | played straight on | 3.0 s |
| 18:00:52 | 3.4 s | jumped once | 6.1 s |
| 18:01:40 | 1.9 s | played straight on | 1.9 s |

Card lag typical: 39 to 56 ms. What the owner saw: each time the card was lifted again after settling back on A,
B's half showed the video's thumbnail first, then played.

---

## 4. Why the expectation was wrong

- **The link is slow to answer, not Nearby.** Even the tiny reachability check on the direct connection took 83 to
  261 ms to answer. That is the Wi-Fi Direct link's own delay per request. A direct connection cannot remove it.
- **Fewer requests can.** That is what reading the index ahead and keeping what was read on a pull-back do. They
  already brought a second lift down to 0.33 to 0.43 s on the Nearby path.
- **Nothing is kept between lifts on this path.** What was read before is kept by the pairing link, and this path
  goes around it. So every lift is a fresh load, a second lift included. That is the thumbnail the owner saw.

---

## 5. What it is still good for

- The code is on its branch and works: the video plays over the group, and falls back when the address does not
  answer.
- It might matter for moving whole files over the group. Throughput was not measured. That is a separate question
  from starting a video.

---

## 6. Limits

1. **Seven loads**, one afternoon, one pair of phones.
2. **The two paths were compared across tests**, not side by side. Loading speed varied between rounds before.
3. **Throughput not measured.** Only start times and jumps.

---

## 7. Tests

`ShelfViewModelLiveVideoTest.kt`: 4 new tests.

- On the Wi-Fi Direct group the video is read over a connection of its own, though the pairing link is fast.
- An address on the Wi-Fi Direct group that does not answer leaves the video on the pairing link.
- A video read over the Wi-Fi Direct group is not moved onto the pairing link.
- The file of a video read over the Wi-Fi Direct group is pulled in pieces, and the video plays on meanwhile.

### Lesson

> **Measure where the delay comes from before going around it.** The delay was in the link, not in Nearby, and the
> new path lost what the old one kept.
