package com.ahusan.airmirror

/**
 * Thin wrapper around the native AirPlay receiver (UxPlay library).
 * Native callbacks arrive on background threads.
 */
class AirPlayServer(private val listener: Listener) {

    interface Listener {
        fun onVideoData(data: ByteArray, ptsUs: Long)
        fun onVideoSize(width: Int, height: Int)
        fun onAudioFormat(ct: Int, spf: Int)
        fun onAudioData(data: ByteArray, ct: Int)
        fun onVolume(fraction: Float)
        fun onConnections(count: Int)
        fun onClient(name: String)
        fun onPin(pin: String)
        fun onFlushVideo()
        fun onFlushAudio()
    }

    companion object {
        init {
            System.loadLibrary("airmirror")
        }
    }

    /** Returns the TCP port (>0) on success, or a negative error code. */
    fun start(name: String, mac: String, keyFile: String, width: Int, height: Int, fps: Int, requireCode: Boolean): Int =
        nativeStart(name, mac, keyFile, width, height, fps, if (requireCode) 3 else 0, "")

    fun stop() = nativeStop()

    fun disconnect() = nativeDisconnect()

    private external fun nativeStart(
        name: String, mac: String, keyFile: String, width: Int, height: Int, fps: Int,
        pinMode: Int, password: String
    ): Int

    private external fun nativeStop()
    private external fun nativeDisconnect()

    // ---- called from native code ----
    @Suppress("unused") private fun onVideoData(data: ByteArray, ptsUs: Long) = listener.onVideoData(data, ptsUs)
    @Suppress("unused") private fun onVideoCodec(h265: Boolean) {}
    @Suppress("unused") private fun onVideoSize(w: Int, h: Int) = listener.onVideoSize(w, h)
    @Suppress("unused") private fun onAudioFormat(ct: Int, spf: Int) = listener.onAudioFormat(ct, spf)
    @Suppress("unused") private fun onAudioData(data: ByteArray, ct: Long) = listener.onAudioData(data, ct.toInt())
    @Suppress("unused") private fun onVolume(v: Float) = listener.onVolume(v)
    @Suppress("unused") private fun onConnections(n: Int) = listener.onConnections(n)
    @Suppress("unused") private fun onClient(name: String) = listener.onClient(name)
    @Suppress("unused") private fun onPin(pin: String) = listener.onPin(pin)
    @Suppress("unused") private fun onFlushVideo() = listener.onFlushVideo()
    @Suppress("unused") private fun onFlushAudio() = listener.onFlushAudio()
}
