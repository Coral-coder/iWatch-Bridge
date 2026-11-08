package com.coralcoder.iwatchbridge.shared

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.view.KeyEvent
import com.coralcoder.iwatchbridge.core.NotificationQueue
import com.coralcoder.iwatchbridge.protocol.shared.Message
import com.coralcoder.iwatchbridge.protocol.shared.SharedLink
import com.coralcoder.iwatchbridge.service.NotificationRelayService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared-with-iPhone mode, phone side: a GATT server the iWatch Bridge watch app connects to.
 * Uses only public Android Bluetooth APIs (no root, no adapter), and runs alongside the phone's
 * other Bluetooth devices. Several watches can be connected at once.
 */
@SuppressLint("MissingPermission") // checked by the UI before start()
class SharedLinkServer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called when a watch app introduces itself, so it can be listed with the paired watches. */
    private val onWatchHello: (Message.WatchHello) -> Unit = {},
) {
    data class WatchConn(val address: String, val name: String = "Apple Watch", val model: String = "", val os: String = "", val battery: Int = -1, val subscribed: Boolean = false)
    data class State(val running: Boolean = false, val advertising: Boolean = false, val watches: Map<String, WatchConn> = emptyMap(), val error: String? = null) {
        val connected get() = watches.size
        val ready get() = watches.values.count { it.subscribed }
        val status: String get() = when {
            error != null -> error
            !running -> "Off"
            watches.values.any { it.subscribed } -> watches.values.filter { it.subscribed }.joinToString { it.name }
            else -> "Waiting for watch app"
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Human-readable trace of the shared link, shown on the Bridge screen so connection problems
    // are visible instead of a silent 'Waiting for watch app'.
    private val _log = MutableSharedFlow<String>(replay = 120, extraBufferCapacity = 120)
    val log: SharedFlow<String> = _log.asSharedFlow()
    private fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.tryEmit("$t  $msg")
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private lateinit var toWatch: BluetoothGattCharacteristic
    private val mtu = ConcurrentHashMap<String, Int>()
    private val inbound = ConcurrentHashMap<String, SharedLink.Reassembler>()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    // One notification chunk is in flight at a time, across all watches. The sender waits for the
    // controller to confirm each chunk (onNotificationSent) and retries instead of dropping, so a
    // momentarily full BLE buffer never loses a notification. Over BLE these notifications are
    // link-layer reliable; the old code lost them in app-level flow control.
    private val sendChannel = Channel<Outgoing>(Channel.UNLIMITED)
    @Volatile private var ack = CompletableDeferred<Boolean>().apply { complete(true) }
    @Volatile private var ackAddress: String? = null
    private var jobs = mutableListOf<Job>()
    private var ringtone: Ringtone? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _state.value = _state.value.copy(advertising = true)
            log("Advertising to the watch app; open Bridge on your Apple Watch")
        }
        override fun onStartFailure(errorCode: Int) {
            _state.value = _state.value.copy(error = "Couldn't advertise (error $errorCode)")
            log("Advertise failed: error $errorCode (need the Nearby devices permission?)")
        }
    }

    private val prefs = context.getSharedPreferences("shared_link", Context.MODE_PRIVATE)
    /** Whether the user left sharing on; restored at app start and after reboot. */
    val enabled get() = prefs.getBoolean("enabled", false)

    fun start() {
        if (_state.value.running) return
        prefs.edit().putBoolean("enabled", true).apply()
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { _state.value = State(error = "Turn on Bluetooth"); log("Bluetooth is off"); return }
        val s = manager.openGattServer(context, callback) ?: run { _state.value = State(error = "Bluetooth GATT unavailable"); log("openGattServer returned null"); return }
        toWatch = BluetoothGattCharacteristic(UUID.fromString(SharedLink.TO_WATCH),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        val toPhone = BluetoothGattCharacteristic(UUID.fromString(SharedLink.TO_PHONE),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        s.addService(BluetoothGattService(UUID.fromString(SharedLink.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(toWatch); addCharacteristic(toPhone)
        })
        server = s
        adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid.fromString(SharedLink.SERVICE)).setIncludeDeviceName(false).build(),
            advertiseCallback,
        )
        _state.value = State(running = true)
        log("Sharing started")
        if (adapter.bluetoothLeAdvertiser == null) { _state.value = _state.value.copy(error = "This phone can't advertise over Bluetooth LE"); log("bluetoothLeAdvertiser is null") }
        com.coralcoder.iwatchbridge.service.BridgeService.startNow(context)

        jobs += scope.launch {
            var sent = emptySet<String>()
            NotificationQueue.items.collect { items ->
                val keys = items.map { it.key }.toSet()
                items.filter { it.key !in sent }.forEach { n ->
                    broadcast(Message.Notification(n.key, n.app, n.appName, n.title, n.body, n.postedAt,
                        canReply = NotificationRelayService.canReply(n.key)))
                }
                (sent - keys).forEach { broadcast(Message.NotificationRemoved(it)) }
                sent = keys
            }
        }
        jobs += scope.launch {
            while (isActive) { broadcast(phoneStatus()); nowPlaying()?.let { broadcast(it) }; delay(60_000) }
        }
        jobs += scope.launch { runSender() }
    }

    /** The single serialized notification sender: one chunk out, confirmed, then the next. */
    private suspend fun runSender() {
        for (out in sendChannel) {
            val dev = devices[out.address] ?: continue // watch gone; drop its backlog
            var delivered = false
            var attempt = 0
            while (!delivered && attempt < 40 && devices.containsKey(out.address)) {
                attempt++
                ack = CompletableDeferred()
                ackAddress = out.address
                val queued = rawNotify(dev, out.chunk)
                delivered = if (queued) (withTimeoutOrNull(4000) { ack.await() } ?: false) else false
                if (!delivered) delay(12L * attempt.coerceAtMost(12)) // let the BLE buffer drain, then retry
            }
        }
    }

    private fun rawNotify(device: BluetoothDevice, chunk: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(device, toWatch, false, chunk) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { toWatch.value = chunk; server?.notifyCharacteristicChanged(device, toWatch, false) == true }
        }

    fun stop() {
        prefs.edit().putBoolean("enabled", false).apply()
        shutdown()
    }

    private fun shutdown() {
        jobs.forEach { it.cancel() }; jobs.clear()
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        devices.clear(); mtu.clear(); inbound.clear()
        _state.value = State()
    }

    private fun phoneStatus(): Message.PhoneStatus {
        val bm = context.getSystemService(BatteryManager::class.java)
        val name = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull() ?: Build.MODEL
        return Message.PhoneStatus(name, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging)
    }

    fun broadcast(m: Message) = devices.keys.filter { _state.value.watches[it]?.subscribed == true }.forEach { send(it, m) }

    /**
     * Inject a test notification through the real delivery path (collector is bypassed; this goes
     * straight to broadcast). Returns how many watches it was sent to, so the UI can report it.
     */
    fun sendTest(): Int {
        val targets = _state.value.watches.values.count { it.subscribed }
        val n = Message.Notification(
            id = "test-" + System.currentTimeMillis(), app = context.packageName, appName = "iWatch Bridge",
            title = "Test notification", body = "If you can see this on your watch, the link works.",
            time = System.currentTimeMillis(),
        )
        if (targets == 0) log("Test tapped, but no watch is connected and subscribed yet")
        else log("Test notification sent to $targets watch(es)")
        broadcast(n)
        return targets
    }

    private fun send(address: String, m: Message) {
        val chunks = SharedLink.chunk(SharedLink.encode(m), (mtu[address] ?: 23) - 3)
        chunks.forEach { sendChannel.trySend(Outgoing(address, it)) }
    }

    private data class Outgoing(val address: String, val chunk: ByteArray)

    private fun onMessage(address: String, m: Message) {
        when (m) {
            is Message.WatchHello -> {
                update(address) { it.copy(name = m.name, model = m.model, os = m.os, battery = m.battery) }
                onWatchHello(m)
                send(address, phoneStatus())
                NotificationQueue.items.value.forEach { n ->
                    send(address, Message.Notification(n.key, n.app, n.appName, n.title, n.body, n.postedAt, NotificationRelayService.canReply(n.key)))
                }
                nowPlaying()?.let { send(address, it) }
            }
            is Message.NotificationAction -> when (m.action) {
                "dismiss" -> NotificationRelayService.dismiss(m.id)
                "reply" -> m.text?.let { NotificationRelayService.reply(context, m.id, it) }
                else -> NotificationRelayService.invokeAction(m.id, m.action)
            }
            is Message.MediaCommand -> mediaCommand(m.cmd)
            is Message.FindPhone -> ring(m.ring)
            Message.Ping -> send(address, phoneStatus())
            else -> {}
        }
    }

    private fun ring(on: Boolean) {
        ringtone?.stop(); ringtone = null
        if (!on) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        scope.launch { delay(30_000); ringtone?.stop() }
    }

    private fun mediaController(): MediaController? = runCatching {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, NotificationRelayService::class.java)).firstOrNull()
    }.getOrNull()

    private fun nowPlaying(): Message.NowPlaying? {
        val c = mediaController() ?: return null
        val md = c.metadata ?: return null
        val audio = context.getSystemService(AudioManager::class.java)
        val vol = 100 * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxOf(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        return Message.NowPlaying(
            md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            c.packageName, c.playbackState?.state == PlaybackState.STATE_PLAYING, vol,
        )
    }

    private fun mediaCommand(cmd: String) {
        val audio = context.getSystemService(AudioManager::class.java)
        val t = mediaController()?.transportControls
        when (cmd) {
            Message.MediaCommand.PLAY_PAUSE -> when {
                t == null -> {
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }
                mediaController()?.playbackState?.state == PlaybackState.STATE_PLAYING -> t.pause()
                else -> t.play()
            }
            Message.MediaCommand.NEXT -> t?.skipToNext()
            Message.MediaCommand.PREVIOUS -> t?.skipToPrevious()
            Message.MediaCommand.VOL_UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            Message.MediaCommand.VOL_DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        }
        scope.launch { delay(400); nowPlaying()?.let { broadcast(it) } }
    }

    private fun update(address: String, f: (WatchConn) -> WatchConn) {
        val cur = _state.value.watches[address] ?: WatchConn(address)
        _state.value = _state.value.copy(watches = _state.value.watches + (address to f(cur)))
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                devices[device.address] = device; update(device.address) { it }
                log("Watch connected (${device.address})")
            } else {
                devices.remove(device.address); mtu.remove(device.address); inbound.remove(device.address)
                _state.value = _state.value.copy(watches = _state.value.watches - device.address)
                log("Watch disconnected (${device.address}), status $status")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, m: Int) { mtu[device.address] = m }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (ackAddress == device.address && ack.isActive) ack.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (descriptor.uuid == CCCD) {
                val sub = value.isNotEmpty() && value[0].toInt() and 1 == 1
                update(device.address) { it.copy(subscribed = sub) }
                log(if (sub) "Watch app subscribed — link ready" else "Watch app unsubscribed")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid.toString() != SharedLink.TO_PHONE) return
            val whole = inbound.getOrPut(device.address) { SharedLink.Reassembler() }.accept(value) ?: return
            runCatching { SharedLink.decode(whole) }.onSuccess { m -> scope.launch { onMessage(device.address, m) } }
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
