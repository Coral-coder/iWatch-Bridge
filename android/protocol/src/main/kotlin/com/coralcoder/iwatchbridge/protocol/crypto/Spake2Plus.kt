package com.coralcoder.iwatchbridge.protocol.crypto

import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2+ (RFC 9383), P-256 / SHA-256 / HKDF-SHA256 / HMAC-SHA256.
 *
 * The watch uses SPAKE2+ to authenticate the setup IKE session when the user pairs by typing the
 * six-digit code shown on the watch ("Pair Manually"); see docs/PROTOCOL.md. The phone is the
 * Prover (it knows the code), the watch the Verifier. The RFC test vectors are in the unit tests.
 *
 * The protocol-specific parts (context string, identities, how w0/w1 are derived from the PIN)
 * are parameters, not baked in, because they are what still needs confirming against hardware.
 */
class Spake2Plus private constructor(
    private val role: Role,
    private val context: ByteArray,
    private val idProver: ByteArray,
    private val idVerifier: ByteArray,
    private val w0: BigInteger,
    private val w1: BigInteger?,     // Prover only
    private val l: ECPoint?,         // Verifier only
    random: () -> BigInteger,
) {
    enum class Role { PROVER, VERIFIER }

    class Result(val kShared: ByteArray, val confirmOut: ByteArray, internal val confirmExpected: ByteArray) {
        /** Constant-time check of the peer's confirmation MAC. */
        fun verifyPeer(confirm: ByteArray) = MessageDigest.isEqual(confirm, confirmExpected)
    }

    private val secret: BigInteger = random()
    /** shareP (Prover) or shareV (Verifier), uncompressed SEC1. */
    val share: ByteArray = run {
        val blind = if (role == Role.PROVER) M else N
        G.multiply(secret).add(blind.multiply(w0)).normalize().getEncoded(false)
    }

    /** Process the peer's share and derive the keys. Throws if the peer's point is invalid. */
    fun finish(peerShare: ByteArray): Result {
        val peer = decodeValid(peerShare)
        val (z, v) = when (role) {
            Role.PROVER -> {
                val t = peer.subtract(N.multiply(w0))
                t.multiply(secret) to t.multiply(w1!!)
            }
            Role.VERIFIER -> {
                val t = peer.subtract(M.multiply(w0))
                t.multiply(secret) to l!!.multiply(secret)
            }
        }
        val shareP = if (role == Role.PROVER) share else peerShare
        val shareV = if (role == Role.PROVER) peerShare else share
        val tt = transcript(shareP, shareV, z.normalize().getEncoded(false), v.normalize().getEncoded(false))
        val kMain = HashAlg.SHA256.digest(tt)
        val confirmKeys = HashAlg.SHA256.hkdf(null, kMain, "ConfirmationKeys".toByteArray(), 64)
        val kConfirmP = confirmKeys.copyOfRange(0, 32)
        val kConfirmV = confirmKeys.copyOfRange(32, 64)
        val kShared = HashAlg.SHA256.hkdf(null, kMain, "SharedKey".toByteArray(), 32)
        val confirmP = HashAlg.SHA256.hmac(kConfirmP, shareV)
        val confirmV = HashAlg.SHA256.hmac(kConfirmV, shareP)
        return if (role == Role.PROVER) Result(kShared, confirmP, confirmV) else Result(kShared, confirmV, confirmP)
    }

    internal fun transcript(shareP: ByteArray, shareV: ByteArray, z: ByteArray, v: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun put(b: ByteArray) {
            var n = b.size.toLong()
            repeat(8) { out.write((n and 0xff).toInt()); n = n ushr 8 }
            out.write(b)
        }
        put(context); put(idProver); put(idVerifier)
        put(M.getEncoded(false)); put(N.getEncoded(false))
        put(shareP); put(shareV); put(z); put(v)
        put(scalarBytes(w0))
        return out.toByteArray()
    }

    companion object {
        private val curve = ECNamedCurveTable.getByName("P-256")
        val G: ECPoint = curve.g
        val ORDER: BigInteger = curve.n
        val M: ECPoint = curve.curve.decodePoint(hexToBytes("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
        val N: ECPoint = curve.curve.decodePoint(hexToBytes("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

        private val rng = SecureRandom()
        private fun randomScalar(): BigInteger {
            while (true) {
                val k = BigInteger(ORDER.bitLength() + 64, rng).mod(ORDER)
                if (k.signum() != 0) return k
            }
        }

        fun prover(context: ByteArray, idProver: ByteArray, idVerifier: ByteArray, w0: BigInteger, w1: BigInteger,
                   random: () -> BigInteger = ::randomScalar) =
            Spake2Plus(Role.PROVER, context, idProver, idVerifier, w0, w1, null, random)

        fun verifier(context: ByteArray, idProver: ByteArray, idVerifier: ByteArray, w0: BigInteger, l: ByteArray,
                     random: () -> BigInteger = ::randomScalar) =
            Spake2Plus(Role.VERIFIER, context, idProver, idVerifier, w0, null, decodeValid(l), random)

        /** L = w1*P, the Verifier's registration record. */
        fun registrationRecord(w1: BigInteger): ByteArray = G.multiply(w1).normalize().getEncoded(false)

        /**
         * w0, w1 from PBKDF output, each half reduced mod p (RFC 9383 §3.2). Each half must be at
         * least 40 bytes for P-256 so the bias stays below 2^-64.
         */
        fun scalarsFrom(pbkdfOutput: ByteArray): Pair<BigInteger, BigInteger> {
            require(pbkdfOutput.size >= 80 && pbkdfOutput.size % 2 == 0)
            val h = pbkdfOutput.size / 2
            return BigInteger(1, pbkdfOutput.copyOfRange(0, h)).mod(ORDER) to
                BigInteger(1, pbkdfOutput.copyOfRange(h, pbkdfOutput.size)).mod(ORDER)
        }

        private fun decodeValid(encoded: ByteArray): ECPoint {
            val p = curve.curve.decodePoint(encoded) // checks the point is on the curve
            require(!p.isInfinity && p.isValid) { "invalid SPAKE2+ share" }
            return p // P-256 has cofactor 1, so on-curve means prime-order subgroup
        }

        private fun scalarBytes(k: BigInteger): ByteArray {
            val raw = k.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }

        private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
