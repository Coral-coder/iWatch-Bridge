package com.coralcoder.iwatchbridge.protocol.host

import com.coralcoder.iwatchbridge.protocol.hci.HciPacket
import kotlinx.coroutines.flow.Flow

/**
 * A Bluetooth controller that iWatch-Bridge owns exclusively. Implementations:
 *  - UsbHciTransport (app): a USB Bluetooth adapter on USB-C/OTG. No root, and the phone's own
 *    Bluetooth keeps working for headphones and everything else.
 *  - RootHalTransport (app + magisk/): the phone's built-in controller via the Bluetooth HAL,
 *    reached through the root helper. Android's own Bluetooth must be off while it runs.
 */
interface HciTransport {
    val name: String
    /** Packets from the controller (events, ACL). Completes when the transport closes. */
    val incoming: Flow<HciPacket>
    suspend fun open()
    suspend fun send(packet: HciPacket)
    suspend fun close()
}
