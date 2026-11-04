package com.coralcoder.iwatchbridge.protocol

import com.coralcoder.iwatchbridge.protocol.crypto.HashAlg
import com.coralcoder.iwatchbridge.protocol.crypto.IkeKdf
import com.coralcoder.iwatchbridge.protocol.magnet.MagnetMessage
import com.coralcoder.iwatchbridge.protocol.magnet.MagnetSession
import com.coralcoder.iwatchbridge.protocol.nrlp.Nrlp
import com.coralcoder.iwatchbridge.protocol.nrlp.NrlpReader
import com.coralcoder.iwatchbridge.protocol.util.hex
import com.coralcoder.iwatchbridge.protocol.util.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLayersTest {
    @Test fun magnetServiceAdvertisementRoundTrip() {
        val m = MagnetMessage.AdvertiseServices(listOf(
            MagnetMessage.Service(1, 0, MagnetMessage.SVC_TERMINUS_LINK, 1),
            MagnetMessage.Service(2, 0, MagnetMessage.SVC_TERMINUS_LINK_URGENT, 1),
            MagnetMessage.Service(3, 0, MagnetMessage.SVC_TIME_SYNC, 0),
        ))
        for (long in listOf(false, true)) assertEquals(m, MagnetMessage.decode(m.encode(long), long))
    }

    @Test fun magnetVersionUsesShortLength() {
        val v = MagnetMessage.Version(0x0B, 0x1234)
        val enc = v.encode(longLength = true)
        assertEquals("0905" + "0b34120000", enc.hex())
        assertEquals(v, MagnetMessage.decode(enc, true))
    }

    @Test fun magnetSessionTracksChannels() {
        val s = MagnetSession()
        s.onMessage(MagnetMessage.Version(0x0B, 0))
        assertTrue(s.longLength)
        val reply = s.onMessage(MagnetMessage.AdvertiseServices(listOf(MagnetMessage.Service(7, 0, MagnetMessage.SVC_TERMINUS_PAIRING, 0))))
        assertEquals(listOf(MagnetMessage.ServicesResponse(listOf(7))), reply)
        s.onMessage(MagnetMessage.ChannelCreated(0x41, 7))
        s.onMessage(MagnetMessage.ChannelAccepted(0, 7, 0x42))
        assertEquals(0x42 to 0x41, s.channels[MagnetMessage.SVC_TERMINUS_PAIRING])
    }

    @Test fun nrlpRecordSplitAcrossFrames() {
        val record = Nrlp.Record(Nrlp.IKEV2, ByteArray(300) { it.toByte() })
        val full = Nrlp.encodeFrame(Nrlp.Header(false, 5, 3), listOf(record))
        val r = NrlpReader()
        val first = r.accept(full.copyOfRange(0, 150))
        assertTrue(first.isEmpty())
        val second = r.accept(Nrlp.Header(false, 6, 3).encode() + full.copyOfRange(150, full.size))
        val got = (second.single() as NrlpReader.Item.Data).record
        assertEquals(Nrlp.IKEV2, got.type)
        assertArrayEquals(record.payload, got.payload)
        assertEquals(6, r.lastHeader!!.seq)
    }

    @Test fun nrlpHello() {
        val item = NrlpReader().accept(Nrlp.hello(Nrlp.Header(false, 0, 0), "abc".toByteArray())).single()
        assertArrayEquals("abc".toByteArray(), (item as NrlpReader.Item.Hello).data)
    }

    /** RFC 4231 test case 2 for HMAC-SHA512, then prf+ against a hand-computed first block. */
    @Test fun ikePrfPlus() {
        val mac = HashAlg.SHA512.hmac("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertEquals("164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea2505549758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737", mac.hex())
        val out = IkeKdf.prfPlus(HashAlg.SHA512, "k".toByteArray(), "s".toByteArray(), 100)
        assertArrayEquals(HashAlg.SHA512.hmac("k".toByteArray(), "s".toByteArray(), byteArrayOf(1)), out.copyOf(64))
        val t2 = HashAlg.SHA512.hmac("k".toByteArray(), out.copyOf(64), "s".toByteArray(), byteArrayOf(2))
        assertArrayEquals(t2.copyOf(36), out.copyOfRange(64, 100))
    }

    @Test fun ikeSaKeySplit() {
        val k = IkeKdf.ikeSaKeys(HashAlg.SHA512, ByteArray(64) { 1 }, "aa".unhex(), "bb".unhex(), ByteArray(8), ByteArray(8), 0, 36)
        assertEquals(64, k.skD.size); assertEquals(0, k.skAi.size); assertEquals(36, k.skEi.size); assertEquals(64, k.skPr.size)
    }
}
