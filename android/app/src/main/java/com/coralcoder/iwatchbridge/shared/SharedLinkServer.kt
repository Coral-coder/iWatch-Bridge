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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Shared-with-iPhone mode, phone side: a GATT server the iWatch Bridge watch app connects to.
 * Uses only public Android Bluetooth APIs (no root, no adapter), and runs alongside the phone's
 * other Bluetooth devices. Several watches can be connected at once.
 */
@SuppressLint("MissingPermission") // checked by the UI before start()
class SharedLinkServer(private val context: Context, private val scope: CoroutineScope) {
    data class WatchConn(val address: String, val name: String = "Apple Watch", val model: String = "", val os: String = "", val battery: Int = -1, val subscribed: Boolean = false)
    data class State(val running: Boolean = false, val watches: Map<String, WatchConn> = emptyMap(), val error: String? = null) {
        val status: String get() = when {
            error != null -> error
            !running -> "Off"
            watches.values.any { it.subscribed } -> watches.values.filter { it.subscribed }.joinToString { it.name }
            else -> "Waiting for watch app"
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private lateinit var toWatch: BluetoothGattCharacteristic
    private val mtu = ConcurrentHashMap<String, Int>()
    private val inbound = ConcurrentHashMap<String, SharedLink.Reassembler>()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val outQueue = ConcurrentHashMap<String, LinkedBlockingQueue<ByteArray>>()
    private val sending = ConcurrentHashMap<String, Boolean>()
    private var jobs = mutableListOf<Job>()
    private var ringtone: Ringtone? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { _state.value = _state.value.copy(error = "Couldn't advertise (error $errorCode)") }
    }

    fun start() {
        if (_state.value.running) return
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { _state.value = State(error = "Turn on Bluetooth"); return }
        val s = manager.openGattServer(context, callback) ?: run { _state.value = State(error = "Bluetooth GATT unavailable"); return }
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
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        devices.clear(); mtu.clear(); inbound.clear(); outQueue.clear(); sending.clear()
        _state.value = State()
    }

    private fun phoneStatus(): Message.PhoneStatus {
        val bm = context.getSystemService(BatteryManager::class.java)
        val name = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull() ?: Build.MODEL
        return Message.PhoneStatus(name, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging)
    }

    fun broadcast(m: Message) = devices.keys.filter { _state.value.watches[it]?.subscribed == true }.forEach { send(it, m) }

    private fun send(address: String, m: Message) {
        val chunks = SharedLink.chunk(SharedLink.encode(m), (mtu[address] ?: 23) - 3)
        val q = outQueue.getOrPut(address) { LinkedBlockingQueue() }
        chunks.forEach(q::add)
        pump(address)
    }

    /** One notification in flight per device; the next goes out in onNotificationSent. */
    private fun pump(address: String) {
        if (sending[address] == true) return
        val chunk = outQueue[address]?.poll() ?: return
        val dev = devices[address] ?: return
        sending[address] = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(dev, toWatch, false, chunk) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { toWatch.value = chunk; server?.notifyCharacteristicChanged(dev, toWatch, false) == true }
        }
        if (!ok) sending[address] = false
    }

    private fun onMessage(address: String, m: Message) {
        when (m) {
            is Message.WatchHello -> {
                update(address) { it.copy(name = m.name, model = m.model, os = m.os, battery = m.battery) }
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
            } else {
                devices.remove(device.address); mtu.remove(device.address); inbound.remove(device.address)
                outQueue.remove(device.address); sending.remove(device.address)
                _state.value = _state.value.copy(watches = _state.value.watches - device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, m: Int) { mtu[device.address] = m }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sending[device.address] = false
            pump(device.address)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (descriptor.uuid == CCCD) update(device.address) { it.copy(subscribed = value.isNotEmpty() && value[0].toInt() and 1 == 1) }
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
