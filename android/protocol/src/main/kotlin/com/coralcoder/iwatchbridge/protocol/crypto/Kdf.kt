package com.coralcoder.iwatchbridge.protocol.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

enum class HashAlg(val outLen: Int, internal val factory: () -> Digest) {
    SHA256(32, ::SHA256Digest),
    SHA512(64, ::SHA512Digest);

    fun digest(data: ByteArray): ByteArray {
        val d = factory(); d.update(data, 0, data.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(factory()); mac.init(KeyParameter(key))
        parts.forEach { mac.update(it, 0, it.size) }
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** RFC 5869. An empty salt means "no salt" (a string of zeros), as in the RFC. */
    fun hkdf(salt: ByteArray?, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(factory())
        gen.init(HKDFParameters(ikm, salt?.takeIf { it.isNotEmpty() }, info))
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }
}

/**
 * IKEv2 key derivation (RFC 7296 §2.13 / §2.14). The watch's pairing variant ("uIKE") and the
 * normal-link IKE sessions use HMAC-SHA512 as the PRF.
 */
object IkeKdf {
    /** prf+ (K,S) = T1 | T2 | ...,  T1 = prf(K, S | 0x01),  Tn = prf(K, Tn-1 | S | n) */
    fun prfPlus(prf: HashAlg, key: ByteArray, seed: ByteArray, length: Int): ByteArray {
        require(length <= 255 * prf.outLen) { "prf+ output too long" }
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var n = 1
        while (out.size() < length) {
            t = prf.hmac(key, t, seed, byteArrayOf(n.toByte()))
            out.write(t); n++
        }
        return out.toByteArray().copyOf(length)
    }

    /** SKEYSEED = prf(Ni | Nr, g^ir) */
    fun skeyseed(prf: HashAlg, ni: ByteArray, nr: ByteArray, sharedSecret: ByteArray) =
        prf.hmac(ni + nr, sharedSecret)

    /** The seven IKE SA keys, split from prf+(SKEYSEED, Ni | Nr | SPIi | SPIr). */
    data class IkeSaKeys(
        val skD: ByteArray, val skAi: ByteArray, val skAr: ByteArray,
        val skEi: ByteArray, val skEr: ByteArray, val skPi: ByteArray, val skPr: ByteArray,
    )

    /**
     * @param integKeyLen 0 for AEAD ciphers (AES-GCM, ChaCha20-Poly1305), which have no SK_a.
     * @param encKeyLen key + salt length (AES-256-GCM: 32 + 4 = 36; ChaCha20-Poly1305: 32 + 4).
     */
    fun ikeSaKeys(
        prf: HashAlg, skeyseed: ByteArray, ni: ByteArray, nr: ByteArray, spiI: ByteArray, spiR: ByteArray,
        integKeyLen: Int, encKeyLen: Int,
    ): IkeSaKeys {
        val p = prf.outLen
        val total = p + 2 * integKeyLen + 2 * encKeyLen + 2 * p
        val km = prfPlus(prf, skeyseed, ni + nr + spiI + spiR, total)
        var o = 0
        fun take(n: Int) = km.copyOfRange(o, o + n).also { o += n }
        return IkeSaKeys(take(p), take(integKeyLen), take(integKeyLen), take(encKeyLen), take(encKeyLen), take(p), take(p))
    }

    /** Child SA keying material: prf+(SK_d, [g^ir (new)] | Ni | Nr). */
    fun childKeys(prf: HashAlg, skD: ByteArray, ni: ByteArray, nr: ByteArray, length: Int, newDh: ByteArray? = null) =
        prfPlus(prf, skD, (newDh ?: ByteArray(0)) + ni + nr, length)
}
