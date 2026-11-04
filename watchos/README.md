# iWatch Bridge — watch app (Shared-with-iPhone mode)

The "Bridge" watchOS app connects your Apple Watch to an Android phone over ordinary
Bluetooth LE, while the watch stays paired to your iPhone. It shows the Android phone's
notifications (with reply/dismiss), controls its music, and pings it to find it.

It is built inside a small iOS carrier app, which is how watchOS apps are distributed.

## Getting it onto your watch

### Route A — from a Mac with Xcode (fastest)
```bash
cd watchos
brew install xcodegen
xcodegen generate
open iWatchBridge.xcodeproj
```
In Xcode: pick your team under Signing, select the **iWatchBridge** scheme, and Run to your
paired watch (or to the iPhone, which installs the watch app). On the iPhone, the Watch app →
**General → Install Apps Automatically** makes the watch app appear on the watch.

### Route B — TestFlight (no Mac needed; uses GitHub's macOS runner)
1. In GitHub → **Actions** → the **iOS TestFlight** run → **Review / Approve** (the first run of
   this workflow is held for approval).
2. A commit whose subject contains `*b` archives the app as a compile check; `*u` uploads it to
   TestFlight. Running the workflow by hand (Actions → iOS TestFlight → Run workflow) always
   uploads.
3. Uploading needs an app record in App Store Connect for bundle ID
   `com.coralcoder.iwatchbridge`. App Store Connect's API can't create it, so either make it once
   (Apps → + → New App, platform iOS, that bundle ID) or add `APPLE_ID` + `FASTLANE_SESSION`
   secrets to Dev_deploy_setup so CI creates it with `fastlane produce`.
   - The App Store Connect **name** can be anything unique (e.g. "Bridge for Wear"); the name
     shown on the watch is `CFBundleDisplayName` = **Bridge**, set in `project.yml`.
4. Once the build is in TestFlight: install TestFlight on the iPhone, accept the build, and the
   watch app installs to the watch through the Watch app.

## Layout
```
project.yml            XcodeGen project (iOS carrier + watchOS app)
Shared/SharedLink.swift  link protocol, mirrors android/protocol/.../shared/SharedLink.kt
WatchApp/              the watchOS app: PhoneLink (BLE central) + SwiftUI views
PhoneApp/              the iOS carrier app (just explains how to use Bridge)
```

## Background delivery (known limit)
watchOS keeps the Bluetooth link alive only while the Bridge app is in the foreground or in an
extended-runtime session. Delivery while the app is closed needs a push relay (APNs); see
`docs/ROADMAP.md`.
