# Roadmap and status

Legend: ✅ done and unit-tested · 🟡 implemented, not yet checked against a real watch · ⬜ to do

## Shared with iPhone mode
- ✅ Link protocol (GATT service, chunking, JSON messages), shared by Kotlin and Swift
- 🟡 Android GATT server: notifications (with reply/dismiss), now playing and media control, Find My Phone, phone battery; several watches at once
- 🟡 Watch app (watchOS 10+): notifications, media remote, Find Phone, phone battery
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
