package com.coralcoder.iwatchbridge.protocol.host

import com.coralcoder.iwatchbridge.protocol.hci.AclReassembler
import com.coralcoder.iwatchbridge.protocol.hci.Cmd
import com.coralcoder.iwatchbridge.protocol.hci.HciEvent
import com.coralcoder.iwatchbridge.protocol.hci.HciPacket
import com.coralcoder.iwatchbridge.protocol.l2cap.L2cap
import com.coralcoder.iwatchbridge.protocol.l2cap.Signal
import com.coralcoder.iwatchbridge.protocol.magnet.MagnetMessage
import com.coralcoder.iwatchbridge.protocol.magnet.MagnetSession
import com.coralcoder.iwatchbridge.protocol.util.BdAddr
import com.coralcoder.iwatchbridge.protocol.util.le16
import com.coralcoder.iwatchbridge.protocol.util.u8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * iWatch-Bridge's Bluetooth host: controller setup, discovery of watches in setup mode, ACL
 * connections with flow control, the L2CAP signaling responder and the Magnet link on the Apple
 * fixed channel. Higher layers (pairing, NetworkRelay, IDS) sit on [AclLink].
 */
class HostStack(
    private val transport: HciTransport,
    private val identity: PhoneIdentity,
    private val scope: CoroutineScope,
) {
    enum class State { OFF, STARTING, READY, FAILED }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 200)
    /** Human-readable trace for the Bridge diagnostics screen. */
    val log: SharedFlow<String> = _log.asSharedFlow()

    private val _setupWatches = MutableStateFlow<Map<String, SetupAdvertisement>>(emptyMap())
    /** Watches currently showing "Bring iPhone near Apple Watch", keyed by address. */
    val setupWatches: StateFlow<Map<String, SetupAdvertisement>> = _setupWatches.asStateFlow()

    private val _links = MutableStateFlow<Map<Int, AclLink>>(emptyMap())
    val links: StateFlow<Map<Int, AclLink>> = _links.asStateFlow()

    var localAddress: BdAddr? = null
        private set
    var controllerInfo: String = ""
        private set

    private val commandMutex = Mutex()
    private var commandCredits = Semaphore(1)
    private var pendingCommand: Pair<Int, CompletableDeferred<HciEvent>>? = null
    private var aclCredits = Semaphore(1)
    private var aclMtu = 27
    private val reassembler = AclReassembler()
    private val pendingConnections = HashMap<String, CompletableDeferred<AclLink>>()
    private var reader: Job? = null

    private fun log(msg: String) { _log.tryEmit(msg) }

    suspend fun start() {
        _state.value = State.STARTING
        try {
            transport.open()
            reader = scope.launch { transport.incoming.collect { onPacket(it) } }
            command(Cmd.reset())
            val version = command(Cmd.readLocalVersion()).returnParams
            controllerInfo = if (version.size >= 9) "HCI %d.%d, manufacturer 0x%04x".format(version.u8(1), version.le16(2), version.le16(5)) else ""
            val addr = command(Cmd.readBdAddr()).returnParams
            localAddress = BdAddr.fromWire(addr, 1)
            val buf = command(Cmd.readBufferSize()).returnParams
            aclMtu = buf.le16(1)
            val aclCount = buf.le16(4)
            aclCredits = Semaphore(maxOf(1, aclCount))
            log("Controller ${transport.name}: $localAddress, $controllerInfo, ACL ${aclMtu}x$aclCount")

            command(Cmd.setEventMask())
            command(Cmd.writeSimplePairingMode(true))
            command(Cmd.writeLeHostSupport(true))
            runCatching { command(Cmd.writeSecureConnectionsHostSupport(true)) }
            command(Cmd.leSetEventMask())
            // Present as an iPhone.
            command(Cmd.writeClassOfDevice(identity.classOfDevice))
            command(Cmd.writeLocalName(identity.name))
            runCatching { command(Cmd.writeInquiryMode(2)) }
            runCatching { command(Cmd.writeExtendedInquiryResponse(identity.eir())) }
            command(Cmd.writeScanEnable(inquiry = false, page = true))
            _state.value = State.READY
            log("Ready as \"${identity.name}\"")
        } catch (e: Exception) {
            _state.value = State.FAILED
            log("Start failed: ${e.message}")
            throw e
        }
    }

    suspend fun stop() {
        runCatching { command(Cmd.leSetScanEnable(false)) }
        reader?.cancel()
        transport.close()
        _links.value = emptyMap()
        _state.value = State.OFF
    }

    /** Send a command and wait for its Command Complete (or failing Command Status). */
    suspend fun command(cmd: HciPacket.Command, timeoutMs: Long = 5000): HciEvent.CommandComplete = commandMutex.withLock {
        val done = CompletableDeferred<HciEvent>()
        commandCredits.acquire()
        pendingCommand = cmd.opcode to done
        transport.send(cmd)
        val ev = try {
            withTimeout(timeoutMs) { done.await() }
        } catch (t: Throwable) {
            pendingCommand = null
            runCatching { commandCredits.release() } // the controller won't return this credit now
            throw t
        }
        when (ev) {
            is HciEvent.CommandComplete -> {
                if (ev.status != 0) throw HciException(cmd.opcode, ev.status)
                ev
            }
            is HciEvent.CommandStatus -> {
                if (ev.status != 0) throw HciException(cmd.opcode, ev.status)
                HciEvent.CommandComplete(ev.numPackets, ev.opcode, byteArrayOf(0))
            }
            else -> error("unexpected $ev")
        }
    }

    /** Start (or stop) listening for watches in setup mode. */
    suspend fun discover(on: Boolean) {
        if (on) {
            command(Cmd.leSetScanParameters(active = true))
            command(Cmd.leSetScanEnable(true, filterDuplicates = false))
        } else {
            command(Cmd.leSetScanEnable(false))
            _setupWatches.value = emptyMap()
        }
    }

    /** Open a BR/EDR ACL connection to a watch (or return the existing one). */
    suspend fun connect(addr: BdAddr, timeoutMs: Long = 20_000): AclLink {
        _links.value.values.firstOrNull { it.address.toString() == addr.toString() }?.let { return it }
        val waiter = CompletableDeferred<AclLink>()
        pendingConnections[addr.toString()] = waiter
        command(Cmd.createConnection(addr))
        return withTimeout(timeoutMs) { waiter.await() }
    }

    internal suspend fun sendL2cap(link: AclLink, pdu: ByteArray) {
        for (frag in AclReassembler.fragment(link.handle, pdu, aclMtu)) {
            aclCredits.acquire()
            transport.send(frag)
        }
    }

    private suspend fun onPacket(p: HciPacket) {
        when (p) {
            is HciPacket.Event -> HciEvent.decode(p).forEach { onEvent(it) }
            is HciPacket.Acl -> reassembler.accept(p)?.let { pdu -> _links.value[p.handle]?.onPdu(L2cap.Frame.decode(pdu)) }
            else -> {}
        }
    }

    private suspend fun onEvent(e: HciEvent) {
        when (e) {
            is HciEvent.CommandComplete -> completeCommand(e.opcode, e, e.numPackets)
            is HciEvent.CommandStatus -> if (e.status != 0 || isAsyncCommand(e.opcode)) completeCommand(e.opcode, e, e.numPackets)
            is HciEvent.NumberOfCompletedPackets -> repeat(e.completed.values.sum()) { runCatching { aclCredits.release() } }
            is HciEvent.AdvertisingReport -> SetupAdvertisement.from(e.addr, e.addrType, e.rssi, e.data)?.let { ad ->
                val key = ad.address.toString()
                if (key !in _setupWatches.value) log("Watch in setup mode: $key rssi ${ad.rssi}")
                _setupWatches.value = _setupWatches.value + (key to ad)
            }
            is HciEvent.ConnectionRequest -> {
                log("Incoming connection from ${e.addr} (class %06x)".format(e.classOfDevice))
                scope.launch { runCatching { command(Cmd.acceptConnection(e.addr)) } }
            }
            is HciEvent.ConnectionComplete -> {
                val waiter = pendingConnections.remove(e.addr.toString())
                if (e.status != 0) {
                    log("Connection to ${e.addr} failed: 0x%02x".format(e.status))
                    waiter?.completeExceptionally(HciException(0x0405, e.status))
                } else {
                    val link = AclLink(this, e.handle, e.addr, scope)
                    _links.value = _links.value + (e.handle to link)
                    log("Connected ${e.addr} handle 0x%03x".format(e.handle))
                    waiter?.complete(link)
                    link.onConnected()
                }
            }
            is HciEvent.DisconnectionComplete -> {
                _links.value[e.handle]?.let { log("Disconnected ${it.address} reason 0x%02x".format(e.reason)); it.onDisconnected(e.reason) }
                _links.value = _links.value - e.handle
                reassembler.reset(e.handle)
            }
            is HciEvent.EncryptionChange -> _links.value[e.handle]?.encrypted?.let { it.value = e.enabled != 0 }
            else -> {}
        }
    }

    /** Commands that finish with Command Status and report their outcome in a later event. */
    private fun isAsyncCommand(op: Int) = op ushr 10 == 0x01 || op in ASYNC_LE_COMMANDS

    private fun completeCommand(opcode: Int, e: HciEvent, credits: Int) {
        val pending = pendingCommand
        if (pending != null && pending.first == opcode) {
            pendingCommand = null
            pending.second.complete(e)
        }
        if (credits > 0) runCatching { commandCredits.release() }
    }

    private companion object {
        val ASYNC_LE_COMMANDS = setOf(
            com.coralcoder.iwatchbridge.protocol.hci.Op.LE_CREATE_CONNECTION,
            com.coralcoder.iwatchbridge.protocol.hci.Op.LE_ENABLE_ENCRYPTION,
            com.coralcoder.iwatchbridge.protocol.hci.Op.LE_READ_LOCAL_P256_PUBLIC_KEY,
            com.coralcoder.iwatchbridge.protocol.hci.Op.LE_GENERATE_DHKEY,
        )
    }

    class HciException(val opcode: Int, val status: Int) : Exception("HCI command 0x%04x failed with status 0x%02x".format(opcode, status))
}

/**
 * One ACL connection to a watch: answers L2CAP signaling, runs the Magnet session on CID 0x3A,
 * and hands everything on dynamic channels to registered channel handlers.
 */
class AclLink internal constructor(
    private val host: HostStack,
    val handle: Int,
    val address: BdAddr,
    private val scope: CoroutineScope,
) {
    val encrypted = MutableStateFlow(false)
    val magnet = MagnetSession()
    private var nextSignalId = 1
    private val channelHandlers = HashMap<Int, suspend (ByteArray) -> Unit>()
    val magnetMessages = Channel<MagnetMessage>(Channel.UNLIMITED)
    val disconnected = CompletableDeferred<Int>()

    fun onChannel(cid: Int, handler: suspend (ByteArray) -> Unit) { channelHandlers[cid] = handler }

    internal suspend fun onConnected() {
        // Ask what the watch supports; it will ask us the same and see the Apple fixed channel.
        signal(Signal.InformationRequest(nextId(), Signal.INFO_EXTENDED_FEATURES))
        signal(Signal.InformationRequest(nextId(), Signal.INFO_FIXED_CHANNELS))
    }

    internal fun onDisconnected(reason: Int) { disconnected.complete(reason); magnetMessages.close() }

    internal suspend fun onPdu(f: L2cap.Frame) {
        when (f.cid) {
            L2cap.CID_SIGNALING -> Signal.decodeAll(f.payload).forEach { onSignal(it) }
            L2cap.CID_APPLE_MAGNET -> {
                val m = MagnetMessage.decode(f.payload, magnet.longLength)
                magnet.onMessage(m).forEach { sendMagnet(it) }
                magnetMessages.trySend(m)
            }
            else -> channelHandlers[f.cid]?.invoke(f.payload)
        }
    }

    private suspend fun onSignal(s: Signal) {
        when (s) {
            is Signal.InformationRequest -> signal(Signal.informationResponseFor(s))
            is Signal.EchoRequest -> signal(Signal.EchoResponse(s.id, s.payload))
            is Signal.ConnectionRequest -> {
                // Dynamic channels are opened by the Magnet layer; until a service claims this PSM, refuse it.
                signal(Signal.ConnectionResponse(s.id, 0, s.sourceCid, Signal.CONN_PSM_NOT_SUPPORTED))
            }
            is Signal.DisconnectionRequest -> signal(Signal.DisconnectionResponse(s.id, s.destCid, s.sourceCid))
            else -> {}
        }
    }

    suspend fun sendMagnet(m: MagnetMessage) = send(L2cap.CID_APPLE_MAGNET, m.encode(magnet.longLength))

    suspend fun signal(s: Signal) = send(L2cap.CID_SIGNALING, s.encode())

    suspend fun send(cid: Int, payload: ByteArray) = host.sendL2cap(this, L2cap.Frame(cid, payload).encode())

    private fun nextId(): Int { val id = nextSignalId; nextSignalId = if (nextSignalId == 255) 1 else nextSignalId + 1; return id }
}
