# Coming improvements

Written 12 Nov 2026. What to work on next, in order, and why. The history behind it is in
`docs/LIVE_VIDEO_HISTORY.md`; the day-to-day state is in `notes-for-next.md`.

| No. | What | Size | Why now |
| --- | --- | --- | --- |
| 1 | Put the repo on a remote | 5 minutes | Everything exists only on one PC. |
| 2 | Re-run the 11 Nov benchmark on the current build | 20 minutes | 12 Nov was much slower; find out if it was the Wi-Fi or the code. |
| 3 | Fix the refused-piece hole | Small | The one new problem of 12 Nov with a known cause. |
| 4 | The transfer coordinator | About a day, with a full retest | The shelf ViewModel is too large to keep fixing safely. |
| 5 | First-lift time | After no. 4 | The ideas touch the code the coordinator moves. |

## 1. Put the repo on a remote

The repository has no remote. All branches, the fix reports and the history document exist only on this PC.

```bash
git remote add origin https://github.com/<your-username>/<repo-name>.git
git push -u origin main
git push origin --all
```

## 2. Re-run the 11 Nov benchmark once

- **What was seen on 12 Nov:** first lifts took 3.3–3.9 s and then jumped about 4 s ahead, against 1.1–2.4 s on
  11 Nov. A piece took 0.2–1.5 s to open, against 0.1–0.25 s. In one session the connection was set up three times
  within two minutes, on three different networks.
- **Why it comes first:** every timing fix sits on the link. Until it is known whether 12 Nov was a bad Wi-Fi day or
  a regression, no later number can be trusted.
- **How:** the same build on both phones, both plugged in, the method of
  `docs/reports/fix/00-2026-11-11-benchmark-comparison.md`.
- **Then:** if the numbers are back to those of 11 Nov, drop it. If not, finding the cause becomes the next job.

## 3. Fix the refused-piece hole

- **What happens:** after a card is pulled back, phone B goes on reading ahead until phone A refuses a piece ("its
  card isn't lifted"). The refused piece is missing from what B keeps. A lift that starts exactly on it is slow
  (2.3 s) and B jumps (3.8 s ahead, then 3.3 s more buffering). Seen once, 12 Nov 12:58.
- **Fix, one of:** B stops reading ahead at the pull-back; or B asks for the refused piece first at the next lift.
- **Details:** `docs/reports/fix/14-2026-11-11-aim-closer-only-after-playing-report.md`.

## 4. The transfer coordinator

One piece of code, alive as long as the app runs, that owns every file moving in or out.

- **Today:** the shelf screen's ViewModel (about 1,500 lines) decides how a file travels, starts the send or receive,
  tracks hidden items and reports errors. Every fix of 10 and 11 Nov added to it.
- **With it:** the screen only says "send this item" or "the peer let go of this card", and draws what the
  coordinator reports.
- **Add with it:**
  - a version check at pairing ("update the other phone"). The 10 Nov phone test ran on two different builds and
    nobody saw it;
  - a "still saving" signal from the receiver, to replace the fixed 3-minute wait for its confirmation.
- **Retest with it, since these were never tested:** a video of 15 minutes or more, a downloaded video (index at the
  start), and the phones off the home Wi-Fi (Wi-Fi on, not connected), which is the outdoors case.

## 5. First-lift time (after the coordinator)

A first lift still costs 1.4–2.8 s on a good day. Ideas, none started:

- A sends the frame it is on as a still at the lift, so B shows a picture at once.
- B reads ahead the first seconds of pictures and sound around A's playing position, not only the index.
- Allow the first megabyte of the file before a lift, as well as the index: wins back the 0.2 s of the access
  restriction.

## Also open, no order yet

- **Stalls after a drop:** B stalled once or twice for 1.0–1.8 s after the card landed, in several tests. Cause
  unknown; read the logs of a drop first.
- **Lift on Bluetooth with no shared Wi-Fi (outdoors):** a card dropped before Wi-Fi Direct is up is sent the slow
  way and stays on it.
- **Black card half on A** during two-way sending: seen once on 10 Nov, cause unknown.
- **Weak points of the 10 Nov fixes:** a send that fails while the app is closed gives no message; a video sent to a
  swiped-away phone has no arriving card.
