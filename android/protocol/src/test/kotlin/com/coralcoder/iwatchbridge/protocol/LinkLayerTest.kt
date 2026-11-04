package com.coralcoder.iwatchbridge.protocol

import com.coralcoder.iwatchbridge.protocol.hci.AclReassembler
import com.coralcoder.iwatchbridge.protocol.hci.Cmd
import com.coralcoder.iwatchbridge.protocol.hci.HciEvent
import com.coralcoder.iwatchbridge.protocol.hci.HciPacket
import com.coralcoder.iwatchbridge.protocol.l2cap.ConfigOption
import com.coralcoder.iwatchbridge.protocol.l2cap.Ertm
import com.coralcoder.iwatchbridge.protocol.l2cap.ErtmChannel
import com.coralcoder.iwatchbridge.protocol.l2cap.L2cap
import com.coralcoder.iwatchbridge.protocol.l2cap.Signal
import com.coralcoder.iwatchbridge.protocol.util.BdAddr
import com.coralcoder.iwatchbridge.protocol.util.hex
import com.coralcoder.iwatchbridge.protocol.util.le64
import com.coralcoder.iwatchbridge.protocol.util.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LinkLayerTest {
    @Test fun hciCommandAndEventRoundTrip() {
        assertEquals("01030c00", Cmd.reset().h4().hex())
        assertEquals("01240c030c027a", Cmd.writeClassOfDevice(0x7A020C).h4().hex())
        // Command Complete for Read BD_ADDR: status 0, address 11:22:33:44:55:66
        val ev = HciPacket.parseH4("040e0a01091000665544332211".unhex()) as HciPacket.Event
        val cc = HciEvent.decode(ev).single() as HciEvent.CommandComplete
        assertEquals(0x1009, cc.opcode)
        assertEquals("11:22:33:44:55:66", BdAddr.fromWire(cc.returnParams, 1).toString())
    }

    @Test fun aclFragmentAndReassemble() {
        val pdu = L2cap.Frame(0x0040, Random(1).nextBytes(1000)).encode()
        val frags = AclReassembler.fragment(0x0b, pdu, 251)
        assertEquals(4, frags.size)
        assertEquals(HciPacket.PB_FIRST_NON_FLUSHABLE, frags[0].pb)
        val r = AclReassembler()
        val results = frags.map { r.accept(HciPacket.parse(2, it.body()) as HciPacket.Acl) }
        assertTrue(results.dropLast(1).all { it == null })
        assertArrayEquals(pdu, results.last())
    }

    /** Examples from Core v5.4 Vol 3 Part A §3.3.5. */
    @Test fun fcsMatchesSpecExamples() {
        assertEquals(0x6138, Ertm.fcs("0e004000020000010203040506070809".unhex()))
        assertEquals(0x14d4, Ertm.fcs("040040000101".unhex()))
    }

    @Test fun informationResponseAdvertisesAppleFixedChannel() {
        val rsp = Signal.informationResponseFor(Signal.InformationRequest(7, Signal.INFO_FIXED_CHANNELS))
        val mask = rsp.payload.le64(0)
        assertTrue(mask and (1L shl 0x3A) != 0L)
        val again = Signal.decodeAll(rsp.encode()).single() as Signal.InformationResponse
        assertEquals(rsp.infoType, again.infoType)
        assertArrayEquals(rsp.payload, again.payload)
    }

    @Test fun configureRequestWithErtmRoundTrip() {
        val req = Signal.ConfigureRequest(3, 0x0041, 0, listOf(
            ConfigOption.Mtu(1691),
            ConfigOption.RetransmissionFlowControl(ConfigOption.RetransmissionFlowControl.MODE_ERTM, 32, 10, 2000, 12000, 1010),
            ConfigOption.Fcs(1),
        ))
        val back = Signal.decodeAll(req.encode()).single() as Signal.ConfigureRequest
        assertEquals(req, back.copy())
        assertEquals(req.options, back.options)
    }

    @Test fun ertmTransfersSegmentedSdusBothWays() {
        val a = ErtmChannel(localCid = 0x40, remoteCid = 0x50, remoteTxWindow = 4, remoteMps = 100)
        val b = ErtmChannel(localCid = 0x50, remoteCid = 0x40, remoteTxWindow = 4, remoteMps = 100)
        val big = Random(2).nextBytes(950) // 10 segments, more than the window
        val received = mutableListOf<ByteArray>()
        var toB = a.send(big)
        var guard = 0
        while (toB.isNotEmpty() && guard++ < 100) {
            val toA = toB.flatMap { b.receive(it).also { r -> received += r.sdus }.toSend }
            toB = toA.flatMap { a.receive(it).toSend }
        }
        assertEquals(1, received.size)
        assertArrayEquals(big, received[0])
        assertEquals(0, a.outstanding)
    }

    @Test fun ertmDropsCorruptFrames() {
        val pdu = Ertm.encodePdu(0x40, Ertm.Control.I(0, 0, Ertm.SAR_UNSEGMENTED), "hi".toByteArray())
        pdu[7] = (pdu[7] + 1).toByte()
        assertNull(Ertm.decodePdu(pdu))
    }

    @Test fun ertmRetransmitsAfterReject() {
        val a = ErtmChannel(0x40, 0x50, remoteTxWindow = 8, remoteMps = 50)
        val sent = a.send(ByteArray(120))
        assertEquals(3, sent.size)
        val rej = Ertm.encodePdu(0x40, Ertm.Control.S(Ertm.S_REJ, 1))
        val again = a.receive(rej).toSend
        assertEquals(2, again.size) // frames 1 and 2 go again
        assertEquals(1, (Ertm.decodePdu(again[0])!!.control as Ertm.Control.I).txSeq)
    }
}
