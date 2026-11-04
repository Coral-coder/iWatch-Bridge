package com.coralcoder.iwatchbridge.link

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.coralcoder.iwatchbridge.protocol.hci.HciPacket
import com.coralcoder.iwatchbridge.protocol.host.HciTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A standard USB Bluetooth adapter (class 0xE0/0x01/0x01) driven straight over Android's USB host
 * API — no root, and the phone's own Bluetooth is untouched. Per the Bluetooth USB transport
 * spec (Core Vol 4 Part B): commands go out as class control transfers, events arrive on the
 * interrupt IN endpoint, ACL data on the bulk endpoints.
 */
class UsbHciTransport(
    private val usb: UsbManager,
    private val device: UsbDevice,
) : HciTransport {
    override val name: String = device.productName ?: "USB Bluetooth adapter"
    private val packets = Channel<HciPacket>(Channel.UNLIMITED)
    override val incoming: Flow<HciPacket> = packets.receiveAsFlow()

    private var conn: UsbDeviceConnection? = null
    private lateinit var iface: UsbInterface
    private lateinit var eventIn: UsbEndpoint
    private lateinit var aclIn: UsbEndpoint
    private lateinit var aclOut: UsbEndpoint
    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()

    override suspend fun open() {
        iface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { isBluetooth(it) }
            ?: throw IOException("$name has no Bluetooth HCI interface")
        val eps = (0 until iface.endpointCount).map(iface::getEndpoint)
        eventIn = eps.first { it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN }
        aclIn = eps.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        aclOut = eps.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        val c = usb.openDevice(device) ?: throw IOException("USB permission not granted for $name")
        if (!c.claimInterface(iface, true)) { c.close(); throw IOException("couldn't claim $name") }
        conn = c
        running = true
        threads += reader("hci-usb-events", eventIn, 2, { b -> 2 + (b[1].toInt() and 0xff) }, { HciPacket.parse(0x04, it) })
        threads += reader("hci-usb-acl", aclIn, 4, { b -> 4 + ((b[2].toInt() and 0xff) or ((b[3].toInt() and 0xff) shl 8)) }, { HciPacket.parse(0x02, it) })
        threads.forEach(Thread::start)
    }

    /** Reads an endpoint and splits the byte stream into packets using each packet's length header. */
    private fun reader(name: String, ep: UsbEndpoint, headerLen: Int, totalLen: (ByteArray) -> Int, parse: (ByteArray) -> HciPacket) = Thread({
        val buf = ByteArray(maxOf(1024, ep.maxPacketSize))
        val acc = ByteArrayOutputStream()
        while (running) {
            val n = conn?.bulkTransfer(ep, buf, buf.size, 500) ?: break
            if (n <= 0) continue
            acc.write(buf, 0, n)
            var data = acc.toByteArray()
            var o = 0
            while (data.size - o >= headerLen) {
                val head = data.copyOfRange(o, o + headerLen)
                val len = totalLen(head)
                if (data.size - o < len) break
                runCatching { packets.trySend(parse(data.copyOfRange(o, o + len))) }
                o += len
            }
            acc.reset()
            if (o < data.size) acc.write(data, o, data.size - o)
        }
    }, name).apply { isDaemon = true }

    override suspend fun send(packet: HciPacket) {
        val c = conn ?: throw IOException("$name is closed")
        val body = packet.body()
        val n = when (packet) {
            is HciPacket.Command -> c.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x00 /* recipient: device */,
                0, 0, iface.id, body, body.size, 1000,
            )
            is HciPacket.Acl -> c.bulkTransfer(aclOut, body, body.size, 1000)
            else -> throw IOException("unsupported packet $packet")
        }
        if (n != body.size) throw IOException("USB write failed ($n of ${body.size} bytes)")
    }

    override suspend fun close() {
        running = false
        threads.forEach { it.join(1000) }
        threads.clear()
        conn?.let { it.releaseInterface(iface); it.close() }
        conn = null
        packets.close()
    }

    companion object {
        fun isBluetooth(i: UsbInterface) =
            i.interfaceClass == 0xE0 && i.interfaceSubclass == 0x01 && i.interfaceProtocol == 0x01

        fun adapters(usb: UsbManager): List<UsbDevice> = usb.deviceList.values.filter { d ->
            (d.deviceClass == 0xE0) || (0 until d.interfaceCount).any { isBluetooth(d.getInterface(it)) }
        }
    }
}
