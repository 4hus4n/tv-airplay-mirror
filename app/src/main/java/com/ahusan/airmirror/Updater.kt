package com.ahusan.airmirror

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK, downloads it and opens the installer. */
object Updater {

    private const val LATEST = "https://api.github.com/repos/4hus4n/tv-airplay-mirror/releases/latest"

    class Info(val build: Int, val url: String, val size: Long)

    fun currentBuild(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    /** Returns update info if a newer build exists, null if up to date. Throws on network error. */
    fun check(ctx: Context, assetName: String): Info? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AirMirror")
        val json = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(json)
        val build = o.optString("tag_name").substringAfterLast('.').toIntOrNull() ?: return null
        if (build <= currentBuild(ctx)) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == assetName) {
                return Info(build, a.optString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    fun download(ctx: Context, info: Info, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update-${info.build}.apk")
        var url = URL(info.url)
        var c: HttpURLConnection
        var redirects = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "AirMirror")
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                redirects++
                continue
            }
            if (code != 200) throw IllegalStateException("download failed (HTTP $code)")
            break
        }
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
        }
        c.disconnect()
        return out
    }

    /** Returns false if the user first has to allow installing from this app. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
