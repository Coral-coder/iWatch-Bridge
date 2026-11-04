package com.coralcoder.iwatchbridge.protocol.l2cap

import com.coralcoder.iwatchbridge.protocol.util.bytes
import com.coralcoder.iwatchbridge.protocol.util.le16
import com.coralcoder.iwatchbridge.protocol.util.u8

/** L2CAP basic frames and BR/EDR signaling (Bluetooth Core v5.4, Vol 3 Part A). */
object L2cap {
    const val CID_SIGNALING = 0x0001
    const val CID_CONNECTIONLESS = 0x0002
    const val CID_LE_ATT = 0x0004
    const val CID_LE_SIGNALING = 0x0005
    const val CID_LE_SMP = 0x0006
    const val CID_BREDR_SMP = 0x0007
    /** Apple's fixed channel for BT_CL / "Magnet" link signaling. */
    const val CID_APPLE_MAGNET = 0x003A
    const val FIRST_DYNAMIC_CID = 0x0040

    data class Frame(val cid: Int, val payload: ByteArray) {
        fun encode() = bytes { le16(payload.size); le16(cid); bytes(payload) }
        companion object {
            fun decode(pdu: ByteArray) = Frame(pdu.le16(2), pdu.copyOfRange(4, 4 + pdu.le16(0)))
        }
    }

    // Extended features mask bits (Information Response type 0x0002)
    const val FEAT_ERTM = 1 shl 3
    const val FEAT_STREAMING = 1 shl 4
    const val FEAT_FCS = 1 shl 5
    const val FEAT_FIXED_CHANNELS = 1 shl 7
    const val FEAT_UNICAST_CONNECTIONLESS = 1 shl 9

    /** What we tell the watch we support: ERTM, FCS, and fixed channels (signaling, connectionless, SMP, Apple 0x3A). */
    const val OUR_FEATURES = FEAT_ERTM or FEAT_FCS or FEAT_FIXED_CHANNELS
    val OUR_FIXED_CHANNELS: Long = (1L shl CID_SIGNALING) or (1L shl CID_CONNECTIONLESS) or
        (1L shl CID_BREDR_SMP) or (1L shl CID_APPLE_MAGNET)
}

/** Signaling commands on CID 0x0001. Several commands may share one frame. */
sealed class Signal {
    abstract val id: Int
    abstract val code: Int
    protected abstract fun data(): ByteArray
    fun encode(): ByteArray { val d = data(); return bytes { u8(code); u8(id); le16(d.size); bytes(d) } }

    data class CommandReject(override val id: Int, val reason: Int, val extra: ByteArray = ByteArray(0)) : Signal() {
        override val code = 0x01
        override fun data() = bytes { le16(reason); bytes(extra) }
    }
    data class ConnectionRequest(override val id: Int, val psm: Int, val sourceCid: Int) : Signal() {
        override val code = 0x02
        override fun data() = bytes { le16(psm); le16(sourceCid) }
    }
    data class ConnectionResponse(override val id: Int, val destCid: Int, val sourceCid: Int, val result: Int, val status: Int = 0) : Signal() {
        override val code = 0x03
        override fun data() = bytes { le16(destCid); le16(sourceCid); le16(result); le16(status) }
    }
    data class ConfigureRequest(override val id: Int, val destCid: Int, val flags: Int, val options: List<ConfigOption>) : Signal() {
        override val code = 0x04
        override fun data() = bytes { le16(destCid); le16(flags); options.forEach { bytes(it.encode()) } }
    }
    data class ConfigureResponse(override val id: Int, val sourceCid: Int, val flags: Int, val result: Int, val options: List<ConfigOption>) : Signal() {
        override val code = 0x05
        override fun data() = bytes { le16(sourceCid); le16(flags); le16(result); options.forEach { bytes(it.encode()) } }
    }
    data class DisconnectionRequest(override val id: Int, val destCid: Int, val sourceCid: Int) : Signal() {
        override val code = 0x06
        override fun data() = bytes { le16(destCid); le16(sourceCid) }
    }
    data class DisconnectionResponse(override val id: Int, val destCid: Int, val sourceCid: Int) : Signal() {
        override val code = 0x07
        override fun data() = bytes { le16(destCid); le16(sourceCid) }
    }
    data class EchoRequest(override val id: Int, val payload: ByteArray) : Signal() {
        override val code = 0x08
        override fun data() = payload
    }
    data class EchoResponse(override val id: Int, val payload: ByteArray) : Signal() {
        override val code = 0x09
        override fun data() = payload
    }
    data class InformationRequest(override val id: Int, val infoType: Int) : Signal() {
        override val code = 0x0A
        override fun data() = bytes { le16(infoType) }
    }
    data class InformationResponse(override val id: Int, val infoType: Int, val result: Int, val payload: ByteArray) : Signal() {
        override val code = 0x0B
        override fun data() = bytes { le16(infoType); le16(result); bytes(payload) }
    }
    data class Unknown(override val id: Int, override val code: Int, val raw: ByteArray) : Signal() {
        override fun data() = raw
    }

    companion object {
        const val INFO_CONNECTIONLESS_MTU = 0x0001
        const val INFO_EXTENDED_FEATURES = 0x0002
        const val INFO_FIXED_CHANNELS = 0x0003

        const val CONN_SUCCESS = 0x0000
        const val CONN_PENDING = 0x0001
        const val CONN_PSM_NOT_SUPPORTED = 0x0002

        fun decodeAll(payload: ByteArray): List<Signal> {
            val out = mutableListOf<Signal>()
            var o = 0
            while (o + 4 <= payload.size) {
                val code = payload.u8(o); val id = payload.u8(o + 1); val len = payload.le16(o + 2)
                val d = payload.copyOfRange(o + 4, minOf(payload.size, o + 4 + len))
                out += when (code) {
                    0x01 -> CommandReject(id, d.le16(0), d.copyOfRange(2, d.size))
                    0x02 -> ConnectionRequest(id, d.le16(0), d.le16(2))
                    0x03 -> ConnectionResponse(id, d.le16(0), d.le16(2), d.le16(4), d.le16(6))
                    0x04 -> ConfigureRequest(id, d.le16(0), d.le16(2), ConfigOption.decodeAll(d, 4))
                    0x05 -> ConfigureResponse(id, d.le16(0), d.le16(2), d.le16(4), ConfigOption.decodeAll(d, 6))
                    0x06 -> DisconnectionRequest(id, d.le16(0), d.le16(2))
                    0x07 -> DisconnectionResponse(id, d.le16(0), d.le16(2))
                    0x08 -> EchoRequest(id, d)
                    0x09 -> EchoResponse(id, d)
                    0x0A -> InformationRequest(id, d.le16(0))
                    0x0B -> InformationResponse(id, d.le16(0), d.le16(2), d.copyOfRange(4, d.size))
                    else -> Unknown(id, code, d)
                }
                o += 4 + len
            }
            return out
        }

        /** Our answer to an Information Request. */
        fun informationResponseFor(req: InformationRequest): InformationResponse = when (req.infoType) {
            INFO_CONNECTIONLESS_MTU -> InformationResponse(req.id, req.infoType, 0, bytes { le16(672) })
            INFO_EXTENDED_FEATURES -> InformationResponse(req.id, req.infoType, 0, bytes { le32(L2cap.OUR_FEATURES.toLong()) })
            INFO_FIXED_CHANNELS -> InformationResponse(req.id, req.infoType, 0, bytes { le64(L2cap.OUR_FIXED_CHANNELS) })
            else -> InformationResponse(req.id, req.infoType, 1 /* not supported */, ByteArray(0))
        }
    }
}

sealed class ConfigOption {
    abstract fun encode(): ByteArray

    data class Mtu(val mtu: Int) : ConfigOption() {
        override fun encode() = bytes { u8(0x01); u8(2); le16(mtu) }
    }
    data class FlushTimeout(val timeout: Int) : ConfigOption() {
        override fun encode() = bytes { u8(0x02); u8(2); le16(timeout) }
    }
    /** Retransmission and flow control (mode 0x03 = ERTM, 0x04 = streaming). */
    data class RetransmissionFlowControl(
        val mode: Int, val txWindow: Int, val maxTransmit: Int,
        val retransmissionTimeoutMs: Int, val monitorTimeoutMs: Int, val mps: Int,
    ) : ConfigOption() {
        override fun encode() = bytes {
            u8(0x04); u8(9); u8(mode); u8(txWindow); u8(maxTransmit)
            le16(retransmissionTimeoutMs); le16(monitorTimeoutMs); le16(mps)
        }
        companion object {
            const val MODE_BASIC = 0x00
            const val MODE_ERTM = 0x03
            const val MODE_STREAMING = 0x04
        }
    }
    data class Fcs(val type: Int) : ConfigOption() { // 0 = none, 1 = 16-bit
        override fun encode() = bytes { u8(0x05); u8(1); u8(type) }
    }
    data class Unknown(val type: Int, val value: ByteArray) : ConfigOption() {
        val hint get() = type and 0x80 != 0
        override fun encode() = bytes { u8(type); u8(value.size); bytes(value) }
    }

    companion object {
        fun decodeAll(b: ByteArray, start: Int): List<ConfigOption> {
            val out = mutableListOf<ConfigOption>()
            var o = start
            while (o + 2 <= b.size) {
                val t = b.u8(o); val len = b.u8(o + 1); val v = b.copyOfRange(o + 2, minOf(b.size, o + 2 + len))
                out += when (t and 0x7f) {
                    0x01 -> Mtu(v.le16(0))
                    0x02 -> FlushTimeout(v.le16(0))
                    0x04 -> RetransmissionFlowControl(v.u8(0), v.u8(1), v.u8(2), v.le16(3), v.le16(5), v.le16(7))
                    0x05 -> Fcs(v.u8(0))
                    else -> Unknown(t, v)
                }
                o += 2 + len
            }
            return out
        }
    }
}
