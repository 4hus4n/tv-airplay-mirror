package com.ahusan.airmirror

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Receiver for Android phones running the "AirMirror Sender" app.
 *
 * Discovery: the phone broadcasts "AIRMIRROR_DISCOVER" to UDP 47101 and we reply
 * "AIRMIRROR|<name>|47100|tv".
 * Stream: TCP 47100, packets = [type:1][length:4][ptsUs:8][payload]
 *   1 = hello (UTF-8 phone name), 2 = H.264 Annex-B access unit,
 *   3 = AAC-LC raw frame (44.1 kHz stereo), 4 = keep-alive.
 */
class SenderServer(private val name: () -> String, private val listener: Listener) {

    interface Listener {
        fun onSenderConnected(phone: String)
        fun onSenderVideo(data: ByteArray, ptsUs: Long)
        fun onSenderAudio(data: ByteArray)
        fun onSenderDisconnected()
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var client: Socket? = null

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "SenderTcp").start()
        Thread({ discoveryLoop() }, "SenderUdp").start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        disconnect()
    }

    fun disconnect() {
        try { client?.close() } catch (_: Exception) {}
    }

    private fun discoveryLoop() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(DISCOVERY_PORT))
            udp = s
            val buf = ByteArray(512)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val msg = String(p.data, 0, p.length, Charsets.UTF_8).trim()
                if (msg.startsWith("AIRMIRROR_DISCOVER")) {
                    val reply = "AIRMIRROR|${name()}|$STREAM_PORT|tv".toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "discovery stopped", e)
        }
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(STREAM_PORT))
            server = ss
            while (running) {
                val sock = ss.accept()
                // a new phone takes over from the previous one
                disconnect()
                client = sock
                Thread({ handle(sock) }, "SenderClient").start()
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "accept stopped", e)
        }
    }

    private fun handle(sock: Socket) {
        var announced = false
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 10_000
            val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 256 * 1024))
            while (running && !sock.isClosed) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                val pts = input.readLong()
                if (len < 0 || len > 8 * 1024 * 1024) throw IllegalStateException("bad packet length $len")
                val data = ByteArray(len)
                input.readFully(data)
                when (type) {
                    1 -> {
                        announced = true
                        listener.onSenderConnected(String(data, Charsets.UTF_8))
                    }
                    2 -> listener.onSenderVideo(data, pts)
                    3 -> listener.onSenderAudio(data)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "phone disconnected: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Exception) {}
            if (client === sock) {
                client = null
                if (announced) listener.onSenderDisconnected()
            }
        }
    }

    companion object {
        private const val TAG = "SenderServer"
        const val STREAM_PORT = 47100
        const val DISCOVERY_PORT = 47101
    }
}
