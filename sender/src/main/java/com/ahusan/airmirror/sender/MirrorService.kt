package com.ahusan.airmirror.sender

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Captures the phone screen (MediaProjection) and sound (Android 10+ playback capture),
 * encodes H.264 + AAC and streams them to an AirMirror receiver over TCP.
 */
class MirrorService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TARGET = "target"
        private const val TAG = "MirrorService"
        private const val CHANNEL = "mirror"

        @Volatile var running = false
            private set
        @Volatile var target = ""
            private set
        /** Called on the main thread when the mirroring state changes (message = error or null). */
        var listener: ((Boolean, String?) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var video: VideoEncoder? = null
    private var audio: AudioCapture? = null
    private var sender: PacketSender? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMirroring(null)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || running) return START_NOT_STICKY

        val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, Discovery.STREAM_PORT)
        target = intent.getStringExtra(EXTRA_TARGET) ?: host
        val code = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

        // must be in the foreground before the projection is created (Android 10+/14)
        val n = notification("Mirroring to $target")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
        running = true
        stopping = false
        notifyState(true, null)

        Thread({ start(host, port, code, data) }, "MirrorStart").start()
        return START_NOT_STICKY
    }

    private fun start(host: String, port: Int, code: Int, data: Intent?) {
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.sendBufferSize = 512 * 1024
            sock.connect(InetSocketAddress(host, port), 5000)
            val s = PacketSender(sock) { stopMirroring("Connection to $target lost") }
            sender = s
            s.send(1, (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).toByteArray(), 0)

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data ?: throw IllegalStateException("no permission"))
                ?: throw IllegalStateException("screen capture was not allowed")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopMirroring(null) }
            }, main)

            val (w, h, dpi) = captureSize()
            val enc = VideoEncoder(w, h) { bytes, pts, key -> s.sendVideo(bytes, pts, key) }
            video = enc
            s.onNeedKeyFrame = { video?.requestKeyFrame() }
            display = p.createVirtualDisplay("AirMirror", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, main)
            watchRotation()

            if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    audio = AudioCapture(p) { bytes, pts -> s.send(3, bytes, pts) }.also { it.start() }
                } catch (e: Exception) {
                    Log.w(TAG, "audio capture unavailable", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stopMirroring("Couldn't connect to $target. Is AirMirror open on it?")
        }
    }

    /** Re-creates the encoder at the new size when the phone rotates. */
    private fun watchRotation() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || stopping) return
                val old = video ?: return
                val (w, h, dpi) = captureSize()
                if (w == old.width && h == old.height) return
                val s = sender ?: return
                val enc = VideoEncoder(w, h) { bytes, pts, key -> s.sendVideo(bytes, pts, key) }
                video = enc
                display?.resize(w, h, dpi)
                display?.surface = enc.surface
                old.release()
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, main)
    }

    private fun captureSize(): Triple<Int, Int, Int> {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        var w = m.widthPixels
        var h = m.heightPixels
        val dpi = m.densityDpi
        // fit within 1920x1080 (either orientation), keep even multiples of 16
        val scale = minOf(1f, 1920f / max(w, h), 1080f / minOf(w, h))
        w = ((w * scale) / 16f).roundToInt() * 16
        h = ((h * scale) / 16f).roundToInt() * 16
        return Triple(max(w, 160), max(h, 160), dpi)
    }

    private fun stopMirroring(error: String?) {
        if (stopping) return
        stopping = true
        main.post {
            displayListener?.let { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
            displayListener = null
            Thread {
                try { audio?.release() } catch (_: Exception) {}
                try { video?.release() } catch (_: Exception) {}
                try { sender?.close() } catch (_: Exception) {}
            }.start()
            try { display?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            audio = null; video = null; sender = null; display = null; projection = null
            running = false
            notifyState(false, error)
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    private fun notifyState(on: Boolean, error: String?) {
        main.post { listener?.invoke(on, error) }
    }

    override fun onDestroy() {
        if (running) stopMirroring(null)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("AirMirror")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
            .build()
    }

    // ===================================================================== network

    /** Writes framed packets from a single thread; drops video if the network falls behind. */
    private class PacketSender(private val sock: Socket, private val onError: () -> Unit) {
        private class Pkt(val type: Int, val data: ByteArray, val pts: Long, val key: Boolean)
        private val queue = LinkedBlockingQueue<Pkt>()
        private val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 256 * 1024))
        @Volatile private var closed = false
        @Volatile private var waitKey = false
        var onNeedKeyFrame: (() -> Unit)? = null
        private val thread = Thread({ loop() }, "PacketSender").apply { start() }

        fun send(type: Int, data: ByteArray, pts: Long) {
            if (!closed) queue.offer(Pkt(type, data, pts, false))
        }

        fun sendVideo(data: ByteArray, pts: Long, key: Boolean) {
            if (closed) return
            if (queue.size > 45) {           // ~1.5 s behind: drop and resync on a keyframe
                queue.removeIf { it.type == 2 }
                waitKey = true
                onNeedKeyFrame?.invoke()
            }
            if (waitKey && !key) return
            waitKey = false
            queue.offer(Pkt(2, data, pts, key))
        }

        private fun loop() {
            var lastPing = System.currentTimeMillis()
            try {
                while (!closed) {
                    val p = queue.poll(500, TimeUnit.MILLISECONDS)
                    if (p != null) {
                        out.writeByte(p.type)
                        out.writeInt(p.data.size)
                        out.writeLong(p.pts)
                        out.write(p.data)
                    }
                    if (queue.isEmpty()) out.flush()
                    val now = System.currentTimeMillis()
                    if (now - lastPing > 2000) {
                        lastPing = now
                        out.writeByte(4); out.writeInt(0); out.writeLong(0); out.flush()
                    }
                }
            } catch (e: Exception) {
                if (!closed) {
                    closed = true
                    onError()
                }
            }
        }

        fun close() {
            closed = true
            try { sock.close() } catch (_: Exception) {}
            thread.join(1000)
        }
    }

    // ===================================================================== video

    private class VideoEncoder(val width: Int, val height: Int,
                               private val onPacket: (ByteArray, Long, Boolean) -> Unit) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val surface: Surface
        @Volatile private var running = true
        private var config: ByteArray? = null
        private val thread: Thread

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, if (width * height > 1280 * 720) 8_000_000 else 5_000_000)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)   // keep sending while the screen is still
            try {
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                f.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = codec.createInputSurface()
            codec.start()
            thread = Thread({ drain() }, "VideoEncoder").apply { start() }
        }

        fun requestKeyFrame() {
            try {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Exception) {}
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val i = codec.dequeueOutputBuffer(info, 10_000)
                    if (i < 0) continue
                    val buf = codec.getOutputBuffer(i)
                    if (buf != null && info.size > 0) {
                        val b = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(b)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            config = b            // SPS + PPS
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val cfg = config
                            // every keyframe carries SPS/PPS so a receiver can join any time
                            val payload = if (key && cfg != null) cfg + b else b
                            onPacket(payload, info.presentationTimeUs, key)
                        }
                    }
                    codec.releaseOutputBuffer(i, false)
                }
            } catch (_: Exception) {
            }
        }

        fun release() {
            running = false
            thread.join(500)
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { surface.release() } catch (_: Exception) {}
        }
    }

    // ===================================================================== audio (Android 10+)

    @SuppressLint("MissingPermission", "NewApi")
    private class AudioCapture(projection: MediaProjection, private val onPacket: (ByteArray, Long) -> Unit) {
        private val record: AudioRecord
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        @Volatile private var running = false
        private var thread: Thread? = null

        init {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(min, 4096) * 4)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()

            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        fun start() {
            codec.start()
            record.startRecording()
            running = true
            thread = Thread({ loop() }, "AudioCapture").apply { start() }
        }

        private fun loop() {
            val pcm = ByteArray(4096)
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            try {
                while (running) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n > 0) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            buf.clear()
                            val len = minOf(n, buf.capacity())
                            buf.put(pcm, 0, len)
                            codec.queueInputBuffer(i, 0, len, samples * 1_000_000 / RATE, 0)
                            samples += len / 4
                        }
                    }
                    while (true) {
                        val o = codec.dequeueOutputBuffer(info, 0)
                        if (o < 0) break
                        val ob = codec.getOutputBuffer(o)
                        if (ob != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val b = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(b)
                            onPacket(b, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(o, false)
                    }
                }
            } catch (_: Exception) {
            }
        }

        fun release() {
            running = false
            thread?.join(500)
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }

        companion object {
            const val RATE = 44100
        }
    }
}
