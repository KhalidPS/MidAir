# 2026-11-14: Size a desktop video card's still to cover the card, so the lifted card has it at once

This report explains **what went wrong**, **what we did instead**, and **what it brings**.

- Branch: `fix/2026-11-14/1901-video-still-fills-card`. One commit: `17d9676`, "fix: size a desktop video card's still
  to cover the card, so the lifted card has it at once". Built on `feature/2026-11-13/desktop-receiver`.
- **Merged** into `feature/2026-11-13/desktop-receiver` on 2026-11-14, after the device check.
- Devices: laptop (desktop app, sender) and phone M2101K6G (receiver).
- **Device result: works.** The user sent a video that was not playing from the laptop to the phone: the phone's
  half showed the thumbnail.

---

## 1. The picture in one minute

**Background:** when a card is lifted, the sender draws a copy of the card in an overlay, waits 2 frames, and records
that copy as a picture (`SlideState.announce`, `cardLayer.toImageBitmap()`). It encodes the picture and sends it in
`DragStart`. The receiver draws only that picture. On the desktop, a video card's thumbnail is its first frame,
decoded by FFmpeg in `VideoStillDecoder` (`core/presentation`, jvmMain) through `readVideoStill` (`core/media`).

**What went wrong:** sending a video that is **not playing** from the laptop to the phone, the card's half on the
phone was black. The laptop's half showed the thumbnail. Phone → laptop showed the thumbnail on both halves. Photos
were not affected.

The cause is in how the still was sized. The old code scaled the frame so its **longest** side fit the card's longest
side (a "fit" size), and reported `isSampled = true`. The card shows its thumbnail with `ContentScale.Crop`, which
Coil turns into `Scale.FILL`. Coil 3.6.3's memory cache (`MemoryCacheService.isCacheValueValidForSize`) reuses a
sampled image only if it covers the requested size under FILL (multiplier <= 1, or within 1 px). A fit-sized frame
is smaller than the card on one side, so the cache turned it down, and a new FFmpeg decode ran each time the card
asked again.

The lifted overlay copy is a new image request, so it waited for FFmpeg. Its picture was taken 2 frames after lift,
before the decode finished, so the card's dark background was sent. The laptop's own half filled in a moment later,
which is why it looked fine there.

Worked example: a card of 300×400 px and a 1920×1080 video.

| | Still | Multiplier under FILL | Cache |
|---|---|---|---|
| Before | 400×225 | 400 / 225 ≈ 1.78 > 1 | rejected, new decode |
| After | 711×400 | 1.0 | reused |

**Why Android is fine:** Coil's `coil-video` decoder on Android sizes the frame with the request's Scale. The cached
frame covers the card, and the lifted copy gets it from the memory cache at once.

This is not related to the edge light work done the same day.

**After:** the desktop still covers the card, so the cache reuses it and the lifted copy has it at once.

---

## 2. The fix

In `core/media`:

- **`readVideoStill(path, scaleFor)`:** takes a function that gets the **upright** frame size. The display rotation is
  applied before sizing, so portrait phone videos are sized the right way. The old `readVideoStill(path, maxSide)`
  stays as a wrapper.
- **`stillScale(width, height, targetWidth, targetHeight, fill, maxSide)`:** covers the target when `fill`, fits
  inside it otherwise. If only one target side is known, it uses that side. If none is known, it keeps the size. The
  result is capped so the longest side is at most `maxSide`.

In `VideoStillDecoder` (`core/presentation`, jvmMain):

- Passes Coil's requested size and `options.scale == Scale.FILL`.
- Raises the cap from 960 to 2048 px on the longest side. A covering frame of a wide video in a tall card is wider
  than the card.
- Reports `isSampled` only when the frame was really scaled down.

---

## 3. How it was checked

- **`:core:media:jvmTest`**, `VideoStillTest`: 8 tests, 0 failures. New cases:
  - a still covers a cropping card: 320×240 → 267×200 for a 100×200 target;
  - a portrait phone video is sized upright: 120×160 for a 120×120 target;
  - it fits when the card does not crop;
  - one known target side, and none;
  - the cap.
- **`:desktopApp:compileKotlin`** builds.

Not done:

- **No end-to-end test of Coil's memory cache.** It would need the FFmpeg and Skia native libraries in
  `core/presentation`'s tests.
- **Device test:** laptop → phone, a video not playing: the phone's half showed the thumbnail (user's check,
  2026-11-14).

---

## 4. What it brings

- **The lifted card should carry the thumbnail** when a not-playing video goes from the laptop to the phone. Confirmed
  on the devices.
- **Fewer FFmpeg decodes.** Shelf slots that scroll back on screen also reuse the cached frame now, instead of
  decoding again.
- **Cost:** a covering frame can be larger than before. For wide videos in tall cards this means a larger bitmap in
  memory, capped at 2048 px on the longest side.

---

## 5. Open points

1. **Portrait and landscape videos:** the device check did not say which; both are sized by the same rule.
2. **Whether to add an end-to-end cache test later**, with the native libraries in `core/presentation`'s tests.
3. **The edge-light branches** (`feature/2026-11-14/edge-light-desktop`) get this fix once they are rebased on, or merged with, `desktop-receiver`.
