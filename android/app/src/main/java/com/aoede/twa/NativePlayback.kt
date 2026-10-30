package com.aoede.twa

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Streaming never follows a redirect with a session cookie, including media redirects. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeHttpSource(private val api: NativeApi, private val accountScope: String = api.scope) :
    BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        val c = api.connection(spec.uri.toString(), expectedScope = accountScope)
        connection = c
        c.setRequestProperty("Accept", "audio/*,video/*,application/octet-stream")
        if (spec.position > 0 || spec.length != C.LENGTH_UNSET.toLong()) {
            val end =
                if (spec.length == C.LENGTH_UNSET.toLong()) ""
                else (spec.position + spec.length - 1).toString()
            c.setRequestProperty("Range", "bytes=${spec.position}-$end")
        }
        try {
            if (c.responseCode !in setOf(200, 206))
                throw IOException("Audio unavailable (HTTP ${c.responseCode})")
            val type = c.contentType.orEmpty().substringBefore(';')
            if (type.contains("html") || type.contains("json"))
                throw IOException("Server returned a non-audio response")
            input = c.inputStream
            api.checkResponse(c)
            if (c.responseCode == 200 && spec.position > 0) {
                var skip = spec.position
                val buffer = ByteArray(16384)
                while (skip > 0) {
                    val n = input!!.read(buffer, 0, minOf(skip, buffer.size.toLong()).toInt())
                    if (n < 0) throw IOException("Seek outside media")
                    skip -= n
                }
            }
            val content =
                c.getHeaderField("Content-Length")?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
            remaining =
                if (spec.length != C.LENGTH_UNSET.toLong()) spec.length
                else if (content >= 0 && c.responseCode == 200)
                    (content - spec.position).coerceAtLeast(0)
                else content
            opened = true
            transferStarted(spec)
            return remaining
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException("Could not open audio", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count =
            input?.read(
                buffer,
                offset,
                if (remaining < 0) length else minOf(length.toLong(), remaining).toInt(),
            ) ?: C.RESULT_END_OF_INPUT
        if (count > 0) {
            if (remaining > 0) remaining -= count
            bytesTransferred(count)
        }
        return count
    }

    override fun getUri(): Uri? = connection?.url?.toString()?.let(Uri::parse)

    override fun getResponseHeaders(): Map<String, List<String>> =
        connection?.headerFields?.filterKeys { it != null } ?: emptyMap()

    override fun close() {
        runCatching { input?.close() }
        input = null
        connection?.disconnect()
        connection = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** One native audio owner, shared by activity, notification, widget and island. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativePlayback(private val app: AoedeApp) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: ExoPlayer? = null
    var queueRevision = 0L
        private set

    private var tracks = listOf<JSONObject>()
        set(value) {
            field = value
            queueRevision++
        }

    private var ownerScope: String? = null

    private fun ownsAccount() =
        ownerScope != null &&
            runCatching { app.nativeLibrary.scope() == ownerScope }.getOrDefault(false)

    private var savedPosition = 0L
    private var savedIndex = 0
    private var sessionId = ""
    private var listened = 0L
    private var lastTick = 0L
    private val processor = NativeAudioProcessor()
    private val trackGainCache = android.util.LruCache<Long, Float>(256)
    private val playbackHistory = ArrayDeque<Int>()
    private val forwardHistory = ArrayDeque<Int>()
    private var lastPlayedIndex = -1
    private var isNavigatingHistory = false
    private var isNavigatingForward = false
    private val preferences
        get() = app.getSharedPreferences(MainActivity.PREFS, 0)

    private var lastPublished = 0L
    private var gainGeneration = 0
    private var savedShuffle = false
    private var savedRepeat = Player.REPEAT_MODE_OFF
    private var lastSaved = 0L
    private val saveRemote = Runnable { syncQueue() }
    var sleepTimerMinutes: Int = 0
        private set
    private val sleep = Runnable {
        sleepTimerMinutes = 0
        app.mediaController.pauseLocal()
        onChanged?.invoke()
    }

    fun setSleepTimer(minutes: Int) {
        require(minutes in 0..240)
        sleepTimerMinutes = minutes
        handler.removeCallbacks(sleep)
        if (minutes > 0) handler.postDelayed(sleep, minutes * 60000L)
        onChanged?.invoke()
    }

    val levels
        get() = processor.levels

    var volume: Float = 1f
        private set

    fun analyze(active: Boolean) {
        processor.sampling = active
    }

    fun refreshAudioPreferences() {
        metadata()
    }

    private var reported30 = false
    private var listenTrack: JSONObject? = null
    var failure: String? = null
        private set

    var onChanged: (() -> Unit)? = null
    var onOpenPlayer: (() -> Unit)? = null
    // A visible Wrapped story temporarily owns transport controls, never the personal queue.
    private var presentation: ((String, Long) -> Unit)? = null
    var party: NativeParty? = null
    val active
        get() = engine != null

    val current: JSONObject?
        get() = tracks.getOrNull(engine?.currentMediaItemIndex ?: savedIndex)

    val queue
        get() = tracks

    val index
        get() = engine?.currentMediaItemIndex ?: savedIndex

    val position
        get() = engine?.currentPosition ?: savedPosition

    val duration
        get() =
            engine?.duration?.takeIf { it > 0 }
                ?: ((current?.optDouble("duration", 0.0) ?: 0.0) * 1000).toLong()

    val playing
        get() = engine?.isPlaying == true

    val readyToPlay
        get() = engine?.playbackState == Player.STATE_READY

    private val tick =
        object : Runnable {
            override fun run() {
                if (engine == null) return
                if (!ownsAccount()) {
                    release()
                    return
                }
                if (presentation != null) {
                    lastTick = 0
                    handler.postDelayed(this, 100)
                    return
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (playing && lastTick > 0) listened += (now - lastTick).coerceIn(0, 1500)
                lastTick = now
                if (!reported30 && listened >= 30000) {
                    reported30 = true
                    listen("play_30s")
                }
                val fade = preferences.getInt("native_fade_seconds", 0).coerceIn(0, 12) * 1000L
                processor.fade =
                    if (party != null || fade == 0L || duration <= 0) 1f
                    else
                        minOf(1f, position.toFloat() / fade, (duration - position).toFloat() / fade)
                            .coerceIn(0f, 1f)
                if (now - lastPublished >= 1000) {
                    lastPublished = now
                    publish()
                    onChanged?.invoke()
                }
                if (now - lastSaved >= 10000) {
                    lastSaved = now
                    persist()
                }
                handler.postDelayed(this, 100)
            }
        }

    private fun player(): ExoPlayer {
        engine?.let {
            return it
        }
        val credentialsScope = app.nativeApi.scope
        val factory = DataSource.Factory { NativeHttpSource(app.nativeApi, credentialsScope) }
        val source = androidx.media3.datasource.DefaultDataSource.Factory(app, factory)
        val renderers =
            object : DefaultRenderersFactory(app) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(processor)).build()
            }
        return ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .build()
            .also { p ->
                engine = p
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                p.volume = volume
                p.shuffleModeEnabled = savedShuffle
                p.repeatMode = savedRepeat
                p.setHandleAudioBecomingNoisy(true)
                p.setWakeMode(C.WAKE_MODE_NETWORK)
                p.addListener(
                    object : Player.Listener {
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            val newIndex = p.currentMediaItemIndex
                            if (isNavigatingHistory || isNavigatingForward) {
                                lastPlayedIndex = newIndex
                            } else {
                                if (lastPlayedIndex in tracks.indices && lastPlayedIndex != newIndex) {
                                    playbackHistory.addLast(lastPlayedIndex)
                                    if (playbackHistory.size > 200) playbackHistory.removeFirst()
                                }
                                forwardHistory.clear()
                                lastPlayedIndex = newIndex
                            }
                            if (listenTrack != null)
                                listen(
                                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                                        "play_complete"
                                    else "skip"
                                )
                            sessionId = UUID.randomUUID().toString()
                            listened = 0
                            reported30 = false
                            listenTrack = current?.let { JSONObject(it.toString()) }
                            metadata()
                            listen("play_start")
                            persist()
                        }

                        override fun onEvents(player: Player, events: Player.Events) {
                            publish()
                            onChanged?.invoke()
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) party?.ready()
                            if (state == Player.STATE_ENDED) {
                                if (party != null) party?.ended() else stop()
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            failure =
                                "Playback failed. Check the connection or try another recording."
                            app.mediaController.updateState("error", position, duration)
                            onChanged?.invoke()
                        }
                    }
                )
                for (action in
                    listOf("play", "pause", "stop", "nexttrack", "previoustrack", "seekto")) app
                    .mediaController
                    .receive(
                        JSONObject()
                            .put("type", "action")
                            .put("action", action)
                            .put("enabled", true)
                            .toString()
                    )
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
    }

    fun play(list: List<JSONObject>, start: Int = 0, autoplay: Boolean = true, at: Long = 0) {
        if (list.isEmpty()) return
        val scope = app.nativeLibrary.scope()
        if (ownerScope != null && ownerScope != scope) stop()
        ownerScope = scope
        require(list.size <= 10000)
        failure = null
        tracks = list.map { JSONObject(it.toString()) }
        savedIndex = start.coerceIn(tracks.indices)
        savedPosition = at.coerceAtLeast(0)
        playbackHistory.clear()
        forwardHistory.clear()
        lastPlayedIndex = savedIndex
        val initialTrackId = NativeTrack.localId(tracks[savedIndex])
        val initialGain = if (initialTrackId > 0) trackGainCache.get(initialTrackId) else null
        if (initialGain != null && preferences.getBoolean("native_replaygain", false)) {
            processor.gain = initialGain
            processor.syncGainImmediate()
        }
        val p = player()
        val downloaded =
            runCatching { app.nativeLibrary.audioFiles(tracks.map(NativeTrack::reference)) }
                .getOrDefault(emptyMap())
        p.setMediaItems(
            tracks.map { track ->
                val file = downloaded[NativeTrack.reference(track)]
                val uri =
                    file?.let { Uri.fromFile(it) }
                        ?: Uri.parse(
                            NativeApi.resolve(app.nativeApi.origin!!, NativeTrack.stream(track))
                                .toString()
                        )
                MediaItem.Builder().setMediaId(NativeTrack.reference(track)).setUri(uri).build()
            },
            savedIndex,
            savedPosition,
        )
        p.prepare()
        if (autoplay) {
            app.mediaController.receive(JSONObject().put("type", "resume").toString())
            p.play()
        }
        metadata()
        publish()
        persist()
    }

    fun insertNext(track: JSONObject) = enqueue(track, true)

    fun enqueue(track: JSONObject, next: Boolean = false) {
        if (party != null || presentation != null || ownerScope != null && !ownsAccount()) return
        val queued = JSONObject(track.toString()).put("_manual", true)
        val p = engine
        if (p == null) {
            play(listOf(queued))
            return
        }
        val following = (p.currentMediaItemIndex + 1).coerceAtMost(tracks.size)
        val position =
            if (next) following
            else
                (following until tracks.size).firstOrNull { !tracks[it].optBoolean("_manual") }
                    ?: tracks.size
        val all = tracks.toMutableList()
        all.add(position, queued)
        tracks = all
        val file = runCatching { app.nativeLibrary.audio(NativeTrack.reference(track)) }.getOrNull()
        val uri =
            file?.let(Uri::fromFile)
                ?: Uri.parse(
                    NativeApi.resolve(app.nativeApi.origin!!, NativeTrack.stream(track)).toString()
                )
        p.addMediaItem(
            position,
            MediaItem.Builder().setMediaId(NativeTrack.reference(track)).setUri(uri).build(),
        )
        persist()
        onChanged?.invoke()
    }

    fun selectQueue(target: Int): Boolean {
        if (party != null || presentation != null || !ownsAccount() || target !in tracks.indices)
            return false
        val p = engine
        if (p == null) play(tracks, target, at = if (target == savedIndex) savedPosition else 0)
        else {
            val currentIdx = p.currentMediaItemIndex
            if (currentIdx in tracks.indices && currentIdx != target) {
                playbackHistory.addLast(currentIdx)
                if (playbackHistory.size > 200) playbackHistory.removeFirst()
            }
            forwardHistory.clear()
            lastPlayedIndex = target
            if (target != p.currentMediaItemIndex) p.seekTo(target, 0)
            p.play()
            persist()
        }
        return true
    }

    fun moveQueued(from: Int, to: Int): Boolean {
        val p = engine ?: return false
        if (
            party != null ||
                presentation != null ||
                !ownsAccount() ||
                from <= index ||
                to <= index ||
                from !in tracks.indices ||
                to !in tracks.indices ||
                !tracks[from].optBoolean("_manual") ||
                !tracks[to].optBoolean("_manual")
        )
            return false
        val all = tracks.toMutableList()
        all.add(to, all.removeAt(from))
        tracks = all
        p.moveMediaItem(from, to)
        persist()
        onChanged?.invoke()
        return true
    }

    fun removeQueued(target: Int): Boolean {
        val p = engine ?: return false
        if (
            party != null ||
                presentation != null ||
                !ownsAccount() ||
                target <= index ||
                target !in tracks.indices ||
                !tracks[target].optBoolean("_manual")
        )
            return false
        tracks = tracks.toMutableList().apply { removeAt(target) }
        p.removeMediaItem(target)
        persist()
        onChanged?.invoke()
        return true
    }

    private fun nextTrack() {
        val p = engine ?: return
        if (forwardHistory.isNotEmpty()) {
            val nextIdx = forwardHistory.removeLast()
            if (nextIdx in tracks.indices) {
                isNavigatingForward = true
                try {
                    val currentIdx = p.currentMediaItemIndex
                    if (currentIdx in tracks.indices && currentIdx != nextIdx) {
                        playbackHistory.addLast(currentIdx)
                        if (playbackHistory.size > 200) playbackHistory.removeFirst()
                    }
                    lastPlayedIndex = nextIdx
                    p.seekTo(nextIdx, 0)
                    p.play()
                } finally {
                    isNavigatingForward = false
                }
                return
            }
        }
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
            p.play()
        }
    }

    private fun previousTrack(fromGesture: Boolean = false) {
        val p = engine ?: return
        if (playbackHistory.isNotEmpty()) {
            val prevIdx = playbackHistory.removeLast()
            if (prevIdx in tracks.indices) {
                val currentIdx = p.currentMediaItemIndex
                if (currentIdx in tracks.indices && currentIdx != prevIdx) {
                    forwardHistory.addLast(currentIdx)
                    if (forwardHistory.size > 200) forwardHistory.removeFirst()
                }
                isNavigatingHistory = true
                try {
                    lastPlayedIndex = prevIdx
                    p.seekTo(prevIdx, 0)
                    p.play()
                } finally {
                    isNavigatingHistory = false
                }
                return
            }
        }
        if (!p.shuffleModeEnabled && p.hasPreviousMediaItem()) {
            p.seekToPreviousMediaItem()
            p.play()
        } else {
            p.seekTo(0)
        }
    }

    fun command(action: String, value: Long = 0) {
        if (action != "stop" && ownerScope != null && !ownsAccount()) {
            release()
            return
        }
        presentation?.let {
            it(action, value)
            return
        }
        when (action) {
            "openplayer" -> onOpenPlayer?.invoke()
            "play" -> {
                if (party != null) party?.play()
                else {
                    if (engine == null && tracks.isNotEmpty())
                        play(tracks, savedIndex, true, savedPosition)
                    else {
                        if (engine?.playerError != null) {
                            failure = null
                            engine?.prepare()
                        }
                        engine?.play()
                    }
                }
            }
            "pause" -> {
                engine?.pause()
                persist()
            }
            "stop" -> stop()
            "seekto" -> {
                if (party != null) party?.control("seek", value)
                else engine?.seekTo(value.coerceIn(0, duration.coerceAtLeast(0)))
                persist()
            }
            "nexttrack" -> {
                if (party != null) party?.control("next")
                else nextTrack()
            }
            "previousgesture" -> {
                if (party != null) party?.control("previous")
                else previousTrack(fromGesture = true)
            }
            "previoustrack" -> {
                if (party != null) party?.control("previous")
                else if (engine?.currentPosition ?: 0 > 3000) engine?.seekTo(0)
                else previousTrack(fromGesture = false)
            }
            "favorite" -> favorite()
            "shuffle" -> {
                if (party != null) return
                engine?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                controls()
                persist()
            }
            "repeat" -> {
                if (party != null) return
                engine?.let {
                    it.repeatMode =
                        when (it.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                }
                controls()
                persist()
            }
        }
        publish()
        onChanged?.invoke()
    }

    fun localPlay(value: Boolean) {
        if (value) engine?.play() else engine?.pause()
        publish()
    }

    fun align(position: Long) {
        engine?.seekTo(position.coerceAtLeast(0))
    }

    fun setVolume(value: Float, save: Boolean = false) {
        if (!value.isFinite()) return
        volume = value.coerceIn(0f, 1f)
        engine?.volume = volume
        if (save) {
            persist()
            app.nativeApi.execute({
                app.nativeApi.request(
                    "/api/playback/volume",
                    "PUT",
                    JSONObject().put("volume", volume),
                )
            }) { _, _ ->
            }
        }
    }

    fun refreshPreferences() {
        val scope = app.nativeApi.scope
        app.nativeApi.execute({ app.nativeApi.request("/api/playback/queue-state") }) { value, error
            ->
            if (error != null || value !is JSONObject || scope != app.nativeApi.scope || playing)
                return@execute
            if (!value.isNull("volume")) setVolume(value.optDouble("volume", 1.0).toFloat())
            if (tracks.isEmpty()) restoreRemoteQueue(value)
        }
    }

    internal fun restoreRemoteQueue(value: JSONObject) {
        if (tracks.isNotEmpty() || engine != null || party != null || presentation != null) return
        ownerScope = app.nativeLibrary.scope()
        val context =
            NativeTrack.items(value.optJSONArray("context_queue"))
                .take(500)
                .map { JSONObject(it.toString()).apply { remove("_manual") } }
                .toMutableList()
        val current = value.optJSONObject("current_track")
        val at = value.optInt("context_index", -1).coerceIn(-1, context.lastIndex.coerceAtLeast(-1))
        savedIndex =
            if (
                current != null &&
                    (at < 0 || NativeTrack.reference(context[at]) != NativeTrack.reference(current))
            ) {
                (at + 1).coerceIn(0, context.size).also {
                    context.add(it, JSONObject(current.toString()).put("_manual", true))
                }
            } else at.coerceAtLeast(0)
        val manual =
            NativeTrack.items(value.optJSONArray("manual_queue")).take(500).map {
                JSONObject(it.toString()).put("_manual", true)
            }
        context.addAll((savedIndex + 1).coerceAtMost(context.size), manual)
        tracks = context
        savedIndex = savedIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        val seconds = value.optDouble("current_time", 0.0)
        savedPosition =
            if (seconds.isFinite()) (seconds.coerceIn(0.0, 86400.0) * 1000).toLong() else 0
        savedShuffle = value.optBoolean("shuffle_mode")
        savedRepeat =
            when (value.optString("repeat_mode")) {
                "all" -> Player.REPEAT_MODE_ALL
                "one" -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        onChanged?.invoke()
    }

    fun favorite(track: JSONObject? = current, done: (Throwable?) -> Unit = {}) {
        if (tracks.isNotEmpty() && !ownsAccount()) {
            done(SecurityException("Account changed"))
            return
        }
        if (track == null) return
        if (NativeTrack.localId(track) <= 0) {
            done(IllegalArgumentException("Only local library tracks support favorites"))
            return
        }
        val ref = NativeTrack.reference(track)
        val liked = track.optBoolean("is_favorite")
        val scope = app.nativeApi.scope
        app.nativeApi.execute({
            app.nativeApi.request(
                "/api/favorites/${NativeApi.encode(ref)}",
                if (liked) "DELETE" else "POST",
            )
        }) { _, error ->
            if (scope != app.nativeApi.scope) return@execute
            if (error == null || error is IOException && error !is NativeApi.Failure) {
                if (error != null)
                    app.nativeSync.enqueue(
                        "toggle_favorite",
                        JSONObject().put("trackId", ref).put("liked", !liked),
                    )
                track.put("is_favorite", !liked)
                runCatching {
                    app.nativeLibrary.get("track", ref)?.let {
                        app.nativeLibrary.put("track", ref, it.put("is_favorite", !liked))
                    }
                }
                controls()
                onChanged?.invoke()
                done(null)
            } else {
                failure = error.message
                onChanged?.invoke()
                done(error)
            }
        }
    }

    private fun prefetchNextTrackGain() {
        if (!preferences.getBoolean("native_replaygain", false)) return
        val nextIdx = index + 1
        if (nextIdx !in tracks.indices) return
        val nextTrack = tracks[nextIdx]
        val nextId = NativeTrack.localId(nextTrack)
        if (nextId <= 0 || trackGainCache.get(nextId) != null) return
        app.nativeApi.execute({
            app.nativeApi.request("/api/track/$nextId/gain")
        }) { value, error ->
            if (error == null && value is JSONObject) {
                val computed =
                    NativeAudioProcessor.replayGain(
                        value.optDouble("gain_db", 0.0),
                        value.optDouble("peak", 1.0),
                    )
                trackGainCache.put(nextId, computed)
            }
        }
    }

    private fun metadata() {
        if (!ownsAccount()) return
        val t = current ?: return
        val trackId = NativeTrack.localId(t)
        val rgEnabled = preferences.getBoolean("native_replaygain", false)
        if (rgEnabled && trackId > 0) {
            val cached = trackGainCache.get(trackId)
            if (cached != null) {
                processor.gain = cached
            }
        } else {
            processor.gain = 1f
        }
        val stamp = ++gainGeneration
        if (preferences.getBoolean("native_replaygain", false) && NativeTrack.localId(t) > 0) {
            app.nativeApi.execute({
                app.nativeApi.request("/api/track/${NativeTrack.localId(t)}/gain")
            }) { value, error ->
                if (
                    error == null &&
                        stamp == gainGeneration &&
                        preferences.getBoolean("native_replaygain", false) &&
                        value is JSONObject
                ) {
                    val computed =
                        NativeAudioProcessor.replayGain(
                            value.optDouble("gain_db", 0.0),
                            value.optDouble("peak", 1.0),
                        )
                    trackGainCache.put(trackId, computed)
                    processor.gain = computed
                }
                prefetchNextTrackGain()
            }
        }
        val stored =
            runCatching { app.nativeLibrary.get("track", NativeTrack.reference(t)) }.getOrNull()
        val raw =
            stored?.optString("offline_artwork").orEmpty().ifBlank { t.optString("thumbnail") }
        val artwork =
            if (raw.startsWith("data:image/")) raw
            else if (raw.startsWith("/") && !raw.startsWith("//")) app.nativeApi.origin!!.rule + raw
            else if (raw.startsWith("https://"))
                app.nativeApi.origin!!.rule + "/api/proxy-image?url=" + NativeApi.encode(raw)
            else ""
        app.mediaController.receive(
            JSONObject()
                .put("type", "metadata")
                .put("title", t.optString("title"))
                .put("artist", t.optString("artist"))
                .put("album", t.optString("album"))
                .put("artwork", artwork)
                .put("offline", raw.startsWith("data:image/"))
                .toString()
        )
        controls()
    }

    private fun controls() {
        val p = engine
        val t = current
        app.mediaController.receive(
            JSONObject()
                .put("type", "controls")
                .put("canFavorite", t != null && NativeTrack.localId(t) > 0)
                .put("favorite", t?.optBoolean("is_favorite") == true)
                .put("canShuffle", party == null)
                .put("shuffle", p?.shuffleModeEnabled == true)
                .put("canRepeat", party == null)
                .put(
                    "repeat",
                    when (p?.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "all"
                        Player.REPEAT_MODE_ONE -> "one"
                        else -> "off"
                    },
                )
                .toString()
        )
    }

    private fun publish() {
        if (!ownsAccount()) return
        val p = engine ?: return
        val state =
            when {
                p.playerError != null -> "error"
                p.isPlaying -> "playing"
                p.playWhenReady && p.playbackState == Player.STATE_BUFFERING -> "buffering"
                else -> "paused"
            }
        app.mediaController.updateState(state, position, duration)
    }

    private fun listen(event: String) {
        if (!ownsAccount()) return
        val t = listenTrack ?: return
        val id = NativeTrack.localId(t)
        if (id <= 0 || sessionId.isBlank()) return
        val payload =
            JSONObject()
                .put("track_id", id)
                .put("event_type", event)
                .put("played_seconds", listened / 1000.0)
                .put("duration_seconds", t.optDouble("duration", 0.0))
                .put("session_id", sessionId)
                .put("client_event_id", UUID.randomUUID().toString())
                .put(
                    "completed",
                    event == "play_complete" &&
                        listened >=
                            minOf(
                                30000L,
                                ((t.optDouble("duration", 0.0) * 500).toLong()).coerceAtLeast(1),
                            ),
                )
                .put("context_type", if (party != null) "party" else "native")
        val scope = runCatching { app.nativeLibrary.scope() }.getOrNull() ?: return
        app.nativeApi.execute({
            if (app.nativeLibrary.scope() != scope) error("Account changed")
            app.nativeApi.request("/api/activity/listen", "POST", payload)
        }) { _, error ->
            if (
                error is IOException &&
                    runCatching { app.nativeLibrary.scope() == scope }.getOrDefault(false)
            )
                app.nativeLibrary.put(
                    "pending",
                    payload.getString("client_event_id"),
                    JSONObject().put("type", "listen_progress").put("payload", payload),
                    scope,
                )
        }
    }

    fun persist() {
        if (tracks.isEmpty() || party != null) return
        val scope = ownerScope ?: return
        savedShuffle = engine?.shuffleModeEnabled ?: savedShuffle
        savedRepeat = engine?.repeatMode ?: savedRepeat
        runCatching {
            app.nativeLibrary.put(
                "snapshot",
                "queue",
                JSONObject()
                    .put("tracks", JSONArray(tracks.map(NativeTrack::portable)))
                    .put("index", index)
                    .put("position", position)
                    .put("shuffle", savedShuffle)
                    .put("repeat", savedRepeat)
                    .put("volume", volume),
                scope,
            )
        }
        handler.removeCallbacks(saveRemote)
        handler.postDelayed(saveRemote, 800)
    }

    internal fun remoteQueueState(): JSONObject {
        val context = tracks.filterNot { it.optBoolean("_manual") }
        val contextIndex = tracks.take(index + 1).count { !it.optBoolean("_manual") } - 1
        val first = (contextIndex - 250).coerceAtLeast(0)
        val portable = JSONArray(context.drop(first).take(500).map(NativeTrack::portable))
        return JSONObject()
            .put("context_queue", portable)
            .put(
                "manual_queue",
                JSONArray(
                    tracks
                        .drop(index + 1)
                        .filter { it.optBoolean("_manual") }
                        .take(500)
                        .map(NativeTrack::portable)
                ),
            )
            .put("current_track", current?.let(NativeTrack::portable))
            .put("context_index", contextIndex - first)
            .put("original_context_queue", portable)
            .put("current_time", position / 1000.0)
            .put("duration", duration / 1000.0)
            .put("was_playing", playing)
            .put("shuffle_mode", savedShuffle)
            .put(
                "repeat_mode",
                when (savedRepeat) {
                    Player.REPEAT_MODE_ALL -> "all"
                    Player.REPEAT_MODE_ONE -> "one"
                    else -> "off"
                },
            )
            .put("volume", volume)
    }

    private fun syncQueue() {
        if (tracks.isEmpty() || party != null || presentation != null || !ownsAccount()) return
        val scope = app.nativeApi.scope
        val data = remoteQueueState()
        app.nativeApi.execute({
            if (scope != app.nativeApi.scope) error("Account changed")
            app.nativeApi.request("/api/playback/queue-state", "PUT", data)
        }) { _, _ ->
        }
    }

    fun restore() {
        val scope = app.nativeLibrary.scope()
        if (engine != null) {
            if (ownerScope == scope) return
            stop()
        }
        ownerScope = scope
        tracks = emptyList()
        savedIndex = 0
        savedPosition = 0
        savedShuffle = false
        savedRepeat = Player.REPEAT_MODE_OFF
        val saved =
            runCatching { app.nativeLibrary.get("snapshot", "queue", scope) }.getOrNull() ?: return
        tracks = NativeTrack.items(saved)
        savedIndex = saved.optInt("index").coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        savedPosition = saved.optLong("position")
        savedShuffle = saved.optBoolean("shuffle")
        savedRepeat = saved.optInt("repeat", Player.REPEAT_MODE_OFF).coerceIn(0, 2)
        playbackHistory.clear()
        forwardHistory.clear()
        lastPlayedIndex = savedIndex
        setVolume(saved.optDouble("volume", 1.0).toFloat())
    }

    fun present(controls: (String, Long) -> Unit): Boolean {
        if (party != null || presentation != null || ownerScope != null && !ownsAccount())
            return false
        if (ownerScope == null) ownerScope = app.nativeLibrary.scope()
        command("pause")
        processor.sampling = false
        presentation = controls
        return true
    }

    fun endPresentation(controls: (String, Long) -> Unit) {
        if (presentation !== controls) return
        presentation = null
        if (current != null && ownsAccount()) {
            metadata()
            publish()
        } else app.mediaController.reset()
    }

    fun stop() {
        presentation?.invoke("stop", 0)
        presentation = null
        listen(if (engine?.playbackState == Player.STATE_ENDED) "play_complete" else "skip")
        listenTrack = null
        processor.sampling = false
        savedIndex = index
        savedPosition = position
        persist()
        engine?.stop()
        engine?.release()
        engine = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(sleep)
        playbackHistory.clear()
        forwardHistory.clear()
        lastPlayedIndex = -1
        tracks = emptyList()
        app.mediaController.reset()
        onChanged?.invoke()
    }

    fun release() {
        party?.close()
        party = null
        stop()
    }
}
