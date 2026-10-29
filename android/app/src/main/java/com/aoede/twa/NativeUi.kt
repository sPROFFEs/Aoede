package com.aoede.twa

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Android views only. No HTML, JavaScript bridge, or WebView is mounted in this interface.
 */
class NativeUi(val activity: MainActivity, val app: AoedeApp) {
    val api
        get() = app.nativeApi

    val library
        get() = app.nativeLibrary

    val player
        get() = app.nativePlayback

    private val root =
        FrameLayout(activity).apply {
            id = R.id.main_root
            clipToPadding = false
        }
    private lateinit var shell: LinearLayout
    private lateinit var chromeHeader: LinearLayout
    lateinit var content: LinearLayout
        private set

    private lateinit var scroll: ScrollView
    private lateinit var mini: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var headerTitle: TextView
    private var chromeTheme = ""
    private var keyboardOpen = false
    private var safeBars = androidx.core.graphics.Insets.NONE
    private var destination = "home"
    lateinit var design: NativeDesign
        private set

    private val history = ArrayDeque<() -> Unit>()
    private var routeKey: String? = null
    private var restoringRoute = false
    private var fullscreen: View? = null
    private var updateFull: (() -> Unit)? = null
    @Volatile private var generation = 0
    @Volatile private var alive = true
    private var renderedPath: String? = null
    private var renderedSignature: String? = null
    private var renderedStableKey: String? = null
    private var backAction: (() -> Unit)? = null
    private var reload: (() -> Unit)? = null
    private var refreshHome: (() -> Unit)? = null
    private var theme = "classic"
    var background = Color.rgb(16, 17, 19)
        private set

    var surface = Color.rgb(31, 32, 35)
        private set

    var ink = Color.WHITE
        private set

    var muted = Color.LTGRAY
        private set

    var accent = Color.rgb(30, 215, 96)
        private set

    private var libraryMetadataAlbums = false
    private var libraryState = android.os.Bundle()
    private var searchQuery = ""
    private var searchSource = "all"
    private var searchCollections = false
    private var offline = false
    private var migration: NativeMigration? = null
    private var translations = JSONObject()
    internal var trackSlideDirection = 1
    private var miniSignature = ""
    private val popups = mutableSetOf<PopupWindow>()
    private val dialogs = mutableSetOf<android.app.Dialog>()
    private var toastView: View? = null
    private val dismissToast = Runnable {
        toastView?.let(root::removeView)
        toastView = null
    }
    private var loginVisible = false
    private var connecting = false
    private var requestedPlayerMode: String? = null
    private val playbackChanged: () -> Unit = {
        if (alive) {
            renderMini()
            updateFull?.invoke()
        }
    }
    private val openPlayer: () -> Unit = { if (alive) showPlayer() }
    private val accentBindings = linkedMapOf<View, (Int) -> Unit>()
    private val defaultCover by lazy {
        requireNotNull(
            app.assets.open("web/assets/img/default_cover.png").use(BitmapFactory::decodeStream)
        )
    }

    fun followAccent(view: View, paint: (Int) -> Unit) {
        if (!accentBindings.containsKey(view))
            view.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {}

                    override fun onViewDetachedFromWindow(view: View) {
                        accentBindings.remove(view)
                        view.removeOnAttachStateChangeListener(this)
                    }
                }
            )
        accentBindings[view] = paint
        paint(accent)
    }

    private fun sceneArtwork(bitmap: android.graphics.Bitmap? = null) {
        if (theme != "glass" || reduced()) return
        val image =
            if (loginVisible) null
            else
                bitmap
                    ?: app.mediaController.metadata.getBitmap(
                        android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART
                    )
                    ?: defaultCover
        val scene =
            (root.background as? NativeArtworkBackground)
                ?: NativeArtworkBackground(background).also { root.background = it }
        scene.artwork(image)
    }

    private fun refreshArtworkAccent(bitmap: android.graphics.Bitmap?) {
        if (theme != "glass") return
        design.artworkSeed(nativeArtworkSeed(bitmap ?: defaultCover))
        sceneArtwork(bitmap)
        navigation.invalidate()
        if (accent == design.accent) return
        accent = design.accent
        accentBindings.values.toList().forEach { it(accent) }
        for (i in 0 until navigation.childCount) {
            val item = navigation.getChildAt(i) as LinearLayout
            if (item.isSelected) {
                (item.getChildAt(0) as ImageView).setImageDrawable(
                    NativeIcon(
                        if (item.tag == "discovery") "compass" else item.tag.toString(),
                        accent,
                        theme,
                    )
                )
                (item.getChildAt(1) as TextView).setTextColor(accent)
            }
        }
    }

    private val mutations = mutableSetOf<String>()
    val tools by lazy { NativeTools(this) }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()

    fun text(key: String, fallback: String): String = translations.optString(key, fallback)

    private fun palette() {
        val prefs = activity.getSharedPreferences(MainActivity.PREFS, 0)
        theme =
            (api.account?.optString("theme") ?: prefs.getString("native_last_theme", "classic"))
                ?.takeIf { it in setOf("classic", "glass", "vitrine") } ?: "classic"
        val language =
            (api.account?.optString("language") ?: prefs.getString("native_last_language", "en"))
                ?.takeIf { it in setOf("en", "es", "de") } ?: "en"
        if (api.account != null)
            prefs
                .edit()
                .putString("native_last_theme", theme)
                .putString("native_last_language", language)
                .apply()
        val light =
            theme != "classic" &&
                (activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        NativeIcon.install(app)
        design = NativeDesign(activity, theme, light, reduced())
        if (theme == "glass")
            design.artworkSeed(
                if (loginVisible) 0xff7c5ce6.toInt()
                else
                    nativeArtworkSeed(
                        app.mediaController.metadata.getBitmap(
                            android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART
                        ) ?: defaultCover
                    )
            )
        background = design.background
        surface = design.surface
        ink = design.ink
        muted = design.muted
        accent = design.accent
        app.darkAppearance = !light
        activity.applyAppearance(!light)
        translations =
            runCatching {
                    JSONObject(
                        app.assets.open("native/locales/$language.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                }
                .getOrDefault(JSONObject())
    }

    fun create(saved: android.os.Bundle? = null) {
        activity.setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime()) && keyboard.bottom > 0
            safeBars = bars
            // The material reaches the screen edge; only its controls avoid system navigation.
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                if (keyboardOpen) keyboard.bottom else 0,
            )
            applyDockInsets()
            if (loginVisible)
                (root.getChildAt(0) as? ScrollView)?.setPadding(
                    0,
                    0,
                    0,
                    if (keyboardOpen) 0 else bars.bottom,
                )
            if (::dock.isInitialized && fullscreen == null && shell.parent === root)
                dock.visibility = if (keyboardOpen) View.GONE else View.VISIBLE
            (fullscreen as? NativePlayerView)?.let(::placePlayer)
            (fullscreen as? NativeWrappedStory)?.let(::placeStory)
            app.musicIsland.updateSafeInsets(
                insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.tappableElement()
                )
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
        player.onChanged = playbackChanged
        player.onOpenPlayer = openPlayer
        app.nativeChanged = playbackChanged
        if (api.hasCredentials) {
            connecting = true
            palette()
            screen("Aoede")
            label("Connecting to your music…", 16)
            val waitingGeneration = generation
            api.execute({ api.refreshContext() }) { _, error ->
                if (!alive) return@execute
                connecting = false
                if (error == null) {
                    player.restore()
                    player.refreshPreferences()
                    if (generation == waitingGeneration) {
                        if (saved == null || !restoreState(saved)) home()
                        requestedPlayerMode?.let { showPlayer(it) }
                    }
                    requestedPlayerMode = null
                    app.nativeSync.flush()
                    app.updater.check()
                } else if (error is NativeApi.Failure && error.code == 401) {
                    player.release()
                    api.clearSession()
                    login()
                } else if (generation == waitingGeneration) login(error.message)
            }
        } else login()
    }

    fun shape(color: Int, radius: Int = 14) = design.shape(color, radius)

    fun space(height: Int, parent: LinearLayout = content) {
        parent.addView(View(activity), LinearLayout.LayoutParams(1, dp(height)))
    }

    fun label(
        value: String,
        size: Int = 15,
        parent: LinearLayout = content,
        color: Int = ink,
    ): TextView =
        TextView(activity).apply {
            text = value
            design.typography(this, size.toFloat(), if (size >= 20) 700 else 400)
            setTextColor(color)
            if (color == accent) followAccent(this) { setTextColor(it) }
            parent.addView(
                this,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (size >= 20) {
                        topMargin = dp(16)
                        bottomMargin = dp(12)
                    }
                },
            )
        }

    fun button(
        value: String,
        parent: LinearLayout = content,
        primary: Boolean = false,
        action: () -> Unit,
    ): Button =
        Button(activity).apply {
            val style = if (primary) parent.tag as? JSONObject else null
            text = value
            isAllCaps = false
            design.typography(
                this,
                style?.optDouble("font", 14.0)?.toFloat() ?: 14f,
                style?.optInt("weight", 600) ?: 600,
            )
            setTextColor(if (primary) design.onAccent else ink)
            minHeight = dp(44)
            minimumHeight = dp(44)
            background =
                design.touch(
                    if (primary) accent else design.wash,
                    style?.optInt("radius")
                        ?: if (theme == "vitrine") {
                            if (primary) 10 else 8
                        } else 999,
                    false,
                )
            val x = style?.optInt("paddingX", 16) ?: 16
            val y = style?.optInt("paddingY", 10) ?: 10
            setPadding(dp(x), dp(y), dp(x), dp(y))
            parent.addView(
                this,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                },
            )
            if (primary)
                followAccent(this) { color ->
                    backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                    setTextColor(design.onAccent)
                }
            design.press(this, reducedMotion())
            setOnClickListener { action() }
        }

    fun field(
        hint: String,
        parent: LinearLayout = content,
        password: Boolean = false,
        value: String = "",
    ): EditText =
        EditText(activity).apply {
            isSaveEnabled = false
            this.hint = hint
            contentDescription = hint
            setText(value)
            design.typography(this, 16f)
            setHintTextColor(muted)
            isSingleLine = true
            inputType =
                if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
            design.typography(this, 16f)
            background = design.shape(design.wash, if (theme == "classic") 6 else 16, true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        }

    fun chipBar(
        items: List<Pair<String, () -> Unit>>,
        active: Int = -1,
        parent: LinearLayout = content,
        top: Int = 0,
        bottom: Int = 0,
    ): LinearLayout {
        val scroller =
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
        val strip = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        scroller.addView(strip)
        parent.addView(
            scroller,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            },
        )
        for ((index, item) in items.withIndex()) {
            val selected = index == active
            val hit =
                FrameLayout(activity).apply {
                    contentDescription = item.first
                    isFocusable = true
                    isSelected = selected
                    setOnClickListener { item.second() }
                }
            val text =
                TextView(activity).apply {
                    this.text = item.first
                    design.typography(this, 13f, 500)
                    setTextColor(
                        if (selected) {
                            if (theme == "classic") Color.BLACK else accent
                        } else if (theme == "classic") ink else muted
                    )
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), 0)
                    background =
                        design.touch(
                            if (selected) {
                                if (theme == "classic") accent else design.strong
                            } else {
                                if (theme != "vitrine") design.wash else Color.TRANSPARENT
                            },
                            design.chipRadius,
                            theme == "glass",
                        )
                }
            hit.addView(text, FrameLayout.LayoutParams(-2, dp(36), Gravity.CENTER_VERTICAL))
            strip.addView(hit, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
            design.press(hit, reducedMotion())
        }
        return strip
    }

    fun panel(
        title: String,
        description: String = "",
        glyph: String = "",
        parent: LinearLayout = content,
        settings: Boolean = false,
    ): LinearLayout {
        val modern = theme != "classic"
        val admin =
            routeKey.orEmpty().startsWith("/admin") ||
                routeKey.orEmpty().startsWith("/api/admin/") ||
                routeKey.orEmpty().startsWith("/api/mobile/v2/admin")
        val spec =
            if (settings || admin) design.component(if (settings) "settingsPanel" else "adminPanel")
            else null
        val surfaceStyle = spec?.getJSONObject("surface")
        val headerStyle = spec?.getJSONObject("header")
        val bodyStyle = spec?.getJSONObject("body")
        val panel =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    design.shape(
                        if (settings && !modern) Color.TRANSPARENT
                        else if (modern) design.content else this@NativeUi.background,
                        if (modern) 26 else 14,
                        !settings || modern,
                    )
            }
        if (surfaceStyle != null) {
            panel.background =
                design
                    .shape(
                        if (modern && reduced()) design.content
                        else Color.parseColor(surfaceStyle.getString("background")),
                        surfaceStyle.getInt("radius"),
                    )
                    .apply {
                        if (surfaceStyle.getInt("borderWidth") > 0)
                            setStroke(
                                dp(surfaceStyle.getInt("borderWidth")),
                                Color.parseColor(surfaceStyle.getString("border")),
                            )
                    }
        }
        if (modern) panel.setPadding(dp(22), dp(22), dp(22), dp(22))
        if (surfaceStyle != null) {
            val x = dp(surfaceStyle.getInt("paddingX") + surfaceStyle.getInt("borderWidth"))
            val y = dp(surfaceStyle.getInt("paddingY") + surfaceStyle.getInt("borderWidth"))
            panel.setPadding(x, y, x, y)
        }
        parent.addView(
            panel,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(if (settings && !modern) 36 else 24)
            },
        )
        val head =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                if (settings) setPadding(0, 0, 0, dp(12))
                else setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        if (headerStyle != null)
            head.setPadding(
                dp(headerStyle.getInt("paddingX")),
                dp(headerStyle.getInt("paddingY")),
                dp(headerStyle.getInt("paddingX")),
                dp(if (settings) 12 else 16),
            )
        panel.addView(head)
        val titleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(titleRow)
        if (glyph.isNotBlank())
            titleRow.addView(
                ImageView(activity).apply { setImageDrawable(NativeIcon(glyph, ink, theme)) },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) },
            )
        titleRow.addView(
            TextView(activity).apply {
                text = title
                design.typography(this, if (settings) 22.4f else 17f, if (settings) 700 else 600)
                spec?.getJSONObject("title")?.let {
                    design.typography(this, it.getDouble("font").toFloat(), it.getInt("weight"))
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (description.isNotBlank())
            label(description, if (settings) 15 else 13, head, muted).apply {
                if (settings) design.lines(this, 1.45f)
                spec?.getJSONObject("description")?.let {
                    design.typography(this, it.getDouble("font").toFloat())
                    design.lines(this, 1.45f)
                }
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            }
        panel.addView(
            View(activity).apply { setBackgroundColor(design.border) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
        val body =
            LinearLayout(activity).apply {
                tag = spec?.optJSONObject("button")
                orientation = LinearLayout.VERTICAL
                if (settings) setPadding(0, dp(16), 0, 0)
                else setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        if (bodyStyle != null)
            body.setPadding(
                dp(bodyStyle.getInt("paddingX")),
                dp(if (settings) 16 else 14),
                dp(bodyStyle.getInt("paddingX")),
                dp(bodyStyle.getInt("paddingY")),
            )
        panel.addView(body)
        return body
    }

    fun tabs(
        labels: List<String>,
        selected: Int,
        parent: LinearLayout = content,
        icons: List<String> = emptyList(),
        changed: (Int) -> Unit,
    ): LinearLayout {
        val spec = design.component("tabs")
        val scroller = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val strip =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
        scroller.addView(strip, android.view.ViewGroup.LayoutParams(-2, -1))
        parent.addView(
            scroller,
            LinearLayout.LayoutParams(-1, dp(if (theme == "vitrine") 44 else 48)),
        )
        parent.addView(
            View(activity).apply { setBackgroundColor(design.border) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(8) },
        )
        fun mark(index: Int) {
            for (i in 0 until strip.childCount) {
                val hit = strip.getChildAt(i) as FrameLayout
                val caption = hit.getChildAt(0) as TextView
                hit.isSelected = i == index
                val look = spec.getJSONObject(if (hit.isSelected) "active" else "inactive")
                val tint = if (hit.isSelected) accent else Color.parseColor(look.getString("ink"))
                caption.setTextColor(tint)
                icons.getOrNull(i)?.let { glyph ->
                    caption.setCompoundDrawablesRelative(
                        NativeIcon(glyph, tint, theme).apply { setBounds(0, 0, dp(18), dp(18)) },
                        null,
                        null,
                        null,
                    )
                }
                caption.background =
                    design
                        .touch(
                            Color.parseColor(look.getString("background")),
                            look.getInt("radius"),
                        )
                        .apply {
                            if (look.getInt("borderWidth") > 0)
                                (getDrawable(0) as GradientDrawable).setStroke(
                                    dp(look.getInt("borderWidth")),
                                    Color.parseColor(look.getString("border")),
                                )
                        }
            }
        }
        for ((index, title) in labels.withIndex()) {
            val hit =
                FrameLayout(activity).apply {
                    contentDescription = title
                    isFocusable = true
                    setOnClickListener {
                        mark(index)
                        changed(index)
                    }
                }
            val caption =
                TextView(activity).apply {
                    text = title
                    design.typography(this, 13f, 600)
                    gravity = Gravity.CENTER
                    setPadding(dp(14), 0, dp(14), 0)
                    compoundDrawablePadding = dp(8)
                    isDuplicateParentStateEnabled = true
                }
            hit.addView(
                caption,
                FrameLayout.LayoutParams(-2, dp(if (theme == "vitrine") 36 else 40), Gravity.CENTER),
            )
            strip.addView(
                hit,
                LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(spec.getInt("gap")) },
            )
            design.press(hit, reducedMotion())
        }
        mark(selected)
        return strip
    }

    fun labelledField(
        title: String,
        parent: LinearLayout = content,
        password: Boolean = false,
        value: String = "",
    ): EditText {
        label(title, 12, parent, muted).apply {
            letterSpacing = .04f
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        val frame = FrameLayout(activity)
        parent.addView(
            frame,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            },
        )
        val input =
            EditText(activity).apply {
                isSaveEnabled = false
                design.typography(this, 16f)
                setText(value)
                isSingleLine = true
                contentDescription = title
                inputType =
                    if (password)
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT
                design.typography(this, 16f)
                background = design.shape(design.wash, if (theme == "classic") 6 else 16, true)
                setPadding(dp(14), 0, dp(if (password) 50 else 14), 0)
            }
        frame.addView(input, FrameLayout.LayoutParams(-1, -1))
        if (password)
            frame.addView(
                NativeIconButton(activity, muted, theme).apply {
                    text = "eye"
                    contentDescription = text("login.show_password", "Show password")
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = design.touch(Color.TRANSPARENT, 999)
                    setOnClickListener {
                        val position = input.selectionStart
                        val show = input.transformationMethod != null
                        input.transformationMethod =
                            if (show) null
                            else android.text.method.PasswordTransformationMethod.getInstance()
                        text = if (show) "eye-off" else "eye"
                        contentDescription = if (show) "Hide password" else "Show password"
                        input.setSelection(position.coerceIn(0, input.length()))
                    }
                },
                FrameLayout.LayoutParams(dp(44), -1, Gravity.END),
            )
        return input
    }

    fun identity(
        name: String,
        subtitle: String,
        photo: String,
        parent: LinearLayout = content,
        size: Int = 84,
        edit: (() -> Unit)? = null,
        detail: String = "",
    ) {
        val row =
            LinearLayout(activity).apply {
                gravity = if (detail.isBlank()) Gravity.CENTER_VERTICAL else Gravity.TOP
            }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        val portrait =
            FrameLayout(activity).apply {
                background = design.shape(design.wash, 999, true)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                contentDescription =
                    if (edit != null) text("settings.avatar_change", "Change profile picture")
                    else name
                if (edit != null) {
                    isFocusable = true
                    setOnClickListener { edit() }
                }
            }
        row.addView(
            portrait,
            LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(18) },
        )
        portrait.addView(
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(NativeIcon("user", muted, theme))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                artwork(this, photo)
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        if (edit != null)
            portrait.addView(
                ImageView(activity).apply {
                    setImageDrawable(NativeIcon("pencil", Color.WHITE, theme))
                    setPadding(dp((size - 16) / 2), dp(6), dp((size - 16) / 2), dp(6))
                    setBackgroundColor(0x66000000)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(-1, dp(28), Gravity.BOTTOM),
            )
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        label(name, if (size == 72) 26 else if (size == 88) 32 else 28, copy).apply {
            if (size == 72) {
                design.typography(this, 25.6f, 700)
                design.lines(this, 1.1f)
            }
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, dp(6))
        }
        label(subtitle, 13, copy, if (edit != null) accent else muted).apply {
            if (edit != null) {
                minimumHeight = dp(28)
                gravity = Gravity.CENTER_VERTICAL
                design.typography(this, 12.48f, 700)
                setTextColor(accent)
                background = design.shape(design.wash, 999, true)
                setPadding(dp(10), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(-2, dp(28))
            }
        }
        if (detail.isNotBlank())
            label(detail, if (edit != null) 16 else 14, copy, muted).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
                design.lines(this, 1.35f)
            }
    }

    fun row(
        title: String,
        subtitle: String = "",
        image: String = "",
        parent: LinearLayout = content,
        coverSize: Int = 52,
        action: () -> Unit,
    ): LinearLayout {
        val row =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                minimumHeight = dp(64)
                isFocusable = true
                contentDescription = if (subtitle.isBlank()) title else "$title, $subtitle"
                background = design.touch(Color.TRANSPARENT, if (theme == "classic") 4 else 12)
                setOnClickListener { action() }
            }
        val cover =
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(design.wash, if (theme == "classic") 4 else 8)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                setImageDrawable(NativeIcon("music-2", muted, theme))
            }
        row.addView(
            cover,
            LinearLayout.LayoutParams(dp(coverSize), dp(coverSize)).apply { marginEnd = dp(12) },
        )
        if (coverSize == 0) cover.visibility = View.GONE
        if (image.isNotBlank()) artwork(cover, image)
        val copy =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        label(title, 16, copy).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (subtitle.isNotBlank())
            label(subtitle, 13, copy, muted).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(3)
            }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    fun artwork(
        image: ImageView,
        path: String,
        loaded: ((android.graphics.Bitmap) -> Unit)? = null,
    ) {
        if (path.length > if (path.startsWith("data:image/")) 192 * 1024 else 4096) return
        image.tag = path
        if (image.drawable == null) image.setImageBitmap(defaultCover)
        val pixels = image.layoutParams?.width?.takeIf { it > 0 }?.coerceIn(64, 512) ?: 512
        api.artwork.load(path, pixels) { value ->
            if (alive && image.tag == path && value != null) {
                image.setImageBitmap(value)
                loaded?.invoke(value)
                if (::navigation.isInitialized) navigation.invalidate()
                if (::mini.isInitialized) mini.invalidate()
            }
        }
    }

    private fun decodeArtwork(bytes: ByteArray, pixels: Int = 512): android.graphics.Bitmap? {
        return NativeArtwork.decode(bytes, pixels)
    }

    fun screen(
        title: String,
        back: (() -> Unit)? = null,
        nav: String = "",
        key: String = title,
        restore: (() -> Unit)? = null,
    ) {
        renderedPath = null
        renderedSignature = null
        renderedStableKey = null
        refreshHome = null
        hideKeyboard()
        if (!restoringRoute && key != routeKey) {
            reload?.let { history.addLast(it) }
            if (history.size > 32) history.removeFirst()
        }
        routeKey = key
        reload = restore
        loginVisible = false
        accentBindings.clear()
        destination = nav
        popups.toList().forEach { it.dismiss() }
        dialogs.toList().forEach { it.dismiss() }
        backAction = back
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        generation++
        palette()
        val key = "$theme:${design.light}:${reduced()}"
        if (chromeTheme != key || !::shell.isInitialized || shell.parent !== root) {
            chromeTheme = key
            root.removeAllViews()
            root.setBackgroundColor(background)
            shell = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            root.addView(shell, FrameLayout.LayoutParams(-1, -1))
            val header =
                LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(if (theme == "glass") 11 else design.gutter),
                        dp(if (theme == "glass") 5 else 6),
                        dp(if (theme == "glass") 11 else design.gutter),
                        dp(if (theme == "glass") 5 else 6),
                    )
                    background =
                        if (theme == "glass")
                            design.shape(surface, 999, true).apply {
                                setStroke(dp(1), design.color("square-rim"))
                                colors =
                                    intArrayOf(
                                        androidx.core.graphics.ColorUtils.compositeColors(
                                            design.color("square-glint"),
                                            surface,
                                        ),
                                        surface,
                                    )
                                orientation = GradientDrawable.Orientation.TL_BR
                            }
                        else shape(this@NativeUi.background, 0)
                }
            chromeHeader = header
            root.addView(
                header,
                FrameLayout.LayoutParams(-1, dp(if (theme == "glass") 54 else 56), Gravity.TOP)
                    .apply {
                        if (theme == "glass") {
                            setMargins(dp(12), dp(6), dp(12), dp(8))
                        }
                    },
            )
            if (theme == "glass" && !reduced())
                header.background =
                    NativeBackdrop(
                        shell,
                        header,
                        surface,
                        dp(999).toFloat(),
                        design.color("square-rim"),
                        design.color("square-glint"),
                        blurDp = design.blurDp,
                        saturation = design.saturation,
                    )
            headerTitle =
                TextView(activity).apply {
                    design.typography(this, if (theme == "vitrine") 24f else 20f, 700)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                }
            val avatar =
                ImageView(activity).apply {
                    contentDescription = text("header.settings", "Settings")
                    isFocusable = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(design.strong, 999)
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    setImageDrawable(NativeIcon("user", ink, theme))
                    setOnClickListener { tools.settings() }
                }
            val more =
                NativeIconButton(activity, if (theme == "vitrine") accent else ink, theme).apply {
                    text = "ellipsis"
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    background =
                        design.touch(if (theme == "glass") design.wash else Color.TRANSPARENT, 999)
                    contentDescription = text("header.more", "More options")
                    isFocusable = true
                    setOnClickListener { menu(this) }
                    design.press(this, reducedMotion())
                }
            if (theme == "vitrine") {
                header.addView(headerTitle, LinearLayout.LayoutParams(0, -1, 1f))
                header.addView(
                    more,
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        marginStart = dp(12)
                        marginEnd = dp(12)
                    },
                )
                header.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))
            } else {
                header.addView(
                    avatar,
                    LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) },
                )
                header.addView(headerTitle, LinearLayout.LayoutParams(0, -1, 1f))
                header.addView(
                    more,
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(12) },
                )
            }
            header.post {
                val hit = android.graphics.Rect()
                avatar.getHitRect(hit)
                hit.inset(-dp(4), -dp(4))
                header.touchDelegate = android.view.TouchDelegate(hit, avatar)
            }
            if (api.username.isNotBlank())
                artwork(avatar, "/user/avatar/${NativeApi.encode(api.username)}")
            scroll =
                ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = false
                    clipToPadding = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
            content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(content)
            shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            dock =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    tag = "native-bottom-dock"
                }
            root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            mini = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            dock.addView(mini)
            navigation =
                LinearLayout(activity).apply {
                    gravity = Gravity.CENTER
                    setPadding(
                        dp(if (theme == "glass") 7 else if (theme == "vitrine") 8 else 0),
                        dp(if (theme == "glass") 7 else if (theme == "vitrine") 4 else 0),
                        dp(if (theme == "glass") 7 else if (theme == "vitrine") 8 else 0),
                        dp(if (theme == "glass") 7 else if (theme == "vitrine") 4 else 0),
                    )
                    background =
                        if (theme == "classic")
                            GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                intArrayOf(0xec121212.toInt(), 0xff121212.toInt()),
                            )
                        else
                            design.shape(
                                if (theme == "classic") this@NativeUi.background else surface,
                                if (theme == "glass") 999 else 0,
                                theme != "classic",
                            )
                }
            dock.addView(
                navigation,
                LinearLayout.LayoutParams(-1, dp(60)).apply {
                    if (theme == "glass") setMargins(dp(12), 0, dp(12), dp(8))
                },
            )
            if (theme != "classic" && !reduced())
                navigation.background =
                    NativeBackdrop(
                        shell,
                        navigation,
                        surface,
                        dp(if (theme == "glass") 999 else 0).toFloat(),
                        design.color("square-rim"),
                        if (theme == "glass") design.color("square-glint") else Color.TRANSPARENT,
                        blurDp = design.blurDp,
                        saturation = design.saturation,
                    )
            for (keyName in design.nav) {
                val label =
                    when (keyName) {
                        "home" -> text("menu.home", "Home")
                        "search" -> text("menu.search", "Search")
                        "discovery" -> text("tabs.discover", "Discover")
                        else -> text("menu.library", "Your Library")
                    }
                val item =
                    LinearLayout(activity).apply {
                        tag = keyName
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        isFocusable = true
                        contentDescription = label
                        setOnClickListener {
                            destination = keyName
                            when (keyName) {
                                "home" -> home()
                                "search" -> search()
                                "discovery" -> discover()
                                else -> collections()
                            }
                        }
                    }
                item.addView(ImageView(activity), LinearLayout.LayoutParams(dp(24), dp(24)))
                item.addView(
                    TextView(activity).apply {
                        text = label
                        design.typography(this, 10.4f, if (theme == "vitrine") 600 else 500)
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(if (theme == "vitrine") 4 else 3)
                    },
                )
                navigation.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
        fullscreen?.let(root::removeView)
        fullscreen = null
        updateFull = null
        dock.visibility = if (keyboardOpen) View.GONE else View.VISIBLE
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        chromeHeader.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        headerTitle.text = title
        scroll.removeAllViews()
        content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.setPadding(
            dp(design.gutter),
            dp(16 + if (theme == "glass") 68 else 56),
            dp(design.gutter),
            dp(84),
        )
        scroll.scrollTo(0, 0)
        for (i in 0 until navigation.childCount) {
            val item = navigation.getChildAt(i) as LinearLayout
            val selected = item.tag == destination
            val color =
                if (selected) {
                    if (theme == "classic") ink else accent
                } else design.faint
            item.isSelected = selected
            item.background =
                design.touch(
                    if (theme == "glass" && selected) design.strong else Color.TRANSPARENT,
                    999,
                )
            (item.getChildAt(0) as ImageView).setImageDrawable(
                NativeIcon(
                    if (item.tag == "discovery") "compass" else item.tag.toString(),
                    color,
                    theme,
                )
            )
            (item.getChildAt(1) as TextView).setTextColor(color)
        }
        sceneArtwork()
        applyDockInsets()
        miniSignature = ""
        renderMini()
        if (offline) label(text("offline.local_mode", "Offline mode"), 13, color = muted)
    }

    fun iconButton(parent: LinearLayout, glyph: String, label: String, action: () -> Unit) =
        NativeIconButton(activity, ink, theme).apply {
            text = glyph
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = design.touch(Color.TRANSPARENT, 999)
            contentDescription = label
            isFocusable = true
            minimumHeight = dp(44)
            minimumWidth = dp(44)
            parent.addView(this, LinearLayout.LayoutParams(dp(44), dp(44)))
            design.press(this, reducedMotion())
            setOnClickListener { action() }
        }

    fun load(
        title: String,
        path: String,
        read: () -> Any = { api.request(path) },
        restore: (() -> Unit)? = null,
        stableKey: String? = null,
        render: (Any) -> Unit,
    ) {
        val tab =
            when {
                path.startsWith("/api/playlists") ||
                    path.startsWith("/api/library") ||
                    path == "/api/favorites" -> "library"
                path.startsWith("/api/party") ||
                    path.startsWith("/api/mixes") ||
                    path.startsWith("/api/wrapped") ||
                    path.startsWith("/api/public") ||
                    path == "/user/list" -> "home"
                path.startsWith("/api/discovery/feed") -> "discovery"
                path.startsWith("/api/search") -> "search"
                else -> ""
            }
        val refreshing = renderedPath == path && routeKey == path && ::content.isInitialized
        val previousY = if (refreshing) scroll.scrollY else 0
        if (refreshing) {
            generation++
            reload = restore ?: { load(title, path, read, restore, stableKey, render) }
            popups.toList().forEach { it.dismiss() }
            dialogs.toList().forEach { it.dismiss() }
        } else
            screen(
                title,
                if (path == "/api/recommendations") null else ({ home() }),
                nav = tab,
                key = path,
                restore = restore ?: { load(title, path, read, restore, stableKey, render) },
            )
        val stamp = generation
        if (!refreshing) label(text("common.loading", "Loading…"), 15, color = muted)
        api.execute({
            if (!alive || stamp != generation) null
            else
                read().let {
                    it to if (stableKey != null) NativeApi.digest(it.toString()) else null
                }
        }) { result, error ->
            if (!alive || stamp != generation) return@execute
            val data = (result as? Pair<*, *>)?.first
            val signature = (result as? Pair<*, *>)?.second as? String
            if (refreshing && error != null) {
                if (error is NativeApi.Failure && error.code == 401) {
                    player.release()
                    api.clearSession()
                    login("Sign in again")
                    return@execute
                }
                toast(error.message ?: "Refresh failed")
                return@execute
            }
            if (
                refreshing &&
                    stableKey != null &&
                    stableKey == renderedStableKey &&
                    signature == renderedSignature
            )
                return@execute
            content.removeAllViews()
            if (error != null) {
                label(error.message ?: "Request failed")
                button("Retry") { reload?.invoke() }
                if (error is NativeApi.Failure && error.code == 401) button("Sign in") { login() }
            } else if (data != null)
                try {
                    render(data)
                    if (generation == stamp) {
                        renderedPath = path
                        renderedSignature = signature
                        renderedStableKey = stableKey
                        if (refreshing)
                            scroll.post { if (generation == stamp) scroll.scrollTo(0, previousY) }
                    }
                } catch (_: org.json.JSONException) {
                    content.removeAllViews()
                    label("The server returned an incompatible response. Update Aoede and retry.")
                }
        }
    }

    fun request(
        path: String,
        method: String = "POST",
        body: JSONObject? = null,
        form: Map<String, String>? = null,
        done: ((Any?) -> Unit)? = null,
    ) {
        val key =
            method +
                path +
                NativeApi.digest(
                    (body
                            ?.let { JSONObject(it.toString()).apply { remove("request_id") } }
                            ?.toString() ?: form?.toSortedMap()?.toString())
                        .orEmpty()
                )
        if (!mutations.add(key)) return
        val stamp = generation
        val scope = api.scope
        val origin = api.origin
        api.execute({ api.request(path, method, body, form) }) { data, error ->
            mutations.remove(key)
            if (!alive || scope != api.scope || origin != api.origin) return@execute
            if (error != null) toast(error.message ?: "Request failed")
            else {
                if (path.contains("/cover")) api.artwork.clear()
                if (stamp == generation) {
                    if (path.startsWith("/admin/") && tools.followAdminJob(data)) return@execute
                    if (done != null) done(data) else reload?.invoke()
                }
            }
        }
    }

    fun toast(message: String) {
        api.main.removeCallbacks(dismissToast)
        dismissToast.run()
        val box =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background =
                    design.shape(
                        if (fullscreen != null) 0xff28282b.toInt()
                        else if (theme == "classic") 0xff282828.toInt() else design.strong,
                        if (theme == "classic") 12 else 20,
                        true,
                    )
                elevation = dp(8).toFloat()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        box.addView(
            ImageView(activity).apply {
                setImageDrawable(
                    NativeIcon("info", if (fullscreen != null) Color.WHITE else ink, theme)
                )
            },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) },
        )
        box.addView(
            TextView(activity).apply {
                text = message.take(240)
                design.typography(this, 14f)
                if (fullscreen != null) setTextColor(Color.WHITE)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        root.addView(
            box,
            FrameLayout.LayoutParams(
                    minOf(dp(460), activity.resources.displayMetrics.widthPixels - dp(32)),
                    -2,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
                .apply {
                    bottomMargin =
                        dp(
                            if (fullscreen != null) 80
                            else if (player.current == null) 92 else 100 + design.miniHeight
                        )
                },
        )
        toastView = box
        if (!reducedMotion()) {
            box.alpha = 0f
            box.translationY = dp(8).toFloat()
            box.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        api.main.postDelayed(dismissToast, 5000)
    }

    fun sheet(
        title: String,
        menu: Boolean = false,
        track: JSONObject? = null,
        build: (LinearLayout, android.app.Dialog) -> Unit,
    ): android.app.Dialog {
        val dialog = android.app.Dialog(activity)
        dialog.setCanceledOnTouchOutside(true)
        val darkPlayer = fullscreen != null
        val material = if (darkPlayer) NativeDesign(activity, theme, false, reduced()) else design
        val color = if (menu) material.strong else material.color("modal-background")
        val foreground = if (darkPlayer) Color.WHITE else ink
        val spec = material.modal
        val padding =
            if (track != null) 0
            else if (menu) 16 else spec.getInt("padding") + spec.optInt("border", 1)
        val topRadius = if (menu) 20 else spec.getJSONArray("radii").getDouble(0).toInt()
        val bottomRadius =
            if (track != null) 0
            else if (menu) 20 else spec.getJSONArray("radii").getDouble(2).toInt()
        val scrim =
            if (menu) {
                if (design.light) 0x47000000 else 0x8c000000.toInt()
            } else material.color("modal-scrim")
        val panel =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    design.shape(color, topRadius, true).apply {
                        cornerRadii =
                            floatArrayOf(
                                dp(topRadius).toFloat(),
                                dp(topRadius).toFloat(),
                                dp(topRadius).toFloat(),
                                dp(topRadius).toFloat(),
                                dp(bottomRadius).toFloat(),
                                dp(bottomRadius).toFloat(),
                                dp(bottomRadius).toFloat(),
                                dp(bottomRadius).toFloat(),
                            )
                        if (!menu) setStroke(dp(1), design.color("modal-border"))
                    }
                setPadding(
                    dp(padding),
                    dp(if (menu) 8 else padding),
                    dp(padding),
                    dp(if (menu) 20 else padding),
                )
            }
        if (theme != "classic" && !reduced() && ::shell.isInitialized && shell.parent === root)
            panel.background =
                NativeBackdrop(
                    fullscreen ?: shell,
                    panel,
                    color,
                    dp(topRadius).toFloat(),
                    if (menu) design.border else design.color("modal-border"),
                    if (theme == "glass") design.color("square-glint") else Color.TRANSPARENT,
                    shade = scrim,
                    bottomRadius = dp(bottomRadius).toFloat(),
                    blurDp = design.blurDp,
                    saturation = design.saturation,
                )
        val handle =
            View(activity).apply {
                background = design.shape(if (darkPlayer) 0x66ffffff else design.border, 999)
            }
        if (menu)
            panel.addView(
                handle,
                LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(10)
                },
            )
        val header =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                if (!menu) setPadding(0, 0, 0, dp(spec.getInt("headerPadding")))
                if (track != null) setPadding(dp(20), 0, dp(20), dp(16))
            }
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        if (track != null) {
            val cover =
                ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = design.shape(surface, 8)
                    clipToOutline = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                }
            header.addView(
                cover,
                LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(14) },
            )
            artwork(cover, artworkPath(track))
            val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            header.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            label(title, 15, copy, foreground).apply {
                design.typography(this, 14.72f, 600)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            label(track.optString("artist"), 13, copy, muted).apply {
                design.typography(this, 12.48f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(3)
            }
        } else
            header.addView(
                TextView(activity).apply {
                    text = title
                    design.typography(
                        this,
                        if (menu) 18f else spec.getDouble("generalTitleSize").toFloat(),
                        700,
                    )
                    setTextColor(foreground)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        val close =
            NativeIconButton(activity, foreground, theme).apply {
                text = "x"
                contentDescription = text("common.close", "Close")
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = design.touch(Color.TRANSPARENT, 999)
                setOnClickListener { dialog.dismiss() }
            }
        if (track == null) header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (!menu || track != null)
            panel.addView(
                View(activity).apply { setBackgroundColor(design.color("modal-header-line")) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(if (menu) 12 else spec.getInt("headerGap")), 0, 0)
            }
        val scroller =
            object : ScrollView(activity) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val maximum = (resources.displayMetrics.heightPixels * .72f).toInt()
                        val available =
                            if (
                                View.MeasureSpec.getMode(heightMeasureSpec) ==
                                    View.MeasureSpec.UNSPECIFIED
                            )
                                maximum
                            else View.MeasureSpec.getSize(heightMeasureSpec)
                        super.onMeasure(
                            widthMeasureSpec,
                            View.MeasureSpec.makeMeasureSpec(
                                minOf(maximum, available),
                                View.MeasureSpec.AT_MOST,
                            ),
                        )
                    }
                }
                .apply {
                    isVerticalScrollBarEnabled = false
                    addView(body)
                }
        panel.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        build(body, dialog)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(Color.alpha(scrim) / 255f)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) isNavigationBarContrastEnforced = false
        }
        showDialog(dialog)
        if (track == null) {
            close.isFocusableInTouchMode = true
            close.requestFocus()
        }
        dialog.window?.setLayout(
            activity.resources.displayMetrics.widthPixels -
                dp(if (track != null) 0 else 24 + if (menu) 0 else spec.optInt("margin") * 2),
            -2,
        )
        if (menu && track == null)
            dialog.window?.attributes = dialog.window?.attributes?.apply { y = dp(12) }
        if (!reducedMotion()) {
            panel.translationY = dp(48).toFloat()
            panel.alpha = .6f
            panel.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
        return dialog
    }

    fun <T : android.app.Dialog> showDialog(dialog: T): T {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
        return dialog
    }

    fun prompt(
        title: String,
        hint: String = "",
        initial: String = "",
        password: Boolean = false,
        done: (String) -> Unit,
    ) {
        sheet(title) { body, dialog ->
            val input = field(hint, body, password, initial)
            val footer = LinearLayout(activity).apply { gravity = Gravity.END }
            body.addView(footer)
            button(text("common.cancel", "Cancel"), footer) { dialog.dismiss() }.layoutParams =
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(8)
                    topMargin = dp(16)
                }
            button(text("common.save", "Save"), footer, true) {
                    val value = input.text.toString().trim()
                    if (value.isNotBlank()) {
                        dialog.dismiss()
                        done(value)
                    } else input.error = text("common.required", "Required")
                }
                .layoutParams =
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { topMargin = dp(16) }
        }
    }

    fun confirm(title: String, message: String, done: () -> Unit) {
        sheet(title) { body, dialog ->
            label(message, 15, body, muted)
            space(12, body)
            val footer = LinearLayout(activity)
            body.addView(footer)
            button(text("common.cancel", "Cancel"), footer) { dialog.dismiss() }.layoutParams =
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
            button(text("common.confirm", "Confirm"), footer, true) {
                    dialog.dismiss()
                    done()
                }
                .layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
    }

    fun choose(
        title: String,
        items: List<String>,
        icons: List<String> = emptyList(),
        track: JSONObject? = null,
        done: (Int) -> Unit,
    ) {
        sheet(title, menu = true, track = track) { body, dialog ->
            if (items.isEmpty()) label(text("common.no_results", "No results"), 15, body, muted)
            for ((index, item) in items.withIndex()) {
                val foreground =
                    if (icons.getOrNull(index) == "trash-2") 0xffff6479.toInt()
                    else if (fullscreen != null) Color.WHITE else ink
                val row =
                    LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(
                            dp(if (track == null) 12 else 22),
                            0,
                            dp(if (track == null) 12 else 22),
                            0,
                        )
                        background =
                            design.touch(Color.TRANSPARENT, if (theme == "classic") 4 else 12)
                        isFocusable = true
                        contentDescription = item
                        setOnClickListener {
                            dialog.dismiss()
                            done(index)
                        }
                    }
                icons.getOrNull(index)?.let { glyph ->
                    row.addView(
                        ImageView(activity).apply {
                            setImageDrawable(NativeIcon(glyph, foreground, theme))
                        },
                        LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) },
                    )
                }
                row.addView(
                    TextView(activity).apply {
                        text = item
                        design.typography(this, 15f, 500)
                        setTextColor(foreground)
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                body.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
    }

    fun login(error: String? = null) {
        hideKeyboard()
        loginVisible = true
        accentBindings.clear()
        history.clear()
        routeKey = null
        reload = null
        refreshHome = null
        backAction = null
        popups.toList().forEach { it.dismiss() }
        dialogs.toList().forEach { it.dismiss() }
        api.main.removeCallbacks(dismissToast)
        dismissToast.run()
        tools.close()
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        generation++
        palette()
        root.setBackgroundColor(background)
        root.removeAllViews()
        fullscreen = null
        updateFull = null
        searchQuery = ""
        searchSource = "all"
        searchCollections = false
        val scroller =
            ScrollView(activity).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, 0, 0, if (keyboardOpen) 0 else safeBars.bottom)
            }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        if (theme == "classic") root.setBackgroundColor(Color.BLACK) else sceneArtwork()
        val outer =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(32), dp(20), dp(32))
            }
        scroller.addView(outer)
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(28), dp(12), dp(28))
            }
        outer.addView(
            body,
            LinearLayout.LayoutParams(
                minOf(dp(460), activity.resources.displayMetrics.widthPixels - dp(40)),
                -2,
            ),
        )
        content = body
        body.isFocusableInTouchMode = true
        body.requestFocus()
        val compact = activity.resources.configuration.screenHeightDp < 600
        body.addView(
            ImageView(activity).apply {
                setImageBitmap(
                    app.assets.open("web/assets/icon.png").use(BitmapFactory::decodeStream)
                )
                contentDescription = "Aoede"
            },
            LinearLayout.LayoutParams(dp(if (compact) 48 else 64), dp(if (compact) 48 else 64))
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(16)
                },
        )
        label("AOEDE", 13, body, muted).apply {
            gravity = Gravity.CENTER
            letterSpacing = .14f
        }
        space(12, body)
        label(text("login.headline", "Your music. Your space."), 32, body).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(260)
            letterSpacing = -.04f
            layoutParams =
                LinearLayout.LayoutParams(paint.measureText("0").times(12).toInt(), -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(12)
                }
            design.lines(this, 1.08f)
        }
        label(text("login.intro", "Sign in to your private music library."), 15, body, muted)
            .apply {
                gravity = Gravity.CENTER
                design.lines(this, 1.5f)
            }
        space(if (compact) 20 else 32, body)
        if (!error.isNullOrBlank())
            label(error, 14, body, Color.rgb(230, 65, 82)).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = design.shape(0x20e64152, 10)
            }
        fun loginField(key: String, fallback: String, secret: Boolean = false): EditText {
            val input = labelledField(text(key, fallback), body, secret)
            val frame = input.parent as FrameLayout
            (frame.layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(54)
                bottomMargin = dp(6)
            }
            input.hint =
                text(
                    if (secret) "login.password_placeholder" else "login.username_placeholder",
                    fallback,
                )
            input.background =
                design
                    .shape(if (theme == "classic") 0xff121415.toInt() else design.wash, 10)
                    .apply {
                        setStroke(
                            dp(1),
                            if (theme == "classic") 0xff72767a.toInt() else design.border,
                        )
                    }
            val caption = body.getChildAt(body.indexOfChild(frame) - 1) as TextView
            design.typography(caption, 13.44f, 600)
            caption.letterSpacing = 0f
            return input
        }
        val username = loginField("login.username", "Username")
        val password = loginField("login.password", "Password", true)
        username.filters = arrayOf(android.text.InputFilter.LengthFilter(32))
        password.filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
        username.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        design.typography(username, 16f)
        username.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        if (Build.VERSION.SDK_INT >= 26) {
            username.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            username.setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        val rememberRow =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
            }
        body.addView(
            rememberRow,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )
        val remember =
            CheckBox(activity).apply {
                contentDescription = text("login.remember_me", "Remember me")
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                minWidth = dp(44)
                minimumHeight = dp(44)
            }
        rememberRow.addView(
            remember,
            LinearLayout.LayoutParams(dp(32), dp(44)).apply { marginEnd = dp(12) },
        )
        val rememberCopy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rememberRow.addView(rememberCopy, LinearLayout.LayoutParams(0, -2, 1f))
        label(text("login.remember_me", "Remember me"), 14, rememberCopy)
        label(
            text("login.remember_me_sub", "Keeps you signed in without saving your password."),
            13,
            rememberCopy,
            muted,
        )
        rememberCopy.setOnClickListener { remember.isChecked = !remember.isChecked }
        val submit =
            button(text("login.button", "Log in"), body, true) {}
                .apply {
                    design.typography(this, 16f, 700)
                    setTextColor(design.onAccent)
                    background = design.touch(accent, 999)
                    layoutParams =
                        LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) }
                }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submit.performClick()
                true
            } else false
        }
        submit.setOnClickListener {
            val name = username.text.toString().trim()
            val secret = password.text.toString()
            if (name.length !in 2..32 || secret.isBlank() || secret.length > 1024) {
                toast("Enter your username and password")
                return@setOnClickListener
            }
            submit.isEnabled = false
            player.release()
            val previousScope = api.scope
            api.execute({ api.authenticate(name, secret, remember.isChecked) }) { _, failure ->
                password.text.clear()
                submit.isEnabled = true
                if (!alive) return@execute
                if (failure == null) {
                    offline = false
                    if (previousScope != api.scope) player.release()
                    player.restore()
                    player.refreshPreferences()
                    home()
                    app.nativeSync.flush()
                    app.updater.check()
                } else login(failure.message ?: "Sign in failed")
            }
        }
        space(28, body)
        label(text("login.footer", "Private music cloud"), 13, body, muted).gravity = Gravity.CENTER
        val server =
            TextView(activity).apply {
                text =
                    api.origin?.host.orEmpty() +
                        " · " +
                        text("native.configure_server", "Configure server")
                design.typography(this, 12f)
                setTextColor(muted)
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                isFocusable = true
                background = design.touch(Color.TRANSPARENT, 8)
                setOnClickListener { activity.openServerSettings() }
            }
        body.addView(server, LinearLayout.LayoutParams(-1, -2))
        if (api.account != null)
            button(text("offline.title", "Device downloads"), body) {
                offline = true
                player.restore()
                offlineDownloads()
            }
    }

    fun browseTabs(selected: String) {
        val tabs =
            listOf(
                "all" to (text("tabs.all", "All") to { home() }),
                "party" to (text("tabs.party", "Party") to { parties() }),
                "public" to (text("tabs.public", "Public") to { publicCollections() }),
                "community" to (text("tabs.community", "Community") to { community() }),
                "discovery" to (text("tabs.discover", "Discover") to { discover() }),
                "radios" to (text("tabs.radios", "Radios") to { tools.radios() }),
            )
        chipBar(
            tabs.map { it.second },
            tabs.indexOfFirst { it.first == selected },
            top = if (theme == "vitrine") 0 else if (theme == "glass") 16 else 14,
            bottom = if (theme == "vitrine") 16 else 18,
        )
    }

    fun sectionHeader(
        title: String,
        parent: LinearLayout = content,
        moreLabel: String = text("common.see_all", "See all"),
        more: (() -> Unit)? = null,
    ) {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading =
            TextView(activity).apply {
                text = title
                design.typography(this, 22f, 700)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        row.addView(heading, LinearLayout.LayoutParams(0, dp(44), 1f))
        heading.gravity = Gravity.CENTER_VERTICAL
        if (more != null) {
            val link =
                TextView(activity).apply {
                    text = moreLabel
                    design.typography(this, if (theme == "vitrine") 14f else 13f, 600)
                    setTextColor(if (theme == "vitrine") ink else muted)
                    gravity = Gravity.CENTER
                    setPadding(
                        dp(if (theme == "vitrine") 14 else 8),
                        0,
                        dp(if (theme == "vitrine") 14 else 0),
                        0,
                    )
                    background = design.touch(Color.TRANSPARENT, 999, theme == "vitrine")
                    isFocusable = true
                    setOnClickListener { more() }
                }
            row.addView(link, LinearLayout.LayoutParams(-2, dp(44)))
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        space(8, parent)
    }

    fun cards(
        items: List<JSONObject>,
        parent: LinearLayout = content,
        action: (JSONObject, Int) -> Unit,
    ) {
        val scroller =
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
        val strip = LinearLayout(activity)
        scroller.setPadding(dp(2), 0, dp(2), dp(8))
        scroller.addView(strip)
        parent.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        val size =
            (activity.resources.configuration.screenWidthDp * .43f).toInt().coerceIn(140, 200)
        for ((index, item) in items.withIndex()) {
            val padding = if (theme == "classic") 0 else if (theme == "glass") 11 else 12
            val card =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
                    isFocusable = true
                    background =
                        design.touch(
                            Color.TRANSPARENT,
                            if (theme == "glass") 18 else design.cardRadius,
                            theme == "glass",
                        )
                    contentDescription = item.optString("title", item.optString("name"))
                    setOnClickListener { action(item, index) }
                }
            val art =
                ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(design.wash, if (theme == "classic") 4 else 8)
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                }
            card.addView(art, LinearLayout.LayoutParams(-1, dp(size - padding * 2)))
            artwork(art, item.optString("thumbnail"))
            space(10, card)
            label(item.optString("title", item.optString("name")), 15, card).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                design.typography(this, 15f, 600)
                design.lines(this, 1.35f)
            }
            val subtitle = item.optString("artist", item.optString("description"))
            if (subtitle.isNotBlank())
                label(subtitle, 13, card, muted).apply {
                    maxLines = 2
                    design.lines(this, 1.45f)
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
                }
            strip.addView(
                card,
                LinearLayout.LayoutParams(dp(size), -2).apply {
                    marginEnd = dp(if (theme == "vitrine") 20 else 12)
                },
            )
            design.press(card, reducedMotion())
        }
        space(24, parent)
    }

    private fun quickPicks(parent: LinearLayout, recent: JSONObject) {
        parent.removeAllViews()
        val entries = mutableListOf<Pair<JSONObject, () -> Unit>>()
        entries.add(
            JSONObject().put("name", text("menu.liked", "Liked Songs")).put("liked", true) to
                {
                    favorites()
                }
        )
        for (p in NativeTrack.items(recent.optJSONArray("playlists"))) entries.add(
            p to { collection(p.get("id").toString()) }
        )
        for (m in NativeTrack.items(recent.optJSONArray("mixes"))) entries.add(
            m to { mix(m.optString("key")) }
        )
        for (r in NativeTrack.items(recent.optJSONArray("radios"))) entries.add(
            r to { tools.playSavedRadio(r) }
        )
        val rows = entries.take(8).chunked(2)
        for ((index, pair) in rows.withIndex()) {
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            parent.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(if (theme == "vitrine") 64 else 56)).apply {
                    bottomMargin = if (index < rows.lastIndex) dp(8) else 0
                },
            )
            for ((position, entry) in pair.withIndex()) {
                val item = entry.first
                val tile =
                    LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        isFocusable = true
                        background =
                            design.touch(
                                if (theme == "classic") 0x12ffffff
                                else if (theme == "glass") 0x09ffffff else Color.TRANSPARENT,
                                if (theme == "classic") 4 else if (theme == "glass") 24 else 10,
                            )
                        clipToOutline = true
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        setOnClickListener { entry.second() }
                    }
                val side = if (theme == "vitrine") 48 else 56
                val art = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                if (item.optBoolean("liked")) {
                    art.background = design.likedCover(if (theme == "vitrine") 9 else 0)
                    art.setImageDrawable(
                        NativeIcon("heart", Color.WHITE, theme, theme != "classic")
                    )
                    art.setPadding(
                        dp(if (theme == "vitrine") 12 else 16),
                        dp(if (theme == "vitrine") 12 else 16),
                        dp(if (theme == "vitrine") 12 else 16),
                        dp(if (theme == "vitrine") 12 else 16),
                    )
                } else {
                    art.background = shape(design.wash, if (theme == "vitrine") 10 else 0)
                    art.clipToOutline = true
                    art.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    artwork(art, item.optString("thumbnail"))
                }
                tile.addView(
                    art,
                    LinearLayout.LayoutParams(dp(side), dp(side)).apply {
                        marginEnd = dp(if (theme == "vitrine") 12 else 10)
                    },
                )
                tile.addView(
                    TextView(activity).apply {
                        text = item.optString("name", item.optString("title"))
                        design.typography(this, 13f, if (theme == "vitrine") 500 else 700)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
                )
                row.addView(
                    tile,
                    LinearLayout.LayoutParams(0, -1, 1f).apply {
                        if (position == 0) marginEnd = dp(8)
                    },
                )
                design.press(tile, reducedMotion())
            }
            if (pair.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    fun home() {
        destination = "home"
        if (offline) {
            offlineDownloads()
            return
        }
        val owner = "${api.origin?.rule}:${api.scope}"
        if (
            routeKey == "home" &&
                renderedPath == "home" &&
                renderedStableKey == owner &&
                fullscreen == null &&
                api.account?.optString("theme", theme) == theme
        ) {
            refreshHome?.invoke()
            return
        }
        screen(text("menu.home", "Home"), nav = "home", key = "home", restore = { home() })
        browseTabs("all")
        val stamp = generation
        val cacheScope = runCatching { library.scope() }.getOrNull()
        val snapshot =
            runCatching { cacheScope?.let { library.get("snapshot", "home_feed", it) } }.getOrNull()
                ?: JSONObject()
        fun region() =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        val quick = region()
        quickPicks(quick, snapshot.optJSONObject("recent") ?: JSONObject())
        space(if (theme == "vitrine") 40 else 28)
        if (theme == "vitrine") {
            content.addView(
                View(activity).apply { setBackgroundColor(design.border) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
            space(24)
        }
        val wrapped = region()
        val partySection = region()
        val mixes = region()
        val recommendations = region()
        fun paintParties(data: Any) {
            partySection.removeAllViews()
            sectionHeader("Party rooms", partySection) { parties() }
            val rooms =
                NativeTrack.items(if (data is JSONObject) data.optJSONArray("rooms") else data)
            if (rooms.isEmpty()) {
                val create =
                    row(
                        text("party.start", "Start a party"),
                        text("party.create_first", "Create the first shared listening room."),
                        parent = partySection,
                        coverSize = 48,
                    ) {
                        parties()
                    }
                create.background =
                    design.touch(
                        design.strong,
                        if (theme == "classic") 8 else if (theme == "glass") 24 else 12,
                        true,
                    )
                create.setPadding(dp(13), dp(13), dp(13), dp(13))
                val copy = create.getChildAt(1) as LinearLayout
                design.typography(copy.getChildAt(0) as TextView, 16f, 700)
                (create.getChildAt(0) as ImageView).apply {
                    setImageDrawable(NativeIcon("plus", Color.WHITE, theme))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background =
                        GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                intArrayOf(accent, 0xff0b6740.toInt()),
                            )
                            .apply {
                                cornerRadius =
                                    dp(
                                            if (theme == "classic") 6
                                            else if (theme == "glass") 18 else 12
                                        )
                                        .toFloat()
                            }
                }
            } else for (room in rooms.take(3)) partyCard(room, partySection)
            if (partySection.childCount > 0)
                (partySection.getChildAt(partySection.childCount - 1).layoutParams
                        as? LinearLayout.LayoutParams)
                    ?.bottomMargin = 0
            space(24, partySection)
        }
        fun paintMixes(data: Any) {
            mixes.removeAllViews()
            val list = NativeTrack.items(data)
            if (list.isNotEmpty()) {
                sectionHeader(text("home.your_mixes", "Your Mixes"), mixes)
                cards(list, mixes) { mix, _ -> this@NativeUi.mix(mix.optString("key")) }
            }
        }
        fun paintRecommendations(data: Any) {
            recommendations.removeAllViews()
            for (section in NativeTrack.items(data)) {
                sectionHeader(section.optString("title"), recommendations)
                val items = NativeTrack.items(section.optJSONArray("items"))
                cards(items, recommendations) { _, index ->
                    if (player.party != null) toast("Leave the party first")
                    else player.play(items, index)
                }
            }
        }
        val pending = mutableSetOf<String>()
        var firstRefresh = true
        fun fetch(key: String, path: String, paint: (Any) -> Unit) {
            if (!pending.add(key)) return
            if (firstRefresh && key != "recent")
                synchronized(snapshot) { snapshot.opt(key) }?.let(paint)
            api.execute({
                if (!alive || stamp != generation) null
                else {
                    val value = api.request(path)
                    val changed =
                        synchronized(snapshot) {
                            val changed = snapshot.opt(key)?.toString() != value.toString()
                            snapshot.put(key, value)
                            if (cacheScope != null)
                                runCatching {
                                    library.put("snapshot", "home_feed", snapshot, cacheScope)
                                }
                            changed
                        }
                    value to changed
                }
            }) { result, error ->
                pending.remove(key)
                if (!alive || stamp != generation) return@execute
                val value = (result as? Pair<*, *>)?.first
                if (error == null && value != null) {
                    if ((result as Pair<*, *>).second == true) paint(value)
                } else if (key == "parties" && synchronized(snapshot) { !snapshot.has(key) })
                    paint(JSONArray())
            }
        }
        fun refresh() {
            fetch("recent", "/api/recent-activity") { if (it is JSONObject) quickPicks(quick, it) }
            fetch("parties", "/api/party/rooms", ::paintParties)
            fetch("mixes", "/api/mixes", ::paintMixes)
            fetch("sections", "/api/recommendations", ::paintRecommendations)
            fetch(
                "wrapped",
                "/api/wrapped/${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}?summary=false",
            ) {
                wrapped.removeAllViews()
                val data = it as? JSONObject
                if (
                    data != null &&
                        data.optBoolean("enabled", true) &&
                        data.optBoolean("visible", true)
                ) {
                    val banner =
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(16), dp(14), dp(16), dp(14))
                            background =
                                design.shape(
                                    if (theme == "classic") surface else design.content,
                                    if (theme == "classic") 6 else 24,
                                    theme != "classic",
                                )
                        }
                    wrapped.addView(
                        banner,
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(26) },
                    )
                    label("Aoede Wrapped ${data.optInt("year")}", 16, banner).apply {
                        design.typography(this, 16f, 650)
                    }
                    label(
                            "Your year in music is ready. Explore your listening highlights.",
                            16,
                            banner,
                            muted,
                        )
                        .apply {
                            design.lines(this, 1.45f)
                            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
                        }
                    button("Watch Wrapped", banner) {
                            NativeWrapped(this).open(data.optInt("year"), story = true)
                        }
                        .apply {
                            layoutParams =
                                LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(16) }
                            setCompoundDrawablesRelative(
                                NativeIcon("sparkles", ink, theme).apply {
                                    setBounds(0, 0, dp(18), dp(18))
                                },
                                null,
                                null,
                                null,
                            )
                            compoundDrawablePadding = dp(8)
                        }
                }
            }
            firstRefresh = false
        }
        renderedPath = "home"
        renderedStableKey = owner
        refreshHome = { refresh() }
        refresh()
    }

    fun artworkPath(track: JSONObject): String =
        track.optString("offline_artwork").ifBlank {
            if (!offline && track.optString("thumbnail").isNotBlank()) track.optString("thumbnail")
            else
                runCatching {
                        library
                            .get("track", NativeTrack.reference(track))
                            ?.optString("offline_artwork")
                            .orEmpty()
                    }
                    .getOrDefault("")
                    .ifBlank { track.optString("thumbnail") }
        }

    fun refreshAvatar() {
        api.artwork.clear()
        if (!::chromeHeader.isInitialized) return
        for (i in 0 until chromeHeader.childCount) {
            val view = chromeHeader.getChildAt(i) as? ImageView ?: continue
            val path = view.tag as? String ?: continue
            if (path.startsWith("/user/avatar/")) artwork(view, path)
        }
    }

    fun tracks(
        items: List<JSONObject>,
        collectionId: String? = null,
        collection: JSONObject? = null,
        parent: LinearLayout = content,
    ) {
        if (items.isEmpty()) {
            label(text("library.empty", "No tracks yet"), 15, parent, muted)
            return
        }
        var shown = 0
        fun append() {
            val end = (shown + 40).coerceAtMost(items.size)
            for (i in shown until end) {
                val t = items[i]
                val r =
                    row(
                        t.optString("title", "Unknown track"),
                        t.optString("artist"),
                        artworkPath(t),
                        parent = parent,
                        coverSize = 44,
                    ) {
                        if (player.party != null) {
                            toast("Leave the party to start personal playback")
                            return@row
                        }
                        player.play(items, i)
                        renderMini()
                    }
                val copy = r.getChildAt(1) as LinearLayout
                design.typography(copy.getChildAt(0) as TextView, 15f, 500)
                design.lines(copy.getChildAt(0) as TextView, 1.4f)
                if (copy.childCount > 1) design.lines(copy.getChildAt(1) as TextView, 1.4f)
                if (collectionId != null) r.setPadding(dp(6), dp(8), dp(6), dp(8))
                if (collectionId != null)
                    r.addView(
                        TextView(activity).apply {
                            design.typography(this, 13f)
                            setTextColor(muted)
                            text = (i + 1).toString()
                            gravity = Gravity.CENTER
                        },
                        0,
                        LinearLayout.LayoutParams(dp(24), dp(44)).apply { marginEnd = dp(12) },
                    )
                iconButton(r, "ellipsis", text("header.more", "Track options")) {
                    trackMenu(t, items, i, collectionId, collection)
                }
            }
            shown = end
            if (shown < items.size) {
                val more = button("Show more (${items.size-shown})", parent) {}
                more.setOnClickListener {
                    parent.removeView(more)
                    append()
                }
            }
        }
        append()
    }

    fun trackMenu(
        t: JSONObject,
        items: List<JSONObject>,
        index: Int,
        collectionId: String?,
        collection: JSONObject?,
    ) {
        val labels = mutableListOf<String>()
        val icons = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun action(label: String, icon: String, run: () -> Unit) {
            labels.add(label)
            icons.add(icon)
            actions.add(run)
        }
        val local = NativeTrack.localId(t) > 0
        val remote = NativeTrack.reference(t).startsWith("fed_")
        if (!local)
            action("Preview", "eye") {
                if (player.party != null) toast("Leave the party to start personal playback")
                else player.play(listOf(t))
            }
        if (local)
            action(if (t.optBoolean("is_favorite")) "Unlike" else "Like", "heart") {
                player.favorite(t) { error -> toast(error?.message ?: "Favorites updated") }
            }
        if (!local && !remote)
            action("Download to Library", "download") {
                request(
                    "/api/download",
                    body =
                        JSONObject()
                            .put("url", t.optString("url", t.optString("id")))
                            .put("title", t.optString("title"))
                            .put("artist", t.optString("artist")),
                    done = { toast("Download queued") },
                )
            }
        if (local || remote)
            action(text("player.add_to_queue", "Add to Queue"), "list-plus") {
                if (player.party != null) toast("Party playback is controlled by the host")
                else {
                    player.enqueue(t)
                    toast("Added to queue: ${t.optString("title")}")
                }
            }
        if (local) {
            val downloaded = library.audio(NativeTrack.reference(t)) != null
            if (downloaded)
                action(text("player.remove_offline", "Remove from Offline"), "trash-2") {
                    confirm(
                        "Remove download",
                        "Keep this track on the server and remove this device copy?",
                    ) {
                        library.remove("track", NativeTrack.reference(t))
                        toast("Device download removed")
                    }
                }
            else
                action(text("player.make_offline", "Make Available Offline"), "download-cloud") {
                    download(listOf(t))
                }
        }
        val party = player.party
        if (
            (local || remote) &&
                party != null &&
                (party.room.optBoolean("is_owner") || party.room.optBoolean("allow_guests_queue"))
        )
            action("Add to Party Queue", "radio-tower") {
                party.add(t) { error -> toast(error?.message ?: "Track added to party") }
            }
        if (local || remote) action("Add to Playlist", "plus") { addTrackToCollection(t) }
        if (local)
            action("Request Deletion", "flag") {
                prompt("Why should this track be removed?") { reason ->
                    request(
                        "/api/tracks/${NativeTrack.localId(t)}/delete-request",
                        body = JSONObject().put("reason", reason),
                        done = { toast("Request sent") },
                    )
                }
            }
        if (collectionId != null && collection?.optBoolean("is_editable") == true) {
            for ((delta, label) in listOf(-1 to "Move up", 1 to "Move down")) if (
                index + delta in items.indices
            )
                action(label, if (delta < 0) "arrow-up" else "arrow-down") {
                    val reordered = items.toMutableList()
                    java.util.Collections.swap(reordered, index, index + delta)
                    val payload = JSONArray()
                    reordered.forEachIndexed { position, item ->
                        payload.put(
                            JSONObject()
                                .put("track_id", NativeTrack.reference(item))
                                .put("position", position)
                                .put("item_id", item.opt("item_id") ?: item.opt("playlist_item_id"))
                        )
                    }
                    request(
                        NativeTrack.collectionPath(collectionId) + "/reorder",
                        "PATCH",
                        JSONObject()
                            .put("items", payload)
                            .put("revision", collection.optInt("revision")),
                        done = { collection(collectionId) },
                    )
                }
            action("Remove from Playlist", "trash-2") {
                confirm("Remove track", "Remove this track from the collection?") {
                    request(
                        NativeTrack.collectionPath(collectionId) +
                            "/remove/" +
                            NativeApi.encode(NativeTrack.reference(t)) +
                            "?item_id=" +
                            t.optLong("item_id", t.optLong("playlist_item_id")),
                        "DELETE",
                        done = { collection(collectionId) },
                    )
                }
            }
            if (local)
                action("Use as cover", "image") {
                    request(
                        NativeTrack.collectionPath(collectionId) + "/cover/track",
                        body = JSONObject().put("track_id", NativeTrack.localId(t)),
                        done = { collection(collectionId) },
                    )
                }
        }
        action("Start radio", "radio") {
            tools.smartRadio(t.optString("artist"), t.optString("title"))
        }
        action("Go to artist", "user") { tools.artist(t.optString("artist")) }
        choose(t.optString("title"), labels, icons, track = t) { actions[it]() }
    }

    fun createCollection(initialTrack: JSONObject? = null) {
        choose(
            text("library.create", "Create"),
            if (initialTrack == null) listOf("Playlist", "Album", "Smart playlist")
            else listOf("Playlist", "Album"),
        ) { kind ->
            if (kind == 2) tools.smartPlaylist()
            else
                prompt(text("playlist.name", "Collection name")) { name ->
                    request(
                        "/api/playlists",
                        body =
                            JSONObject()
                                .put("name", name)
                                .put("kind", if (kind == 1) "album" else "playlist")
                                .put("request_id", java.util.UUID.randomUUID().toString()),
                        done = { value ->
                            val id = (value as JSONObject).get("id").toString()
                            if (initialTrack == null) collection(id)
                            else
                                request(
                                    NativeTrack.collectionPath(id) + "/add",
                                    body =
                                        JSONObject()
                                            .put("track_id", NativeTrack.reference(initialTrack)),
                                    done = { collection(id) },
                                )
                        },
                    )
                }
        }
    }

    fun collectionGrid(
        items: List<JSONObject>,
        parent: LinearLayout = content,
        columns: Int = 2,
        action: (JSONObject) -> Unit,
    ) {
        items.chunked(columns).forEach { pair ->
            val line = LinearLayout(activity)
            parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })
            pair.forEachIndexed { index, item ->
                val cell =
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        isFocusable = true
                        background = design.touch(Color.TRANSPARENT, design.cardRadius)
                        setOnClickListener { action(item) }
                    }
                line.addView(
                    cell,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index < columns - 1) marginEnd = dp(14)
                    },
                )
                if (theme == "glass") {
                    val inset = if (columns == 1) 15 else 10
                    cell.setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
                    cell.background =
                        design.touch(design.content, if (columns == 1) 24 else 18, true)
                }
                val art =
                    object : androidx.appcompat.widget.AppCompatImageView(activity) {
                            override fun onMeasure(w: Int, h: Int) {
                                val size = View.MeasureSpec.getSize(w)
                                super.onMeasure(
                                    w,
                                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                                )
                            }
                        }
                        .apply {
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            background = design.shape(design.wash, if (theme == "classic") 6 else 8)
                            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                            clipToOutline = true
                            setImageDrawable(
                                NativeIcon(
                                    if (item.optBoolean("is_liked")) "heart"
                                    else if (item.optString("kind") == "album") "disc-3"
                                    else "list-music",
                                    if (item.optBoolean("is_liked")) Color.WHITE else muted,
                                    theme,
                                    filled = item.optBoolean("is_liked"),
                                    size = if (item.optBoolean("is_liked")) 28 else 44,
                                )
                            )
                        }
                cell.addView(art, LinearLayout.LayoutParams(-1, -2))
                if (item.optString("thumbnail").isNotBlank())
                    artwork(art, item.optString("thumbnail")) {
                        art.scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                if (item.optBoolean("is_liked"))
                    art.background = design.likedCover(if (theme == "vitrine") 8 else 6)
                label(item.optString("name"), 15, cell).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    design.typography(this, 15f, 600)
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
                }
                label(
                        item.optString("grid_subtitle", item.optString("owner_username")),
                        13,
                        cell,
                        muted,
                    )
                    .apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
                    }
                design.press(cell, reducedMotion())
            }
            if (columns == 2 && pair.size == 1)
                line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    private fun libraryLayoutKey() =
        "native_library_grid:" + NativeApi.digest("${api.origin?.rule}:${api.scope}")

    fun collections(
        kind: String = "all",
        query: String = "",
        order: String = "recent",
        grid: Boolean =
            activity
                .getSharedPreferences(MainActivity.PREFS, 0)
                .getBoolean(libraryLayoutKey(), false),
        offset: Int = 0,
    ) {
        if (api.scope.isNotBlank())
            activity
                .getSharedPreferences(MainActivity.PREFS, 0)
                .edit()
                .putBoolean(libraryLayoutKey(), grid)
                .apply()
        libraryState =
            android.os.Bundle().apply {
                putString("kind", kind)
                putString("query", query)
                putString("order", order)
                putBoolean("grid", grid)
                putInt("offset", offset)
            }
        destination = "library"
        if (offline) {
            offlineDownloads()
            return
        }
        val saved =
            kind in listOf("all", "playlists") || (kind == "albums" && !libraryMetadataAlbums)
        val sortKeys =
            if (saved) listOf("recent", "name", "owner", "count")
            else if (kind == "songs") listOf("title", "artist", "newest")
            else listOf("name", "count")
        val sort = order.takeIf { it in sortKeys } ?: sortKeys.first()
        val path =
            if (saved) "/api/playlists"
            else if (kind == "songs")
                "/api/library/tracks?paged=true&limit=50&offset=$offset&sort=$sort&q=${NativeApi.encode(query)}"
            else
                "/api/library/facets?kind=$kind&paged=true&limit=50&offset=$offset&sort=$sort&q=${NativeApi.encode(query)}"
        load(
            text("menu.library", "Your Library"),
            path,
            read = {
                if (saved)
                    JSONObject()
                        .put("collections", api.request(path))
                        .put("favorites", api.request("/api/favorites"))
                else api.request(path)
            },
        ) { value ->
            space(16)
            val actions =
                LinearLayout(activity).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            content.addView(actions)
            button(text("library.smart_playlist", "Smart Playlist"), actions) {
                    tools.smartPlaylist()
                }
                .apply {
                    setCompoundDrawablesRelative(
                        NativeIcon("sparkles", ink, theme).apply {
                            setBounds(0, 0, dp(16), dp(16))
                        },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = dp(6)
                    design.typography(this, 13f, 600)
                    setPadding(dp(12), 0, dp(12), 0)
                    layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) }
                }
            button(text("library.create", "Create"), actions, true) { createCollection() }
                .apply {
                    layoutParams = LinearLayout.LayoutParams(-2, dp(44))
                    design.typography(this, 13f, 600)
                    setTextColor(design.onAccent)
                    setPadding(dp(12), 0, dp(12), 0)
                    setCompoundDrawablesRelative(
                        NativeIcon("plus", design.onAccent, theme).apply {
                            setBounds(0, 0, dp(16), dp(16))
                        },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = dp(6)
                }
            val kinds = listOf("all", "playlists", "albums", "songs", "artists", "genres")
            chipBar(
                kinds.map { key ->
                    text(
                        if (key == "all") "tabs.all" else "library.$key",
                        key.replaceFirstChar(Char::uppercase),
                    ) to { collections(key, query, sort, grid) }
                } + listOf(text("library.offline", "Offline") to { offlineDownloads() }),
                kinds.indexOf(kind),
                top = 28,
                bottom = 16,
            )
            if (kind == "albums")
                toggle(
                    text("library.metadata_albums", "Browse albums from song metadata"),
                    libraryMetadataAlbums,
                    leading = true,
                ) {
                    libraryMetadataAlbums = it
                    collections(kind, query, sort, grid)
                }
            val filter = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            content.addView(filter, LinearLayout.LayoutParams(-1, dp(44)))
            val q =
                EditText(activity).apply {
                    isSaveEnabled = false
                    hint = text("library.search", "Search your library")
                    setText(query)
                    design.typography(this, 14f)
                    setHintTextColor(muted)
                    isSingleLine = true
                    setPadding(dp(14), 0, dp(14), 0)
                    background = design.shape(design.wash, if (theme == "classic") 6 else 12, true)
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                }
            filter.addView(q, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
            iconButton(filter, "search", text("menu.search", "Search")) {
                    collections(kind, q.text.toString().take(200), sort, grid)
                }
                .background = design.touch(design.wash, if (theme == "classic") 4 else 12)
            q.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    collections(kind, q.text.toString().take(200), sort, grid)
                    true
                } else false
            }
            space(12)
            val toolbar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            content.addView(toolbar, LinearLayout.LayoutParams(-1, dp(44)))
            val options =
                if (saved) listOf("Recently added", "Name", "Creator", "Most songs")
                else if (kind == "songs") listOf("Song title", "Artist", "Recently added")
                else listOf("Name", "Most songs")
            val order =
                TextView(activity).apply {
                    text = options[sortKeys.indexOf(sort).coerceAtLeast(0)]
                    design.typography(this, 14f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(12), 0)
                    background = design.touch(design.wash, if (theme == "classic") 6 else 12, true)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        null,
                        null,
                        NativeIcon("chevron-down", ink, theme),
                        null,
                    )
                    isFocusable = true
                    setOnClickListener {
                        choose("Sort", options) {
                            collections(kind, q.text.toString().take(200), sortKeys[it], grid)
                        }
                    }
                }
            toolbar.addView(
                order,
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) },
            )
            if (kind != "songs") {
                iconButton(toolbar, "list-music", "List layout") {
                        collections(kind, query, sort, false)
                    }
                    .background = design.touch(if (!grid) design.strong else design.wash, 6)
                iconButton(toolbar, "grid-2x2", "Grid layout") {
                        collections(kind, query, sort, true)
                    }
                    .background = design.touch(if (grid) design.strong else design.wash, 6)
            }
            space(16)
            var items =
                NativeTrack.items(if (saved) (value as JSONObject).opt("collections") else value)
            var total = (value as? JSONObject)?.optInt("total", items.size) ?: items.size
            var pageOffset = offset
            if (saved) {
                if (kind != "albums")
                    items =
                        listOf(
                            JSONObject()
                                .put("id", "liked")
                                .put("name", text("menu.liked", "Liked Songs"))
                                .put("kind", "playlist")
                                .put("owner_username", api.username)
                                .put("is_liked", true)
                                .put(
                                    "track_count",
                                    NativeTrack.items((value as JSONObject).opt("favorites")).size,
                                )
                        ) + items

                items =
                    items.filter {
                        (kind != "albums" || it.optString("kind") == "album") &&
                            (kind != "playlists" || it.optString("kind") != "album") &&
                            (query.isBlank() ||
                                (it.optString("name") + " " + it.optString("owner_username"))
                                    .contains(query, true))
                    }
                items =
                    when (sort) {
                        "name" -> items.sortedBy { it.optString("name").lowercase() }
                        "owner" -> items.sortedBy { it.optString("owner_username").lowercase() }
                        "count" -> items.sortedByDescending { it.optInt("track_count") }
                        else -> items
                    }
                total = items.size
                pageOffset = offset.coerceAtMost(((total - 1).coerceAtLeast(0) / 50) * 50)
                items = items.drop(pageOffset).take(50)
                fun open(item: JSONObject) {
                    if (item.optBoolean("is_liked")) favorites()
                    else collection(item.get("id").toString())
                }
                for (item in items) item.put(
                    "grid_subtitle",
                    "${if(item.optString("kind")=="album")"Album"else"Playlist"} · ${item.optString("owner_username",api.username)} · ${item.optInt("track_count")} songs",
                )
                if (grid) collectionGrid(items, action = ::open)
                else
                    for (item in items) {
                        val type =
                            if (item.optString("kind") == "album") "Album"
                            else if (item.optBoolean("is_collaborative")) "Collaborative playlist"
                            else "Playlist"
                        val r =
                            row(
                                item.optString("name"),
                                "$type · ${item.optString("owner_username",api.username)} · ${item.optInt("track_count")} songs",
                                item.optString("thumbnail"),
                                coverSize = 56,
                            ) {
                                if (item.optBoolean("is_liked")) favorites()
                                else collection(item.get("id").toString())
                            }
                        r.setPadding(dp(8), dp(8), dp(8), dp(8))
                        val copy = r.getChildAt(1) as LinearLayout
                        design.typography(
                            copy.getChildAt(0) as TextView,
                            if (theme == "vitrine") 15f else 16f,
                            600,
                        )
                        if (theme == "glass") {
                            r.background = design.touch(design.content, 24, true)
                            r.setPadding(dp(9), dp(9), dp(9), dp(9))
                            (r.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(4)
                            (r.getChildAt(0) as ImageView).background =
                                design.shape(design.wash, 20)
                        }
                        if (item.optBoolean("is_liked"))
                            (r.getChildAt(0) as ImageView).apply {
                                background = design.likedCover(6)
                                setPadding(dp(13), dp(13), dp(13), dp(13))
                                setImageDrawable(NativeIcon("heart", Color.WHITE, theme, true))
                            }
                    }
            } else if (kind == "songs") tracks(items)
            else if (grid)
                collectionGrid(
                    items.map {
                        JSONObject(it.toString())
                            .put("grid_subtitle", "${it.optInt("track_count")} songs")
                    }
                ) { item ->
                    val facet =
                        if (kind == "artists") "artist"
                        else if (kind == "albums") "album" else "genre"
                    load(
                        item.optString("name"),
                        "/api/library/tracks?limit=200&$facet=${NativeApi.encode(item.optString("name"))}",
                    ) {
                        tracks(NativeTrack.items(it))
                    }
                }
            else
                for (item in items) row(
                    item.optString("name"),
                    "${item.optInt("track_count")} songs",
                    item.optString("thumbnail"),
                    coverSize = 56,
                ) {
                    val name =
                        if (kind == "artists") "artist"
                        else if (kind == "genres") "genre" else "album"
                    load(
                        item.optString("name"),
                        "/api/library/tracks?limit=200&$name=${NativeApi.encode(item.optString("name"))}",
                    ) {
                        tracks(NativeTrack.items(it))
                    }
                }
            space(20)
            content.addView(
                View(activity).apply { setBackgroundColor(design.border) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
            space(12)
            val footer = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            content.addView(footer)
            label(
                    "Showing ${if(items.isEmpty())0 else pageOffset+1}–${pageOffset+items.size} of $total",
                    14,
                    footer,
                    muted,
                )
                .layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            for ((name, next) in
                listOf(
                    "Previous" to (pageOffset - 50).coerceAtLeast(0),
                    "Next" to pageOffset + 50,
                )) button(name, footer) { collections(kind, query, sort, grid, next) }
                .apply {
                    layoutParams =
                        LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) }
                    isEnabled =
                        if (name == "Previous") offset > 0 else pageOffset + items.size < total
                    alpha = if (isEnabled) 1f else .4f
                }
        }
    }

    fun collection(id: String) {
        if (offline) {
            val cached = library.get("collection", id)
            if (cached == null) {
                toast("Collection unavailable offline")
                return
            }
            offlineCollection(id)
            return
        }
        load(text("library.playlists", "Playlists"), NativeTrack.collectionPath(id)) { value ->
            renderCollection(value as JSONObject, id)
        }
    }

    fun mix(key: String) {
        load("Personal Mix", "/api/mixes/${NativeApi.encode(key)}") { value ->
            val data =
                JSONObject((value as JSONObject).toString())
                    .put("_mix_key", key)
                    .put("name", value.optString("title", "Mix"))
            renderCollection(data, null)
        }
    }

    fun favorites() {
        load(text("menu.liked", "Liked Songs"), "/api/favorites") { value ->
            renderCollection(
                JSONObject()
                    .put("name", text("menu.liked", "Liked Songs"))
                    .put("is_liked", true)
                    .put("kind", "playlist")
                    .put("tracks", JSONArray(NativeTrack.items(value))),
                null,
            )
        }
    }

    private fun renderCollection(data: JSONObject, id: String?, options: (() -> Unit)? = null) {
        val liked = data.optBoolean("is_liked")
        val mix = data.optString("_mix_key")
        val simple = liked || mix.isNotBlank()
        val list = NativeTrack.items(data)
        val apple = theme == "vitrine"
        val glass = theme == "glass"
        val hero =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                if (glass) {
                    background = design.shape(design.content, 28, true)
                    setPadding(dp(19), dp(19), dp(19), dp(19))
                } else setPadding(0, dp(if (apple) 4 else 0), 0, dp(if (apple) 24 else 20))
            }
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))
        val available =
            activity.resources.configuration.screenWidthDp -
                design.gutter * 2 -
                if (glass) 38 else 0
        val side =
            minOf(
                (activity.resources.configuration.screenWidthDp * .64f).toInt(),
                if (apple) 248 else 260,
                available,
            )
        val art =
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(design.wash, if (glass) 20 else if (apple) 8 else 6)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                setImageDrawable(NativeIcon("list-music", muted, theme))
            }
        hero.addView(
            art,
            LinearLayout.LayoutParams(dp(side), dp(side)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
                bottomMargin = dp(26)
            },
        )
        if (liked) {
            art.background = design.likedCover(if (glass) 20 else if (apple) 8 else 6)
            art.scaleType = ImageView.ScaleType.FIT_CENTER
            art.setPadding(
                dp((side - 96) / 2),
                dp((side - 96) / 2),
                dp((side - 96) / 2),
                dp((side - 96) / 2),
            )
            art.setImageDrawable(NativeIcon("heart", Color.WHITE, theme, filled = true))
        } else artwork(art, data.optString("thumbnail"))
        val info =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
        hero.addView(info, LinearLayout.LayoutParams(-1, -2))
        label(
                if (mix.isNotBlank()) "PERSONAL MIX"
                else if (data.optString("kind") == "album") "ALBUM"
                else if (data.optBoolean("is_collaborative")) "COLLABORATIVE PLAYLIST"
                else "PLAYLIST",
                13,
                info,
                muted,
            )
            .apply {
                design.typography(this, 13.44f, 700)
                setTextColor(if (glass || apple) ink else muted)
                gravity = Gravity.CENTER
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
            }
        val heading =
            label(data.optString("name"), if (apple) 26 else 28, info).apply {
                design.typography(
                    this,
                    if (apple) 26f else 28f,
                    if (theme == "classic") 800 else 750,
                )
                letterSpacing = if (theme == "classic") -.05f else -.045f
                design.lines(this, 1.15f)
                gravity = Gravity.CENTER
                val inset = (textSize * .1f + .5f).toInt()
                setPadding(inset, (textSize * .05f).toInt(), inset, (textSize * .05f + .5f).toInt())
                layoutParams =
                    LinearLayout.LayoutParams(-2, -2).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(6)
                    }
                translationX = -textSize * .05f
            }
        if (theme == "classic")
            heading.paint.shader =
                android.graphics.LinearGradient(
                    0f,
                    0f,
                    heading.paint.measureText(heading.text.toString()),
                    0f,
                    Color.WHITE,
                    0xb3ffffff.toInt(),
                    android.graphics.Shader.TileMode.CLAMP,
                )
        val seconds = list.sumOf { it.optDouble("duration", 0.0).toLong() }
        val stats = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        info.addView(
            stats,
            LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(24)
            },
        )
        val creator =
            TextView(activity).apply {
                text =
                    if (mix.isNotBlank())
                        "${list.size} songs · ${data.optString("summary", "Built from your local library")}"
                    else if (liked) "${list.size} songs"
                    else
                        "By ${data.optString("owner_username")}  ·  ${list.size} songs  ·  ${seconds/60} min"
                design.typography(this, 13.44f, 600)
                design.lines(this, 1.5f)
                setTextColor(muted)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (!simple) setOnClickListener { tools.profile(data.optString("owner_username")) }
            }
        stats.addView(creator, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        val badge =
            TextView(activity).apply {
                text = if (data.optBoolean("is_public")) "PUBLIC" else "PRIVATE"
                design.typography(this, 10f, 700)
                letterSpacing = .1f
                setTextColor(accent)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                minimumHeight = dp(24)
                background =
                    design.shape(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 28), 6)
            }
        if (!simple) stats.addView(badge, LinearLayout.LayoutParams(-2, -2))
        if (creator.paint.measureText(creator.text.toString()) + dp(76) > dp(available)) {
            stats.orientation = LinearLayout.VERTICAL
            stats.gravity = Gravity.CENTER
            creator.layoutParams = LinearLayout.LayoutParams(dp(available), -2)
            badge.layoutParams =
                LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = dp(6)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
        }
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        info.addView(
            actions,
            LinearLayout.LayoutParams(-1, if (apple) -2 else dp(if (glass) 44 else 56)).apply {
                topMargin = dp(16)
            },
        )
        fun play(shuffle: Boolean) {
            if (player.party != null) toast("Leave the party first")
            else player.play(if (shuffle) list.shuffled() else list)
        }
        if (apple) {
            button(text("player.play", "Play"), actions, true) { play(false) }
                .apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) }
                    setCompoundDrawablesRelative(
                        NativeIcon("play", design.onAccent, theme).apply {
                            setBounds(0, 0, dp(20), dp(20))
                        },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = dp(8)
                }
            button(text("player.shuffle", "Shuffle"), actions) { play(true) }
                .apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setTextColor(accent)
                    setCompoundDrawablesRelative(
                        NativeIcon("shuffle", accent, theme).apply {
                            setBounds(0, 0, dp(20), dp(20))
                        },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = dp(8)
                }
            for (i in 0 until actions.childCount) (actions.getChildAt(i) as? Button)?.apply {
                design.typography(this, 14.72f, 600)
                addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    val iconWidth = compoundDrawablesRelative[0]?.bounds?.width() ?: 0
                    val padding =
                        (((right - left) -
                                paint.measureText(text.toString()) -
                                iconWidth -
                                compoundDrawablePadding) / 2)
                            .toInt()
                            .coerceAtLeast(dp(12))
                    if (paddingLeft != padding)
                        setPadding(padding, paddingTop, padding, paddingBottom)
                }
            }
        } else {
            val playSize = if (glass) 44 else 56
            iconButton(actions, "play", text("player.play", "Play")) { play(false) }
                .apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(playSize), dp(playSize)).apply {
                            marginEnd = dp(8)
                        }
                    setPadding(
                        dp((playSize - 22) / 2),
                        dp((playSize - 22) / 2),
                        dp((playSize - 22) / 2),
                        dp((playSize - 22) / 2),
                    )
                    background = design.touch(accent, 999)
                    color(design.onAccent)
                }
            iconButton(actions, "shuffle", text("player.shuffle", "Shuffle")) { play(true) }
                .apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        }
        val secondary =
            if (apple)
                LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    info.addView(
                        this,
                        LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) },
                    )
                }
            else actions
        if (id != null && !offline && !liked && list.any { NativeTrack.localId(it) > 0 }) {
            val hit = FrameLayout(activity)
            secondary.addView(
                hit,
                LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) },
            )
            val download =
                Button(activity).apply {
                    text = text("common.download", "Download")
                    isAllCaps = false
                    design.typography(this, 13f, 600)
                    setTextColor(ink)
                    minHeight = dp(32)
                    minimumHeight = dp(32)
                    minimumWidth = 0
                    setPadding(dp(16), 0, dp(16), 0)
                    background = design.touch(design.wash, if (apple) 8 else 999, true)
                    setCompoundDrawablesRelative(
                        NativeIcon("circle-arrow-down", ink, theme).apply {
                            setBounds(0, 0, dp(14), dp(14))
                        },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = dp(6)
                    setOnClickListener {
                        library.put("collection", requireNotNull(id), data)
                        download(list.filter { NativeTrack.localId(it) > 0 }, single = false)
                    }
                }
            hit.addView(
                download,
                FrameLayout.LayoutParams(-2, dp(if (apple) 44 else 32), Gravity.CENTER),
            )
            hit.contentDescription = download.text
            hit.isFocusable = true
            hit.setOnClickListener { download.performClick() }
        }
        if (mix.isNotBlank()) {
            fun save() {
                prompt(
                    text("player.save_as_playlist", "Save as playlist"),
                    initial = data.optString("name"),
                ) { name ->
                    request(
                        "/api/mixes/${NativeApi.encode(mix)}/save",
                        body = JSONObject().put("name", name.take(120)),
                        done = { saved -> collection((saved as JSONObject).get("id").toString()) },
                    )
                }
            }
            if (apple)
                button(text("player.save_as_playlist", "Save as playlist"), secondary) { save() }
                    .layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            else
                iconButton(secondary, "save", text("player.save_as_playlist", "Save as playlist")) {
                    save()
                }
        }
        if (apple && mix.isBlank())
            secondary.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        if (id != null || options != null)
            iconButton(secondary, "ellipsis", text("header.more", "Collection options")) {
                if (options != null) options() else collectionMenu(requireNotNull(id), data)
            }
        if (apple)
            content.addView(
                View(activity).apply { setBackgroundColor(design.border) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(16) },
            )
        tracks(list, id, data)
    }

    fun collectionMenu(id: String, p: JSONObject) {
        val owner = p.optBoolean("is_owner")
        val source =
            if (p.optLong("source_instance_id") > 0 && p.optLong("source_remote_playlist_id") > 0)
                "fed_${p.optLong("source_instance_id")}_${p.optLong("source_remote_playlist_id")}"
            else
                p.optLong("source_playlist_id")
                    .takeIf {
                        it > 0 &&
                            p.optBoolean("source_playlist_exists") &&
                            p.optBoolean("source_playlist_public")
                    }
                    ?.toString()
        val ownerCodes =
            (0..6)
                .filter { it != 1 || p.optBoolean("is_editable") }
                .toMutableList()
                .apply {
                    if (p.optBoolean("is_smart")) addAll(listOf(7, 8))
                    if (source != null) add(9)
                }
        val ownerLabels =
            listOf(
                "Rename",
                "Collaborators",
                "Sharing",
                "Upload cover",
                "Reset cover",
                "Delete",
                "Choose cover from songs",
                "Edit smart rules",
                "Refresh matches",
                "Sync from source",
            )
        val ownerIcons =
            listOf(
                "pencil",
                "users",
                "globe",
                "image",
                "rotate-ccw",
                "trash-2",
                "music-2",
                "sparkles",
                "refresh-cw",
                "refresh-cw",
            )
        val options =
            if (owner) ownerCodes.map { ownerLabels[it] }
            else if (p.optBoolean("is_editable"))
                listOf("Copy / sync", "Collaborators", "Leave playlist")
            else listOf("Copy / sync")
        choose(
            p.optString("name"),
            options,
            if (owner) ownerCodes.map { ownerIcons[it] }
            else if (p.optBoolean("is_editable")) listOf("copy", "users", "log-out")
            else listOf("copy"),
        ) { index ->
            if (owner)
                when (ownerCodes[index]) {
                    0 ->
                        prompt("Rename collection", initial = p.optString("name")) {
                            request(
                                NativeTrack.collectionPath(id),
                                "PUT",
                                JSONObject().put("name", it),
                                done = { collection(id) },
                            )
                        }
                    1 -> collaborators(id)
                    2 ->
                        choose("Visibility", listOf("Private", "Public across connected nodes")) {
                            request(
                                NativeTrack.collectionPath(id) + "/public",
                                body = JSONObject().put("is_public", it == 1),
                                done = { collection(id) },
                            )
                        }
                    3 ->
                        activity.pickNativeFile("image/*") { uri ->
                            tools.upload(
                                NativeTrack.collectionPath(id) + "/cover/upload",
                                "cover_file",
                                uri,
                            ) {
                                collection(id)
                            }
                        }
                    4 ->
                        request(
                            NativeTrack.collectionPath(id) + "/cover",
                            "DELETE",
                            done = { collection(id) },
                        )
                    5 ->
                        confirm(
                            "Delete collection",
                            "Permanently delete this collection? Downloaded device copies are managed separately.",
                        ) {
                            request(
                                NativeTrack.collectionPath(id),
                                "DELETE",
                                done = { collections() },
                            )
                        }
                    6 -> {
                        val tracks =
                            NativeTrack.items(p.optJSONArray("tracks")).filter {
                                NativeTrack.localId(it) > 0
                            }
                        if (tracks.isEmpty()) toast("Add songs before choosing a cover")
                        else
                            sheet("Choose cover") { body, dialog ->
                                for (track in tracks) row(
                                    track.optString("title"),
                                    track.optString("artist"),
                                    "/api/cover/${NativeTrack.localId(track)}",
                                    body,
                                ) {
                                    dialog.dismiss()
                                    request(
                                        NativeTrack.collectionPath(id) + "/cover/track",
                                        body =
                                            JSONObject()
                                                .put("track_id", NativeTrack.localId(track)),
                                        done = {
                                            api.artwork.clear()
                                            collection(id)
                                        },
                                    )
                                }
                            }
                    }
                    7 -> tools.smartPlaylist(p)
                    8 -> request("/api/smart-playlists/$id/refresh", done = { collection(id) })
                    9 ->
                        source?.let {
                            request(
                                NativeTrack.collectionPath(it) + "/copy",
                                done = { collection(id) },
                            )
                        }
                }
            else if (index == 0)
                request(
                    NativeTrack.collectionPath(id) + "/copy",
                    done = { value ->
                        val copy = (value as? JSONObject)?.optString("id").orEmpty()
                        if (Regex("[1-9][0-9]*").matches(copy)) collection(copy) else collections()
                    },
                )
            else if (index == 1) collaborators(id)
            else
                request(
                    NativeTrack.collectionPath(id) + "/collaborators",
                    "GET",
                    done = { value ->
                        val data = value as JSONObject
                        val member =
                            NativeTrack.items(data.optJSONArray("members")).firstOrNull {
                                (!data.isNull("viewer_member_id") &&
                                    it.opt("id")?.toString() ==
                                        data.opt("viewer_member_id")?.toString()) ||
                                    (!it.optBoolean("is_federated") &&
                                        !id.startsWith("fed_") &&
                                        it.optString("username") == api.username)
                            }
                        if (member != null)
                            confirm("Leave playlist", "Leave this collaborative playlist?") {
                                request(
                                    NativeTrack.collectionPath(id) +
                                        "/collaborators/" +
                                        NativeApi.encode(member.get("id").toString()),
                                    "DELETE",
                                    done = { collections() },
                                )
                            }
                    },
                )
        }
    }

    fun collaborators(id: String) {
        sheet(text("library.collaborators", "Collaborators")) { body, dialog ->
            val path = NativeTrack.collectionPath(id) + "/collaborators"
            var busy = false
            lateinit var refresh: () -> Unit
            fun mutate(method: String, member: String? = null, payload: JSONObject? = null) {
                if (busy || !dialog.isShowing) return
                busy = true
                api.execute({
                    api.request(
                        path + if (member == null) "" else "/" + NativeApi.encode(member),
                        method,
                        payload,
                    )
                }) { _, error ->
                    if (!dialog.isShowing) return@execute
                    busy = false
                    if (error != null) toast(error.message ?: "Unable to update collaborators.")
                    else {
                        toast(
                            if (method == "POST") "Collaborator invited."
                            else "Collaborator removed."
                        )
                        refresh()
                    }
                }
            }
            refresh = {
                body.removeAllViews()
                label(text("common.loading", "Loading…"), 14, body, muted)
                api.execute({
                    val data = api.request(path) as JSONObject
                    data.put(
                        "_users",
                        if (data.optBoolean("is_owner"))
                            runCatching { JSONArray(NativeTrack.items(api.request("/user/list"))) }
                                .getOrDefault(JSONArray())
                        else JSONArray(),
                    )
                }) { value, error ->
                    if (!dialog.isShowing) return@execute
                    body.removeAllViews()
                    if (error != null) {
                        label(error.message ?: "Unable to load collaborators.", 14, body, muted)
                        button("Retry", body) { refresh() }
                    } else {
                        val data = value as JSONObject
                        val members = NativeTrack.items(data.optJSONArray("members"))
                        label(
                                text(
                                    "library.collaborators_description",
                                    "Invite a listener on this server or a connected node. They can add, remove and reorder songs. You control sharing and deletion.",
                                ),
                                15,
                                body,
                                muted,
                            )
                            .apply { design.lines(this, 1.5f) }
                        if (data.optBoolean("is_owner")) {
                            space(20, body)
                            label(
                                text("library.invite_collaborator", "Add collaborator"),
                                13,
                                body,
                                muted,
                            )
                            val form =
                                LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                            body.addView(
                                form,
                                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
                            )
                            val input =
                                field(
                                        text("library.invite_search", "Search user to invite..."),
                                        form,
                                    )
                                    .apply {
                                        filters =
                                            arrayOf(android.text.InputFilter.LengthFilter(100))
                                    }
                            input.layoutParams =
                                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
                            val invite =
                                button(text("common.invite", "Invite"), form, true) {
                                    val name = input.text.toString().trim()
                                    if (name.isNotBlank())
                                        mutate("POST", payload = JSONObject().put("username", name))
                                }
                            invite.layoutParams = LinearLayout.LayoutParams(-2, dp(44))
                            val existing =
                                members
                                    .map {
                                        it.optString("profile_key").ifBlank {
                                            it.optString("username")
                                                .lowercase(java.util.Locale.ROOT)
                                        }
                                    }
                                    .toSet()
                            val available =
                                NativeTrack.items(data.optJSONArray("_users")).filter {
                                    it.optString("profile_key").ifBlank {
                                        it.optString("username").lowercase(java.util.Locale.ROOT)
                                    } !in existing &&
                                        (it.optBoolean("is_federated") ||
                                            !it.optString("username").equals(api.username, true))
                                }
                            var filtered = available
                            val suggestions =
                                object : ListView(activity) {
                                        override fun onMeasure(width: Int, height: Int) {
                                            super.onMeasure(
                                                width,
                                                View.MeasureSpec.makeMeasureSpec(
                                                    dp(160),
                                                    View.MeasureSpec.AT_MOST,
                                                ),
                                            )
                                        }
                                    }
                                    .apply {
                                        divider = null
                                        isNestedScrollingEnabled = true
                                        isVerticalScrollBarEnabled = false
                                    }
                            val adapter =
                                object : BaseAdapter() {
                                    override fun getCount() = filtered.size

                                    override fun getItem(position: Int) = filtered[position]

                                    override fun getItemId(position: Int) = position.toLong()

                                    override fun getView(
                                        position: Int,
                                        convert: View?,
                                        parent: android.view.ViewGroup,
                                    ): View {
                                        val user = filtered[position]
                                        val row =
                                            (convert as? LinearLayout)
                                                ?: LinearLayout(activity).apply {
                                                    gravity = Gravity.CENTER_VERTICAL
                                                    minimumHeight = dp(44)
                                                    setPadding(dp(8), dp(8), dp(8), dp(8))
                                                    background = design.touch(design.wash, 8)
                                                    addView(
                                                        ImageView(activity).apply {
                                                            scaleType =
                                                                ImageView.ScaleType.CENTER_CROP
                                                            background = design.shape(surface, 999)
                                                            clipToOutline = true
                                                            outlineProvider =
                                                                android.view.ViewOutlineProvider
                                                                    .BACKGROUND
                                                        },
                                                        LinearLayout.LayoutParams(dp(28), dp(28))
                                                            .apply { marginEnd = dp(10) },
                                                    )
                                                    addView(
                                                        TextView(activity).apply {
                                                            design.typography(this, 14f, 600)
                                                            setTextColor(ink)
                                                        },
                                                        LinearLayout.LayoutParams(0, -2, 1f),
                                                    )
                                                    addView(
                                                        TextView(activity).apply {
                                                            text =
                                                                this@NativeUi.text(
                                                                    "common.select",
                                                                    "Select",
                                                                )
                                                            design.typography(this, 12f)
                                                            setTextColor(muted)
                                                        },
                                                        LinearLayout.LayoutParams(-2, -2).apply {
                                                            marginStart = dp(8)
                                                        },
                                                    )
                                                }
                                        (row.getChildAt(1) as TextView).text =
                                            user.optString("username") +
                                                if (user.optBoolean("is_federated"))
                                                    " @ ${user.optString("fed_instance_name")}"
                                                else ""
                                        artwork(
                                            row.getChildAt(0) as ImageView,
                                            user.optString(
                                                "avatar_url",
                                                "/user/avatar/${NativeApi.encode(user.optString("username"))}",
                                            ),
                                        )
                                        return row
                                    }
                                }
                            suggestions.adapter = adapter
                            suggestions.setOnItemClickListener { _, _, position, _ ->
                                val user = filtered.getOrNull(position)
                                if (user != null)
                                    mutate(
                                        "POST",
                                        payload =
                                            JSONObject()
                                                .put("username", user.optString("username"))
                                                .apply {
                                                    user
                                                        .optString("profile_key")
                                                        .takeIf { it.isNotBlank() }
                                                        ?.let { put("profile_key", it) }
                                                },
                                    )
                            }
                            body.addView(
                                suggestions,
                                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
                            )
                            input.addTextChangedListener(
                                object : android.text.TextWatcher {
                                    override fun beforeTextChanged(
                                        s: CharSequence?,
                                        start: Int,
                                        count: Int,
                                        after: Int,
                                    ) {}

                                    override fun onTextChanged(
                                        s: CharSequence?,
                                        start: Int,
                                        before: Int,
                                        count: Int,
                                    ) {
                                        val query =
                                            s.toString().trim().lowercase(java.util.Locale.ROOT)
                                        filtered =
                                            available.filter {
                                                "${it.optString("username")} ${it.optString("fed_instance_name")}"
                                                    .lowercase(java.util.Locale.ROOT)
                                                    .contains(query)
                                            }
                                        adapter.notifyDataSetChanged()
                                        suggestions.visibility =
                                            if (filtered.isEmpty()) View.GONE else View.VISIBLE
                                    }

                                    override fun afterTextChanged(s: android.text.Editable?) {}
                                }
                            )
                        }
                        space(20, body)
                        label(
                            "${text("library.current_members", "Current members")} (${members.size})",
                            13,
                            body,
                            muted,
                        )
                        if (members.isEmpty())
                            label(
                                text("library.no_collaborators", "No collaborators yet."),
                                15,
                                body,
                                muted,
                            )
                        for (member in members) {
                            val row =
                                LinearLayout(activity).apply {
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(0, dp(8), 0, dp(8))
                                }
                            body.addView(row)
                            label(
                                    member.optString("username") +
                                        if (member.optBoolean("is_federated"))
                                            " @ ${member.optString("fed_instance_name")}"
                                        else "",
                                    15,
                                    row,
                                )
                                .layoutParams =
                                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) }
                            if (data.optBoolean("is_owner"))
                                button(text("common.remove", "Remove"), row) {
                                        mutate("DELETE", member.get("id").toString())
                                    }
                                    .layoutParams = LinearLayout.LayoutParams(-2, dp(44))
                        }
                    }
                }
            }
            api.main.post { if (dialog.isShowing) refresh() }
        }
    }

    fun toggle(
        title: String,
        checked: Boolean,
        parent: LinearLayout = content,
        leading: Boolean = false,
        changed: (Boolean) -> Unit,
    ): androidx.appcompat.widget.SwitchCompat {
        val modern = theme != "classic"
        val switchWidth = if (theme == "glass") 42 else 50
        val switchHeight = if (theme == "glass") 26 else 30
        val thumbSize = if (theme == "glass") 18 else if (theme == "vitrine") 22 else 20
        val row =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                if (leading && modern) setPadding(0, dp(8), 0, dp(8))
            }
        val control =
            androidx.appcompat.widget.SwitchCompat(activity).apply {
                isChecked = checked
                showText = false
                switchMinWidth = dp(switchWidth)
                setPadding(0, 0, 0, 0)
                contentDescription = title
                background = null
                thumbDrawable =
                    design.shape(Color.WHITE, 999).apply { setSize(dp(thumbSize), dp(thumbSize)) }
                trackDrawable =
                    design.shape(design.strong, 999, true).apply {
                        setSize(dp(switchWidth), dp(switchHeight))
                    }
                trackTintList =
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(
                            accent,
                            if (theme == "classic") design.color("surface-3") else design.faint,
                        ),
                    )
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnCheckedChangeListener { _, value -> changed(value) }
            }
        val caption =
            TextView(activity).apply {
                text = title
                design.typography(this, 14f)
                setTextColor(muted)
                setOnClickListener { control.performClick() }
                design.lines(this, if (modern) 1.45f else 1.4f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        if (leading) {
            row.addView(
                control,
                LinearLayout.LayoutParams(dp(switchWidth), dp(switchHeight)).apply {
                    marginEnd = dp(if (modern) 12 else 10)
                },
            )
            row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                control,
                LinearLayout.LayoutParams(dp(switchWidth), dp(switchHeight)).apply {
                    marginStart = dp(12)
                },
            )
        }
        row.isFocusable = true
        row.contentDescription = title
        row.setOnClickListener { control.performClick() }
        control.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setAccessibilityDelegate(
            row,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = control.isChecked
                }
            },
        )
        followAccent(control) { color ->
            control.trackTintList =
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        color,
                        if (theme == "classic") design.color("surface-3") else design.faint,
                    ),
                )
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return control
    }

    fun search(query: String? = null) {
        if (query != null) {
            searchQuery = query.take(200)
            searchCollections = false
            searchSource = "all"
        }
        destination = "search"
        screen(
            text("menu.search", "Search"),
            { home() },
            nav = "search",
            key = "search",
            restore = { search() },
        )
        val stamp = generation
        space(12)
        val pill =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background =
                    design.shape(
                        if (theme == "classic") Color.WHITE else design.surface,
                        if (theme == "classic") 6 else 999,
                        theme != "classic",
                    )
            }
        content.addView(pill, LinearLayout.LayoutParams(-1, dp(52)))
        val searchInk = if (theme == "classic") 0xff171717.toInt() else ink
        pill.addView(
            ImageView(activity).apply { setImageDrawable(NativeIcon("search", searchInk, theme)) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) },
        )
        val input =
            EditText(activity).apply {
                isSaveEnabled = false
                hint = text("search.placeholder", "Artists, songs, or playlists")
                design.typography(this, 16f, 600)
                setTextColor(searchInk)
                setHintTextColor(if (theme == "classic") 0xff555555.toInt() else muted)
                setText(searchQuery)
                isSingleLine = true
                background = null
                setPadding(0, 0, 0, 0)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
                contentDescription = text("menu.search", "Search")
            }
        pill.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        space(20)
        val sources = listOf("all", "spotify", "youtube", "lastfm", "musicbrainz", "local", "users")
        val names = listOf("All", "Spotify", "YouTube", "Last.fm", "MusicBrainz", "Local", "Users")
        lateinit var refresh: () -> Unit
        var strip: LinearLayout? = null
        var collectionToggle: androidx.appcompat.widget.SwitchCompat? = null
        var updatingSource = false
        fun selectSource(index: Int, request: Boolean = true) {
            searchSource = sources[index]
            if (searchSource !in listOf("all", "spotify", "youtube") && searchCollections) {
                searchCollections = false
                updatingSource = true
                collectionToggle?.isChecked = false
                updatingSource = false
            }
            strip?.let { bar ->
                for (i in 0 until bar.childCount) {
                    val cell = bar.getChildAt(i) as FrameLayout
                    cell.isSelected = i == index
                    val label = cell.getChildAt(0) as TextView
                    label.setTextColor(ink)
                    label.background =
                        design.touch(if (i == index) design.strong else design.wash, 999)
                    (label.layoutParams as FrameLayout.LayoutParams).height = dp(40)
                }
            }
            if (request) refresh()
        }
        val sourceStrip =
            chipBar(
                names.mapIndexed { i, title -> title to { selectSource(i) } },
                sources.indexOf(searchSource),
            )
        strip = sourceStrip
        (sourceStrip.parent as View).layoutParams.height = dp(48)
        selectSource(sources.indexOf(searchSource).coerceAtLeast(0), false)
        space(28)
        collectionToggle =
            toggle(
                text("search.collections", "Search albums and playlists (Spotify / YouTube)"),
                searchCollections,
                leading = true,
            ) { value ->
                searchCollections = value
                if (!updatingSource) {
                    if (value && searchSource !in listOf("all", "spotify", "youtube"))
                        selectSource(0, false)
                    refresh()
                }
            }
        space(20)
        val stage = FrameLayout(activity)
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val progress =
            ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
                visibility = View.GONE
            }
        stage.addView(results, FrameLayout.LayoutParams(-1, -2))
        stage.addView(progress, FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP))
        content.addView(stage)
        var serial = 0
        fun browse() {
            results.removeAllViews()
            space(26, results)
            label(text("search.browse_all", "Browse all"), 22, results).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, dp(18))
            }
            val tiles =
                listOf(
                    Triple(
                        text("search.made_for_you", "Made for you"),
                        0xff7e2da6.toInt(),
                        "sparkles",
                    ) to { discover() },
                    Triple(text("tabs.radios", "Radios"), 0xff087d70.toInt(), "radio") to
                        {
                            tools.radios()
                        },
                    Triple(text("library.songs", "Songs"), 0xffaf2d50.toInt(), "music-2") to
                        {
                            collections("songs")
                        },
                    Triple(text("library.albums", "Albums"), 0xffa34921.toInt(), "disc-3") to
                        {
                            collections("albums")
                        },
                    Triple(
                        text("search.community_playlists", "Community playlists"),
                        0xff285b9f.toInt(),
                        "users",
                    ) to { publicCollections() },
                    Triple(text("tabs.party", "Party"), 0xff7049a7.toInt(), "audio-lines") to
                        {
                            parties()
                        },
                )
            for (pair in tiles.chunked(2)) {
                val row = LinearLayout(activity)
                results.addView(
                    row,
                    LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(12) },
                )
                for ((i, tile) in pair.withIndex()) {
                    val card =
                        FrameLayout(activity).apply {
                            background =
                                if (theme == "classic") design.touch(tile.first.second, 8)
                                else
                                    GradientDrawable(
                                            GradientDrawable.Orientation.TL_BR,
                                            intArrayOf(design.strong, design.wash),
                                        )
                                        .apply {
                                            cornerRadius = dp(24).toFloat()
                                            setStroke(dp(1), design.border)
                                        }
                            clipToOutline = true
                            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                            isFocusable = true
                            contentDescription = tile.first.first
                            setOnClickListener { tile.second() }
                        }
                    card.addView(
                        ImageView(activity).apply {
                            setImageDrawable(
                                NativeIcon(
                                    tile.first.third,
                                    if (theme == "classic") Color.WHITE else ink,
                                    theme,
                                )
                            )
                            alpha = .45f
                            rotation = 18f
                            translationX = dp(12).toFloat()
                            translationY = dp(12).toFloat()
                        },
                        FrameLayout.LayoutParams(dp(72), dp(72), Gravity.END or Gravity.BOTTOM),
                    )
                    card.addView(
                        TextView(activity).apply {
                            text = tile.first.first
                            design.typography(this, 18.4f, 700)
                            setTextColor(if (theme == "classic") Color.WHITE else ink)
                            setPadding(dp(16), dp(12), dp(16), dp(12))
                            gravity = Gravity.CENTER_VERTICAL
                            maxLines = 2
                        },
                        FrameLayout.LayoutParams(-1, -1),
                    )
                    row.addView(
                        card,
                        LinearLayout.LayoutParams(0, -1, 1f).apply {
                            if (i == 0) marginEnd = dp(12)
                        },
                    )
                    design.press(card, reducedMotion())
                }
            }
        }
        refresh = {
            val q = input.text.toString().trim()
            searchQuery = q
            val ticket = ++serial
            if (q.isBlank() && searchSource != "users") {
                progress.visibility = View.GONE
                results.alpha = 1f
                browse()
            } else if (searchCollections && q.startsWith("https://")) {
                results.removeAllViews()
                button("Open album or playlist", results, true) { tools.resolveCollection(q) }
            } else {
                val path =
                    if (searchSource == "users") "/user/list"
                    else if (searchCollections)
                        "/api/collections/search?q=${NativeApi.encode(q)}&source=$searchSource"
                    else "/api/search?q=${NativeApi.encode(q)}&source=$searchSource"
                progress.visibility = View.VISIBLE
                results.alpha = .65f
                api.execute({ api.request(path) }) { value, error ->
                    if (!alive || stamp != generation || ticket != serial) return@execute
                    progress.visibility = View.GONE
                    results.alpha = 1f
                    if (error != null) {
                        toast(error.message ?: "Search unavailable")
                        return@execute
                    }
                    results.removeAllViews()
                    val items = NativeTrack.items(value)
                    if (searchSource == "users") {
                        for (user in
                            items.filter {
                                (it.optString("username") + " " + it.optString("fed_instance_name"))
                                    .contains(q, true)
                            }) row(
                            user.optString("username"),
                            user.optString("fed_instance_name"),
                            user.optString("avatar_url"),
                            results,
                        ) {
                            tools.profile(user.optString("profile_key", user.optString("username")))
                        }
                    } else if (searchCollections) {
                        for (item in items) row(
                            item.optString("name"),
                            item.optString("artist"),
                            item.optString("thumbnail"),
                            results,
                        ) {
                            tools.resolveCollection(item.optString("url"))
                        }
                    } else tracks(items, parent = results)
                }
            }
        }
        var pending: Runnable? = null
        input.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    pending?.let(api.main::removeCallbacks)
                    pending =
                        Runnable { if (alive && stamp == generation) refresh() }
                            .also { api.main.postDelayed(it, 300) }
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        input.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                pending?.let(api.main::removeCallbacks)
                if (searchCollections && input.text.toString().startsWith("https://"))
                    tools.resolveCollection(input.text.toString())
                else refresh()
                true
            } else false
        }
        refresh()
    }

    fun discover() {
        destination = "discovery"
        load(text("tabs.discover", "Discover"), "/api/discovery/feed?limit=20") { value ->
            overviewTabs("discovery")
            val items = NativeTrack.items(value)
            if (items.isEmpty())
                label(
                    text(
                        "discover.empty",
                        "No discovery items right now. Explore music in Search to build recommendations, then come back.",
                    ),
                    15,
                    color = muted,
                )
            val dismissed =
                library.get("snapshot", "discovery-dismissed")?.optJSONArray("keys") ?: JSONArray()
            val keys = (0 until dismissed.length()).map { dismissed.optString(it) }.toMutableSet()
            for (item in items.filter { NativeTrack.reference(it) !in keys }) {
                val card =
                    LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        background =
                            design.shape(
                                if (theme == "vitrine") Color.TRANSPARENT
                                else if (theme == "glass") design.content else surface,
                                14,
                                theme == "glass",
                            )
                    }
                content.addView(
                    card,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) },
                )
                val cover = FrameLayout(activity)
                card.addView(
                    cover,
                    LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(12) },
                )
                val art =
                    ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = shape(surface, 8)
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                    }
                cover.addView(art, FrameLayout.LayoutParams(-1, -1))
                artwork(art, artworkPath(item))
                cover.addView(
                    NativeIconButton(activity, Color.WHITE, theme).apply {
                        text = "play"
                        contentDescription =
                            text("player.preview", "Preview") + " " + item.optString("title")
                        background = design.touch(0x44000000, 8)
                        setPadding(dp(23), dp(23), dp(23), dp(23))
                        setOnClickListener {
                            if (player.party != null) toast("Leave the party first")
                            else player.play(listOf(item))
                        }
                    },
                    FrameLayout.LayoutParams(-1, -1),
                )
                cover.addView(
                    ImageView(activity).apply {
                        setImageDrawable(
                            NativeIcon(
                                if (NativeTrack.localId(item) > 0) "hard-drive"
                                else if (item.optInt("fed_instance_id") > 0) "globe" else "music-2",
                                0xbbffffff.toInt(),
                                theme,
                            )
                        )
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        background = design.shape(0x44000000, 999)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START).apply {
                        setMargins(dp(3), dp(3), 0, 0)
                    },
                )
                val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
                label(item.optString("title"), 15, copy).apply {
                    maxLines = 2
                    design.typography(this, 15f, 600)
                    design.lines(this, 1.4f)
                }
                label(
                        item.optString("artist") +
                            item.optString("album").let { if (it.isBlank()) "" else " · $it" },
                        13,
                        copy,
                        muted,
                    )
                    .apply {
                        maxLines = 2
                        design.lines(this, 1.4f)
                    }
                val actions = LinearLayout(activity)
                copy.addView(actions)
                iconButton(actions, "x", "Pass") {
                    keys.add(NativeTrack.reference(item))
                    library.put(
                        "snapshot",
                        "discovery-dismissed",
                        JSONObject().put("keys", JSONArray(keys.toList().takeLast(500))),
                    )
                    if (reducedMotion()) content.removeView(card)
                    else
                        card
                            .animate()
                            .alpha(0f)
                            .translationX(-dp(20).toFloat())
                            .setDuration(180)
                            .withEndAction {
                                (card.parent as? android.view.ViewGroup)?.removeView(card)
                            }
                            .start()
                }
                for ((glyph, favorite) in listOf("download" to false, "heart" to true)) iconButton(
                    actions,
                    glyph,
                    if (favorite) "Save to library" else "Download to library",
                ) {
                    request(
                        "/api/download",
                        body =
                            JSONObject()
                                .put("url", item.optString("url", item.optString("id")))
                                .put("title", item.optString("title"))
                                .put("artist", item.optString("artist")),
                        done = {
                            toast("Download queued")
                            if (favorite) (card.parent as? android.view.ViewGroup)?.removeView(card)
                        },
                    )
                }
                for (i in 0 until actions.childCount) actions.getChildAt(i).apply {
                    background =
                        design.touch(
                            if (theme == "classic") design.wash else design.color("square-wash"),
                            999,
                            theme != "classic",
                        )
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
                }
            }
        }
    }

    fun publicCollections() {
        load(text("public.title", "Public Playlists"), "/api/public/playlists") {
            overviewTabs("public")
            sectionHeader(text("public.shared_playlists", "Shared Playlists"))
            for (p in NativeTrack.items(it)) row(
                p.optString("name"),
                "${p.optString("kind", "playlist")} · ${p.optString("owner_username")} · ${p.optInt("track_count")} songs",
                p.optString("thumbnail"),
            ) {
                collection(p.get("id").toString())
            }
        }
    }

    fun community() {
        load("Community", "/user/list") {
            val users = NativeTrack.items(it)
            overviewTabs("community")
            sectionHeader("${text("community.server_members", "Server Members")} (${users.size})")
            val filter = field(text("community.filter_placeholder", "Filter by username..."))
            val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            val cells = mutableListOf<Pair<JSONObject, View>>()
            for (pair in users.chunked(2)) {
                val line = LinearLayout(activity)
                grid.addView(
                    line,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) },
                )
                pair.forEachIndexed { index, u ->
                    val card =
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            setPadding(dp(16), dp(18), dp(16), dp(18))
                            background =
                                design.touch(
                                    if (theme == "classic") design.strong else design.content,
                                    if (theme == "classic") 8 else 20,
                                    true,
                                )
                            isFocusable = true
                            setOnClickListener {
                                tools.profile(u.optString("profile_key", u.optString("username")))
                            }
                        }
                    line.addView(
                        card,
                        LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (index == 0) marginEnd = dp(12)
                        },
                    )
                    val photo =
                        ImageView(activity).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = design.shape(design.wash, 999, true)
                            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                            clipToOutline = true
                        }
                    card.addView(
                        photo,
                        LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(10) },
                    )
                    artwork(photo, u.optString("avatar_url"))
                    label(
                            u.optString("username") +
                                if (u.optBoolean("is_federated"))
                                    " @ ${u.optString("fed_instance_name")}"
                                else "",
                            16,
                            card,
                        )
                        .apply { gravity = Gravity.CENTER }
                    label(
                            "${u.optInt("public_playlists_count")} playlists · ${u.optInt("favorites_count")} favorites · ${u.optInt("total_listens")} listens",
                            13,
                            card,
                            muted,
                        )
                        .apply {
                            gravity = Gravity.CENTER
                            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
                        }
                    cells.add(u to card)
                }
                if (pair.size == 1)
                    line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            }
            filter.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        cells.forEach { (_, card) ->
                            (card.parent as? android.view.ViewGroup)?.removeView(card)
                        }
                        grid.removeAllViews()
                        val visible =
                            cells.filter { (user, _) ->
                                (user.optString("username") +
                                        " " +
                                        user.optString("fed_instance_name"))
                                    .contains(s.toString().trim(), true)
                            }
                        for (pair in visible.chunked(2)) {
                            val line = LinearLayout(activity)
                            grid.addView(
                                line,
                                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) },
                            )
                            pair.forEachIndexed { index, (_, card) ->
                                line.addView(
                                    card,
                                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                                        if (index == 0) marginEnd = dp(12)
                                    },
                                )
                            }
                            if (pair.size == 1)
                                line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                        }
                    }

                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
            )
        }
    }

    fun overviewTabs(selected: String) {
        browseTabs(selected)
        if (theme == "vitrine") {
            space(24)
            content.addView(
                View(activity).apply { setBackgroundColor(design.border) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
        }
        space(24)
    }

    private fun partyCard(room: JSONObject, parent: LinearLayout = content) {
        val current = room.optJSONObject("current_track")
        val card =
            row(
                room.optString("name"),
                "Hosted by ${room.optString("owner_username")} · ${room.optInt("active_users")}/${room.optInt("max_users")} listening",
                "",
                parent,
                if (activity.resources.configuration.screenWidthDp <= 600) 48 else 56,
            ) {
                joinParty(
                    if (room.optBoolean("is_remote_federated"))
                        "fed_${room.optInt("remote_instance_id")}_${room.get("id")}"
                    else room.get("id").toString()
                )
            }
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        card.background =
            design.touch(
                if (theme == "classic") design.strong else design.content,
                if (theme == "glass") 24 else 12,
                true,
            )
        (card.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
        (card.getChildAt(0) as ImageView).apply {
            setImageDrawable(
                NativeIcon(
                    if (room.optBoolean("is_remote_federated") || room.optBoolean("is_federated"))
                        "globe"
                    else "radio-tower",
                    Color.WHITE,
                    theme,
                )
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background =
                GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(accent, 0xff0b6740.toInt()),
                    )
                    .apply { cornerRadius = dp(12).toFloat() }
        }
        val copy = card.getChildAt(1) as LinearLayout
        design.typography(copy.getChildAt(0) as TextView, 16f, 700)
        current?.let {
            label("${it.optString("title")} — ${it.optString("artist")}", 13, copy).apply {
                maxLines = 1
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(3)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        val status = room.optString("playback_status", "paused")
        if (activity.resources.configuration.screenWidthDp > 600)
            label(
                    status.uppercase(),
                    11,
                    card,
                    if (status == "playing") 0xffd9ffea.toInt() else muted,
                )
                .apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background =
                        design.shape(
                            if (status == "playing") 0xff17643d.toInt() else design.wash,
                            999,
                        )
                }
    }

    fun parties() {
        load("Party rooms", "/api/party/rooms") { value ->
            overviewTabs("party")
            button(text("party.create_room", "Create Room"), primary = true) { tools.createParty() }
            sectionHeader(text("party.live_rooms", "Live Listening Rooms"))
            for (r in NativeTrack.items((value as JSONObject).optJSONArray("rooms"))) {
                partyCard(r)
            }
        }
    }

    fun joinParty(key: String) {
        player.party?.close()
        val party = NativeParty(app, key)
        party.onError = ::toast
        party.onChanged = { if (alive) tools.partyDetails(party, false) }
        party.join { error ->
            if (error != null) toast(error.message ?: "Could not join party")
            else tools.partyDetails(party, true)
        }
    }

    fun download(tracks: List<JSONObject>, single: Boolean = true) {
        val local = tracks.filter { NativeTrack.localId(it) > 0 }
        if (local.isEmpty()) {
            toast("Only local tracks can be saved on this device")
            return
        }
        val scope = library.scope()
        api.execute({
            synchronized(library) {
                val db = library.writableDatabase
                db.beginTransaction()
                try {
                    for (track in local) {
                        require(scope == library.scope())
                        library.put(
                            "download",
                            NativeTrack.reference(track),
                            JSONObject(track.toString())
                                .put("status", "pending")
                                .put(
                                    "is_single",
                                    single ||
                                        track.optBoolean("is_single") ||
                                        library
                                            .get("track", NativeTrack.reference(track), scope)
                                            ?.optBoolean("is_single") == true ||
                                        library
                                            .get("download", NativeTrack.reference(track), scope)
                                            ?.optBoolean("is_single") == true,
                                ),
                            scope,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }) { _, error ->
            if (error != null) {
                if (alive) toast("Could not queue device downloads")
                return@execute
            }
            if (scope != runCatching { library.scope() }.getOrNull()) return@execute
            try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, NativeDownloadService::class.java),
                )
                if (alive) toast("${local.size} device downloads queued")
            } catch (_: RuntimeException) {
                if (alive)
                    toast(
                        "Downloads are saved in the queue. Keep Aoede open and retry from Storage."
                    )
            }
        }
    }

    fun offlineDownloads(kind: String = "all", query: String = "") {
        screen(
            text("offline.title", "Device downloads"),
            { if (offline) login() else collections() },
            nav = "library",
            key = "offline",
            restore = { offlineDownloads(kind, query) },
        )
        val saved = runCatching { library.list("track") }.getOrDefault(emptyList())
        val collections = runCatching { library.list("collection") }.getOrDefault(emptyList())
        val covered =
            collections.flatMap { NativeTrack.items(it).map(NativeTrack::reference) }.toSet()
        val singles =
            saved.filter {
                if (it.has("is_single")) it.optBoolean("is_single")
                else NativeTrack.reference(it) !in covered
            }
        val selected =
            if (kind in listOf("playlist", "album", "playlists")) "playlists"
            else if (kind in listOf("song", "singles")) "singles" else "all"
        label(text("offline.title", "Offline Downloads"), 26).apply {
            design.typography(this, 25.6f, 700)
            letterSpacing = -.04f
            (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 0
                bottomMargin = dp(6)
            }
        }
        label(
            "${saved.size} tracks · ${collections.size} collections · ${String.format(java.util.Locale.US, "%.1f", saved.sumOf { it.optLong("offline_size") } / (1024.0 * 1024))} MB on device",
            14,
            color = muted,
        )
        if (saved.isNotEmpty()) {
            val actions = LinearLayout(activity)
            content.addView(
                actions,
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(16) },
            )
            for ((caption, shuffle) in
                listOf(
                    text("player.play_all", "Play All") to false,
                    text("player.shuffle", "Shuffle") to true,
                )) button(caption, actions, !shuffle) {
                    if (player.party == null) player.play(if (shuffle) saved.shuffled() else saved)
                    else toast("Leave the party first")
                }
                .layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
        }
        chipBar(
            listOf(
                    "${text("offline.all_tracks", "All Tracks")} (${saved.size})",
                    "${text("offline.collections", "Playlists & albums")} (${collections.size})",
                    "${text("offline.singles", "Individual Songs")} (${singles.size})",
                )
                .mapIndexed { i, title ->
                    title to { offlineDownloads(listOf("all", "playlists", "singles")[i], query) }
                },
            listOf("all", "playlists", "singles").indexOf(selected),
            top = 20,
            bottom = 16,
        )
        val search =
            if (saved.isNotEmpty() || collections.isNotEmpty())
                field(
                    text("offline.search_placeholder", "Search downloaded tracks or playlists..."),
                    value = query,
                )
            else null
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        fun render(query: String) {
            results.removeAllViews()
            if (selected != "singles" && collections.isNotEmpty()) {
                sectionHeader(text("offline.collections", "Playlists & albums"), results)
                for (collection in
                    collections.filter { it.optString("name").contains(query, true) }) {
                    val id = collection.get("id").toString()
                    val items =
                        NativeTrack.items(collection).filter {
                            library.audio(NativeTrack.reference(it)) != null
                        }
                    val row =
                        row(
                            collection.optString("name"),
                            "${items.size} songs · Available offline",
                            collection.optString("thumbnail"),
                            results,
                            48,
                        ) {
                            offlineCollection(id)
                        }
                    row.background =
                        design.touch(
                            if (theme == "classic") design.strong else design.content,
                            12,
                            true,
                        )
                    row.setPadding(dp(10), dp(10), dp(10), dp(10))
                    (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
                    iconButton(row, "play", text("player.play", "Play")) {
                        if (player.party == null) player.play(items)
                        else toast("Leave the party first")
                    }
                    iconButton(row, "trash-2", text("offline.remove_copy", "Remove offline copy")) {
                            confirm("Remove offline copy", collection.optString("name")) {
                                library.remove("collection", id)
                                offlineDownloads(selected, query)
                            }
                        }
                        .color(0xffff6479.toInt())
                }
            }
            if (selected != "playlists") {
                val rows =
                    (if (selected == "singles") singles else saved).filter {
                        (it.optString("title") + " " + it.optString("artist")).contains(query, true)
                    }
                if (rows.isNotEmpty()) {
                    sectionHeader(
                        if (selected == "singles") "Individual / Standalone Songs"
                        else if (collections.isEmpty()) "Downloaded Tracks"
                        else "All Downloaded Tracks",
                        results,
                    )
                    tracks(rows, parent = results)
                }
            }
            if (saved.isEmpty() && collections.isEmpty())
                empty(
                    text(
                            "offline.empty",
                            "No offline downloads yet.\nUse the 'Make Available Offline' option on any track or 'Download for Offline' on playlists while online.",
                        )
                        .replace("<br>", "\n"),
                    parent = results,
                )
            else if (selected == "playlists" && collections.isEmpty())
                empty(
                    text(
                        "offline.no_collections",
                        "No collections downloaded. Open a playlist or album online and choose Download.",
                    ),
                    parent = results,
                )
            else if (selected == "singles" && singles.isEmpty())
                empty(
                    "No standalone single tracks downloaded yet.\nUse \"Make Available Offline\" from any track's options menu.",
                    "music-2",
                    results,
                )
        }
        render(query)
        search?.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    render(s.toString().trim().take(200))
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        space(24)
        content.addView(
            View(activity).apply { setBackgroundColor(design.border) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
        val tools =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        button(
                "${text("offline.device_storage", "Device Storage")} · ${String.format(java.util.Locale.US, "%.1f", saved.sumOf { it.optLong("offline_size") } / (1024.0 * 1024))} MB"
            ) {
                tools.visibility = if (tools.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            .apply {
                background = design.touch(Color.TRANSPARENT, 4)
                setTextColor(muted)
                design.typography(this, 14f)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) }
                setCompoundDrawablesRelative(
                    NativeIcon("hard-drive", muted, theme).apply {
                        setBounds(0, 0, dp(18), dp(18))
                    },
                    null,
                    NativeIcon("chevron-down", muted, theme).apply {
                        setBounds(0, 0, dp(16), dp(16))
                    },
                    null,
                )
                compoundDrawablePadding = dp(10)
            }
        content.addView(tools)
        label(
            text(
                "help.offline.desc",
                "Tracks are stored in your browser/app private database for offline playback.",
            ),
            14,
            tools,
            muted,
        )
        button(text("offline.check_readiness", "Check Readiness"), tools) { this.tools.storage() }
        button("Create offline playlist or album", tools) { createOfflineCollection() }
    }

    fun empty(message: String, glyph: String = "", parent: LinearLayout = content) {
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(260)
                setPadding(dp(20), dp(24), dp(20), dp(24))
                if (theme != "classic") background = design.shape(design.content, 24, true)
            }
        parent.addView(body, LinearLayout.LayoutParams(-1, -2))
        if (glyph.isNotBlank())
            body.addView(
                ImageView(activity).apply {
                    setImageDrawable(NativeIcon(glyph, design.faint, theme))
                },
                LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(16) },
            )
        label(message, 15, body, muted).apply {
            gravity = Gravity.CENTER
            design.lines(this, 1.5f)
        }
    }

    private fun createOfflineCollection() {
        choose("New device collection", listOf("Playlist", "Album")) { kind ->
            prompt("Collection name") { name ->
                val id = "native-" + java.util.UUID.randomUUID()
                val value =
                    JSONObject()
                        .put("id", id)
                        .put("name", name.take(100))
                        .put("kind", if (kind == 1) "album" else "playlist")
                        .put("tracks", JSONArray())
                        .put("is_owner", true)
                        .put("is_editable", true)
                library.put("collection", id, value)
                app.nativeSync.enqueue(
                    "create_playlist",
                    JSONObject()
                        .put("temp_id", id)
                        .put("name", value.getString("name"))
                        .put("kind", value.getString("kind")),
                )
                offlineCollection(id)
            }
        }
    }

    private fun offlineCollection(id: String) {
        val value =
            library.get("collection", id)
                ?: run {
                    toast("Collection unavailable offline")
                    return
                }
        screen(
            value.optString("name"),
            { offlineDownloads() },
            nav = "library",
            key = "offline:$id",
            restore = { offlineCollection(id) },
        )
        val available =
            NativeTrack.items(value).filter { library.audio(NativeTrack.reference(it)) != null }
        val editable = value.optBoolean("is_editable", value.optBoolean("is_owner"))
        val copy = JSONObject(value.toString()).put("tracks", JSONArray(available))
        if (copy.optString("owner_username").isBlank()) copy.put("owner_username", api.username)
        renderCollection(copy, null) {
            val options =
                if (editable) listOf("Add a downloaded track", "Rename", "Remove offline copy")
                else listOf("Remove offline copy")
            choose(
                value.optString("name"),
                options,
                if (editable) listOf("list-plus", "pencil", "trash-2") else listOf("trash-2"),
            ) { selected ->
                when (if (editable) selected else 2) {
                    0 -> {
                        val saved = library.list("track")
                        choose("Choose recording", saved.map { it.optString("title") }) { i ->
                            val track = saved[i]
                            val all = NativeTrack.items(value).toMutableList()
                            all.add(NativeTrack.portable(track))
                            library.put("collection", id, value.put("tracks", JSONArray(all)))
                            app.nativeSync.enqueue(
                                "add_to_playlist",
                                JSONObject()
                                    .put("playlistId", id)
                                    .put("trackId", NativeTrack.reference(track)),
                            )
                            offlineCollection(id)
                        }
                    }
                    1 -> {
                        prompt("Collection name", initial = value.optString("name")) { name ->
                            library.put("collection", id, value.put("name", name.take(100)))
                            app.nativeSync.enqueue(
                                "edit_playlist",
                                JSONObject().put("playlistId", id).put("name", name.take(100)),
                            )
                            offlineCollection(id)
                        }
                    }
                    2 -> {
                        confirm(
                            "Remove offline copy",
                            "Downloaded audio remains on this device. This does not delete the server collection.",
                        ) {
                            library.remove("collection", id)
                            offlineDownloads()
                        }
                    }
                }
            }
        }
    }

    private fun applyDockInsets() {
        if (!::navigation.isInitialized || !::shell.isInitialized || shell.parent !== root) return
        val floating = theme == "glass"
        val vertical = dp(if (floating) 7 else if (theme == "vitrine") 4 else 0)
        navigation.setPadding(
            navigation.paddingLeft,
            vertical,
            navigation.paddingRight,
            vertical + if (floating) 0 else safeBars.bottom,
        )
        (navigation.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(60) + if (floating) 0 else safeBars.bottom
            bottomMargin = if (floating) maxOf(dp(8), safeBars.bottom) else 0
            navigation.layoutParams = this
        }
        content.setPadding(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            dp(if (player.current == null) 84 else 84 + design.miniHeight + 8) + safeBars.bottom,
        )
    }

    private fun renderMini() {
        if (
            !::mini.isInitialized ||
                fullscreen != null ||
                !::shell.isInitialized ||
                shell.parent !== root
        )
            return
        val bitmap =
            app.mediaController.metadata.getBitmap(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART
            )
        val signature =
            "${player.current?.let(NativeTrack::reference)}:${player.playing}:${player.failure}:${System.identityHashCode(bitmap)}"
        if (signature == miniSignature) return
        miniSignature = signature
        refreshArtworkAccent(bitmap)
        mini.removeAllViews()
        content.setPadding(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            dp(if (player.current == null) 84 else 84 + design.miniHeight + 8) + safeBars.bottom,
        )
        val t = player.current ?: return
        val r =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background =
                    if (theme == "classic") shape(0xff292929.toInt(), design.miniRadius)
                    else if (reduced()) shape(surface, design.miniRadius)
                    else
                        NativeBackdrop(
                            shell,
                            this,
                            if (theme == "vitrine") design.color("square-film-strong") else surface,
                            dp(design.miniRadius).toFloat(),
                            design.border,
                            if (theme == "glass") design.color("square-glint")
                            else Color.TRANSPARENT,
                            blurDp = design.blurDp,
                            saturation = design.saturation,
                        )
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { showPlayer() }
            }
        val art = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        art.background = shape(design.wash, if (theme == "vitrine") 6 else 4)
        art.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        art.clipToOutline = true
        val miniArt = if (theme == "vitrine") 44 else 40
        r.addView(
            art,
            LinearLayout.LayoutParams(dp(miniArt), dp(miniArt)).apply { marginEnd = dp(10) },
        )
        if (bitmap != null) art.setImageBitmap(bitmap) else artwork(art, artworkPath(t))
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        r.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        label(t.optString("title"), 14, copy).maxLines = 1
        label(player.failure ?: t.optString("artist"), 12, copy, muted).maxLines = 1
        iconButton(r, if (player.playing) "Ⅱ" else "▶", if (player.playing) "Pause" else "Play") {
            if (player.playing) app.mediaController.pauseLocal()
            else app.mediaController.command("play")
        }
        iconButton(r, "›|", "Next") { app.mediaController.command("nexttrack") }
        mini.addView(
            r,
            LinearLayout.LayoutParams(-1, dp(design.miniHeight)).apply {
                setMargins(dp(8), 0, dp(8), dp(8))
            },
        )
        trackGestures(r, false)
    }

    fun addCurrentToCollection() {
        player.current?.let(::addTrackToCollection)
    }

    private fun addTrackToCollection(track: JSONObject) {
        sheet(text("player.add_to_playlist", "Add to Playlist")) { body, dialog ->
            label(text("common.loading", "Loading…"), 14, body, muted)
            api.execute({
                JSONObject()
                    .put("collections", JSONArray(NativeTrack.items(api.request("/api/playlists"))))
                    .put(
                        "membership",
                        if (NativeTrack.localId(track) > 0)
                            runCatching {
                                    api.request(
                                        "/api/tracks/${NativeTrack.localId(track)}/playlists"
                                    )
                                }
                                .getOrNull()
                        else null,
                    )
            }) { value, error ->
                if (!dialog.isShowing) return@execute
                body.removeAllViews()
                if (error != null) {
                    label(error.message ?: "Collections unavailable", 14, body, muted)
                    return@execute
                }
                val data = value as JSONObject
                val collections =
                    NativeTrack.items(data.optJSONArray("collections")).filter {
                        it.optBoolean("is_editable", true)
                    }
                val membership =
                    data.optJSONObject("membership")?.optJSONArray("playlist_ids") ?: JSONArray()
                val existing =
                    (0 until membership.length()).map { membership.get(it).toString() }.toSet()
                val search = field(text("library.find_playlist", "Find a playlist"), body)
                val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                body.addView(list)
                fun render(query: String) {
                    list.removeAllViews()
                    val filtered = collections.filter { it.optString("name").contains(query, true) }
                    if (filtered.isEmpty())
                        label(
                            if (collections.isEmpty())
                                "No editable playlists available. Create one below!"
                            else text("common.no_results", "No results"),
                            14,
                            list,
                            muted,
                        )
                    for (collection in filtered) {
                        val id = collection.get("id").toString()
                        val included = id in existing
                        val row =
                            row(
                                collection.optString("name"),
                                "${collection.optInt("track_count")} tracks",
                                collection.optString("thumbnail"),
                                list,
                                44,
                            ) {
                                if (!included)
                                    request(
                                        NativeTrack.collectionPath(id) + "/add",
                                        body =
                                            JSONObject()
                                                .put("track_id", NativeTrack.reference(track)),
                                        done = {
                                            dialog.dismiss()
                                            toast("Track added")
                                        },
                                    )
                            }
                        if (included) row.isClickable = false
                        iconButton(
                                row,
                                if (included) "check" else "plus",
                                if (included) "Already in playlist" else "Add to playlist",
                            ) {
                                if (!included) row.performClick()
                            }
                            .apply {
                                if (included) {
                                    color(accent)
                                    isEnabled = false
                                }
                            }
                    }
                }
                search.addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            render(s.toString().trim())
                        }

                        override fun afterTextChanged(s: android.text.Editable?) {}
                    }
                )
                render("")
                button(text("library.new_playlist", "New Playlist"), body, true) {
                    dialog.dismiss()
                    createCollection(track)
                }
            }
        }
    }

    val playerInsets
        get() =
            android.graphics.Rect(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                maxOf(root.paddingBottom, safeBars.bottom),
            )

    private fun placePlayer(panel: NativePlayerView) {
        val safe = playerInsets
        val params = panel.layoutParams as FrameLayout.LayoutParams
        if (
            params.leftMargin != -root.paddingLeft ||
                params.topMargin != -root.paddingTop ||
                params.rightMargin != -root.paddingRight ||
                params.bottomMargin != -root.paddingBottom
        ) {
            params.setMargins(
                -root.paddingLeft,
                -root.paddingTop,
                -root.paddingRight,
                -root.paddingBottom,
            )
            panel.layoutParams = params
        }
        panel.applyInsets(safe)
    }

    private fun hideKeyboard() {
        (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
        activity.currentFocus?.clearFocus()
    }

    fun showPlayer(mode: String = "cover") {
        if (connecting) {
            requestedPlayerMode = mode
            return
        }
        if (player.current == null) {
            toast("Choose a track first")
            return
        }
        (fullscreen as? NativePlayerView)?.let {
            it.setMode(mode)
            return
        }
        fullscreen?.let(root::removeView)
        hideKeyboard()
        val panel = NativePlayerView(this)
        fullscreen = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        placePlayer(panel)
        dock.visibility = View.INVISIBLE
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        chromeHeader.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        activity.applyAppearance(true)
        updateFull = { if (player.current == null) hidePlayer() else panel.render() }
        panel.setMode(mode)
        if (!reducedMotion()) {
            panel.translationY = root.height.toFloat()
            panel.scaleX = .97f
            panel.scaleY = .97f
            panel.alpha = .7f
            panel
                .animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(380)
                .setInterpolator(android.view.animation.PathInterpolator(.18f, .9f, .22f, 1f))
                .start()
        }
    }

    private fun placeStory(panel: NativeWrappedStory) {
        val safe = playerInsets
        (panel.layoutParams as FrameLayout.LayoutParams).apply {
            setMargins(-root.paddingLeft, -root.paddingTop, -root.paddingRight, -root.paddingBottom)
            panel.layoutParams = this
        }
        panel.setPadding(safe.left, safe.top, safe.right, safe.bottom)
    }

    fun showWrapped(data: JSONObject, progress: android.os.Bundle? = null) {
        if (player.party != null) {
            toast("Leave the party before watching Wrapped")
            return
        }
        hideKeyboard()
        fullscreen?.let(root::removeView)
        val panel = NativeWrappedStory(this, data, progress)
        fullscreen = panel
        updateFull = null
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        placeStory(panel)
        dock.visibility = View.INVISIBLE
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        chromeHeader.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        activity.applyAppearance(true)
    }

    fun hidePlayer() {
        val panel = fullscreen ?: return
        fullscreen = null
        updateFull = null
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        chromeHeader.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        activity.applyAppearance(!design.light)
        dock.visibility = if (keyboardOpen) View.GONE else View.VISIBLE
        renderMini()
        val remove = {
            root.removeView(panel)
            if (fullscreen == null) dock.visibility = if (keyboardOpen) View.GONE else View.VISIBLE
            renderMini()
        }
        if (!reducedMotion() && panel.height > 0)
            panel
                .animate()
                .translationY(panel.height.toFloat())
                .scaleX(.94f)
                .scaleY(.94f)
                .alpha(.3f)
                .setDuration(340)
                .setInterpolator(android.view.animation.PathInterpolator(.3f, 0f, .2f, 1f))
                .withEndAction { remove() }
                .start()
        else remove()
    }

    fun showQueue() {
        showPlayer("queue")
    }

    fun reduced() =
        activity.getSharedPreferences(MainActivity.PREFS, 0).getBoolean("reduce_effects", false)

    fun reducedMotion() =
        reduced() ||
            android.provider.Settings.Global.getFloat(
                activity.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f

    @Suppress("ClickableViewAccessibility")
    fun trackGestures(view: View, artwork: Boolean) {
        var x = 0f
        var y = 0f
        var dragging = false
        var cancelled = false
        var started = 0L
        var axis = 0
        var dragPanel: NativePlayerView? = null
        fun settle() {
            val duration = if (reducedMotion()) 0L else 320L
            view
                .animate()
                .translationX(0f)
                .translationY(0f)
                .rotation(0f)
                .setDuration(duration)
                .setInterpolator(android.view.animation.PathInterpolator(.18f, .9f, .22f, 1f))
                .start()
            dragPanel?.let { panel ->
                panel
                    .animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(android.view.animation.PathInterpolator(.18f, .9f, .22f, 1f))
                    .withEndAction { if (fullscreen === panel) dock.visibility = View.INVISIBLE }
                    .start()
            }
        }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    x = event.rawX
                    y = event.rawY
                    started = event.eventTime
                    dragging = false
                    axis = 0
                    dragPanel = if (artwork) fullscreen as? NativePlayerView else null
                    cancelled = event.pointerCount != 1
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - x
                    val dy = event.rawY - y
                    if (event.pointerCount != 1 || cancelled) {
                        cancelled = true
                        settle()
                        false
                    } else {
                        if (axis == 0) {
                            if (
                                kotlin.math.abs(dx) > dp(12) &&
                                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5
                            )
                                axis = 1
                            else if (artwork && dy > dp(16) && dy > kotlin.math.abs(dx) * 1.5)
                                axis = 2
                        }
                        dragging = axis != 0
                        if (dragging) target.parent?.requestDisallowInterceptTouchEvent(true)
                        if (dragging && !reducedMotion()) {
                            if (axis == 1) {
                                target.translationX = dx
                                if (target is ImageView)
                                    target.rotation =
                                        (dx / target.width.coerceAtLeast(1) * 2).coerceIn(-3f, 3f)
                            } else
                                dragPanel?.let { panel ->
                                    panel.animate().cancel()
                                    val progress =
                                        (dy / panel.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    panel.translationY = dy.coerceAtLeast(0f)
                                    panel.scaleX = 1f - progress * .06f
                                    panel.scaleY = panel.scaleX
                                    panel.alpha = 1f - progress * .15f
                                    dock.visibility = if (keyboardOpen) View.GONE else View.VISIBLE
                                }
                        }
                        dragging
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - x
                    val dy = event.rawY - y
                    if (cancelled || event.eventTime - started > 1500) {
                        settle()
                        return@setOnTouchListener true
                    }
                    when {
                        artwork && axis == 2 && dy > dp(72) && dy > kotlin.math.abs(dx) * 1.5 ->
                            hidePlayer()
                        axis == 1 &&
                            kotlin.math.abs(dx) > dp(48) &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5 -> {
                            val previous = player.current?.let(NativeTrack::reference)
                            trackSlideDirection = if (dx < 0) 1 else -1
                            app.mediaController.command(
                                if (dx < 0) "nexttrack" else "previousgesture"
                            )
                            if (
                                previous == player.current?.let(NativeTrack::reference) ||
                                    target !is ImageView
                            )
                                settle()
                        }
                        else -> {
                            settle()
                            if (!dragging) target.performClick()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelled = true
                    settle()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelled = true
                    settle()
                    false
                }
                else -> false
            }
        }
    }

    private fun time(ms: Long) = "${ms/60000}:${((ms/1000)%60).toString().padStart(2,'0')}"

    fun popup(
        anchor: View,
        title: String,
        items: List<String>,
        icons: List<String> = emptyList(),
        action: (Int) -> Unit,
    ) {
        val darkPlayer = fullscreen != null
        val foreground = if (darkPlayer) Color.WHITE else ink
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background =
                    design.shape(
                        if (darkPlayer) 0xf529292b.toInt()
                        else if (theme == "classic") 0xff282828.toInt() else design.strong,
                        if (theme == "classic") 8 else 24,
                        true,
                    )
                contentDescription = title
            }
        val window =
            PopupWindow(body, dp(240), -2, true).apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                elevation = dp(10).toFloat()
            }
        popups.add(window)
        window.setOnDismissListener { popups.remove(window) }
        for ((index, label) in items.withIndex()) {
            val row =
                LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                    background = design.touch(Color.TRANSPARENT, if (theme == "classic") 4 else 16)
                    isFocusable = true
                    contentDescription = label
                    setOnClickListener {
                        window.dismiss()
                        action(index)
                    }
                }
            icons.getOrNull(index)?.let { glyph ->
                row.addView(
                    ImageView(activity).apply {
                        setImageDrawable(NativeIcon(glyph, foreground, theme))
                    },
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) },
                )
            }
            row.addView(
                TextView(activity).apply {
                    text = label
                    design.typography(this, 14f, 500)
                    setTextColor(foreground)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            body.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
        }
        window.showAsDropDown(anchor, 0, dp(8), Gravity.END)
        if (!reducedMotion()) {
            body.alpha = 0f
            body.scaleX = .96f
            body.scaleY = .96f
            body.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
    }

    fun menu(anchor: View? = null) {
        val items =
            mutableListOf(
                text("library.create", "Create"),
                text("header.downloads", "Downloads"),
                text("header.delete_responses", "Deletion request responses"),
                "About & updates",
            )
        val icons = mutableListOf("plus", "download-cloud", "messages-square", "info")
        if (api.isAdmin) {
            items.add(text("header.admin_settings", "Administration"))
            icons.add("settings")
        }
        val action: (Int) -> Unit = {
            when (it) {
                0 -> createCollection()
                1 -> tools.downloads()
                2 -> tools.deletionResponses()
                3 -> tools.about()
                4 -> tools.admin()
            }
        }
        if (anchor != null) popup(anchor, "Aoede", items, icons, action)
        else choose("Aoede", items, icons, done = action)
    }

    fun signOut() {
        confirm("Sign out", "Stop playback and sign out of this server?") {
            player.release()
            api.execute({ api.logout() }) { _, error ->
                if (error != null)
                    toast("The server could not revoke this session. Try again when connected.")
                else login()
            }
        }
    }

    fun back(): Boolean {
        if (fullscreen != null) {
            hidePlayer()
            return true
        }
        if (history.isNotEmpty()) {
            val previous = history.removeLast()
            restoringRoute = true
            try {
                previous()
            } finally {
                restoringRoute = false
            }
            return true
        }
        if (backAction != null) {
            val action = backAction
            backAction = null
            action?.invoke()
            return true
        }
        return false
    }

    fun saveState(): android.os.Bundle =
        android.os.Bundle().apply {
            putString("scope", api.scope)
            putString("route", routeKey?.take(4096))
            putString("search", searchQuery.take(200))
            putString("source", searchSource)
            putBoolean("collections", searchCollections)
            putBoolean("metadataAlbums", libraryMetadataAlbums)
            putBundle("library", libraryState)
            putBundle("tools", tools.saveState())
            (fullscreen as? NativePlayerView)?.let { putString("player", it.presentationMode) }
            (fullscreen as? NativeWrappedStory)?.let { putBundle("wrapped", it.saveState()) }
        }

    fun restoreState(state: android.os.Bundle): Boolean {
        if (!api.hasCredentials || state.getString("scope") != api.scope) return false
        val route = state.getString("route").orEmpty().take(4096)
        searchQuery = state.getString("search").orEmpty().take(200)
        searchSource = state.getString("source").orEmpty()
        searchCollections = state.getBoolean("collections")
        libraryMetadataAlbums = state.getBoolean("metadataAlbums")
        state.getBundle("tools")?.let(tools::restoreState)
        val savedLibrary = state.getBundle("library") ?: android.os.Bundle()
        val path = route.substringBefore('?')
        when {
            route == "home" -> home()
            route == "search" -> search()
            route == "offline" -> offlineDownloads()
            route.startsWith("offline:") -> offlineCollection(route.removePrefix("offline:"))
            path == "/api/playlists" || path.startsWith("/api/library/") ->
                collections(
                    savedLibrary.getString("kind", "all")!!,
                    savedLibrary.getString("query", "")!!,
                    savedLibrary.getString("order", "recent")!!,
                    savedLibrary.getBoolean("grid"),
                    savedLibrary.getInt("offset"),
                )
            Regex("/api/playlists/[1-9][0-9]*").matches(path) ->
                collection(path.substringAfterLast('/'))
            Regex("/api/federation/proxy/[1-9][0-9]*/collections/[1-9][0-9]*").matches(path) -> {
                val parts = path.split('/')
                collection("fed_${parts[4]}_${parts[6]}")
            }
            path == "/api/favorites" -> favorites()
            path == "/api/public/playlists" -> publicCollections()
            path == "/user/list" -> community()
            path == "/api/party/rooms" -> parties()
            path.startsWith("/api/party/") && player.party?.path == path ->
                tools.partyDetails(player.party!!, true)
            path.startsWith("/api/party/") -> parties()
            path.startsWith("/api/mixes/") ->
                mix(android.net.Uri.decode(path.substringAfterLast('/')))
            path.startsWith("/api/wrapped/") ->
                NativeWrapped(this)
                    .open(
                        path.substringAfterLast('/').toIntOrNull()
                            ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
                        story = state.getBundle("wrapped") != null,
                        progress = state.getBundle("wrapped"),
                    )
            path.startsWith("/api/discovery") -> discover()
            else -> if (!tools.restorePage(path)) return false
        }
        if (player.current != null)
            state
                .getString("player")
                ?.takeIf { it in listOf("cover", "queue", "lyrics") }
                ?.let(::showPlayer)
        return true
    }

    fun close() {
        history.clear()
        reload = null
        refreshHome = null
        popups.toList().forEach { it.dismiss() }
        dialogs.toList().forEach { it.dismiss() }
        api.main.removeCallbacks(dismissToast)
        dismissToast.run()
        accentBindings.clear()
        tools.close()
        alive = false
        generation++
        migration?.close()
        migration = null
        if (player.onChanged === playbackChanged) player.onChanged = null
        if (player.onOpenPlayer === openPlayer) player.onOpenPlayer = null
        if (app.nativeChanged === playbackChanged) app.nativeChanged = null
    }

    fun pause() {
        (fullscreen as? NativeWrappedStory)?.foreground(false)
        tools.pause()
    }

    fun resume() {
        (fullscreen as? NativeWrappedStory)?.foreground(true)
        tools.resume()
    }

    fun importLegacy() {
        migration?.close()
        migration = NativeMigration(app)
        label("Importing previous downloads. Keep Aoede open…", 14)
        migration!!.start { message, error ->
            if (alive) toast(error?.message ?: message.orEmpty())
        }
    }
}
