package com.coralcoder.iwatchbridge.protocol.hci

import com.coralcoder.iwatchbridge.protocol.util.BdAddr
import com.coralcoder.iwatchbridge.protocol.util.bytes
import com.coralcoder.iwatchbridge.protocol.util.le16
import com.coralcoder.iwatchbridge.protocol.util.u8

/**
 * HCI packets (Bluetooth Core v5.4, Vol 4 Part E). iWatch-Bridge runs its own host stack on top
 * of a controller it owns outright: a USB Bluetooth adapter (no root), or the phone's own
 * controller through the Bluetooth HAL (root, see magisk/). Android's stack can't be used
 * because the watch talks on an Apple fixed L2CAP channel (0x003A), wants ERTM channels, and
 * pairs with LE Secure Connections out-of-band data, none of which the public APIs expose.
 */
sealed class HciPacket {
    abstract val indicator: Int
    abstract fun body(): ByteArray
    /** UART (H4) framing: one indicator byte, then the packet. */
    fun h4(): ByteArray = byteArrayOf(indicator.toByte()) + body()

    data class Command(val opcode: Int, val params: ByteArray = ByteArray(0)) : HciPacket() {
        override val indicator = 0x01
        override fun body() = bytes { le16(opcode); u8(params.size); bytes(params) }
        val ogf get() = opcode ushr 10
        val ocf get() = opcode and 0x3ff
        override fun toString() = "Command(%04x, %d bytes)".format(opcode, params.size)
    }

    data class Acl(val handle: Int, val pb: Int, val bc: Int, val data: ByteArray) : HciPacket() {
        override val indicator = 0x02
        override fun body() = bytes { le16(handle or (pb shl 12) or (bc shl 14)); le16(data.size); bytes(data) }
        override fun toString() = "Acl(handle=%03x, pb=%d, %d bytes)".format(handle, pb, data.size)
    }

    data class Event(val code: Int, val params: ByteArray) : HciPacket() {
        override val indicator = 0x04
        override fun body() = bytes { u8(code); u8(params.size); bytes(params) }
        override fun toString() = "Event(%02x, %d bytes)".format(code, params.size)
    }

    companion object {
        const val PB_FIRST_NON_FLUSHABLE = 0
        const val PB_CONTINUING = 1
        const val PB_FIRST_FLUSHABLE = 2

        /** Parse one packet without its H4 indicator (as delivered by the HAL or a USB endpoint). */
        fun parse(indicator: Int, b: ByteArray): HciPacket = when (indicator) {
            0x01 -> Command(b.le16(0), b.copyOfRange(3, 3 + b.u8(2)))
            0x02 -> {
                val hdr = b.le16(0)
                Acl(hdr and 0x0fff, (hdr ushr 12) and 3, (hdr ushr 14) and 3, b.copyOfRange(4, 4 + b.le16(2)))
            }
            0x04 -> Event(b.u8(0), b.copyOfRange(2, 2 + b.u8(1)))
            else -> throw IllegalArgumentException("unsupported HCI packet type $indicator")
        }

        fun parseH4(b: ByteArray) = parse(b.u8(0), b.copyOfRange(1, b.size))
    }
}

/** Opcodes used by the host. Values are (OGF << 10) | OCF. */
object Op {
    // Link control (OGF 0x01)
    const val CREATE_CONNECTION = 0x0405
    const val DISCONNECT = 0x0406
    const val ACCEPT_CONNECTION_REQUEST = 0x0409
    const val REJECT_CONNECTION_REQUEST = 0x040A
    const val LINK_KEY_REQUEST_REPLY = 0x040B
    const val LINK_KEY_REQUEST_NEGATIVE_REPLY = 0x040C
    const val AUTHENTICATION_REQUESTED = 0x0411
    const val SET_CONNECTION_ENCRYPTION = 0x0413
    const val REMOTE_NAME_REQUEST = 0x0419
    const val IO_CAPABILITY_REQUEST_REPLY = 0x042B
    const val USER_CONFIRMATION_REQUEST_REPLY = 0x042C
    const val REMOTE_OOB_EXTENDED_DATA_REQUEST_REPLY = 0x0445
    // Controller & baseband (OGF 0x03)
    const val SET_EVENT_MASK = 0x0C01
    const val RESET = 0x0C03
    const val WRITE_LOCAL_NAME = 0x0C13
    const val WRITE_SCAN_ENABLE = 0x0C1A
    const val WRITE_CLASS_OF_DEVICE = 0x0C24
    const val WRITE_INQUIRY_MODE = 0x0C45
    const val WRITE_EXTENDED_INQUIRY_RESPONSE = 0x0C52
    const val WRITE_SIMPLE_PAIRING_MODE = 0x0C56
    const val READ_LOCAL_OOB_EXTENDED_DATA = 0x0C7D
    const val SET_EVENT_MASK_PAGE_2 = 0x0C63
    const val WRITE_LE_HOST_SUPPORT = 0x0C6D
    const val WRITE_SECURE_CONNECTIONS_HOST_SUPPORT = 0x0C7A
    // Informational (OGF 0x04)
    const val READ_LOCAL_VERSION = 0x1001
    const val READ_LOCAL_SUPPORTED_FEATURES = 0x1003
    const val READ_BUFFER_SIZE = 0x1005
    const val READ_BD_ADDR = 0x1009
    // LE (OGF 0x08)
    const val LE_SET_EVENT_MASK = 0x2001
    const val LE_READ_BUFFER_SIZE = 0x2002
    const val LE_SET_RANDOM_ADDRESS = 0x2005
    const val LE_SET_ADVERTISING_PARAMETERS = 0x2006
    const val LE_SET_ADVERTISING_DATA = 0x2008
    const val LE_SET_ADVERTISING_ENABLE = 0x200A
    const val LE_SET_SCAN_PARAMETERS = 0x200B
    const val LE_SET_SCAN_ENABLE = 0x200C
    const val LE_CREATE_CONNECTION = 0x200D
    const val LE_CREATE_CONNECTION_CANCEL = 0x200E
    const val LE_LONG_TERM_KEY_REQUEST_REPLY = 0x201A
    const val LE_ENABLE_ENCRYPTION = 0x2019
    const val LE_READ_LOCAL_P256_PUBLIC_KEY = 0x2025
    const val LE_GENERATE_DHKEY = 0x2026
}

object Ev {
    const val INQUIRY_COMPLETE = 0x01
    const val CONNECTION_COMPLETE = 0x03
    const val CONNECTION_REQUEST = 0x04
    const val DISCONNECTION_COMPLETE = 0x05
    const val AUTHENTICATION_COMPLETE = 0x06
    const val REMOTE_NAME_REQUEST_COMPLETE = 0x07
    const val ENCRYPTION_CHANGE = 0x08
    const val COMMAND_COMPLETE = 0x0E
    const val COMMAND_STATUS = 0x0F
    const val HARDWARE_ERROR = 0x10
    const val NUMBER_OF_COMPLETED_PACKETS = 0x13
    const val LINK_KEY_REQUEST = 0x17
    const val LINK_KEY_NOTIFICATION = 0x18
    const val IO_CAPABILITY_REQUEST = 0x31
    const val IO_CAPABILITY_RESPONSE = 0x32
    const val USER_CONFIRMATION_REQUEST = 0x33
    const val REMOTE_OOB_DATA_REQUEST = 0x35
    const val SIMPLE_PAIRING_COMPLETE = 0x36
    const val ENCRYPTION_KEY_REFRESH_COMPLETE = 0x30
    const val LE_META = 0x3E

    // LE meta subevents
    const val LE_CONNECTION_COMPLETE = 0x01
    const val LE_ADVERTISING_REPORT = 0x02
    const val LE_LONG_TERM_KEY_REQUEST = 0x05
    const val LE_READ_LOCAL_P256_PUBLIC_KEY_COMPLETE = 0x08
    const val LE_GENERATE_DHKEY_COMPLETE = 0x09
    const val LE_ENHANCED_CONNECTION_COMPLETE = 0x0A
    const val LE_EXTENDED_ADVERTISING_REPORT = 0x0D
}

/** Typed views of the events the host acts on. */
sealed interface HciEvent {
    data class CommandComplete(val numPackets: Int, val opcode: Int, val returnParams: ByteArray) : HciEvent {
        val status get() = if (returnParams.isEmpty()) 0 else returnParams.u8(0)
    }
    data class CommandStatus(val status: Int, val numPackets: Int, val opcode: Int) : HciEvent
    data class ConnectionRequest(val addr: BdAddr, val classOfDevice: Int, val linkType: Int) : HciEvent
    data class ConnectionComplete(val status: Int, val handle: Int, val addr: BdAddr, val linkType: Int, val encrypted: Boolean) : HciEvent
    data class LeConnectionComplete(val status: Int, val handle: Int, val role: Int, val addrType: Int, val addr: BdAddr) : HciEvent
    data class DisconnectionComplete(val status: Int, val handle: Int, val reason: Int) : HciEvent
    data class EncryptionChange(val status: Int, val handle: Int, val enabled: Int) : HciEvent
    data class NumberOfCompletedPackets(val completed: Map<Int, Int>) : HciEvent
    data class AdvertisingReport(val eventType: Int, val addrType: Int, val addr: BdAddr, val rssi: Int, val data: ByteArray) : HciEvent
    data class Other(val event: HciPacket.Event) : HciEvent

    companion object {
        fun decode(e: HciPacket.Event): List<HciEvent> {
            val p = e.params
            return when (e.code) {
                Ev.COMMAND_COMPLETE -> listOf(CommandComplete(p.u8(0), p.le16(1), p.copyOfRange(3, p.size)))
                Ev.COMMAND_STATUS -> listOf(CommandStatus(p.u8(0), p.u8(1), p.le16(2)))
                Ev.CONNECTION_REQUEST -> listOf(ConnectionRequest(BdAddr.fromWire(p, 0), p.u8(6) or (p.u8(7) shl 8) or (p.u8(8) shl 16), p.u8(9)))
                Ev.CONNECTION_COMPLETE -> listOf(ConnectionComplete(p.u8(0), p.le16(1) and 0xfff, BdAddr.fromWire(p, 3), p.u8(9), p.u8(10) != 0))
                Ev.DISCONNECTION_COMPLETE -> listOf(DisconnectionComplete(p.u8(0), p.le16(1) and 0xfff, p.u8(3)))
                Ev.ENCRYPTION_CHANGE -> listOf(EncryptionChange(p.u8(0), p.le16(1) and 0xfff, p.u8(3)))
                Ev.NUMBER_OF_COMPLETED_PACKETS -> {
                    val n = p.u8(0)
                    listOf(NumberOfCompletedPackets((0 until n).associate { p.le16(1 + it * 4) and 0xfff to p.le16(3 + it * 4) }))
                }
                Ev.LE_META -> decodeLeMeta(e)
                else -> listOf(Other(e))
            }
        }

        private fun decodeLeMeta(e: HciPacket.Event): List<HciEvent> {
            val p = e.params
            return when (p.u8(0)) {
                Ev.LE_CONNECTION_COMPLETE, Ev.LE_ENHANCED_CONNECTION_COMPLETE ->
                    listOf(LeConnectionComplete(p.u8(1), p.le16(2) and 0xfff, p.u8(4), p.u8(5), BdAddr.fromWire(p, 6)))
                Ev.LE_ADVERTISING_REPORT -> {
                    // Legacy reports, one by one (controllers send one per event in practice).
                    val out = mutableListOf<HciEvent>()
                    var o = 2
                    repeat(p.u8(1)) {
                        val type = p.u8(o); val addrType = p.u8(o + 1); val addr = BdAddr.fromWire(p, o + 2)
                        val len = p.u8(o + 8); val data = p.copyOfRange(o + 9, o + 9 + len)
                        val rssi = p[o + 9 + len].toInt()
                        out += AdvertisingReport(type, addrType, addr, rssi, data)
                        o += 10 + len
                    }
                    out
                }
                Ev.LE_EXTENDED_ADVERTISING_REPORT -> {
                    val out = mutableListOf<HciEvent>()
                    var o = 2
                    repeat(p.u8(1)) {
                        val type = p.le16(o); val addrType = p.u8(o + 2); val addr = BdAddr.fromWire(p, o + 3)
                        val rssi = p[o + 13].toInt()
                        val len = p.u8(o + 23); val data = p.copyOfRange(o + 24, o + 24 + len)
                        out += AdvertisingReport(type, addrType, addr, rssi, data)
                        o += 24 + len
                    }
                    out
                }
                else -> listOf(Other(e))
            }
        }
    }
}

/** Command builders. */
object Cmd {
    fun reset() = HciPacket.Command(Op.RESET)
    fun readBdAddr() = HciPacket.Command(Op.READ_BD_ADDR)
    fun readBufferSize() = HciPacket.Command(Op.READ_BUFFER_SIZE)
    fun leReadBufferSize() = HciPacket.Command(Op.LE_READ_BUFFER_SIZE)
    fun readLocalVersion() = HciPacket.Command(Op.READ_LOCAL_VERSION)
    fun setEventMask(mask: Long = 0x3DBFF807FFFBFFFFL) = HciPacket.Command(Op.SET_EVENT_MASK, bytes { le64(mask) })
    fun setEventMaskPage2(mask: Long = 0L) = HciPacket.Command(Op.SET_EVENT_MASK_PAGE_2, bytes { le64(mask) })
    fun leSetEventMask(mask: Long = 0x00000000000FFFFFL) = HciPacket.Command(Op.LE_SET_EVENT_MASK, bytes { le64(mask) })
    fun writeSimplePairingMode(on: Boolean = true) = HciPacket.Command(Op.WRITE_SIMPLE_PAIRING_MODE, byteArrayOf(if (on) 1 else 0))
    fun writeSecureConnectionsHostSupport(on: Boolean = true) = HciPacket.Command(Op.WRITE_SECURE_CONNECTIONS_HOST_SUPPORT, byteArrayOf(if (on) 1 else 0))
    fun writeLeHostSupport(on: Boolean = true) = HciPacket.Command(Op.WRITE_LE_HOST_SUPPORT, byteArrayOf(if (on) 1 else 0, 0))
    fun writeClassOfDevice(cod: Int) = HciPacket.Command(Op.WRITE_CLASS_OF_DEVICE, byteArrayOf(cod.toByte(), (cod ushr 8).toByte(), (cod ushr 16).toByte()))
    fun writeScanEnable(inquiry: Boolean, page: Boolean) =
        HciPacket.Command(Op.WRITE_SCAN_ENABLE, byteArrayOf(((if (inquiry) 1 else 0) or (if (page) 2 else 0)).toByte()))
    fun writeLocalName(name: String): HciPacket.Command {
        val n = name.toByteArray(Charsets.UTF_8).copyOf(248) // zero-padded to 248 bytes
        return HciPacket.Command(Op.WRITE_LOCAL_NAME, n)
    }
    fun writeInquiryMode(mode: Int = 2) = HciPacket.Command(Op.WRITE_INQUIRY_MODE, byteArrayOf(mode.toByte()))
    fun writeExtendedInquiryResponse(eir: ByteArray, fec: Boolean = false) =
        HciPacket.Command(Op.WRITE_EXTENDED_INQUIRY_RESPONSE, byteArrayOf(if (fec) 1 else 0) + eir.copyOf(240))
    fun createConnection(addr: BdAddr, packetTypes: Int = 0xCC18, allowRoleSwitch: Boolean = true) =
        HciPacket.Command(Op.CREATE_CONNECTION, bytes {
            bytes(addr.wire()); le16(packetTypes); u8(0x01) /* R1 */; u8(0); le16(0); u8(if (allowRoleSwitch) 1 else 0)
        })
    fun acceptConnection(addr: BdAddr, becomeCentral: Boolean = false) =
        HciPacket.Command(Op.ACCEPT_CONNECTION_REQUEST, addr.wire() + byteArrayOf(if (becomeCentral) 0 else 1))
    fun disconnect(handle: Int, reason: Int = 0x13) = HciPacket.Command(Op.DISCONNECT, bytes { le16(handle); u8(reason) })
    fun leSetScanParameters(active: Boolean = false, intervalUnits: Int = 0x0060, windowUnits: Int = 0x0030) =
        HciPacket.Command(Op.LE_SET_SCAN_PARAMETERS, bytes { u8(if (active) 1 else 0); le16(intervalUnits); le16(windowUnits); u8(0); u8(0) })
    fun leSetScanEnable(on: Boolean, filterDuplicates: Boolean = false) =
        HciPacket.Command(Op.LE_SET_SCAN_ENABLE, byteArrayOf(if (on) 1 else 0, if (filterDuplicates) 1 else 0))
    fun leCreateConnection(peer: BdAddr, peerAddrType: Int) = HciPacket.Command(Op.LE_CREATE_CONNECTION, bytes {
        le16(0x0060); le16(0x0030); u8(0) /* no filter accept list */; u8(peerAddrType); bytes(peer.wire()); u8(0) /* own public */
        le16(0x0018); le16(0x0028); le16(0); le16(0x01F4); le16(0); le16(0)
    })
}
