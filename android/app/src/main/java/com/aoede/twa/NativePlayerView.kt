package com.aoede.twa

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject

/** Native translation of .fs-content's mobile grid and the Vitrine arrangement. */
class NativePlayerView(private val ui: NativeUi) : FrameLayout(ui.activity) {
    private val player
        get() = ui.player

    private val design
        get() = ui.design

    private val apple
        get() = design.theme == "vitrine"

    private val glass
        get() = design.theme == "glass"

    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val shade = View(context)
    private val grid = PlayerGrid()
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val stage = FrameLayout(context)
    private val cover =
        object : androidx.appcompat.widget.AppCompatImageView(context) {
                private val mask =
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        xfermode =
                            android.graphics.PorterDuffXfermode(
                                android.graphics.PorterDuff.Mode.DST_IN
                            )
                    }

                override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                    super.onSizeChanged(w, h, oldw, oldh)
                    mask.shader =
                        android.graphics.LinearGradient(
                            0f,
                            0f,
                            0f,
                            h.coerceAtLeast(1).toFloat(),
                            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                            floatArrayOf(0f, .88f, 1f),
                            android.graphics.Shader.TileMode.CLAMP,
                        )
                }

                override fun onDraw(canvas: android.graphics.Canvas) {
                    if (!glass) {
                        super.onDraw(canvas)
                        return
                    }
                    val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                    super.onDraw(canvas)
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
                    canvas.restoreToCount(layer)
                }
            }
            .apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Current artwork"
            }
    private val info = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val detailCover =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
    private var volumePopup: PopupWindow? = null
    private val title = text(if (glass) 18f else 22f, 700)
    private val artist = text(15.2f)
    private val viewLabel = text(if (apple) 13f else 16f, 600)
    private var backdropBitmap: Bitmap? = null
    private val seek = slider("Track position", 1000)
    private val elapsed = text(12.8f)
    private var showRemainingTime = false
    private var userScrolledLyrics = false
    private var followLyricBtn: TextView? = null
    private val duration = text(12.8f)
    private val controls =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            clipChildren = false
        }
    private val actions =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
    private val toggle: NativeIconButton
    private val favorite: NativeIconButton
    private var shuffle: NativeIconButton? = null
    private var repeat: NativeIconButton? = null
    private val volume = slider("Playback volume", 100)
    private var seeking = false
    var mode = "cover"
        private set
    val presentationMode
        get() = mode

    private var reference = ""
    private var lastPlaying: Boolean? = null
    private var lyricLines = emptyList<Pair<Long, TextView>>()
    private var lyricScroll: ScrollView? = null
    private var lyricActive = -2
    private var modeStamp = 0
    private var queueSignature = ""

    init {
        clipChildren = false
        clipToPadding = false
        grid.clipChildren = false
        grid.clipToPadding = false
        info.clipChildren = false
        tag = "native-fullscreen-player"
        setBackgroundColor(0xff202025.toInt())
        addView(backdrop, LayoutParams(-1, -1))
        addView(shade, LayoutParams(-1, -1))
        addView(
            grid,
            LayoutParams(-1, -1).apply {
                val safe = ui.playerInsets
                setMargins(safe.left, safe.top, safe.right, safe.bottom)
            },
        )
        fun add(name: String, view: View) {
            view.tag = "native-fs-$name"
            grid.addView(view)
        }
        add("header", header)
        control(
            header,
            "chevron-down",
            ui.text("player.minimize", "Minimize player"),
            44,
            22,
            !apple,
        ) {
            ui.hidePlayer()
        }
        header.addView(
            viewLabel,
            LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            },
        )
        viewLabel.gravity = Gravity.CENTER
        control(
            header,
            if (apple) "ellipsis" else "volume-2",
            if (apple) "More playback options" else "Playback volume",
            44,
            22,
            glass,
        ) {
            if (apple) playbackMenu() else volumeDialog()
        }
        stage.addView(cover, LayoutParams(-1, -1, Gravity.CENTER))
        cover.background =
            if (glass) null else design.shape(Color.TRANSPARENT, if (apple) 18 else 8)
        cover.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        cover.clipToOutline = !glass
        stage.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (!apple) return@addOnLayoutChangeListener
            val size = minOf(r - l, b - t).coerceAtLeast(0)
            val lp = cover.layoutParams as LayoutParams
            if (lp.width != size || lp.height != size) {
                lp.width = size
                lp.height = size
                cover.layoutParams = lp
            }
        }
        add("stage", stage)
        ui.trackGestures(cover, true)
        ui.trackGestures(viewLabel, true)
        val copy =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        artist.maxLines = 1
        artist.ellipsize = TextUtils.TruncateAt.END
        artist.setTextColor(0xffd0d1d6.toInt())
        artist.setLineSpacing(0f, 1f)
        artist.minHeight = 0
        artist.background = design.touch(Color.TRANSPARENT, 4)
        artist.setOnClickListener { openArtist() }
        viewLabel.setOnClickListener { if (mode == "cover") openArtist() }
        copy.addView(title, LinearLayout.LayoutParams(-1, -2))
        copy.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        val infoPadding = if (glass) 11 else if (apple) 4 else 8
        info.setPadding(
            if (glass) dp(13) else 0,
            dp(infoPadding),
            if (glass) dp(13) else 0,
            dp(infoPadding),
        )
        info.addView(
            detailCover,
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) },
        )
        info.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        favorite =
            control(info, "heart", ui.text("player.favorite", "Favorite"), 44, 22, glass) {
                ui.app.mediaController.command("favorite")
            }
        (favorite.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        control(
                info,
                "list-plus",
                ui.text("player.add_to_playlist", "Add to playlist"),
                44,
                22,
                glass,
            ) {
                ui.addCurrentToCollection()
            }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) }
        if (glass) info.background = film(999)
        add("info", info)
        val progress =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        progress.addView(seek, LinearLayout.LayoutParams(-1, dp(28)))
        val clocks = LinearLayout(context)
        elapsed.setTextColor(0xffc5c7d1.toInt())
        duration.setTextColor(0xffc5c7d1.toInt())
        duration.gravity = Gravity.END
        duration.setOnClickListener {
            showRemainingTime = !showRemainingTime
            render()
        }
        clocks.addView(elapsed, LinearLayout.LayoutParams(0, dp(18), 1f))
        clocks.addView(duration, LinearLayout.LayoutParams(0, dp(18), 1f))
        progress.addView(clocks)
        add("progress", progress)
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    seeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    ui.app.mediaController.command("seekto", player.duration * bar.progress / 1000)
                }

                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    if (user) elapsed.text = time(player.duration * value / 1000)
                }
            }
        )
        val width = context.resources.configuration.screenWidthDp
        val skip = (width * .15f).toInt().coerceIn(48, 64)
        val play = (width * .2f).toInt().coerceIn(64, 80)
        fun spacer() {
            controls.addView(
                View(context),
                if (apple) LinearLayout.LayoutParams(dp(24), 1)
                else LinearLayout.LayoutParams(0, 1, 1f),
            )
        }
        if (!apple) {
            shuffle =
                control(controls, "shuffle", "Shuffle", 44, 24, false) {
                    ui.app.mediaController.command("shuffle")
                }
            spacer()
        }
        control(
                controls,
                if (apple) "rewind" else "skip-back",
                "Previous",
                skip,
                if (apple) 32 else 24,
                glass,
            ) {
                ui.trackSlideDirection = -1
                ui.app.mediaController.command("previoustrack")
            }
            .apply { if (apple) color(Color.WHITE, true) }
        spacer()
        toggle =
            control(
                controls,
                "play",
                "Play",
                play,
                if (apple) 48 else if (glass) 32 else 38,
                glass,
            ) {
                if (player.playing) ui.app.mediaController.pauseLocal()
                else ui.app.mediaController.command("play")
            }
        if (design.theme == "classic") toggle.background = design.touch(Color.WHITE, 999)
        spacer()
        control(
                controls,
                if (apple) "fast-forward" else "skip-forward",
                "Next",
                skip,
                if (apple) 32 else 24,
                glass,
            ) {
                ui.trackSlideDirection = 1
                ui.app.mediaController.command("nexttrack")
            }
            .apply { if (apple) color(Color.WHITE, true) }
        if (!apple) {
            spacer()
            repeat =
                control(controls, "repeat", "Repeat", 44, 24, false) {
                    ui.app.mediaController.command("repeat")
                }
        }
        add("controls", controls)
        val volumeRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        control(volumeRow, "volume-2", "Mute", 44, 22, false) {
                player.setVolume(if (player.volume > 0) 0f else .7f, true)
            }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14) }
        volumeRow.addView(volume, LinearLayout.LayoutParams(0, dp(44), 1f))
        control(volumeRow, "volume-2", "Maximum volume", 32, 22, false) {
                player.setVolume(1f, true)
            }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(14) }
        add("volume", volumeRow)
        volumeRow.visibility = if (apple) View.VISIBLE else View.GONE
        volume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    player.setVolume(bar.progress / 100f, true)
                }

                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    if (user) player.setVolume(value / 100f)
                }
            }
        )
        if (glass) actions.background = film(999)
        val actionItems =
            if (apple) listOf("mic-2", "volume-2", "list-music")
            else listOf("disc-3", "mic-2", "list-music", "timer")
        if (!apple) actions.setPadding(dp(4), 0, dp(4), 0)
        if (design.theme != "classic")
            actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        for ((index, glyph) in actionItems.withIndex()) {
            val action =
                control(
                    actions,
                    glyph,
                    when (glyph) {
                        "mic-2" -> "Lyrics"
                        "volume-2" -> "Playback volume"
                        "list-music" -> "Queue"
                        "timer" -> "Sleep timer"
                        else -> "Artwork"
                    },
                    if (apple) 48 else 44,
                    24,
                    false,
                ) {
                    when (glyph) {
                        "mic-2" -> setMode(if (mode == "lyrics") "cover" else "lyrics")
                        "list-music" -> setMode(if (mode == "queue") "cover" else "queue")
                        "volume-2" -> volumeDialog()
                        "timer" -> sleepTimer()
                        else -> setMode("cover")
                    }
                }
            action.tag = glyph
            if (index < actionItems.lastIndex || design.theme != "classic")
                actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
        add("actions", actions)
        render()
    }

    fun applyInsets(safe: android.graphics.Rect) {
        val params = grid.layoutParams as LayoutParams
        if (
            params.leftMargin != safe.left ||
                params.topMargin != safe.top ||
                params.rightMargin != safe.right ||
                params.bottomMargin != safe.bottom
        ) {
            params.setMargins(safe.left, safe.top, safe.right, safe.bottom)
            grid.layoutParams = params
        }
    }

    private fun dp(value: Int) = ui.dp(value)

    private fun text(size: Float, weight: Int = 400) =
        TextView(context).apply {
            design.typography(this, size, weight)
            setTextColor(Color.WHITE)
        }

    private fun film(radius: Int) =
        design.shape(0x662a282c, radius).apply { setStroke(maxOf(1, dp(1)), 0x7affffff) }

    private fun control(
        parent: LinearLayout,
        glyph: String,
        label: String,
        size: Int,
        iconSize: Int,
        boxed: Boolean,
        action: () -> Unit,
    ): NativeIconButton =
        NativeIconButton(context, Color.WHITE, design.theme).apply {
            text = glyph
            contentDescription = label
            isFocusable = true
            val inset = (size - iconSize) / 2
            setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
            background = if (boxed) film(999) else design.touch(Color.TRANSPARENT, 999)
            parent.addView(this, LinearLayout.LayoutParams(dp(size), dp(size)))
            design.press(this, ui.reducedMotion())
            setOnClickListener { action() }
        }

    private fun slider(label: String, limit: Int) =
        SeekBar(context).apply {
            contentDescription = label
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            max = limit
            val inset = if (label == "Track position") 0 else dp(8)
            setPadding(inset, 0, inset, 0)
            splitTrack = false
            progressDrawable =
                object : android.graphics.drawable.Drawable() {
                    private val paint =
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

                    override fun draw(canvas: android.graphics.Canvas) {
                        val height = dp(if (label == "Track position") 6 else 4).toFloat()
                        val line =
                            android.graphics.RectF(
                                bounds.left.toFloat(),
                                bounds.exactCenterY() - height / 2,
                                bounds.right.toFloat(),
                                bounds.exactCenterY() + height / 2,
                            )
                        paint.color = 0x44ffffff
                        canvas.drawRoundRect(line, height / 2, height / 2, paint)
                        line.right = line.left + line.width() * level / 10000f
                        paint.color = Color.WHITE
                        canvas.drawRoundRect(line, height / 2, height / 2, paint)
                    }

                    override fun onLevelChange(level: Int): Boolean {
                        invalidateSelf()
                        return true
                    }

                    override fun setAlpha(alpha: Int) {}

                    override fun setColorFilter(filter: android.graphics.ColorFilter?) {}

                    @Deprecated("Drawable compatibility")
                    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                }
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44ffffff)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumb = design.shape(Color.WHITE, 999).apply { setSize(dp(12), dp(12)) }
            thumbOffset = dp(6)
            if (label == "Track position") thumb.alpha = 0
        }

    fun setMode(value: String) {
        if (value !in setOf("cover", "queue", "lyrics")) return
        mode = value
        modeStamp++
        lyricLines = emptyList()
        lyricScroll = null
        lyricActive = -2
        while (stage.childCount > 1) stage.removeViewAt(1)
        cover.visibility = if (mode == "cover") View.VISIBLE else View.INVISIBLE
        detailCover.visibility = if (apple && mode != "cover") View.VISIBLE else View.GONE
        detailCover.setImageDrawable(cover.drawable)
        if (mode == "queue") queue()
        if (mode == "lyrics") lyrics()
        viewLabel.text =
            when (mode) {
                "queue" -> ui.text("player.queue", "Queue")
                "lyrics" -> ui.text("player.lyrics", "Lyrics")
                else -> player.current?.optString("artist")
            }
        grid.requestLayout()
        render()
    }

    private fun queueState() = "${player.index}:${player.queueRevision}"

    private fun queue() {
        queueSignature = queueState()
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        scroll.addView(body)
        stage.addView(scroll, LayoutParams(-1, -1))
        if (!apple) scroll.background = if (glass) film(18) else design.shape(0xdd121212.toInt(), 8)
        fun heading(value: String) {
            body.addView(
                text(12f, 600).apply {
                    text = value.uppercase()
                    setTextColor(0xffc5c7d1.toInt())
                    letterSpacing = .08f
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(12)
                },
            )
        }
        heading("Manual Queue")
        val manual =
            player.queue.withIndex().filter {
                it.index > player.index && it.value.optBoolean("_manual")
            }
        if (manual.isEmpty())
            body.addView(
                text(14f).apply {
                    text = "No manual tracks queued."
                    setTextColor(0xffc5c7d1.toInt())
                    setPadding(0, dp(12), 0, dp(12))
                }
            )
        for ((position, item) in manual) {
            val row =
                ui.row(
                    item.optString("title"),
                    item.optString("artist") +
                        if (position == manual.first().index) " · Next" else "",
                    ui.artworkPath(item),
                    body,
                    40,
                ) {
                    player.selectQueue(position)
                }
            (row.getChildAt(1) as LinearLayout).let { copy ->
                (copy.getChildAt(0) as TextView).setTextColor(Color.WHITE)
                if (copy.childCount > 1)
                    (copy.getChildAt(1) as TextView).setTextColor(0xffc5c7d1.toInt())
            }
            val grip =
                NativeIconButton(context, 0xffc5c7d1.toInt(), design.theme).apply {
                    text = "grip-vertical"
                    contentDescription = "Reorder queued track"
                    setPadding(dp(4), dp(14), dp(4), dp(14))
                    background = null
                    setOnLongClickListener { view ->
                        val clip =
                            android.content.ClipData.newPlainText(
                                "Aoede queued track",
                                position.toString(),
                            )
                        if (android.os.Build.VERSION.SDK_INT >= 24)
                            view.startDragAndDrop(clip, View.DragShadowBuilder(row), position, 0)
                        else
                            @Suppress("DEPRECATION")
                            view.startDrag(clip, View.DragShadowBuilder(row), position, 0)
                    }
                    setOnClickListener {
                        ui.choose("Reorder queued track", listOf("Move up", "Move down")) {
                            direction ->
                            val adjacent =
                                manual.indexOfFirst { it.index == position } +
                                    if (direction == 0) -1 else 1
                            manual.getOrNull(adjacent)?.let {
                                player.moveQueued(position, it.index)
                            }
                        }
                    }
                }
            row.addView(
                grip,
                0,
                LinearLayout.LayoutParams(dp(20), dp(44)).apply { marginEnd = dp(8) },
            )
            row.setOnDragListener { _, event ->
                val from = event.localState as? Int
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED ->
                        from != null &&
                            from in player.queue.indices &&
                            player.queue[from].optBoolean("_manual")
                    android.view.DragEvent.ACTION_DROP ->
                        from != null && player.moveQueued(from, position)
                    else -> true
                }
            }
            ui.iconButton(row, "x", "Remove queued track") { player.removeQueued(position) }
                .color(Color.WHITE)
        }
        heading("Playback Context")
        val contextTracks = player.queue.filterNot { it.optBoolean("_manual") }
        val currentPosition =
            player.queue.take(player.index + 1).count { !it.optBoolean("_manual") }
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = design.shape(0x14ffffff, 12)
            }
        body.addView(card)
        for ((label, value) in
            listOf(
                "Mode" to
                    if (ui.app.mediaController.shuffle) "Shuffled context"
                    else if (contextTracks.isNotEmpty()) "Context playback"
                    else "No playback context",
                "Context tracks" to
                    if (contextTracks.isEmpty()) "None"
                    else "${maxOf(1,currentPosition)}/${contextTracks.size}",
            )) {
            val line = LinearLayout(context)
            card.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            line.addView(
                text(13f).apply {
                    text = label
                    setTextColor(0xffc5c7d1.toInt())
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            line.addView(text(13f, 600).apply { text = value })
        }
        body.addView(
            text(12f).apply {
                text = "Manual queue plays before context and is restored after refresh."
                setTextColor(0xffc5c7d1.toInt())
                setPadding(0, dp(12), 0, 0)
            }
        )
    }

    private fun lyrics() {
        val t = player.current ?: return
        val ref = NativeTrack.reference(t)
        val stamp = modeStamp
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(16), dp(12), dp(16))
            }
        userScrolledLyrics = false
        scroll.addView(body)
        stage.addView(scroll, LayoutParams(-1, -1))
        val follow =
            TextView(context).apply {
                text = "Follow current line"
                design.typography(this, 13f, 600)
                setTextColor(Color.WHITE)
                background = design.shape(0xdd202024.toInt(), 999, true)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                elevation = dp(6).toFloat()
                visibility = View.GONE
                setOnClickListener {
                    userScrolledLyrics = false
                    visibility = View.GONE
                    if (lyricActive >= 0 && lyricActive < lyricLines.size) {
                        lyricScroll?.smoothScrollTo(
                            0,
                            (lyricLines[lyricActive].second.top - stage.height / 3).coerceAtLeast(0),
                        )
                    }
                }
            }
        followLyricBtn = follow
        stage.addView(
            follow,
            LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(16)
            },
        )
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                userScrolledLyrics = true
                if (lyricLines.isNotEmpty()) follow.visibility = View.VISIBLE
            }
            false
        }
        lyricScroll = scroll
        if (!apple) scroll.background = if (glass) film(18) else design.shape(0xdd121212.toInt(), 8)
        val loading = text(20f, 600).apply { text = ui.text("common.loading", "Loading…") }
        body.addView(loading)
        val trackId = NativeTrack.localId(t)
        val trackParam = if (trackId > 0) "&track_id=$trackId" else ""
        val path =
            "/api/lyrics?title=${NativeApi.encode(t.optString("title"))}&artist=${NativeApi.encode(t.optString("artist"))}&album=${NativeApi.encode(t.optString("album"))}&duration=${t.optDouble("duration").coerceIn(0.0,86400.0)}$trackParam"
        ui.api.execute({ ui.api.request(path) }) { value, error ->
            if (
                parent == null ||
                    stamp != modeStamp ||
                    player.current?.let(NativeTrack::reference) != ref
            )
                return@execute
            body.removeAllViews()
            val data = value as? JSONObject
            if (data?.optBoolean("instrumental") == true) {
                body.addView(
                    text(24f, 600).apply {
                        text = "Instrumental track ♪"
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(48), 0, dp(24))
                    }
                )
                render()
                return@execute
            }
            val synced = data?.optString("synced_lyrics", data.optString("synced")).orEmpty()
            val parsed =
                Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)")
                    .findAll(synced)
                    .map { match ->
                        val ms =
                            (match.groupValues[1].toLong() * 60000 +
                                    match.groupValues[2].toDouble() * 1000)
                                .toLong()
                        val line =
                            text(if (apple) 28f else 26f, 700).apply {
                                text = match.groupValues[3]
                                setPadding(0, dp(8), 0, dp(8))
                                minHeight = dp(54)
                                setOnClickListener { ui.app.mediaController.command("seekto", ms) }
                                isFocusable = true
                            }
                        body.addView(line)
                        ms to line
                    }
                    .toList()
            lyricLines = parsed
            if (parsed.isEmpty())
                body.addView(
                    text(18f, 600).apply {
                        text =
                            if (error != null) "Lyrics unavailable. Try again."
                            else
                                data
                                    ?.optString("plain_lyrics", data.optString("plain"))
                                    .orEmpty()
                                    .ifBlank { "No lyrics found for this track." }
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(48), 0, dp(24))
                        setTextColor(0x80c5c7d1.toInt())
                    }
                )
            else {
                body.addView(
                    text(12f).apply {
                        text = "via lrclib.net"
                        setTextColor(0x80c5c7d1.toInt())
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(32), 0, dp(16))
                    }
                )
            }
            render()
        }
    }

    private fun volumeDialog() {
        if (apple) {
            volume.requestFocus()
            volume.sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED
            )
            return
        }
        volumePopup?.dismiss()
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = design.shape(0xf529292b.toInt(), 16, true)
            }
        body.addView(
            text(14f, 600).apply { text = "Playback volume" },
            LinearLayout.LayoutParams(-1, dp(28)),
        )
        val bar = slider("Playback volume", 100).apply { progress = (player.volume * 100).toInt() }
        body.addView(bar, LinearLayout.LayoutParams(-1, dp(44)))
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(v: SeekBar) {}

                override fun onStopTrackingTouch(v: SeekBar) {
                    player.setVolume(v.progress / 100f, true)
                }

                override fun onProgressChanged(v: SeekBar, p: Int, user: Boolean) {
                    if (user) player.setVolume(p / 100f)
                }
            }
        )
        volumePopup =
            PopupWindow(body, dp(240), -2, true).apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                elevation = dp(10).toFloat()
                showAsDropDown(header.getChildAt(2), 0, dp(8), Gravity.END)
            }
    }

    private fun sleepTimer() {
        val current = player.sleepTimerMinutes
        val title = if (current > 0) "Sleep timer ($current min)" else "Sleep timer"
        val labels = listOf("Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "2 hours")
        val values = listOf(0, 15, 30, 45, 60, 120)
        ui.choose(title, labels) { idx ->
            val minutes = values[idx]
            player.setSleepTimer(minutes)
            if (minutes > 0) {
                ui.toast("Sleep timer set for $minutes minutes")
            } else {
                ui.toast("Sleep timer turned off")
            }
        }
    }

    private fun playbackMenu() {
        ui.popup(
            header.getChildAt(2),
            "Playback options",
            listOf(
                "Show artwork",
                if (ui.app.mediaController.shuffle) "Shuffle · On" else "Shuffle · Off",
                "Repeat · ${ui.app.mediaController.repeat}",
                if (player.sleepTimerMinutes > 0) "Sleep timer · ${player.sleepTimerMinutes}m" else "Sleep timer",
                "Download track",
            ),
            listOf("disc-3", "shuffle", "repeat", "timer", "download"),
        ) {
            when (it) {
                0 -> setMode("cover")
                1 -> ui.app.mediaController.command("shuffle")
                2 -> ui.app.mediaController.command("repeat")
                3 -> sleepTimer()
                4 -> player.current?.let { track -> ui.download(listOf(track)) }
            }
        }
    }

    fun render() {
        val t = player.current ?: return
        val artistAvailable =
            player.failure == null &&
                t.optString("artist").trim().let {
                    it.isNotBlank() &&
                        !it.equals("Unknown", true) &&
                        !it.equals("Unknown Artist", true)
                }
        artist.isClickable = artistAvailable
        artist.isFocusable = artistAvailable
        viewLabel.isClickable = artistAvailable && mode == "cover"
        viewLabel.isFocusable = viewLabel.isClickable
        if (mode == "queue" && queueState() != queueSignature) {
            stage.removeAllViews()
            queue()
        }
        fun copy(view: TextView, value: String) {
            if (view.text.toString() != value) view.text = value
        }
        copy(title, t.optString("title"))
        copy(artist, player.failure ?: t.optString("artist"))
        if (mode == "cover") copy(viewLabel, t.optString("artist"))
        toggle.text = if (player.playing) "pause" else "play"
        toggle.contentDescription = if (player.playing) "Pause" else "Play"
        toggle.color(if (design.theme == "classic") Color.BLACK else Color.WHITE)
        favorite.color(
            if (design.theme == "classic" && ui.app.mediaController.favorite) ui.accent
            else Color.WHITE,
            ui.app.mediaController.favorite,
        )
        favorite.isEnabled = ui.app.mediaController.canFavorite
        favorite.alpha = if (favorite.isEnabled) 1f else .35f
        shuffle?.let {
            it.isEnabled = ui.app.mediaController.canShuffle
            it.alpha = if (it.isEnabled) 1f else .35f
        }
        repeat?.let {
            it.isEnabled = ui.app.mediaController.canRepeat
            it.alpha = if (it.isEnabled) 1f else .35f
        }
        shuffle?.color(if (ui.app.mediaController.shuffle) ui.accent else 0xffc5c7d1.toInt())
        repeat?.let {
            it.text = if (ui.app.mediaController.repeat == "one") "repeat-1" else "repeat"
            it.color(if (ui.app.mediaController.repeat != "off") ui.accent else 0xffc5c7d1.toInt())
        }
        if (!seeking)
            seek.progress =
                if (player.duration > 0) (player.position * 1000 / player.duration).toInt() else 0
        copy(elapsed, time(player.position))
        copy(
            duration,
            if (showRemainingTime || apple) "−" + time((player.duration - player.position).coerceAtLeast(0))
            else time(player.duration),
        )
        if (!volume.isPressed) volume.progress = (player.volume * 100).toInt()
        val id = NativeTrack.reference(t)
        if (id != reference) {
            val previous = reference
            reference = id
            if (
                previous.isNotBlank() &&
                    !ui.reducedMotion() &&
                    mode == "cover" &&
                    cover.drawable != null
            ) {
                val ghost =
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(cover.drawable)
                        scaleX = cover.scaleX
                        scaleY = cover.scaleY
                        translationX = cover.translationX
                        rotation = cover.rotation
                    }
                stage.addView(ghost, LayoutParams(cover.width, cover.height, Gravity.CENTER))
                ghost
                    .animate()
                    .translationX(-ui.trackSlideDirection * stage.width.toFloat())
                    .rotation(-ui.trackSlideDirection * 2f)
                    .setDuration(340)
                    .setInterpolator(android.view.animation.PathInterpolator(.18f, .9f, .22f, 1f))
                    .withEndAction { stage.removeView(ghost) }
                    .start()
            }
            val bitmap =
                ui.app.mediaController.metadata.getBitmap(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART
                )
            cover.setImageDrawable(null)
            if (bitmap != null) {
                cover.setImageBitmap(bitmap)
                background(bitmap)
            }
            ui.artwork(cover, ui.artworkPath(t)) { bitmapValue ->
                if (reference == id) {
                    detailCover.setImageBitmap(bitmapValue)
                    background(bitmapValue)
                }
            }
            if (previous.isNotBlank() && !ui.reducedMotion()) {
                cover.animate().cancel()
                cover.translationX = ui.trackSlideDirection * stage.width.toFloat()
                cover.rotation = ui.trackSlideDirection * 2f
                cover.alpha = 1f
                cover
                    .animate()
                    .translationX(0f)
                    .rotation(0f)
                    .alpha(1f)
                    .setDuration(340)
                    .setInterpolator(android.view.animation.PathInterpolator(.18f, .9f, .22f, 1f))
                    .start()
            }
            if (previous.isNotBlank() && mode != "cover") setMode(mode)
        }
        if (lastPlaying != player.playing) {
            lastPlaying = player.playing
            val scale =
                if (player.playing || ui.reducedMotion()) 1f
                else if (design.theme == "classic") .94f else .84f
            cover
                .animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(if (ui.reducedMotion()) 0 else 400)
                .start()
        }
        if (lyricLines.isNotEmpty()) {
            val index = lyricLines.indexOfLast { it.first <= player.position }
            if (index != lyricActive) {
                lyricActive = index
                lyricLines.forEachIndexed { i, line ->
                    line.second.alpha = if (i == index) 1f else .45f
                }
                if (index >= 0 && !lyricScroll!!.isPressed && !userScrolledLyrics)
                    lyricScroll?.smoothScrollTo(
                        0,
                        (lyricLines[index].second.top - stage.height / 3).coerceAtLeast(0),
                    )
            }
        }
        for (i in 0 until actions.childCount) {
            val v = actions.getChildAt(i)
            val selected =
                when (v.tag) {
                    "mic-2" -> mode == "lyrics"
                    "list-music" -> mode == "queue"
                    "disc-3" -> mode == "cover"
                    "timer" -> player.sleepTimerMinutes > 0
                    else -> false
                }
            if (v.isSelected != selected) {
                v.isSelected = selected
                if (glass) v.background = if (selected) film(999) else null
            }
        }
    }

    private fun openArtist() {
        val name = player.current?.optString("artist")?.trim().orEmpty()
        if (
            player.failure != null ||
                name.isBlank() ||
                name.equals("Unknown", true) ||
                name.equals("Unknown Artist", true)
        )
            return
        ui.hidePlayer()
        ui.tools.artist(name)
    }

    private fun background(bitmap: Bitmap) {
        if (ui.reduced()) {
            backdropBitmap = null
            backdrop.setImageDrawable(null)
            shade.setBackgroundColor(0xff111218.toInt())
            return
        }
        if (backdropBitmap === bitmap) return
        backdropBitmap = bitmap
        if (design.theme == "classic") {
            val base = nativeArtworkSeed(bitmap)
            shade.background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        androidx.core.graphics.ColorUtils.compositeColors(0x06ffffff, base),
                        androidx.core.graphics.ColorUtils.compositeColors(0xbb000000.toInt(), base),
                    ),
                )
        } else {
            val tiny = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
            backdrop.setImageBitmap(blurNative(tiny, if (apple) 4 else 2))
            if (tiny !== bitmap) tiny.recycle()
            if (apple)
                backdrop.colorFilter =
                    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) })
            shade.background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    if (apple) intArrayOf(0x8018181e.toInt(), 0xd918181e.toInt())
                    else intArrayOf(0x990c0d12.toInt(), 0xd90c0d12.toInt()),
                )
        }
    }

    override fun onDetachedFromWindow() {
        volumePopup?.dismiss()
        volumePopup = null
        modeStamp++
        super.onDetachedFromWindow()
    }

    private fun time(ms: Long) = "${ms/60000}:${((ms/1000)%60).toString().padStart(2,'0')}"

    private inner class PlayerGrid : ViewGroup(context) {
        private val boxes = mutableMapOf<View, android.graphics.Rect>()

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val height = MeasureSpec.getSize(h)
            setMeasuredDimension(width, height)
            val density = resources.displayMetrics.density
            val margins = layoutParams as? ViewGroup.MarginLayoutParams
            val viewport =
                (height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)) / density
            val viewportWidth = width / density
            for (i in 0 until controls.childCount) {
                val button = controls.getChildAt(i) as? NativeIconButton ?: continue
                val size =
                    when (button) {
                        toggle -> (viewportWidth * .2f).toInt().coerceIn(64, 80)
                        shuffle,
                        repeat -> 44
                        else -> (viewportWidth * .15f).toInt().coerceIn(48, 64)
                    }
                val icon =
                    if (button === toggle) {
                        if (apple) 48 else if (glass) 32 else 38
                    } else if (apple) 32 else 24
                val params = button.layoutParams as LinearLayout.LayoutParams
                params.width = dp(size)
                params.height = dp(size)
                val inset = dp((size - icon) / 2)
                button.setPadding(inset, inset, inset, inset)
            }
            if (apple) {
                val font = (width / density * .048f).coerceIn(17.6f, 24.8f)
                if (
                    kotlin.math.abs(
                        title.textSize / resources.displayMetrics.scaledDensity - font
                    ) > .1f
                )
                    design.typography(title, font, 700)
            }
            val gutter = dp(if (width / density < 360) 16 else 24)
            val gap =
                (density *
                    (if (apple) (viewport * .012f).coerceIn(4f, 12f)
                    else (viewport * .015f).coerceIn(6f, 14f)))
            val top = dp(16)
            val bottom = dp(if (apple) (viewport * .04f).toInt().coerceIn(20, 40) else 16)
            val heights =
                mutableMapOf(
                    "header" to dp(if (apple) 44 else 48),
                    "info" to dp(if (mode != "cover" && apple) 80 else if (glass) 66 else 64),
                    "progress" to dp(if (apple) 44 else 46),
                    "controls" to dp(if (apple) 72 else 80),
                    "volume" to dp(44),
                    "actions" to dp(if (apple) 48 else 52),
                )
            val order =
                if (apple && mode != "cover")
                    listOf("header", "info", "stage", "progress", "controls", "volume", "actions")
                else if (apple)
                    listOf("header", "stage", "info", "progress", "controls", "volume", "actions")
                else listOf("header", "stage", "info", "progress", "controls", "actions")
            val columnWidth =
                if (width > height && width / density >= 560) (width - gutter * 3) / 2
                else width - gutter * 2
            // Text is measured before the remaining artwork space, including large-font layouts.
            info.measure(
                MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(180), MeasureSpec.AT_MOST),
            )
            heights["info"] = maxOf(heights.getValue("info"), info.measuredHeight)
            fun place(name: String, l: Int, t: Int, r: Int, b: Int) {
                val child = findViewWithTag<View>("native-fs-$name")
                val box = android.graphics.Rect(l, t, r, b)
                boxes[child] = box
                child.measure(
                    MeasureSpec.makeMeasureSpec(box.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(box.height().coerceAtLeast(0), MeasureSpec.EXACTLY),
                )
            }
            boxes.clear()
            if (width > height && width / density >= 560) {
                val short = height / density < 400
                val compactGap = dp(if (short) 2 else 4)
                val edge = dp(if (short) 4 else 8)
                if (short) {
                    heights["info"] = dp(64)
                    heights["controls"] = dp(64)
                    heights["actions"] = dp(44)
                    toggle.layoutParams =
                        (toggle.layoutParams as LinearLayout.LayoutParams).apply {
                            this.width = dp(64)
                            this.height = dp(64)
                        }
                }
                place("header", gutter, edge, width - gutter, edge + heights.getValue("header"))
                val start = edge * 2 + heights.getValue("header")
                val end = height - edge
                val right = gutter * 2 + columnWidth
                val names = order.filter { it != "header" && it != "stage" }
                val total = names.sumOf { heights.getValue(it) } + compactGap * (names.size - 1)
                var yy = start + ((end - start - total) / 2).coerceAtLeast(0)
                val stageLeft = if (apple && mode != "cover") right else gutter
                val playbackLeft = if (apple && mode != "cover") gutter else right
                place("stage", stageLeft, start, stageLeft + columnWidth, end)
                for (name in names) {
                    val hh = heights.getValue(name)
                    place(name, playbackLeft, yy, playbackLeft + columnWidth, yy + hh)
                    yy += hh + compactGap
                }
                return
            }
            val stageHeight =
                (height -
                        top -
                        bottom -
                        heights.filterKeys { it in order }.values.sum() -
                        gap * (order.size - 1) -
                        dp(if (glass) 0 else 16))
                    .let { kotlin.math.round(it).toInt().coerceAtLeast(0) }
            var y = top.toFloat()
            boxes.clear()
            for (name in order) {
                val child = findViewWithTag<View>("native-fs-$name")
                if (name == "stage" && !glass) y += dp(8)
                val hh = if (name == "stage") stageHeight else heights.getValue(name)
                val rect =
                    android.graphics.Rect(
                        gutter,
                        kotlin.math.round(y).toInt(),
                        width - gutter,
                        kotlin.math.round(y + if (name == "actions") dp(48) else hh).toInt(),
                    )
                boxes[child] = rect
                child.measure(
                    MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY),
                )
                y += hh + gap + if (name == "stage" && !glass) dp(8) else 0
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for ((view, box) in boxes) view.layout(box.left, box.top, box.right, box.bottom)
        }
    }
}
