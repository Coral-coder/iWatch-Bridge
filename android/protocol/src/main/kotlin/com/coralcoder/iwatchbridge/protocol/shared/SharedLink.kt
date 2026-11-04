package com.coralcoder.iwatchbridge.protocol.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * "Shared with iPhone" mode: the watch stays paired to its iPhone, and the iWatch Bridge watch
 * app links to this Android phone over Bluetooth LE with ordinary GATT, which both platforms allow
 * apps to use. The Android phone is the GATT server (peripheral); the watch app is the client.
 *
 * Wire format (mirrored in watchos/Shared/SharedLink.swift):
 *  - service [SERVICE], characteristics [TO_WATCH] (notify) and [TO_PHONE] (write)
 *  - each message is JSON (one [Message]), split into chunks that fit the ATT MTU;
 *    chunk = flags byte (bit 0: more chunks follow) + bytes.
 */
object SharedLink {
    const val SERVICE = "7d2a0001-5c4b-4f0e-9b1a-6a3e2f9c1b00"
    const val TO_WATCH = "7d2a0002-5c4b-4f0e-9b1a-6a3e2f9c1b00"
    const val TO_PHONE = "7d2a0003-5c4b-4f0e-9b1a-6a3e2f9c1b00"
    const val PROTOCOL_VERSION = 1

    val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t"; encodeDefaults = false }

    fun encode(m: Message): ByteArray = json.encodeToString(Message.serializer(), m).toByteArray()
    fun decode(b: ByteArray): Message = json.decodeFromString(Message.serializer(), b.decodeToString())

    /** Split a message into chunks of at most [maxChunk] bytes (ATT MTU - 3). */
    fun chunk(payload: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk >= 2)
        val room = maxChunk - 1
        if (payload.isEmpty()) return listOf(byteArrayOf(0))
        return (payload.indices step room).map { o ->
            val end = minOf(payload.size, o + room)
            byteArrayOf(if (end < payload.size) 1 else 0) + payload.copyOfRange(o, end)
        }
    }

    /** Reassembles chunks from one peer. */
    class Reassembler(private val limit: Int = 256 * 1024) {
        private val buf = ByteArrayOutputStream()
        fun accept(chunk: ByteArray): ByteArray? {
            if (chunk.isEmpty()) return null
            buf.write(chunk, 1, chunk.size - 1)
            if (buf.size() > limit) { buf.reset(); return null }
            if (chunk[0].toInt() and 1 == 1) return null
            return buf.toByteArray().also { buf.reset() }
        }
    }
}

@Serializable
sealed class Message {
    // ---- phone -> watch
    @Serializable @SerialName("phone")
    data class PhoneStatus(val name: String, val battery: Int, val charging: Boolean, val version: Int = SharedLink.PROTOCOL_VERSION) : Message()

    @Serializable @SerialName("notif")
    data class Notification(
        val id: String, val app: String, val appName: String, val title: String, val body: String,
        val time: Long, val canReply: Boolean = false, val actions: List<String> = emptyList(),
    ) : Message()

    @Serializable @SerialName("notifGone")
    data class NotificationRemoved(val id: String) : Message()

    @Serializable @SerialName("media")
    data class NowPlaying(val title: String, val artist: String, val app: String, val playing: Boolean, val volume: Int) : Message()

    // ---- watch -> phone
    @Serializable @SerialName("watch")
    data class WatchHello(val name: String, val model: String, val os: String, val battery: Int = -1, val version: Int = SharedLink.PROTOCOL_VERSION) : Message()

    @Serializable @SerialName("act")
    data class NotificationAction(val id: String, val action: String, val text: String? = null) : Message()

    @Serializable @SerialName("mediaCmd")
    data class MediaCommand(val cmd: String) : Message() {
        companion object { const val PLAY_PAUSE = "playPause"; const val NEXT = "next"; const val PREVIOUS = "previous"; const val VOL_UP = "volUp"; const val VOL_DOWN = "volDown" }
    }

    @Serializable @SerialName("find")
    data class FindPhone(val ring: Boolean) : Message()

    @Serializable @SerialName("ping")
    data object Ping : Message()
}
