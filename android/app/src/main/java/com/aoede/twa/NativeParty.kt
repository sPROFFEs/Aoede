package com.aoede.twa

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import org.json.JSONObject

class NativeParty(private val app: AoedeApp, val key: String) {
    val path: String

    init {
        val remote = Regex("fed_([1-9][0-9]*)_([1-9][0-9]*)").matchEntire(key)
        path =
            if (remote != null)
                "/api/party/remote/${remote.groupValues[1]}/rooms/${remote.groupValues[2]}"
            else {
                require(Regex("[1-9][0-9]*").matches(key))
                "/api/party/rooms/$key"
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val events =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "aoede-native-party").apply { isDaemon = true }
        }
    private val accountScope = app.nativeLibrary.scope()
    private val credentialsScope = app.nativeApi.scope
    private val backend = app.nativeApi.origin!!.rule
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    private var revision = 0L
    private var item = 0L
    private var readyItem = 0L
    private var readyRevision = -1L
    private var received = 0L
    var room = JSONObject()
        private set

    var onChanged: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    private val personal = app.nativePlayback.queue.toList()
    private val personalIndex = app.nativePlayback.index
    private val personalPosition = app.nativePlayback.position

    fun join(done: (Throwable?) -> Unit) {
        app.nativeApi.execute({
            require(app.nativeLibrary.scope() == accountScope)
            app.nativeApi.request("$path/join", "POST", JSONObject())
        }) { data, error ->
            if (
                closed ||
                    runCatching { app.nativeLibrary.scope() != accountScope }.getOrDefault(true)
            )
                return@execute
            if (error != null) {
                done(error)
                return@execute
            }
            app.nativePlayback.party = this
            if (app.mediaController.state == "none") app.mediaController.updateState("paused", 0, 0)
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    app,
                    android.content.Intent(app, PlaybackService::class.java),
                )
            } catch (error: RuntimeException) {
                close()
                done(error)
                return@execute
            }
            (data as? JSONObject)?.optJSONObject("room")?.let(::apply)
            events.execute(::receive)
            done(null)
        }
    }

    private fun receive() {
        var retry = 1000L
        while (!closed) {
            try {
                if (app.nativeLibrary.scope() != accountScope) break
                val c =
                    app.nativeApi.connection(
                        backend + "$path/events",
                        expectedScope = credentialsScope,
                    )
                connection = c
                c.readTimeout = 25000
                c.setRequestProperty("Accept", "text/event-stream")
                if (c.responseCode != 200)
                    throw NativeApi.Failure(c.responseCode, "Party connection unavailable")
                c.inputStream.bufferedReader().use { reader ->
                    val data = StringBuilder()
                    while (!closed) {
                        // A bounded reader prevents a malformed peer event from growing memory
                        // without limit.
                        val line = StringBuilder()
                        while (true) {
                            val ch = reader.read()
                            if (ch < 0) throw java.io.EOFException()
                            if (ch == 10) break
                            require(line.length < 1024 * 1024) { "Party event too large" }
                            if (ch != 13) line.append(ch.toChar())
                        }
                        if (line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                val message = JSONObject(data.toString())
                                data.setLength(0)
                                message.optJSONObject("room")?.let { value ->
                                    main.post { if (!closed) apply(value) }
                                }
                            }
                        } else if (line.startsWith("data:")) {
                            require(data.length + line.length <= 1024 * 1024)
                            data.append(line.substring(5).trimStart())
                        }
                        retry = 1000
                    }
                }
            } catch (error: Exception) {
                if (error is NativeApi.Failure && error.code in setOf(401, 403, 404)) {
                    main.post { onError?.invoke("Party access changed; leave and reconnect") }
                    break
                }
                if (!closed)
                    try {
                        Thread.sleep(retry)
                        retry = (retry * 2).coerceAtMost(30000)
                    } catch (_: InterruptedException) {
                        break
                    }
            } finally {
                connection?.disconnect()
                connection = null
            }
        }
    }

    private fun apply(value: JSONObject) {
        if (
            closed || app.nativePlayback.party !== this || app.nativeLibrary.scope() != accountScope
        )
            return
        room =
            value.put(
                "is_owner",
                !key.startsWith("fed_") &&
                    value.optLong("owner_id") ==
                        app.nativeApi.account?.optJSONObject("account")?.optLong("id"),
            )
        received = SystemClock.elapsedRealtime()
        revision = room.optLong("revision")
        val current = room.optJSONObject("current_track")
        val next = room.optLong("current_item_id")
        val state = room.optString("playback_status")
        if (current == null && item != 0L) {
            item = 0
            app.nativePlayback.stop()
        }
        if (current != null && next > 0 && next != item) {
            item = next
            readyItem = 0
            if (key.startsWith("fed_"))
                current
                    .put("stream_url", "$path/stream/$item")
                    .put("thumbnail", "/static/img/default_cover.png")
                    .put("db_id", JSONObject.NULL)
                    .put("is_local", false)
                    .put("is_federated", true)
                    .put("id", "$key:$item")
            app.nativePlayback.play(listOf(current), 0, false, room.optLong("playback_position_ms"))
        }
        if (state == "loading") {
            app.nativePlayback.localPlay(false)
            ready()
        } else if (state == "playing") {
            val stamp = revision
            val delay =
                (room.optLong("playback_starts_at_ms") - room.optLong("server_time_ms")).coerceIn(
                    0,
                    5000,
                )
            main.postDelayed(
                {
                    if (
                        !closed &&
                            stamp == revision &&
                            room.optString("playback_status") == "playing"
                    ) {
                        val target =
                            room.optLong("playback_position_ms") +
                                (SystemClock.elapsedRealtime() - received - delay).coerceAtLeast(0)
                        if (kotlin.math.abs(app.nativePlayback.position - target) > 1500)
                            app.nativePlayback.align(target)
                        if (!app.mediaController.locallyPaused) app.nativePlayback.localPlay(true)
                    }
                },
                delay,
            )
        } else app.nativePlayback.localPlay(false)
        onChanged?.invoke()
    }

    fun ready() {
        if (
            closed ||
                item == 0L ||
                (readyItem == item && readyRevision == revision) ||
                room.optString("playback_status") != "loading"
        )
            return
        if (!app.nativePlayback.readyToPlay) return
        if (
            !room.optBoolean("is_owner") &&
                NativeTrack.items(room.optJSONArray("participants")).any {
                    it.optLong("id") == room.optLong("owner_id")
                }
        )
            return
        readyItem = item
        readyRevision = revision
        app.nativeApi.execute({
            if (app.nativeLibrary.scope() != accountScope) error("Account changed")
            app.nativeApi.request(
                "$path/control",
                "POST",
                JSONObject().put("action", "ready").put("expected_item_id", item),
            )
        }) { _, error ->
            if (error != null) {
                readyItem = 0
                readyRevision = -1
                if (error !is NativeApi.Failure || error.code != 403)
                    onError?.invoke(error.message.orEmpty())
            }
        }
    }

    fun ended() {
        control("ended")
    }

    fun play() {
        if (room.optBoolean("is_owner")) control("play")
        else if (room.optString("playback_status") == "playing") app.nativePlayback.localPlay(true)
        else onError?.invoke("Waiting for the host to start playback")
    }

    fun control(action: String, position: Long = 0) {
        if (closed) return
        val payload =
            JSONObject()
                .put("action", action)
                .put("position_ms", position.coerceAtLeast(0))
                .put("expected_item_id", item)
        app.nativeApi.execute({
            if (app.nativeLibrary.scope() != accountScope) error("Account changed")
            app.nativeApi.request("$path/control", "POST", payload)
        }) { _, error ->
            if (error != null && !closed) onError?.invoke(error.message.orEmpty())
        }
    }

    fun add(track: JSONObject, done: (Throwable?) -> Unit) {
        val payload = JSONObject().put("track_reference", NativeTrack.reference(track))
        app.nativeApi.execute({
            require(app.nativeLibrary.scope() == accountScope)
            app.nativeApi.request("$path/queue", "POST", payload)
        }) { _, error ->
            done(error)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        connection?.disconnect()
        events.shutdownNow()
        main.removeCallbacksAndMessages(null)
        if (app.nativePlayback.party === this) {
            app.nativePlayback.party = null
            app.nativePlayback.stop()
            if (
                personal.isNotEmpty() &&
                    runCatching { app.nativeLibrary.scope() == accountScope }.getOrDefault(false)
            )
                app.nativePlayback.play(personal, personalIndex, false, personalPosition)
        }
    }
}
