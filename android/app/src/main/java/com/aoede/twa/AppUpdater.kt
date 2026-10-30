package com.aoede.twa

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class UpdateFileProvider : FileProvider()

/** Only the official release can supply an APK. The configured music server supplies no install URL. */
class AppUpdater(private val context: Context, private val publish: (String) -> Unit) {
    data class Update(val version: String, val code: Int, val url: String, val size: Long, val digest: String)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var checked = 0L
    private var checking = false
    private var downloading = false
    private var available: Update? = null
    val availableUpdate: Update? get() = available
    var onUpdateAvailable: ((Update?) -> Unit)? = null
    private var pending: Pair<File, Update>? = null

    companion object {
        private const val repository = "https://github.com/sPROFFEs/Aoede/releases/download/"
        private const val maximum = 100L * 1024 * 1024

        internal fun parseRelease(data: JSONObject, installed: Int): Update? {
            if (data.optBoolean("draft") || data.optBoolean("prerelease")) return null
            val tag = data.optString("tag_name")
            require(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}").matches(tag))
            val assets = data.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") != "aoede-android.apk" || asset.optString("state") != "uploaded") continue
                val label = Regex("Aoede Android (\\d+\\.\\d+\\.\\d+) \\(([1-9]\\d*)\\)").matchEntire(asset.optString("label")) ?: continue
                val code = label.groupValues[2].toIntOrNull() ?: continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                val size = asset.optLong("size")
                if (code <= installed || size !in 1..maximum || !Regex("[a-f0-9]{64}").matches(digest)) continue
                return Update(label.groupValues[1], code, "$repository$tag/aoede-android.apk", size, digest)
            }
            return null
        }

        internal fun parseAtomFeed(xml: String, installed: Int): Update? {
            val entry = xml.substringAfter("<entry>", "").substringBefore("</entry>")
            if (entry.isBlank()) return null
            val tagMatch = Regex("""releases/tag/([A-Za-z0-9][A-Za-z0-9._+-]{0,79})""").find(entry) ?: return null
            val tag = tagMatch.groupValues[1]
            val labelMatch = Regex("""Android (\d+\.\d+\.\d+) \((\d+)\)""").find(entry) ?: return null
            val version = labelMatch.groupValues[1]
            val code = labelMatch.groupValues[2].toIntOrNull() ?: return null
            if (code <= installed) return null
            return Update(version, code, "$repository$tag/aoede-android.apk", 0L, "")
        }

        internal fun parseExpandedAssetsDigest(html: String): String {
            val match = Regex("""aoede-android\.apk[\s\S]*?sha256:([a-f0-9]{64})""").find(html)
            return match?.groupValues?.get(1).orEmpty()
        }

        internal fun allowedUrl(url: URL): Boolean = url.protocol == "https" && url.userInfo == null &&
            (url.port == -1 || url.port == 443) && url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

        @Suppress("DEPRECATION")
        internal fun compatible(candidate: PackageInfo, installed: PackageInfo, code: Int): Boolean {
            fun signatures(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { it.toCharsString() }?.toSet().orEmpty()
            val signatures = signatures(installed)
            val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
            return candidate.packageName == "com.aoede.twa" && candidate.packageName == installed.packageName &&
                version == code.toLong() && code > BuildConfig.VERSION_CODE && signatures.isNotEmpty() && signatures == signatures(candidate)
        }
    }

    private fun read(url: String, limit: Long, consume: (java.io.InputStream) -> Unit) {
        var target = URL(url)
        val deadline = android.os.SystemClock.elapsedRealtime() + 300000
        repeat(6) {
            require(allowedUrl(target)) { "Invalid update download host" }
            val connection = target.openConnection() as javax.net.ssl.HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.setRequestProperty("Accept", when (target.host) { "api.github.com" -> "application/vnd.github+json"; "github.com" -> "*/*"; else -> "application/octet-stream" })
                connection.setRequestProperty("User-Agent", "AoedeAndroid/${BuildConfig.VERSION_NAME}")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    target = URL(target, connection.getHeaderField("Location") ?: error("Missing download redirect"))
                } else {
                    check(status == 200) { "Update service returned HTTP $status" }
                    require((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1) <= limit) { "Update file is too large" }
                    connection.inputStream.use { input ->
                        var count = 0L
                        val bounded = object : java.io.FilterInputStream(input) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Update download timed out" }
                                val bytes = super.read(buffer, offset, length)
                                if (bytes > 0) { count += bytes; require(count <= limit) { "Update file is too large" } }
                                return bytes
                            }
                            override fun read(): Int {
                                val buffer = ByteArray(1)
                                return if (read(buffer, 0, 1) == -1) -1 else buffer[0].toInt() and 255
                            }
                        }
                        consume(bounded)
                    }
                    return
                }
            } finally { connection.disconnect() }
        }
        error("Too many update redirects")
    }

    fun check(after: () -> Unit = {}) {
        if (checking) return
        if (System.currentTimeMillis() - checked < 300000) { deliver(); after(); return }
        checking = true
        worker.execute {
            var update: Update? = null
            var success = false
            val apiResult = runCatching {
                var data = ""
                read("https://api.github.com/repos/sPROFFEs/Aoede/releases/latest", 512 * 1024) { data = it.bufferedReader().readText() }
                parseRelease(JSONObject(data), BuildConfig.VERSION_CODE)
            }
            if (apiResult.isSuccess) {
                success = true
                update = apiResult.getOrNull()
            } else {
                val fallbackResult = runCatching {
                    var atomXml = ""
                    read("https://github.com/sPROFFEs/Aoede/releases.atom", 256 * 1024) { atomXml = it.bufferedReader().readText() }
                    val candidate = parseAtomFeed(atomXml, BuildConfig.VERSION_CODE)
                    if (candidate != null) {
                        val tag = candidate.url.substringAfter(repository).substringBefore("/aoede-android.apk")
                        var assetsHtml = ""
                        runCatching {
                            read("https://github.com/sPROFFEs/Aoede/releases/expanded_assets/$tag", 256 * 1024) { assetsHtml = it.bufferedReader().readText() }
                        }
                        val digest = parseExpandedAssetsDigest(assetsHtml)
                        candidate.copy(digest = digest)
                    } else null
                }
                if (fallbackResult.isSuccess) {
                    success = true
                    update = fallbackResult.getOrNull()
                }
            }
            main.post {
                checking = false
                if (success) { checked = System.currentTimeMillis(); available = update }
                // Preserve a last successful result when offline; retry on the next foreground check.
                deliver()
                onUpdateAvailable?.invoke(available)
                after()
            }
        }
    }

    private fun deliver() {
        val files = JSONArray()
        available?.let { files.put(JSONObject().put("client", "android-native").put("platform", "android")
            .put("name", "aoede-android.apk").put("version", it.version).put("version_code", it.code).put("url", it.url)) }
        publish(JSONObject().put("release", JSONObject().put("downloads", files)).toString())
    }

    fun offer(activity: MainActivity) {
        if (downloading) { Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_LONG).show(); return }
        if (checking) { Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_LONG).show(); return }
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        val owner = java.lang.ref.WeakReference(activity)
        check {
            val current = owner.get()
            if (current != null && !current.isDestroyed && current.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                val update = available
                if (update == null) Toast.makeText(current, R.string.update_failed, Toast.LENGTH_LONG).show()
                else AlertDialog.Builder(current).setTitle(context.getString(R.string.update_title, update.version))
                    .setMessage(R.string.update_description).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.update_download) { _, _ -> download(current, update) }.show()
            }
        }
    }

    private fun download(activity: MainActivity, update: Update) {
        pending?.takeIf { it.second == update && it.first.isFile }?.let { install(activity); return }
        downloading = true
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_LONG).show()
        val owner = java.lang.ref.WeakReference(activity)
        worker.execute {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(directory, "download.part")
            val result = runCatching {
                val hash = MessageDigest.getInstance("SHA-256")
                val downloadLimit = if (update.size > 0L) update.size else maximum
                read(update.url, downloadLimit) { input ->
                    partial.outputStream().use { output ->
                        val bytes = ByteArray(32768)
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            hash.update(bytes, 0, count)
                            output.write(bytes, 0, count)
                        }
                    }
                }
                if (update.size > 0L) {
                    require(partial.length() == update.size) { "Update file size does not match" }
                }
                if (update.digest.isNotBlank()) {
                    require(hash.digest().joinToString("") { "%02x".format(it) } == update.digest) { "Update checksum does not match" }
                }
                verify(partial, update)
                val apk = File(directory, "aoede-update.apk")
                check(partial.renameTo(apk)) { "Cannot save update" }
                apk
            }
            partial.delete()
            main.post {
                downloading = false
                result.onSuccess { apk ->
                    pending = apk to update
                    val current = owner.get()
                    if (current != null && !current.isDestroyed && current.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) install(current)
                    else Toast.makeText(context, R.string.update_ready, Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File, update: Update) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = manager.getPackageArchiveInfo(file.path, flags) ?: error("Invalid APK")
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(candidate.versionName == update.version && compatible(candidate, installed, update.code)) { "Update package or signature does not match" }
    }

    fun install(activity: MainActivity) {
        val (file, _) = pending ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                activity.requestUpdatePermission()
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show() }
    }
}
