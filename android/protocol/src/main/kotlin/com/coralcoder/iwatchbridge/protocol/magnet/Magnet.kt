package com.coralcoder.iwatchbridge.protocol.magnet

import com.coralcoder.iwatchbridge.protocol.util.bytes
import com.coralcoder.iwatchbridge.protocol.util.le16
import com.coralcoder.iwatchbridge.protocol.util.le64
import com.coralcoder.iwatchbridge.protocol.util.u8

/**
 * Magnet ("BT_CL") link signaling on fixed L2CAP CID 0x003A: the two sides advertise named
 * services and open a pair of dynamic L2CAP channels for each one.
 *
 * Layouts follow the WatchWitch Wireshark dissector (seemoo-lab/watchwitch-wireshark,
 * lua/magnet.lua, GPL-3.0). The length field is 1 byte before the version exchange and for the
 * Version message itself, and 2 bytes (little-endian) once both sides speak version >= 9.
 * Watches on watchOS 26 report version 0x0B.
 */
sealed class MagnetMessage {
    abstract val opcode: Int
    protected abstract fun body(): ByteArray

    fun encode(longLength: Boolean): ByteArray {
        val b = body()
        return bytes {
            u8(opcode)
            if (longLength && opcode != OP_VERSION) le16(b.size) else u8(b.size)
            bytes(b)
        }
    }

    data class Service(val id: Int, val flags: Int, val name: String, val option: Int)

    data class AdvertiseServices(val services: List<Service>) : MagnetMessage() {
        override val opcode = OP_ADVERTISE_SERVICES
        override fun body() = bytes {
            u8(services.size)
            services.forEach { s ->
                val n = s.name.toByteArray()
                u8(2 + 1 + 1 + n.size + 1); le16(s.id); u8(s.flags); u8(n.size); bytes(n); u8(s.option)
            }
        }
    }
    data class ServicesResponse(val ids: List<Int>) : MagnetMessage() {
        override val opcode = OP_SERVICES_RESPONSE
        override fun body() = bytes { u8(ids.size); ids.forEach { le16(it) } }
    }
    data class ChannelCreated(val cid: Int, val serviceId: Int) : MagnetMessage() {
        override val opcode = OP_CHANNEL_CREATED
        override fun body() = bytes { le16(cid); le16(serviceId) }
    }
    data class ChannelAccepted(val status: Int, val serviceId: Int, val cid: Int) : MagnetMessage() {
        override val opcode = OP_CHANNEL_ACCEPTED
        override fun body() = bytes { u8(status); le16(serviceId); le16(cid) }
    }
    data class ServiceAdded(val service: Service, val unknown: Int = 0) : MagnetMessage() {
        override val opcode = OP_SERVICE_ADDED
        override fun body() = bytes {
            val n = service.name.toByteArray()
            le16(service.id); u8(unknown); u8(n.size); bytes(n); u8(service.option)
        }
    }
    data class ServiceRemoved(val serviceId: Int) : MagnetMessage() {
        override val opcode = OP_SERVICE_REMOVED
        override fun body() = bytes { le16(serviceId) }
    }
    data class ServiceRemovalAck(val serviceId: Int, val rest: ByteArray = ByteArray(0)) : MagnetMessage() {
        override val opcode = OP_SERVICE_REMOVAL_ACK
        override fun body() = bytes { le16(serviceId); bytes(rest) }
    }
    data class Version(val version: Int, val flags: Long) : MagnetMessage() {
        override val opcode = OP_VERSION
        override fun body() = bytes { u8(version); le32(flags) }
    }
    data class TimeData(val unixNanos: Long, val rest: ByteArray) : MagnetMessage() {
        override val opcode = OP_TIME_DATA_1
        override fun body() = bytes { le64(unixNanos); bytes(rest) }
    }
    data class DeviceId(val vendorId: Int, val productId: Int, val version: Int, val chipset: Int) : MagnetMessage() {
        override val opcode = OP_DID_INFO
        override fun body() = bytes { le16(vendorId); le16(productId); le16(version); le16(chipset) }
    }
    data class Raw(override val opcode: Int, val data: ByteArray) : MagnetMessage() {
        override fun body() = data
    }

    companion object {
        const val OP_ADVERTISE_SERVICES = 0x01
        const val OP_SERVICES_RESPONSE = 0x02
        const val OP_CHANNEL_CREATED = 0x03
        const val OP_CHANNEL_ACCEPTED = 0x04
        const val OP_SERVICE_ADDED = 0x05
        const val OP_SERVICE_REMOVED = 0x06
        const val OP_SERVICE_REMOVAL_ACK = 0x07
        const val OP_ERROR = 0x08
        const val OP_VERSION = 0x09
        const val OP_TIME_REQUEST = 0x70
        const val OP_TIME_DATA_1 = 0x71
        const val OP_TIME_DATA_2 = 0x72
        const val OP_DID_INFO = 0x90
        const val OP_CL_DATA = 0x91

        const val CURRENT_VERSION = 0x0B

        // Known service names
        const val SVC_TERMINUS_LINK = "com.apple.terminusLink"
        const val SVC_TERMINUS_LINK_URGENT = "com.apple.terminusLink.urgent"
        const val SVC_TERMINUS_PAIRING = "com.apple.terminusPairing"
        const val SVC_CLINK = "CLink"
        const val SVC_CLINK_HP = "CLinkHP"
        const val SVC_TIME_SYNC = "com.apple.BT.TS"

        fun decode(b: ByteArray, longLength: Boolean): MagnetMessage {
            val op = b.u8(0)
            val long = longLength && op != OP_VERSION
            val len = if (long) b.le16(1) else b.u8(1)
            val start = if (long) 3 else 2
            val d = b.copyOfRange(start, minOf(b.size, start + len))
            return when (op) {
                OP_ADVERTISE_SERVICES -> {
                    var o = 1
                    AdvertiseServices((0 until d.u8(0)).map {
                        val entryLen = d.u8(o)
                        val id = d.le16(o + 1); val flags = d.u8(o + 3); val nl = d.u8(o + 4)
                        val name = String(d, o + 5, nl); val option = d.u8(o + 5 + nl)
                        o += entryLen + 1
                        Service(id, flags, name, option)
                    })
                }
                OP_SERVICES_RESPONSE -> ServicesResponse((0 until d.u8(0)).map { d.le16(1 + it * 2) })
                OP_CHANNEL_CREATED -> ChannelCreated(d.le16(0), d.le16(2))
                OP_CHANNEL_ACCEPTED -> ChannelAccepted(d.u8(0), d.le16(1), d.le16(3))
                OP_SERVICE_ADDED -> {
                    val nl = d.u8(3)
                    ServiceAdded(Service(d.le16(0), 0, String(d, 4, nl), d.u8(4 + nl)), d.u8(2))
                }
                OP_SERVICE_REMOVED -> ServiceRemoved(d.le16(0))
                OP_SERVICE_REMOVAL_ACK -> ServiceRemovalAck(d.le16(0), d.copyOfRange(2, d.size))
                OP_VERSION -> Version(d.u8(0), (d.le16(1).toLong()) or (d.le16(3).toLong() shl 16))
                OP_TIME_DATA_1 -> TimeData(d.le64(0), d.copyOfRange(8, d.size))
                OP_DID_INFO -> DeviceId(d.le16(0), d.le16(2), d.le16(4), d.le16(6))
                else -> Raw(op, d)
            }
        }
    }
}

/**
 * Tracks the service table and channel pairs for one Magnet link. Each service is carried by
 * two dynamic L2CAP channels, one per direction.
 */
class MagnetSession {
    var peerVersion: Int = 0
        private set
    val longLength get() = peerVersion >= 9
    val remoteServices = LinkedHashMap<Int, MagnetMessage.Service>()
    val localServices = LinkedHashMap<Int, MagnetMessage.Service>()
    /** service name -> L2CAP channel ids (ours / theirs) once open */
    val channels = HashMap<String, Pair<Int?, Int?>>()

    fun serviceNameFor(id: Int) = remoteServices[id]?.name ?: localServices[id]?.name

    /** Apply an incoming message; returns replies to send. */
    fun onMessage(m: MagnetMessage): List<MagnetMessage> = when (m) {
        is MagnetMessage.Version -> { peerVersion = m.version; emptyList() }
        is MagnetMessage.AdvertiseServices -> {
            m.services.forEach { remoteServices[it.id] = it }
            listOf(MagnetMessage.ServicesResponse(m.services.map { it.id }))
        }
        is MagnetMessage.ServiceAdded -> { remoteServices[m.service.id] = m.service; emptyList() }
        is MagnetMessage.ServiceRemoved -> {
            remoteServices.remove(m.serviceId)?.let { channels.remove(it.name) }
            listOf(MagnetMessage.ServiceRemovalAck(m.serviceId))
        }
        is MagnetMessage.ChannelCreated -> {
            serviceNameFor(m.serviceId)?.let { name -> channels[name] = (channels[name]?.first) to m.cid }
            emptyList()
        }
        is MagnetMessage.ChannelAccepted -> {
            serviceNameFor(m.serviceId)?.let { name -> channels[name] = m.cid to (channels[name]?.second) }
            emptyList()
        }
        else -> emptyList()
    }
}
