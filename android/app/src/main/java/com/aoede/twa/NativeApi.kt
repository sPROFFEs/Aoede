package com.aoede.twa

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.KeyPairGeneratorSpec
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** App-private encrypted native session. The wrapping key never leaves AndroidKeyStore. */
class NativeVault(private val context: Context) {
    private val random = SecureRandom()
    private val alias = "aoede.native.session.v1"
    private val directory = File(context.noBackupFilesDir, "native-sessions").apply { mkdirs() }

    private fun file(origin: ServerOrigin) = File(directory, NativeApi.digest(origin.rule) + ".bin")

    @Suppress("DEPRECATION")
    @Synchronized
    private fun key(): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                .apply {
                    initialize(
                        KeyPairGeneratorSpec.Builder(context)
                            .setAlias(alias)
                            .setSubject(X500Principal("CN=Aoede native session"))
                            .setSerialNumber(BigInteger.ONE)
                            .setStartDate(start.time)
                            .setEndDate(end.time)
                            .setKeySize(2048)
                            .build()
                    )
                }
                .generateKeyPair()
        }
        val wrapped = File(directory, "key.bin")
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        if (wrapped.exists()) {
            cipher.init(Cipher.DECRYPT_MODE, store.getKey(alias, null))
            return cipher.doFinal(wrapped.readBytes())
        }
        val raw = ByteArray(32).also(random::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE, store.getCertificate(alias).publicKey)
        val temporary = File(directory, "key.tmp")
        temporary.writeBytes(cipher.doFinal(raw))
        check(temporary.renameTo(wrapped))
        return raw
    }

    @Synchronized
    fun read(origin: ServerOrigin): JSONObject? {
        val file = file(origin)
        if (!file.exists()) return null
        return runCatching {
                require(file.length() in 29..65536)
                val bytes = file.readBytes()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key(), "AES"),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
                cipher.updateAAD(origin.rule.toByteArray())
                JSONObject(
                    String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
                )
            }
            .getOrElse {
                file.delete()
                null
            }
    }

    @Synchronized
    fun write(origin: ServerOrigin, value: JSONObject) {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(origin.rule.toByteArray())
        val temporary = File(directory, NativeApi.digest(origin.rule) + ".tmp")
        temporary.outputStream().use {
            it.write(iv)
            it.write(cipher.doFinal(value.toString().toByteArray()))
        }
        check(temporary.renameTo(file(origin)))
    }

    fun clear(origin: ServerOrigin) {
        file(origin).delete()
    }
}

class NativeApi(internal val app: AoedeApp) {
    class Failure(val code: Int, message: String) : IOException(message)

    private class ImageWork(
        val current: () -> Boolean,
        val discarded: () -> Unit,
        val work: () -> Unit,
    ) : Runnable {
        override fun run() {
            if (current()) work() else discarded()
        }
    }

    val main = Handler(Looper.getMainLooper())
    private val workers =
        ThreadPoolExecutor(
            3,
            3,
            30,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(64),
            { r -> Thread(r, "aoede-native-api").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val images =
        ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(128),
            { r -> Thread(r, "aoede-native-image").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    val artwork = NativeArtwork(this)
    private val vault by lazy { NativeVault(app) }
    var origin: ServerOrigin? = null
        private set

    @Volatile private var session = JSONObject()
    private var sessionRevision = 0L
    private val taskScope = ThreadLocal<Pair<Long, String>>()

    @Synchronized private fun identity() = sessionRevision to scope

    private fun scoped(task: () -> Any?): () -> Any? {
        val expected = identity()
        return {
            taskScope.set(expected)
            try {
                check(expected == identity()) { "Account changed" }
                task()
            } finally {
                taskScope.remove()
            }
        }
    }

    private var importedLegacyCookie = false
    private val requests = java.util.WeakHashMap<HttpURLConnection, Long>()
    val account: JSONObject?
        get() = session.optJSONObject("context")

    val hasCredentials
        get() = session.optString("access_token").isNotBlank()

    val username: String
        get() = account?.optJSONObject("account")?.optString("username").orEmpty()

    val scope: String
        get() =
            account
                ?.optString("storage_scope")
                .orEmpty()
                .takeIf { Regex("[a-f0-9]{64}").matches(it) }
                .orEmpty()

    val isAdmin: Boolean
        get() = account?.optJSONObject("account")?.optBoolean("is_admin") == true

    companion object {
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
                "%02x".format(it)
            }

        internal fun originHeader(origin: ServerOrigin): String {
            val host =
                if (origin.host.contains(':') && !origin.host.startsWith('[')) "[${origin.host}]"
                else origin.host
            val standard = if (origin.scheme == "https") 443 else 80
            return "${origin.scheme}://$host" +
                if (origin.port == standard) "" else ":${origin.port}"
        }

        internal fun resolve(origin: ServerOrigin, path: String): URL {
            require(
                path.length <= 8192 &&
                    !path.contains('\\') &&
                    !path.contains('\r') &&
                    !path.contains('\n')
            )
            val url = URL(URL(origin.rule + "/"), path)
            require(
                url.ref == null &&
                    ServerOrigin.from(Uri.parse(url.toString())) == origin &&
                    url.userInfo == null
            ) {
                "Cross-origin request rejected"
            }
            return url
        }
    }

    @Synchronized
    fun configure(value: String) {
        val next = ServerOrigin.from(Uri.parse(value)) ?: error("Invalid server")
        if (origin == next) return
        artwork.clear()
        sessionRevision++
        importedLegacyCookie = false
        origin = next
        session = vault.read(next) ?: JSONObject()
        // Existing login may be imported once. Native requests never share a global cookie handler.
        if (
            session.optString("access_token").isBlank() &&
                File(app.applicationInfo.dataDir, "app_webview").isDirectory
        ) {
            runCatching { CookieManager.getInstance().getCookie(next.rule) }
                .getOrNull()
                ?.split(';')
                ?.forEach { raw ->
                    val name = raw.substringBefore('=').trim()
                    val token = raw.substringAfter('=', "")
                    if (name in setOf("access_token", "lang") && token.length <= 8192) {
                        session.put(name, token)
                        if (name == "access_token" && token.isNotBlank())
                            importedLegacyCookie = true
                    }
                }
        }
    }

    @Synchronized
    private fun capture(connection: HttpURLConnection) {
        val target = origin ?: return
        checkResponse(connection)
        connection.headerFields
            .filterKeys { it?.equals("Set-Cookie", true) == true }
            .values
            .flatten()
            .forEach { raw ->
                HttpCookie.parse(raw).forEach { cookie ->
                    if (
                        cookie.name in setOf("access_token", "lang") &&
                            cookie.value.length <= 8192 &&
                            (!cookie.secure || target.scheme == "https")
                    ) {
                        if (cookie.maxAge == 0L) session.remove(cookie.name)
                        else session.put(cookie.name, cookie.value)
                    }
                }
            }
    }

    @Synchronized
    fun checkResponse(connection: HttpURLConnection) {
        val target = origin ?: throw Failure(401, "Sign in again")
        if (
            ServerOrigin.from(Uri.parse(connection.url.toString())) != target ||
                requests[connection] != sessionRevision
        )
            throw Failure(409, "Server changed; retry the request")
    }

    @Synchronized
    fun cookieHeader(target: ServerOrigin): String? {
        if (target != origin) return null
        return listOf("access_token", "lang")
            .mapNotNull { name ->
                session.optString(name).takeIf { it.isNotBlank() }?.let { "$name=$it" }
            }
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun connection(
        path: String,
        method: String = "GET",
        expectedScope: String? = null,
    ): HttpURLConnection {
        val target = origin ?: throw IOException("Configure your Aoede server first")
        if (expectedScope != null && expectedScope != scope)
            throw Failure(409, "Account changed; retry the request")
        if (taskScope.get()?.let { it != identity() } == true)
            throw Failure(409, "Account changed; retry the request")
        if (account == null && path !in setOf("/login", "/api/mobile/v2/context"))
            throw Failure(401, "Sign in first")
        return (resolve(target, path).openConnection() as HttpURLConnection).apply {
            requests[this] = sessionRevision
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = method
            setRequestProperty("User-Agent", "AoedeAndroid/${BuildConfig.VERSION_NAME} Native/2")
            setRequestProperty("Origin", originHeader(target))
            setRequestProperty("Referer", originHeader(target) + "/")
            setRequestProperty("Accept", "application/json")
            val cookies =
                listOf("access_token", "lang")
                    .mapNotNull { name ->
                        session.optString(name).takeIf { it.isNotBlank() }?.let { "$name=$it" }
                    }
                    .joinToString("; ")
            if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
        }
    }

    fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        form: Map<String, String>? = null,
    ): Any {
        val c = connection(path, method)
        if (path.startsWith("/admin/api/downloader/") && path.endsWith("/auth-browser/start"))
            c.readTimeout = 150000
        try {
            if (body != null || form != null) {
                val data =
                    body?.toString()
                        ?: form!!.entries.joinToString("&") {
                            encode(it.key) + "=" + encode(it.value)
                        }
                c.doOutput = true
                c.setRequestProperty(
                    "Content-Type",
                    if (form != null) "application/x-www-form-urlencoded" else "application/json",
                )
                c.outputStream.use { it.write(data.toByteArray()) }
            }
            val code = c.responseCode
            capture(c)
            if (code in 300..399) {
                val location = c.getHeaderField("Location").orEmpty()
                resolve(
                    origin!!,
                    location,
                ) // Validate before interpreting even an unfollowed redirect.
                if (path == "/logout" || (path == "/login" && !location.startsWith("/login")))
                    return JSONObject().put("ok", true)
                if (path == "/user/change-password" && location.startsWith("/login"))
                    return JSONObject().put("reauthenticate", true)
                if (location.startsWith("/login"))
                    throw Failure(401, "Sign in again or check your credentials")
                Uri.parse(location).getQueryParameter("error")?.let {
                    throw Failure(400, it.take(240))
                }
                return JSONObject().put("ok", true).put("redirect", location)
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes =
                stream?.use { input -> input.readBytesBounded(4 * 1024 * 1024) } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            val result = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            if (code !in 200..299) {
                val error =
                    (result as? JSONObject)
                        ?.let { it.optString("error", it.optString("detail")) }
                        .orEmpty()
                        .take(240)
                throw Failure(
                    code,
                    when (code) {
                        401 -> "Sign in again"
                        422 -> "Check the form values and try again"
                        429 -> "Too many attempts. Wait before trying again"
                        in 500..599 -> "The server could not complete this request (HTTP $code)"
                        else -> error.ifBlank { "Aoede request failed (HTTP $code)" }
                    },
                )
            }
            if (result is JSONObject && result.has("error"))
                throw Failure(400, result.optString("error").take(240))
            if (bytes.isNotEmpty() && result !is JSONObject && result !is JSONArray)
                throw Failure(
                    502,
                    "Unexpected server response. Update the Aoede server and try again.",
                )
            return result ?: JSONObject().put("ok", true)
        } finally {
            c.disconnect()
        }
    }

    fun upload(path: String, field: String, uri: Uri): Any {
        require(
            uri.scheme == "content" && field in setOf("avatar_file", "cover_file", "cookies_file")
        )
        val bytes =
            app.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(8 * 1024 * 1024) }
                ?: throw IOException("Cannot read selected file")
        val declared =
            app.contentResolver.getType(uri).orEmpty().substringBefore(';').trim().lowercase()
        val mime =
            if (field == "cookies_file") "text/plain"
            else {
                val imageType = Regex("image/[a-z0-9.+-]{1,64}")
                if (imageType.matches(declared)) declared
                else {
                    val bounds =
                        android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    bounds.outMimeType?.takeIf { imageType.matches(it) }
                        ?: "application/octet-stream"
                }
            }
        val extension =
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf {
                Regex("[a-zA-Z0-9]{1,8}").matches(it)
            } ?: "bin"
        val filename = if (field == "cookies_file") "cookies.txt" else "upload.$extension"
        val boundary = "aoede-" + java.util.UUID.randomUUID()
        val c = connection(path, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { output ->
                output.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n")
                        .toByteArray()
                )
                output.write(bytes)
                output.write(("\r\n--$boundary--\r\n").toByteArray())
            }
            val code = c.responseCode
            capture(c)
            require(code in 200..399) { "Upload rejected (HTTP $code)" }
            if (code in 300..399) {
                val location = c.getHeaderField("Location").orEmpty()
                resolve(origin!!, location)
                if (location.startsWith("/login")) throw Failure(401, "Sign in again")
                Uri.parse(location).getQueryParameter("error")?.let {
                    throw Failure(400, it.take(240))
                }
                return JSONObject().put("ok", true)
            }
            val result =
                JSONObject(c.inputStream.use { String(it.readBytesBounded(65536), Charsets.UTF_8) })
            if (result.has("error")) throw Failure(400, result.optString("error").take(240))
            return result
        } finally {
            c.disconnect()
        }
    }

    fun authenticate(username: String, password: String, remember: Boolean): JSONObject {
        synchronized(this) {
            sessionRevision++
            importedLegacyCookie = false
            origin?.let(vault::clear)
            session = JSONObject().put("remember", remember)
            if (taskScope.get() != null) taskScope.set(identity())
        }
        request(
            "/login",
            "POST",
            form =
                mapOf(
                    "username" to username,
                    "password" to password,
                    "remember_me" to remember.toString(),
                ),
        )
        return refreshContext()
    }

    fun refreshContext(): JSONObject {
        val requestedOrigin = origin
        val requestedRevision = synchronized(this) { sessionRevision }
        val data =
            try {
                request("/api/mobile/v2/context") as? JSONObject
                    ?: throw Failure(502, "Invalid Aoede context")
            } catch (error: Failure) {
                if (error.code in setOf(404, 405))
                    throw Failure(
                        409,
                        "Update your Aoede server to 1.3.1, or select the installed web client in Configure server",
                    )
                throw error
            }
        require(
            data.optInt("protocol") == 2 &&
                Regex("[a-f0-9]{64}").matches(data.optString("storage_scope"))
        ) {
            "Update your Aoede server to 1.3.1"
        }
        require(data.optJSONObject("account")?.optLong("id", 0)?.let { it > 0 } == true)
        require(
            Regex("[A-Za-z0-9][A-Za-z0-9_-]{1,31}")
                .matches(data.getJSONObject("account").optString("username"))
        )
        synchronized(this) {
            require(origin == requestedOrigin && requestedRevision == sessionRevision) {
                "Server changed"
            }
            session.put("context", data)
            if (taskScope.get() != null) taskScope.set(identity())
            if (importedLegacyCookie) {
                session.put("legacy_scope", data.getString("storage_scope"))
                app.nativeLibrary.put(
                    "migration",
                    "legacy-owner",
                    JSONObject().put("verified", true),
                )
            }
            if (session.optBoolean("remember", true)) vault.write(origin!!, session)
            else vault.clear(origin!!)
            if (importedLegacyCookie)
                runCatching {
                    CookieManager.getInstance()
                        .setCookie(origin!!.rule, "access_token=; Path=/; Max-Age=0")
                    CookieManager.getInstance().flush()
                }
            importedLegacyCookie = false
        }
        return data
    }

    val canImportLegacy: Boolean
        get() =
            scope.isNotBlank() &&
                (session.optString("legacy_scope") == scope ||
                    runCatching {
                            app.nativeLibrary
                                .get("migration", "legacy-owner")
                                ?.optBoolean("verified") == true
                        }
                        .getOrDefault(false))

    fun saveContextPreference(key: String, value: String) {
        synchronized(this) {
            account?.put(key, value)
            if (session.optBoolean("remember", true)) vault.write(origin!!, session)
            else vault.clear(origin!!)
        }
    }

    fun logout() {
        request("/logout", "POST")
        clearSession()
    }

    fun clearSession() {
        artwork.clear()
        synchronized(this) {
            sessionRevision++
            origin?.let(vault::clear)
            session = JSONObject()
        }
    }

    fun changeClientMode(server: String, toNative: Boolean) {
        artwork.clear()
        val target = ServerOrigin.from(Uri.parse(server)) ?: error("Invalid server")
        synchronized(this) {
            sessionRevision++
            vault.clear(target)
            session = JSONObject()
            origin = null
            importedLegacyCookie = false
        }
        // A compatibility login must never silently reuse another client's remembered account.
        // Going native preserves the browser cookie so the normal verified import can identify its
        // owner.
        if (!toNative)
            runCatching {
                CookieManager.getInstance()
                    .setCookie(target.rule, "access_token=; Path=/; Max-Age=0")
                CookieManager.getInstance().flush()
            }
    }

    fun image(
        task: () -> Any?,
        current: () -> Boolean = { true },
        done: (Any?, Throwable?) -> Unit,
    ) {
        val bound = scoped(task)
        val discarded = {
            main.post { done(null, IOException("Artwork request cancelled")) }
            Unit
        }
        for (queued in images.queue.filterIsInstance<ImageWork>()) {
            if (!queued.current() && images.remove(queued)) queued.discarded()
        }
        try {
            images.execute(
                ImageWork(current, discarded) {
                    val result = runCatching(bound)
                    main.post { done(result.getOrNull(), result.exceptionOrNull()) }
                }
            )
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            main.post { done(null, IOException("Artwork queue is busy")) }
        }
    }

    fun execute(task: () -> Any?, done: (Any?, Throwable?) -> Unit) {
        val bound = scoped(task)
        try {
            workers.execute {
                val result = runCatching(bound)
                main.post { done(result.getOrNull(), result.exceptionOrNull()) }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            main.post { done(null, IOException("Please wait for current requests")) }
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16384)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Response too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
