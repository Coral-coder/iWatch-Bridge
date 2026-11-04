package com.coralcoder.iwatchbridge.protocol.host

/**
 * How the phone presents itself over Bluetooth: the same class of device, name style and
 * device ID an iPhone uses, so the watch treats us as its companion phone.
 */
data class PhoneIdentity(
    val name: String = "iPhone",
    /** Major class Phone, minor Smartphone, with the service bits an iPhone sets. */
    val classOfDevice: Int = 0x7A020C,
    /** Apple (Bluetooth SIG company id) — used in Device ID and manufacturer data. */
    val vendorId: Int = 0x004C,
    val productType: String = "iPhone17,1",
    val osVersion: String = "26.1",
    val osBuild: String = "23B85",
) {
    /** Extended Inquiry Response: complete local name. */
    fun eir(): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).take(238).toByteArray()
        return byteArrayOf((n.size + 1).toByte(), 0x09) + n
    }
}
