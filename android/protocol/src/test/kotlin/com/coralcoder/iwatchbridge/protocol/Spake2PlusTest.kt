package com.coralcoder.iwatchbridge.protocol

import com.coralcoder.iwatchbridge.protocol.crypto.Spake2Plus
import com.coralcoder.iwatchbridge.protocol.util.hex
import com.coralcoder.iwatchbridge.protocol.util.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** RFC 9383 Appendix C, SPAKE2+-P256-SHA256-HKDF-SHA256-HMAC-SHA256. */
class Spake2PlusTest {
    private val context = "SPAKE2+-P256-SHA256-HKDF-SHA256-HMAC-SHA256 Test Vectors".toByteArray()
    private val idP = "client".toByteArray()
    private val idV = "server".toByteArray()
    private val w0 = BigInteger("bb8e1bbcf3c48f62c08db243652ae55d3e5586053fca77102994f23ad95491b3", 16)
    private val w1 = BigInteger("7e945f34d78785b8a3ef44d0df5a1a97d6b3b460409a345ca7830387a74b1dba", 16)
    private val x = BigInteger("d1232c8e8693d02368976c174e2088851b8365d0d79a9eee709c6a05a2fad539", 16)
    private val y = BigInteger("717a72348a182085109c8d3917d6c43d59b224dc6a7fc4f0483232fa6516d8b3", 16)
    private val l = "04eb7c9db3d9a9eb1f8adab81b5794c1f13ae3e225efbe91ea487425854c7fc00f00bfedcbd09b2400142d40a14f2064ef31dfaa903b91d1faea7093d835966efd"
    private val shareP = "04ef3bd051bf78a2234ec0df197f7828060fe9856503579bb1733009042c15c0c1de127727f418b5966afadfdd95a6e4591d171056b333dab97a79c7193e341727"
    private val shareV = "04c0f65da0d11927bdf5d560c69e1d7d939a05b0e88291887d679fcadea75810fb5cc1ca7494db39e82ff2f50665255d76173e09986ab46742c798a9a68437b048"
    private val kShared = "0c5f8ccd1413423a54f6c1fb26ff01534a87f893779c6e68666d772bfd91f3e7"
    private val confirmP = "926cc713504b9b4d76c9162ded04b5493e89109f6d89462cd33adc46fda27527"
    private val confirmV = "9747bcc4f8fe9f63defee53ac9b07876d907d55047e6ff2def2e7529089d3e68"

    @Test fun registrationRecord() = assertEquals(l, Spake2Plus.registrationRecord(w1).hex())

    @Test fun rfcVector() {
        val prover = Spake2Plus.prover(context, idP, idV, w0, w1) { x }
        val verifier = Spake2Plus.verifier(context, idP, idV, w0, l.unhex()) { y }
        assertEquals(shareP, prover.share.hex())
        assertEquals(shareV, verifier.share.hex())

        val p = prover.finish(verifier.share)
        val v = verifier.finish(prover.share)
        assertEquals(kShared, p.kShared.hex())
        assertEquals(kShared, v.kShared.hex())
        assertEquals(confirmP, p.confirmOut.hex())
        assertEquals(confirmV, v.confirmOut.hex())
        assertTrue(p.verifyPeer(v.confirmOut))
        assertTrue(v.verifyPeer(p.confirmOut))
    }

    @Test fun wrongPinFailsConfirmation() {
        val prover = Spake2Plus.prover(context, idP, idV, w0.add(BigInteger.ONE), w1)
        val verifier = Spake2Plus.verifier(context, idP, idV, w0, l.unhex())
        val p = prover.finish(verifier.share)
        val v = verifier.finish(prover.share)
        assertFalse(v.verifyPeer(p.confirmOut))
        assertFalse(p.kShared.contentEquals(v.kShared))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPoint() {
        val bad = shareV.unhex().also { it[40] = (it[40] + 1).toByte() }
        Spake2Plus.prover(context, idP, idV, w0, w1).finish(bad)
    }

    @Test fun randomRunAgrees() {
        val v = Spake2Plus.verifier(context, idP, idV, w0, Spake2Plus.registrationRecord(w1))
        val p = Spake2Plus.prover(context, idP, idV, w0, w1)
        assertArrayEquals(p.finish(v.share).kShared, v.finish(p.share).kShared)
    }
}
