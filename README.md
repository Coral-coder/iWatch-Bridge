# iWatch Bridge

Use an Apple Watch with an Android phone, as close to the iPhone experience as possible.

The Android app looks and works like the iPhone **Watch** app: My Watch, All Watches (several
watches, Auto Switch), Face Gallery, the pairing flow, and the per-watch settings pages.

## Two ways to connect

| Mode | Your iPhone | What Android needs | Status |
| --- | --- | --- | --- |
| **Shared with iPhone** | The watch stays paired to your iPhone; Android joins as a second phone | Nothing special: ordinary Bluetooth LE, plus the iWatch Bridge watch app | Android side done; watch app in `watchos/` |
| **Primary phone** | Android replaces the iPhone: pair, activate and run the watch | A USB Bluetooth adapter (no root), **or** root for the built-in radio | Link layer done; pairing handshake in progress (see [docs/ROADMAP.md](docs/ROADMAP.md)) |

**Why two modes.** A watch has exactly one companion phone in Apple's protocol. If Android is
the companion, the iPhone isn't. When the Android phone is your second phone, *Shared* keeps
everything native on the iPhone and adds notifications, media control and Find My Phone from
Android through the watch app.

**Why a USB adapter or root for Primary.** The watch talks to its phone on an Apple-specific L2CAP
fixed channel (0x003A) with ERTM channels and LE Secure Connections out-of-band pairing. Android
doesn't let apps use any of these. So this app runs its own Bluetooth host on a radio it fully
controls:
- a **USB Bluetooth adapter** on USB-C/OTG. No root, and the phone's Bluetooth stays free for
  headphones and cars.
- the phone's **built-in radio** through the Bluetooth HAL. Needs root (Magisk) and Android
  13+ firmware with the AIDL Bluetooth HAL, and Android's Bluetooth must be off while it runs.

## Repository

```
android/protocol   pure Kotlin protocol stack, unit-tested on the JVM
  hci/ l2cap/      HCI, ACL, L2CAP signaling, ERTM (Bluetooth Core spec)
  host/            HostStack: controller setup as an iPhone, discovery, links, Magnet on 0x3A
  magnet/ nrlp/    Apple link layers (Magnet service channels, NetworkRelay framing)
  crypto/          SPAKE2+ (RFC 9383), IKEv2 prf+/key schedule
  pairing/         setup steps and session
  registry/        multi-watch registry, active watch, Auto Switch
  shared/          Shared-mode BLE link protocol (mirrored in Swift)
  net/rec0de/...   codecs vendored from WatchWitch (Alloy, NWSC, bplist, protobuf, bulletins)
android/app        the Android app (Jetpack Compose, iOS-styled)
watchos/           the watch app for Shared mode (SwiftUI, XcodeGen)
docs/              protocol notes, roadmap
```

## Building

The Android CI (`.github/workflows/android.yml`) runs the tests and builds a debug APK on every
push. On `main` it also builds a release APK signed with the shared upload key from
Dev_deploy_setup. Tag `vX.Y.Z` to publish a GitHub Release.

Locally:

```bash
cd android && ./gradlew :protocol:test :app:assembleDebug
```

The watch app builds with the iOS TestFlight workflow (from Dev_deploy_setup). A commit subject
containing `*b` archives it, and `*u` uploads it to TestFlight.

## Credits and license

GPL-3.0. This project builds on **WatchWitch** by the Secure Mobile Networking Lab (SEEMOO), TU
Darmstadt (GPL-3.0; [paper](https://arxiv.org/abs/2507.07210),
[code](https://github.com/seemoo-lab/watchwitch)). Their codecs are vendored under
`android/protocol/src/main/kotlin/net/rec0de/`. They're unchanged except that an Android-only
helper was removed from `Utils.kt`, and `nwsc/NWSCManager.kt` is a new, smaller replacement. Public
reports of watchOS 26 pairing on Android informed the protocol notes; no code from unlicensed
projects is used.

Apple, Apple Watch and iPhone are trademarks of Apple Inc. This project is not affiliated with
Apple.
