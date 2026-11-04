package net.rec0de.android.watchwitch.nwsc

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom

/**
 * iWatch-Bridge replacement for WatchWitch's Android-bound NWSCManager: only the state that
 * [NWSCPacket] needs (sequence numbers and the local NWSC identity key). The connection
 * handling lives in com.coralcoder.iwatchbridge.protocol.nwsc, one instance per paired watch;
 * it calls [bind] with that watch's key before building packets.
 */
object NWSCManager {
    @Volatile
    var localPublicKey: Ed25519PublicKeyParameters = Ed25519KeyPairGenerator().run {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
        generateKeyPair().public as Ed25519PublicKeyParameters
    }
        private set

    // As in WatchWitch: sequence numbers start at the current time in microseconds, so they keep
    // increasing across restarts without being persisted.
    private var sequence = microsNow()

    fun bind(publicKey: Ed25519PublicKeyParameters) = synchronized(this) {
        localPublicKey = publicKey
        sequence = maxOf(sequence, microsNow())
    }

    fun freshSequenceNumber(): Long = synchronized(this) { sequence++ }

    private fun microsNow(): Long = java.time.Instant.now().let { it.epochSecond * 1_000_000 + it.nano / 1000 }
}
