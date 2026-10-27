package com.aoede.twa

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
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

    private val root = FrameLayout(activity).apply { id = R.id.main_root }
    private lateinit var shell: LinearLayout
    lateinit var content: LinearLayout
        private set

    private lateinit var scroll: ScrollView
    private lateinit var mini: LinearLayout
    private val history = ArrayDeque<() -> Unit>()
    private var fullscreen: View? = null
    private var updateFull: (() -> Unit)? = null
    private var generation = 0
    private var alive = true
    private var backAction: (() -> Unit)? = null
    private var reload: (() -> Unit)? = null
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

    private var offline = false
    private var migration: NativeMigration? = null
    private var translations = JSONObject()
    private var miniSignature = ""
    private val mutations = mutableSetOf<String>()
    val tools by lazy { NativeTools(this) }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun text(key: String, fallback: String): String = translations.optString(key, fallback)

    private fun palette() {
        theme = api.account?.optString("theme", "classic") ?: "classic"
        val light =
            theme != "classic" &&
                (activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        background = if (light) Color.rgb(249, 249, 252) else Color.rgb(15, 16, 19)
        surface = if (light) Color.rgb(238, 239, 244) else Color.rgb(32, 33, 37)
        ink = if (light) Color.rgb(24, 25, 30) else Color.rgb(248, 248, 250)
        muted = if (light) Color.rgb(103, 106, 117) else Color.rgb(165, 168, 178)
        accent =
            when (theme) {
                "vitrine" -> Color.rgb(250, 45, 82)
                "glass" -> if (light) Color.rgb(94, 78, 180) else Color.rgb(178, 159, 245)
                else -> Color.rgb(30, 215, 96)
            }
        app.darkAppearance = !light
        activity.applyAppearance(!light)
        translations =
            runCatching {
                    JSONObject(
                        app.assets
                            .open(
                                "native/locales/${api.account?.optString("language","en")?:"en"}.json"
                            )
                            .bufferedReader()
                            .use { it.readText() }
                    )
                }
                .getOrDefault(JSONObject())
    }

    fun create() {
        activity.setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            app.musicIsland.updateSafeInsets(
                insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.tappableElement()
                )
            )
            insets
        }
        player.onChanged = {
            if (alive) {
                renderMini()
                updateFull?.invoke()
            }
        }
        player.onOpenPlayer = { if (alive) showPlayer() }
        app.nativeChanged = {
            if (alive) {
                renderMini()
                updateFull?.invoke()
            }
        }
        if (api.hasCredentials) {
            palette()
            screen("Aoede")
            label("Connecting to your music…", 16)
            api.execute({ api.refreshContext() }) { _, error ->
                if (!alive) return@execute
                if (error == null) {
                    player.restore()
                    player.refreshPreferences()
                    home()
                    app.nativeSync.flush()
                    app.updater.check()
                } else if (error is NativeApi.Failure && error.code == 401) {
                    player.release()
                    api.clearSession()
                    login()
                } else login(error.message)
            }
        } else login()
    }

    fun shape(color: Int, radius: Int = 14) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    fun label(
        value: String,
        size: Int = 15,
        parent: LinearLayout = content,
        color: Int = ink,
    ): TextView =
        TextView(activity).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            setPadding(0, dp(5), 0, dp(5))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun button(
        value: String,
        parent: LinearLayout = content,
        primary: Boolean = false,
        action: () -> Unit,
    ): Button =
        Button(activity).apply {
            text = value
            isAllCaps = false
            setTextColor(
                if (primary && theme == "classic") Color.BLACK
                else if (primary) Color.WHITE else ink
            )
            textSize = 14f
            minHeight = dp(48)
            background = shape(if (primary) accent else surface)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            parent.addView(
                this,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(2)
                },
            )
            setOnClickListener { action() }
        }

    fun field(
        hint: String,
        parent: LinearLayout = content,
        password: Boolean = false,
        value: String = "",
    ): EditText =
        EditText(activity).apply {
            this.hint = hint
            contentDescription = hint
            setText(value)
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(muted)
            isSingleLine = true
            inputType =
                if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
            background = shape(surface, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        }

    fun row(
        title: String,
        subtitle: String = "",
        image: String = "",
        parent: LinearLayout = content,
        action: () -> Unit,
    ): LinearLayout {
        val row =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                minimumHeight = dp(64)
                isFocusable = true
                contentDescription = if (subtitle.isBlank()) title else "$title, $subtitle"
                setOnClickListener { action() }
            }
        if (image.isNotBlank()) {
            val cover =
                ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(surface, 8)
                }
            row.addView(
                cover,
                LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) },
            )
            artwork(cover, image)
        }
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        label(title, 16, copy).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
        }
        if (subtitle.isNotBlank()) label(subtitle, 13, copy, muted).maxLines = 2
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    fun artwork(image: ImageView, path: String) {
        if (path.length > if (path.startsWith("data:image/")) 192 * 1024 else 4096) return
        image.tag = path
        val pixels = image.layoutParams?.width?.takeIf { it > 0 }?.coerceIn(64, 512) ?: 512
        if (path.startsWith("data:image/")) {
            runCatching {
                    val b =
                        android.util.Base64.decode(
                            path.substringAfter(','),
                            android.util.Base64.DEFAULT,
                        )
                    decodeArtwork(b, pixels)
                }
                .getOrNull()
                ?.let(image::setImageBitmap)
            return
        }
        val target =
            if (path.startsWith("/") && !path.startsWith("//")) path
            else if (path.startsWith("https://")) "/api/proxy-image?url=" + NativeApi.encode(path)
            else return
        api.image({
            val c = api.connection(target)
            try {
                require(c.responseCode == 200)
                val bytes = c.inputStream.use { it.readBytesBounded(2 * 1024 * 1024) }
                api.checkResponse(c)
                decodeArtwork(bytes, pixels)
            } finally {
                c.disconnect()
            }
        }) { value, _ ->
            if (alive && image.tag == path && value is android.graphics.Bitmap)
                image.setImageBitmap(value)
        }
    }

    private fun decodeArtwork(bytes: ByteArray, pixels: Int = 512): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample =
            MediaController.artworkSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > pixels) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    fun screen(title: String, back: (() -> Unit)? = null) {
        backAction = back
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        generation++
        palette()
        root.setBackgroundColor(background)
        root.removeAllViews()
        fullscreen = null
        updateFull = null
        shell = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        val header =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(12), dp(4))
            }
        if (back != null) iconButton(header, "‹", text("common.back", "Back")) { back() }
        val heading =
            TextView(activity).apply {
                this.text = title
                textSize = 25f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ink)
                maxLines = 1
            }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(56), 1f))
        heading.gravity = Gravity.CENTER_VERTICAL
        iconButton(header, "•••", text("header.more", "More options")) { menu() }
        shell.addView(header)
        scroll =
            ScrollView(activity).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
            }
        content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(28))
            }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        miniSignature = ""
        mini = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        shell.addView(mini)
        val nav =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = shape(this@NativeUi.background, 0)
            }
        for ((label, action) in
            listOf(
                text("menu.home", "Home") to { home() },
                text("menu.search", "Search") to { search() },
                text("menu.library", "Library") to { collections() },
                text("tabs.discover", "Discover") to { discover() },
            )) {
            val item =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isFocusable = true
                    contentDescription = label
                    setOnClickListener {
                        history.clear()
                        action()
                    }
                }
            val glyph = listOf("home", "search", "library", "discover")[nav.childCount]
            item.addView(
                ImageView(activity).apply {
                    setImageDrawable(NativeIcon(glyph, ink))
                    isClickable = false
                },
                LinearLayout.LayoutParams(dp(22), dp(22)),
            )
            item.addView(
                TextView(activity).apply {
                    this.text = label
                    gravity = Gravity.CENTER
                    textSize = 11f
                    setTextColor(ink)
                },
                LinearLayout.LayoutParams(-1, dp(20)),
            )
            nav.addView(item, LinearLayout.LayoutParams(0, dp(54), 1f))
        }
        shell.addView(nav)
        renderMini()
        if (offline) label("Device library · offline", 13, color = muted)
    }

    fun iconButton(parent: LinearLayout, glyph: String, label: String, action: () -> Unit) =
        NativeIconButton(activity, ink).apply {
            text = glyph
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(Color.TRANSPARENT, 24)
            contentDescription = label
            isFocusable = true
            minimumHeight = dp(48)
            minimumWidth = dp(48)
            parent.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
            setOnClickListener { action() }
        }

    fun load(title: String, path: String, render: (Any) -> Unit) {
        reload = { load(title, path, render) }
        screen(title, if (path == "/api/recommendations") null else ({ home() }))
        val stamp = generation
        label(text("common.loading", "Loading…"), 15, color = muted)
        api.execute({ api.request(path) }) { data, error ->
            if (!alive || stamp != generation) return@execute
            content.removeAllViews()
            if (error != null) {
                label(error.message ?: "Request failed")
                button("Retry") { reload?.invoke() }
                if (error is NativeApi.Failure && error.code == 401) button("Sign in") { login() }
            } else if (data != null)
                try {
                    render(data)
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
                if (done != null) done(data) else if (stamp == generation) reload?.invoke()
            }
        }
    }

    fun toast(message: String) {
        Toast.makeText(activity, message.take(240), Toast.LENGTH_LONG).show()
    }

    fun prompt(
        title: String,
        hint: String = "",
        initial: String = "",
        password: Boolean = false,
        done: (String) -> Unit,
    ) {
        val input =
            EditText(activity).apply {
                setText(initial)
                this.hint = hint
                isSingleLine = true
                if (password)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(text("common.cancel", "Cancel"), null)
            .setPositiveButton(text("common.save", "Save")) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) done(value)
            }
            .show()
    }

    fun confirm(title: String, message: String, done: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(text("common.cancel", "Cancel"), null)
            .setPositiveButton(text("common.confirm", "Confirm")) { _, _ -> done() }
            .show()
    }

    fun choose(title: String, items: List<String>, done: (Int) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, index -> done(index) }
            .show()
    }

    fun login(error: String? = null) {
        history.clear()
        backAction = null
        tools.close()
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        generation++
        palette()
        root.setBackgroundColor(background)
        root.removeAllViews()
        fullscreen = null
        updateFull = null
        val scroller = ScrollView(activity).apply { isFillViewport = true }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28), dp(36), dp(28), dp(36))
            }
        content = body
        body.isFocusableInTouchMode = true
        body.requestFocus()
        scroller.addView(body)
        body.addView(
            ImageView(activity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = "Aoede"
            },
            LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            },
        )
        label(text("login.headline", "Your music. Your space."), 32, body).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        label(text("login.intro", "Sign in and make yourself at home."), 15, body, muted).gravity =
            Gravity.CENTER
        label(api.origin?.host.orEmpty(), 12, body, muted).gravity = Gravity.CENTER
        if (!error.isNullOrBlank()) label(error, 14, body, Color.rgb(230, 65, 82))
        val username = field(text("login.username", "Username"), body)
        val password = field(text("login.password", "Password"), body, true)
        username.filters = arrayOf(android.text.InputFilter.LengthFilter(32))
        password.filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
        username.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val show =
            CheckBox(activity).apply {
                text = text("login.show_password", "Show password")
                setTextColor(ink)
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    val position = password.selectionStart
                    password.transformationMethod =
                        if (checked) null
                        else android.text.method.PasswordTransformationMethod.getInstance()
                    password.setSelection(position.coerceIn(0, password.length()))
                }
            }
        body.addView(show)
        if (Build.VERSION.SDK_INT >= 26) {
            username.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            username.setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        val remember =
            CheckBox(activity).apply {
                text = text("login.remember_me", "Remember me")
                setTextColor(ink)
                isChecked = true
                minHeight = dp(48)
            }
        body.addView(remember)
        val submit = button(text("login.button", "Sign in"), body, true) {}
        submit.setOnClickListener {
            val name = username.text.toString().trim()
            val secret = password.text.toString()
            if (name.length !in 2..32 || secret.isBlank() || secret.length > 1024) {
                toast("Enter your username and password")
                return@setOnClickListener
            }
            submit.isEnabled = false
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
                } else toast(failure.message ?: "Sign in failed")
            }
        }
        button(text("native.configure_server", "Configure server"), body) {
            activity.openServerSettings()
        }
        if (api.account != null)
            button(text("offline.title", "Device downloads"), body) {
                offline = true
                player.restore()
                offlineDownloads()
            }
    }

    fun home() {
        load(text("menu.home", "Home"), "/api/recommendations") { value ->
            label("${text("home.welcome","Welcome back")}, ${api.username}", 26)
                .setTypeface(null, Typeface.BOLD)
            val shortcuts =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            content.addView(shortcuts)
            for ((name, action) in
                listOf(
                    "Party" to { parties() },
                    "Public" to { publicCollections() },
                    "Community" to { community() },
                )) button(name, shortcuts) { action() }
                .apply {
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    textSize = 13f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
                }
            button("Radio & mixes") { tools.radios() }
            button("Wrapped") { tools.wrapped() }
            val sections =
                if (value is JSONArray) value
                else (value as? JSONObject)?.optJSONArray("sections") ?: JSONArray()
            for (section in NativeTrack.items(sections)) {
                label(section.optString("title", "For you"), 22).setTypeface(null, Typeface.BOLD)
                tracks(NativeTrack.items(section.optJSONArray("items")).take(6))
            }
            button(text("menu.library", "Your library")) { collections() }
        }
    }

    private fun trackArtwork(track: JSONObject): String =
        runCatching {
                library
                    .get("track", NativeTrack.reference(track))
                    ?.optString("offline_artwork")
                    .orEmpty()
            }
            .getOrDefault("")
            .ifBlank { track.optString("thumbnail") }

    fun tracks(
        items: List<JSONObject>,
        collectionId: String? = null,
        collection: JSONObject? = null,
    ) {
        if (items.isEmpty()) {
            label(text("library.empty", "No tracks yet"), 15, color = muted)
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
                        trackArtwork(t),
                    ) {
                        if (player.party != null) {
                            toast("Leave the party to start personal playback")
                            return@row
                        }
                        player.play(items, i)
                        renderMini()
                    }
                iconButton(r, "⋮", text("header.more", "Track options")) {
                    trackMenu(t, items, i, collectionId, collection)
                }
            }
            shown = end
            if (shown < items.size) {
                val more = button("Show more (${items.size-shown})") {}
                more.setOnClickListener {
                    content.removeView(more)
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
        val options =
            mutableListOf(
                "Play next",
                "Add to playlist or album",
                "Favorite / unfavorite",
                "Download to device",
                "Download to server library",
                "Add to party",
                "Artist",
                "Request deletion",
            )
        if (collectionId != null && collection?.optBoolean("is_editable") == true)
            options.addAll(listOf("Move up", "Move down", "Remove from collection", "Use as cover"))
        choose(t.optString("title"), options) { choice ->
            when (choice) {
                0 -> player.insertNext(t)
                1 ->
                    load("Add to collection", "/api/playlists") { data ->
                        for (p in
                            NativeTrack.items(data).filter {
                                it.optBoolean("is_editable", true)
                            }) row(p.optString("name")) {
                            request(
                                NativeTrack.collectionPath(p.get("id").toString()) + "/add",
                                body = JSONObject().put("track_id", NativeTrack.reference(t)),
                                done = { toast("Track added") },
                            )
                        }
                    }
                2 -> player.favorite(t) { error -> toast(error?.message ?: "Favorites updated") }
                3 -> download(listOf(t))
                4 ->
                    request(
                        "/api/download",
                        body =
                            JSONObject()
                                .put("url", t.optString("url", t.optString("id")))
                                .put("title", t.optString("title"))
                                .put("artist", t.optString("artist")),
                        done = { toast("Download queued") },
                    )
                5 -> {
                    val party = player.party
                    if (party == null) toast("Join a party first")
                    else party.add(t) { error -> toast(error?.message ?: "Track added to party") }
                }
                6 -> tools.artist(t.optString("artist"))
                7 ->
                    prompt("Why should this track be removed?") { reason ->
                        request(
                            "/api/tracks/${NativeTrack.localId(t)}/delete-request",
                            body = JSONObject().put("reason", reason),
                            done = { toast("Request sent") },
                        )
                    }
                8,
                9 -> {
                    val reordered = items.toMutableList()
                    val other = if (choice == 8) index - 1 else index + 1
                    if (other in reordered.indices) {
                        java.util.Collections.swap(reordered, index, other)
                        val payload = JSONArray()
                        reordered.forEachIndexed { position, item ->
                            payload.put(
                                JSONObject()
                                    .put("track_id", NativeTrack.reference(item))
                                    .put("position", position)
                                    .put(
                                        "item_id",
                                        item.opt("item_id") ?: item.opt("playlist_item_id"),
                                    )
                            )
                        }
                        request(
                            NativeTrack.collectionPath(collectionId!!) + "/reorder",
                            "PATCH",
                            JSONObject()
                                .put("items", payload)
                                .put("revision", collection!!.optInt("revision")),
                            done = { collection(collectionId) },
                        )
                    }
                }
                10 ->
                    confirm("Remove track", "Remove this track from the collection?") {
                        request(
                            NativeTrack.collectionPath(collectionId!!) +
                                "/remove/" +
                                NativeApi.encode(NativeTrack.reference(t)) +
                                "?item_id=" +
                                t.optLong("item_id", t.optLong("playlist_item_id")),
                            "DELETE",
                            done = { collection(collectionId) },
                        )
                    }
                11 ->
                    request(
                        NativeTrack.collectionPath(collectionId!!) + "/cover/track",
                        body = JSONObject().put("track_id", NativeTrack.localId(t)),
                        done = { collection(collectionId) },
                    )
            }
        }
    }

    fun collections() {
        if (offline) {
            offlineDownloads()
            return
        }
        load(text("menu.library", "Your library"), "/api/playlists") { data ->
            button("Create playlist or album", primary = true) {
                choose("New collection", listOf("Playlist", "Album", "Smart playlist")) { kind ->
                    if (kind == 2) tools.smartPlaylist()
                    else
                        prompt("Collection name") { name ->
                            request(
                                "/api/playlists",
                                body =
                                    JSONObject()
                                        .put("name", name)
                                        .put("kind", if (kind == 1) "album" else "playlist")
                                        .put("request_id", java.util.UUID.randomUUID().toString()),
                                done = { p -> collection((p as JSONObject).get("id").toString()) },
                            )
                        }
                }
            }
            button(text("offline.title", "Device downloads")) { offlineDownloads() }
            button(text("menu.liked", "Favorites")) {
                load("Favorites", "/api/favorites") { tracks(NativeTrack.items(it)) }
            }
            button("Songs, artists & genres") { tools.libraryBrowser() }
            for (p in NativeTrack.items(data)) row(
                p.optString("name"),
                "${p.optString("kind","playlist")} · ${p.optInt("track_count")} tracks",
                p.optString("thumbnail"),
            ) {
                collection(p.get("id").toString())
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
            screen(cached.optString("name"), { offlineDownloads() })
            tracks(NativeTrack.items(cached), id, cached)
            return
        }
        load("Collection", NativeTrack.collectionPath(id)) { value ->
            val data = value as JSONObject
            label(data.optString("name"), 28).setTypeface(null, Typeface.BOLD)
            label(data.optString("owner_username"), 14, color = muted)
            val list = NativeTrack.items(data)
            button("Play", primary = true) {
                if (player.party != null) toast("Leave the party first") else player.play(list)
            }
            button("Shuffle") {
                if (player.party == null) {
                    player.play(list.shuffled())
                } else toast("Leave the party first")
            }
            button("Download for offline") {
                val local = list.filter { NativeTrack.localId(it) > 0 }
                library.put("collection", id, data)
                download(local)
            }
            button(text("header.more", "Collection options")) { collectionMenu(id, data) }
            tracks(list, id, data)
        }
    }

    fun collectionMenu(id: String, p: JSONObject) {
        val owner = p.optBoolean("is_owner")
        val options =
            if (owner)
                listOf(
                    "Rename",
                    "Collaborators",
                    "Sharing",
                    "Upload cover",
                    "Reset cover",
                    "Delete",
                )
            else listOf("Copy / sync", "Collaborators")
        choose(p.optString("name"), options) { index ->
            if (owner)
                when (index) {
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
                }
            else if (index == 0)
                request(NativeTrack.collectionPath(id) + "/copy", done = { collections() })
            else collaborators(id)
        }
    }

    fun collaborators(id: String) {
        load("Collaborators", NativeTrack.collectionPath(id) + "/collaborators") { value ->
            val data = value as JSONObject
            if (data.optBoolean("is_owner"))
                button("Invite listener") {
                    load("Choose a listener", "/user/list") { users ->
                        for (u in NativeTrack.items(users)) row(
                            u.optString("username"),
                            u.optString("fed_instance_name"),
                        ) {
                            request(
                                NativeTrack.collectionPath(id) + "/collaborators",
                                body =
                                    JSONObject()
                                        .put("username", u.optString("username"))
                                        .put("profile_key", u.opt("profile_key")),
                                done = { collaborators(id) },
                            )
                        }
                    }
                }
            for (member in NativeTrack.items(data.optJSONArray("members"))) row(
                member.optString("username"),
                member.optString("fed_instance_name"),
            ) {
                if (data.optBoolean("is_owner"))
                    confirm("Remove collaborator", member.optString("username")) {
                        request(
                            NativeTrack.collectionPath(id) + "/collaborators/" + member.get("id"),
                            "DELETE",
                            done = { collaborators(id) },
                        )
                    }
            }
        }
    }

    fun search() {
        screen(text("menu.search", "Search"), { home() })
        val q = field(text("header.search_placeholder", "Songs, artists or a collection URL"))
        val albums =
            Switch(activity).apply {
                text = "Search albums & playlists"
                setTextColor(ink)
                minHeight = dp(48)
            }
        content.addView(albums)
        chooseSource@ run {
            val sources = listOf("all", "local", "spotify", "youtube", "federation")
            var source = "all"
            button("Source: all") { choose("Search source", sources) { source = sources[it] } }
            button("Search", primary = true) {
                val query = q.text.toString().trim()
                if (query.length !in 1..200) {
                    toast("Enter a search (up to 200 characters)")
                    return@button
                }
                if (albums.isChecked && query.startsWith("https://")) {
                    tools.resolveCollection(query)
                    return@button
                }
                val path =
                    if (albums.isChecked)
                        "/api/collections/search?q=${NativeApi.encode(query)}&source=$source"
                    else "/api/search?q=${NativeApi.encode(query)}&source=$source"
                load("Search results", path) { data ->
                    if (albums.isChecked)
                        for (p in NativeTrack.items(data)) row(
                            p.optString("name"),
                            p.optString("artist"),
                            p.optString("thumbnail"),
                        ) {
                            tools.resolveCollection(p.optString("url"))
                        }
                    else tracks(NativeTrack.items(data))
                }
            }
        }
    }

    fun discover() {
        load("Discover", "/api/discovery/feed?limit=40") { tracks(NativeTrack.items(it)) }
    }

    fun publicCollections() {
        load("Public collections", "/api/public/playlists") {
            for (p in NativeTrack.items(it)) row(
                p.optString("name"),
                p.optString("owner_username"),
                p.optString("thumbnail"),
            ) {
                collection(p.get("id").toString())
            }
        }
    }

    fun community() {
        load("Community", "/user/list") {
            for (u in NativeTrack.items(it)) row(
                u.optString("username"),
                u.optString("fed_instance_name"),
                u.optString("avatar_url"),
            ) {
                tools.profile(u.optString("profile_key", u.optString("username")))
            }
        }
    }

    fun parties() {
        load("Party rooms", "/api/party/rooms") { value ->
            button("Create room", primary = true) {
                prompt("Room name") { name ->
                    request(
                        "/api/party/rooms",
                        body = JSONObject().put("name", name).put("is_federated", true),
                        done = { result ->
                            val r = (result as JSONObject).optJSONObject("room") ?: result
                            joinParty((r as JSONObject).get("id").toString())
                        },
                    )
                }
            }
            for (r in NativeTrack.items((value as JSONObject).optJSONArray("rooms"))) {
                val key =
                    if (r.optBoolean("is_remote_federated"))
                        "fed_${r.optInt("remote_instance_id")}_${r.get("id")}"
                    else r.get("id").toString()
                row(r.optString("name"), r.optString("owner_username")) { joinParty(key) }
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

    fun download(tracks: List<JSONObject>) {
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
                            JSONObject(track.toString()).put("status", "pending"),
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

    fun offlineDownloads() {
        screen(
            text("offline.title", "Device downloads"),
            { if (offline) login() else collections() },
        )
        val saved = runCatching { library.list("track") }.getOrDefault(emptyList())
        label("${saved.size} tracks available on this device", 14, color = muted)
        button("Create offline playlist or album") {
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
        for (c in runCatching { library.list("collection") }.getOrDefault(emptyList())) row(
            c.optString("name"),
            c.optString("kind", "playlist"),
        ) {
            offlineCollection(c.get("id").toString())
        }
        tracks(saved)
        button("Storage & migration") { tools.storage() }
    }

    private fun offlineCollection(id: String) {
        val value = library.get("collection", id) ?: return
        screen(value.optString("name"), { offlineDownloads() })
        val available =
            NativeTrack.items(value).filter { library.audio(NativeTrack.reference(it)) != null }
        label("${available.size} tracks downloaded", 14, color = muted)
        if (value.optBoolean("is_owner")) {
            button("Add a downloaded track") {
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
            button("Rename") {
                prompt("Collection name", initial = value.optString("name")) { name ->
                    library.put("collection", id, value.put("name", name.take(100)))
                    app.nativeSync.enqueue(
                        "edit_playlist",
                        JSONObject().put("playlistId", id).put("name", name.take(100)),
                    )
                    offlineCollection(id)
                }
            }
        }
        button("Remove device collection") {
            confirm(
                "Remove device collection",
                "Downloaded audio remains on this device. This does not delete the server collection.",
            ) {
                library.remove("collection", id)
                offlineDownloads()
            }
        }
        tracks(available)
        label(
            "Collection edits synchronize when connected. Review pending changes in Storage.",
            14,
            color = muted,
        )
    }

    private fun renderMini() {
        if (!::mini.isInitialized || fullscreen != null) return
        val bitmap =
            app.mediaController.metadata.getBitmap(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART
            )
        val signature =
            "${player.current?.let(NativeTrack::reference)}:${player.playing}:${player.failure}:${System.identityHashCode(bitmap)}"
        if (signature == miniSignature) return
        miniSignature = signature
        mini.removeAllViews()
        val t = player.current ?: return
        val r =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = shape(surface)
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener { showPlayer() }
            }
        val art = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        r.addView(art, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        if (bitmap != null) art.setImageBitmap(bitmap) else artwork(art, trackArtwork(t))
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
            LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) },
        )
        swipe(r, false)
    }

    fun showPlayer() {
        if (player.current == null) {
            toast("Choose a track first")
            return
        }
        if (fullscreen != null) return
        val panel =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(this@NativeUi.background, 0)
                setPadding(dp(24), dp(8), dp(24), dp(12))
            }
        fullscreen = panel
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        mini.visibility = View.GONE
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        panel.addView(top)
        iconButton(top, "⌄", "Minimize player") { hidePlayer() }
        label("NOW PLAYING", 12, top, muted).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        iconButton(top, "⋮", "Queue") { showQueue() }
        val landscape =
            activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val body =
            LinearLayout(activity).apply {
                orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val cover =
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Current artwork"
            }
        body.addView(
            cover,
            if (landscape) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 1f),
        )
        swipe(cover, true)
        val details =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (landscape) dp(16) else 0, 0, 0, 0)
            }
        val detailScroll =
            ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                addView(details)
            }
        body.addView(
            detailScroll,
            if (landscape) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, -2),
        )
        val title =
            label("", 24, details).apply {
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
            }
        val artist = label("", 16, details, muted)
        val times = label("", 12, details, muted)
        val seek =
            SeekBar(activity).apply {
                max = 1000
                contentDescription = "Track position"
            }
        details.addView(seek, LinearLayout.LayoutParams(-1, dp(40)))
        var seeking = false
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    seeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    app.mediaController.command("seekto", player.duration * bar.progress / 1000)
                }

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {}
            }
        )
        val controls = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        details.addView(controls)
        iconButton(controls, "⤨", "Shuffle") { app.mediaController.command("shuffle") }
        iconButton(controls, "|‹", "Previous") { app.mediaController.command("previoustrack") }
        val toggle =
            iconButton(controls, "▶", "Play") {
                if (player.playing) app.mediaController.pauseLocal()
                else app.mediaController.command("play")
            }
        iconButton(controls, "›|", "Next") { app.mediaController.command("nexttrack") }
        iconButton(controls, "↻", "Repeat") { app.mediaController.command("repeat") }
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        details.addView(actions)
        val favorite =
            iconButton(actions, "♡", "Favorite") { app.mediaController.command("favorite") }
        iconButton(actions, "≡", "Lyrics") { tools.lyrics() }
        iconButton(actions, "⇩", "Download") { player.current?.let { download(listOf(it)) } }
        val volume =
            SeekBar(activity).apply {
                max = 100
                progress = (player.volume * 100).toInt()
                contentDescription = "Playback volume"
            }
        details.addView(volume, LinearLayout.LayoutParams(-1, dp(40)))
        volume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    player.setVolume(bar.progress / 100f, true)
                }

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) player.setVolume(progress / 100f)
                }
            }
        )
        var last = ""
        updateFull = {
            val t = player.current
            if (t == null) hidePlayer()
            else {
                title.text = t.optString("title")
                artist.text = player.failure ?: t.optString("artist")
                toggle.text = if (player.playing) "Ⅱ" else "▶"
                toggle.contentDescription = if (player.playing) "Pause" else "Play"
                favorite.isSelected = t.optBoolean("is_favorite")
                favorite.alpha = if (favorite.isSelected) 1f else .6f
                if (!seeking)
                    seek.progress =
                        if (player.duration > 0) (player.position * 1000 / player.duration).toInt()
                        else 0
                times.text = "${time(player.position)}   ·   ${time(player.duration)}"
                val id = NativeTrack.reference(t)
                if (last != id) {
                    val changed = last.isNotBlank()
                    last = id
                    cover.setImageDrawable(null)
                    artwork(cover, trackArtwork(t))
                    if (changed && !reduced()) {
                        cover.animate().cancel()
                        cover.alpha = .4f
                        cover.translationX = dp(48).toFloat()
                        cover.animate().alpha(1f).translationX(0f).setDuration(200).start()
                    }
                }
            }
        }
        updateFull?.invoke()
        if (!reduced()) {
            panel.alpha = 0f
            panel.translationY = dp(40).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    fun hidePlayer() {
        val panel = fullscreen ?: return
        fullscreen = null
        updateFull = null
        root.removeView(panel)
        mini.visibility = View.VISIBLE
        renderMini()
    }

    fun showQueue() {
        hidePlayer()
        screen("Queue", { home() })
        tracks(player.queue)
    }

    fun reduced() =
        activity.getSharedPreferences(MainActivity.PREFS, 0).getBoolean("reduce_effects", false) ||
            android.provider.Settings.Global.getFloat(
                activity.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f

    @Suppress("ClickableViewAccessibility")
    private fun swipe(view: View, artwork: Boolean) {
        var x = 0f
        var y = 0f
        var dragging = false
        var cancelled = false
        var started = 0L
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    x = event.rawX
                    y = event.rawY
                    started = event.eventTime
                    dragging = false
                    cancelled = event.pointerCount != 1
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - x
                    val dy = event.rawY - y
                    if (event.pointerCount != 1 || cancelled) {
                        cancelled = true
                        target.translationX = 0f
                        target.translationY = 0f
                        false
                    } else {
                        dragging = kotlin.math.abs(dx) > dp(12) || (artwork && dy > dp(16))
                        if (dragging && !reduced()) {
                            if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5)
                                target.translationX = dx
                            else if (artwork && dy > 0) target.translationY = dy
                        }
                        dragging
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - x
                    val dy = event.rawY - y
                    if (cancelled || event.eventTime - started > 1500) {
                        target.translationX = 0f
                        target.translationY = 0f
                        return@setOnTouchListener true
                    }
                    target
                        .animate()
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(if (reduced()) 0 else 180)
                        .start()
                    when {
                        artwork && dy > dp(72) && dy > kotlin.math.abs(dx) * 1.5 -> hidePlayer()
                        kotlin.math.abs(dx) > dp(48) &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5 ->
                            app.mediaController.command(
                                if (dx < 0) "nexttrack" else "previousgesture"
                            )
                        !dragging -> target.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelled = true
                    target.translationX = 0f
                    target.translationY = 0f
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelled = true
                    target.translationX = 0f
                    target.translationY = 0f
                    false
                }
                else -> false
            }
        }
    }

    private fun time(ms: Long) = "${ms/60000}:${((ms/1000)%60).toString().padStart(2,'0')}"

    fun menu() {
        val items =
            mutableListOf(
                "Profile",
                "Appearance & settings",
                "Downloads",
                "About & updates",
                "Configure server",
                "Sign out",
            )
        if (api.isAdmin) items.add("Administration")
        choose("Aoede", items) {
            when (it) {
                0 -> tools.profile(api.username)
                1 -> tools.settings()
                2 -> tools.downloads()
                3 -> tools.about()
                4 -> activity.openServerSettings()
                5 ->
                    confirm("Sign out", "Stop playback and sign out of this server?") {
                        player.release()
                        api.execute({ api.logout() }) { _, error ->
                            if (error != null)
                                toast(
                                    "The server could not revoke this session. Try again when connected."
                                )
                            else login()
                        }
                    }
                6 -> tools.admin()
            }
        }
    }

    fun back(): Boolean {
        if (fullscreen != null) {
            hidePlayer()
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

    fun close() {
        tools.close()
        alive = false
        generation++
        migration?.close()
        migration = null
        player.onChanged = null
        player.onOpenPlayer = null
        app.nativeChanged = null
    }

    fun pause() {
        tools.pause()
    }

    fun resume() {
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
