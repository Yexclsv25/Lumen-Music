package com.yexclsv.localfiles.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String,
    val sizeBytes: Long
)

sealed class UpdateResult {
    object UpToDate : UpdateResult()
    data class Available(val info: UpdateInfo) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

/** Checks GitHub Releases for a newer build, downloads the APK and hands it to the system installer. */
object Updater {
    const val REPO = "Yexclsv25/Lumen-Music"
    const val REPO_URL = "https://github.com/$REPO"

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Lumen-Updater")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val c = open("https://api.github.com/repos/$REPO/releases/latest")
            when (val code = c.responseCode) {
                200 -> Unit
                404 -> return@withContext UpdateResult.Error("No releases published yet")
                403, 429 -> return@withContext UpdateResult.Error("GitHub rate limit reached, try again later")
                else -> return@withContext UpdateResult.Error("GitHub returned $code")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val latest = j.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (latest.isEmpty()) return@withContext UpdateResult.Error("Release has no version tag")

            var apk: String? = null
            var size = 0L
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (isNewer(latest, currentVersion)) {
                UpdateResult.Available(UpdateInfo(latest, j.optString("body"), apk, j.optString("html_url", REPO_URL), size))
            } else UpdateResult.UpToDate
        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "Network error")
        }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(s: String) = Regex("\\d+").findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList()
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until max(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the APK into the app cache; [onProgress] gets 0..1 on the main thread. */
    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: suspend (Float) -> Unit): File {
        val url = info.apkUrl ?: throw IllegalStateException("This release has no APK attached")
        val dir = File(ctx.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = File(dir, "Lumen-${info.version}.apk")
        withContext(Dispatchers.IO) {
            val c = open(url)
            if (c.responseCode != 200) throw IllegalStateException("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastReport > 100) {
                            lastReport = now
                            withContext(Dispatchers.Main) { onProgress((done.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
        }
        onProgress(1f)
        return out
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Activity) {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        )
    }

    fun install(ctx: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
