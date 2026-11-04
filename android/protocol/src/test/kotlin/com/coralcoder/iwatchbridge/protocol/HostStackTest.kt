package com.coralcoder.iwatchbridge.protocol

import com.coralcoder.iwatchbridge.protocol.hci.HciPacket
import com.coralcoder.iwatchbridge.protocol.hci.Op
import com.coralcoder.iwatchbridge.protocol.host.HciTransport
import com.coralcoder.iwatchbridge.protocol.host.HostStack
import com.coralcoder.iwatchbridge.protocol.host.PhoneIdentity
import com.coralcoder.iwatchbridge.protocol.l2cap.L2cap
import com.coralcoder.iwatchbridge.protocol.l2cap.Signal
import com.coralcoder.iwatchbridge.protocol.magnet.MagnetMessage
import com.coralcoder.iwatchbridge.protocol.util.BdAddr
import com.coralcoder.iwatchbridge.protocol.util.bytes
import com.coralcoder.iwatchbridge.protocol.util.le64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the host against a simulated controller and a simulated watch on the far end. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostStackTest {
    private val watchAddr = BdAddr.parse("AA:BB:CC:DD:EE:01")

    private inner class FakeController : HciTransport {
        override val name = "fake"
        val toHost = Channel<HciPacket>(Channel.UNLIMITED)
        override val incoming: Flow<HciPacket> = toHost.receiveAsFlow()
        val commands = mutableListOf<HciPacket.Command>()
        val acl = mutableListOf<ByteArray>()
        override suspend fun open() {}
        override suspend fun close() { toHost.close() }

        private fun complete(op: Int, ret: ByteArray) =
            toHost.trySend(HciPacket.Event(0x0E, bytes { u8(1); le16(op); bytes(ret) }))

        override suspend fun send(packet: HciPacket) {
            when (packet) {
                is HciPacket.Command -> {
                    commands += packet
                    when (packet.opcode) {
                        Op.READ_LOCAL_VERSION -> complete(packet.opcode, bytes { u8(0); u8(0x0c); le16(0); u8(0x0c); le16(0x000f); le16(0) })
                        Op.READ_BD_ADDR -> complete(packet.opcode, byteArrayOf(0) + BdAddr.parse("02:00:00:00:00:99").wire())
                        Op.READ_BUFFER_SIZE -> complete(packet.opcode, bytes { u8(0); le16(1021); u8(0); le16(8); le16(0) })
                        Op.CREATE_CONNECTION -> {
                            toHost.trySend(HciPacket.Event(0x0F, bytes { u8(0); u8(1); le16(packet.opcode) }))
                            toHost.trySend(HciPacket.Event(0x03, bytes { u8(0); le16(0x0b); bytes(watchAddr.wire()); u8(1); u8(0) }))
                        }
                        else -> complete(packet.opcode, byteArrayOf(0))
                    }
                }
                is HciPacket.Acl -> {
                    acl += packet.data
                    toHost.trySend(HciPacket.Event(0x13, bytes { u8(1); le16(packet.handle); le16(1) }))
                }
                else -> {}
            }
        }

        fun watchSends(cid: Int, payload: ByteArray) =
            toHost.trySend(HciPacket.Acl(0x0b, 2, 0, L2cap.Frame(cid, payload).encode()))
    }

    @Test fun startsAsAnIphoneAndAnswersTheWatch() = runTest(UnconfinedTestDispatcher()) {
        val ctl = FakeController()
        val host = HostStack(ctl, PhoneIdentity(name = "Dana's iPhone"), backgroundScope)
        host.start()
        assertEquals(HostStack.State.READY, host.state.value)
        assertEquals("02:00:00:00:00:99", host.localAddress.toString())
        val cod = ctl.commands.first { it.opcode == Op.WRITE_CLASS_OF_DEVICE }.params
        assertEquals(listOf(0x0C, 0x02, 0x7A), cod.map { it.toInt() and 0xff })

        // Watch in setup mode advertising FE25 service data.
        val adv = byteArrayOf(5, 0x16, 0x25, 0xFE.toByte(), 0x01, 0x02)
        ctl.toHost.trySend(HciPacket.Event(0x3E, bytes { u8(0x02); u8(1); u8(0); u8(0); bytes(watchAddr.wire()); u8(adv.size); bytes(adv); u8(0xC4) }))
        assertTrue(host.setupWatches.value.containsKey(watchAddr.toString()))

        val link = host.connect(watchAddr)
        assertEquals(0x0b, link.handle)

        // The watch asks which fixed channels we support: 0x3A must be in the answer.
        ctl.acl.clear()
        ctl.watchSends(L2cap.CID_SIGNALING, Signal.InformationRequest(9, Signal.INFO_FIXED_CHANNELS).encode())
        val rsp = Signal.decodeAll(L2cap.Frame.decode(ctl.acl.last()).payload).single() as Signal.InformationResponse
        assertTrue(rsp.payload.le64(0) and (1L shl 0x3A) != 0L)

        // Magnet: version, then service advertisement, which we acknowledge.
        val got = mutableListOf<MagnetMessage>()
        val job = launch { for (m in link.magnetMessages) got += m }
        ctl.watchSends(L2cap.CID_APPLE_MAGNET, MagnetMessage.Version(0x0B, 0).encode(false))
        ctl.watchSends(L2cap.CID_APPLE_MAGNET, MagnetMessage.AdvertiseServices(listOf(MagnetMessage.Service(5, 0, MagnetMessage.SVC_TERMINUS_PAIRING, 0))).encode(true))
        val ack = MagnetMessage.decode(L2cap.Frame.decode(ctl.acl.last()).payload, true)
        assertEquals(MagnetMessage.ServicesResponse(listOf(5)), ack)
        assertEquals(2, got.size)
        job.cancel()
    }
}
