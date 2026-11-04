package com.coralcoder.iwatchbridge.protocol.host

import com.coralcoder.iwatchbridge.protocol.util.BdAddr
import com.coralcoder.iwatchbridge.protocol.util.le16
import com.coralcoder.iwatchbridge.protocol.util.u8

/** BLE advertising data (Core Supplement Part A): a list of length-type-value structures. */
object AdStructures {
    const val SERVICE_DATA_16 = 0x16
    const val MANUFACTURER_DATA = 0xFF
    const val COMPLETE_NAME = 0x09
    const val SHORT_NAME = 0x08

    fun parse(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var o = 0
        while (o < data.size) {
            val len = data.u8(o)
            if (len == 0 || o + 1 + len > data.size) break
            out += data.u8(o + 1) to data.copyOfRange(o + 2, o + 1 + len)
            o += 1 + len
        }
        return out
    }

    fun serviceData16(data: ByteArray, uuid: Int): ByteArray? =
        parse(data).firstOrNull { it.first == SERVICE_DATA_16 && it.second.size >= 2 && it.second.le16(0) == uuid }
            ?.second?.let { it.copyOfRange(2, it.size) }
}

/**
 * A watch waiting to be set up. In setup mode the watch advertises 16-bit service data under
 * UUID 0xFE25 (Apple). The payload describes the pairing strategy, product and watchOS version;
 * it is kept raw here and decoded in [SetupAdvertisement.fields] as fields get confirmed.
 */
data class SetupAdvertisement(
    val address: BdAddr,
    val addressType: Int,
    val rssi: Int,
    val payload: ByteArray,
    val localName: String?,
    val seenAtMs: Long,
) {
    /** First byte: advertisement version / pairing strategy flags (exact bit meanings still being confirmed). */
    val fields: Map<String, Int> get() = if (payload.isEmpty()) emptyMap() else mapOf("flags" to payload.u8(0))

    companion object {
        const val APPLE_SETUP_UUID = 0xFE25

        fun from(address: BdAddr, addressType: Int, rssi: Int, adv: ByteArray, now: Long = System.currentTimeMillis()): SetupAdvertisement? {
            val payload = AdStructures.serviceData16(adv, APPLE_SETUP_UUID) ?: return null
            val name = AdStructures.parse(adv).firstOrNull { it.first == AdStructures.COMPLETE_NAME || it.first == AdStructures.SHORT_NAME }
                ?.second?.toString(Charsets.UTF_8)
            return SetupAdvertisement(address, addressType, rssi, payload, name, now)
        }
    }
}
