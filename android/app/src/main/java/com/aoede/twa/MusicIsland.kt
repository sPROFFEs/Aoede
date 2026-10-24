package com.aoede.twa

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import kotlin.math.sin

/** Optional, process-owned overlay. It never starts a service or watches other apps. */
class MusicIsland(private val app: AoedeApp) {
    private val prefs get() = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private val media get() = app.mediaController
    private val windowContext by lazy {
        if (Build.VERSION.SDK_INT >= 30) {
            val display = (app.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else app
    }
    private val windows get() = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var registered = false
    private var screenAwake = true
    private var safeTop = dp(32)
    private val defaultOffset get() = (safeTop / app.resources.displayMetrics.density).roundToInt().coerceIn(0, 96)
    private var tracking = false
    private var dialog: AlertDialog? = null
    internal var taskActive = false
    internal var expanded = false
        private set
    internal val visible get() = root != null
    val enabled get() = prefs.getBoolean("music_island", false)
    val permissionGranted get() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)
    private val reduced get() = prefs.getBoolean("reduce_effects",
        (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice) ||
        (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled())
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenAwake = intent.action != Intent.ACTION_SCREEN_OFF
            refresh()
        }
    }
    private val permissionChange = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == app.packageName) handler.post { refresh() }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!expanded || root == null) return
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("music_island", value && permissionGranted).apply()
        refresh()
    }

    fun taskRemoved() {
        taskActive = false
        refresh()
    }

    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val alive = app.player != null && (taskActive || media.needsForeground)
        if (!enabled || !alive || !permissionGranted) {
            hide()
            unregister()
            return
        }
        if (dialog != null) { hide(); return }
        register()
        val locked = (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val awake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!screenAwake || !awake || locked) { hide(); return }
        if (root == null) show(false) else render()
    }

    fun relayout() {
        if (root != null) { val wasExpanded = expanded; hide(); show(wasExpanded) }
    }

    fun updateSafeTop(top: Int) {
        if (safeTop == top || top < 0) return
        safeTop = top
        val view = root ?: return
        if (expanded) view.findViewWithTag<View>("card")?.let { card ->
            (card.layoutParams as FrameLayout.LayoutParams).apply { topMargin = safeTop + dp(8); card.layoutParams = this }
        } else if (!prefs.contains("island_offset")) {
            (view.layoutParams as WindowManager.LayoutParams).apply {
                y = safeTop
                runCatching { windows.updateViewLayout(view, this) }
            }
        }
    }

    private fun register() {
        if (registered) return
        ContextCompat.registerReceiver(app, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 23) {
            (app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                .startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.packageName, permissionChange)
        }
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        app.unregisterReceiver(screen)
        if (Build.VERSION.SDK_INT >= 23) {
            (app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).stopWatchingMode(permissionChange)
        }
        registered = false
    }

    internal fun hide() {
        handler.removeCallbacks(tick)
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null
        expanded = false
        tracking = false
    }

    internal fun show(open: Boolean) {
        if (!enabled || !permissionGranted) return
        hide()
        expanded = open
        val view = if (open) panel() else capsule()
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (open) WindowManager.LayoutParams.FLAG_DIM_BEHIND
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val params = WindowManager.LayoutParams(if (open) -1 else dp(156), if (open) -1 else dp(48),
            type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = if (open) 0 else dp(prefs.getInt("island_offset", defaultOffset).coerceIn(0, 96))
            dimAmount = if (open) .16f else 0f
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        root = view
        try {
            windows.addView(view, params)
        } catch (_: SecurityException) { hide(); return }
        catch (_: WindowManager.BadTokenException) { hide(); return }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            if (top > 0) updateSafeTop(top)
            insets
        }
        ViewCompat.requestApplyInsets(view)
        render()
        if (open) { view.requestFocus(); tick.run() }
        if (!reduced) {
            val target = view.findViewWithTag<View>("card") ?: view
            target.alpha = 0f; target.scaleX = .94f; target.scaleY = .94f
            target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }
    }

    private fun capsule(): View = FrameLayout(windowContext).apply {
        background = shape(Color.BLACK, 24f)
        isClickable = true
        contentDescription = app.getString(R.string.island_expand)
        setOnClickListener { show(true) }
        addView(cover("art", 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(12) })
        addView(Bars(windowContext).apply { tag = "bars"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(dp(28), dp(24), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(14) })
    }

    private fun panel(): View {
        val frame = FrameLayout(windowContext).apply {
            isFocusableInTouchMode = true
            setOnClickListener { show(false) }
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { show(false); true } else false
            }
        }
        @Suppress("DEPRECATION")
        val displayWidth = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds.width()
            else android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }.widthPixels
        val scroll = ScrollView(windowContext).apply {
            tag = "card"; background = shape(Color.rgb(27, 24, 30), 30f); clipToOutline = true
            isFillViewport = false
            setOnClickListener { openPlayer() }
        }
        frame.addView(scroll, FrameLayout.LayoutParams(minOf(dp(420), displayWidth - dp(24)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = safeTop + dp(8); bottomMargin = dp(32)
        })
        val body = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(10))
            setOnClickListener { openPlayer() }
        }
        scroll.addView(body)
        val heading = LinearLayout(windowContext).apply { gravity = Gravity.CENTER_VERTICAL; setOnClickListener { openPlayer() } }
        heading.addView(cover("art", 64), LinearLayout.LayoutParams(dp(64), dp(64)))
        val copy = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
        copy.addView(label("title", 18f, true).apply { maxLines = 2 })
        copy.addView(label("artist", 13f).apply { setTextColor(0xFFB9B4BF.toInt()) })
        heading.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ImageView(windowContext).apply { setImageResource(R.mipmap.ic_launcher); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(28), dp(28)))
        body.addView(heading)
        val seek = SeekBar(windowContext, null, 0, R.style.MusicIslandSeekBar).apply {
            tag = "seek"; max = 1000; contentDescription = app.getString(R.string.island_seek)
            progressDrawable = ContextCompat.getDrawable(app, R.drawable.island_progress)
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(8), dp(8)) }
            splitTrack = false
            progressTintList = ColorStateList.valueOf(Color.WHITE); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) frame.findViewWithTag<TextView>("elapsed").text = time(duration() * value / 1000)
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    if (media.supports("seekto")) media.command("seekto", duration() * bar.progress / 1000)
                    tracking = false
                }
            })
        }
        body.addView(seek, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        val times = LinearLayout(windowContext)
        times.addView(label("elapsed", 11f), LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(label("duration", 11f).apply { gravity = Gravity.END })
        body.addView(times)
        val controls = LinearLayout(windowContext).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        for ((tag, icon, description) in listOf(
            Triple("shuffle", R.drawable.ic_island_shuffle, R.string.island_shuffle),
            Triple("previous", R.drawable.ic_skip_previous, R.string.playback_action_prev),
            Triple("toggle", R.drawable.ic_play, R.string.playback_action_play),
            Triple("next", R.drawable.ic_skip_next, R.string.playback_action_next),
            Triple("repeat", R.drawable.ic_island_repeat, R.string.island_repeat))) {
            val size = if (tag == "toggle") 64 else 48
            controls.addView(button(tag, icon, description).apply {
                setOnClickListener {
                    when (tag) {
                        "toggle" -> if (media.needsForeground) media.pauseLocal() else media.command("play")
                        "previous" -> media.command("previoustrack")
                        "next" -> media.command("nexttrack")
                        else -> media.command(tag)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(size), if (tag == "toggle") 1.3f else 1f))
        }
        body.addView(controls, LinearLayout.LayoutParams(-1, dp(72)))
        val bottom = LinearLayout(windowContext).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(button("favorite", R.drawable.ic_island_heart, R.string.island_favorite).apply {
            setOnClickListener { media.command("favorite") }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottom.addView(label("open", 12f).apply {
            text = app.getString(R.string.island_open_player); gravity = Gravity.END
            setOnClickListener { openPlayer() }; contentDescription = text
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(bottom)
        return frame
    }

    private fun openPlayer() {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (media.state !in setOf("none", "error")) intent.putExtra(MainActivity.OPEN_PLAYER, true)
        // Keep the overlay visible until this user-requested launch; Android 15 requires it for SAW's BAL exception.
        try { app.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { return }
        catch (_: SecurityException) { return }
        show(false)
    }

    private fun render() {
        val view = root ?: return
        val meta = media.metadata
        val art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        val image = view.findViewWithTag<ImageView>("art")
        if (art != null) { image.setImageBitmap(art); image.imageTintList = null }
        else { image.setImageResource(R.drawable.ic_notification); image.imageTintList = ColorStateList.valueOf(0xFFB9B4BF.toInt()) }
        view.findViewWithTag<Bars>("bars")?.playing = media.isPlaying && !reduced
        if (!expanded) return
        val style = PlaybackWidget.artworkStyle(art)
        val background = (view.findViewWithTag<View>("card").background as GradientDrawable)
        background.colors = intArrayOf(ColorUtils.blendARGB(style.accent, Color.BLACK, .88f), 0xFF141316.toInt())
        view.findViewWithTag<TextView>("title").text = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty().ifBlank { app.getString(R.string.app_name) }
        view.findViewWithTag<TextView>("artist").text = meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty().ifBlank { app.getString(R.string.island_idle) }
        fun control(tag: String, available: Boolean, active: Boolean = false) {
            view.findViewWithTag<ImageButton>(tag).apply {
                isEnabled = available; alpha = if (available) 1f else .3f
                isSelected = active; imageTintList = ColorStateList.valueOf(if (active) style.accent else Color.WHITE)
            }
        }
        control("toggle", media.state !in setOf("none", "error"))
        view.findViewWithTag<ImageButton>("toggle").apply {
            setImageResource(if (media.needsForeground) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = app.getString(if (media.needsForeground) R.string.playback_action_pause else R.string.playback_action_play)
        }
        control("previous", media.supports("previoustrack")); control("next", media.supports("nexttrack"))
        control("favorite", media.canFavorite, media.favorite)
        view.findViewWithTag<ImageButton>("favorite").setImageResource(if (media.favorite) R.drawable.ic_island_heart_filled else R.drawable.ic_island_heart)
        control("shuffle", media.canShuffle, media.shuffle); control("repeat", media.canRepeat, media.repeat != "off")
        view.findViewWithTag<ImageButton>("repeat").contentDescription = app.getString(when (media.repeat) {
            "one" -> R.string.island_repeat_one; "all" -> R.string.island_repeat_all; else -> R.string.island_repeat
        })
        view.findViewWithTag<ImageButton>("repeat").setImageResource(if (media.repeat == "one") R.drawable.ic_island_repeat_one else R.drawable.ic_island_repeat)
        updateProgress()
    }

    private fun duration() = media.metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION).coerceAtLeast(0)

    private fun updateProgress() {
        val view = root ?: return
        if (!expanded || tracking) return
        val state = media.mediaSession.controller.playbackState
        val length = duration()
        val elapsed = (state?.position ?: 0) + if (media.isPlaying && state != null)
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed).toLong() else 0
        val position = elapsed.coerceIn(0, if (length > 0) length else Long.MAX_VALUE)
        view.findViewWithTag<SeekBar>("seek").apply {
            isEnabled = length > 0 && media.supports("seekto")
            progress = if (length > 0) (position * 1000 / length).toInt() else 0
        }
        view.findViewWithTag<TextView>("elapsed").text = time(position)
        view.findViewWithTag<TextView>("duration").text = if (length > 0) time(length) else "—:—"
    }

    fun showSettings(activity: MainActivity, requestPermission: () -> Unit) {
        dialog?.dismiss()
        hide()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        body.addView(TextView(activity).apply { text = app.getString(R.string.island_description) })
        val toggle = SwitchCompat(activity).apply { tag = "island-toggle"; text = app.getString(R.string.island_enable); minHeight = dp(56); isChecked = enabled && permissionGranted }
        body.addView(toggle)
        val position = TextView(activity)
        val slider = SeekBar(activity).apply { max = 96; progress = prefs.getInt("island_offset", defaultOffset).coerceIn(0, 96); contentDescription = app.getString(R.string.island_position) }
        fun positionLabel() { position.text = app.getString(R.string.island_position_value, slider.progress) }
        positionLabel(); body.addView(position); body.addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(TextView(activity).apply { text = app.getString(R.string.island_position_help); textSize = 12f })
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) { prefs.edit().putInt("island_offset", value).apply(); positionLabel() }
            }
        })
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked && !permissionGranted) {
                dialog?.dismiss()
                try { requestPermission() } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.island_permission_denied, Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(activity, R.string.island_permission_denied, Toast.LENGTH_LONG).show()
                }
            } else { prefs.edit().putBoolean("music_island", checked).apply() }
        }
        dialog = AlertDialog.Builder(activity).setTitle(R.string.island_title).setView(body).setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { dialog = null; refresh() }.create()
        dialog!!.setOwnerActivity(activity)
        dialog!!.show()
    }

    fun closeSettings(activity: MainActivity) {
        if (dialog?.ownerActivity === activity) dialog?.dismiss()
    }

    private fun shape(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun cover(name: String, size: Int) = ImageView(windowContext).apply {
        tag = name; scaleType = ImageView.ScaleType.CENTER_CROP
        background = shape(0xFF353139.toInt(), if (size > 32) 13f else 7f); clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun label(name: String, size: Float, bold: Boolean = false) = TextView(windowContext).apply {
        tag = name; textSize = size; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(name: String, icon: Int, description: Int) = ImageButton(windowContext).apply {
        tag = name; setImageResource(icon); imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = app.getString(description); scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12)); minimumWidth = dp(48); minimumHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(if (name == "toggle") 0x22FFFFFF else Color.TRANSPARENT, 32f), shape(Color.WHITE, 32f))
    }
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (value * app.resources.displayMetrics.density).roundToInt()
    private fun time(milliseconds: Long): String { val seconds = milliseconds / 1000; return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60) }

    private class Bars(context: Context) : View(context) {
        var playing = false
            set(value) { if (field != value) { field = value; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val t = SystemClock.uptimeMillis() / 170.0
            paint.strokeWidth = width / 15f
            for (i in 0..4) {
                val bar = if (playing) (.25 + .65 * kotlin.math.abs(sin(t + i * 1.3))).toFloat() else .25f + (i % 3) * .12f
                val x = width * (i + .5f) / 5
                canvas.drawLine(x, height * (1 - bar) / 2, x, height * (1 + bar) / 2, paint)
            }
            if (playing && isAttachedToWindow && visibility == VISIBLE) postInvalidateDelayed(120)
        }
    }
}
