package com.coralcoder.iwatchbridge.protocol.util

/** Little helpers for the wire codecs. Bluetooth is little-endian; most Apple layers above are big-endian. */

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray {
    val clean = filterNot { it.isWhitespace() }.removePrefix("0x")
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff
fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
fun ByteArray.le32(i: Int): Long = (le16(i).toLong()) or (le16(i + 2).toLong() shl 16)
fun ByteArray.le64(i: Int): Long = le32(i) or (le32(i + 4) shl 32)

/** Growable little-endian/big-endian writer. */
class ByteWriter(capacity: Int = 32) {
    private var buf = ByteArray(capacity)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int) = apply { ensure(1); buf[size++] = v.toByte() }
    fun le16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun be16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun le32(v: Long) = apply { le16(v.toInt()); le16((v ushr 16).toInt()) }
    fun le64(v: Long) = apply { le32(v); le32(v ushr 32) }
    fun bytes(b: ByteArray) = apply { ensure(b.size); b.copyInto(buf, size); size += b.size }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

inline fun bytes(block: ByteWriter.() -> Unit): ByteArray = ByteWriter().apply(block).toByteArray()

/** Bluetooth device address; stored and printed most-significant byte first, sent reversed. */
@JvmInline
value class BdAddr(val msbFirst: ByteArray) {
    init { require(msbFirst.size == 6) }
    fun wire(): ByteArray = msbFirst.reversedArray()
    override fun toString(): String = msbFirst.joinToString(":") { "%02X".format(it) }

    companion object {
        fun fromWire(b: ByteArray, offset: Int = 0) = BdAddr(b.copyOfRange(offset, offset + 6).reversedArray())
        fun parse(s: String) = BdAddr(s.split(":").map { it.toInt(16).toByte() }.toByteArray())
    }
}
