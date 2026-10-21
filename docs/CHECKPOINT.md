# Checkpoint: 2026-10-21 (for 2026-10-22)

**Where we stand:** Phases 0–7 are done and merged into `master`. A file card lifted on one Android phone slides off
the edge, continues onto the phone next to it, lands in its slot on that phone's shelf and fills its ring while the
real file arrives. The phones line up with one swipe, remember each other and link up again by themselves. Tested on
two physical phones: a Redmi Note 10 Pro (Android 13) and an Android 11 phone set to Arabic (right-to-left). The full
plan and the decisions behind it are in [`PLAN.md`](PLAN.md).

## Start here tomorrow: the Baseline Profile run

Everything is set up (`:baselineprofile` module); it only needs a phone. Use the Redmi (Android 13).

1. Plug the phone in, unlock it, keep the screen on (Developer options → Stay awake helps), and check
   `adb devices` lists it.
2. Open the app once and add about a dozen files to the shelf (photos and a video), so there is something to scroll.
3. Generate the profile:
   ```bash
   export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
   ./gradlew :app:generateBaselineProfile
   ```
   It writes `app/src/release/generated/baselineProfiles/baseline-prof.txt` (and `startup-prof.txt`). Commit them
   on a `chore/` branch.
4. Measure:
   ```bash
   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
   ```
   Results are in `baselineprofile/build/outputs/connected_android_test_additional_output/`. Compare
   `startupWithoutProfile` and `startupWithProfile` (`timeToInitialDisplayMs`), and check `shelfScroll`:
   `frameDurationCpuMs` P90 under 16.7 ms means 60 fps.
5. If the shelf janks, profile the slide next (the blur and the glow are the likely costs).

The benchmark builds install a release build signed with the debug key; they replace the debug app on the phone, so
run `./gradlew :app:installDebug` afterwards.

## Branches

| Branch | Head | State |
|---|---|---|
| `master` | `acc2f23` (+ this checkpoint) | Phases 0–7, all merged |

Older feature branches are fully contained in `master` and can be deleted.

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
| 6b. Background transfers | Transfers run in an app-wide scope under a `dataSync` foreground service with a notification | `7d3f984` |
| 6c. Land in place | A let-go card flies straight into its shelf slot and waits there with its ring; several can arrive at once; light ripples across the screen on arrival; fixed cards drawn off-place on right-to-left phones | `50d75f9` |
| 7a. Line-up swipe | After pairing (or "Line up" on the paired screen), one finger swipes from one phone onto the other; each learns which edge faces the other, the gap and the height offset | `c765b7b` |
| 7b. Auto-reconnect | Paired phones remember each other and the line-up, and link up again by themselves while the app is open, after proving they hold the key agreed at pairing; "Unpair" makes both forget | `c64ccf8` |
| 7c. Flowing glow, reduced motion | Android 13+: the pairing glow is an AGSL shader whose colours flow along the edge; with "Remove animations" on, the glow and the line-up band stand still | `4212e94` |
| 7d. Baseline Profile setup | Profile generator (open the app, scroll the shelf) and benchmarks (cold start with and without the profile, shelf frame times); the app installs the profile itself | `acc2f23` |

**Tests:** 185 unit tests, all passing (`./gradlew testAndroidHostTest`).

**Tried on the phones and confirmed by the user:** slides between the phones, landing in place (6c), the line-up
swipe (7a), auto-reconnect (7b). **Not tried yet:** the flowing glow on the Redmi (7c; if it doesn't flow, look for
a `FlowingGlow` warning in logcat), reduced motion, the Baseline Profile run.

## Still to check on the phones

- A 1 GB video, leaving the app and locking the phone mid-way; it should still arrive (Phase 6b).
- Retry a failed send from the snackbar (Phase 6a).
- "Remove animations" on (Settings → Accessibility): the pairing glow stays still (Phase 7c).

## Known limitations

- **Alignment:** until the screens are lined up, any edge leads to the other phone and the gap is assumed to be
  8 mm. The line-up's gap depends on comparing the two clocks over Nearby, so it can be a few millimetres off; side
  and height are measured directly.
- **Reconnect:** only while the app is open on both phones, and only if the phone's name hasn't changed (it is how
  the remembered phone is spotted before it proves itself). Each phone remembers one other phone.
- **Pairing needs:** Bluetooth, Wi-Fi and **Location switched on**, plus precise location permission. Android only
  lets apps find nearby phones while Location is on; the pairing screen says so and offers a button.
- **Notification permission:** asked together with the Nearby permissions on Android 13+. Without it a transfer
  still runs, but its notification is hidden.
- **Ordering:** if a newer incoming file finishes before an older one, their two cards swap places (the shelf is
  ordered by when files arrived in full).
- **Release signing:** no release key yet; release builds (and the profile and benchmark builds) use the debug key.
- **Review debt:** Phases 5–7 were not put through the multi-agent review (to save usage). A targeted review of the
  transfer and reconnect code would be the one worth doing.
- **Hardware:** Nearby doesn't work reliably on emulators, so anything about the link needs two real phones.

## After that

### Phase 8: desktop receiver (KMP)

A `jvm()` target, a LAN transport (Ktor sockets + mDNS) next to Nearby, and a Compose Desktop receiver: the video's
phone → laptop setup. Nearby is Android-only, so the phone needs the LAN transport too, and both must be on the same
Wi-Fi. Start by reading PLAN.md section 4.1 and the Phase 8 row.

### Later

- TalkBack "Send to…" action (not needed now).
- The Android-only copy of the app (packages instead of modules, Koin): ask only for the folder location.

## How to run it

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:installDebug          # with each phone plugged in
```

On both phones: turn on Bluetooth, Wi-Fi and Location. Open the app, tap the phone icon at the bottom left, and
allow the permissions. Hold the phones side by side, hold **Connect** on one, confirm the same code on both, then
swipe one finger across both screens to line them up. Back on the shelf, long-press a file and drag it off the edge
towards the other phone. From then on, opening the app on both phones links them up again by itself.

Developer tool: the Twin Simulator (two fake phones on one screen) has no launcher icon. Open it with
`adb shell am start -n com.kotlistine.transferingfilebyair/.SimulatorActivity`.
