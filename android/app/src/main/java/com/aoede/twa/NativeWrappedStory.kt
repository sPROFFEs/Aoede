package com.aoede.twa

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject

/** Native translation of wrapped_story/story.js and its mobile CSS; no browser/audio bridge. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeWrappedStory(
    private val ui: NativeUi,
    private val data: JSONObject,
    private val saved: android.os.Bundle? = null,
) : FrameLayout(ui.activity) {
    private val fg = 0xfff7f2ee.toInt()
    private val muted = 0xadf7f2ee.toInt()
    private val border = 0x2ef7f2ee
    private val main = Handler(Looper.getMainLooper())
    private val songs = NativeWrapped.tracks(data).filter { NativeTrack.localId(it) > 0 }.take(100)
    private val artists =
        NativeTrack.items(data.optJSONArray("top_artists"))
            .filter { it.optInt("stream_count") > 0 }
            .take(5)
    private val scope = ui.api.scope
    private var index = saved?.getInt("slide")?.coerceIn(0, 9) ?: 0
    private var elapsed = saved?.getLong("elapsed")?.coerceIn(0, 50000) ?: 0L
    private var last = 0L
    private var userPaused = saved?.getBoolean("paused") ?: false
    private var foreground = true
    private var focusSuppressed = false
    private var introActive = false
    private var previewFailureShown = false
    private var closed = false
    private var previewSlot = -1
    private var party = data.optJSONObject("_party")
    private var soundtrack: ExoPlayer? = null
    private var preview: ExoPlayer? = null
    private val scene = StoryScene()
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val progress = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val stage =
        ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
    private val previous: NativeIconButton
    private val toggle: NativeIconButton
    private val next: NativeIconButton
    private val transport: (String, Long) -> Unit = { action, _ ->
        when (action) {
            "pause" -> pause(true)
            "play" -> pause(false)
            "nexttrack" -> go(1)
            "previoustrack" -> go(-1)
            "stop" -> {
                pause(true)
                releaseAudio()
                ui.hidePlayer()
            }
            "openplayer" -> Unit
        }
    }

    private val clock =
        object : Runnable {
            override fun run() {
                if (closed || !isAttachedToWindow) return
                if (scope != ui.api.scope) {
                    ui.hidePlayer()
                    return
                }
                val now = SystemClock.uptimeMillis()
                if (!paused && !introActive && last > 0) elapsed += (now - last).coerceIn(0, 500)
                last = now
                updateProgress()
                if (!paused && !introActive && index in listOf(2, 3)) sample()
                if (!paused && elapsed >= interval() && index < 9) go(1)
                main.postDelayed(this, 50)
            }
        }

    private val paused
        get() = userPaused || !foreground || focusSuppressed

    private fun dp(value: Int) = ui.dp(value)

    private fun text(value: String, size: Float, weight: Int = 400, color: Int = fg) =
        TextView(context).apply {
            text = value
            ui.design.typography(this, size, weight)
            setTextColor(color)
            ui.design.lines(this, 1.5f)
        }

    init {
        tag = "native-wrapped-story"
        setBackgroundColor(0xff101018.toInt())
        addView(scene, LayoutParams(-1, -1))
        addView(column, LayoutParams(-1, -1))
        val header =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(8))
            }
        column.addView(header)
        fun link(label: String, action: () -> Unit) =
            Button(context).apply {
                text = label
                isAllCaps = false
                ui.design.typography(this, 13.12f)
                setTextColor(fg)
                setPadding(dp(9), dp(8), dp(9), dp(8))
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(44)
                background = ui.design.shape(0x57000000, 10).apply { setStroke(dp(1), border) }
                setOnClickListener { action() }
            }
        header.addView(link("Summary") { summary() }, LinearLayout.LayoutParams(-2, dp(44)))
        header.addView(
            progress,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(8), 0, dp(8), 0) },
        )
        header.addView(link("Close") { ui.hidePlayer() }, LinearLayout.LayoutParams(-2, dp(44)))
        column.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(18))
            }
        column.addView(controls)
        fun control(glyph: String, label: String, action: () -> Unit) =
            NativeIconButton(context, fg, "glass").apply {
                text = glyph
                contentDescription = label
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = ui.design.shape(0x57000000, 999).apply { setStroke(dp(1), border) }
                setOnClickListener { action() }
                controls.addView(
                    this,
                    LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                        setMargins(dp(5), 0, dp(5), 0)
                    },
                )
            }
        previous = control("chevron-left", "Previous slide") { go(-1) }
        toggle = control("pause", "Pause story") { pause(!userPaused) }
        next = control("chevron-right", "Next slide") { go(1) }
        render(0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ui.player.present(transport)) {
            post { ui.hidePlayer() }
            return
        }
        ui.app.mediaController.receive(
            JSONObject()
                .put("type", "metadata")
                .put("title", "Aoede Wrapped ${data.optInt("year")}")
                .put("artist", ui.api.username)
                .put("album", "Wrapped")
                .toString()
        )
        for (action in listOf("nexttrack", "previoustrack", "seekto")) ui.app.mediaController
            .receive(
                JSONObject()
                    .put("type", "action")
                    .put("action", action)
                    .put("enabled", false)
                    .toString()
            )
        run {
            soundtrack =
                ExoPlayer.Builder(context).build().also { p ->
                    p.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    )
                    p.setHandleAudioBecomingNoisy(true)
                    p.repeatMode = Player.REPEAT_MODE_ONE
                    p.volume = .12f
                    p.addListener(
                        object : Player.Listener {
                            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                                if (closed) return
                                if (
                                    !ready &&
                                        reason in
                                            listOf(
                                                Player
                                                    .PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                                                Player
                                                    .PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                                            )
                                )
                                    pause(true)
                            }

                            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                                if (closed) return
                                focusSuppressed = reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                                preview?.playWhenReady =
                                    !paused && (introActive || index in listOf(2, 3))
                                scene.running(!paused)
                                publish()
                            }
                        }
                    )
                    p.setMediaItem(
                        MediaItem.fromUri(
                            "asset:///web/assets/wrapped_story/audio/wrapped-background.mp3"
                        )
                    )
                    p.prepare()
                    p.playWhenReady = !paused
                }
            val factory = DataSource.Factory { NativeHttpSource(ui.api, scope) }
            preview =
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(DefaultDataSource.Factory(context, factory))
                    )
                    .build()
                    .also { p ->
                        p.volume = .27f
                        p.addListener(
                            object : Player.Listener {
                                override fun onPlayerError(
                                    error: androidx.media3.common.PlaybackException
                                ) {
                                    soundtrack?.volume = .12f
                                    if (!previewFailureShown && !closed) {
                                        previewFailureShown = true
                                        ui.toast(
                                            "This audio preview is unavailable. You can continue watching your recap."
                                        )
                                    }
                                }
                            }
                        )
                    }
        }
        ui.app.mediaController.receive(
            JSONObject()
                .put("type", "controls")
                .put("canFavorite", false)
                .put("canShuffle", false)
                .put("canRepeat", false)
                .toString()
        )
        main.post(clock)
        scene.running(!paused)
        if (saved == null && !ui.reducedMotion()) intro() else publish()
    }

    private fun intro() {
        introActive = true
        soundtrack?.volume = 0f
        preview?.apply {
            volume = .54f
            setMediaItem(
                MediaItem.fromUri("asset:///web/assets/wrapped_story/audio/light-transition.mp3")
            )
            prepare()
            playWhenReady = !paused
        }
        val intro =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xff101018.toInt())
            }
        addView(intro, LayoutParams(-1, -1))
        val eyebrow =
            text("YOUR YEAR IN MUSIC", 13f, color = muted).apply {
                letterSpacing = .16f
                gravity = Gravity.CENTER
            }
        intro.addView(eyebrow)
        val letters =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                clipChildren = false
            }
        intro.addView(
            letters,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(16), 0, dp(16)) },
        )
        for ((i, letter) in "Aoede".withIndex()) {
            val glyph =
                text(letter.toString(), 72f, 900, if (i % 2 == 1) 0xffb9ef78.toInt() else fg)
                    .apply { letterSpacing = -.075f }
            letters.addView(glyph)
            glyph.translationY = dp(100).toFloat()
            glyph.alpha = 0f
            glyph
                .animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(100L + i * 85)
                .setDuration(850)
                .setInterpolator(android.view.animation.DecelerateInterpolator(3f))
                .start()
        }
        intro.addView(
            text("WRAPPED   ${data.optInt("year")}", 15f).apply {
                letterSpacing = .22f
                gravity = Gravity.CENTER
            }
        )
        fun finish() {
            if (intro.parent === this) {
                removeView(intro)
                introActive = false
                preview?.stop()
                preview?.volume = .27f
                soundtrack?.volume = .12f
                elapsed = 0
                last = 0
                publish()
            }
        }
        intro.setOnClickListener { finish() }
        main.postDelayed(
            {
                if (!closed)
                    intro.animate().alpha(0f).setDuration(300).withEndAction { finish() }.start()
            },
            2200,
        )
    }

    private fun interval() =
        if (index == 3) maxOf(10000L, minOf(5, songs.size) * 10000L) else 10000L

    private fun go(direction: Int) {
        if (index == 9 && direction > 0) {
            summary()
            return
        }
        val target = (index + direction).coerceIn(0, 9)
        if (target == index) return
        index = target
        elapsed = 0
        last = 0
        previewSlot = -1
        preview?.stop()
        soundtrack?.volume = .12f
        render(direction)
        publish()
    }

    private fun summary() {
        ui.hidePlayer()
        ui.tools.wrapped()
    }

    private fun render(direction: Int) {
        scene.slide = index
        stage.removeAllViews()
        val short = resources.configuration.screenHeightDp <= 760
        val padding =
            if (short) 22
            else (resources.configuration.screenWidthDp * .07f).toInt().coerceIn(24, 38)
        val gap = if (short) 10 else 14
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (short) Gravity.TOP else Gravity.CENTER_VERTICAL
                setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
                background =
                    if (ui.reduced()) ui.design.shape(0xbb000000.toInt(), 20)
                    else
                        NativeBackdrop(
                            scene,
                            this,
                            0x75000000,
                            dp(20).toFloat(),
                            border,
                            blurDp = 12f,
                            saturation = 1.2f,
                        )
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        stage.addView(card, FrameLayout.LayoutParams(-1, -2))
        fun add(
            value: String,
            size: Float,
            weight: Int = 400,
            color: Int = fg,
            line: Float = 1.5f,
        ): TextView {
            val view = text(value, size, weight, color)
            ui.design.lines(view, line)
            card.addView(
                view,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (card.childCount > 1) topMargin = dp(gap)
                },
            )
            return view
        }
        val kickers =
            listOf(
                "Aoede Wrapped ${data.optInt("year")}",
                "Time listened",
                "Your top song",
                "Top songs",
                "Top artists",
                "Top artist sprint",
                "Wrapped Party",
                "Wrapped Party",
                data.optJSONObject("artist_clip")?.optString("title") ?: "A message from Aoede",
                "Summary",
            )
        add(kickers[index].uppercase(), 12.48f, color = muted).letterSpacing = .075f
        fun title(value: String, feature: Boolean = false) =
            add(
                    value,
                    if (feature) 26.4f else if (index in 3..9) 44f else 48f,
                    700,
                    line = if (feature) 1.08f else .98f,
                )
                .apply { letterSpacing = -.052f }
        fun cover(path: String, size: Int, round: Boolean = false) =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.design.shape(0x20ffffff, if (round) 999 else 18)
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                card.addView(
                    this,
                    LinearLayout.LayoutParams(dp(size), dp(size)).apply { topMargin = dp(gap) },
                )
                ui.artwork(this, path)
            }
        when (index) {
            0 -> {
                cover("/user/avatar/${NativeApi.encode(ui.api.username)}", 68, true)
                title(ui.api.username.ifBlank { "Your year" })
                add(
                    "A fast pass through what actually played, repeated and survived the year.",
                    15.68f,
                    color = muted,
                )
            }
            1 -> {
                val duration = NativeWrapped.duration(data.optDouble("minutes_listened", 0.0))
                val split = duration.lastIndexOf(' ')
                add(duration.substring(0, split), 88f, 800, line = .85f).letterSpacing = -.09f
                add(duration.substring(split + 1), 32f, color = muted)
                add(
                    "${data.optInt("qualified_event_count", data.optInt("event_count"))} tracked listens across your library.",
                    15.68f,
                    color = muted,
                )
            }
            2 -> {
                val track = songs.firstOrNull()
                if (track == null) {
                    title("No top song yet", true)
                    add(
                        "Play more tracks and this slide will unlock with your #1.",
                        15.68f,
                        color = muted,
                    )
                } else {
                    cover(ui.artworkPath(track), 92)
                    title(track.optString("title"), true)
                    add(
                        "${track.optString("artist")} - ${track.optInt("stream_count")} plays",
                        15.68f,
                        color = muted,
                    )
                }
            }
            3 -> {
                title("The repeat list")
                for ((i, track) in songs.take(5).withIndex()) entry(
                    card,
                    "#${i+1}",
                    track.optString("title"),
                    "${track.optString("artist")} · ${track.optInt("stream_count")} plays",
                    ui.artworkPath(track),
                    i,
                )
            }
            4 -> {
                title(artists.firstOrNull()?.optString("artist") ?: "No top artists yet")
                for ((i, artist) in artists.withIndex()) entry(
                    card,
                    "#${i+1}",
                    artist.optString("artist"),
                    "${artist.optInt("stream_count")} plays",
                    artist.optString("artist_image"),
                    i,
                )
            }
            5 -> {
                title("Who led each month")
                val months = java.text.DateFormatSymbols(java.util.Locale.ENGLISH).shortMonths
                for ((i, month) in
                    NativeTrack.items(data.optJSONArray("top_artist_sprint"))
                        .filter { (it.optJSONArray("artists")?.length() ?: 0) > 0 }
                        .withIndex()) {
                    val leader = month.getJSONArray("artists").getJSONObject(0)
                    entry(
                        card,
                        months.getOrNull(month.optInt("month", 1) - 1).orEmpty(),
                        leader.optString("artist"),
                        "${leader.optInt("stream_count")} plays",
                        leader.optString("artist_image"),
                        i,
                    )
                }
            }
            6,
            7 -> {
                title(if (index == 6) "Most listening time" else "Biggest repeaters")
                val rows =
                    NativeTrack.items(
                            party?.optJSONArray(
                                if (index == 6) "most_minutes_listened" else "biggest_repeaters"
                            )
                        )
                        .take(5)
                if (rows.isEmpty())
                    add(
                        if (index == 6)
                            "No party ranking yet. Ask everyone to listen more and come back later."
                        else
                            "No repeater ranking yet. Replays will appear here once data is stable.",
                        15.68f,
                        color = muted,
                    )
                for ((i, row) in rows.withIndex()) entry(
                    card,
                    "#${i+1}",
                    row.optString("username"),
                    if (index == 6) NativeWrapped.duration(row.optDouble("minutes_listened", 0.0))
                    else "${row.optInt("stream_count")} plays",
                    "/user/avatar/${NativeApi.encode(row.optString("username"))}",
                    i,
                    true,
                )
            }
            8 -> {
                title("For the record")
                add(
                    data.optJSONObject("artist_clip")?.optString("message").orEmpty(),
                    15.68f,
                    color = muted,
                )
            }
            9 -> {
                title("Your wrapped at a glance")
                add(
                    "Save your top songs playlist, then open the full summary.",
                    15.68f,
                    color = muted,
                )
                for ((caption, value) in
                    listOf(
                        "Listened" to
                            NativeWrapped.duration(data.optDouble("minutes_listened", 0.0)),
                        "Tracked listens" to
                            data
                                .optInt("qualified_event_count", data.optInt("event_count"))
                                .toString(),
                        "Top artist" to (artists.firstOrNull()?.optString("artist") ?: "No data"),
                        "Top song" to (songs.firstOrNull()?.optString("title") ?: "No data"),
                        "Top Songs playlist" to "${songs.size} tracks",
                    )) {
                    val stat =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            background = ui.design.shape(0x0dffffff, 12)
                        }
                    card.addView(
                        stat,
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
                    )
                    stat.addView(text(caption, 13.6f, color = muted))
                    stat.addView(text(value, 16f, 700))
                }
                val save = ui.button("Save Top Songs", card) {}
                save.background = ui.design.touch(fg, 10, true)
                save.setTextColor(0xff111111.toInt())
                save.isEnabled = songs.isNotEmpty()
                save.setOnClickListener { NativeWrapped(ui).save(data, save) }
                ui.button("Summary", card) { summary() }.setTextColor(fg)
            }
        }
        previous.isEnabled = index > 0
        previous.alpha = if (index > 0) 1f else .4f
        next.contentDescription = if (index == 9) "Open summary" else "Next slide"
        progress.removeAllViews()
        repeat(10) { i ->
            val segment =
                FrameLayout(context).apply { background = ui.design.shape(0x38ffffff, 999) }
            segment.addView(
                View(context).apply {
                    tag = "fill"
                    setBackgroundColor(fg)
                    pivotX = 0f
                    scaleX = if (i < index) 1f else 0f
                },
                LayoutParams(-1, -1),
            )
            progress.addView(
                segment,
                LinearLayout.LayoutParams(0, dp(2), 1f).apply { if (i > 0) marginStart = dp(5) },
            )
        }
        stage.scrollTo(0, 0)
        if (!ui.reducedMotion()) {
            card.alpha = 0f
            card.translationX = dp(28) * direction.toFloat()
            card.scaleX = .985f
            card.scaleY = .985f
            card
                .animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .start()
            for (i in 0 until card.childCount) card.getChildAt(i).apply {
                alpha = 0f
                translationY = dp(10).toFloat()
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((200L + i * 85).coerceAtMost(700))
                    .setDuration(380)
                    .start()
            }
        }
    }

    private fun entry(
        parent: LinearLayout,
        rank: String,
        title: String,
        detail: String,
        artwork: String,
        order: Int,
        avatar: Boolean = false,
    ) {
        val row =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
        parent.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (order == 0) 14 else 10) },
        )
        row.addView(text(rank, 14f, color = muted), LinearLayout.LayoutParams(dp(32), -2))
        val image =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.design.shape(0x20ffffff, if (avatar) 999 else 9)
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
        row.addView(image, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        if (artwork.isNotBlank()) ui.artwork(image, artwork)
        else image.setImageDrawable(NativeIcon("user", fg))
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        copy.addView(text(title, 16f, 700))
        copy.addView(text(detail, 13f, color = muted))
        parent.addView(
            View(context).apply { setBackgroundColor(border) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
    }

    private fun updateProgress() {
        if (index < progress.childCount)
            (progress.getChildAt(index) as FrameLayout).getChildAt(0).scaleX =
                (elapsed.toFloat() / interval()).coerceIn(0f, 1f)
    }

    private fun sample() {
        val slot = if (index == 3) (elapsed / 10000).toInt().coerceAtMost(4) else 0
        if (slot == previewSlot) return
        previewSlot = slot
        val track = songs.getOrNull(slot) ?: return
        preview?.apply {
            val duration = track.optDouble("duration", 0.0)
            val start =
                if (duration.isFinite() && duration > 18)
                    (maxOf(0.0, duration - 12.0) * .4 * 1000).toLong()
                else 0L
            setMediaItem(
                MediaItem.fromUri(
                    ui.api.origin!!.rule + "/api/stream/${NativeTrack.localId(track)}"
                ),
                start,
            )
            prepare()
            playWhenReady = !paused
        }
        soundtrack?.volume = .045f
    }

    private fun pause(value: Boolean) {
        userPaused = value
        applyPause()
    }

    fun foreground(value: Boolean) {
        foreground = value
        applyPause()
    }

    fun saveState() =
        android.os.Bundle().apply {
            putInt("slide", index)
            putLong("elapsed", elapsed)
            putBoolean("paused", userPaused)
        }

    private fun applyPause() {
        last = 0
        soundtrack?.playWhenReady = !userPaused && foreground
        preview?.playWhenReady = !paused && (introActive || index in listOf(2, 3))
        scene.running(!paused)
        toggle.text = if (paused) "play" else "pause"
        toggle.contentDescription = if (paused) "Resume story" else "Pause story"
        publish()
    }

    private fun publish() {
        if (closed || scope != ui.api.scope) return
        if (!paused) ui.app.mediaController.receive(JSONObject().put("type", "resume").toString())
        ui.app.mediaController.updateState(if (paused) "paused" else "playing", elapsed, interval())
    }

    private fun releaseAudio() {
        preview?.release()
        preview = null
        soundtrack?.release()
        soundtrack = null
    }

    override fun onDetachedFromWindow() {
        closed = true
        main.removeCallbacksAndMessages(null)
        releaseAudio()
        scene.running(false)
        ui.player.endPresentation(transport)
        super.onDetachedFromWindow()
    }

    private inner class StoryScene : View(context) {
        var slide = 0
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private val animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 18000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
            }

        fun running(value: Boolean) {
            if (value && !ui.reducedMotion()) {
                if (!animator.isStarted) animator.start() else animator.resume()
            } else if (animator.isStarted) animator.pause()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val palette =
                arrayOf(
                    0xffff9369.toInt() to 0xffb994ff.toInt(),
                    0xffb9ef78.toInt() to 0xff76ccba.toInt(),
                    0xfffaba77.toInt() to 0xffff7e98.toInt(),
                    0xffd1b2ff.toInt() to 0xff8c9aff.toInt(),
                    0xfff4ed83.toInt() to 0xffff9d85.toInt(),
                )[slide % 5]
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(0xff101018.toInt())
            paint.style = Paint.Style.STROKE
            paint.color = palette.first
            paint.alpha = 204
            paint.strokeWidth = maxOf(dp(65).toFloat(), w * .12f)
            val diameter = maxOf(w * .65f, dp(550).toFloat()) * (1 - .14f * phase)
            canvas.drawCircle(
                w * 1.22f - diameter / 2 - w * .08f * phase,
                -h * .35f + diameter / 2 + h * .14f * phase,
                diameter / 2,
                paint,
            )
            paint.style = Paint.Style.FILL
            paint.color = palette.second
            paint.alpha = 209
            canvas.save()
            canvas.rotate(-24f + slide * 27, w / 2, h)
            canvas.drawOval(
                RectF(-w * .2f, h * .9f, w * 1.2f, h * .9f + maxOf(dp(120).toFloat(), h * .28f)),
                paint,
            )
            canvas.restore()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.alpha = 255
            val disc =
                (w * .32f).coerceIn(dp(180).toFloat(), dp(440).toFloat()) * (1 + .15f * phase)
            canvas.drawCircle(
                -w * .12f + disc / 2 + w * .18f * phase,
                h * .22f + disc / 2 + h * .22f * phase,
                disc / 2,
                paint,
            )
        }
    }
}
