# 2026-10-29: Live video, one transfer: before and after (a learning guide)

This guide puts the old approach (before LH-4) next to the new one, step by step, with the code from both. For
each change it explains **what was wrong**, **what we did instead**, and **the general lesson** you can reuse in
other apps.

- The facts and numbers: `2026-10-29-live-video-one-transfer-report.md` (same folder).
- How the whole live video feature works from zero: `docs/LIVE_VIDEO_GUIDE.md`.
- Branch: `feature/live-video-one-transfer`. Not tried on the phones yet, so "after" behaviour is what the code and
  tests do, not yet what the phones showed.

---

## Contents

1. [The picture in one minute](#1-the-picture-in-one-minute)
2. [Step 1: what happens to a piece after the player reads it](#2-step-1-what-happens-to-a-piece-after-the-player-reads-it)
3. [Step 2: who decides how the file comes over](#3-step-2-who-decides-how-the-file-comes-over)
4. [Step 3: getting the missing parts without hurting playback](#4-step-3-getting-the-missing-parts-without-hurting-playback)
5. [Step 4: putting the file together and proving it is right](#5-step-4-putting-the-file-together-and-proving-it-is-right)
6. [Step 5: moving the player to the local copy](#6-step-5-moving-the-player-to-the-local-copy)
7. [Step 6: when things go wrong](#7-step-6-when-things-go-wrong)
8. [Where each piece of logic lives, and why](#8-where-each-piece-of-logic-lives-and-why)
9. [Tests: what changed](#9-tests-what-changed)
10. [Lessons to take away](#10-lessons-to-take-away)
11. [Exercises](#11-exercises)

---

## 1. The picture in one minute

**Before:** two transfers of the same file at once, then a player restart.

```
 Sender                                    Receiver
 ──────                                    ────────
 DragCommit ─────────────────────────────▶ card lands, video keeps playing (reads pieces)
 FileOffer + FILE (whole 23.6 MB) ───────▶ ┐
 pieces the player asks for ─────────────▶ ┘ both on ONE Nearby connection → they fight
                                           pieces: 0.2 s → 1.7–3.3 s each → video may stall
                                           file arrives → player RELOADS from the local copy
 removes its item
```

**After:** one transfer. The receiver reuses what it already has.

```
 Sender                                    Receiver
 ──────                                    ────────
 DragCommit ─────────────────────────────▶ card lands, video keeps playing (reads pieces, KEEPS them)
            ◀──────────── FileRoute(FETCHING)  "don't send it, I'll take it"
 serves only the missing pieces ─────────▶ asks for gaps only when the player isn't waiting
 sends SHA-256 of its file ──────────────▶ puts the file together, compares the hash
            ◀──────────── FileRoute(FETCHED)   file kept; the SAME player now reads it locally
 removes its item
```

| | Before | After |
|---|---|---|
| Transfers after the drop | 2, competing | 1, polite to the player |
| Bytes on the link | whole file + what the player read | each byte once |
| Player after the file arrives | reloads (thumbnail flash) | keeps going, no reload |
| Who chooses how the file comes | nobody: always sent | the receiver |
| Correctness check | none needed (Nearby sent the whole file) | SHA-256, because we assemble it ourselves |

---

## 2. Step 1: what happens to a piece after the player reads it

### Before: pieces were thrown away

A piece is 1 MB of the video file, drained from a Nearby stream into a temp file. Once the player moved on, the
piece was closed, and closing deleted its file. A piece nobody wanted was not even written:

```kotlin
// DrainedPiece.drain(), before
lock.withLock {
    // Nobody wants it any more: the rest is read and thrown away.
    if (!isClosed) writer.write(buffer, 0, read)
    written += read
    arrived.signalAll()
}
...
// DrainedPiece.close(), before
if (isEnded) file.delete()
```

So by the drop, the receiver had downloaded several MB of the file (the header, the index at the end, and 5–10 s of
buffer) and then deleted all of it, while the sender started sending the same bytes again.

### After: whole pieces are kept

Two things are needed to keep a piece: it must have **arrived whole**, and we must know **which file and which
offset** it belongs to. The offset is known when the piece is asked for, so it is remembered by request id
(`origins`) and attached when the piece arrives (`claim`):

```kotlin
// NearbyMediaChannel.openPiece(), after
origins[requestId] = PieceOrigin(fileId, offset)

// NearbyMediaChannel.deliver(), after
origins.remove(requestId)?.let(piece::claim)
```

```kotlin
// DrainedPiece, after
fun claim(origin: PieceOrigin) = lock.withLock {
    this.origin = origin
    if (isEnded && failure == null) keepIfClaimed()   // already arrived? keep it now
}

private fun keepIfClaimed() {                        // also called when the drain ends
    val origin = origin ?: return
    if (isKept || isDeleted) return
    isKept = true
    keep(origin.fileId, KeptPiece(ByteSpan(origin.offset, origin.offset + written), file))
}
```

Note the two possible orders: the piece may finish arriving **before** or **after** it is claimed (Nearby calls back
on several threads). Both paths end in `keepIfClaimed()`, and the flags make sure it runs once. The file is now
deleted only if the piece is closed, finished, **and not kept** (`deleteIfUnwanted`).

### Why

The bytes were already paid for (radio time, battery, link time). Throwing them away and fetching them again is the
most basic waste there is.

### Lesson

> **Before you fetch something, ask whether you already fetched it.** Caching what you streamed is often the
> cheapest optimisation in a system.
>
> **When two events can arrive in either order, make each one check for the other**, and guard the shared action
> with a flag so it happens exactly once.

---

## 3. Step 2: who decides how the file comes over

### Before: the sender always sent the file

```kotlin
// LinkedSlidePeer.send(), before
override suspend fun send(file: OutgoingFile): EmptyResult<DataError> =
    peerLink.sendFile(file.uri) { transferId ->
        peerLink.transport.send(SlideMessage.FileOffer(file.itemId, transferId))
    }
```

The sender had no way to know the receiver already had part of the file.

### After: the receiver answers, the sender listens

Only the receiver knows how it played the video: over Nearby pieces (so it has them), over the home Wi-Fi (so it
has nothing kept), or not at all. So **the receiver decides**, and says so with a new message:

```kotlin
// SlideMessage.kt, after
data class FileRoute(val itemId: String, val route: Route) : SlideMessage {
    enum class Route { SEND, FETCHING, FETCHED, CANCELLED }
}
```

The view model picks the intent at the drop, from what it knows:

```kotlin
// ShelfViewModel, on DragCommit, after
val video = following?.takeIf { it.itemId == start.itemId && it.isLoaded }
val delivery = when {
    video?.viaLink == true -> Delivery.FETCH        // played from Nearby pieces: take the file from them
    slide.videoOffered -> Delivery.ASK_TO_SEND      // offered, but not read that way: "please send it"
    else -> Delivery.SENT                           // an ordinary card: nothing to say
}
```

The sender waits for the answer only when it offered a video (`peerMayFetch`), so ordinary files are not slowed:

```kotlin
// LinkedSlidePeer.send(), after
if (file.peerMayFetch) {
    when (peerFetch(file.itemId)) {
        Route.FETCHED -> return Result.Success(Unit)            // receiver has it: done, nothing sent
        Route.CANCELLED -> return Result.Failure(DataError.Network.CANCELLED)
        null -> return Result.Failure(DataError.Network.DISCONNECTED)
        Route.SEND, Route.FETCHING -> Unit                      // fall through: send as before
    }
}
return peerLink.sendFile(file.uri) { ... }
```

### Two design details worth studying

**1. Backward compatibility with a timeout.** An older build never sends `FileRoute`. The sender waits at most 5 s
(`RouteWait`) and then behaves exactly as before. New features that need an answer from the other side should
always have a "no answer" path.

**2. Stale messages and reused ids.** Shelf item ids are database row ids, and SQLite can give the same id to a new
row after the old one is deleted. Imagine: a late `FETCHED` for item `67` arrives after the sender has stopped
waiting. Later, a *different* file gets id `67` and is sent. If the old `FETCHED` were still in the map, the sender
would think the new file was delivered, **and delete it**, while the receiver never got it. So every answer carries
the time it arrived, and only fresh answers count:

```kotlin
// LinkedSlidePeer.peerFetch(), after
val heard = routes.map { notes -> notes[itemId]?.takeIf { it.at.elapsedNow() < RouteWait * 2 }?.route }
```

### Lesson

> **Let the side with the knowledge make the decision.** Here the receiver knows what it has.
>
> **Every new request/answer in a protocol needs three paths:** the answer comes, a different answer comes, no
> answer comes (old peer, lost link).
>
> **Ids that can be reused are a trap for late messages.** Give messages a lifetime, a fresh token, or a sequence
> number, so an old answer can never be mistaken for a new one.

---

## 4. Step 3: getting the missing parts without hurting playback

### Before: no priorities at all

The `FILE` payload and the player's pieces shared one Nearby connection, which has no priorities. The big file
transfer took most of the link, and a piece holding ~2.4 s of this video took up to 3.3 s to arrive. The player
runs out of buffer: a stall.

### After: ask only for gaps, and only when the player is idle

**Which bytes are missing?** That is pure arithmetic on byte ranges, so it lives in a small pure file
(`FileSpans.kt`) with its own tests:

```kotlin
// kept: [0,1MB) [1MB,2MB) [20MB,23.6MB)   size: 23.6 MB
missingSpans(size, have, maxBytes = 1 MB)   // → [2MB,3MB) [3MB,4MB) … [19MB,20MB)
```

**When to ask?** The channel counts the player's pieces that are still on their way:

```kotlin
// openPiece(..., forPlayer = true): +1 when asked, −1 when the piece has fully arrived
private val playerPieces = MutableStateFlow(0)
```

The fetch loop waits for that count to reach zero before each round of 3 requests:

```kotlin
// NearbyMediaChannel.fillIn(), after
withTimeoutOrNull(PlayerFirstMillis) { playerPieces.first { it <= 0 } }   // player first (at most 2 s)
val pieces = coroutineScope {
    missing.take(FetchPiecesAhead)
        .map { span -> async { openPiece(fileId, span.start, span.length, forPlayer = false) } }
        .awaitAll()
}
```

Why this works: ExoPlayer reads in bursts. It fills its buffer to 10 s, then **stops reading** until the buffer
drops to 5 s. At 0.41 MB per second of video, that leaves seconds of idle link in which the fetch can run.

Why the timeout (2 s): on a slow link the player might *never* be idle. Without a limit, the fetch would wait
forever. This is a **soft priority**: the player goes first, but the fetch can't starve.

Two more safety details:
- A round that adds no new bytes counts as fruitless; after 3 of those, the fetch gives up. Without this, a file
  that is shorter than announced would loop forever (each request returns 0 bytes, nothing changes).
- The `+1 / −1` counter is updated in a `finally` block, so a cancelled request can't leave the counter stuck above
  zero and block every future fetch.

### Lesson

> **When you can't get priorities from the transport, build them yourself**: watch the important traffic and only
> send background traffic in its gaps.
>
> **Any "wait until X" needs a limit**, or one bad case (a slow link) turns into a hang.
>
> **Counters shared across coroutines must be updated in `finally`**, or cancellation leaks them.

---

## 5. Step 4: putting the file together and proving it is right

### Before: nothing to prove

Nearby delivered the whole file as one payload, so the file was correct by construction.

### After: we assemble it, so we must check it

Pieces can overlap (the player starts at any byte, the fetch asks for aligned gaps). `assemblyPlan` picks, for
each part of the file, the kept piece that reaches furthest, so each byte is copied once:

```
kept:   [0────10)
           [5──────────────30)
              [8─12)
                               [30────40)
plan:   [0────10) from #0, [10─────30) from #1, [30────40) from #3
```

The copy and the hash happen in the same pass, so hashing costs no extra read:

```kotlin
// NearbyMediaChannel.assemble(), after
output.write(buffer, 0, read)
sha256.update(buffer, 0, read)
```

Meanwhile the sender hashes its own file **in parallel** with the fetch (`DigestRequest` → `Digest`), so the check
adds almost no waiting:

```kotlin
// NearbyMediaChannel.fetch(), after
val digest = async { askDigest(fileId) }          // starts now, the sender hashes while we fetch
if (!fillIn(...)) ...
val copy = assemble(...)
if (!digest.await().contentEquals(copy.sha256)) → fall back to sending the file
```

Why bother, when Nearby already protects each piece on the wire? Because the risk is not the radio, it is **our
own code**: an off-by-one in an offset would silently corrupt a video. The hash turns that silent bug into a safe
fallback.

### Lesson

> **The moment you split data and join it back yourself, add an end-to-end check.** Lower layers protect the
> transport, not your logic.
>
> **Run independent slow work in parallel** (`async`): the sender's hashing and the receiver's fetch don't depend on
> each other until the very end.

---

## 6. Step 5: moving the player to the local copy

### Before: reload the player

```kotlin
// ShelfViewModel.playLocalCopy(), before (always, once the file arrived)
engine.load(VideoSource.Local(item.uri), startAtMs = engine.positionMs, playWhenReady = engine.playWhenReady.value)
```

A reload throws away the decoder state, opens the file again, reads the index, and waits for a first frame: a
thumbnail flash, and fragile code (the guide's old Section 14 explains why it only worked because of the order in
which cards were composed).

It was needed because the sender, once done, gives back its permission to read the file, so pieces would be
refused (that was bug #10 in the guide: the video failed about 15 s after the file arrived).

### After: change where the bytes come from, not the player

The player reads through a `ByteStream` that the channel hands it. The player doesn't care **where** the bytes come
from. So instead of changing the player, we change the source underneath it:

```kotlin
// MediaChannel.settle(fileId, uri): "this file lives here now"
override fun settle(fileId: String, uri: String) {
    local.put(fileId, LocalCopy(uri, owned = null))?.owned?.delete()
    dropKept(fileId)
}
```

The open stream checks for a local copy at every piece boundary, and switches without the player noticing:

```kotlin
// PiecedStream.read(), after
if (current.size < PieceBytes) return -1
val next = currentStart + PieceBytes
if (goOnFromHere(next)) continue             // file is here: read the rest from disk
...
if (following == null) {
    if (goOnFromHere(next)) continue         // piece refused? maybe because the file is here now
    throw IOException("Next piece didn't come")
}
```

The order of events is what makes it safe. The receiver calls `settle` **before** it sends `FETCHED`, and the
sender only lets go of its file **after** `FETCHED`. So the player always has a source.

The view model now reloads only on the home-Wi-Fi path, where the player reads over HTTP and the channel can't swap
the source:

```kotlin
// ShelfViewModel.receive(), after
if (movesToOwnFile) streamedFromPeer = result.data   // only for VideoSource.Url
```

A small Linux detail makes this simpler: `settle` deletes the temporary assembled copy even if a stream still has it
open. On Linux (and so Android), an open file stays readable after it is deleted; its space is freed when the last
reader closes it.

### Lesson

> **Swap the data source, not the consumer.** A good abstraction (`ByteStream`) lets you change where data comes
> from without restarting the thing that uses it.
>
> **Order your hand-off so there is never a gap:** the new owner takes over *before* the old owner lets go
> ("make before break").

---

## 7. Step 6: when things go wrong

The old approach had one path; the new one must fail **into** the old one, never into something worse.

| Situation | Before | After |
|---|---|---|
| Everything fine | file sent, player reloads | fetched, no reload |
| No free space / hash mismatch | (not possible) | receiver answers `SEND` → old path |
| Video read over home Wi-Fi | old path | receiver answers `SEND` → old path |
| Link drops mid-way | transfer fails, card back with Undo | same |
| User taps the arriving card | transfer cancelled | fetch cancelled, sender told `CANCELLED` |
| Receiver is an older build | old path | sender waits 5 s → old path |
| Card dragged back | pieces deleted | kept pieces deleted too (`forget`) |

Cancelling a fetch is a nice coroutine example. The user's tap sets a flag; a small watcher cancels the fetch; and
we must tell **"the fetch was cancelled"** apart from **"this whole receive was cancelled"**:

```kotlin
// LinkedSlidePeer.fetchUnlessStopped(), after
val fetching = async { media.fetch(...) }
val stopper = launch {
    cancelled.first { file.peerItemId in it }
    fetching.cancel()
}
try {
    fetching.await()
} catch (e: CancellationException) {
    if (!fetching.isCancelled || !isActive) throw e   // it's us being cancelled: let it go up
    null                                              // only the fetch was stopped: report it
} finally {
    stopper.cancel()
}
```

### Lesson

> **A new fast path should degrade into the old, proven path**, not into an error.
>
> **Never swallow a `CancellationException` you didn't cause.** Check whose cancellation it is, and rethrow it if
> it's yours.

---

## 8. Where each piece of logic lives, and why

The project's rule is `presentation → domain ← data`, and platform code behind interfaces. LH-4 follows it:

| Decision or work | Where | Why there |
|---|---|---|
| "How was this video played? So how should its file come?" | `ShelfViewModel` (presentation) | It owns the playback state (`FollowedVideo.viaLink`) |
| The intent, as a type: `Delivery`, `peerMayFetch` | `SlidePeer.kt` (shelf **domain**) | Plain Kotlin both sides can share; no Nearby in it |
| The handshake (`FileRoute`), keeping, settling, fallback | `LinkedSlidePeer` (shelf **data**) | It already matched `FileOffer`s to cards: same job |
| The message and its wire format | `core:transfer:domain` / `core:transfer:data` | Every message lives there |
| `fetch`, `settle`, `forget` as an interface | `MediaChannel` (core transfer **domain**) | Keeps Nearby out of the shelf; default no-op bodies so fakes and other channels don't break |
| Pieces, disk, hashing, Nearby frames | `NearbyMediaChannel` (`androidMain`) | Platform code: files, `MessageDigest`, Nearby |
| Byte-range arithmetic | `FileSpans.kt` (`commonMain`) | Pure, so it's tested without a phone and ready for KMP |

### Lesson

> **Put the tricky arithmetic in a pure function**, away from threads and I/O. It is the part most likely to have an
> off-by-one, and the easiest part to test exhaustively.
>
> **Adding methods with default bodies to an interface** lets you grow it without breaking every implementation.

---

## 9. Tests: what changed

| Before | After |
|---|---|
| "Once the file is in, the player reloads from it" | Split in two: **no reload** after reading over Nearby; reload **kept** on the Wi-Fi path |
| (none) | The view model picks `FETCH`, `ASK_TO_SEND` or `SENT`; `peerMayFetch` only for a card that carried its video; `forget` on a drag-back |
| (none) | `LinkedSlidePeer`: every branch of the handshake on both sides (11 tests) |
| (none) | `FileSpansTest`: gaps, overlaps, end of file, assembly plan (9 tests) |
| Range frames round-trip | Plus digest frames, and malformed ones rejected |

One test was **rewritten, not deleted**: the old "reload" test described behaviour we deliberately changed. Its
intent ("after the file arrives the video keeps playing correctly") still holds, so it became two tests, one per
path.

Total: about 260 → **285** tests, all passing.

### Lesson

> **When a test fails because the behaviour changed on purpose, rewrite it to state the new rule.** Don't just
> delete it: the old rule usually still holds for some path.

---

## 10. Lessons to take away

1. Reuse what you already downloaded before downloading it again.
2. Let the side that has the knowledge make the decision.
3. Every request in a protocol needs "answered", "answered differently" and "never answered" paths.
4. Late messages plus reusable ids cause silent bugs: give answers a lifetime or a token.
5. No priorities in the transport? Send background work in the gaps of the important work.
6. Every wait needs a limit; every shared counter needs `finally`.
7. Splitting and re-joining data needs an end-to-end check (a hash).
8. Swap the data source under a consumer instead of restarting the consumer.
9. Hand over "make before break": the new owner first, then the old one lets go.
10. A fast path should fall back into the old path, never into an error.
11. Pure functions for tricky arithmetic, tested on their own.

---

## 11. Exercises

1. **Read `missingSpans` and `assemblyPlan`** in `FileSpans.kt`, then write one more test: a file of 10 bytes with
   kept spans `[2,4)` and `[3,8)`. What should each function return?
2. **Find the "make before break" order** in the code: where is `settle` called, and where is `FETCHED` sent? What
   would break if you swapped those two lines?
3. **Remove the freshness check** in `LinkedSlidePeer.peerFetch` in your head: describe, step by step, the scenario
   in which the sender deletes a file the receiver never got.
4. **Change `PlayerFirstMillis`** to 10 s. On a link that is just fast enough for the video, what happens to the
   fetch? And at 0?
5. **Design a resume:** today a dropped link fails the transfer. Sketch what would have to survive a reconnect
   (hint: `kept` already does) and what message the receiver would send to continue.
