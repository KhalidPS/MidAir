# Checkpoint: 2026-10-21

**Where we stand:** a file card lifted on one Android phone slides off the edge, continues onto the phone next to
it, and lands in that phone's shelf with the real file. This works between two physical phones (tested on a Redmi
Note 10 Pro on Android 13 and an Android 11 phone). The full plan and the decisions behind it are in
[`PLAN.md`](PLAN.md).

## Branches

| Branch | Head | State |
|---|---|---|
| `master` | `50d75f9` | Phases 0–6 |
| `feature/calibration` | (latest) | Phase 7a (line-up swipe), on top of `master`. **Not merged** |

## Done

| Phase | What works | Commits |
|---|---|---|
| 0. Foundation | KMP + Compose Multiplatform modules, convention plugins, Koin, dark theme, navigation | `90978e2`, `a60c178` |
| 1. Shelf | Add files (SAF), persistent shelf (Room), live previews (photos, video frames, PDF pages, text), grid | `22acace` |
| 2. Slide on one phone | Long-press lift, blur behind, drag with tilt, edge glow, send off the edge or spring back, Undo | `0995645` |
| 3. Twin Simulator | Slide protocol (CBOR, mm geometry, latency prediction), receiver animation and landing, dev-only simulator | `33717d5` |
| 4. Pairing | Edge-glow screen, hold Connect, 4-digit code confirmed on both phones, bloom on pairing, Nearby link | `4a03bbe` |
| 5. Real slide between phones | Card mirrored over Nearby, lands on the other phone; the real file is sent and kept in `Downloads/TransferingFileByAir` | `376e7f8`, `4d69d88` |
| 6a. Transfer progress | Progress ring on the arriving card, tap it to stop the file, Retry on a failed send | `0ab28e6` |
| 6b. Background transfers | Transfers run in an app-wide scope under a `dataSync` foreground service with a notification, so leaving the app or locking the phone doesn't stop them | `7d3f984` |
| 6c. Land in place | A let-go card flies straight into its slot at the top of the shelf and waits there with its ring (tap to stop); several can arrive at once; the arrival ripples light across the screen; fixed cards drawn off-place on right-to-left phones | `50d75f9` |
| 7a. Line-up swipe | Right after pairing (or "Line up" on the paired screen), one finger swipes from one phone onto the other; each phone learns which edge faces the other, the gap and the height offset, and slides follow it | `feature/calibration` |

**Tests:** 178 unit tests, all passing (`./gradlew testAndroidHostTest`).

## Known limitations

- **Phase 6 is untested on phones:** the 1 GB video check (also with the app in the background) is still to do.
- **Notification permission:** asked together with the Nearby permissions on Android 13+. Without it the transfer
  still runs, but its notification is hidden.
- **Background start:** the service is started when a transfer begins, which is always while the app is open. If
  Android refuses it anyway, the transfer runs without the extra protection.
- **Alignment:** until the screens are lined up, any edge leads to the other phone and the gap is assumed to be
  8 mm. The line-up is kept only while the app runs (remembering it comes with auto-reconnect). Its gap depends on
  comparing the two clocks over Nearby, so it can be a few millimetres off; side and height are measured directly.
- **Pairing needs:** Bluetooth, Wi-Fi and **Location switched on**, plus precise location permission. Android
  only lets apps find nearby phones while Location is on. The pairing screen now says so and offers a button.
- **Reconnect:** pairing is forgotten when the app closes; the phones pair again next time.
- **Review debt:** Phase 5 and the Location fix were not put through the multi-agent review (to save usage).
  The last review round of Phase 4 was also stopped part-way.
- **Hardware:** Nearby doesn't work reliably on emulators, so anything about the link needs two real phones.

## What's next

### Phase 6: check on phones

- Send a 1 GB video, leave the app and lock the phone mid-way; it should still arrive.
- Slide several cards one after another: each lands in its own slot and fills its ring; tap one to stop it.
- Retry a failed send from the snackbar.
- Try it with the Arabic (right-to-left) phone as sender and as receiver.

### Phase 7: calibration and polish

- ~~Calibration swipe~~ (7a, try it on the phones: swipe through the band from one phone onto the other).
- Remember the paired phone and its line-up, and reconnect automatically.
- AGSL shader glow (Android 13+), reduced-motion mode, TalkBack "Send to…" action.
- Baseline Profile and a 60 fps check on a mid-range phone.

### Phase 8: desktop receiver (KMP)

- A `jvm()` target, a LAN transport (Ktor sockets + mDNS), and a Compose Desktop receiver: the video's phone → laptop setup.

### Housekeeping

- Merge `feature/background-transfers` into `master` once Phase 6 works on the phones.
- Optionally, one targeted review of the file transfer code (Phases 5 and 6 weren't reviewed).

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
