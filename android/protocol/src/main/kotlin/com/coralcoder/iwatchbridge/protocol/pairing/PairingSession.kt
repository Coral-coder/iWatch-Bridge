package com.coralcoder.iwatchbridge.protocol.pairing

import com.coralcoder.iwatchbridge.protocol.host.AclLink
import com.coralcoder.iwatchbridge.protocol.host.HostStack
import com.coralcoder.iwatchbridge.protocol.host.SetupAdvertisement
import com.coralcoder.iwatchbridge.protocol.magnet.MagnetMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout

/**
 * Drives one watch setup, step by step (see [PairingStep]). Steps whose wire format is
 * implemented run for real; the first one that isn't stops with [NotYetSupported] naming it,
 * so the app never pretends to have paired.
 */
class PairingSession(
    private val host: HostStack,
    private val target: SetupAdvertisement,
) {
    private val _events = MutableSharedFlow<PairingEvent>(replay = 32, extraBufferCapacity = 32)
    val events: SharedFlow<PairingEvent> = _events.asSharedFlow()
    private val proof = CompletableDeferred<PairingProof>()
    private var step = PairingStep.DISCOVER

    /** The user scanned the watch or typed its code. */
    fun provide(p: PairingProof) { proof.complete(p) }

    private suspend fun enter(s: PairingStep) { step = s; _events.emit(PairingEvent.Progress(s)) }

    suspend fun run(): String? = try {
        enter(PairingStep.CONNECT_LINK)
        host.discover(false)
        val link = host.connect(target.address)

        enter(PairingStep.OPEN_PAIRING_CHANNEL)
        waitForService(link, MagnetMessage.SVC_TERMINUS_PAIRING)

        _events.emit(PairingEvent.NeedsUser(UserRequest.ScanOrEnterCode))
        val p = proof.await()

        enter(PairingStep.SETUP_IKE_INIT)
        throw NotYetSupported(
            PairingStep.SETUP_IKE_INIT,
            "uIKE IKE_SA_INIT on com.apple.terminusPairing isn't implemented yet " +
                "(proof: ${if (p is PairingProof.Pin) "6-digit code" else "optical code"}). See docs/ROADMAP.md.",
        )
    } catch (e: NotYetSupported) {
        _events.emit(PairingEvent.Failed(e.step, e.message ?: "", unsupported = true)); null
    } catch (e: Exception) {
        _events.emit(PairingEvent.Failed(step, e.message ?: e.toString())); null
    }

    /** Wait until the watch advertises (or has advertised) a Magnet service. */
    private suspend fun waitForService(link: AclLink, name: String, timeoutMs: Long = 15_000) {
        if (link.magnet.remoteServices.values.any { it.name == name }) return
        withTimeout(timeoutMs) {
            for (m in link.magnetMessages) {
                if (link.magnet.remoteServices.values.any { it.name == name }) return@withTimeout
                if (m is MagnetMessage.Raw && m.opcode == MagnetMessage.OP_ERROR) error("watch reported a Magnet error")
            }
            error("link closed before the watch offered $name")
        }
    }
}
