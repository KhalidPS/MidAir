# 2026-11-15: Pair through a scanned code even when the laptop remembers the phone

This report explains **what went wrong**, **what we did instead**, and **what is still open**.

- Branch: `fix/2026-11-15/1410-qr-pairs-a-remembered-laptop`. One commit: `f38dc32`, "fix: pair through a scanned
  code even when the laptop remembers the phone". Built on `feature/2026-11-13/desktop-receiver` (`da7ad1e`).
- **Not merged.** Not yet tried on the devices.
- Devices in the report: laptop (desktop app) and phone Redmi Note 10 Pro (M2101K6G).

---

## 1. What went wrong

On 2026-11-15 the phone and the laptop would not pair. The phone log showed the same thing three times:

1. The automatic link-up failed: `Couldn't link up with 192.168.0.120 on <port>: ConnectException ... EHOSTUNREACH
   (No route to host)`.
2. The user then scanned the laptop's QR code. The laptop answered, but the phone logged
   `Called DESKTOP-CFBQSSU: mine QR, theirs TRUSTED → REFUSE`.

**Cause:** in `LanPeerLink.onControlChannel`, each end chose its proof (auth mode) before hearing the other. The
laptop had an invitation on show, but it still remembered the phone (`link.properties` had `peer_id` / `peer_key`),
so it chose TRUSTED. The phone had scanned the code, so it chose QR. `decideAuth` refuses QR against TRUSTED. So once
a laptop remembered a phone, the QR fallback could never work, whatever the phone did.

---

## 2. The fix

In `LanPeerLink.onControlChannel` (`core/transfer/lan`, jvmSharedMain):

- **The called end listens first.** The laptop (the end that was called) now reads the caller's `Auth` frame before
  choosing its own. If the caller says QR and this end has an invitation on show, it answers QR too. The two then
  link as a new pairing (`LINK_NEW_PAIRING`) and a fresh key is handed out.
- **The dialer is unchanged.** It still sends first and then reads. Because only the called end changed the order,
  the change stays compatible with an older build on either side.
- **A stale session is dropped for a matching QR proof too.** Before, an old session with the same peer was dropped
  only when both ends proved TRUSTED. Now it is also dropped when both prove QR.
- A small helper, `receiveAuth()`, reads the next frame as an `Auth` or fails with the same message as before.

The code is in jvmSharedMain, so it ships in both the Android app and the desktop app. For this bug the laptop build
is the one that matters, since the laptop is the called end.

---

## 3. How it was checked

- **`LanPeerLinkTest`** (jvm): two new tests.
  - "scanning the code of a laptop paired before links them again"
  - "scanning the code pairs a phone that forgot the laptop, though the laptop remembers it"
  - Both fail on the old code and pass with the fix.
- All 18 `LanPeerLinkTest` tests pass, and so do the rest of the lan module's tests.
- One unrelated test, "once the file is kept here, a stream reads on from this copy", failed once on the old code and
  passed on a rerun. It looks flaky. It was not touched.

Not done:

- **Device test.** The fix has not been tried on the phone and laptop yet.

---

## 4. What is still open: the automatic link-up

This fix only makes the QR fallback work. The automatic link-up still failed first, and that is **not fixed**.

What was measured:

- Phone and laptop are on the same Wi-Fi (`Khaled2026`, TP-Link TL-WR840N, 2.4 GHz, channel 4). Laptop
  `192.168.0.120`, phone `192.168.0.104`.
- The laptop app listens on TCP 56789, and the phone can open that port now. The laptop can ping the phone.
- A Java listener on the laptop received the phone's unicast UDP packets, but **none** of its multicast packets to
  `224.0.0.251:5353` (mDNS). Discovery depends on those.
- The Windows network profile is Public, with inbound blocked by default. There are Block rules for Android Studio's
  JBR `java.exe` next to the Allow rules.

Likely cause: the Windows firewall (Public profile or the Block rules), the router dropping multicast, or both. **Not
confirmed.**

---

## 5. Open points

1. **Try the fix on the devices:** scan the laptop's code while the laptop still remembers the phone, and check they
   link.
2. **Find out why mDNS does not reach the laptop:** switch the profile to Private or remove the Block rules, and
   check whether multicast then arrives.
3. **The flaky stream test**, if it fails again.
