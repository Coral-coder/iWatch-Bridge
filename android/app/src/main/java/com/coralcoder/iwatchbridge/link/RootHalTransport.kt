package com.coralcoder.iwatchbridge.link

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.coralcoder.iwatchbridge.protocol.hci.HciPacket
import com.coralcoder.iwatchbridge.protocol.host.HciTransport
import com.coralcoder.iwatchbridge.root.RootHciHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The phone's built-in Bluetooth controller, through the Bluetooth HAL. Needs root: the app
 * starts [RootHciHelper] as root (via `su` and app_process), which takes the HAL and relays
 * packets over a local socket. Android's Bluetooth must be off while this runs, since the HAL
 * serves one client.
 *
 * Socket framing: [indicator u8][length u32 big-endian][packet]; the helper also sends status
 * lines as indicator 0xFF.
 */
class RootHalTransport(private val context: Context) : HciTransport {
    override val name = "Built-in Bluetooth (root)"
    private val packets = Channel<HciPacket>(Channel.UNLIMITED)
    override val incoming: Flow<HciPacket> = packets.receiveAsFlow()
    private var socket: LocalSocket? = null
    private var out: DataOutputStream? = null
    private var process: Process? = null
    /** Diagnostics from the helper. */
    val status = Channel<String>(Channel.UNLIMITED)

    override suspend fun open() = withContext(Dispatchers.IO) {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH=$apk app_process /system/bin --nice-name=iwatchbridge-hci ${RootHciHelper::class.java.name} ${android.os.Process.myUid()}"
        process = try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw IOException("Root isn't available (no su). Use a USB Bluetooth adapter instead, or install the Magisk module.", e)
        }
        // Wait for the helper's socket.
        var s: LocalSocket? = null
        for (i in 0 until 50) {
            try {
                s = LocalSocket().apply { connect(LocalSocketAddress(RootHciHelper.SOCKET, LocalSocketAddress.Namespace.ABSTRACT)) }
                break
            } catch (_: IOException) {
                if (process?.isAlive == false) {
                    val msg = process!!.inputStream.bufferedReader().readText().trim()
                    throw IOException("Root helper exited: ${msg.ifEmpty { "su was denied" }}")
                }
                delay(200)
            }
        }
        val sock = s ?: throw IOException("Root helper didn't start")
        socket = sock
        out = DataOutputStream(sock.outputStream.buffered())
        Thread({
            val input = DataInputStream(sock.inputStream.buffered())
            try {
                while (true) {
                    val ind = input.readUnsignedByte()
                    val len = input.readInt()
                    val b = ByteArray(len).also { input.readFully(it) }
                    if (ind == 0xFF) status.trySend(String(b)) else packets.trySend(HciPacket.parse(ind, b))
                }
            } catch (_: IOException) {
                packets.close()
            }
        }, "hci-root-reader").apply { isDaemon = true }.start()
    }

    override suspend fun send(packet: HciPacket) = withContext(Dispatchers.IO) {
        val o = out ?: throw IOException("root transport closed")
        val body = packet.body()
        synchronized(o) { o.writeByte(packet.indicator); o.writeInt(body.size); o.write(body); o.flush() }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        process?.destroy()
        socket = null; out = null; process = null
    }
}
