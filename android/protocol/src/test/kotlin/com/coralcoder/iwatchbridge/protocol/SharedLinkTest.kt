package com.coralcoder.iwatchbridge.protocol

import com.coralcoder.iwatchbridge.protocol.shared.Message
import com.coralcoder.iwatchbridge.protocol.shared.SharedLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLinkTest {
    @Test fun wireFormatIsStable() {
        // The Swift side decodes exactly this shape; changing it needs a protocol version bump.
        assertEquals("""{"t":"find","ring":true}""", SharedLink.encode(Message.FindPhone(true)).decodeToString())
        assertEquals("""{"t":"ping"}""", SharedLink.encode(Message.Ping).decodeToString())
        val n = SharedLink.decode("""{"t":"act","id":"k1","action":"reply","text":"On my way","extra":1}""".toByteArray())
        assertEquals(Message.NotificationAction("k1", "reply", "On my way"), n)
    }

    @Test fun chunkingRoundTrip() {
        val m = Message.Notification("0|com.whatsapp|1", "com.whatsapp", "WhatsApp", "Sam", "x".repeat(700), 123L, canReply = true)
        val chunks = SharedLink.chunk(SharedLink.encode(m), 182)
        assertTrue(chunks.size > 1 && chunks.all { it.size <= 182 })
        val r = SharedLink.Reassembler()
        chunks.dropLast(1).forEach { assertNull(r.accept(it)) }
        assertEquals(m, SharedLink.decode(r.accept(chunks.last())!!))
    }
}
