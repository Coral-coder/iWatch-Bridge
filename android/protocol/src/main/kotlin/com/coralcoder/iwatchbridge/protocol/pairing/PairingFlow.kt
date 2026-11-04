package com.coralcoder.iwatchbridge.protocol.pairing

/**
 * The steps of setting up a watch, in the order the iPhone performs them. The app shows the
 * same screens as the iPhone's Watch app for each step. Steps are grouped by protocol layer so
 * progress and failures can be reported precisely.
 *
 * Sources: the WatchWitch paper (arXiv 2507.07210) and observed behaviour of watchOS 26 setup.
 * See docs/PROTOCOL.md for what is confirmed and what still has to be confirmed.
 */
enum class PairingStep(val title: String, val layer: Layer) {
    DISCOVER("Looking for Apple Watch", Layer.BLUETOOTH),             // FE25 setup advertisement
    CONNECT_LINK("Connecting", Layer.BLUETOOTH),                      // BR/EDR ACL + Magnet on CID 0x3A
    OPEN_PAIRING_CHANNEL("Connecting", Layer.BLUETOOTH),              // com.apple.terminusPairing channels
    SETUP_IKE_INIT("Pairing", Layer.KEY_EXCHANGE),                    // uIKE IKE_SA_INIT (+ additional key exchange)
    AUTHENTICATE("Pairing", Layer.KEY_EXCHANGE),                      // optical code PSK, or SPAKE2+ with the 6-digit code
    EXCHANGE_IDENTITIES("Pairing", Layer.KEY_EXCHANGE),               // long-term keys in private notifies
    BOND("Pairing", Layer.BLUETOOTH),                                 // LE Secure Connections with OOB data
    NORMAL_LINK("Connecting", Layer.LINK),                            // NetworkRelay: IKE class C/D, ESP over ERTM
    IDS_SESSION("Connecting", Layer.SERVICES),                        // local IDS (Alloy) over NWSC
    ACTIVATION("Activating", Layer.ACTIVATION),                       // relay MobileActivation to Apple
    ACCOUNT_SIGN_IN("Apple Account", Layer.ACTIVATION),               // Activation Lock / Apple Account
    SETTINGS_SETUP("Setting up", Layer.SERVICES),                     // wrist, passcode, Siri, etc.
    INITIAL_SYNC("Syncing", Layer.SERVICES),                          // PairedSync: Wi-Fi, time, faces
    DONE("Ready", Layer.SERVICES);

    enum class Layer { BLUETOOTH, KEY_EXCHANGE, LINK, SERVICES, ACTIVATION }
}

/** How the user proves they're holding the watch. */
sealed interface PairingProof {
    /** The particle-cloud animation on the watch, read by the phone camera. */
    class Optical(val code: ByteArray) : PairingProof
    /** "Pair Manually": the six digits the watch shows after you pick it by name. */
    class Pin(val digits: String) : PairingProof {
        init { require(digits.length == 6 && digits.all(Char::isDigit)) { "PIN must be 6 digits" } }
    }
}

sealed interface PairingEvent {
    data class Progress(val step: PairingStep, val fraction: Float? = null) : PairingEvent
    data class NeedsUser(val request: UserRequest) : PairingEvent
    data class Failed(val step: PairingStep, val reason: String, val unsupported: Boolean = false) : PairingEvent
    data class Paired(val watchId: String) : PairingEvent
}

/** Prompts the app answers on the watch's behalf, matching the iPhone screens. */
sealed interface UserRequest {
    data object ScanOrEnterCode : UserRequest
    data class EnterPinShownOnWatch(val watchName: String) : UserRequest
    data object SetUpOrRestore : UserRequest
    data class ActivationLock(val maskedAccount: String) : UserRequest
    data object TermsAndConditions : UserRequest
    data object WristPreference : UserRequest
}

/** Thrown by steps whose wire format isn't implemented yet, so the UI can say exactly what's missing. */
class NotYetSupported(val step: PairingStep, detail: String) : Exception("${step.name}: $detail")
