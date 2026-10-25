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
import android.graphics.Rect
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Button
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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import org.json.JSONArray
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
    private var root: FrameLayout? = null
    private var changingWindow = false
    private var windowRevision = 0
    private val reconcile = Runnable {
        val wasVisible = root != null
        refresh()
        if (wasVisible && root != null) relayout()
    }
    private var registered = false
    private var safeInsets = Insets.of(0, dp(32), 0, 0)
    private val safeTop get() = protectedInsets().top
    private val capsuleTop get() = prefs.getInt("island_top", when {
        prefs.getInt("island_gap", 0) > 0 -> (safeTop / app.resources.displayMetrics.density).roundToInt() + 4 + prefs.getInt("island_gap", 0)
        prefs.contains("island_offset") -> prefs.getInt("island_offset", 8)
        else -> 8
    }).coerceIn(0, 240)
    private val capsuleWidth get() = prefs.getInt("island_width", 156).coerceIn(112, 320)
    private val capsuleHeight get() = prefs.getInt("island_height", 48).coerceIn(28, 72)
    private var tracking = false
    private var dialog: AlertDialog? = null
    private var appVisible = true
    private var hiding = false
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
            if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                if (intent.getStringExtra("reason") in setOf("homekey", "recentapps")) collapse()
                return
            }
            if (intent.action !in setOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT)) return
            if (intent.action == Intent.ACTION_SCREEN_OFF || (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) collapse()
            refresh()
            handler.removeCallbacks(wakeRefresh)
            // Some devices deliver wake/unlock before PowerManager/Keyguard finish their transition.
            if (intent.action != Intent.ACTION_SCREEN_OFF) handler.postDelayed(wakeRefresh, 500)
        }
    }
    private val wakeRefresh = Runnable { refresh() }
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
    private var sampling = false
    private var sampleGeneration = 0
    private val spectrum = object : Runnable {
        override fun run() {
            val player = app.player ?: return
            if (!sampling) return
            val generation = sampleGeneration
            player.evaluateJavascript("window.__aoedeIslandLevels && window.__aoedeIslandLevels(true)") { result ->
                if (!sampling || generation != sampleGeneration) return@evaluateJavascript
                updateSpectrum(result)
                handler.postDelayed(this, if (result == "null") 1000 else 100)
            }
        }
    }

    internal fun updateSpectrum(raw: String) {
        val levels = runCatching {
            require(raw.length < 256)
            val values = JSONArray(raw)
            require(values.length() == 5)
            FloatArray(5) { i -> values.getDouble(i).also { require(it.isFinite() && it in 0.0..1.0) }.toFloat() }
        }.getOrNull()
        root?.findViewWithTag<Bars>("bars")?.levels = levels
    }

    internal fun syncSpectrum() {
        val active = root != null && !hiding && !expanded && media.isPlaying && !reduced && app.player != null &&
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        root?.findViewWithTag<Bars>("bars")?.playing = active
        if (sampling == active) return
        sampling = active
        sampleGeneration++
        handler.removeCallbacks(spectrum)
        if (active) handler.post(spectrum) else {
            app.player?.evaluateJavascript("window.__aoedeIslandLevels && window.__aoedeIslandLevels(false)", null)
            root?.findViewWithTag<Bars>("bars")?.levels = FloatArray(5)
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

    internal fun setAppVisible(value: Boolean) {
        if (value && expanded) collapse()
        appVisible = value
        refresh()
    }

    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (changingWindow) { deferRefresh(); return }
        val alive = app.player != null && (taskActive || media.needsForeground)
        if (!enabled || !alive || !permissionGranted) {
            hide()
            unregister()
            return
        }
        if (appVisible && dialog == null) {
            hideAnimated()
            unregister()
            return
        }
        if (hiding) hide()
        if (dialog != null && expanded) collapse()
        register()
        val awake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!awake && expanded) collapse()
        // Keep the one compact window attached: the system hides it while sleeping/under keyguard
        // and reveals it after unlocking, even if an OEM delays or drops its wake broadcast.
        if (root == null) show(false) else render()
    }

    fun relayout() {
        if (root != null) show(expanded)
    }

    fun updateSafeTop(top: Int) {
        updateSafeInsets(Insets.of(safeInsets.left, top, safeInsets.right, safeInsets.bottom))
    }

    fun updateSafeInsets(value: Insets) {
        val next = Insets.of(value.left, if (value.top > 0) value.top else safeInsets.top, value.right, value.bottom)
        if (next == safeInsets) return
        safeInsets = next
        if (root != null) { if (changingWindow) deferRefresh() else show(expanded) }
    }

    fun collapse() {
        if (expanded) show(false)
    }

    private fun deferRefresh() {
        handler.removeCallbacks(reconcile)
        handler.post(reconcile)
    }

    private fun protectedInsets(): Insets {
        if (Build.VERSION.SDK_INT < 30) return safeInsets
        val stable = windows.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.tappableElement())
        return Insets.max(safeInsets, Insets.of(stable.left, stable.top, stable.right, stable.bottom))
    }

    @Suppress("DEPRECATION")
    private fun displayBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds
        else android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private fun capsuleBounds(): Rect {
        return compactRect(capsuleWidth, capsuleHeight, capsuleTop,
            prefs.getInt("island_horizontal", 0))
    }

    private fun compactRect(widthDp: Int, heightDp: Int, topDp: Int, horizontal: Int): Rect {
        val screen = displayBounds(); val insets = protectedInsets()
        val left = insets.left + dp(8); val right = (screen.width() - insets.right - dp(8)).coerceAtLeast(left + dp(48))
        val width = minOf(dp(widthDp), right - left)
        val travel = right - left - width
        val x = left + travel * (horizontal.coerceIn(-100, 100) + 100) / 200
        val maximumY = (screen.height() - insets.bottom - dp(8 + heightDp)).coerceAtLeast(0)
        val y = dp(topDp).coerceAtMost(maximumY)
        return Rect(x, y, x + width, y + dp(heightDp))
    }

    private val minimumTouchTop get() = kotlin.math.ceil((safeTop + dp(4)) / app.resources.displayMetrics.density).toInt()
    private val handleTop get() = prefs.getInt("island_handle_top", maxOf(capsuleTop + capsuleHeight, minimumTouchTop) + 4).coerceIn(0, 320)
    private val horizontal get() = prefs.getInt("island_horizontal", 0).coerceIn(-100, 100)

    private fun register() {
        if (registered) return
        ContextCompat.registerReceiver(app, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }, ContextCompat.RECEIVER_EXPORTED) // Protected screen broadcasts may originate in an OEM's privileged UI process.
        if (Build.VERSION.SDK_INT >= 23) {
            (app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                .startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.packageName, permissionChange)
        }
        registered = true
    }

    private fun unregister() {
        handler.removeCallbacks(wakeRefresh)
        if (!registered) return
        app.unregisterReceiver(screen)
        if (Build.VERSION.SDK_INT >= 23) {
            (app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).stopWatchingMode(permissionChange)
        }
        registered = false
    }

    internal fun hide() {
        if (changingWindow) { deferRefresh(); return }
        changingWindow = true
        windowRevision++
        handler.removeCallbacks(tick)
        val previous = root
        root = null
        hiding = false
        previous?.animate()?.cancel()
        expanded = false
        tracking = false
        syncSpectrum()
        try { previous?.let { windows.removeViewImmediate(it) } }
        catch (_: IllegalArgumentException) { /* The system may already have removed a revoked overlay. */ }
        finally { changingWindow = false }
    }

    private fun hideAnimated() {
        val view = root ?: return
        if (hiding) return
        if (reduced) { hide(); return }
        if (expanded) show(false)
        hiding = true
        syncSpectrum()
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val params = view.layoutParams as WindowManager.LayoutParams
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windows.updateViewLayout(view, params) }
        view.animate().cancel()
        view.animate().scaleX(.02f).alpha(0f).setDuration(180)
            .setInterpolator(PathInterpolator(.4f, 0f, 1f, 1f))
            .withEndAction { if (hiding && root === view) hide() }.start()
    }

    internal fun show(open: Boolean) {
        if (!enabled || !permissionGranted || (appVisible && dialog == null)) return
        if (changingWindow) { deferRefresh(); return }
        changingWindow = true
        val modeChanged = expanded != open
        if (modeChanged) windowRevision++
        try {
            handler.removeCallbacks(tick)
            expanded = open
            tracking = false
            val existing = root
            val view = existing ?: IslandWindow(windowContext)
            if (modeChanged) { view.animate().cancel(); view.scaleX = 1f; view.alpha = 1f }
            (view.findViewWithTag<View>("card") ?: view.getChildAt(0))?.animate()?.cancel()
            view.removeAllViews()
            val capsule = capsuleBounds()
            val handleHeight = prefs.getInt("island_handle_height", 12).coerceIn(0, 32)
            val handle = compactRect(prefs.getInt("island_handle_width", 32).coerceIn(16, 128), handleHeight,
                handleTop, prefs.getInt("island_handle_horizontal", horizontal))
            val touch = compactRect(prefs.getInt("island_touch_width", capsuleWidth).coerceIn(48, 320),
                prefs.getInt("island_touch_height", 48).coerceIn(48, 160),
                prefs.getInt("island_touch_top", maxOf(capsuleTop, minimumTouchTop)).coerceIn(minimumTouchTop, maxOf(320, minimumTouchTop)),
                prefs.getInt("island_touch_horizontal", horizontal))
            val hasHandle = handleHeight > 0 && (capsule.top < safeTop + dp(4) || prefs.contains("island_handle_top"))
            val box = Rect(capsule).apply { union(touch); if (hasHandle) union(handle) }
            if (!open) view.pivotX = capsule.exactCenterX() - box.left
            fun place(child: View, rect: Rect) {
                view.addView(child, FrameLayout.LayoutParams(rect.width(), rect.height(), Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = rect.left - box.left; topMargin = rect.top - box.top
                })
            }
            if (open) view.addView(panel(), FrameLayout.LayoutParams(-1, -1)) else place(capsule(), capsule)
            if (!open && hasHandle) {
                place(FrameLayout(windowContext).apply {
                    tag = "handle"; background = shape(Color.BLACK, minOf(7, handleHeight / 2).toFloat())
                    addView(View(windowContext).apply { background = shape(0xFFB9B4BF.toInt(), 1f) },
                        FrameLayout.LayoutParams(minOf(dp(14), handle.width() - dp(4)), minOf(dp(2), handle.height()), Gravity.CENTER))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, handle)
            }
            view.foreground = if (dialog != null) GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFFB8A3FF.toInt(), dp(4).toFloat(), dp(3).toFloat())
            } else null
            view.isFocusableInTouchMode = open
            view.contentDescription = if (open) null else app.getString(R.string.island_expand)
            view.importantForAccessibility = if (dialog != null) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else if (open) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
            view.setOnClickListener { if (dialog == null) { if (expanded) collapse() else show(true) } }
            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE
            val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                (if (dialog != null) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                if (open) WindowManager.LayoutParams.FLAG_DIM_BEHIND
                else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            val params = WindowManager.LayoutParams(if (open) -1 else box.width(), if (open) -1 else box.height(),
                type, flags, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = if (open) 0 else box.left
                y = if (open) 0 else box.top
                dimAmount = if (open) .16f else 0f
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                // Insets on a small, positioned overlay are window-relative. Use the full display's
                // stable metrics (or the activity) rather than feeding those back into screen coordinates.
                if (Build.VERSION.SDK_INT >= 30) {
                    val stable = protectedInsets()
                    if (stable != safeInsets) { safeInsets = stable; deferRefresh() }
                }
                insets
            }
            try {
                if (existing == null) windows.addView(view, params) else windows.updateViewLayout(view, params)
                root = view
            } catch (_: SecurityException) {
                if (existing == null) runCatching { windows.removeViewImmediate(view) }
                return
            } catch (_: WindowManager.BadTokenException) {
                if (existing == null) runCatching { windows.removeViewImmediate(view) }
                return
            }
            ViewCompat.requestApplyInsets(view)
            render()
            if (open) { view.requestFocus(); tick.run() }
            if (!reduced && (existing == null || modeChanged)) {
                val target = view.findViewWithTag<View>("card") ?: view.getChildAt(0)
                if (existing == null && !open) {
                    view.scaleX = .02f; view.alpha = 0f
                    view.animate().scaleX(1f).alpha(1f).setDuration(280)
                        .setInterpolator(PathInterpolator(.16f, 1f, .3f, 1f)).start()
                } else {
                    target.alpha = 0f; target.scaleX = .94f; target.scaleY = .94f
                    target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
                }
            }
        } finally { changingWindow = false }
    }

    private fun capsule(): View = FrameLayout(windowContext).apply {
        tag = "capsule"
        background = shape(Color.BLACK, capsuleHeight / 2f)
        val artSize = minOf(28, capsuleHeight - 10)
        addView(cover("art", artSize), FrameLayout.LayoutParams(dp(artSize), dp(artSize), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(12) })
        addView(Bars(windowContext).apply { tag = "bars"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(dp(28), dp(minOf(24, capsuleHeight - 8)), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(14) })
    }

    private inner class IslandWindow(context: Context) : FrameLayout(context) {
        private var focused = false
        private var swallowingOutsideTouch = false
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                swallowingOutsideTouch = false
                val card = findViewWithTag<View>("card")
                if (expanded && card != null) {
                    val bounds = Rect()
                    card.getDrawingRect(bounds)
                    offsetDescendantRectToMyCoords(card, bounds)
                    if (!bounds.contains(event.x.toInt(), event.y.toInt())) {
                        swallowingOutsideTouch = true
                        collapse() // Recents/Home can cancel the gesture before a click/UP is delivered.
                    }
                }
            }
            if (swallowingOutsideTouch) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowingOutsideTouch = false
                return true
            }
            return super.dispatchTouchEvent(event)
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            val lost = focused && !hasFocus
            focused = hasFocus
            val revision = windowRevision
            if (lost && expanded) handler.post {
                if (root === this && revision == windowRevision && !focused && expanded) collapse()
            }
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (expanded && event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) collapse()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun panel(): View {
        val frame = FrameLayout(windowContext).apply {
            setOnClickListener { show(false) }
        }
        val bounds = displayBounds()
        val displayWidth = bounds.width()
        val insets = protectedInsets()
        val width = minOf(dp(420), displayWidth - insets.left - insets.right - dp(24))
        val capsule = capsuleBounds()
        val left = (capsule.centerX() - width / 2).coerceIn(insets.left + dp(12), displayWidth - insets.right - dp(12) - width)
        // Retain a usable scrolling viewport when the capsule is low or the phone rotates.
        val top = maxOf(safeTop + dp(8), minOf(capsule.top, bounds.height() - insets.bottom - dp(176)))
        val scroll = ScrollView(windowContext).apply {
            tag = "card"; background = shape(Color.rgb(27, 24, 30), 30f); clipToOutline = true
            isFillViewport = false
            setOnClickListener { openPlayer() }
        }
        frame.addView(scroll, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = left; topMargin = top; bottomMargin = insets.bottom + dp(16)
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
        syncSpectrum()
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
        fun slider(key: String, title: Int, format: Int, minimum: Int, maximum: Int, value: Int) {
            val label = TextView(activity)
            val bar = SeekBar(activity).apply {
                tag = key; max = maximum - minimum; progress = value - minimum
                contentDescription = app.getString(title)
                keyProgressIncrement = 1
            }
            val decrease = Button(activity).apply {
                tag = "$key-decrease"
                text = when { key.endsWith("_top") -> "↑"; key.endsWith("_height") -> "↓"; else -> "←" }
                contentDescription = app.getString(R.string.island_decrease, app.getString(title))
                setPadding(0, 0, 0, 0)
                setOnClickListener { bar.progress = (bar.progress - 1).coerceAtLeast(0) }
            }
            val increase = Button(activity).apply {
                tag = "$key-increase"
                text = when { key.endsWith("_top") -> "↓"; key.endsWith("_height") -> "↑"; else -> "→" }
                contentDescription = app.getString(R.string.island_increase, app.getString(title))
                setPadding(0, 0, 0, 0)
                setOnClickListener { bar.progress = (bar.progress + 1).coerceAtMost(bar.max) }
            }
            fun updateLabel() {
                label.text = app.getString(format, bar.progress + minimum)
                decrease.isEnabled = bar.progress > 0; increase.isEnabled = bar.progress < bar.max
            }
            updateLabel(); body.addView(label)
            body.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(decrease, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(bar, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(increase, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    prefs.edit().putInt(key, value + minimum).apply()
                    updateLabel()
                    relayout()
                }
            })
        }
        slider("island_width", R.string.island_width, R.string.island_width_value, 112, 320, capsuleWidth)
        slider("island_height", R.string.island_height, R.string.island_height_value, 28, 72, capsuleHeight)
        slider("island_top", R.string.island_position, R.string.island_position_value, 0, 240, capsuleTop)
        slider("island_horizontal", R.string.island_horizontal, R.string.island_horizontal_value, -100, 100,
            prefs.getInt("island_horizontal", 0).coerceIn(-100, 100))
        body.addView(TextView(activity).apply { text = app.getString(R.string.island_position_help); textSize = 12f })
        body.addView(TextView(activity).apply { text = app.getString(R.string.island_handle_title); textSize = 18f; setPadding(0, dp(16), 0, dp(8)) })
        slider("island_handle_width", R.string.island_width, R.string.island_width_value, 16, 128, prefs.getInt("island_handle_width", 32).coerceIn(16, 128))
        slider("island_handle_height", R.string.island_height, R.string.island_height_value, 0, 32, prefs.getInt("island_handle_height", 12).coerceIn(0, 32))
        slider("island_handle_top", R.string.island_position, R.string.island_position_value, 0, 320, handleTop)
        slider("island_handle_horizontal", R.string.island_horizontal, R.string.island_horizontal_value, -100, 100, prefs.getInt("island_handle_horizontal", horizontal).coerceIn(-100, 100))
        body.addView(TextView(activity).apply { text = app.getString(R.string.island_touch_title); textSize = 18f; setPadding(0, dp(16), 0, dp(8)) })
        slider("island_touch_width", R.string.island_width, R.string.island_width_value, 48, 320, prefs.getInt("island_touch_width", capsuleWidth).coerceIn(48, 320))
        slider("island_touch_height", R.string.island_height, R.string.island_height_value, 48, 160, prefs.getInt("island_touch_height", 48).coerceIn(48, 160))
        slider("island_touch_top", R.string.island_position, R.string.island_position_value, minimumTouchTop, maxOf(320, minimumTouchTop), prefs.getInt("island_touch_top", maxOf(capsuleTop, minimumTouchTop)).coerceIn(minimumTouchTop, maxOf(320, minimumTouchTop)))
        slider("island_touch_horizontal", R.string.island_horizontal, R.string.island_horizontal_value, -100, 100, prefs.getInt("island_touch_horizontal", horizontal).coerceIn(-100, 100))
        body.addView(TextView(activity).apply { text = app.getString(R.string.island_touch_help); textSize = 12f })
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked && !permissionGranted) {
                dialog?.dismiss()
                try { requestPermission() } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.island_permission_denied, Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(activity, R.string.island_permission_denied, Toast.LENGTH_LONG).show()
                }
            } else { prefs.edit().putBoolean("music_island", checked).apply(); refresh() }
        }
        val settingsDialog = AlertDialog.Builder(activity).setTitle(R.string.island_title)
            .setView(ScrollView(activity).apply { addView(body) }).setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.island_reset_layout) { _, _ ->
                prefs.edit().remove("island_width").remove("island_height").remove("island_gap")
                    .remove("island_offset").remove("island_horizontal").remove("island_top")
                    .also { edit -> for (part in listOf("handle", "touch")) for (axis in listOf("width", "height", "top", "horizontal")) edit.remove("island_${part}_$axis") }.apply()
            }
            .create()
        settingsDialog.setOnDismissListener {
            if (dialog === settingsDialog) { dialog = null; refresh(); relayout() }
        }
        dialog = settingsDialog
        settingsDialog.setOwnerActivity(activity)
        settingsDialog.show()
        refresh()
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
        var levels: FloatArray? = null
            set(value) {
                val previous = field
                field = if (value != null && previous != null) FloatArray(5) { i ->
                    if (value.all { it == 0f }) 0f else previous[i] +
                        (value[i] - previous[i]) * if (value[i] > previous[i]) .85f else .45f
                } else value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val t = SystemClock.uptimeMillis() / 170.0
            val measured = levels
            paint.strokeWidth = width / 15f
            for (i in 0..4) {
                val bar = if (!playing) .25f + (i % 3) * .12f else if (measured != null) .12f + .83f * measured[i]
                    else (.25 + .65 * kotlin.math.abs(sin(t + i * 1.3))).toFloat()
                val x = width * (i + .5f) / 5
                canvas.drawLine(x, height * (1 - bar) / 2, x, height * (1 + bar) / 2, paint)
            }
            if (playing && measured == null && isAttachedToWindow && visibility == VISIBLE) postInvalidateDelayed(120)
        }
    }
}
