package com.coralcoder.iwatchbridge.root

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs as root (started by RootHalTransport through `su` + app_process). Takes the vendor
 * Bluetooth HAL (AIDL `android.hardware.bluetooth.IBluetoothHci/default`, Android 13+ devices)
 * and relays raw HCI between it and the app over an abstract local socket.
 *
 * Only the app's own uid may connect: anything that can talk to this socket controls the radio.
 *
 * The HAL takes one client, so Android's Bluetooth must be switched off first. Transaction codes
 * follow the declaration order in AOSP's IBluetoothHci.aidl / IBluetoothHciCallbacks.aidl.
 */
object RootHciHelper {
    const val SOCKET = "iwatchbridge-hci"
    private const val HAL = "android.hardware.bluetooth.IBluetoothHci/default"
    private const val HAL_DESC = "android.hardware.bluetooth.IBluetoothHci"
    private const val CB_DESC = "android.hardware.bluetooth.IBluetoothHciCallbacks"

    // IBluetoothHci
    private const val TX_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 0
    private const val TX_INITIALIZE = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TX_SEND_ACL = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val TX_SEND_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 3
    // IBluetoothHciCallbacks
    private const val CB_ACL = IBinder.FIRST_CALL_TRANSACTION + 0
    private const val CB_EVENT = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val CB_INIT_COMPLETE = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val CB_ISO = IBinder.FIRST_CALL_TRANSACTION + 3
    private const val CB_SCO = IBinder.FIRST_CALL_TRANSACTION + 4
    // Stable-AIDL meta transactions
    private const val TX_GET_VERSION = 16777214
    private const val TX_GET_HASH = 16777213

    @Volatile private var out: DataOutputStream? = null
    private val initDone = CountDownLatch(1)
    @Volatile private var initStatus = -1

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args.firstOrNull()?.toIntOrNull() ?: throw IOException("usage: RootHciHelper <app uid>"))
        } catch (t: Throwable) {
            System.err.println("iwatchbridge-hci: ${t.message}")
            exitProcess(1)
        }
        exitProcess(0)
    }

    private fun run(allowedUid: Int) {
        if (sh("settings get global bluetooth_on").trim() == "1") {
            throw IOException("Turn off Bluetooth in Android settings first: the watch link needs the Bluetooth chip to itself.")
        }
        val hal = serviceManagerGet(HAL)
            ?: throw IOException("This phone has no AIDL Bluetooth HAL (needs Android 13+ firmware). Use a USB Bluetooth adapter.")
        System.err.println("HAL: ${runCatching { hal.interfaceDescriptor }.getOrNull()}")

        val callbacks = Callbacks()
        markVintf(callbacks)

        val server = LocalServerSocket(SOCKET)
        val client: LocalSocket = server.accept()
        val peer = client.peerCredentials
        if (peer.uid != allowedUid) throw IOException("refusing uid ${peer.uid}")
        val o = DataOutputStream(client.outputStream.buffered())
        out = o

        transact(hal, TX_INITIALIZE) { writeStrongBinder(callbacks) }
        if (!initDone.await(10, TimeUnit.SECONDS)) throw IOException("HAL didn't finish initializing")
        if (initStatus != 0) throw IOException("HAL initialize failed with status $initStatus (another stack may still own it)")
        status("ready")

        val input = DataInputStream(client.inputStream.buffered())
        try {
            while (true) {
                val ind = input.readUnsignedByte()
                val len = input.readInt()
                val b = ByteArray(len).also { input.readFully(it) }
                when (ind) {
                    0x01 -> transact(hal, TX_SEND_COMMAND) { writeByteArray(b) }
                    0x02 -> transact(hal, TX_SEND_ACL) { writeByteArray(b) }
                }
            }
        } catch (_: IOException) {
            // app went away
        } finally {
            runCatching { transact(hal, TX_CLOSE) {} }
            server.close()
        }
    }

    private class Callbacks : Binder() {
        init { attachInterface(null, CB_DESC) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TX_GET_VERSION -> { data.enforceInterface(CB_DESC); reply?.writeNoException(); reply?.writeInt(1); return true }
                TX_GET_HASH -> { data.enforceInterface(CB_DESC); reply?.writeNoException(); reply?.writeString("notfrozen"); return true }
                CB_ACL, CB_EVENT, CB_ISO, CB_SCO -> {
                    data.enforceInterface(CB_DESC)
                    val b = data.createByteArray() ?: ByteArray(0)
                    val ind = when (code) { CB_ACL -> 0x02; CB_EVENT -> 0x04; CB_SCO -> 0x03; else -> 0x05 }
                    forward(ind, b)
                    reply?.writeNoException()
                    return true
                }
                CB_INIT_COMPLETE -> {
                    data.enforceInterface(CB_DESC)
                    initStatus = data.readInt()
                    initDone.countDown()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun forward(indicator: Int, b: ByteArray) {
        val o = out ?: return
        synchronized(o) {
            try { o.writeByte(indicator); o.writeInt(b.size); o.write(b); o.flush() } catch (_: IOException) {}
        }
    }

    private fun status(s: String) = forward(0xFF, s.toByteArray())

    private fun transact(binder: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(HAL_DESC)
            data.write()
            binder.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun serviceManagerGet(name: String): IBinder? {
        val sm = Class.forName("android.os.ServiceManager")
        return runCatching { sm.getMethod("waitForDeclaredService", String::class.java).invoke(null, name) as IBinder? }.getOrNull()
            ?: sm.getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }

    /** The HAL is vendor code; it may only call back into binders marked with VINTF stability. */
    private fun markVintf(b: Binder) {
        runCatching { Binder::class.java.getMethod("markVintfStability").invoke(b) }
            .onFailure { System.err.println("markVintfStability unavailable: ${it.message}") }
    }

    private fun sh(cmd: String): String =
        ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
}
