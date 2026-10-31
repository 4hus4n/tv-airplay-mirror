package com.ahusan.airmirror.sender

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds AirMirror receivers (TV / PC) on the local network with a UDP broadcast. */
class Discovery(private val ctx: Context, private val onFound: (Receiver) -> Unit) {

    data class Receiver(val name: String, val host: String, val port: Int, val kind: String)

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "Discovery").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val socket = try {
            DatagramSocket().apply { broadcast = true; soTimeout = 400 }
        } catch (e: Exception) {
            return
        }
        val msg = "AIRMIRROR_DISCOVER".toByteArray()
        val buf = ByteArray(512)
        try {
            while (running) {
                for (target in broadcastTargets()) {
                    try { socket.send(DatagramPacket(msg, msg.size, target, PORT)) } catch (_: Exception) {}
                }
                val until = System.currentTimeMillis() + 2500
                while (running && System.currentTimeMillis() < until) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val parts = String(p.data, 0, p.length, Charsets.UTF_8).trim().split("|")
                        if (parts.size >= 4 && parts[0] == "AIRMIRROR") {
                            val port = parts[2].toIntOrNull() ?: continue
                            onFound(Receiver(parts[1], p.address.hostAddress ?: continue, port, parts[3]))
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.linkAddresses?.forEach { la ->
                val a = la.address
                if (a is Inet4Address && la.prefixLength in 8..30) {
                    val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
                    val mask = -1 shl (32 - la.prefixLength)
                    val bc = ip or mask.inv()
                    out.add(InetAddress.getByAddress(byteArrayOf(
                        (bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte())))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    companion object {
        const val PORT = 47101
        const val STREAM_PORT = 47100
    }
}
