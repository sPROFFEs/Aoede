package com.aoede.twa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MediaController(private val app: AoedeApp) {
    private val handler = Handler(Looper.getMainLooper())
    private val actions = mutableSetOf<String>()
    private var invoker: ((String, Long) -> Unit)? = null
    private var artworkUrl = ""
    private var artwork: Bitmap? = null
    private var title = ""
    private var artist = ""
    private var album = ""
    private var positionMs = 0L
    private var durationMs = 0L
    private var rate = 1f
    private var lastNotified: Any? = null
    private var artworkJob: Future<*>? = null
    private var artworkGeneration = 0
    // One download and one pending cover; rapid skipping cannot spawn unbounded workers.
    private val artworkExecutor by lazy {
        ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(1),
            { task -> Thread(task, "aoede-artwork").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
    var serverOrigin: ServerOrigin? = null
    var onChanged: (() -> Unit)? = null
    var metadata = MediaMetadataCompat.Builder().build()
        private set
    var state = "none"
        private set
    val isPlaying get() = state == "playing"
    val needsForeground get() = state == "playing" || state == "buffering"
    var locallyPaused = false
        private set
    var canFavorite = false
        private set
    var favorite = false
        private set
    var canShuffle = false
        private set
    var shuffle = false
        private set
    var canRepeat = false
        private set
    var repeat = "off"
        private set

    val mediaSession = MediaSessionCompat(app, "aoede-media").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { command("play") }
            override fun onPause() { pauseLocal() }
            override fun onStop() { command("stop"); reset() }
            override fun onSkipToNext() { command("nexttrack") }
            override fun onSkipToPrevious() { command("previoustrack") }
            override fun onSeekTo(pos: Long) { command("seekto", pos.coerceAtLeast(0)) }
        }, handler)
        isActive = false
    }

    fun setActionInvoker(value: ((String, Long) -> Unit)?) { invoker = value }
    fun supports(action: String) = action in actions

    fun command(action: String, position: Long = 0) {
        if (action in setOf("play", "nexttrack", "previoustrack")) locallyPaused = false
        invoker?.invoke(action, position)
    }

    fun pauseLocal() {
        locallyPaused = true
        invoker?.invoke("pause", 0)
        if (state != "none") updateState("paused", positionMs, durationMs, rate)
    }

    fun receive(raw: String) {
        if (raw.length > 256 * 1024) return
        ui {
            try {
                val message = JSONObject(raw)
                when (message.optString("type")) {
                    "metadata" -> updateMetadata(message)
                    "state" -> updateState(message.optString("state"), message.optLong("position"),
                        message.optLong("duration"), message.optDouble("rate", 1.0).toFloat())
                    "action" -> {
                        val action = message.optString("action")
                        if (action !in setOf("play", "pause", "stop", "nexttrack", "previoustrack", "seekto")) return@ui
                        if (message.optBoolean("enabled")) actions.add(action) else actions.remove(action)
                        emitState()
                    }
                    "resume" -> locallyPaused = false
                    "controls" -> {
                        canFavorite = message.optBoolean("canFavorite")
                        favorite = message.optBoolean("favorite")
                        canShuffle = message.optBoolean("canShuffle")
                        shuffle = message.optBoolean("shuffle")
                        canRepeat = message.optBoolean("canRepeat")
                        repeat = message.optString("repeat").takeIf { it in setOf("off", "all", "one") } ?: "off"
                        notifyChanged()
                    }
                    "ready" -> if (locallyPaused) invoker?.invoke("pause", 0)
                }
            } catch (_: org.json.JSONException) { /* Ignore malformed messages at the bridge boundary. */ }
        }
    }

    private fun updateMetadata(message: JSONObject) {
        title = message.optString("title").take(512)
        artist = message.optString("artist").take(512)
        album = message.optString("album").take(512)
        val source = message.optString("artwork")
        val url = source.take(if (source.startsWith("data:image/")) 192 * 1024 else 2048)
        if (url != artworkUrl) {
            artworkUrl = url
            artwork = null
            artworkJob?.cancel(true)
            artworkGeneration++
            if (url.isNotBlank() && (!message.optBoolean("offline") || url.startsWith("data:image/"))) downloadArtwork(url, artworkGeneration)
        }
        emitMetadata()
    }

    fun updateState(value: String, position: Long, duration: Long, speed: Float = 1f) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (value !in setOf("none", "paused", "playing", "buffering", "error")) return
        state = if (locallyPaused && value in setOf("playing", "buffering")) "paused" else value
        val boundedDuration = duration.coerceIn(0, 31L * 24 * 3600 * 1000)
        positionMs = position.coerceIn(0, if (boundedDuration > 0) boundedDuration else 31L * 24 * 3600 * 1000)
        if (durationMs != boundedDuration) {
            durationMs = boundedDuration
            emitMetadata()
        }
        rate = if (speed.isFinite() && speed in 0.1f..4f) speed else 1f
        emitState()
        app.ensurePlaybackService()
        app.musicIsland.syncSpectrum() // Position updates also recover from a missed OEM wake/unlock broadcast.
    }

    private fun emitMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        artwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        metadata = builder.build()
        mediaSession.setMetadata(metadata)
        notifyChanged()
    }

    private fun emitState() {
        var flags = PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP
        if ("nexttrack" in actions) flags = flags or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if ("previoustrack" in actions) flags = flags or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if ("seekto" in actions) flags = flags or PlaybackStateCompat.ACTION_SEEK_TO
        val code = when (state) {
            "playing" -> PlaybackStateCompat.STATE_PLAYING
            "buffering" -> PlaybackStateCompat.STATE_BUFFERING
            "paused" -> PlaybackStateCompat.STATE_PAUSED
            "error" -> PlaybackStateCompat.STATE_ERROR
            else -> PlaybackStateCompat.STATE_NONE
        }
        mediaSession.isActive = state != "none" && state != "error"
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(code, positionMs, if (isPlaying) rate else 0f, SystemClock.elapsedRealtime())
            .setActions(if (state == "none") 0 else flags).build())
        notifyChanged()
    }

    private fun notifyChanged() {
        val fingerprint = listOf(state, title, artist, album, artwork, actions.toSet(),
            canFavorite, favorite, canShuffle, shuffle, canRepeat, repeat)
        if (fingerprint != lastNotified) {
            lastNotified = fingerprint
            onChanged?.invoke()
            PlaybackWidget.update(app)
            app.musicIsland.refresh()
        }
    }

    fun reset() {
        state = "none"
        locallyPaused = false
        actions.clear()
        canFavorite = false; favorite = false
        canShuffle = false; shuffle = false
        canRepeat = false; repeat = "off"
        artworkUrl = ""
        artwork = null
        artworkGeneration++
        artworkJob?.cancel(true)
        positionMs = 0
        durationMs = 0
        title = ""
        artist = ""
        album = ""
        emitMetadata()
        emitState()
        app.ensurePlaybackService()
    }

    private fun downloadArtwork(initialUrl: String, generation: Int) {
        val cookieOrigin = serverOrigin
        artworkJob = artworkExecutor.submit {
            try {
                var bytes: ByteArray? = null
                if (initialUrl.startsWith("data:")) {
                    val prefix = initialUrl.substringBefore(',')
                    if (prefix !in setOf("data:image/jpeg;base64", "data:image/png;base64", "data:image/webp;base64")) return@submit
                    bytes = android.util.Base64.decode(initialUrl.substringAfter(','), android.util.Base64.DEFAULT)
                }
                var url = if (bytes == null) URL(initialUrl) else null
                for (redirect in 0..5) {
                    if (bytes != null) break
                    val currentUrl = url ?: return@submit
                    if (Thread.currentThread().isInterrupted) return@submit
                    val uri = Uri.parse(currentUrl.toString())
                    val origin = ServerOrigin.from(uri) ?: return@submit
                    if (origin.scheme == "http" && origin != cookieOrigin) return@submit
                    val connection = currentUrl.openConnection() as HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        if (origin == cookieOrigin) {
                            CookieManager.getInstance().getCookie(currentUrl.toString())?.let {
                                connection.setRequestProperty("Cookie", it)
                            }
                        }
                        val status = connection.responseCode
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            val location = connection.getHeaderField("Location") ?: return@submit
                            val next = URL(currentUrl, location)
                            if (currentUrl.protocol == "https" && next.protocol != "https") return@submit
                            url = next
                            continue
                        }
                        if (status != 200 || connection.contentLength > 5 * 1024 * 1024) return@submit
                        val buffer = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val chunk = ByteArray(16384)
                            while (!Thread.currentThread().isInterrupted) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                if (buffer.size() + count > 5 * 1024 * 1024) return@submit
                                buffer.write(chunk, 0, count)
                            }
                        }
                        if (Thread.currentThread().isInterrupted) return@submit
                        bytes = buffer.toByteArray()
                        break
                    } finally { connection.disconnect() }
                }
                val data = bytes ?: return@submit
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val sample = artworkSampleSize(bounds.outWidth, bounds.outHeight) ?: return@submit
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@submit
                ui {
                    if (generation == artworkGeneration && initialUrl == artworkUrl) {
                        artwork = bitmap
                        emitMetadata()
                    } else bitmap.recycle()
                }
            } catch (_: Exception) { /* A missing cover must not interrupt playback or log session URLs. */ }
        }
    }

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    companion object {
        fun artworkSampleSize(width: Int, height: Int): Int? {
            if (width <= 0 || height <= 0 || width > 100000 || height > 100000) return null
            var sample = 1
            while (maxOf(width, height) / sample > 512) sample *= 2
            return sample
        }
    }
}
