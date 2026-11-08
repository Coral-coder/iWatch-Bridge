import SwiftUI

@main
struct BridgeWatchApp: App {
    @StateObject private var link = PhoneLink()

    var body: some Scene {
        WindowGroup {
            TabView {
                NotificationsView()
                NowPlayingView()
                PhoneView()
            }
            .tabViewStyle(.verticalPage)
            .environmentObject(link)
        }
    }
}

struct NotificationsView: View {
    @EnvironmentObject var link: PhoneLink

    var body: some View {
        NavigationStack {
            List {
                if link.notifications.isEmpty {
                    Text(link.status == .connected ? "No Notifications" : "Not connected to your Android phone")
                        .foregroundStyle(.secondary)
                }
                ForEach(link.notifications) { n in
                    NavigationLink(value: n.id) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(n.appName.uppercased()).font(.caption2).foregroundStyle(.secondary)
                            Text(n.title).font(.headline).lineLimit(1)
                            Text(n.body).font(.footnote).lineLimit(2).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions { Button(role: .destructive) { link.dismiss(n) } label: { Image(systemName: "xmark") } }
                }
            }
            .navigationTitle("Android")
            .navigationDestination(for: String.self) { id in
                if let n = link.notifications.first(where: { $0.id == id }) { NotificationDetail(n: n) }
            }
        }
    }
}

struct NotificationDetail: View {
    @EnvironmentObject var link: PhoneLink
    @Environment(\.dismiss) private var close
    let n: PhoneNotification
    @State private var reply = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(n.appName).font(.caption).foregroundStyle(.secondary)
                Text(n.title).font(.headline)
                Text(n.body)
                if n.canReply {
                    TextField("Reply", text: $reply)
                        .onSubmit { if !reply.isEmpty { link.reply(n, text: reply); close() } }
                }
                Button("Dismiss", role: .destructive) { link.dismiss(n); close() }
            }
        }
    }
}

struct NowPlayingView: View {
    @EnvironmentObject var link: PhoneLink

    var body: some View {
        VStack(spacing: 10) {
            if let np = link.nowPlaying {
                Text(np.title.isEmpty ? "Not Playing" : np.title).font(.headline).lineLimit(2).multilineTextAlignment(.center)
                Text(np.artist).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            } else {
                Text("Not Playing").font(.headline).foregroundStyle(.secondary)
            }
            HStack(spacing: 18) {
                Button { link.media("previous") } label: { Image(systemName: "backward.fill") }
                Button { link.media("playPause") } label: { Image(systemName: link.nowPlaying?.playing == true ? "pause.fill" : "play.fill").font(.title2) }
                Button { link.media("next") } label: { Image(systemName: "forward.fill") }
            }
            .buttonStyle(.plain)
            HStack(spacing: 30) {
                Button { link.media("volDown") } label: { Image(systemName: "speaker.minus.fill") }
                Button { link.media("volUp") } label: { Image(systemName: "speaker.plus.fill") }
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
        .navigationTitle("Now Playing")
    }
}

struct PhoneView: View {
    @EnvironmentObject var link: PhoneLink

    private var statusText: String {
        switch link.status {
        case .connected: return "Connected"
        case .connecting: return "Connecting…"
        case .searching: return "Looking for your phone…"
        case .bluetoothOff: return "Bluetooth is off"
        case .unauthorized: return "Allow Bluetooth in Settings"
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Image(systemName: "candybarphone").font(.largeTitle)
                Text(link.phoneName).font(.headline)
                Text(statusText).font(.footnote).foregroundStyle(link.status == .connected ? .green : .secondary)
                if link.phoneBattery >= 0 {
                    Label("\(link.phoneBattery)%", systemImage: link.phoneCharging ? "battery.100.bolt" : "battery.75")
                        .font(.footnote)
                }
                Button(link.ringing ? "Stop" : "Ping Phone") { link.findPhone(!link.ringing) }
                    .tint(link.ringing ? .red : .blue)
                    .disabled(link.status != .connected)

                if !link.diagnostics.isEmpty {
                    Divider()
                    Text("Connection log").font(.caption2).foregroundStyle(.secondary)
                    ForEach(Array(link.diagnostics.suffix(12).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }
}
