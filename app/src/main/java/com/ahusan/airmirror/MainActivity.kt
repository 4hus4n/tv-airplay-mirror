package com.ahusan.airmirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity(), AirPlayServer.Listener, VideoDecoder.Callback,
    SenderServer.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("airmirror", Context.MODE_PRIVATE) }

    private lateinit var videoFrame: AspectFrameLayout
    private lateinit var idleView: View
    private lateinit var deviceName: TextView
    private lateinit var statusText: TextView
    private lateinit var pinView: TextView
    private lateinit var toast: TextView
    private lateinit var codeBtn: Button
    private lateinit var updateBtn: Button

    private val server = AirPlayServer(this)
    private val video = VideoDecoder(this)
    private val audio = AudioPlayer()
    private val senderServer = SenderServer({ tvName() }, this)
    private var pendingUpdate: Updater.Info? = null
    private var updating = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serverExec = Executors.newSingleThreadExecutor()
    private var serverRunning = false
    private var mirroring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        videoFrame = findViewById(R.id.videoFrame)
        idleView = findViewById(R.id.idleView)
        deviceName = findViewById(R.id.deviceName)
        statusText = findViewById(R.id.statusText)
        pinView = findViewById(R.id.pinView)
        toast = findViewById(R.id.toast)
        codeBtn = findViewById(R.id.codeBtn)
        updateBtn = findViewById(R.id.updateBtn)
        updateBtn.setOnClickListener { onUpdateClicked() }

        findViewById<SurfaceView>(R.id.surface).holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = video.setSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = video.setSurface(null)
        })

        findViewById<Button>(R.id.renameBtn).setOnClickListener { showRenameDialog() }
        codeBtn.setOnClickListener {
            prefs.edit().putBoolean("requireCode", !requireCode()).apply()
            restartServer()
        }
        findViewById<Button>(R.id.renameBtn).requestFocus()
        refreshIdleTexts()
        checkForUpdate(silent = true)
    }

    override fun onStart() {
        super.onStart()
        hideSystemUi()
        acquireMulticast()
        video.start()
        audio.start()
        startServer()
        senderServer.start()
    }

    override fun onStop() {
        stopServer()
        senderServer.stop()
        video.stop()
        audio.stop()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        super.onStop()
    }

    // ------------------------------------------------------------------ server

    private fun startServer() {
        if (serverRunning) return
        statusText.text = "Starting…"
        val name = tvName()
        val mac = deviceId()
        val key = File(filesDir, "airplay.pem").absolutePath
        val code = requireCode()
        serverRunning = true
        serverExec.execute {
            val port = server.start(name, mac, key, 1920, 1080, 60, code)
            ui.post {
                if (port <= 0) serverRunning = false
                statusText.text = if (port > 0) "IP ${localIp() ?: "unknown"} · waiting for a device"
                else "Could not start receiver (error $port). Check Wi-Fi and reopen the app."
            }
        }
    }

    private fun stopServer() {
        if (!serverRunning) return
        serverRunning = false
        mirroring = false
        serverExec.execute { server.stop() }
    }

    private fun restartServer() {
        stopServer()
        refreshIdleTexts()
        startServer()
    }

    // ------------------------------------------------------------------ callbacks (background threads)

    override fun onVideoData(data: ByteArray, ptsUs: Long) = video.push(data, ptsUs)

    override fun onVideoSize(width: Int, height: Int) {
        video.setSourceSize(width, height)
    }

    override fun onAudioFormat(ct: Int, spf: Int) = audio.setFormat(ct)

    override fun onAudioData(data: ByteArray, ct: Int) = audio.push(data, ct)

    override fun onVolume(fraction: Float) = audio.setVolume(fraction)

    override fun onConnections(count: Int) {
        if (count == 0) ui.post {
            mirroring = false
            video.reset()
            audio.flush()
            pinView.visibility = View.GONE
            idleView.visibility = View.VISIBLE
            refreshIdleTexts()
            findViewById<Button>(R.id.renameBtn).requestFocus()
        }
    }

    override fun onClient(name: String) {
        ui.post { showToast("Connecting: $name") }
    }

    override fun onPin(pin: String) {
        ui.post {
            pinView.text = "Enter this code on your device\n$pin"
            pinView.textSize = 36f
            pinView.visibility = View.VISIBLE
            ui.removeCallbacks(hidePin)
            ui.postDelayed(hidePin, 60_000)
        }
    }

    override fun onFlushVideo() = video.reset()

    override fun onFlushAudio() = audio.flush()

    override fun onFirstFrame() {
        ui.post {
            mirroring = true
            pinView.visibility = View.GONE
            idleView.visibility = View.GONE
        }
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        ui.post { videoFrame.setAspectRatio(width, height) }
    }

    // ------------------------------------------------------------------ Android phones (AirMirror Sender)

    override fun onSenderConnected(phone: String) {
        video.reset()
        audio.setFormat(4)   // AAC-LC 44.1 kHz stereo
        ui.post { showToast("Connecting: $phone") }
    }

    override fun onSenderVideo(data: ByteArray, ptsUs: Long) = video.push(data, ptsUs)

    override fun onSenderAudio(data: ByteArray) = audio.push(data, 4)

    override fun onSenderDisconnected() = onConnections(0)

    // ------------------------------------------------------------------ updates

    private fun onUpdateClicked() {
        val info = pendingUpdate
        if (info == null) checkForUpdate(silent = false) else downloadUpdate(info)
    }

    private fun checkForUpdate(silent: Boolean) {
        if (updating) return
        if (!silent) updateBtn.text = "Checking…"
        Thread {
            val result = try { Result.success(Updater.check(this, "AirMirror.apk")) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                val info = result.getOrNull()
                pendingUpdate = info
                when {
                    info != null -> {
                        updateBtn.text = "Install update (build ${info.build})"
                        if (silent) showToast("A new AirMirror update is available")
                    }
                    result.isFailure -> {
                        updateBtn.text = "Check for updates"
                        if (!silent) showToast("Couldn't check for updates. Check the internet.")
                    }
                    else -> {
                        updateBtn.text = "Check for updates"
                        if (!silent) showToast("AirMirror is up to date (build ${Updater.currentBuild(this)})")
                    }
                }
            }
        }.start()
    }

    private fun downloadUpdate(info: Updater.Info) {
        if (updating) return
        updating = true
        updateBtn.text = "Downloading… 0%"
        Thread {
            try {
                val apk = Updater.download(this, info) { p -> ui.post { updateBtn.text = "Downloading… $p%" } }
                ui.post {
                    updating = false
                    updateBtn.text = "Install update (build ${info.build})"
                    if (!Updater.install(this, apk)) {
                        showToast("Allow AirMirror to install apps, then press Install again")
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    updating = false
                    updateBtn.text = "Install update (build ${info.build})"
                    showToast("Download failed. Try again.")
                }
            }
        }.start()
    }

    private val hidePin = Runnable { pinView.visibility = View.GONE }
    private val hideToast = Runnable { toast.visibility = View.GONE }

    // ------------------------------------------------------------------ UI

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && mirroring) {
            server.disconnect()
            senderServer.disconnect()
            showToast("Disconnected")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showToast(text: String) {
        toast.text = text
        toast.visibility = View.VISIBLE
        ui.removeCallbacks(hideToast)
        ui.postDelayed(hideToast, 3000)
    }

    private fun refreshIdleTexts() {
        deviceName.text = tvName()
        codeBtn.text = if (requireCode()) "Connection code: On" else "Connection code: Off"
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(tvName())
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("TV name shown on iPhone")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim().replace(Regex("[^\\p{L}\\p{N} '\\-]"), "")
                if (n.isNotEmpty()) {
                    prefs.edit().putString("name", n.take(40)).apply()
                    restartServer()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // ------------------------------------------------------------------ settings & network

    private fun requireCode() = prefs.getBoolean("requireCode", false)

    private fun tvName(): String {
        prefs.getString("name", null)?.let { return it }
        val sys = try { Settings.Global.getString(contentResolver, "device_name") } catch (_: Exception) { null }
        return (sys?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV")
            .replace(Regex("[^\\p{L}\\p{N} '\\-]"), "").take(40).ifBlank { "Android TV" }
    }

    /** Stable random MAC-style id; iPhones remember the TV by this. */
    private fun deviceId(): String {
        prefs.getString("deviceId", null)?.let { return it }
        val b = Random.nextBytes(6)
        b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()   // locally administered, unicast
        val id = b.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xff) }
        prefs.edit().putString("deviceId", id).apply()
        return id
    }

    private fun acquireMulticast() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("airmirror").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun localIp(): String? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
