package com.coralcoder.iwatchbridge.protocol.l2cap

import com.coralcoder.iwatchbridge.protocol.util.bytes
import com.coralcoder.iwatchbridge.protocol.util.le16

/**
 * Enhanced Retransmission Mode (Core v5.4, Vol 3 Part A §3.3.2 / §8), standard 16-bit control
 * field. The watch's link-layer services (com.apple.terminusLink etc.) run over ERTM channels.
 */
object Ertm {
    const val SAR_UNSEGMENTED = 0
    const val SAR_START = 1
    const val SAR_END = 2
    const val SAR_CONTINUATION = 3

    const val S_RR = 0
    const val S_REJ = 1
    const val S_RNR = 2
    const val S_SREJ = 3

    sealed class Control {
        abstract val reqSeq: Int
        abstract val f: Boolean
        abstract fun encode(): Int

        data class I(val txSeq: Int, override val reqSeq: Int, val sar: Int, override val f: Boolean = false) : Control() {
            override fun encode() = (txSeq and 0x3f shl 1) or (if (f) 1 shl 7 else 0) or (reqSeq and 0x3f shl 8) or (sar shl 14)
        }
        data class S(val function: Int, override val reqSeq: Int, val p: Boolean = false, override val f: Boolean = false) : Control() {
            override fun encode() = 0x01 or (function shl 2) or (if (p) 1 shl 4 else 0) or (if (f) 1 shl 7 else 0) or (reqSeq and 0x3f shl 8)
        }

        companion object {
            fun decode(v: Int): Control = if (v and 1 == 0) {
                I((v ushr 1) and 0x3f, (v ushr 8) and 0x3f, (v ushr 14) and 3, v and 0x80 != 0)
            } else {
                S((v ushr 2) and 3, (v ushr 8) and 0x3f, v and 0x10 != 0, v and 0x80 != 0)
            }
        }
    }

    /** CRC-16 (x^16 + x^15 + x^2 + 1), LSB first, initial value 0; over header + control + payload. */
    fun fcs(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** Build a complete L2CAP PDU (basic header + control + [SDU length] + payload + FCS). */
    fun encodePdu(cid: Int, control: Control, payload: ByteArray = ByteArray(0), sduLength: Int? = null, withFcs: Boolean = true): ByteArray {
        val body = bytes {
            le16(control.encode())
            if (sduLength != null) le16(sduLength)
            bytes(payload)
        }
        val len = body.size + if (withFcs) 2 else 0
        val noFcs = bytes { le16(len); le16(cid); bytes(body) }
        return if (withFcs) noFcs + bytes { le16(fcs(noFcs)) } else noFcs
    }

    data class Pdu(val cid: Int, val control: Control, val sduLength: Int?, val payload: ByteArray)

    /** Parse an ERTM PDU; returns null if the FCS doesn't match (the spec says: drop it silently). */
    fun decodePdu(pdu: ByteArray, withFcs: Boolean = true): Pdu? {
        val len = pdu.le16(0); val cid = pdu.le16(2)
        val end = 4 + len
        if (pdu.size < end || len < 2) return null
        val payloadEnd = if (withFcs) end - 2 else end
        if (withFcs && fcs(pdu, 0, payloadEnd) != pdu.le16(payloadEnd)) return null
        val control = Control.decode(pdu.le16(4))
        var o = 6
        val sdu = if (control is Control.I && control.sar == SAR_START) pdu.le16(o).also { o += 2 } else null
        return Pdu(cid, control, sdu, pdu.copyOfRange(o, payloadEnd))
    }
}

/**
 * One ERTM channel's state: sequence numbers, transmit window, acknowledgements, retransmission
 * and SDU reassembly. Pure logic (no timers or I/O); the owner calls [onTimeout] for the
 * retransmission/monitor timers and sends whatever PDUs come back.
 */
class ErtmChannel(
    val localCid: Int,
    val remoteCid: Int,
    private val remoteTxWindow: Int,
    private val remoteMps: Int,
    private val maxTransmit: Int = 0, // 0 = unlimited
    private val fcs: Boolean = true,
) {
    private var nextTxSeq = 0
    private var expectedAckSeq = 0
    private var expectedTxSeq = 0
    private var bufferSeq = 0
    private val unacked = ArrayDeque<Pair<Int, ByteArray>>() // txSeq -> I-frame info payload (with SDU length if any)
    private val unackedMeta = HashMap<Int, Pair<Int, Int?>>()  // txSeq -> (sar, sduLength)
    private val retries = HashMap<Int, Int>()
    private val pending = ArrayDeque<Triple<Int, Int?, ByteArray>>() // (sar, sduLen, data) not yet sent (window full)
    private var sdu: java.io.ByteArrayOutputStream? = null
    private var sduExpected = 0
    var remoteBusy = false
        private set

    val outstanding get() = unacked.size
    private fun mod(v: Int) = v and 0x3f

    /** Segment an SDU and return the PDUs to send now (more go out as acknowledgements arrive). */
    fun send(sduData: ByteArray): List<ByteArray> {
        if (sduData.size <= remoteMps) {
            pending += Triple(Ertm.SAR_UNSEGMENTED, null, sduData)
        } else {
            var o = 0
            while (o < sduData.size) {
                val first = o == 0
                val room = if (first) remoteMps - 2 else remoteMps
                val n = minOf(room, sduData.size - o)
                val sar = when {
                    first -> Ertm.SAR_START
                    o + n == sduData.size -> Ertm.SAR_END
                    else -> Ertm.SAR_CONTINUATION
                }
                pending += Triple(sar, if (first) sduData.size else null, sduData.copyOfRange(o, o + n))
                o += n
            }
        }
        return flush()
    }

    private fun flush(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        while (pending.isNotEmpty() && !remoteBusy && unacked.size < remoteTxWindow) {
            val (sar, sduLen, data) = pending.removeFirst()
            val seq = nextTxSeq
            nextTxSeq = mod(nextTxSeq + 1)
            unacked += seq to data
            unackedMeta[seq] = sar to sduLen
            retries[seq] = 1
            out += Ertm.encodePdu(remoteCid, Ertm.Control.I(seq, expectedTxSeq, sar), data, sduLen, fcs)
        }
        return out
    }

    private fun acknowledge(reqSeq: Int) {
        while (unacked.isNotEmpty() && unacked.first().first != reqSeq) {
            val (seq, _) = unacked.removeFirst()
            unackedMeta.remove(seq); retries.remove(seq)
        }
        expectedAckSeq = reqSeq
    }

    private fun retransmitFrom(seq: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var started = false
        for ((s, data) in unacked) {
            if (s == seq) started = true
            if (!started) continue
            val count = (retries[s] ?: 1) + 1
            if (maxTransmit != 0 && count > maxTransmit) throw IllegalStateException("ERTM MaxTransmit exceeded on cid $localCid")
            retries[s] = count
            val (sar, sduLen) = unackedMeta[s]!!
            out += Ertm.encodePdu(remoteCid, Ertm.Control.I(s, expectedTxSeq, sar), data, sduLen, fcs)
        }
        return out
    }

    class Received(val sdus: List<ByteArray>, val toSend: List<ByteArray>)

    /** Handle an incoming PDU on this channel. */
    fun receive(pdu: ByteArray): Received {
        val p = Ertm.decodePdu(pdu, fcs) ?: return Received(emptyList(), emptyList())
        val toSend = mutableListOf<ByteArray>()
        val sdus = mutableListOf<ByteArray>()
        when (val c = p.control) {
            is Ertm.Control.I -> {
                acknowledge(c.reqSeq)
                if (c.txSeq == expectedTxSeq) {
                    expectedTxSeq = mod(expectedTxSeq + 1)
                    reassemble(c.sar, p.sduLength, p.payload)?.let { sdus += it }
                    toSend += flush()
                    // Acknowledge (piggybacked if we just sent I-frames, else an explicit RR).
                    if (toSend.isEmpty()) toSend += Ertm.encodePdu(remoteCid, Ertm.Control.S(Ertm.S_RR, expectedTxSeq), withFcs = fcs)
                } else {
                    // Out of sequence: ask for a retransmission from what we expected.
                    toSend += Ertm.encodePdu(remoteCid, Ertm.Control.S(Ertm.S_REJ, expectedTxSeq), withFcs = fcs)
                }
            }
            is Ertm.Control.S -> {
                acknowledge(c.reqSeq)
                when (c.function) {
                    Ertm.S_RR -> { remoteBusy = false; if (c.p) toSend += Ertm.encodePdu(remoteCid, Ertm.Control.S(Ertm.S_RR, expectedTxSeq, f = true), withFcs = fcs) }
                    Ertm.S_RNR -> remoteBusy = true
                    Ertm.S_REJ -> { remoteBusy = false; toSend += retransmitFrom(c.reqSeq) }
                    Ertm.S_SREJ -> {
                        val data = unacked.firstOrNull { it.first == c.reqSeq }
                        if (data != null) {
                            val (sar, sduLen) = unackedMeta[c.reqSeq]!!
                            toSend += Ertm.encodePdu(remoteCid, Ertm.Control.I(c.reqSeq, expectedTxSeq, sar, f = c.p), data.second, sduLen, fcs)
                        }
                    }
                }
                toSend += flush()
            }
        }
        return Received(sdus, toSend)
    }

    /** Retransmission timer expired: poll the peer (RR with P=1), per the spec's WAIT_F behaviour. */
    fun onTimeout(): List<ByteArray> =
        if (unacked.isEmpty()) emptyList()
        else listOf(Ertm.encodePdu(remoteCid, Ertm.Control.S(Ertm.S_RR, expectedTxSeq, p = true), withFcs = fcs))

    private fun reassemble(sar: Int, sduLength: Int?, data: ByteArray): ByteArray? = when (sar) {
        Ertm.SAR_UNSEGMENTED -> data
        Ertm.SAR_START -> { sdu = java.io.ByteArrayOutputStream(sduLength ?: 0).apply { write(data) }; sduExpected = sduLength ?: 0; null }
        Ertm.SAR_CONTINUATION -> { sdu?.write(data); null }
        else -> sdu?.let { it.write(data); sdu = null; it.toByteArray().takeIf { b -> sduExpected == 0 || b.size == sduExpected } }
    }
}
