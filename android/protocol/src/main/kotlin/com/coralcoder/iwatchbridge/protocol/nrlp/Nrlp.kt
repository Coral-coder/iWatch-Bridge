package com.coralcoder.iwatchbridge.protocol.nrlp

import com.coralcoder.iwatchbridge.protocol.util.be16
import com.coralcoder.iwatchbridge.protocol.util.bytes
import com.coralcoder.iwatchbridge.protocol.util.u8

/**
 * NRLP ("Network Relay Link Protocol"), carried on the com.apple.terminusLink channels: tunnels
 * IKEv2, ESP and IP between watch and phone.
 *
 * Layout from WatchWitch's dissector (watchwitch-wireshark lua/nrlp.lua, GPL-3.0):
 *   frame  = seq/ack (2 bytes) then records, or "TERMINUS"+data for the hello
 *   record = type (1) | length (2, big-endian) | payload | checksum (2)
 * A record may continue across frames. The checksum algorithm isn't documented yet; [Checksum]
 * makes it pluggable, and received checksums aren't verified.
 */
object Nrlp {
    const val PAD0 = 0x00
    const val PADN = 0x01
    const val UNCOMPRESSED_IP = 0x02
    const val SIXLOWPAN = 0x03
    const val IKEV2 = 0x04
    const val ECHO = 0x05
    const val ESP_CLASS_D = 0x64
    const val ESP_CLASS_D_ECT0 = 0x65
    const val TCP = 0x66
    const val TCP_ECT0 = 0x67
    const val ESP_CLASS_C = 0x68
    const val ESP_CLASS_C_ECT0 = 0x69

    val HELLO_MAGIC = "TERMINUS".toByteArray()

    fun interface Checksum { fun compute(type: Int, payload: ByteArray): Int }

    /** Placeholder until the real algorithm is confirmed against a capture. */
    val ZERO_CHECKSUM = Checksum { _, _ -> 0 }

    data class Header(val echo: Boolean, val seq: Int, val ack: Int, val echoCount: Int = 0) {
        fun encode(): ByteArray =
            if (echo) byteArrayOf(0x01, echoCount.toByte())
            else byteArrayOf(((seq and 0x3f) shl 1).toByte(), (ack and 0x3f).toByte())

        companion object {
            fun decode(b: ByteArray) =
                if (b.u8(0) and 1 == 1) Header(true, 0, 0, b.u8(1))
                else Header(false, (b.u8(0) and 0x7e) ushr 1, b.u8(1) and 0x3f)
        }
    }

    data class Record(val type: Int, val payload: ByteArray)

    fun encodeRecord(r: Record, checksum: Checksum = ZERO_CHECKSUM) =
        bytes { u8(r.type); be16(r.payload.size); bytes(r.payload); be16(checksum.compute(r.type, r.payload)) }

    fun encodeFrame(header: Header, records: List<Record>, checksum: Checksum = ZERO_CHECKSUM) =
        header.encode() + records.fold(ByteArray(0)) { acc, r -> acc + encodeRecord(r, checksum) }

    fun hello(header: Header, data: ByteArray) = header.encode() + HELLO_MAGIC + data
}

/** Reassembles NRLP records from a stream of frames on one channel. */
class NrlpReader {
    private val buf = java.io.ByteArrayOutputStream()
    var lastHeader: Nrlp.Header? = null
        private set

    sealed interface Item {
        data class Hello(val data: ByteArray) : Item
        data class Data(val record: Nrlp.Record) : Item
        data class EchoCount(val count: Int) : Item
    }

    fun accept(frame: ByteArray): List<Item> {
        val h = Nrlp.Header.decode(frame)
        lastHeader = h
        if (h.echo) return listOf(Item.EchoCount(h.echoCount))
        val body = frame.copyOfRange(2, frame.size)
        if (buf.size() == 0 && body.size >= 8 && body.copyOf(8).contentEquals(Nrlp.HELLO_MAGIC)) {
            return listOf(Item.Hello(body.copyOfRange(8, body.size)))
        }
        buf.write(body)
        val out = mutableListOf<Item>()
        var all = buf.toByteArray()
        var o = 0
        while (all.size - o >= 3) {
            val len = all.be16(o + 1)
            if (all.size - o < 3 + len + 2) break
            out += Item.Data(Nrlp.Record(all.u8(o), all.copyOfRange(o + 3, o + 3 + len)))
            o += 3 + len + 2
        }
        buf.reset()
        if (o < all.size) buf.write(all, o, all.size - o)
        return out
    }
}
