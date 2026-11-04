import SwiftUI

/// The iPhone side only carries the watch app (that's how watch apps are installed). The watch
/// app talks to the Android phone directly over Bluetooth LE.
@main
struct PhoneApp: App {
    var body: some Scene {
        WindowGroup {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Image(systemName: "applewatch.radiowaves.left.and.right").font(.system(size: 56)).foregroundStyle(.tint)
                    Text("iWatch Bridge").font(.largeTitle.bold())
                    Text("Use your Apple Watch with a second, Android phone while it stays paired to this iPhone.")
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Install Bridge on your Apple Watch from the Watch app's App Store section.", systemImage: "1.circle.fill")
                        Label("On the Android phone, open iWatch Bridge → Bridge → Shared with iPhone → Start.", systemImage: "2.circle.fill")
                        Label("Open Bridge on the watch. It finds the Android phone and connects.", systemImage: "3.circle.fill")
                    }
                    Text("Your iPhone keeps all of Apple Watch's native features. Bridge adds Android notifications, music control and Find My Phone.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding(24)
            }
        }
    }
}
