# 2026-10-29: Live video, one transfer (LH-4): report

| | |
|---|---|
| **Work** | LH-4 of the live video handoff: "one transfer" |
| **Branch** | `feature/live-video-one-transfer`, stacked on `feature/live-video-handoff` (neither merged) |
| **State** | Built, 285/285 unit tests pass, debug APK builds. **Not tried on the phones yet.** Not committed yet |
| **Plan it follows** | `docs/CHECKPOINT.md` (2026-10-25), "Start here next time: LH-4, one transfer" |

---

## 1. What you won't see yet

- **No phone run yet.** Everything below about speed is an **estimate** from the 2026-10-25 phone measurements and
  the sample video. The real numbers come from the first two-phone run (Section 8 lists what to read in the logs).
- **The Wi-Fi fallback path is unchanged.** When the video was read over the home Wi-Fi (Nearby still on
  Bluetooth), the file still comes as a second transfer and the player still reloads. LH-4 covers the main path:
  video read over Nearby on Wi-Fi Direct.
- **No resume.** If the link drops halfway through, the transfer fails, as a normal file transfer does today.
- **Not reviewed with `/verify`** (same as the rest of the live video work, to save usage).

---

## 2. The problem

After the drop, the receiver got the video **twice at once** over the same Nearby connection:

1. the 1 MB pieces its player still needed to keep playing, and
2. the whole file again, as a separate Nearby `FILE` transfer.

They competed. On the phones (2026-10-25), pieces slowed from about **0.2 s to 1.7–3.3 s** each, the video could
stall until the file had arrived, and then the player **reloaded** onto the received copy. The card showed its
thumbnail for a moment, and the reload code was fragile (guide, old Section 14).

---

## 3. What changed

**The receiver builds the file from the pieces it already has.** Every piece the player reads is kept. After the
drop, the receiver asks only for the parts still missing, and only while its player isn't waiting for a piece. It
puts the file together, checks it against the sender's SHA-256, and keeps it like any received file. The player goes
on reading from that copy without a reload. The sender never sends the file a second time.

### The new exchange after the drop

```
 Sender                                              Receiver (was playing the video over Nearby)
 ──────                                              ────────
 DragCommit ───────────────────────────────────────▶ chooses Delivery.FETCH
            ◀────────────────────────── FileRoute(FETCHING)
 (waits; does NOT send the file)                     fetch: missing pieces only, player first
 serves pieces   ◀──── RangeRequest ───               ...
 hashes its file ◀──── DigestRequest ──               ...
                 ───── Digest(SHA-256) ─▶             assemble + hash, compare
                                                     keep in Downloads, settle (player reads it), add to shelf
            ◀────────────────────────── FileRoute(FETCHED)
 removes the item (the send is done)
```

If anything fails while the link is up (no room, hash mismatch, pieces refused), the receiver answers
`FileRoute(SEND)` and the file comes the old way. A sender that hears nothing within 5 s (an older build) sends the
file the old way too.

### Files

| Layer | File | Change | Why |
|---|---|---|---|
| Protocol | `core/transfer/domain/.../SlideMessage.kt` | New `FileRoute(itemId, route)` with `SEND / FETCHING / FETCHED / CANCELLED` | The receiver must tell the sender not to send the file, and when it has it |
| | `core/transfer/data/.../SlideMessageDto.kt`, `SlideMessageMapper.kt`, `SlideMessageValidation.kt` | Wire format (`"route"`) and mapping | Same codec path as every message |
| | `core/transfer/nearby/.../Reconnect.kt` | New link frames `DigestRequest` (8) and `Digest` (9) | The byte-for-byte check needs the sender's hash; it belongs with the range frames |
| | `core/transfer/nearby/.../NearbyPeerLink.kt` | Routes the two new frames to the media channel | |
| Channel API | `core/transfer/domain/.../MediaChannel.kt` | New `fetch`, `settle`, `forget` (with harmless defaults) | Keeps the domain free of Nearby; fakes and other channels keep compiling |
| Nearby | `core/transfer/nearby/.../NearbyMediaChannel.kt` | Keeps whole pieces (`claim` → `keepPiece`); `fetch` (`fillIn`, `askDigest`, `assemble`); `settle` / `forget`; reads from the local copy (`openLocal`, `PiecedStream.goOnFromHere`, `LocalStream`); counts the player's pieces in flight (`playerPieces`) | The core of LH-4 |
| | `core/transfer/nearby/.../FileSpans.kt` (new) | Pure `missingSpans`, `coveredBytes`, `assemblyPlan` | The arithmetic most likely to hide an off-by-one, so it is in `commonMain` and unit tested |
| Shelf domain | `feature/shelf/domain/.../SlidePeer.kt` | `OutgoingFile.peerMayFetch`, `IncomingFile.delivery`, new `Delivery` enum (`SENT / ASK_TO_SEND / FETCH`) | The view model states the intent; the data layer carries it out |
| Shelf data | `feature/shelf/data/.../LinkedSlidePeer.kt` | Sender: waits for `FileRoute` before sending. Receiver: `fetch` → keep → `settle` → shelf → `FETCHED`, with fallback and cancel | Same class that already matched `FileOffer`s to cards |
| Shelf UI logic | `feature/shelf/presentation/.../ShelfViewModel.kt` | Remembers whether the video was offered (sender) and how it was read (receiver); picks the `Delivery` at the drop; `forget` on drag-back and on a new offer; reloads only on the Wi-Fi path | The view model knows how the video was being played |
| Docs | `docs/LIVE_VIDEO_GUIDE.md`, `docs/CHECKPOINT.md` | Sections 5.10–5.12, 6.7 (new), 11, 12, 13, 14 | |

### Decisions taken (the checkpoint's open questions)

| Question | Decision | Why |
|---|---|---|
| Who decides it's a "live" transfer? | **The receiver**, with `FileRoute` right after the commit | Only the receiver knows whether it read the video over Nearby (and so has pieces) or over the home Wi-Fi |
| What if the link drops mid-way? | **Fail** (no resume). Other failures fall back to the `FILE` transfer | Same behaviour as a normal transfer; resume can come later |
| Where do the pieces live? | `cacheDir/live-ranges` (already used), wiped at the next start | A free-space check first: 2 × the missing part + 64 MB |

Other choices worth knowing:
- **Hash: SHA-256 of the whole file**, computed by the sender while the receiver fetches, so it costs almost no
  wall time. Nearby already protects each piece; the hash protects against our own bugs in putting pieces together.
- **"Player first" is soft.** A fetch round waits for the player's pieces to arrive, but at most 2 s, so a link too
  slow to ever fill the player's buffer still finishes the file.
- **Stale answers are ignored.** A `FileRoute` older than 10 s is not trusted, because item ids come back after an
  item is deleted: an old `FETCHED` must never end a new send (the sender would then delete a file the receiver
  doesn't have).

---

## 4. Performance: what it should add

**Basis:** the sample `share_sample2.mp4` is **23.6 MB, 57.5 s, 3.45 Mbit/s (0.41 MB per second of video), index at
the end**. Player buffer: 5–10 s ahead. Phone measurements from 2026-10-25: a piece normally arrives in 0.1–0.5 s,
during the old `FILE` transfer in 1.7–3.3 s; Wi-Fi Direct carried 150–220 Mbit/s.

| | Before LH-4 | After LH-4 (estimate) | Why |
|---|---|---|---|
| Piece time after the drop | 1.7–3.3 s (measured) | ~0.1–0.5 s, as before the drop | Nothing competes with the player any more: background pieces wait for it |
| Risk of a stall after the drop | Real: one piece holds ~2.4 s of this video but took up to 3.3 s | Low | Same reason |
| Player reload when the file arrives | Yes: thumbnail flash plus a new load (~0.1–0.3 s on a local file) | **None** | The same stream switches to the local copy at a piece boundary |
| Bytes over the link after the drop | Whole file again (23.6 MB) **plus** the player's pieces | Only what the player hadn't read: ~15–19 MB for a typical 3–5 s slide | The ~5–8 MB already read (header, index, 5–10 s of buffer) is reused, not re-sent |
| Total bytes for the whole handoff | ~23.6 MB + everything the player read (~29–32 MB) | ~23.6 MB (each byte once) | "One transfer" |
| Time until the file is on the shelf | ~2–4 s for the `FILE` transfer, while starving the player | ~2–3 s (15–19 MB at an assumed 60–100 Mbit/s with 3 pieces in flight), plus ~0.2–0.5 s to assemble and hash | Similar or slightly faster, without hurting playback |

**The main gain is not raw speed. It is that the video no longer stutters after the drop, and no longer restarts
when the file lands.** The file reaches the shelf in about the same time, and the link carries about 20–30% fewer
bytes for this sample. Short clips the player has fully buffered by the drop need almost no fetch at all.

### What it costs

| Cost | Size | Note |
|---|---|---|
| Extra disk writes | ~1 extra copy of the file (pieces, then the assembled file, then the Downloads copy: ~71 MB written for the sample vs ~47 MB before) | A later step could assemble straight into Downloads and save one copy |
| Peak free space | ~2 × file size during the fetch | Checked before starting; falls back to the `FILE` transfer if missing |
| CPU | SHA-256 of the file on both phones (~0.05–0.2 s for 23.6 MB; ~3–6 s for 1 GB on the sender, overlapping the fetch) | |
| Latency when the receiver is an older build | The sender waits 5 s for an answer, then sends the file as before | Only when the video was offered |

---

## 5. Risks

1. **Nearby behaviour assumed, not observed.** The fetch adds background pieces on the same link. They are sized and
   paced like the player's pieces, which the phones handled, but how they mix with the card's moves after the drop
   is untested.
2. **The switch to the local copy happens at a piece boundary.** If the sender refuses a piece in the short window
   before the receiver settles (it shouldn't: the sender only lets go after `FETCHED`), the stream switches to the
   local copy instead of failing. That fallback is untested on the phones.
3. **Deleting a file a player still reads.** The fetched copy and kept pieces are deleted while a player may have
   them open. This is safe on Android (Linux keeps open files readable), but worth knowing when reading the code.
4. **Not covered by unit tests:** `NearbyMediaChannel` itself (it needs Nearby). Its pure parts (`FileSpans`), the
   frames, the message, the handshake in `LinkedSlidePeer` and the view model's choices are covered.

---

## 6. Tests

`./gradlew testAndroidHostTest`: **285 tests, 0 failures** (about 260 before). New or changed:

| File | What it checks |
|---|---|
| `core/transfer/nearby/.../FileSpansTest.kt` (new, 9 tests) | Missing parts, overlaps, the end of the file, covered bytes, the assembly plan, no plan while a byte is missing |
| `core/transfer/nearby/.../RangeFrameTest.kt` (+1, extended) | Digest frames round-trip; malformed ones are dropped |
| `core/transfer/data/.../SlideMessageCodecTest.kt` (extended) | `FileRoute` round-trips for every route |
| `feature/shelf/data/.../LinkedSlidePeerTest.kt` (+11) | Sender: fetched (never sent), asked to send, fetch fails then sent, no answer then sent, cancelled, link lost. Receiver: fetched → kept, shelf, settle, then `FETCHED`; fetch fails → `SEND` and the usual way; link lost; stopped by the user; `ASK_TO_SEND` |
| `feature/shelf/presentation/.../ShelfViewModelLiveVideoTest.kt` (+7, 1 rewritten) | `peerMayFetch` only for a card that carried its video; `Delivery` chosen at the drop (`FETCH` / `ASK_TO_SEND` / `SENT`); no reload after reading over Nearby; reload kept on the Wi-Fi path; kept pieces forgotten on a drag-back |

---

## 7. How it degrades

| Situation | What happens |
|---|---|
| Receiver read the video over Nearby | **New path**: fetch from pieces, no second transfer, no reload |
| Receiver read it over the home Wi-Fi | Receiver answers `SEND`: file sent as before, player reloads as before |
| Video offered but not loaded yet at the drop | `SEND`: file sent as before |
| Card without a video | Unchanged: no `FileRoute`, file sent at once |
| Not enough free space, or hash mismatch | `SEND`: file sent as before |
| Link drops mid-fetch | Both sides fail as for any transfer; the sender's card comes back with Undo |
| User taps the arriving card | `CANCELLED`: the sender's card comes back |
| Receiver is an older build | No answer: the sender sends the file after 5 s |

---

## 8. How to check it on the two phones

Install the debug build on both phones (`docs/CHECKPOINT.md`, "How to test on two phones"), wait for
`Link quality 3`, play `share_sample2.mp4`, and slide it across. In the **receiver's** `AirLink` / `AirVideo` log:

1. `Fetched <id> (S bytes, K already here) in N ms`: **N** is the fetch time, and **K / S** is how much the
   player had already brought over.
2. `<id> is on this phone for good: read from here`, then `<id> goes on from this phone's copy at <offset>`.
3. **No** new `Load from this phone …` line after the drop (that was the reload).
4. `Piece … opened in` after the drop: should be 0.1–0.5 s, not 1.7–3.3 s.
5. In the sender's log, the item is removed only after the receiver's `FETCHED`.

Then the failure paths: drag the card back mid-slide; tap the arriving card to stop it; turn Wi-Fi off on one phone
mid-fetch. Write the measured numbers into Section 4 of this report, next to the estimates.

---

## 9. Next

1. The phone run above, then tune `FetchPiecesAhead` / `PlayerFirstMillis` if the card's moves or the player suffer.
2. Optional: assemble straight into Downloads to save one copy of the file on disk.
3. From the checkpoint: the safety net for a silent sender, the codec check, the sound cross-fade.
