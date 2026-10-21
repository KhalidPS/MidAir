# Checkpoint: 2026-10-21

**Where we stand:** a file card lifted on one Android phone slides off the edge, continues onto the phone next to
it, and lands in that phone's shelf with the real file. This works between two physical phones (tested on a Redmi
Note 10 Pro on Android 13 and an Android 11 phone). The full plan and the decisions behind it are in
[`PLAN.md`](PLAN.md).

## Branches

| Branch | Head | State |
|---|---|---|
| `master` | `33717d5` | Phases 0–3 (shelf, slide choreography, Twin Simulator) |
| `feature/pairing` | `4a03bbe` | Phase 4, on top of `master`. **Not merged** |
| `feature/cross-device-slide` | `4d69d88` | Phase 5 + Location fix, on top of `feature/pairing`. **Not merged** |

Merging `feature/cross-device-slide` into `master` brings in both phases (fast-forward, no conflicts).

## Done

| Phase | What works | Commits |
|---|---|---|
| 0. Foundation | KMP + Compose Multiplatform modules, convention plugins, Koin, dark theme, navigation | `90978e2`, `a60c178` |
| 1. Shelf | Add files (SAF), persistent shelf (Room), live previews (photos, video frames, PDF pages, text), grid | `22acace` |
| 2. Slide on one phone | Long-press lift, blur behind, drag with tilt, edge glow, send off the edge or spring back, Undo | `0995645` |
| 3. Twin Simulator | Slide protocol (CBOR, mm geometry, latency prediction), receiver animation and landing, dev-only simulator | `33717d5` |
| 4. Pairing | Edge-glow screen, hold Connect, 4-digit code confirmed on both phones, bloom on pairing, Nearby link | `4a03bbe` |
| 5. Real slide between phones | Card mirrored over Nearby, lands on the other phone; the real file is sent and kept in `Downloads/TransferingFileByAir` | `376e7f8`, `4d69d88` |

**Tests:** 152 unit tests, all passing (`./gradlew testAndroidHostTest`).

## Known limitations

- **Big files:** the arriving card waits in the middle until the whole file is there, with no progress shown.
  Leaving the app or locking the phone during a transfer can interrupt it.
- **Alignment:** the app doesn't know which side the other phone is on (any edge works) and assumes an 8 mm gap,
  so the card can be slightly off where it crosses.
- **Pairing needs:** Bluetooth, Wi-Fi and **Location switched on**, plus precise location permission. Android
  only lets apps find nearby phones while Location is on. The pairing screen now says so and offers a button.
- **Reconnect:** pairing is forgotten when the app closes; the phones pair again next time.
- **Review debt:** Phase 5 and the Location fix were not put through the multi-agent review (to save usage).
  The last review round of Phase 4 was also stopped part-way.
- **Hardware:** Nearby doesn't work reliably on emulators, so anything about the link needs two real phones.

## What's next

### Phase 6: transfer polish

- Progress ring on the landing card while a file arrives (from Nearby's transfer updates).
- A foreground service (`dataSync`), so transfers survive leaving the app or locking the phone.
- Cancel a transfer, and retry a failed one.
- Clear errors when the link drops mid-transfer. The sender's item already comes back if the file doesn't arrive.
- Done when a 1 GB video transfers reliably, also with the app in the background.

### Phase 7: calibration and polish

- **Calibration swipe:** one finger swipes across both screens, which gives the exact side, vertical offset and
  gap, so the card lines up perfectly.
- Remember the paired phone and reconnect automatically.
- AGSL shader glow (Android 13+), reduced-motion mode, TalkBack "Send to…" action.
- Baseline Profile and a 60 fps check on a mid-range phone.

### Phase 8: desktop receiver (KMP)

- A `jvm()` target, a LAN transport (Ktor sockets + mDNS), and a Compose Desktop receiver: the video's phone → laptop setup.

### Housekeeping

- Merge `feature/cross-device-slide` into `master`.
- Optionally, one targeted review of Phase 5's file transfer code.

## How to run it

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:installDebug          # with each phone plugged in
```

On both phones: turn on Bluetooth, Wi-Fi and Location. Open the app, tap the phone icon at the bottom left, and
allow the permissions. Hold the phones side by side, hold **Connect** on one, then confirm the same code on both.
Back on the shelf, long-press a file and drag it off the edge towards the other phone.

Developer tool: the Twin Simulator (two fake phones on one screen) has no launcher icon. Open it with
`adb shell am start -n com.kotlistine.transferingfilebyair/.SimulatorActivity`.
