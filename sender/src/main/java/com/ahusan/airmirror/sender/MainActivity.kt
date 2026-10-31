package com.ahusan.airmirror.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Discovery.Receiver>()
    private val lastSeen = HashMap<String, Long>()
    private var discovery: Discovery? = null
    private var chosen: Discovery.Receiver? = null
    private var pendingUpdate: Updater.Info? = null

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var stopBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var empty: TextView

    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        MirrorService.listener = { on, error -> refresh(); if (error != null) toast(error) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        checkUpdate(silent = true)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        discovery = Discovery(this) { r -> ui.post { onFound(r) } }.also { it.start() }
        ui.post(pruneTask)
    }

    override fun onPause() {
        discovery?.stop()
        discovery = null
        ui.removeCallbacks(pruneTask)
        super.onPause()
    }

    override fun onDestroy() {
        MirrorService.listener = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(20))
        }
        root.addView(TextView(this).apply {
            text = "AirMirror"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Mirror this phone's screen and sound to a TV or PC running AirMirror (same Wi-Fi)."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 15f
            setPadding(0, px(6), 0, px(18))
        })
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#8FB8DE"))
            textSize = 15f
            setPadding(0, 0, 0, px(10))
        }
        root.addView(status)
        stopBtn = bigButton("Stop mirroring", "#C0392B") { stopMirroring() }
        root.addView(stopBtn)

        root.addView(TextView(this).apply {
            text = "Devices"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(16), 0, px(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        empty = TextView(this).apply {
            text = "Searching… Open AirMirror on your TV or PC."
            setTextColor(Color.parseColor("#78909C"))
            textSize = 15f
            setPadding(0, px(8), 0, px(8))
        }
        root.addView(empty)

        root.addView(smallButton("Enter TV / PC IP address") { manualDialog() })
        updateBtn = smallButton("Check for updates") { onUpdateClicked() }
        root.addView(updateBtn)
        root.addView(TextView(this).apply {
            text = "Sound needs Android 10 or newer. Protected apps (Netflix etc.) may show black.\nBuild ${Updater.currentBuild(this@MainActivity)}"
            setTextColor(Color.parseColor("#607D8B"))
            textSize = 12f
            setPadding(0, px(18), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1320"))
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    private fun rounded(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = px(14).toFloat()
    }

    private fun bigButton(text: String, color: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        background = rounded(color)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, px(58)).apply { bottomMargin = px(10) }
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.parseColor("#2D9CDB"))
        setBackgroundColor(Color.TRANSPARENT)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, px(48))
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        val on = MirrorService.running
        stopBtn.visibility = if (on) View.VISIBLE else View.GONE
        status.text = if (on) "● Mirroring to ${MirrorService.target}" else "Tap a device to start mirroring."
        list.removeAllViews()
        for (r in found.values) {
            val icon = if (r.kind == "pc") "🖥" else "📺"
            list.addView(bigButton("$icon  ${r.name}", "#1B2B42") { startMirroring(r) })
        }
        empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onFound(r: Discovery.Receiver) {
        val key = "${r.host}:${r.port}"
        lastSeen[key] = System.currentTimeMillis()
        if (found[key] != r) {
            found[key] = r
            refresh()
        }
    }

    private val pruneTask = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val gone = lastSeen.filter { now - it.value > 9000 }.keys
            if (gone.isNotEmpty()) {
                gone.forEach { found.remove(it); lastSeen.remove(it) }
                refresh()
            }
            ui.postDelayed(this, 3000)
        }
    }

    private fun manualDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getPreferences(Context.MODE_PRIVATE).getString("ip", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("TV / PC IP address")
            .setMessage("Shown on the AirMirror screen of the TV, or in the PC log.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    getPreferences(Context.MODE_PRIVATE).edit().putString("ip", ip).apply()
                    startMirroring(Discovery.Receiver(ip, ip, Discovery.STREAM_PORT, "tv"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ mirroring

    private fun startMirroring(r: Discovery.Receiver) {
        if (MirrorService.running) {
            if (MirrorService.target == r.name) return
            stopMirroring()
        }
        chosen = r
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)   // needed to capture app sound
            return
        }
        askCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                toast("Without this permission the screen mirrors but sound stays on the phone.")
            }
            askCapture()
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7) return
        val r = chosen ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            toast("Screen capture was not allowed.")
            return
        }
        val i = Intent(this, MirrorService::class.java)
            .setAction(MirrorService.ACTION_START)
            .putExtra(MirrorService.EXTRA_RESULT, resultCode)
            .putExtra(MirrorService.EXTRA_DATA, data)
            .putExtra(MirrorService.EXTRA_HOST, r.host)
            .putExtra(MirrorService.EXTRA_PORT, r.port)
            .putExtra(MirrorService.EXTRA_TARGET, r.name)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        ui.postDelayed({ refresh() }, 300)
    }

    private fun stopMirroring() {
        startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
    }

    // ------------------------------------------------------------------ updates

    private fun onUpdateClicked() {
        val info = pendingUpdate
        if (info == null) checkUpdate(silent = false) else downloadUpdate(info)
    }

    private fun checkUpdate(silent: Boolean) {
        if (!silent) updateBtn.text = "Checking…"
        Thread {
            val res = try { Result.success(Updater.check(this, "AirMirror-Sender.apk")) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                val info = res.getOrNull()
                pendingUpdate = info
                updateBtn.text = if (info != null) "⬆ Install update (build ${info.build})" else "Check for updates"
                if (!silent) when {
                    info != null -> {}
                    res.isFailure -> toast("Couldn't check for updates. Check the internet.")
                    else -> toast("AirMirror Sender is up to date.")
                }
            }
        }.start()
    }

    private fun downloadUpdate(info: Updater.Info) {
        updateBtn.isEnabled = false
        Thread {
            try {
                val apk = Updater.download(this, info) { p -> ui.post { updateBtn.text = "Downloading… $p%" } }
                ui.post {
                    updateBtn.isEnabled = true
                    updateBtn.text = "⬆ Install update (build ${info.build})"
                    if (!Updater.install(this, apk)) toast("Allow installing from AirMirror Sender, then tap Install again.")
                }
            } catch (e: Exception) {
                ui.post {
                    updateBtn.isEnabled = true
                    updateBtn.text = "⬆ Install update (build ${info.build})"
                    toast("Download failed. Try again.")
                }
            }
        }.start()
    }
}
