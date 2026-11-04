package com.coralcoder.iwatchbridge.core

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import com.coralcoder.iwatchbridge.link.RootHalTransport
import com.coralcoder.iwatchbridge.link.UsbHciTransport
import com.coralcoder.iwatchbridge.protocol.host.HciTransport
import com.coralcoder.iwatchbridge.protocol.host.HostStack
import com.coralcoder.iwatchbridge.protocol.host.PhoneIdentity
import com.coralcoder.iwatchbridge.protocol.host.SetupAdvertisement
import com.coralcoder.iwatchbridge.protocol.pairing.PairingEvent
import com.coralcoder.iwatchbridge.protocol.pairing.PairingProof
import com.coralcoder.iwatchbridge.protocol.pairing.PairingSession
import com.coralcoder.iwatchbridge.protocol.registry.RegistryStore
import com.coralcoder.iwatchbridge.protocol.registry.WatchRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-wide state: the paired watches, the Bluetooth controller in use, and any setup in
 * progress. Screens observe the StateFlows; BridgeService keeps it alive in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeController(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val registry = WatchRegistry(object : RegistryStore {
        private val file = File(context.filesDir, "watches.json")
        override fun load() = file.takeIf { it.exists() }?.readText()
        override fun save(json: String) {
            val tmp = File(file.path + ".tmp"); tmp.writeText(json); tmp.renameTo(file)
        }
    })

    val settings = WatchSettings(context)

    /** Shared-with-iPhone mode: the watch app's Bluetooth LE link to this phone. */
    val shared = com.coralcoder.iwatchbridge.shared.SharedLinkServer(context, scope)

    enum class Radio(val label: String) {
        USB("USB Bluetooth adapter"),
        ROOT("Built-in Bluetooth (root)"),
    }

    data class RadioStatus(
        val radio: Radio? = null,
        val state: HostStack.State = HostStack.State.OFF,
        val detail: String = "",
        val error: String? = null,
    )

    private val _radio = MutableStateFlow(RadioStatus())
    val radio: StateFlow<RadioStatus> = _radio.asStateFlow()

    private val _host = MutableStateFlow<HostStack?>(null)
    val host: StateFlow<HostStack?> = _host.asStateFlow()

    /** Watches in setup mode nearby. */
    val setupWatches: StateFlow<List<SetupAdvertisement>> = _host
        .flatMapLatest { it?.setupWatches ?: flowOf(emptyMap()) }
        .map { m -> m.values.sortedByDescending { it.rssi } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Last 300 lines of the Bluetooth trace, for the diagnostics screen. */
    val log: StateFlow<List<String>> = _host
        .flatMapLatest { it?.log ?: emptyFlow() }
        .runningFold(emptyList<String>()) { acc, line -> (acc + line).takeLast(300) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _pairing = MutableStateFlow<List<PairingEvent>>(emptyList())
    val pairing: StateFlow<List<PairingEvent>> = _pairing.asStateFlow()
    private var session: PairingSession? = null
    private var sessionJob: Job? = null

    val identity: PhoneIdentity
        get() = PhoneIdentity(name = phoneName())

    private fun phoneName(): String {
        val owner = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()
        return owner?.takeIf { it.isNotBlank() } ?: "${Build.MODEL}"
    }

    fun usbAdapters() = UsbHciTransport.adapters(context.getSystemService(UsbManager::class.java))

    /** Bring up the Bluetooth host on the chosen controller. */
    fun startRadio(radio: Radio) {
        scope.launch {
            stopRadioNow()
            val transport: HciTransport = when (radio) {
                Radio.USB -> {
                    val usb = context.getSystemService(UsbManager::class.java)
                    val dev = UsbHciTransport.adapters(usb).firstOrNull()
                    if (dev == null) { _radio.value = RadioStatus(radio, HostStack.State.FAILED, error = "Plug in a USB Bluetooth adapter (USB-C or OTG)."); return@launch }
                    if (!usb.hasPermission(dev)) { _radio.value = RadioStatus(radio, HostStack.State.FAILED, error = "Allow USB access to ${dev.productName ?: "the adapter"}, then try again."); return@launch }
                    UsbHciTransport(usb, dev)
                }
                Radio.ROOT -> RootHalTransport(context)
            }
            val h = HostStack(transport, identity, scope)
            _host.value = h
            _radio.value = RadioStatus(radio, HostStack.State.STARTING)
            try {
                h.start()
                _radio.value = RadioStatus(radio, HostStack.State.READY, detail = "${transport.name} · ${h.localAddress}")
            } catch (e: Exception) {
                _radio.value = RadioStatus(radio, HostStack.State.FAILED, error = e.message ?: e.toString())
            }
        }
    }

    fun stopRadio() { scope.launch { stopRadioNow() } }

    private suspend fun stopRadioNow() {
        _host.value?.let { runCatching { it.stop() } }
        _host.value = null
        _radio.value = RadioStatus()
    }

    fun startDiscovery() { scope.launch { runCatching { _host.value?.discover(true) } } }
    fun stopDiscovery() { scope.launch { runCatching { _host.value?.discover(false) } } }

    /** Begin setting up the watch at [target]. */
    fun beginPairing(target: SetupAdvertisement) {
        val h = _host.value ?: return
        sessionJob?.cancel()
        _pairing.value = emptyList()
        val s = PairingSession(h, target)
        session = s
        sessionJob = scope.launch {
            launch { s.events.collect { e -> _pairing.value = _pairing.value + e } }
            s.run()
        }
    }

    fun providePairingProof(p: PairingProof) { session?.provide(p) }

    fun cancelPairing() {
        sessionJob?.cancel(); sessionJob = null; session = null
        _pairing.value = emptyList()
    }
}
