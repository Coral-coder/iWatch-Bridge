import Foundation
import CoreBluetooth

/// Shared-with-iPhone link protocol. Mirrors android/protocol/.../shared/SharedLink.kt exactly:
/// the Android phone is the GATT server; messages are JSON with a "t" discriminator, split into
/// chunks of [flags byte (bit 0 = more follow)] + bytes.
enum SharedLink {
    static let service = CBUUID(string: "7d2a0001-5c4b-4f0e-9b1a-6a3e2f9c1b00")
    static let toWatch = CBUUID(string: "7d2a0002-5c4b-4f0e-9b1a-6a3e2f9c1b00")
    static let toPhone = CBUUID(string: "7d2a0003-5c4b-4f0e-9b1a-6a3e2f9c1b00")
    static let protocolVersion = 1

    static func chunk(_ payload: Data, maxChunk: Int) -> [Data] {
        let room = max(1, maxChunk - 1)
        if payload.isEmpty { return [Data([0])] }
        var out: [Data] = []
        var o = payload.startIndex
        while o < payload.endIndex {
            let end = min(payload.endIndex, o + room)
            var c = Data([end < payload.endIndex ? 1 : 0])
            c.append(payload[o..<end])
            out.append(c)
            o = end
        }
        return out
    }

    final class Reassembler {
        private var buf = Data()
        func accept(_ chunk: Data) -> Data? {
            guard let flags = chunk.first else { return nil }
            buf.append(chunk.dropFirst())
            if buf.count > 256 * 1024 { buf.removeAll(); return nil }
            if flags & 1 == 1 { return nil }
            defer { buf.removeAll() }
            return buf
        }
    }
}

struct PhoneNotification: Codable, Identifiable, Equatable {
    let id: String
    let app: String
    let appName: String
    let title: String
    let body: String
    let time: Int64
    var canReply: Bool = false
    var actions: [String] = []
}

enum LinkMessage {
    // phone -> watch
    case phone(name: String, battery: Int, charging: Bool)
    case notification(PhoneNotification)
    case notificationRemoved(id: String)
    case media(title: String, artist: String, app: String, playing: Bool, volume: Int)
    // watch -> phone
    case watchHello(name: String, model: String, os: String, battery: Int)
    case action(id: String, action: String, text: String?)
    case mediaCommand(String)
    case findPhone(ring: Bool)
    case ping
    case unknown

    private struct Envelope: Codable {
        var t: String
        var name: String?, battery: Int?, charging: Bool?, version: Int?
        var id: String?, app: String?, appName: String?, title: String?, body: String?, time: Int64?, canReply: Bool?, actions: [String]?
        var artist: String?, playing: Bool?, volume: Int?
        var model: String?, os: String?
        var action: String?, text: String?, cmd: String?, ring: Bool?
    }

    static func decode(_ data: Data) -> LinkMessage? {
        guard let e = try? JSONDecoder().decode(Envelope.self, from: data) else { return nil }
        switch e.t {
        case "phone": return .phone(name: e.name ?? "Phone", battery: e.battery ?? -1, charging: e.charging ?? false)
        case "notif":
            return .notification(PhoneNotification(id: e.id ?? UUID().uuidString, app: e.app ?? "", appName: e.appName ?? "",
                                                    title: e.title ?? "", body: e.body ?? "", time: e.time ?? 0,
                                                    canReply: e.canReply ?? false, actions: e.actions ?? []))
        case "notifGone": return .notificationRemoved(id: e.id ?? "")
        case "media": return .media(title: e.title ?? "", artist: e.artist ?? "", app: e.app ?? "", playing: e.playing ?? false, volume: e.volume ?? 0)
        default: return .unknown
        }
    }

    func encode() -> Data {
        var e: Envelope
        switch self {
        case let .watchHello(name, model, os, battery):
            e = Envelope(t: "watch", name: name, battery: battery, version: SharedLink.protocolVersion, model: model, os: os)
        case let .action(id, action, text): e = Envelope(t: "act", id: id, action: action, text: text)
        case let .mediaCommand(cmd): e = Envelope(t: "mediaCmd", cmd: cmd)
        case let .findPhone(ring): e = Envelope(t: "find", ring: ring)
        default: e = Envelope(t: "ping")
        }
        return (try? JSONEncoder().encode(e)) ?? Data()
    }
}
