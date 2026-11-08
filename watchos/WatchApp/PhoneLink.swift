import CoreBluetooth
import Foundation
import UserNotifications
import WatchKit

/// The watch's Bluetooth LE link to the Android phone (Shared-with-iPhone mode).
@MainActor
final class PhoneLink: NSObject, ObservableObject {
    enum Status: Equatable { case bluetoothOff, searching, connecting, connected, unauthorized }

    @Published private(set) var status: Status = .searching
    @Published private(set) var phoneName = "Android phone"
    @Published private(set) var phoneBattery = -1
    @Published private(set) var phoneCharging = false
    @Published private(set) var notifications: [PhoneNotification] = []
    @Published private(set) var nowPlaying: (title: String, artist: String, app: String, playing: Bool, volume: Int)?
    @Published var ringing = false
    /// A short on-watch trace so connection problems are visible on the wrist, not silent.
    @Published private(set) var diagnostics: [String] = []

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var toPhone: CBCharacteristic?
    private let reassembler = SharedLink.Reassembler()
    private var writeQueue: [Data] = []
    private let knownKey = "phonePeripheral"
    private var watchdog: Task<Void, Never>?

    private func note(_ s: String) {
        let t = Date().formatted(date: .omitted, time: .standard)
        diagnostics.append("\(t)  \(s)")
        if diagnostics.count > 80 { diagnostics.removeFirst(diagnostics.count - 80) }
    }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        UNUserNotificationCenter.current().delegate = self
        // Ask once for permission to show the phone's notifications on the wrist.
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Raise an Android notification as a real watchOS notification (banner + sound on the wrist),
    /// not just a row inside the app. Delivered immediately (nil trigger).
    private func present(_ n: PhoneNotification) {
        let content = UNMutableNotificationContent()
        content.title = n.title.isEmpty ? n.appName : n.title
        content.subtitle = n.title.isEmpty ? "" : n.appName
        content.body = n.body
        content.sound = .default
        content.threadIdentifier = n.app
        let req = UNNotificationRequest(identifier: n.id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    private func clearPresented(_ id: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
    }

    // MARK: actions

    func dismiss(_ n: PhoneNotification) {
        notifications.removeAll { $0.id == n.id }
        send(.action(id: n.id, action: "dismiss", text: nil))
    }

    func reply(_ n: PhoneNotification, text: String) { send(.action(id: n.id, action: "reply", text: text)) }

    func media(_ cmd: String) { send(.mediaCommand(cmd)) }

    func findPhone(_ ring: Bool) { ringing = ring; send(.findPhone(ring: ring)) }

    private func send(_ m: LinkMessage) {
        guard let p = peripheral, toPhone != nil else { return }
        let idle = writeQueue.isEmpty // otherwise a write is in flight and didWriteValue continues the queue
        writeQueue.append(contentsOf: SharedLink.chunk(m.encode(), maxChunk: p.maximumWriteValueLength(for: .withResponse)))
        if idle { pump() }
    }

    private func pump() {
        guard let p = peripheral, let c = toPhone, let next = writeQueue.first else { return }
        p.writeValue(next, for: c, type: .withResponse)
    }

    private func hello() {
        let d = WKInterfaceDevice.current()
        d.isBatteryMonitoringEnabled = true
        let battery = d.batteryLevel >= 0 ? Int(d.batteryLevel * 100) : -1
        send(.watchHello(name: d.name, model: d.model, os: d.systemVersion, battery: battery))
    }

    private func scan() {
        guard central.state == .poweredOn else { return }
        // Reconnect straight to the phone we used last time; otherwise look for the service.
        if let s = UserDefaults.standard.string(forKey: knownKey), let id = UUID(uuidString: s),
           let p = central.retrievePeripherals(withIdentifiers: [id]).first {
            note("Reconnecting to the last phone")
            connect(p)
        } else {
            status = .searching
        }
        note("Scanning for the Bridge phone app")
        central.scanForPeripherals(withServices: [SharedLink.service])
    }

    private func connect(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        status = .connecting
        central.connect(p)
        // If it doesn't reach "ready" in time, the peripheral is likely stale (Android rotates its
        // Bluetooth address); drop it and scan again.
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 12_000_000_000)
            guard let self, !Task.isCancelled else { return }
            if self.status != .connected {
                self.note("Connect timed out; rescanning")
                self.central.cancelPeripheralConnection(p)
                self.peripheral = nil
                self.scan()
            }
        }
    }

    private func handle(_ m: LinkMessage) {
        switch m {
        case let .phone(name, battery, charging):
            phoneName = name; phoneBattery = battery; phoneCharging = charging
        case let .notification(n):
            notifications.removeAll { $0.id == n.id }
            notifications.insert(n, at: 0)
            WKInterfaceDevice.current().play(.notification)
            present(n)
        case let .notificationRemoved(id):
            notifications.removeAll { $0.id == id }
            clearPresented(id)
        case let .media(title, artist, app, playing, volume):
            nowPlaying = (title, artist, app, playing, volume)
        default: break
        }
    }
}

extension PhoneLink: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn: note("Bluetooth on"); scan()
            case .unauthorized: note("Bluetooth not allowed — enable it in Settings"); status = .unauthorized
            case .poweredOff: note("Bluetooth is off"); status = .bluetoothOff
            default: status = .bluetoothOff
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated {
            guard status == .searching || status == .connecting, self.peripheral?.state != .connected else { return }
            note("Found a phone (\(RSSI) dBm); connecting")
            // Android rotates its Bluetooth address, so a remembered peripheral may never answer;
            // drop that pending connect in favour of the phone we can actually see.
            if let old = self.peripheral, old.identifier != peripheral.identifier { central.cancelPeripheralConnection(old) }
            central.stopScan()
            connect(peripheral)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            note("Connected; finding the Bridge service")
            UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: knownKey)
            central.stopScan()
            peripheral.discoverServices([SharedLink.service])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { note("Connect failed: \(error?.localizedDescription ?? "unknown")"); self.peripheral = nil; scan() }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            note("Disconnected: \(error?.localizedDescription ?? "closed")")
            toPhone = nil; writeQueue.removeAll()
            status = .searching
            // Re-scan rather than only waiting on this peripheral, since its address may have changed.
            scan()
        }
    }
}

extension PhoneLink: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard let s = peripheral.services?.first(where: { $0.uuid == SharedLink.service }) else {
                note("Bridge service not found on this phone"); return
            }
            peripheral.discoverCharacteristics([SharedLink.toWatch, SharedLink.toPhone], for: s)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            for c in service.characteristics ?? [] {
                if c.uuid == SharedLink.toWatch { peripheral.setNotifyValue(true, for: c) }
                if c.uuid == SharedLink.toPhone { toPhone = c }
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if characteristic.isNotifying {
                watchdog?.cancel()
                status = .connected
                note("Link ready")
                hello()
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard characteristic.uuid == SharedLink.toWatch, let v = characteristic.value,
                  let whole = reassembler.accept(v), let m = LinkMessage.decode(whole) else { return }
            handle(m)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if !writeQueue.isEmpty { writeQueue.removeFirst() }
            pump()
        }
    }
}

extension PhoneLink: UNUserNotificationCenterDelegate {
    // Show the banner and play the sound even when Bridge is the frontmost app.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }
}
