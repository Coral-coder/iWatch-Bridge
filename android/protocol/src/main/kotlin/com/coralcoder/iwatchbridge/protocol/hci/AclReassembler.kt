package com.coralcoder.iwatchbridge.protocol.hci

import com.coralcoder.iwatchbridge.protocol.util.le16

/**
 * Turns ACL fragments into whole L2CAP PDUs (per connection handle) and splits outgoing PDUs
 * into ACL packets that fit the controller's buffer size.
 */
class AclReassembler {
    private val partial = HashMap<Int, java.io.ByteArrayOutputStream>()
    private val expected = HashMap<Int, Int>()

    /** Feed one ACL packet; returns a complete L2CAP PDU (basic header included) when one is done. */
    fun accept(acl: HciPacket.Acl): ByteArray? {
        val h = acl.handle
        if (acl.pb != HciPacket.PB_CONTINUING) {
            partial.remove(h); expected.remove(h)
            if (acl.data.size < 4) {
                partial[h] = java.io.ByteArrayOutputStream().apply { write(acl.data) }
                expected[h] = -1
                return null
            }
            val total = acl.data.le16(0) + 4
            if (acl.data.size >= total) return acl.data.copyOf(total)
            partial[h] = java.io.ByteArrayOutputStream(total).apply { write(acl.data) }
            expected[h] = total
            return null
        }
        val buf = partial[h] ?: return null // continuation without a start: drop
        buf.write(acl.data)
        var total = expected[h]!!
        if (total == -1 && buf.size() >= 4) { total = buf.toByteArray().le16(0) + 4; expected[h] = total }
        if (total > 0 && buf.size() >= total) {
            partial.remove(h); expected.remove(h)
            return buf.toByteArray().copyOf(total)
        }
        return null
    }

    fun reset(handle: Int) { partial.remove(handle); expected.remove(handle) }

    companion object {
        fun fragment(handle: Int, pdu: ByteArray, maxAclData: Int, flushable: Boolean = false): List<HciPacket.Acl> {
            require(maxAclData > 0)
            val out = ArrayList<HciPacket.Acl>((pdu.size + maxAclData - 1) / maxAclData)
            var o = 0
            while (o < pdu.size) {
                val n = minOf(maxAclData, pdu.size - o)
                val pb = when {
                    o > 0 -> HciPacket.PB_CONTINUING
                    flushable -> HciPacket.PB_FIRST_FLUSHABLE
                    else -> HciPacket.PB_FIRST_NON_FLUSHABLE
                }
                out += HciPacket.Acl(handle, pb, 0, pdu.copyOfRange(o, o + n))
                o += n
            }
            return out
        }
    }
}
