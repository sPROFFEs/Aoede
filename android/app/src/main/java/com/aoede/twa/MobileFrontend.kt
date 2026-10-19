package com.aoede.twa

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicReference

/** Local UI at the selected server origin: cookies, uploads and streams keep their normal paths. */
class MobileFrontend(private val context: Context, private val origin: ServerOrigin) {
    private val nextDocument = AtomicReference<Pair<String, String>?>(null)

    fun intercept(uri: Uri, method: String = "GET", mainFrame: Boolean = false): WebResourceResponse? {
        if (method != "GET" || ServerOrigin.from(uri) != origin) return null
        val path = uri.path.orEmpty()
        if (path.split('/').any { it == ".." || it == "." } || '\\' in path || '\u0000' in path) {
            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
        if (mainFrame && path in setOf("/portal", "/login")) {
            val ready = nextDocument.getAndSet(null)
            if (ready?.first == path) return html(ready.second)
        }
        if (path == "/_aoede/start" || (mainFrame && path in setOf("/", "/portal", "/login", "/index.html"))) {
            return asset("mobile/index.html")
        }
        // Match paths after full-origin validation: AndroidX's domain matcher ignores custom-port origins.
        return when {
            path.startsWith("/assets/") || path.startsWith("/static/") -> asset("web/assets/" + path.substringAfter('/', "").substringAfter('/'))
            path == "/_aoede/media-session.js" -> asset("media-session.js")
            path in setOf("/_aoede/boot.js", "/_aoede/runtime.js", "/_aoede/mobile.css") -> asset("mobile/" + path.substringAfterLast('/'))
            else -> null
        }
    }

    /** Called only for a main-frame message from the exact configured origin. */
    fun prepare(message: String): String? {
        if (message.length > 65536) return null
        return try {
            val payload = JSONObject(message)
            if (payload.optString("type") != "render") return null
            val login = payload.optBoolean("login")
            val data = payload.optJSONObject("data")
            val username = data?.optString("username").orEmpty()
            val theme = data?.optString("theme") ?: "classic"
            if (!login && (data?.optInt("protocol") != 1 || !username.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{1,31}")) ||
                data.opt("is_admin") !is Boolean || theme !in setOf("classic", "glass", "vitrine"))) return null
            val language = payload.optString("language").takeIf { it in setOf("en", "es", "de") } ?: "en"
            val role = if (login) "login" else if (data!!.getBoolean("is_admin")) "admin" else "user"
            val pool = data?.optJSONObject("pool")
            val loginError = if (login) payload.optString("loginError").take(512) else ""
            fun number(key: String) = pool?.optDouble(key, 0.0)?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            var document = context.assets.open("web/$language-$role.html").bufferedReader().use { it.readText() }
            val replacements = mapOf("USERNAME" to if (login) "" else username, "THEME" to if (login) "classic" else theme,
                "POOL_USED" to number("used").toString(), "POOL_LIMIT" to number("limit").toString(),
                "LOGIN_ERROR" to android.text.TextUtils.htmlEncode(loginError))
            if (login && loginError.isBlank()) document = Regex("<div class=\"error-pill-pro\">.*?</div>", RegexOption.DOT_MATCHES_ALL).replace(document, "")
            document = Regex("AOEDE_MOBILE_(USERNAME|THEME|POOL_USED|POOL_LIMIT|LOGIN_ERROR)").replace(document) { replacements.getValue(it.groupValues[1]) }
            val offline = payload.optBoolean("offline") && !login
            val flags = "<script>window.AOEDE_ANDROID=true;window.AOEDE_VIEWPORT=${(context.applicationContext as AoedeApp).viewport};window.AOEDE_NATIVE_OFFLINE=$offline;window.AOEDE_NATIVE_POOL=${number("percent").coerceAtMost(100.0)};</script>"
            document = document.replaceFirst("<head>", "<head>$flags")
            val reduced = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getBoolean("reduce_effects",
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice)
            document = document.replaceFirst("<html ", "<html data-native-effects=\"${if (reduced) "reduced" else "full"}\" ")
            val path = if (login) "/login" else "/portal"
            nextDocument.set(path to document)
            origin.rule + path + "?native=1" +
                (if (loginError.isNotEmpty()) "&error=" + Uri.encode(loginError) else "") +
                (if (!login && payload.optBoolean("home")) "#home" else "")
        } catch (_: org.json.JSONException) { null }
    }

    private fun html(value: String) = WebResourceResponse("text/html", "UTF-8", 200, "OK",
        headers(), ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))

    private fun headers() = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
        "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline' https://www.youtube.com https://s.ytimg.com; " +
            "style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data: blob: https:; media-src 'self' blob: https:; " +
            "connect-src 'self' https:; frame-src 'self' https://www.youtube.com; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")

    private fun asset(path: String): WebResourceResponse? = try {
        val mime = when (path.substringAfterLast('.')) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json", "webmanifest" -> "application/json"
            "woff2" -> "font/woff2"
            "svg" -> "image/svg+xml"
            else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
        }
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime in setOf("application/javascript", "application/json")) "UTF-8" else null,
            200, "OK", headers(), context.assets.open(path))
    } catch (_: FileNotFoundException) { null }
}
