package com.aoede.twa

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * A temporary, network-blocked WebView reads the old origin's IndexedDB; it never renders the app
 * or plays audio.
 */
class NativeMigration(private val app: AoedeApp) {
    private var view: WebView? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var file: File? = null
    private var output: FileOutputStream? = null
    private var digest: MessageDigest? = null
    private var metadata: JSONObject? = null
    private var expected = 0L
    private var received = 0L
    private var imported = 0
    private var record: java.io.ByteArrayOutputStream? = null
    private var recordKind = ""
    @Volatile private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    fun start(done: (String?, Throwable?) -> Unit) {
        if (!app.nativeApi.canImportLegacy) {
            done(
                null,
                SecurityException(
                    "The previous account session could not be verified. Original storage is preserved in the compatibility client."
                ),
            )
            return
        }
        if (view != null) {
            done(null, IllegalStateException("Migration already running"))
            return
        }
        val origin = app.nativeApi.origin ?: return
        val scope = app.nativeLibrary.scope()
        val username = app.nativeApi.username
        if (
            !runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
                .getOrDefault(false)
        ) {
            done(
                null,
                IllegalStateException(
                    "Update Android System WebView once to import the old library"
                ),
            )
            return
        }
        val nonce = UUID.randomUUID().toString()
        val web = WebView(app)
        view = web
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.blockNetworkLoads = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    true

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = true

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
            }
        fun finish(message: String?, error: Throwable?) {
            if (finished) return
            finished = true
            app.nativeApi.main.post {
                output?.close()
                output = null
                file?.delete()
                file = null
                record = null
                web.destroy()
                view = null
                done(message, error)
            }
        }
        WebViewCompat.addWebMessageListener(web, "AoedeMigration", setOf(origin.rule)) {
            _,
            message,
            source,
            mainFrame,
            reply ->
            if (
                !mainFrame ||
                    ServerOrigin.from(source) != origin ||
                    message.data.orEmpty().length > 128 * 1024
            ) {
                finish(null, SecurityException("Invalid migration message"))
                return@addWebMessageListener
            }
            worker.execute {
                if (finished) return@execute
                try {
                    require(app.nativeLibrary.scope() == scope && app.nativeApi.origin == origin)
                    val data = JSONObject(message.data!!)
                    require(data.optString("nonce") == nonce)
                    var response = "ok"
                    when (data.getString("type")) {
                        "track" -> {
                            require(output == null && record == null)
                            metadata = data.getJSONObject("metadata")
                            val id = NativeTrack.reference(metadata!!)
                            require(NativeTrack.localId(metadata!!) > 0 && id.length < 64)
                            expected = data.getLong("size")
                            require(expected in 1..2L * 1024 * 1024 * 1024)
                            require(
                                app.nativeLibrary.directory(scope).usableSpace >
                                    expected + 32L * 1024 * 1024
                            )
                            file =
                                File(
                                    app.nativeLibrary.directory(scope),
                                    NativeApi.digest(id) + ".migration.partial",
                                )
                            output = FileOutputStream(file!!)
                            digest = MessageDigest.getInstance("SHA-256")
                            received = 0
                        }
                        "chunk" -> {
                            require(output != null && data.getLong("offset") == received)
                            val bytes =
                                android.util.Base64.decode(
                                    data.getString("data"),
                                    android.util.Base64.NO_WRAP,
                                )
                            require(bytes.size in 1..48 * 1024 && received + bytes.size <= expected)
                            output!!.write(bytes)
                            digest!!.update(bytes)
                            received += bytes.size
                        }
                        "end" -> {
                            require(output != null && received == expected)
                            output!!.fd.sync()
                            output!!.close()
                            output = null
                            val record =
                                metadata!!
                                    .put("offline_size", received)
                                    .put(
                                        "offline_sha256",
                                        digest!!.digest().joinToString("") { "%02x".format(it) },
                                    )
                            app.nativeLibrary.commitAudio(
                                NativeTrack.reference(record),
                                record,
                                file!!,
                                scope,
                            )
                            file = null
                            imported++
                        }
                        "record" -> {
                            require(output == null && record == null)
                            recordKind = data.getString("kind")
                            require(recordKind in setOf("collection", "pending"))
                            expected = data.getLong("size")
                            require(expected in 1..4L * 1024 * 1024)
                            received = 0
                            record = java.io.ByteArrayOutputStream()
                        }
                        "record_chunk" -> {
                            require(record != null && data.getLong("offset") == received)
                            val bytes =
                                android.util.Base64.decode(
                                    data.getString("data"),
                                    android.util.Base64.NO_WRAP,
                                )
                            require(bytes.size in 1..48 * 1024 && received + bytes.size <= expected)
                            record!!.write(bytes)
                            received += bytes.size
                        }
                        "record_end" -> {
                            require(record != null && received == expected)
                            val value = JSONObject(record!!.toString("UTF-8"))
                            record = null
                            if (recordKind == "collection")
                                app.nativeLibrary.put(
                                    "collection",
                                    value.get("id").toString(),
                                    value,
                                    scope,
                                )
                            else {
                                require(value.optString("username") == username)
                                app.nativeLibrary.put(
                                    "pending",
                                    "legacy-" + value.get("action_id"),
                                    value.put("review_required", true),
                                    scope,
                                )
                            }
                        }
                        "done" -> {
                            require(output == null && record == null)
                            app.nativeLibrary.put(
                                "migration",
                                "indexeddb-v1",
                                JSONObject().put("complete", true).put("tracks", imported),
                                scope,
                            )
                            finish(
                                "Imported $imported tracks. The original device database is preserved; unassigned legacy downloads remain there for review.",
                                null,
                            )
                            return@execute
                        }
                        "error" ->
                            throw IllegalStateException(
                                "Could not read the previous device library. Original data was preserved."
                            )
                        else -> error("Unknown migration operation")
                    }
                    app.nativeApi.main.post { reply.postMessage(response) }
                } catch (error: Exception) {
                    finish(null, error)
                }
            }
        }
        val script =
            app.assets
                .open("mobile/native-migration.js")
                .bufferedReader()
                .use { it.readText() }
                .replace("__NONCE_JSON__", JSONObject.quote(nonce))
                .replace("__USER_JSON__", JSONObject.quote(username))
        web.loadDataWithBaseURL(
            origin.rule + "/_aoede/migrate",
            "<!doctype html><meta charset=utf-8><script>$script</script>",
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun close() {
        finished = true
        view?.destroy()
        view = null
        worker.shutdownNow()
        output?.close()
        file?.delete()
    }
}
