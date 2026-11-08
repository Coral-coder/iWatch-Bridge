# Roadmap and status

Legend: ✅ done and unit-tested · 🟡 implemented, not yet checked against a real watch · ⬜ to do

## Shared with iPhone mode
- ✅ Link protocol (GATT service, chunking, JSON messages), shared by Kotlin and Swift
- 🟡 Android GATT server: notifications (with reply/dismiss), now playing and media control, Find My Phone, phone battery; several watches at once; shared watches listed in My Watch / All Watches; restarts with the app and after reboot
- 🟡 Watch app (watchOS 10+): notifications, media remote, Find Phone, phone battery — compiles/archives clean in CI (TestFlight upload wired)
- ⬜ Watch app background delivery: watchOS keeps BLE alive only while the app runs or in
  extended-runtime sessions. Next is a relay over APNs for when the app is closed.

## Primary phone mode
| Layer | Status |
| --- | --- |
| HCI over USB adapter (no root) | 🟡 |
| HCI over built-in radio (root, AIDL HAL) | 🟡 |
| Present as an iPhone: class of device, name, EIR | 🟡 |
| ACL links, flow control, fragmentation | ✅ |
| L2CAP signaling, fixed-channel mask with 0x3A | ✅ |
| ERTM (segmentation, acks, REJ/SREJ, FCS) | ✅ |
| Magnet service table and channel tracking | ✅ |
| FE25 setup advertisement discovery | 🟡 |
| SPAKE2+ (PIN pairing) | ✅ RFC 9383 vectors |
| IKEv2 key schedule (prf+, SA keys) | ✅ |
| uIKE IKE_SA_INIT / IKE_AUTH on terminusPairing | ⬜ **next** |
| Optical pairing (camera) | ⬜ the decoder is Apple's; needs a clean reimplementation |
| LE Secure Connections with OOB data (SMP) | ⬜ |
| NetworkRelay: IKE class C/D, userspace ESP over ERTM | ⬜ |
| NWSC + Alloy/IDS session | 🟡 codecs vendored from WatchWitch |
| Activation relay (albert.apple.com) and Activation Lock | ⬜ |
| Notifications (bulletins) | 🟡 codecs vendored; app queue done |
| Settings sync | 🟡 app side done (pending until link) |
| Multiple watches, Auto Switch | ✅ registry; per-watch keys once pairing lands |
| Health sync | ⬜ (WatchWitch handlers can be adapted) |
| Calls / SMS | ⬜ |
| Watch faces sync | ⬜ |
| Apple Pay | ✖ needs Apple's provisioning servers |

## What needs a real watch
Every 🟡 row has to be checked against hardware with an HCI capture (Android's "Bluetooth HCI snoop
log", or btmon with a USB adapter). WatchWitch's Wireshark dissectors
(seemoo-lab/watchwitch-wireshark) decode Magnet, NRLP, Alloy and NWSC from such captures.

## Feature parity (vs. Merge, the one real competitor)

Merge (Merge Labs) is the only maintained Apple-Watch-to-Android app. It's subscription-gated
(~$6/mo), and its top user complaints are notification lag from Android battery optimization,
BLE disconnects/drain, and the paywall. A free/open-source app that is reliable is the opening.

Already built here (shared mode):
- ✅ Notification mirroring, per-app filtering, reply, dismiss
- ✅ Media control (play/pause/next/prev/volume) + now playing
- ✅ Find My Phone (ring), phone battery on the watch
- ✅ Multiple watches / active-watch switching

Merge has that we don't yet (ranked):
1. ⬜ **Health/activity sync** watch→phone into Health Connect (heart rate, steps, sleep,
   calories, workouts). This is Merge's core value — build first after the link is solid.
2. ⬜ ECG sync (Series 4+) → Android.
3. ⬜ Glucose (Health Connect → watch).
4. ⬜ Calls (answer/decline/dial) — limited by watchOS without native telephony; investigate.
5. ⬜ Over-internet relay when not in Bluetooth range (needs a relay; APNs for the watch side).

Reliability work (the real differentiator, since Merge is flaky):
- ⬜ Battery-optimization exemption prompt + setup wizard (Android kills the companion service).
- ⬜ Robust reconnect, watch battery to phone, connection health UI (started: shared-mode log).
