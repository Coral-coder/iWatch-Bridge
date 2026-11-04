# Protocol notes

What the watch and its companion phone say to each other. Each section notes its source:
**[spec]** published standards, **[WW]** WatchWitch paper/code/dissectors (GPL-3.0),
**[obs]** publicly reported behaviour of watchOS 26 pairing (facts only, no code).

## Transport
- BR/EDR ACL between phone and watch; LE for the setup advertisement and SMP bonding. [obs]
- Apple fixed L2CAP channel **0x003A** carries Magnet ("BT_CL") link signaling. Our L2CAP
  Information Response advertises it in the fixed-channel mask. [WW][obs]
- The watch in setup mode advertises 16-bit service data **0xFE25**, with setup metadata such as
  pairing strategy, product and watchOS version. [obs]

## Magnet (CID 0x3A) [WW]
`opcode u8 | length (u8, or u16 LE once version ≥ 9) | body`. The Version message always uses a
1-byte length. watchOS 26 reports version 0x0B. Opcodes are listed in `magnet/Magnet.kt`. Each
named service gets two dynamic L2CAP channels, one per direction:
`com.apple.terminusLink(.urgent)` (NRLP), `com.apple.terminusPairing` (setup), `CLink`/`CLinkHP`,
`com.apple.BT.TS` (time sync).

## NRLP [WW]
Frame: `seq/ack (2 bytes)` then records `type u8 | len u16 BE | payload | checksum u16`. The hello
frame is `TERMINUS` + data. Types: IKEv2 0x04, ESP class D 0x64, ESP class C 0x68, TCP 0x66,
and others. **The checksum algorithm isn't known yet**; `Nrlp.Checksum` is pluggable.

## Setup ("uIKE") [obs]
IKEv2 variant on terminusPairing: PRF HMAC-SHA512, AES-256-GCM. Authentication is one of:
- **PIN** ("Pair Manually"): SPAKE2+ P-256 (RFC 9383) with HKDF-SHA256, info string
  `"SPAKE2+ Authentication for IKEv2"`. The phone is the Prover.
- **Optical**: the particle-cloud animation encodes a 110-byte code used as a pre-shared key,
  bound to the current advertisement.

Then private notifies exchange the long-term identities, LE Secure Connections bonds with OOB data,
and the "normal link" comes up: NetworkRelay IKE for classes D and C, ESP over ERTM, then IDS over
NWSC/TCP on inner IPv6.

**Still unknown:** the exact w0/w1 derivation from the 6 digits, the identities and context in the
SPAKE2+ transcript, and the private notify payload formats. Next step: capture a real
iPhone↔watch setup with a USB adapter acting as a sniffer-in-the-middle, or with iOS
sysdiagnose Bluetooth logs.

## Activation [obs]
The watch's own MobileActivation request is relayed over HTTPS to
`albert.apple.com/deviceservices/deviceActivation`. Activation Lock challenges ask for the owner's
Apple Account. No cloud IDS/APNs registration is needed for local pairing.

## Local IDS (Alloy) [WW]
Alloy messages over NWSC streams, topics `com.apple.private.alloy.*`. Codecs are vendored in
`net/rec0de/android/watchwitch/alloy` and `nwsc`; bulletins (notifications) are in
`servicehandlers/messaging`.

## Shared mode (our own protocol)
GATT service `7d2a0001-5c4b-4f0e-9b1a-6a3e2f9c1b00`: `…0002` notify (phone→watch), `…0003` write
(watch→phone). Messages are JSON with a `t` discriminator, chunked as `flags u8 (bit 0 = more) |
bytes`. Definitions: `android/protocol/.../shared/SharedLink.kt` and `watchos/Shared/SharedLink.swift`.
