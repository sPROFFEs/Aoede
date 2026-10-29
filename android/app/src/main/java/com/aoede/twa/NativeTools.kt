package com.aoede.twa

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Native secondary screens, using the same authorized backend controllers as the web client. */
class NativeTools(private val ui: NativeUi) {
    private val api
        get() = ui.api

    private val app
        get() = ui.app

    private val player
        get() = ui.player

    private var partyBody: LinearLayout? = null
    private var partyState: TextView? = null
    private var partyNow: TextView? = null
    private var partyQueue: LinearLayout? = null
    private var partyArtist: TextView? = null
    private var partyParticipants: LinearLayout? = null
    private var partyCover: android.widget.ImageView? = null
    private var partyToggle: NativeIconButton? = null
    private var partySignature = ""
    private var remotePoll: Runnable? = null
    private var remoteBody: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    private var settingsTab = "account"
    private var foreground = true
    private val monitorPolls = mutableListOf<Pair<LinearLayout, Runnable>>()

    fun settings(
        tab: String = settingsTab,
        drafts: Map<String, String> = emptyMap(),
        cached: JSONObject? = null,
    ) {
        settingsTab = tab
        val render: (Any) -> Unit = { value ->
            val data = value as JSONObject
            val prefs = ui.activity.getSharedPreferences(MainActivity.PREFS, 0)
            val scene = ui.content
            val fields = mutableMapOf<String, android.widget.EditText>()
            val allPanels = mutableMapOf<String, LinearLayout>()
            fun settingsPanel(
                title: String,
                description: String = "",
                glyph: String = "",
                parent: LinearLayout = ui.content,
            ) = ui.panel(title, description, glyph, parent, settings = true)
            fun settingsField(
                title: String,
                parent: LinearLayout,
                password: Boolean = false,
                value: String = "",
            ): android.widget.EditText {
                val input = ui.labelledField(title.uppercase(), parent, password, value)
                val frame = input.parent as android.widget.FrameLayout
                val caption =
                    parent.getChildAt(parent.indexOfChild(frame) - 1) as android.widget.TextView
                (caption.layoutParams as LinearLayout.LayoutParams).topMargin =
                    ui.dp(if (parent.indexOfChild(caption) == 0) 0 else 16)
                ui.design.typography(caption, 12.48f, 500)
                caption.letterSpacing = .04f
                caption.setTextColor(ui.muted)
                (frame.layoutParams as LinearLayout.LayoutParams).apply {
                    height = ui.dp(42)
                    bottomMargin = 0
                }
                input.background =
                    ui.design.shape(
                        if (ui.design.theme == "classic") 0x0affffff else ui.design.wash,
                        6,
                        true,
                    )
                return input
            }
            ui.space(16)
            ui.identity(
                api.username,
                if (api.isAdmin) ui.text("common.admin", "Administrator")
                else ui.text("common.member", "Member"),
                "/user/avatar/${NativeApi.encode(api.username)}",
                size = 72,
                detail =
                    ui.text(
                        "settings.security_subtitle",
                        "Account security, metadata providers, and YouTube authentication for this user profile.",
                    ),
                edit = {
                    ui.activity.pickNativeFile("image/*") {
                        upload("/user/upload-avatar", "avatar_file", it) { settings() }
                    }
                },
            )
            val keys = listOf("account", "appearance", "playback", "integrations", "youtube")
            ui.tabs(
                listOf(
                    "Account",
                    ui.text("appearance.title", "Appearance"),
                    "Playback",
                    "Integrations",
                    "YouTube authentication",
                ),
                keys.indexOf(tab).coerceAtLeast(0),
                icons = listOf("user", "palette", "volume-2", "key-round", "cookie"),
            ) { i ->
                settingsTab = keys[i]
                allPanels.forEach { (key, panel) ->
                    panel.visibility =
                        if (key == settingsTab) android.view.View.VISIBLE
                        else android.view.View.GONE
                }
            }
            ui.space(if (ui.design.theme == "vitrine") 20 else 24)
            fun region(key: String) =
                LinearLayout(ui.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility =
                        if (key == tab) android.view.View.VISIBLE else android.view.View.GONE
                    ui.content.addView(this)
                    allPanels[key] = this
                }
            val account = region("account")
            val security =
                settingsPanel(
                    ui.text("settings.section.security", "Security"),
                    ui.text(
                        "settings.security_desc",
                        "Manage your password and keep your account secure.",
                    ),
                    "lock",
                    account,
                )
            val current =
                settingsField(
                        ui.text("settings.current_password", "Current password"),
                        security,
                        true,
                        drafts["current"].orEmpty(),
                    )
                    .also { fields["current"] = it }
            val next =
                settingsField(
                        ui.text("settings.new_password", "New password"),
                        security,
                        true,
                        drafts["next"].orEmpty(),
                    )
                    .also { fields["next"] = it }
            val confirm =
                settingsField(
                        ui.text("settings.confirm_password", "Confirm password"),
                        security,
                        true,
                        drafts["confirm"].orEmpty(),
                    )
                    .also { fields["confirm"] = it }
            for (input in listOf(current, next, confirm)) {
                val frame = input.parent as android.widget.FrameLayout
                if (frame.childCount > 1) frame.removeViewAt(1)
                input.setPadding(ui.dp(14), 0, ui.dp(14), 0)
            }
            ui.button(ui.text("settings.save_button", "Save changes"), security, true) {
                if (next.text.toString() != confirm.text.toString()) {
                    confirm.error = "Passwords do not match"
                } else
                    ui.request(
                        "/user/change-password",
                        form =
                            mapOf(
                                "current_password" to current.text.toString(),
                                "new_password" to next.text.toString(),
                                "confirm_password" to confirm.text.toString(),
                            ),
                        done = {
                            fields.values.forEach { it.text.clear() }
                            player.release()
                            api.clearSession()
                            ui.login("Password changed. Sign in again.")
                        },
                    )
            }
            val language =
                settingsPanel(
                    ui.text("settings.language_section", "Language"),
                    "Choose the language used across your devices.",
                    "globe",
                    account,
                )
            ui.button(
                when (api.account?.optString("language")) {
                    "es" -> "Español"
                    "de" -> "Deutsch"
                    else -> "English"
                },
                language,
            ) {
                ui.choose("Language", listOf("English", "Español", "Deutsch")) { i ->
                    val selected = listOf("en", "es", "de")[i]
                    ui.request(
                        "/api/mobile/v2/settings",
                        "PATCH",
                        JSONObject().put("language", selected),
                        done = {
                            api.saveContextPreference("language", selected)
                            settings("account", fields.mapValues { it.value.text.toString() }, data)
                        },
                    )
                }
            }
            val accountActions = settingsPanel("Account", "", "shield", account)
            ui.button("Public profile", accountActions) { profile(api.username) }
            ui.button("Deletion request responses", accountActions) { deletionResponses() }
            ui.button("Configure server", accountActions) { ui.activity.openServerSettings() }
            ui.button("Sign out", accountActions) { ui.signOut() }

            val appearance = region("appearance")
            val themes =
                settingsPanel(
                    ui.text("appearance.title", "Appearance"),
                    ui.text(
                        "appearance.description",
                        "Choose how Aoede looks. Your theme follows you across devices.",
                    ),
                    "palette",
                    appearance,
                )
            val choices = mutableListOf<android.view.View>()
            var saving = false
            for (key in listOf("classic", "glass", "vitrine")) {
                val selected = data.optString("theme") == key
                val card =
                    object : LinearLayout(ui.activity) {
                            override fun onInitializeAccessibilityNodeInfo(
                                info: android.view.accessibility.AccessibilityNodeInfo
                            ) {
                                super.onInitializeAccessibilityNodeInfo(info)
                                info.className = "android.widget.RadioButton"
                                info.isCheckable = true
                                info.isChecked = isSelected
                            }
                        }
                        .apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
                            isFocusable = true
                            isSelected = selected
                            contentDescription = ui.text("appearance.$key", key)
                        }
                card.background =
                    ui.design
                        .shape(
                            android.graphics.Color.TRANSPARENT,
                            if (ui.design.theme == "vitrine") 24 else 20,
                            true,
                        )
                        .apply {
                            setStroke(ui.dp(2), if (selected) ui.accent else ui.design.border)
                        }
                themes.addView(
                    card,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(20) },
                )
                card.addView(
                    NativeThemePreview(ui.activity, key),
                    LinearLayout.LayoutParams(-1, ui.dp(140)),
                )
                ui.label(
                        ui.text("appearance.$key", key.replaceFirstChar(Char::uppercase)),
                        16,
                        card,
                    )
                    .apply {
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12)
                    }
                ui.label(
                        ui.text(
                            "appearance.${key}_description",
                            when (key) {
                                "classic" -> "Familiar contrast and a clean music-first layout."
                                "glass" -> "Soft translucent surfaces and airy controls."
                                else -> "An editorial layout with clear, expressive controls."
                            },
                        ),
                        14,
                        card,
                        ui.muted,
                    )
                    .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(6) }
                choices.add(card)
                card.setOnClickListener {
                    if (!saving && !selected) {
                        saving = true
                        choices.forEach { it.isEnabled = false }
                        val scope = api.scope
                        api.execute({
                            api.request(
                                "/api/mobile/v2/settings",
                                "PATCH",
                                JSONObject().put("theme", key),
                            )
                        }) { _, error ->
                            saving = false
                            choices.forEach { it.isEnabled = true }
                            if (api.scope != scope) return@execute
                            if (error != null) ui.toast(error.message ?: "Could not save theme")
                            else {
                                api.saveContextPreference("theme", key)
                                if (ui.content === scene)
                                    settings(
                                        "appearance",
                                        fields.mapValues { it.value.text.toString() },
                                        data.put("theme", key),
                                    )
                            }
                        }
                    }
                }
            }
            val device = settingsPanel("This device", "", "smartphone", appearance)
            ui.toggle("Reduce effects", prefs.getBoolean("reduce_effects", false), device) {
                prefs.edit().putBoolean("reduce_effects", it).apply()
                settings(
                    "appearance",
                    fields.mapValues { field -> field.value.text.toString() },
                    data,
                )
            }
            ui.button("Dynamic Island", device) { ui.activity.openIslandSettings() }
            ui.button("Storage & migration", device) { storage() }
            ui.button("Installed web client · compatibility", device) {
                ui.confirm(
                    "Use compatibility client",
                    "This preserves the previous installed interface and its original device library. Native playback will stop before switching.",
                ) {
                    app.destroyPlayer()
                    api.origin?.rule?.let { api.changeClientMode(it, false) }
                    prefs.edit().putBoolean("use_native_player", false).apply()
                    ui.activity.recreate()
                }
            }

            val playback = region("playback")
            val audio =
                settingsPanel("Playback", "Adjust playback on this device.", "volume-2", playback)
            ui.toggle(
                "Loudness normalization",
                prefs.getBoolean("native_replaygain", false),
                audio,
            ) {
                prefs.edit().putBoolean("native_replaygain", it).apply()
                player.refreshAudioPreferences()
            }
            ui.space(12, audio)
            ui.label("Track fade", 13, audio, ui.muted)
            val fade =
                android.widget.SeekBar(ui.activity).apply {
                    max = 12
                    progress = prefs.getInt("native_fade_seconds", 0)
                    contentDescription = "Track fade in seconds"
                    progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                    thumbTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                }
            audio.addView(fade, LinearLayout.LayoutParams(-1, ui.dp(44)))
            val fadeLabel = ui.label("${fade.progress} seconds", 13, audio, ui.muted)
            fade.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}

                    override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                        prefs.edit().putInt("native_fade_seconds", bar.progress).apply()
                    }

                    override fun onProgressChanged(
                        bar: android.widget.SeekBar,
                        value: Int,
                        user: Boolean,
                    ) {
                        fadeLabel.text = "$value seconds"
                    }
                }
            )
            ui.button("Sleep timer", audio) {
                ui.choose(
                    "Pause playback after",
                    listOf("Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "2 hours"),
                ) {
                    player.setSleepTimer(listOf(0, 15, 30, 45, 60, 120)[it])
                }
            }

            val integrations = region("integrations")
            val provider =
                settingsPanel(
                    ui.text("settings.data_sources", "Data sources"),
                    "Stored secrets are never returned. Leave a field blank to keep it.",
                    "key-round",
                    integrations,
                )
            val configured = data.optJSONObject("integrations") ?: JSONObject()
            val providerFields =
                listOf(
                        "spotify_client_id",
                        "spotify_client_secret",
                        "lastfm_api_key",
                        "lastfm_shared_secret",
                    )
                    .associateWith { key ->
                        settingsField(
                                key.replace('_', ' ').replaceFirstChar(Char::uppercase) +
                                    (if (configured.optBoolean(key)) " · configured" else ""),
                                provider,
                                true,
                                drafts[key].orEmpty(),
                            )
                            .also { fields[key] = it }
                    }
            ui.button("Save credentials", provider, true) {
                val payload = JSONObject()
                providerFields.forEach { (key, input) ->
                    if (input.text.isNotBlank()) payload.put(key, input.text.toString())
                }
                ui.request(
                    "/api/mobile/v2/integrations",
                    "PUT",
                    payload,
                    done = {
                        providerFields.values.forEach { it.text.clear() }
                        ui.toast("Credentials saved")
                    },
                )
            }
            ui.button("Remove a stored credential", provider) {
                ui.choose("Remove credential", providerFields.keys.toList()) { index ->
                    val key = providerFields.keys.elementAt(index)
                    ui.confirm("Remove credential", key) {
                        ui.request(
                            "/api/mobile/v2/integrations",
                            "PUT",
                            JSONObject().put(key, JSONObject.NULL),
                            done = { settings("integrations") },
                        )
                    }
                }
            }
            ui.button("Metadata provider priority", provider) {
                ui.choose("First provider", listOf("spotify", "lastfm", "musicbrainz")) { first ->
                    val providers = listOf("spotify", "lastfm", "musicbrainz")
                    ui.request(
                        "/api/mobile/v2/settings",
                        "PATCH",
                        JSONObject()
                            .put(
                                "metadata_preferences",
                                org.json.JSONArray(
                                    listOf(providers[first]) +
                                        providers.filterIndexed { i, _ -> i != first }
                                ),
                            ),
                        done = { ui.toast("Priority saved") },
                    )
                }
            }

            val youtube = region("youtube")
            val cookies =
                settingsPanel(
                    "YouTube authentication",
                    "Import cookies to use your account with the server downloader.",
                    "cookie",
                    youtube,
                )
            ui.button("Import YouTube cookies", cookies, true) {
                ui.activity.pickNativeFile("text/*") {
                    upload("/user/upload-cookies", "cookies_file", it) {
                        ui.toast("Cookies updated")
                    }
                }
            }
        }
        if (cached != null) {
            ui.screen(
                "Settings",
                { ui.home() },
                key = "/api/mobile/v2/settings",
                restore = { settings() },
            )
            render(cached)
        } else
            ui.load(
                "Settings",
                "/api/mobile/v2/settings",
                restore = { settings() },
                render = render,
            )
    }

    fun deletionResponses() {
        ui.load("Your requests", "/api/tracks/delete-requests/mine") { rows ->
            for (r in NativeTrack.items(rows)) ui.row(
                r.optString("track_title"),
                r.optString("status") + " · " + r.optString("review_note"),
            ) {}
            ui.request("/api/tracks/delete-requests/ack", done = {})
        }
    }

    fun profile(username: String) {
        ui.load(
            ui.text("profile.profile", "Profile"),
            "/user/profile/${NativeApi.encode(username)}",
        ) { value ->
            val data = value as JSONObject
            val playlists = NativeTrack.items(data.optJSONArray("public_playlists"))
            val favorites = NativeTrack.items(data.optJSONArray("favorites"))
            val hero =
                LinearLayout(ui.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
                    background =
                        ui.design.shape(
                            ui.design.content,
                            if (ui.design.theme == "vitrine") 22 else 24,
                            true,
                        )
                }
            if (ui.design.theme == "classic") hero.background = null
            ui.content.addView(
                hero,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(24) },
            )
            ui.button(ui.text("common.back", "Back"), hero) { ui.back() }
                .apply {
                    layoutParams =
                        LinearLayout.LayoutParams(-2, ui.dp(44)).apply { bottomMargin = ui.dp(14) }
                    setTextColor(ui.muted)
                    if (ui.design.theme == "classic")
                        background = ui.design.touch(android.graphics.Color.TRANSPARENT, 6)
                    setCompoundDrawablesRelative(
                        NativeIcon("arrow-left", ui.muted, ui.design.theme).apply {
                            setBounds(0, 0, ui.dp(16), ui.dp(16))
                        },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = ui.dp(6)
                }
            val identity =
                LinearLayout(ui.activity).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    orientation =
                        if (
                            ui.activity.resources.configuration.screenWidthDp -
                                ui.design.gutter * 2 -
                                40 < 274
                        )
                            LinearLayout.VERTICAL
                        else LinearLayout.HORIZONTAL
                }
            hero.addView(identity, LinearLayout.LayoutParams(-1, -2))
            val avatar =
                android.widget.ImageView(ui.activity).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    background = ui.design.shape(ui.design.wash, 999, true)
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    contentDescription = data.optString("username")
                }
            identity.addView(
                avatar,
                LinearLayout.LayoutParams(ui.dp(88), ui.dp(88)).apply {
                    if (identity.orientation == LinearLayout.HORIZONTAL) marginEnd = ui.dp(16)
                    else bottomMargin = ui.dp(16)
                    gravity =
                        if (identity.orientation == LinearLayout.HORIZONTAL)
                            android.view.Gravity.CENTER_VERTICAL
                        else android.view.Gravity.START
                },
            )
            ui.artwork(avatar, data.optString("avatar_url"))
            val details = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            identity.addView(
                details,
                if (identity.orientation == LinearLayout.HORIZONTAL)
                    LinearLayout.LayoutParams(0, -2, 1f)
                else LinearLayout.LayoutParams(-1, -2),
            )
            ui.label(
                    if (data.optBoolean("is_admin")) ui.text("common.admin", "Admin")
                    else ui.text("common.member", "Member"),
                    12,
                    details,
                )
                .apply {
                    text = text.toString().uppercase()
                    setTextColor(
                        if (ui.design.theme == "classic") ui.accent else 0xffa6e0bd.toInt()
                    )
                    background =
                        ui.design.shape(
                            if (ui.design.theme == "classic") 0x1622c55e else ui.design.wash,
                            999,
                            true,
                        )
                    gravity = android.view.Gravity.CENTER
                    setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3))
                    layoutParams =
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(4) }
                }
            ui.label(data.optString("username"), 32, details).apply {
                ui.design.typography(this, 32f, 700)
                ui.design.lines(this, 1.1f)
                (layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = ui.dp(4)
                    bottomMargin = ui.dp(10)
                }
            }
            if (data.optBoolean("is_federated"))
                ui.label(data.optString("fed_instance_name"), 12, details, ui.muted)
            val stats = data.optJSONObject("stats")
            val chips = NativeFlowLayout(ui.activity, ui.dp(10))
            details.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            for ((glyph, number, caption) in
                listOf(
                    Triple("list-music", playlists.size, ui.text("library.playlists", "Playlists")),
                    Triple("heart", favorites.size, ui.text("menu.liked", "Favorites")),
                    Triple(
                        "headphones",
                        stats?.optInt("total_listens") ?: 0,
                        ui.text("common.plays", "Listens"),
                    ),
                    Triple("clock", stats?.optInt("listening_minutes") ?: 0, "Mins"),
                )) {
                val chip =
                    TextView(ui.activity).apply {
                        val value = "$number $caption"
                        text =
                            android.text.SpannableString(value).apply {
                                setSpan(
                                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                    0,
                                    number.toString().length,
                                    0,
                                )
                                if (ui.design.theme == "classic")
                                    setSpan(
                                        android.text.style.ForegroundColorSpan(ui.accent),
                                        0,
                                        number.toString().length,
                                        0,
                                    )
                            }
                        ui.design.typography(this, 13f)
                        setTextColor(ui.ink)
                        background =
                            ui.design.shape(
                                if (ui.design.theme == "classic") 0x0fffffff else ui.design.content,
                                999,
                                true,
                            )
                        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                        setCompoundDrawablesRelative(
                            NativeIcon(glyph, ui.ink, ui.design.theme).apply {
                                setBounds(0, 0, ui.dp(14), ui.dp(14))
                            },
                            null,
                            null,
                            null,
                        )
                        compoundDrawablePadding = ui.dp(6)
                    }
                chips.addView(chip)
            }
            if (data.optBoolean("is_self"))
                ui.button(ui.text("profile.edit_settings", "Edit Profile & Settings"), details) {
                    settings()
                }
            if (playlists.isNotEmpty()) {
                ui.sectionHeader(
                    "${ui.text("profile.public_playlists", "Public Playlists & Albums")} (${playlists.size})"
                )
                for (item in playlists) {
                    val visibility =
                        if (
                            item.optLong("source_playlist_id") > 0 ||
                                item.optLong("source_instance_id") > 0
                        )
                            "Synced copy"
                        else if (item.optBoolean("is_public")) "Public" else "Private"
                    val owner =
                        item
                            .optString("owner_username")
                            .takeIf { it.isNotBlank() && !item.optBoolean("is_owner") }
                            ?.let { "By $it - " }
                            .orEmpty()
                    val kind = if (item.optString("kind") == "album") "Album · " else ""
                    val node =
                        if (item.optBoolean("is_federated"))
                            " · ${item.optString("fed_instance_name")}"
                        else ""
                    ui.row(
                            item.optString("name"),
                            "$kind$owner${item.optInt("track_count")} tracks - $visibility$node",
                            item.optString("thumbnail"),
                            coverSize = 56,
                        ) {
                            ui.collection(item.get("id").toString())
                        }
                        .apply {
                            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(8)
                            background =
                                ui.design.touch(
                                    if (ui.design.theme != "glass")
                                        android.graphics.Color.TRANSPARENT
                                    else ui.design.content,
                                    12,
                                    ui.design.theme == "glass",
                                )
                            minimumHeight = ui.dp(if (ui.design.theme == "glass") 74 else 72)
                            getChildAt(0).background = ui.design.shape(ui.design.wash, 8)
                            val copy = getChildAt(1) as LinearLayout
                            ui.design.typography(
                                copy.getChildAt(0) as TextView,
                                if (ui.design.theme == "vitrine") 15.04f else 15f,
                                600,
                            )
                            ui.design.lines(copy.getChildAt(0) as TextView, 1.35f)
                            ui.design.lines(copy.getChildAt(1) as TextView, 1.45f)
                            (copy.getChildAt(1).layoutParams as LinearLayout.LayoutParams)
                                .topMargin = 0
                        }
                }
            }
            if (favorites.isNotEmpty()) {
                ui.sectionHeader(
                    "Favorite Songs (${favorites.size})",
                    more = {
                        if (ui.player.party == null) ui.player.play(favorites)
                        else ui.toast("Leave the party first")
                    },
                )
                ui.tracks(favorites)
            }
            val artists = NativeTrack.items(stats?.optJSONArray("top_artists"))
            if (artists.isNotEmpty()) {
                ui.sectionHeader("Top Artists")
                ui.chipBar(
                    artists.map { it.optString("artist") to { artist(it.optString("artist")) } }
                )
            }
            if (playlists.isEmpty() && favorites.isEmpty())
                ui.label(
                    ui.text(
                        "profile.empty",
                        "This user hasn't shared any public playlists or favorites yet.",
                    ),
                    15,
                    color = ui.muted,
                )
        }
    }

    fun libraryBrowser(kind: String = "songs", query: String = "", offset: Int = 0) {
        val path =
            if (kind == "songs")
                "/api/library/tracks?paged=true&limit=40&offset=$offset&q=${NativeApi.encode(query)}"
            else
                "/api/library/facets?kind=$kind&paged=true&limit=40&offset=$offset&q=${NativeApi.encode(query)}"
        ui.load("Browse your library", path) { value ->
            val data = value as JSONObject
            ui.button("Browse · $kind") {
                ui.choose("Browse", listOf("songs", "artists", "albums", "genres", "years")) {
                    libraryBrowser(listOf("songs", "artists", "albums", "genres", "years")[it])
                }
            }
            val search = ui.field("Filter library", value = query)
            ui.button("Apply filter") { libraryBrowser(kind, search.text.toString().take(200)) }
            if (kind == "songs") ui.tracks(NativeTrack.items(data))
            else
                for (item in NativeTrack.items(data)) ui.row(
                    item.optString("name"),
                    "${item.optInt("track_count")} tracks",
                    item.optString("thumbnail"),
                ) {
                    val filter =
                        when (kind) {
                            "artists" -> "artist"
                            "albums" -> "album"
                            "genres" -> "genre"
                            else -> "year"
                        }
                    ui.load(
                        item.optString("name"),
                        "/api/library/tracks?limit=200&$filter=" +
                            NativeApi.encode(item.optString("name")),
                    ) {
                        ui.tracks(NativeTrack.items(it))
                    }
                }
            if (data.optBoolean("has_more"))
                ui.button("Next page") { libraryBrowser(kind, query, offset + 40) }
            if (offset > 0)
                ui.button("Previous page") {
                    libraryBrowser(kind, query, (offset - 40).coerceAtLeast(0))
                }
        }
    }

    fun artist(name: String) {
        ui.load(name, "/api/artist/" + Uri.encode(name)) { value ->
            val data = value as JSONObject
            val local = NativeTrack.items(data.optJSONArray("local_tracks"))
            val spotify = data.optJSONObject("spotify")
            val info = data.optJSONObject("info")
            val hero = android.widget.FrameLayout(ui.activity)
            ui.content.addView(
                hero,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(24) },
            )
            val photo =
                android.widget.ImageView(ui.activity).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
            hero.addView(photo, android.widget.FrameLayout.LayoutParams(-1, -1))
            ui.artwork(
                photo,
                spotify?.optString("image").orEmpty().ifBlank {
                    local.firstOrNull()?.optString("thumbnail") ?: "/assets/img/default_cover.png"
                },
            ) { bitmap ->
                fun position() {
                    if (photo.width == 0 || photo.height == 0) return
                    val scale =
                        maxOf(
                            photo.width.toFloat() / bitmap.width,
                            photo.height.toFloat() / bitmap.height,
                        )
                    photo.scaleType = android.widget.ImageView.ScaleType.MATRIX
                    photo.imageMatrix =
                        android.graphics.Matrix().apply {
                            setScale(scale, scale)
                            postTranslate(
                                (photo.width - bitmap.width * scale) / 2,
                                (photo.height - bitmap.height * scale) * .25f,
                            )
                        }
                }
                photo.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
                position()
            }
            val overlay =
                LinearLayout(ui.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(32), ui.dp(80), ui.dp(32), ui.dp(40))
                    background =
                        android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(0x33000000, 0xd9000000.toInt()),
                        )
                }
            hero.addView(overlay)
            val tags = info?.optJSONArray("tags")
            if (tags != null && tags.length() > 0) {
                val row = NativeFlowLayout(ui.activity, ui.dp(6))
                overlay.addView(
                    row,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) },
                )
                for (index in 0 until tags.length()) row.addView(
                    TextView(ui.activity).apply {
                        text = tags.optString(index)
                        ui.design.typography(this, 11.5f)
                        setTextColor(android.graphics.Color.WHITE)
                        setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3))
                        background = ui.design.shape(0x1fffffff, 999)
                    }
                )
            }
            ui.label(data.optString("name", name), 32, overlay, android.graphics.Color.WHITE)
                .apply {
                    ui.design.typography(this, 32f, 800)
                    ui.design.lines(this, 1.1f)
                    (layoutParams as LinearLayout.LayoutParams).apply {
                        topMargin = 0
                        bottomMargin = ui.dp(8)
                    }
                }
            val stats = mutableListOf<String>()
            info
                ?.optLong("listeners")
                ?.takeIf { it > 0 }
                ?.let {
                    stats.add(
                        "${java.text.NumberFormat.getIntegerInstance().format(it)} Last.fm listeners"
                    )
                }
            spotify
                ?.optLong("followers")
                ?.takeIf { it > 0 }
                ?.let {
                    stats.add(
                        "${java.text.NumberFormat.getIntegerInstance().format(it)} Spotify followers"
                    )
                }
            stats.add("${local.size} tracks in your library")
            ui.label(stats.joinToString(" · "), 14, overlay, 0xd9ffffff.toInt()).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(18)
            }
            val actions = LinearLayout(ui.activity)
            overlay.addView(actions)
            if (local.isNotEmpty())
                ui.button(ui.text("player.play", "Play"), actions, true) {
                        if (player.party == null) player.play(local)
                        else ui.toast("Leave the party first")
                    }
                    .layoutParams =
                    LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(10) }
            ui.button("Radio", actions) { smartRadio(name) }.layoutParams =
                LinearLayout.LayoutParams(-2, ui.dp(44))
            if (local.isNotEmpty()) {
                ui.sectionHeader("In your library", moreLabel = ui.text("player.play", "Play")) {
                    if (player.party == null) player.play(local)
                    else ui.toast("Leave the party first")
                }
                ui.tracks(local)
            } else
                ui.empty("You don't have any tracks by this artist in your library yet.", "music-2")
            val owned =
                local
                    .map {
                        it.optString("title").lowercase() + "|" + it.optString("artist").lowercase()
                    }
                    .toSet()
            val top =
                NativeTrack.items(data.optJSONArray("top_tracks"))
                    .filter {
                        it.optString("title").lowercase() +
                            "|" +
                            it.optString("artist").lowercase() !in owned
                    }
                    .take(8)
            if (top.isNotEmpty()) {
                ui.sectionHeader("Popular tracks you don't have")
                for (track in top) {
                    val artist = track.optString("artist", name)
                    val row =
                        ui.row(
                            track.optString("title"),
                            artist,
                            "/api/cover/resolve?artist=${NativeApi.encode(artist)}&title=${NativeApi.encode(track.optString("title"))}",
                        ) {
                            ui.search("$artist ${track.optString("title")}")
                        }
                    ui.iconButton(row, "download", "Download to library") {
                        ui.search("$artist ${track.optString("title")}")
                    }
                    ui.iconButton(row, "radio", "Start radio") {
                        smartRadio(artist, track.optString("title"))
                    }
                }
            }
            val albums =
                NativeTrack.items(data.optJSONArray("albums")).map {
                    JSONObject(it.toString())
                        .put("thumbnail", it.optString("image"))
                        .put(
                            "grid_subtitle",
                            it.optString("release_date").take(4) +
                                " · " +
                                if (it.optString("type") == "single") "Single" else "Album",
                        )
                }
            if (albums.isNotEmpty()) {
                ui.sectionHeader("Discography")
                ui.collectionGrid(albums) {
                    if (it.optString("url").isNotBlank()) resolveCollection(it.optString("url"))
                }
            }
            val similar = NativeTrack.items(data.optJSONArray("similar"))
            if (similar.isNotEmpty()) {
                ui.sectionHeader("Fans also like")
                for (item in similar) ui.row(
                    item.optString("name"),
                    image = item.optString("image"),
                ) {
                    artist(item.optString("name"))
                }
            }
            val bio = info?.optString("bio").orEmpty()
            if (bio.isNotBlank()) {
                ui.sectionHeader("About")
                ui.label(bio, 14, color = ui.muted)
            }
        }
    }

    fun smartRadio(artist: String, title: String = "") {
        if (player.party != null) {
            ui.toast("Leave the party first")
            return
        }
        api.execute({
            api.request(
                "/api/radio/track?artist=${NativeApi.encode(artist)}&title=${NativeApi.encode(title)}&limit=30"
            )
        }) { value, error ->
            if (error != null) ui.toast(error.message ?: "Radio unavailable")
            else {
                val tracks = NativeTrack.items((value as? JSONObject)?.optJSONArray("tracks"))
                if (tracks.isEmpty()) ui.toast("Need more tracks in your library to build a radio")
                else if (player.party == null) {
                    player.play(tracks)
                    ui.toast("Radio: ${tracks.size} tracks queued")
                }
            }
        }
    }

    fun smartPlaylist(existing: JSONObject? = null) {
        ui.sheet(if (existing == null) "New smart playlist" else "Edit smart playlist") {
            body,
            dialog ->
            ui.label(
                "Preview the rules before saving. Matching songs refresh when favorites change.",
                14,
                body,
                ui.muted,
            )
            val initial = existing?.optJSONObject("smart_rules") ?: JSONObject()
            val name =
                ui.labelledField(
                    "Playlist name",
                    body,
                    value = existing?.optString("name").orEmpty(),
                )
            name.filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            val fields = linkedMapOf<String, android.widget.EditText>()
            for ((key, label) in
                listOf(
                    "artist" to "Artist",
                    "album" to "Album",
                    "genre" to "Genre",
                    "year_min" to "Released after year",
                    "year_max" to "Released before year",
                    "added_within_days" to "Added in last N days",
                    "min_plays" to "Minimum play count",
                    "not_played_days" to "Not played for N days",
                    "limit" to "Maximum songs",
                )) {
                val value =
                    if (initial.isNull(key)) {
                        if (key == "limit") "50" else ""
                    } else initial.optString(key)
                fields[key] =
                    ui.labelledField(label, body, value = value).apply {
                        if (key !in listOf("artist", "album", "genre"))
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }
            }
            var favoriteOnly = initial.optBoolean("favorite_only")
            ui.toggle("Favorites only", favoriteOnly, body) { favoriteOnly = it }
            val sorts = listOf("newest", "artist", "album", "most_played", "least_played")
            var sort = initial.optString("sort", "newest").takeIf { it in sorts } ?: "newest"
            val order = ui.button("Sort · ${sort.replace('_', ' ')}", body) {}
            order.setOnClickListener {
                ui.choose("Sort", sorts.map { it.replace('_', ' ') }) {
                    sort = sorts[it]
                    order.text = "Sort · ${sort.replace('_', ' ')}"
                }
            }
            fun rules(): JSONObject? {
                val result = JSONObject().put("favorite_only", favoriteOnly).put("sort", sort)
                for ((key, input) in fields) {
                    val text = input.text.toString().trim()
                    if (key in listOf("artist", "album", "genre")) result.put(key, text)
                    else if (text.isNotBlank()) {
                        val number = text.toIntOrNull()
                        val range =
                            if (key.startsWith("year_")) 1000..9999
                            else if (key == "limit") 1..500 else 0..Int.MAX_VALUE
                        if (number == null || number !in range) {
                            input.error = "Enter a number in $range"
                            return null
                        }
                        result.put(key, number)
                    }
                }
                if (!result.has("limit")) result.put("limit", 50)
                if (
                    result.has("year_min") &&
                        result.has("year_max") &&
                        result.getInt("year_min") > result.getInt("year_max")
                ) {
                    fields.getValue("year_max").error = "End year must follow start year"
                    return null
                }
                return result
            }
            val preview = ui.label("Preview matching songs before saving.", 14, body, ui.muted)
            val previewButton = ui.button("Preview", body) {}
            previewButton.setOnClickListener {
                val rules = rules() ?: return@setOnClickListener
                previewButton.isEnabled = false
                preview.text = "Loading preview…"
                api.execute({
                    api.request(
                        "/api/smart-playlists/preview",
                        "POST",
                        JSONObject().put("rules", rules),
                    )
                }) { data, error ->
                    if (!dialog.isShowing) return@execute
                    previewButton.isEnabled = true
                    preview.text =
                        if (error != null) error.message
                        else
                            (data as? JSONObject)?.let {
                                "${it.optInt("track_count")} matching songs · ${it.optString("summary")}"
                            }
                }
            }
            ui.button("Save playlist", body, true) {
                val rules = rules() ?: return@button
                if (name.text.isBlank()) {
                    name.error = "Enter a playlist name"
                    return@button
                }
                val id = existing?.optLong("id")?.takeIf { it > 0 }
                ui.request(
                    "/api/smart-playlists" + if (id == null) "" else "/$id",
                    if (id == null) "POST" else "PUT",
                    JSONObject().put("name", name.text.toString().trim()).put("rules", rules),
                    done = {
                        if (dialog.isShowing) {
                            dialog.dismiss()
                            if (id == null) ui.collections() else ui.collection(id.toString())
                        }
                    },
                )
            }
        }
    }

    fun resolveCollection(url: String) {
        if (url.isBlank()) {
            ui.toast("No collection URL available")
            return
        }
        ui.sheet("Import collection") { body, dialog ->
            ui.label("Loading collection…", 14, body, ui.muted)
            api.execute({ api.request("/api/collections/resolve?url=" + NativeApi.encode(url)) }) {
                value,
                error ->
                if (!dialog.isShowing) return@execute
                body.removeAllViews()
                if (error != null) {
                    ui.label(error.message ?: "Could not load this collection", 14, body, ui.muted)
                    ui.button(ui.text("common.cancel", "Cancel"), body) { dialog.dismiss() }
                    return@execute
                }
                val data = (value as JSONObject).optJSONObject("collection") ?: value
                val tracks = NativeTrack.items(data)
                val count = data.optInt("track_count", tracks.size)
                ui.label(
                    "$count songs from ${data.optString("source")}. Saved under ${api.username} in source order.",
                    14,
                    body,
                    ui.muted,
                )
                ui.label(
                    "Matching songs already in the pool are reused. Only missing songs are downloaded; repeated entries in the source keep their positions.",
                    14,
                    body,
                    ui.muted,
                )
                if (data.optInt("skipped") > 0)
                    ui.label(
                        "${data.optInt("skipped")} unavailable entries will be skipped.",
                        14,
                        body,
                        ui.muted,
                    )
                val name =
                    ui.labelledField(
                            "Name",
                            body,
                            value = data.optString("name", "Imported collection"),
                        )
                        .apply { filters = arrayOf(android.text.InputFilter.LengthFilter(120)) }
                ui.label("Save as", 13, body, ui.muted)
                var kind = if (data.optString("kind") == "album") "album" else "playlist"
                val kindButton = ui.button(if (kind == "album") "Album" else "Playlist", body) {}
                kindButton.setOnClickListener {
                    ui.choose("Save as", listOf("Playlist", "Album")) {
                        kind = if (it == 1) "album" else "playlist"
                        kindButton.text = if (it == 1) "Album" else "Playlist"
                    }
                }
                val list =
                    android.widget.ListView(ui.activity).apply {
                        divider = null
                        isNestedScrollingEnabled = true
                        adapter =
                            object : android.widget.BaseAdapter() {
                                override fun getCount() = tracks.size

                                override fun getItem(position: Int) = tracks[position]

                                override fun getItemId(position: Int) = position.toLong()

                                override fun areAllItemsEnabled() = false

                                override fun isEnabled(position: Int) = false

                                override fun getView(
                                    position: Int,
                                    convert: android.view.View?,
                                    parent: android.view.ViewGroup,
                                ): android.view.View =
                                    (convert as? TextView ?: TextView(ui.activity)).apply {
                                        val track = tracks[position]
                                        text =
                                            "${track.optInt("position", position+1)}. ${track.optString("title")} — ${track.optString("artist")}"
                                        ui.design.typography(this, 14f)
                                        ui.design.lines(this, 1.5f)
                                        setTextColor(ui.ink)
                                        setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                                    }
                            }
                    }
                body.addView(
                    list,
                    LinearLayout.LayoutParams(
                            -1,
                            ui.dp(minOf(320, tracks.size * 42).coerceAtLeast(44)),
                        )
                        .apply {
                            topMargin = ui.dp(12)
                            bottomMargin = ui.dp(16)
                        },
                )
                val footer = LinearLayout(ui.activity)
                body.addView(footer)
                ui.button(ui.text("common.cancel", "Cancel"), footer) { dialog.dismiss() }
                    .layoutParams =
                    LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginEnd = ui.dp(10) }
                val submit = ui.button("Import $count songs", footer, true) {}
                submit.layoutParams = LinearLayout.LayoutParams(0, ui.dp(48), 1f)
                submit.setOnClickListener {
                    val collectionName = name.text.toString().trim()
                    if (collectionName.isBlank()) {
                        name.error = "Enter a collection name."
                        return@setOnClickListener
                    }
                    submit.isEnabled = false
                    submit.text = "Queuing songs…"
                    val payload =
                        JSONObject()
                            .put("url", data.optString("url", url))
                            .put("kind", kind)
                            .put("name", collectionName)
                    api.execute({
                        api.request("/api/download/collection", "POST", payload)
                    }) importResult@{ result, failure ->
                        if (!dialog.isShowing) return@importResult
                        if (failure != null) {
                            submit.isEnabled = true
                            submit.text = "Import $count songs"
                            ui.toast(failure.message ?: "Could not import this collection")
                        } else {
                            dialog.dismiss()
                            val queued = result as? JSONObject
                            ui.toast(
                                queued?.optString("message", "Import queued") ?: "Import queued"
                            )
                            val id = queued?.opt("playlist_id")?.toString()
                            if (!id.isNullOrBlank() && id != "null") ui.collection(id)
                        }
                    }
                }
            }
        }
    }

    private var downloadDialog: android.app.Dialog? = null
    private var downloadPoll: Runnable? = null

    fun downloads() {
        if (downloadDialog?.isShowing == true) return
        downloadDialog =
            ui.sheet(ui.text("downloads.modal_title", "Downloads Manager")) { body, dialog ->
                var collection = false
                val form = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
                val submitLabel = ui.text("downloads.modal_download_btn", "Download")
                lateinit var submit: android.widget.Button
                ui.toggle("Album / playlist URL (Spotify / YouTube)", false, body, leading = true) {
                    collection = it
                    submit.text = if (it) "Preview collection" else submitLabel
                }
                body.addView(
                    form,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = ui.dp(12)
                        bottomMargin = ui.dp(24)
                    },
                )
                val url =
                    ui.field(
                        ui.text("downloads.modal_placeholder", "Paste YouTube/Spotify URL..."),
                        form,
                    )
                url.inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
                url.filters = arrayOf(android.text.InputFilter.LengthFilter(4096))
                submit = ui.button(submitLabel, form, true) {}
                url.layoutParams =
                    LinearLayout.LayoutParams(-1, ui.dp(43)).apply { bottomMargin = ui.dp(12) }
                submit.layoutParams = LinearLayout.LayoutParams(-2, ui.dp(44))
                url.background =
                    ui.design.shape(
                        ui.design.wash,
                        if (ui.design.theme == "vitrine") 16 else 14,
                        true,
                    )
                val jobs = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
                body.addView(jobs)
                var serial = 0
                var signature = ""
                val active = mutableMapOf<String, String>()
                val poll =
                    object : Runnable {
                        override fun run() {
                            main.removeCallbacks(this)
                            if (!foreground || !dialog.isShowing) return
                            val ticket = ++serial
                            api.execute({ api.request("/api/jobs") }) { result, error ->
                                if (!foreground || !dialog.isShowing || ticket != serial)
                                    return@execute
                                if (error != null) {
                                    if (signature != "error") {
                                        jobs.removeAllViews()
                                        ui.label(
                                            error.message
                                                ?: "Could not connect to downloads. Retrying…",
                                            14,
                                            jobs,
                                            ui.muted,
                                        )
                                        signature = "error"
                                    }
                                } else {
                                    val list = NativeTrack.items(result)
                                    for (job in list) {
                                        val id = job.optString("id")
                                        val status = job.optString("status").lowercase()
                                        val title = downloadTitle(job)
                                        if (
                                            status !in
                                                listOf("completed", "finished", "error", "failed")
                                        )
                                            active[id] = title
                                        else if (active.remove(id) != null)
                                            ui.toast(
                                                if (status in listOf("error", "failed"))
                                                    "Download failed: $title"
                                                else "Downloaded: $title"
                                            )
                                    }
                                    active.keys.retainAll(list.map { it.optString("id") }.toSet())
                                    val snapshot = result.toString()
                                    if (snapshot != signature) {
                                        signature = snapshot
                                        jobs.removeAllViews()
                                        if (list.isEmpty())
                                            ui.label("No downloads yet.", 14, jobs, ui.muted)
                                                .gravity = android.view.Gravity.CENTER
                                        for (job in list) downloadJob(job, jobs) {
                                            val id = job.optLong("id", -1)
                                            if (id < 0) return@downloadJob
                                            ui.request(
                                                "/api/downloads/$id/retry",
                                                done = {
                                                    if (dialog.isShowing) {
                                                        ++serial
                                                        signature = ""
                                                        main.post(this)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                if (error !is NativeApi.Failure || error.code !in listOf(401, 403))
                                    main.postDelayed(this, 3000)
                            }
                        }
                    }
                downloadPoll = poll
                main.post(poll)
                body.addOnAttachStateChangeListener(
                    object : android.view.View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: android.view.View) {}

                        override fun onViewDetachedFromWindow(view: android.view.View) {
                            ++serial
                            main.removeCallbacks(poll)
                            if (downloadPoll === poll) {
                                downloadPoll = null
                                downloadDialog = null
                            }
                        }
                    }
                )
                submit.setOnClickListener {
                    val target = url.text.toString().trim()
                    if (target.isEmpty()) {
                        url.error = "Enter a music URL"
                        return@setOnClickListener
                    }
                    val parsed = Uri.parse(target)
                    val collectionUrl =
                        parsed.scheme in listOf("http", "https") &&
                            parsed.userInfo == null &&
                            (parsed.host == "open.spotify.com" &&
                                Regex("/(album|playlist)/")
                                    .containsMatchIn(parsed.path.orEmpty()) ||
                                parsed.host in
                                    listOf(
                                        "youtube.com",
                                        "www.youtube.com",
                                        "music.youtube.com",
                                        "m.youtube.com",
                                        "youtu.be",
                                    ) &&
                                    (parsed.getQueryParameter("list") != null ||
                                        parsed.path.orEmpty().startsWith("/browse/MPRE")))
                    if (collection || collectionUrl) {
                        dialog.dismiss()
                        resolveCollection(target)
                        return@setOnClickListener
                    }
                    submit.isEnabled = false
                    ++serial
                    main.removeCallbacks(poll)
                    api.execute({
                        val duplicate =
                            runCatching {
                                    api.request(
                                        "/api/check-duplicate?url=" + NativeApi.encode(target)
                                    ) as JSONObject
                                }
                                .getOrNull()
                        if (duplicate?.optBoolean("exists") == true)
                            JSONObject().put("duplicate", duplicate.optJSONObject("track"))
                        else
                            api.request(
                                "/api/download",
                                "POST",
                                JSONObject()
                                    .put("url", target)
                                    .put("title", "")
                                    .put("artist", "")
                                    .put("album", "")
                                    .put("source", ""),
                            )
                    }) { result, error ->
                        if (!dialog.isShowing) return@execute
                        submit.isEnabled = true
                        if (error != null) ui.toast(error.message ?: "Failed to queue download")
                        else {
                            val response = result as? JSONObject
                            val existing = response?.optJSONObject("duplicate")
                            ui.toast(
                                if (existing != null)
                                    "Already in library: ${existing.optString("title")} — ${existing.optString("artist")}"
                                else
                                    response?.optString("message", "Download queued")
                                        ?: "Download queued"
                            )
                            url.text.clear()
                            signature = ""
                        }
                        main.post(poll)
                    }
                }
            }
    }

    private fun downloadTitle(job: JSONObject) =
        listOf("resolved_track_title", "resolved_title", "track_title", "filename", "url")
            .map { job.optString(it).takeUnless { value -> value == "null" }.orEmpty() }
            .firstOrNull { it.isNotBlank() } ?: "Untitled download"

    private fun downloadJob(job: JSONObject, parent: LinearLayout, retry: () -> Unit) {
        val status = job.optString("status").lowercase()
        val failed = status in listOf("error", "failed")
        val completed = status in listOf("completed", "finished")
        val processing = status in listOf("processing", "downloading")
        val color =
            if (failed) 0xfffca5a5.toInt()
            else if (completed) 0xff6ee7a7.toInt()
            else if (processing) 0xff93c5fd.toInt() else 0xfffbbf24.toInt()
        val block =
            LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    ui.dp(if (ui.design.theme == "classic") 0 else 2),
                    ui.dp(14),
                    0,
                    ui.dp(14),
                )
            }
        parent.addView(block)
        val header = LinearLayout(ui.activity).apply { gravity = android.view.Gravity.TOP }
        block.addView(header)
        val copy = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        header.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        ui.label(downloadTitle(job), 15, copy).apply {
            ui.design.typography(this, 14.72f, 600)
            ui.design.lines(this, 1.4f)
        }
        ui.label(
                "Source: ${job.optString("source", "Unknown source").replaceFirstChar(Char::uppercase)}",
                13,
                copy,
                ui.muted,
            )
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(4) }
        TextView(ui.activity).apply {
            text =
                if (failed) "Failed"
                else if (completed) "Completed" else if (processing) "Processing" else "Queued"
            ui.design.typography(this, 11.2f, 600)
            setTextColor(color)
            setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3))
            background = ui.design.shape(ui.design.wash, 999, true)
            header.addView(this)
        }
        val progress =
            android.widget
                .ProgressBar(ui.activity, null, android.R.attr.progressBarStyleHorizontal)
                .apply {
                    max = 100
                    progress = job.optInt("progress").coerceIn(0, 100)
                    progressTintList =
                        android.content.res.ColorStateList.valueOf(if (failed) color else ui.accent)
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(ui.design.border)
                }
        block.addView(
            progress,
            LinearLayout.LayoutParams(-1, ui.dp(if (ui.design.theme == "classic") 6 else 4)).apply {
                topMargin = ui.dp(10)
            },
        )
        val detail =
            listOf("error", "detail", "filename", "url")
                .map { job.optString(it).takeUnless { value -> value == "null" }.orEmpty() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        ui.label(detail, 13, block, if (failed) color else ui.muted).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8)
        }
        fun value(key: String) = job.optString(key).takeUnless { it == "null" }.orEmpty()
        fun joined(artist: String, title: String) =
            listOf(value(artist), value(title)).filter { it.isNotBlank() }.joinToString(" - ")
        val details =
            mutableListOf(
                "Requested source" to
                    value("requested_source")
                        .ifBlank { value("source") }
                        .replaceFirstChar(Char::uppercase)
            )
        if (value("original_input_url").isNotBlank() && value("original_input_url") != value("url"))
            details.add("Original input" to value("original_input_url"))
        details.add("Requested track" to joined("requested_artist", "requested_title"))
        details.add(
            "Resolved track" to
                joined("resolved_artist", "resolved_title").ifBlank {
                    value("resolved_track_title")
                }
        )
        for ((key, title) in
            listOf(
                "resolved_track_id" to "Track ID",
                "engine_used" to "Engine",
                "resolution_mode" to "Resolution",
                "fallback_reason" to "Fallback",
                "error_type" to "Error type",
            )) details.add(title to value(key))
        val resolution =
            LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                background =
                    ui.design.shape(
                        if (ui.design.theme == "classic") 0x29000000
                        else android.graphics.Color.TRANSPARENT,
                        12,
                        true,
                    )
            }
        block.addView(resolution, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        for ((caption, text) in details.filter { it.second.isNotBlank() }) {
            val line = LinearLayout(ui.activity).apply { gravity = android.view.Gravity.TOP }
            resolution.addView(
                line,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) },
            )
            ui.label(caption.uppercase(), 12, line, ui.muted).apply {
                ui.design.typography(this, 11.52f)
                ui.design.lines(this, 1.35f)
                layoutParams =
                    LinearLayout.LayoutParams(ui.dp(110), -2).apply { marginEnd = ui.dp(8) }
            }
            ui.label(text, 13, line).apply {
                ui.design.typography(this, 12.48f, 600)
                ui.design.lines(this, 1.35f)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
        }
        val footer =
            LinearLayout(ui.activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        block.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        ui.label("${job.optInt("progress").coerceIn(0, 100)}% completion", 12, footer, ui.muted)
            .layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        if (failed)
            ui.button("Retry", footer) { retry() }.layoutParams =
                LinearLayout.LayoutParams(-2, ui.dp(44))
        val time =
            runCatching {
                    java.text
                        .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(value("created_at"))
                }
                .getOrNull()
        if (time != null)
            ui.label(
                    android.text.format.DateFormat.getTimeFormat(ui.activity).format(time),
                    12,
                    footer,
                    ui.muted,
                )
                .layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(12) }
        parent.addView(
            android.view.View(ui.activity).apply { setBackgroundColor(ui.design.border) },
            LinearLayout.LayoutParams(-1, ui.dp(1)),
        )
    }

    private var radioHub =
        listOf("London", "Tokyo", "Berlin", "Huelva", "New York", "Paris", "Madrid", "Ibiza")
            .random()

    fun playSavedRadio(station: JSONObject) {
        playRadio(
            station.optString("radio_id", station.optString("id")),
            station.optString("name", station.optString("title")),
            station.optString(
                "stream_url",
                station.optString("streamUrl", station.optString("url")),
            ),
            saved = true,
        )
    }

    private fun playRadio(id: String, name: String, stream: String = "", saved: Boolean = false) {
        if (player.party != null) {
            ui.toast("Leave the party first")
            return
        }
        val target =
            stream.ifBlank { "https://radio.garden/api/ara/content/listen/$id/channel.mp3" }
        player.play(
            listOf(
                JSONObject()
                    .put("id", "radio-$id")
                    .put("title", name)
                    .put("artist", if (saved) "Saved Radio" else "Live Radio")
                    .put("is_radio", true)
                    .put("thumbnail", "/assets/img/default_cover.png")
                    .put("stream_url", "/api/proxy/radio?url=" + NativeApi.encode(target))
            )
        )
        if (saved && id.isNotBlank())
            api.execute({
                api.request(
                    "/api/recent-activity/radio",
                    "POST",
                    JSONObject().put("radio_id", id).put("name", name).put("stream_url", target),
                )
            }) { _, _ ->
            }
    }

    fun radios(query: String = radioHub) {
        radioHub = query
        ui.screen(
            ui.text("radios.title", "Discover Radios"),
            { ui.home() },
            nav = "home",
            key = "radios",
            restore = { radios() },
        )
        val owner = ui.content
        ui.overviewTabs("radios")
        ui.sectionHeader(ui.text("radios.editorial", "Editorial Playlists"))
        val editorial = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        owner.addView(editorial)
        ui.sectionHeader(ui.text("radios.search_stations", "Search Stations"))
        val searchRow =
            LinearLayout(ui.activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        owner.addView(searchRow)
        val search =
            ui.field(
                ui.text("radios.search_placeholder", "Search city or station..."),
                searchRow,
                value = query,
            )
        search.layoutParams =
            LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginEnd = ui.dp(12) }
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        val submit = ui.button(ui.text("radio.search.button", "Search"), searchRow, true) {}
        submit.layoutParams = LinearLayout.LayoutParams(-2, ui.dp(48))
        val results = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        owner.addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        ui.space(32)
        ui.sectionHeader(ui.text("radios.saved_stations", "Your Saved Radios"))
        val saved = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        owner.addView(saved)
        fun stationRow(
            station: JSONObject,
            parent: LinearLayout,
            stored: Boolean,
            refresh: () -> Unit,
        ) {
            val name = station.optString("title", station.optString("name", "Unknown"))
            val id =
                if (stored) station.optString("id")
                else Uri.parse(station.optString("url")).lastPathSegment.orEmpty()
            if (!stored && !Regex("[A-Za-z0-9_-]{5,64}").matches(id)) return
            val row =
                ui.row(
                    name,
                    station.optString("subtitle", if (stored) "Saved Radio" else "Streaming"),
                    parent = parent,
                    coverSize = 40,
                ) {
                    playRadio(
                        id,
                        name,
                        if (stored) station.optString("streamUrl", station.optString("url"))
                        else "",
                        saved = stored,
                    )
                }
            row.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            row.background =
                ui.design.touch(
                    if (ui.design.theme == "classic") ui.surface else ui.design.content,
                    if (ui.design.theme == "classic") 8 else 20,
                    ui.design.theme != "classic",
                )
            (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12)
            (row.getChildAt(0) as android.widget.ImageView).apply {
                setImageDrawable(NativeIcon("radio", ui.muted, ui.design.theme))
                setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
            }
            if (stored)
                ui.iconButton(row, "trash-2", "Remove saved radio") {
                        ui.confirm("Remove saved radio", name) {
                            ui.request(
                                "/api/radio/${NativeApi.encode(id)}",
                                "DELETE",
                                done = {
                                    api.execute({
                                        api.request(
                                            "/api/recent-activity/radio/${NativeApi.encode(id)}",
                                            "DELETE",
                                        )
                                    }) { _, _ ->
                                        refresh()
                                    }
                                },
                            )
                        }
                    }
                    .color(0xffff6479.toInt())
            else
                ui.iconButton(row, "plus", "Save to library") {
                    ui.request(
                        "/api/radio/inject",
                        form = mapOf("channel_id" to id, "name" to name),
                        done = { refresh() },
                    )
                }
        }
        fun loadSaved() {
            api.execute({ api.request("/api/radio/list") }) { value, error ->
                if (ui.content !== owner) return@execute
                saved.removeAllViews()
                if (error != null) ui.label("Failed to load your radios.", 14, saved, ui.muted)
                else {
                    val list = NativeTrack.items(value)
                    if (list.isEmpty()) ui.empty("No saved radio stations yet.", "radio", saved)
                    for (station in list) stationRow(station, saved, true) { loadSaved() }
                }
            }
        }
        var serial = 0
        fun drawStations(stations: List<JSONObject>) {
            results.removeAllViews()
            if (stations.isEmpty()) ui.label("No signal found.", 14, results, ui.muted)
            for (station in stations) stationRow(station, results, false) { loadSaved() }
        }
        fun lookup() {
            val q = search.text.toString().trim().take(200)
            if (q.isEmpty()) return
            radioHub = q
            val ticket = ++serial
            results.removeAllViews()
            ui.label(ui.text("common.searching", "Searching…"), 14, results, ui.muted)
            api.execute({
                val hits =
                    NativeTrack.items(api.request("/api/radio/search?q=" + NativeApi.encode(q)))
                val first = hits.firstOrNull()?.optJSONObject("_source")
                if (first?.optString("type") == "place") {
                    val map = first.optJSONObject("page")?.optString("map").orEmpty()
                    require(map.isNotBlank() && map.split('/').none { it == "." || it == ".." })
                    NativeTrack.items(api.request("/api/radio/place/" + Uri.encode(map, "/")))
                        .mapNotNull { it.optJSONObject("page") }
                        .filter { !it.optString("url").endsWith("/channels") }
                } else
                    hits.mapNotNull {
                        it.optJSONObject("_source")
                            ?.takeIf { it.optString("type") == "channel" }
                            ?.optJSONObject("page")
                    }
            }) { value, error ->
                if (ui.content !== owner || ticket != serial) return@execute
                if (error != null) {
                    results.removeAllViews()
                    ui.label(error.message ?: "Connection error", 14, results, ui.muted)
                } else {
                    @Suppress("UNCHECKED_CAST") drawStations(value as List<JSONObject>)
                }
            }
        }
        submit.setOnClickListener { lookup() }
        search.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                lookup()
                true
            } else false
        }
        api.execute({ api.request("/api/radio/browse") }) { value, error ->
            if (ui.content !== owner) return@execute
            if (error != null)
                ui.label("Could not load editorial playlists.", 14, editorial, ui.muted)
            else
                ui.cards(
                    NativeTrack.items(value).map {
                        JSONObject(it.toString())
                            .put("thumbnail", it.optString("image"))
                            .put("grid_subtitle", "${it.optInt("count")} radios")
                    },
                    editorial,
                ) { entry, _ ->
                    val path =
                        entry.optJSONObject("page")?.optString("url").orEmpty().trimStart('/')
                    if (path.isBlank() || path.split('/').any { it == "." || it == ".." })
                        return@cards
                    val ticket = ++serial
                    api.execute({ api.request("/api/radio/playlist/" + Uri.encode(path, "/")) }) {
                        stations,
                        failure ->
                        if (ui.content !== owner || ticket != serial) return@execute
                        if (failure != null)
                            ui.toast(failure.message ?: "Could not load this radio playlist")
                        else
                            drawStations(
                                NativeTrack.items(stations)
                                    .map { it.optJSONObject("page") ?: it }
                                    .filter { it.optString("url").isNotBlank() }
                            )
                    }
                }
        }
        loadSaved()
        lookup()
    }

    fun lyrics() {
        ui.showPlayer("lyrics")
    }

    fun wrapped() = NativeWrapped(ui).open()

    fun createParty() {
        ui.sheet(ui.text("party.create_room", "Create party room")) { body, dialog ->
            var federated = false
            var limit = 5
            var playlist: Any = JSONObject.NULL
            val selected = mutableSetOf<Int>()
            ui.label("Room Type", 13, body, ui.muted)
            val type = ui.button("Local room", body) {}
            val peers =
                LinearLayout(ui.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = android.view.View.GONE
                }
            body.addView(peers)
            type.setOnClickListener {
                ui.choose("Room Type", listOf("Local room", "Federated room")) {
                    federated = it == 1
                    type.text = if (federated) "Federated room" else "Local room"
                    peers.visibility =
                        if (federated) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
            val name = ui.labelledField(ui.text("party.room_name", "Room name"), body)
            ui.label("User limit", 13, body, ui.muted)
            val count = ui.button(limit.toString(), body) {}
            count.setOnClickListener {
                ui.choose("User limit", (2..15).map { "$it people" }) {
                    limit = it + 2
                    count.text = "$limit people"
                }
            }
            ui.label("Start with a playlist", 13, body, ui.muted)
            val collection = ui.button("Start with an empty queue", body) {}
            collection.isEnabled = false
            val guests = ui.toggle("Allow guests to add songs", true, body) {}
            val status = ui.label("Loading playlists and federation peers…", 13, body, ui.muted)
            api.execute({
                JSONObject()
                    .put("playlists", api.request("/api/playlists"))
                    .put("peers", api.request("/api/party/peers"))
            }) { result, error ->
                if (!dialog.isShowing) return@execute
                if (error != null) status.text = error.message ?: "Could not load room options"
                else {
                    status.visibility = android.view.View.GONE
                    val data = result as JSONObject
                    val playlists =
                        NativeTrack.items(data.opt("playlists")).filter {
                            it.optBoolean("is_owner") && it.optString("id").toLongOrNull() != null
                        }
                    collection.isEnabled = true
                    collection.setOnClickListener {
                        ui.choose(
                            "Start with a playlist",
                            listOf("Start with an empty queue") +
                                playlists.map { it.optString("name") },
                        ) { index ->
                            playlist =
                                if (index == 0) JSONObject.NULL else playlists[index - 1].get("id")
                            collection.text =
                                if (index == 0) "Start with an empty queue"
                                else playlists[index - 1].optString("name")
                        }
                    }
                    val available =
                        NativeTrack.items(data.optJSONObject("peers")?.optJSONArray("peers"))
                    if (available.isEmpty())
                        ui.label("No federation peers available", 14, peers, ui.muted)
                    for (peer in available) {
                        val id = peer.optInt("id")
                        ui.toggle(
                            peer.optString("name", peer.optString("instance_name")),
                            false,
                            peers,
                        ) {
                            if (it) selected.add(id) else selected.remove(id)
                        }
                    }
                }
            }
            ui.button(ui.text("party.create_room", "Create Room"), body, true) {
                ui.request(
                    "/api/party/rooms",
                    body =
                        JSONObject()
                            .put(
                                "name",
                                name.text.toString().trim().take(80).ifBlank {
                                    "Party with ${api.username}"
                                },
                            )
                            .put("max_users", limit)
                            .put("allow_guests_queue", guests.isChecked)
                            .put("playlist_id", playlist)
                            .put("is_federated", federated)
                            .put(
                                "federated_peer_ids",
                                JSONArray(if (federated) selected.toList() else emptyList<Int>()),
                            ),
                    done = { result ->
                        dialog.dismiss()
                        val room = (result as JSONObject).optJSONObject("room") ?: result
                        ui.joinParty((room as JSONObject).get("id").toString())
                    },
                )
            }
        }
    }

    fun partyDetails(party: NativeParty, open: Boolean) {
        if (open) {
            ui.screen(
                "Party · ${party.room.optString("name")}",
                { ui.parties() },
                nav = "home",
                key = party.path,
                restore = {
                    if (player.party === party) partyDetails(party, true) else ui.parties()
                },
            )
            partyBody = ui.content
            val body = ui.content
            val owner = party.room.optBoolean("is_owner")
            ui.browseTabs("party")
            partyCover =
                android.widget
                    .ImageView(ui.activity)
                    .apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = ui.design.shape(ui.design.wash, 12)
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                    }
                    .also {
                        body.addView(
                            it,
                            LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply {
                                bottomMargin = ui.dp(16)
                            },
                        )
                    }
            ui.label(party.room.optString("name"), 44).apply {
                ui.design.lines(this, 1f)
                (layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = ui.dp(4)
                    bottomMargin = ui.dp(6)
                }
            }
            partyState = ui.label("", 14, color = ui.muted)
            partyParticipants =
                LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(
                partyParticipants,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) },
            )
            ui.button("Leave") {
                party.close()
                partyBody = null
                ui.parties()
            }
            ui.space(20)
            fun block(): LinearLayout =
                LinearLayout(ui.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
                    background =
                        ui.design.shape(
                            if (ui.design.theme == "classic") ui.background else ui.design.content,
                            if (ui.design.theme == "classic") 12 else 24,
                            true,
                        )
                    body.addView(
                        this,
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) },
                    )
                }
            val now = block()
            ui.label("NOW PLAYING", 12, now, ui.accent)
            partyNow =
                ui.label("", 25, now).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply {
                        topMargin = ui.dp(8)
                        bottomMargin = ui.dp(4)
                    }
                }
            partyArtist = ui.label("", 14, now, ui.muted)
            partyToggle = null
            if (owner) {
                val controls =
                    LinearLayout(ui.activity).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                now.addView(
                    controls,
                    LinearLayout.LayoutParams(-1, ui.dp(52)).apply { topMargin = ui.dp(20) },
                )
                for ((glyph, action) in
                    listOf(
                        "skip-back" to "previous",
                        "play" to "toggle",
                        "skip-forward" to "next",
                    )) {
                    val key =
                        ui.iconButton(
                            controls,
                            glyph,
                            if (action == "toggle") "Play or pause party" else action,
                        ) {
                            val command =
                                if (action == "toggle") {
                                    if (
                                        party.room.optString("playback_status") in
                                            listOf("playing", "loading")
                                    )
                                        "pause"
                                    else "play"
                                } else action
                            if (command == "pause") party.control("pause")
                            else
                                app.mediaController.command(
                                    when (command) {
                                        "previous" -> "previoustrack"
                                        "next" -> "nexttrack"
                                        else -> command
                                    }
                                )
                        }
                    (key.layoutParams as LinearLayout.LayoutParams).marginEnd = ui.dp(12)
                    key.background =
                        ui.design.touch(if (action == "toggle") ui.accent else ui.design.wash, 999)
                    if (action == "toggle") {
                        key.layoutParams =
                            LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply {
                                marginEnd = ui.dp(12)
                            }
                        key.color(ui.design.onAccent)
                        partyToggle = key
                    }
                }
            } else ui.label("Playback follows the host", 14, now, ui.muted)
            ui.sectionHeader("Shared queue")
            partyQueue = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            ui.content.addView(partyQueue)
            if (
                party.room.optBoolean(
                    "can_add_songs",
                    owner || party.room.optBoolean("allow_guests_queue"),
                )
            ) {
                val add = block()
                ui.label(ui.text("party.add_songs", "Add songs"), 22, add)
                ui.label(
                    ui.text("party.add_songs_desc", "Search tracks already in this Aoede library."),
                    14,
                    add,
                    ui.muted,
                )
                val search = ui.field(ui.text("party.search_placeholder", "Song or artist"), add)
                val results =
                    LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
                add.addView(results)
                var serial = 0
                search.addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun afterTextChanged(s: android.text.Editable?) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            val current = ++serial
                            val query = s.toString().trim().take(200)
                            if (query.isEmpty()) {
                                results.removeAllViews()
                                return
                            }
                            main.postDelayed(
                                {
                                    if (serial != current || ui.content !== body) return@postDelayed
                                    api.execute({
                                        api.request(
                                            party.path + "/tracks?q=" + NativeApi.encode(query)
                                        )
                                    }) { response, error ->
                                        if (serial != current || ui.content !== body) return@execute
                                        results.removeAllViews()
                                        if (error != null)
                                            ui.label(
                                                error.message ?: "Search failed",
                                                14,
                                                results,
                                                ui.muted,
                                            )
                                        else
                                            for (track in
                                                NativeTrack.items(
                                                    (response as? JSONObject)?.optJSONArray(
                                                        "tracks"
                                                    )
                                                )) {
                                                val row =
                                                    ui.row(
                                                        track.optString("title"),
                                                        track.optString("artist"),
                                                        ui.artworkPath(track),
                                                        results,
                                                        40,
                                                    ) {}
                                                row.setOnClickListener {
                                                    if (!row.isEnabled) return@setOnClickListener
                                                    row.isEnabled = false
                                                    party.add(track) { failure ->
                                                        if (ui.content === body) {
                                                            row.isEnabled = true
                                                            ui.toast(
                                                                failure?.message
                                                                    ?: "Added to the party queue"
                                                            )
                                                        }
                                                    }
                                                }
                                                ui.iconButton(row, "plus", "Add to party") {
                                                    row.performClick()
                                                }
                                            }
                                    }
                                },
                                250,
                            )
                        }
                    }
                )
            } else {
                val locked = block()
                ui.label("Host-managed queue", 18, locked)
                ui.label(
                    "${party.room.optString("owner_username")} has disabled guest additions.",
                    14,
                    locked,
                    ui.muted,
                )
            }
            if (owner)
                ui.button("Delete room") {
                    ui.confirm("Delete room", "End this party for everyone?") {
                        ui.request(
                            party.path,
                            "DELETE",
                            done = {
                                party.close()
                                partyBody = null
                                ui.parties()
                            },
                        )
                    }
                }
            partySignature = ""
        }
        if (ui.content !== partyBody) return
        val room = party.room
        partyState?.text =
            "Hosted by ${room.optString("owner_username")} · ${room.optInt("active_users")}/${room.optInt("max_users")} listening"
        val current = room.optJSONObject("current_track")
        partyNow?.text = current?.optString("title") ?: "Queue is waiting"
        partyArtist?.text = current?.optString("artist") ?: "Add a song to get started."
        partyCover?.let { image ->
            if (current == null) {
                image.tag = null
                image.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
                image.setImageDrawable(NativeIcon("radio", ui.ink, ui.design.theme))
            } else if (image.tag != ui.artworkPath(current)) {
                image.setPadding(0, 0, 0, 0)
                ui.artwork(image, ui.artworkPath(current))
            }
        }
        partyToggle?.apply {
            text =
                if (room.optString("playback_status") in listOf("playing", "loading")) "pause"
                else "play"
            color(ui.design.onAccent)
        }
        val items = NativeTrack.items(room.optJSONArray("queue"))
        val signature =
            room.optJSONArray("participants")?.toString().orEmpty() +
                room.optInt("current_index") +
                room.optJSONArray("queue")?.toString().orEmpty()
        if (signature != partySignature) {
            partySignature = signature
            partyParticipants?.let { parent ->
                parent.removeAllViews()
                ui.chipBar(
                    NativeTrack.items(room.optJSONArray("participants")).map {
                        it.optString("username") to {}
                    },
                    parent = parent,
                )
            }
            val list = partyQueue ?: return
            list.removeAllViews()
            ui.label("${items.size} songs", 13, list, ui.muted)
            if (items.isEmpty()) ui.label("The shared queue is empty.", 14, list, ui.muted)
            for ((index, item) in items.withIndex()) {
                val track = item.optJSONObject("track") ?: item
                val row =
                    ui.row(
                        track.optString("title"),
                        track.optString("artist") +
                            if (item.optString("added_by").isNotBlank())
                                " · added by ${item.optString("added_by")}"
                            else "",
                        ui.artworkPath(track),
                        list,
                        42,
                    ) {}
                row.isClickable = false
                row.isFocusable = false
                val number =
                    TextView(ui.activity).apply {
                        text = (index + 1).toString()
                        ui.design.typography(this, 13f)
                        setTextColor(
                            if (index == room.optInt("current_index")) ui.accent else ui.muted
                        )
                        gravity = android.view.Gravity.CENTER
                    }
                row.addView(
                    number,
                    0,
                    LinearLayout.LayoutParams(ui.dp(22), -1).apply { marginEnd = ui.dp(8) },
                )
                if (room.optBoolean("is_owner"))
                    ui.iconButton(row, "x", "Remove from party") {
                        ui.request(
                            party.path + "/queue/" + item.get("item_id"),
                            "DELETE",
                            done = {},
                        )
                    }
            }
        }
    }

    fun storage() {
        ui.screen("Device storage", { ui.offlineDownloads() })
        val records = app.nativeLibrary.list("track")
        ui.label(
            "${records.size} tracks · ${records.sumOf{it.optLong("offline_size")}/1024/1024} MB",
            20,
        )
        ui.button("Import previous installed library") {
            ui.confirm(
                "Import device downloads",
                "Import collections verified for this account. Original IndexedDB data will be retained. Unassigned legacy files will not be mixed into this account.",
            ) {
                ui.importLegacy()
            }
        }
        ui.button("Pending offline changes") { pending() }
        for (job in
            app.nativeLibrary.list("download").filter { it.optString("status") == "failed" }) ui
            .row(job.optString("title"), "Download failed · tap to retry") {
                ui.download(listOf(job))
            }
        for (record in records) ui.row(record.optString("title"), "Remove this device copy") {
            ui.confirm("Remove download", "This keeps the track on your server.") {
                app.nativeLibrary.remove("track", NativeTrack.reference(record))
                storage()
            }
        }
    }

    private fun pending() {
        ui.screen("Pending offline changes", { storage() })
        ui.button("Synchronize reviewed changes", primary = true) {
            app.nativeSync.flush { error -> if (error != null) ui.toast(error) else pending() }
        }
        for (action in app.nativeLibrary.list("pending")) ui.row(
            action.optString("type"),
            if (action.optBoolean("review_required")) "Legacy change · review before applying"
            else "Waiting to synchronize",
        ) {
            val type = action.optString("type")
            ui.confirm("Apply offline change", type) {
                app.nativeSync.flush(action.getString("_native_record_id")) { error ->
                    if (error != null) ui.toast(error) else pending()
                }
            }
        }
    }

    fun upload(path: String, field: String, uri: Uri, done: () -> Unit) {
        val owner = ui.content
        val scope = api.scope
        api.execute({ api.upload(path, field, uri) }) { _, error ->
            if (scope != api.scope) return@execute
            if (error == null) ui.refreshAvatar()
            if (ui.content !== owner) return@execute
            if (error != null) ui.toast(error.message ?: "Upload failed") else done()
        }
    }

    fun about() {
        ui.sheet("About & updates") { body, dialog ->
            val brand =
                LinearLayout(ui.activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            body.addView(
                brand,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(20) },
            )
            brand.addView(
                android.widget.ImageView(ui.activity).apply {
                    setImageBitmap(
                        app.assets
                            .open("web/assets/apple-touch-icon.png")
                            .use(android.graphics.BitmapFactory::decodeStream)
                    )
                    contentDescription = "Aoede"
                },
                LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { marginEnd = ui.dp(16) },
            )
            val copy = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            brand.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            ui.label("Aoede", 28, copy)
            val version = ui.label("Your music. Your server.", 13, copy, ui.muted)
            ui.button("Check Android update", body, true) {
                app.updater.check { app.updater.offer(ui.activity) }
            }
            val content = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(content)
            ui.label("Checking the latest release…", 14, content, ui.muted)
            api.execute({ api.request("/api/about") }) { value, error ->
                if (!dialog.isShowing) return@execute
                content.removeAllViews()
                val data = value as? JSONObject
                version.text =
                    "Server ${data?.optJSONObject("current")?.optString("display_version", "Unknown") ?: "Unknown"} · Android ${BuildConfig.VERSION_NAME}"
                val release = data?.optJSONObject("release")
                fun link(label: String, target: String, parent: LinearLayout) {
                    // Match the web About sheet: backend data cannot supply arbitrary install
                    // links.
                    if (
                        !Regex(
                                "https://github\\.com/sPROFFEs/Aoede/releases/(?:download/[A-Za-z0-9][A-Za-z0-9._%+\\-]*/[A-Za-z0-9][A-Za-z0-9.\\-]*|tag/[A-Za-z0-9][A-Za-z0-9._%+\\-]*)"
                            )
                            .matches(target)
                    )
                        return
                    ui.button(label, parent) {
                        ui.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    }
                }
                if (error != null || release == null)
                    ui.label(
                        "Release information is unavailable. Check your connection or visit All releases for downloads.",
                        14,
                        content,
                        ui.muted,
                    )
                else {
                    if (data.optBoolean("stale"))
                        ui.label(
                            "GitHub is unavailable. Showing the last successful check.",
                            14,
                            content,
                            ui.muted,
                        )
                    ui.sectionHeader("What’s new", content)
                    ui.label(release.optString("title"), 18, content)
                    ui.label(
                        release.optString(
                            "summary",
                            "See the release notes for the latest changes.",
                        ),
                        14,
                        content,
                        ui.muted,
                    )
                    link(
                        "Read release notes · ${release.optString("tag")}",
                        release.optString("url"),
                        content,
                    )
                    ui.space(16, content)
                    ui.sectionHeader("Get Aoede", content)
                    ui.label(
                        "Choose your platform. Downloads follow the latest published release.",
                        14,
                        content,
                        ui.muted,
                    )
                    val files =
                        LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
                    val keys = listOf("android", "mac", "win", "linux")
                    fun select(index: Int) {
                        files.removeAllViews()
                        val downloads =
                            NativeTrack.items(release.optJSONArray("downloads")).filter {
                                it.optString("platform") == keys[index]
                            }
                        for (item in downloads) link(
                            "${item.optString("label")}\n${item.optString("name")}",
                            item.optString("url"),
                            files,
                        )
                        if (downloads.isEmpty())
                            ui.label(
                                "No package is published for this platform yet. Check All releases for earlier builds.",
                                14,
                                files,
                                ui.muted,
                            )
                        if (index == 0)
                            ui.label(
                                "The wrapper is a backup client. Android clients share a package ID and cannot be installed side by side.",
                                12,
                                files,
                                ui.muted,
                            )
                    }
                    ui.tabs(listOf("Android", "macOS", "Windows", "Linux"), 0, content) {
                        select(it)
                    }
                    content.addView(files)
                    select(0)
                }
                ui.button("All releases", content) {
                    ui.activity.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/sPROFFEs/Aoede/releases"),
                        )
                    )
                }
            }
        }
    }

    fun admin(tab: String = "users") {
        if (!api.isAdmin) {
            ui.screen("Administration")
            ui.label("Administrator access required")
            return
        }
        when (tab) {
            "users" -> adminUsers()
            "federation" -> federation()
            else -> adminLibrary(tab)
        }
    }

    private fun adminHeader(tab: String) {
        ui.label(ui.text("admin.title", "Administration"), 28)
        ui.label("Accounts, library tools and federation, organized by task.", 14, color = ui.muted)
        ui.chipBar(
            listOf(
                "Download Manager" to { adminDownloads() },
                "System Monitor" to { system() },
                "Delete Inbox" to { adminDeletions() },
                "Home" to { ui.home() },
            ),
            top = 16,
            bottom = 20,
        )
        val keys = listOf("users", "library", "duplicates", "maintenance", "federation")
        ui.tabs(
            listOf("Users", "Library", "Duplicates", "Maintenance", "Federation"),
            keys.indexOf(tab),
        ) {
            admin(keys[it])
        }
    }

    private fun adminDeletions() {
        ui.load("Track deletion requests", "/api/mobile/v2/admin/delete-requests") { value ->
            for (r in NativeTrack.items(value)) ui.row(
                r.optString("track_title"),
                r.optString("requester") + " · " + r.optString("reason"),
            ) {
                ui.choose("Review request", listOf("Approve and delete track", "Reject")) { i ->
                    ui.prompt("Review note") { note ->
                        ui.request(
                            "/admin/song-delete-requests/${r.get("id")}/decision",
                            form =
                                mapOf(
                                    "decision" to if (i == 0) "approve" else "reject",
                                    "review_note" to note,
                                ),
                            done = { adminDeletions() },
                        )
                    }
                }
            }
        }
    }

    private fun adminUsers() {
        ui.load("Users", "/api/mobile/v2/admin/users") { value ->
            adminHeader("users")
            val create =
                ui.panel(
                    ui.text("admin.register_new", "Register new user"),
                    "Create a user account and optionally grant admin privileges.",
                    "user",
                )
            val username = ui.labelledField(ui.text("admin.username_label", "Username"), create)
            val password =
                ui.labelledField(ui.text("admin.password_label", "Password"), create, true)
            val role =
                ui.toggle(
                    ui.text("admin.admin_checkbox", "Administrator privileges"),
                    false,
                    create,
                ) {}
            ui.button(ui.text("admin.create_button", "Create user"), create, true) {
                ui.request(
                    "/admin/users/create",
                    form =
                        mapOf(
                            "username" to username.text.toString().trim(),
                            "password" to password.text.toString(),
                            "is_admin" to role.isChecked.toString(),
                        ),
                    done = {
                        password.text.clear()
                        adminUsers()
                    },
                )
            }
            val accounts =
                ui.panel(
                    ui.text("admin.active_accounts", "Active accounts"),
                    "Manage roles, login restrictions and account access.",
                    "users",
                )
            for (user in NativeTrack.items((value as JSONObject).optJSONArray("users"))) ui.row(
                    user.optString("username"),
                    if (user.optBoolean("is_admin")) "Administrator" else "Listener",
                    parent = accounts,
                    coverSize = 0,
                ) {
                    ui.choose(
                        user.optString("username"),
                        listOf(
                            "Login restriction information",
                            "Change role",
                            "Reset password",
                            "Delete user",
                        ),
                    ) { action ->
                        val id = user.get("id").toString()
                        when (action) {
                            0 -> loginRestriction(user)
                            1 ->
                                ui.confirm("Change role", user.optString("username")) {
                                    ui.request(
                                        "/admin/users/toggle-role",
                                        form = mapOf("user_id" to id),
                                        done = { adminUsers() },
                                    )
                                }
                            2 ->
                                ui.prompt("New password", password = true) {
                                    ui.request(
                                        "/admin/users/reset-password",
                                        form = mapOf("user_id" to id, "new_password" to it),
                                        done = { adminUsers() },
                                    )
                                }
                            3 ->
                                ui.confirm(
                                    "Delete user",
                                    "Permanently remove this user and their server data?",
                                ) {
                                    ui.request(
                                        "/admin/users/delete",
                                        form = mapOf("user_id" to id),
                                        done = { adminUsers() },
                                    )
                                }
                        }
                    }
                }
                .apply {
                    ui.iconButton(
                        this,
                        "info",
                        "Login restriction for ${user.optString("username")}",
                    ) {
                        loginRestriction(user)
                    }
                }
        }
    }

    private fun loginRestriction(user: JSONObject) {
        val owner = ui.content
        api.execute({ api.request("/admin/api/users/${user.getLong("id")}/login-lock") }) {
            value,
            error ->
            if (ui.content !== owner) return@execute
            val state = value as? JSONObject
            val seconds = state?.optInt("remaining_seconds")?.coerceAtLeast(0) ?: 0
            ui.toast(
                error?.message
                    ?: "${user.optString("username")} · " +
                        if (state?.optBoolean("blocked") == true)
                            "Login temporarily blocked · ${seconds / 60}m ${seconds % 60}s remaining"
                        else "No active login restriction"
            )
        }
    }

    private var systemTab = 0

    private fun system() {
        ui.load(
            ui.text("monitor.title", "System monitor"),
            "/api/mobile/v2/admin/system",
            stableKey = systemTab.toString(),
        ) { value ->
            val data = value as JSONObject
            val owner = ui.content
            monitorPolls.forEach { main.removeCallbacks(it.second) }
            monitorPolls.clear()
            ui.label(ui.text("monitor.header", "System Monitor"), 28)
            ui.label(
                "Server health, updates, backups and scheduled operations.",
                14,
                color = ui.muted,
            )
            ui.chipBar(
                listOf("User Admin" to { admin() }, "Download Manager" to { adminDownloads() }),
                top = 16,
                bottom = 20,
            )
            val groups = mutableListOf<LinearLayout>()
            ui.tabs(
                listOf("Overview", "Statistics", "Updates", "Backups", "Wrapped", "Jobs"),
                systemTab,
                icons =
                    listOf(
                        "activity",
                        "chart-column",
                        "refresh-cw",
                        "archive",
                        "sparkles",
                        "list-checks",
                    ),
            ) { selected ->
                systemTab = selected
                groups.forEachIndexed { i, v ->
                    v.visibility =
                        if (i == selected) android.view.View.VISIBLE else android.view.View.GONE
                }
                monitorPolls.forEach {
                    main.removeCallbacks(it.second)
                    main.post(it.second)
                }
            }
            fun group() =
                LinearLayout(ui.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility =
                        if (groups.size == systemTab) android.view.View.VISIBLE
                        else android.view.View.GONE
                    groups.add(this)
                    owner.addView(this)
                }
            val overview = group()
            val metrics = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            overview.addView(metrics)
            monitorPolling(overview, owner, 10000, { "/admin/api/system-stats" }) { result ->
                metrics.removeAllViews()
                val ram = result.optJSONObject("ram") ?: JSONObject()
                val pool = result.optJSONObject("pool") ?: JSONObject()
                monitorMetric(
                    metrics,
                    "CPU load",
                    result.optDouble("cpu_usage", 0.0),
                    "Current host usage",
                    "cpu",
                    0xff2fbf71.toInt(),
                )
                monitorMetric(
                    metrics,
                    "RAM usage",
                    ram.optDouble("percent", 0.0),
                    "${ram.optDouble("used_gb", 0.0)}GB / ${ram.optDouble("total_gb", 0.0)}GB",
                    "layers",
                    0xff8b5cf6.toInt(),
                )
                monitorMetric(
                    metrics,
                    "Storage pool",
                    pool.optDouble("percent", 0.0),
                    "${pool.optDouble("used", 0.0)}GB used of ${pool.optDouble("limit", 0.0)}GB",
                    "hard-drive",
                    if (pool.optDouble("percent") > 90) 0xffdc2626.toInt() else 0xff3b82f6.toInt(),
                )
            }
            summary(
                data.optJSONObject("build") ?: JSONObject(),
                ui.panel("Build", "Installed Aoede server build.", "info", overview),
            )
            val operations =
                ui.panel("Operations", "Server storage and scheduled work.", "settings", overview)
            val limit =
                ui.labelledField("Pool limit (GB)", operations).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
            api.execute({ api.request("/admin/api/system-stats") }) { value, error ->
                if (ui.content === owner && error == null && limit.text.isEmpty())
                    limit.setText(
                        (value as? JSONObject)
                            ?.optJSONObject("pool")
                            ?.opt("limit")
                            ?.toString()
                            .orEmpty()
                    )
            }
            ui.button("Apply pool limit", operations, true) {
                val value = limit.text.toString().toIntOrNull()
                if (value == null || value < 1) limit.error = "Enter a positive storage limit"
                else
                    ui.request(
                        "/admin/system/pool-limit",
                        form = mapOf("limit_gb" to value.toString()),
                        done = { system() },
                    )
            }
            ui.button("Purge storage residue", operations) {
                ui.confirm(
                    "Purge storage residue",
                    "Remove unreferenced storage residue? Active library tracks are retained.",
                ) {
                    ui.request("/admin/system/purge-storage", done = { system() })
                }
            }
            val stats = group()
            userStatistics(stats, owner)
            val updates = group()
            val updater =
                ui.panel(
                    "Updates",
                    "Check and apply the latest Aoede server update.",
                    "refresh-cw",
                    updates,
                )
            val updateStatus =
                LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            updater.addView(updateStatus)
            summary(data.optJSONObject("updates") ?: JSONObject(), updateStatus)
            monitorPolling(updates, owner, 10000, { "/api/mobile/v2/admin/system" }) { state ->
                updateStatus.removeAllViews()
                summary(state.optJSONObject("updates") ?: JSONObject(), updateStatus)
            }
            ui.button("Check server update", updater, true) {
                startAdminJob("/admin/system/updates/check", "Checking server updates")
            }
            ui.button("Apply server update", updater) {
                ui.confirm("Update server", "Back up the server and apply the available update?") {
                    startAdminJob("/admin/system/updates/apply", "Updating Aoede")
                }
            }
            val backups = group()
            val backup =
                ui.panel(
                    "Backups",
                    "Create a snapshot or restore a retained server backup.",
                    "archive",
                    backups,
                )
            var latestBackup = data.optJSONObject("backups") ?: JSONObject()
            val slots = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            backup.addView(slots)
            val restore = ui.button("Restore backup", backup) {}
            fun renderBackups(state: JSONObject) {
                latestBackup = state
                slots.removeAllViews()
                for (name in listOf("current", "previous")) {
                    val stored = state.optJSONObject(name)
                    ui.label(name.replaceFirstChar(Char::uppercase), 16, slots)
                    if (stored == null)
                        ui.label("No backup created for this slot yet.", 14, slots, ui.muted)
                    else {
                        ui.label(stored.optString("filename"), 14, slots)
                        ui.label(
                            "${stored.optString("size_label")} · ${stored.optString("created_at")} · ${if (stored.optBoolean("exists")) "Available" else "Missing file"}",
                            13,
                            slots,
                            ui.muted,
                        )
                    }
                    ui.space(12, slots)
                }
                restore.isEnabled =
                    listOf("current", "previous").any {
                        state.optJSONObject(it)?.optBoolean("exists") == true
                    }
            }
            renderBackups(latestBackup)
            restore.setOnClickListener {
                val available =
                    listOf("current", "previous").filter {
                        latestBackup.optJSONObject(it)?.optBoolean("exists") == true
                    }
                ui.choose("Restore slot", available) { slot ->
                    val selected = available.getOrNull(slot)
                    if (selected != null)
                        ui.confirm(
                            "Restore server",
                            "Restore the $selected backup and replace current server data?",
                        ) {
                            ui.request(
                                "/admin/system/backups/restore",
                                form = mapOf("slot" to selected),
                                done = { system() },
                            )
                        }
                }
            }
            ui.button("Create backup", backup, true) {
                ui.request("/admin/system/backups/create", done = { system() })
            }
            monitorPolling(backups, owner, 10000, { "/api/mobile/v2/admin/system" }) { status ->
                status.optJSONObject("backups")?.let(::renderBackups)
            }
            val backupState = data.optJSONObject("backups") ?: JSONObject()
            ui.space(16, backup)
            val enabled =
                ui.toggle(
                    "Enable daily autobackup",
                    backupState.optBoolean("autobackup_enabled"),
                    backup,
                ) {}
            val hour =
                ui.labelledField(
                        "Hour",
                        backup,
                        value = backupState.optInt("autobackup_hour").toString(),
                    )
                    .apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            val minute =
                ui.labelledField(
                        "Minute",
                        backup,
                        value = backupState.optInt("autobackup_minute").toString(),
                    )
                    .apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            val zone =
                ui.labelledField(
                    "Timezone",
                    backup,
                    value = backupState.optString("autobackup_timezone", "UTC"),
                )
            ui.button("Save backup settings", backup, true) {
                val h = hour.text.toString().toIntOrNull()
                val m = minute.text.toString().toIntOrNull()
                val tz = zone.text.toString().trim()
                if (h == null || h !in 0..23) hour.error = "Choose an hour from 0 to 23"
                else if (m == null || m !in 0..59) minute.error = "Choose a minute from 0 to 59"
                else if (
                    tz !in java.util.TimeZone.getAvailableIDs() &&
                        tz != backupState.optString("autobackup_timezone")
                )
                    zone.error = "Enter a valid timezone, for example Europe/Madrid"
                else
                    ui.request(
                        "/admin/system/backups/settings",
                        form =
                            mapOf(
                                "autobackup_enabled" to enabled.isChecked.toString(),
                                "autobackup_hour" to h.toString(),
                                "autobackup_minute" to m.toString(),
                                "autobackup_timezone" to tz,
                            ),
                        done = { system() },
                    )
            }
            val wrapped = group()
            val schedule =
                ui.panel(
                    "Wrapped",
                    "Controls whether the yearly Wrapped experience is visible in the app.",
                    "sparkles",
                    wrapped,
                )
            val configuration = data.optJSONObject("wrapped") ?: JSONObject()
            val visible =
                ui.toggle(
                    "Show Wrapped in the app",
                    configuration.optBoolean("enabled"),
                    schedule,
                ) {}
            val fromDate =
                scheduleField(
                    "Visible from date",
                    configuration.optString("visible_from_date_input"),
                    schedule,
                )
            val fromTime =
                scheduleField(
                    "Visible from time",
                    configuration.optString("visible_from_time_input"),
                    schedule,
                    time = true,
                )
            val untilDate =
                scheduleField(
                    "Visible until date",
                    configuration.optString("visible_until_date_input"),
                    schedule,
                )
            val untilTime =
                scheduleField(
                    "Visible until time",
                    configuration.optString("visible_until_time_input"),
                    schedule,
                    time = true,
                )
            val message =
                ui.labelledField(
                        "Artist clip message",
                        schedule,
                        value = configuration.optString("artist_clip_message"),
                    )
                    .apply {
                        isSingleLine = false
                        minLines = 3
                        gravity = android.view.Gravity.TOP
                        inputType =
                            android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
                        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                        (parent as android.widget.FrameLayout).layoutParams.height = ui.dp(100)
                    }
            ui.button("Save Wrapped", schedule, true) {
                ui.request(
                    "/admin/system/wrapped/settings",
                    form =
                        mapOf(
                            "wrapped_enabled" to visible.isChecked.toString(),
                            "wrapped_visible_from_date" to fromDate.text.toString(),
                            "wrapped_visible_from_time" to fromTime.text.toString(),
                            "wrapped_visible_until_date" to untilDate.text.toString(),
                            "wrapped_visible_until_time" to untilTime.text.toString(),
                            "wrapped_artist_clip_message" to message.text.toString(),
                        ),
                    done = { system() },
                )
            }
            ui.label(
                "Current state: ${if (configuration.optBoolean("visible")) "visible" else if (configuration.optBoolean("enabled")) "scheduled/hidden by date" else "disabled"}. Leave dates empty to show immediately while enabled. Schedule uses ${configuration.optString("timezone", "UTC")}.",
                13,
                schedule,
                ui.muted,
            )
            ui.space(16, schedule)
            ui.label(
                "Operational year (automatic): ${configuration.optInt("year", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))}",
                14,
                schedule,
                ui.muted,
            )
            ui.button("Regenerate Wrapped", schedule) {
                ui.request("/admin/system/wrapped/regenerate", done = { system() })
            }
            ui.button("Reset user Wrapped data", schedule) {
                ui.sheet("Reset user Wrapped data") { body, dialog ->
                    ui.label("Loading users…", 14, body, ui.muted)
                    api.execute({ api.request("/api/mobile/v2/admin/users") }) { result, error ->
                        if (!dialog.isShowing) return@execute
                        body.removeAllViews()
                        if (error != null) {
                            ui.label(error.message ?: "Users unavailable", 14, body, ui.muted)
                            return@execute
                        }
                        val users = NativeTrack.items((result as JSONObject).optJSONArray("users"))
                        var username = users.firstOrNull()?.optString("username").orEmpty()
                        val select = ui.button(username.ifBlank { "No users" }, body) {}
                        select.setOnClickListener {
                            ui.choose("User", users.map { it.optString("username") }) {
                                username = users[it].optString("username")
                                select.text = username
                            }
                        }
                        val purge = ui.toggle("Also reset listening history", false, body) {}
                        ui.button("Reset Wrapped data", body, true) {
                                if (username.isNotBlank())
                                    ui.confirm(
                                        "Reset Wrapped data",
                                        "Delete Wrapped aggregates${if (purge.isChecked) " and listening history" else ""} for $username?",
                                    ) {
                                        ui.request(
                                            "/admin/system/wrapped/reset-user",
                                            form =
                                                mapOf(
                                                    "wrapped_username" to username,
                                                    "wrapped_reset_purge_tracking" to
                                                        purge.isChecked.toString(),
                                                ),
                                            done = {
                                                dialog.dismiss()
                                                system()
                                            },
                                        )
                                    }
                            }
                            .isEnabled = users.isNotEmpty()
                    }
                }
            }
            val jobs = group()
            val history =
                ui.panel(
                    "Recent Admin Jobs",
                    "Status and results of server operations.",
                    "list-music",
                    jobs,
                )
            val historyRows =
                LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            history.addView(historyRows)
            fun renderJobs(status: JSONObject) {
                historyRows.removeAllViews()
                val entries = NativeTrack.items(status.optJSONArray("jobs"))
                if (entries.isEmpty()) ui.label("No recent admin jobs.", 14, historyRows, ui.muted)
                for (job in entries) ui.row(
                    job.optString("message", job.optString("job_type")),
                    job.optString("status"),
                    parent = historyRows,
                ) {
                    adminJob(job.getInt("id"))
                }
            }
            renderJobs(data)
            monitorPolling(jobs, owner, 10000, { "/api/mobile/v2/admin/system" }, ::renderJobs)
        }
    }

    private fun scheduleField(
        title: String,
        value: String,
        parent: LinearLayout,
        time: Boolean = false,
    ): android.widget.EditText {
        val owner = ui.content
        val input =
            ui.labelledField(title, parent, value = value.takeUnless { it == "null" }.orEmpty())
                .apply {
                    isFocusable = false
                    isClickable = true
                    setPadding(ui.dp(14), 0, ui.dp(50), 0)
                    setOnClickListener {
                        val parts =
                            text.toString().split(if (time) ':' else '-').mapNotNull {
                                it.toIntOrNull()
                            }
                        val calendar = java.util.Calendar.getInstance()
                        if (time)
                            ui.showDialog(
                                android.app.TimePickerDialog(
                                    ui.activity,
                                    { _, hour, minute ->
                                        if (ui.content === owner)
                                            setText(
                                                String.format(
                                                    java.util.Locale.ROOT,
                                                    "%02d:%02d",
                                                    hour,
                                                    minute,
                                                )
                                            )
                                    },
                                    parts.getOrNull(0)
                                        ?: calendar.get(java.util.Calendar.HOUR_OF_DAY),
                                    parts.getOrNull(1) ?: calendar.get(java.util.Calendar.MINUTE),
                                    true,
                                )
                            )
                        else
                            ui.showDialog(
                                android.app.DatePickerDialog(
                                    ui.activity,
                                    { _, year, month, day ->
                                        if (ui.content === owner)
                                            setText(
                                                String.format(
                                                    java.util.Locale.ROOT,
                                                    "%04d-%02d-%02d",
                                                    year,
                                                    month + 1,
                                                    day,
                                                )
                                            )
                                    },
                                    parts.getOrNull(0) ?: calendar.get(java.util.Calendar.YEAR),
                                    (parts.getOrNull(1)?.minus(1)
                                            ?: calendar.get(java.util.Calendar.MONTH))
                                        .coerceIn(0, 11),
                                    (parts.getOrNull(2)
                                            ?: calendar.get(java.util.Calendar.DAY_OF_MONTH))
                                        .coerceIn(1, 31),
                                )
                            )
                    }
                }
        val frame = input.parent as android.widget.FrameLayout
        frame.addView(
            NativeIconButton(ui.activity, ui.muted, ui.design.theme).apply {
                text = "x"
                contentDescription = "Clear $title"
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                background = null
                setOnClickListener { input.text.clear() }
            },
            android.widget.FrameLayout.LayoutParams(ui.dp(44), -1, android.view.Gravity.END),
        )
        return input
    }

    private fun monitorPolling(
        panel: LinearLayout,
        owner: LinearLayout,
        interval: Long,
        path: () -> String,
        render: (JSONObject) -> Unit,
    ): Runnable {
        monitorPolls.removeAll { (previous, poll) ->
            if (previous === owner) false
            else {
                main.removeCallbacks(poll)
                true
            }
        }
        val notice = ui.label("", 13, panel, ui.muted).apply { visibility = android.view.View.GONE }
        var serial = 0
        val poll =
            object : Runnable {
                override fun run() {
                    main.removeCallbacks(this)
                    if (
                        !foreground ||
                            ui.content !== owner ||
                            panel.visibility != android.view.View.VISIBLE
                    )
                        return
                    val ticket = ++serial
                    val requestPath = path()
                    api.execute({
                        try {
                            api.request(requestPath)
                        } catch (error: java.io.IOException) {
                            val monitor =
                                Regex("/updater/api/jobs/([1-9][0-9]*)\\?access=[a-f0-9]{64}")
                                    .matchEntire(requestPath)
                            if (monitor == null) throw error
                            api.request("/admin/api/system/jobs/${monitor.groupValues[1]}")
                        }
                    }) { result, error ->
                        if (
                            !foreground ||
                                ui.content !== owner ||
                                panel.visibility != android.view.View.VISIBLE ||
                                ticket != serial
                        )
                            return@execute
                        if (error != null) {
                            notice.text = error.message ?: "Statistics unavailable"
                            notice.visibility = android.view.View.VISIBLE
                        } else if (result is JSONObject) {
                            notice.visibility = android.view.View.GONE
                            render(result)
                        }
                        val finished =
                            (requestPath.startsWith("/admin/api/system/jobs/") ||
                                requestPath.startsWith("/updater/api/jobs/")) &&
                                (result as? JSONObject)?.optString("status") in
                                    listOf("completed", "failed", "cancelled")
                        if (
                            !finished &&
                                (error !is NativeApi.Failure || error.code !in listOf(401, 403))
                        )
                            main.postDelayed(this, interval)
                    }
                }
            }
        monitorPolls.add(owner to poll)
        main.post(poll)
        return poll
    }

    private fun monitorMetric(
        parent: LinearLayout,
        caption: String,
        percent: Double,
        detail: String,
        glyph: String,
        color: Int,
    ) {
        val card =
            LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22))
                background =
                    ui.design.shape(
                        if (ui.design.theme == "classic") 0xff17191f.toInt() else ui.design.content,
                        16,
                        true,
                    )
            }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })
        val header =
            LinearLayout(ui.activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        card.addView(header)
        ui.label(caption, 13, header, ui.muted).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        header.addView(
            android.widget.ImageView(ui.activity).apply {
                setImageDrawable(NativeIcon(glyph, ui.muted, ui.design.theme))
            },
            LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)),
        )
        val number = if (percent.isFinite()) percent.coerceIn(0.0, 100.0) else 0.0
        ui.label("${String.format(java.util.Locale.US, "%.1f", number)}%", 35, card)
        ui.label(detail, 13, card, ui.muted)
        card.addView(
            android.widget
                .ProgressBar(ui.activity, null, android.R.attr.progressBarStyleHorizontal)
                .apply {
                    max = 1000
                    progress = (number * 10).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(color)
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(ui.design.border)
                },
            LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(14) },
        )
    }

    private fun listeningTime(seconds: Double): String {
        val minutes = (seconds.coerceAtLeast(0.0) / 60 + .5).toLong()
        return if (minutes < 60) "${minutes}m"
        else "${minutes/60}h" + if (minutes % 60 == 0L) "" else " ${minutes%60}m"
    }

    private fun userStatistics(panel: LinearLayout, owner: LinearLayout) {
        val body =
            ui.panel(
                "User listening statistics",
                "Listening activity, collections and recent music for regular users.",
                "chart-column",
                panel,
            )
        val periods = listOf("24h", "7d", "30d", "year", "all")
        val periodNames =
            listOf("Last 24 hours", "Last 7 days", "Last 30 days", "This year", "All time")
        val sorts = listOf("listening_seconds", "qualified_listens", "last_listen_at", "username")
        val sortNames = listOf("Listening time", "Listens", "Last listen", "Username")
        var period = 2
        var sort = 0
        var offset = 0
        val filters = LinearLayout(ui.activity)
        body.addView(filters)
        val periodButton = ui.button(periodNames[period], filters) {}
        val sortButton = ui.button(sortNames[sort], filters) {}
        for (button in listOf(periodButton, sortButton)) button.layoutParams =
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
        val rows = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        lateinit var poll: Runnable
        fun refresh() {
            main.removeCallbacks(poll)
            main.post(poll)
        }
        periodButton.setOnClickListener {
            ui.choose("Period", periodNames) {
                period = it
                offset = 0
                periodButton.text = periodNames[it]
                refresh()
            }
        }
        sortButton.setOnClickListener {
            ui.choose("Sort", sortNames) {
                sort = it
                offset = 0
                sortButton.text = sortNames[it]
                refresh()
            }
        }
        ui.button("Refresh", body) { refresh() }
        body.addView(rows)
        poll =
            monitorPolling(
                panel,
                owner,
                30000,
                {
                    "/admin/api/user-statistics?period=${periods[period]}&sort=${sorts[sort]}&order=${if (sorts[sort] == "username") "asc" else "desc"}&limit=25&offset=$offset"
                },
            ) { data ->
                rows.removeAllViews()
                val totals = data.optJSONObject("totals") ?: JSONObject()
                val totalsRow = NativeFlowLayout(ui.activity, ui.dp(12))
                rows.addView(totalsRow)
                for ((caption, amount) in
                    listOf(
                        "Users" to totals.optInt("users").toString(),
                        "Active accounts" to totals.optInt("active_users").toString(),
                        "Qualified listens" to totals.optInt("qualified_listens").toString(),
                        "Listening time" to
                            listeningTime(totals.optDouble("listening_seconds", 0.0)),
                    )) {
                    totalsRow.addView(
                        LinearLayout(ui.activity).apply {
                            orientation = LinearLayout.VERTICAL
                            minimumWidth =
                                ui.dp(
                                    (ui.activity.resources.configuration.screenWidthDp -
                                            ui.design.gutter * 2 -
                                            68)
                                        .coerceAtLeast(180) / 2
                                )
                            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                            background = ui.design.shape(ui.design.wash, 10, true)
                            ui.label(caption, 13, this, ui.muted)
                            ui.label(amount, 24, this)
                        }
                    )
                }
                val users = NativeTrack.items(data.optJSONArray("users"))
                if (users.isEmpty()) ui.empty("No regular users found.", parent = rows)
                for (user in users) {
                    val card =
                        ui.panel(
                            user.optString("username"),
                            if (user.optBoolean("is_active")) "Active" else "Inactive",
                            "user",
                            rows,
                        )
                    ui.label("Listening", 12, card, ui.muted)
                    ui.label(
                        "${listeningTime(user.optDouble("listening_seconds", 0.0))} · ${user.optInt("qualified_listens")} listens · ${user.optInt("unique_tracks")} tracks",
                        14,
                        card,
                    )
                    ui.label("Collection", 12, card, ui.muted)
                    ui.label(
                        "${user.optInt("playlist_count")} playlists · ${user.optInt("favorite_count")} favorites",
                        14,
                        card,
                    )
                    ui.label("Top music", 12, card, ui.muted)
                    val top = user.optJSONObject("top_track")
                    ui.label(
                        if (top == null) "No qualified listens"
                        else "${top.optString("title")} · ${top.optString("artist")}",
                        14,
                        card,
                    )
                    ui.label("Last listen", 12, card, ui.muted)
                    ui.label(
                        user.optString("last_listen_at").takeIf { it.isNotBlank() && it != "null" }
                            ?: "Never",
                        14,
                        card,
                    )
                }
                val total = data.optJSONObject("pagination")?.optInt("total") ?: users.size
                val pages = LinearLayout(ui.activity)
                rows.addView(pages)
                for ((title, next) in
                    listOf("Previous" to (offset - 25).coerceAtLeast(0), "Next" to offset + 25)) ui
                    .button(title, pages) {
                        offset = next
                        refresh()
                    }
                    .apply {
                        layoutParams =
                            LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply {
                                marginEnd = ui.dp(8)
                            }
                        isEnabled = if (title == "Previous") offset > 0 else offset + 25 < total
                    }
                ui.label(
                    "${if (total == 0) 0 else offset+1}–${minOf(offset+users.size, total)} of $total",
                    13,
                    rows,
                    ui.muted,
                )
            }
    }

    private var startingJob = false

    private fun adminJobId(response: JSONObject?): Int? =
        response?.optInt("job_id")?.takeIf { it > 0 }
            ?: Regex("/admin/system/updates/jobs/([1-9][0-9]*)")
                .matchEntire(Uri.parse(response?.optString("redirect").orEmpty()).path.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

    fun followAdminJob(value: Any?): Boolean {
        if (!api.isAdmin) return false
        val id = adminJobId(value as? JSONObject) ?: return false
        adminJob(id)
        return true
    }

    private fun startAdminJob(path: String, title: String) {
        if (startingJob) return
        startingJob = true
        val owner = ui.content
        val scope = api.scope
        ui.toast("Starting operation…")
        api.execute({ api.request(path, "POST") }) { value, error ->
            startingJob = false
            if (ui.content !== owner || api.scope != scope) return@execute
            val response = value as? JSONObject
            val id = adminJobId(response)
            if (error != null || id == null)
                ui.toast(error?.message ?: "The server did not return an operation ID")
            else adminJob(id, title)
        }
    }

    private fun adminJob(id: Int, title: String = "Operation progress") {
        val path = "/admin/api/system/jobs/$id"
        var endpoint = path
        ui.screen(title, { system() }, key = path, restore = { adminJob(id, title) })
        val owner = ui.content
        val body =
            ui.panel(title, "Progress remains visible while server services restart.", "activity")
        val phase = ui.label("Queued", 20, body)
        val message = ui.label("Connecting to the operation…", 14, body, ui.muted)
        val bar =
            android.widget
                .ProgressBar(ui.activity, null, android.R.attr.progressBarStyleHorizontal)
                .apply {
                    max = 100
                    isIndeterminate = true
                    progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                }
        body.addView(
            bar,
            LinearLayout.LayoutParams(-1, ui.dp(8)).apply {
                topMargin = ui.dp(16)
                bottomMargin = ui.dp(12)
            },
        )
        val percent = ui.label("Waiting for progress", 13, body, ui.muted)
        val result = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(result)
        var rendered = false
        monitorPolling(owner, owner, 1500, { endpoint }) { job ->
            job.optString("monitor_endpoint")
                .takeIf { Regex("/updater/api/jobs/$id\\?access=[a-f0-9]{64}").matches(it) }
                ?.let { endpoint = it }
            val status = job.optString("status", "queued")
            val details = job.optJSONObject("details") ?: JSONObject()
            val progress = details.optInt("progress").coerceIn(0, 100)
            phase.text =
                details
                    .optString("phase", status)
                    .replace('_', ' ')
                    .replaceFirstChar(Char::uppercase)
            message.text = job.optString("message").take(1000)
            bar.isIndeterminate = status == "queued" && progress == 0
            bar.progress = if (status == "completed") 100 else progress
            percent.text = "${bar.progress}% · $status"
            if (!rendered && status in listOf("completed", "failed", "cancelled")) {
                rendered = true
                bar.isIndeterminate = false
                details.optJSONObject("result")?.let { data ->
                    if (data.has("groups")) duplicateResults(data, result)
                    else summary(data, result)
                    for (track in NativeTrack.items(data.optJSONArray("missing_files"))) adminTrack(
                        track,
                        result,
                    )
                }
                ui.button("Back to system monitor", result) { system() }
                ui.button("Library tools", result) { admin("maintenance") }
            }
        }
    }

    private fun adminTrack(track: JSONObject, parent: LinearLayout, deleted: () -> Unit = {}) {
        val line =
            ui.row(
                track.optString("title"),
                track.optString("artist"),
                track.optString("thumbnail", "/api/cover/${track.optInt("id")}"),
                parent,
                coverSize = 44,
            ) {
                if (player.party != null) ui.toast("Leave the party first")
                else if (track.optBoolean("file_exists", true)) player.play(listOf(track))
                else ui.toast("This recording's file is missing")
            }
        ui.iconButton(line, "trash-2", "Delete server recording") {
            ui.confirm(
                "Delete server recording",
                "Permanently remove ${track.optString("title")} from the pool, playlists and favorites?",
            ) {
                ui.request(
                    "/admin/api/library/track/${track.getInt("id")}",
                    "DELETE",
                    done = {
                        parent.removeView(line)
                        deleted()
                    },
                )
            }
        }
    }

    private fun duplicateResults(data: JSONObject, parent: LinearLayout) {
        val groups = NativeTrack.items(data.optJSONArray("groups"))
        val removed = mutableSetOf<Long>()
        ui.label(
            "${groups.size} groups · ${data.optInt("exact_count")} exact · ${data.optInt("possible_count")} possible · ${data.optInt("similar_count")} similar",
            16,
            parent,
        )
        ui.label(
            "Suggested copies favor an available file, more playlist references and then the oldest entry. Listen before deleting possible matches.",
            14,
            parent,
            ui.muted,
        )
        val query = ui.labelledField("Filter song, artist, album or filename", parent)
        var type = "all"
        var limit = 20
        val typeButton = ui.button("All candidates", parent) {}
        val count = ui.label("", 13, parent, ui.muted)
        val rows = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(rows)
        fun render() {
            rows.removeAllViews()
            val text = query.text.toString().trim()
            val matches =
                groups.mapNotNull { group ->
                    val tracks = NativeTrack.items(group).filter { it.optLong("id") !in removed }
                    if (tracks.size < 2 || type != "all" && group.optString("match_type") != type)
                        null
                    else if (
                        text.isNotEmpty() &&
                            tracks.none { track ->
                                listOf("title", "artist", "album", "filename").any {
                                    track.optString(it).contains(text, true)
                                }
                            }
                    )
                        null
                    else group to tracks
                }
            count.text = "Showing ${minOf(limit, matches.size)} of ${matches.size} groups"
            for ((group, tracks) in matches.take(limit)) {
                val reasons = group.optJSONArray("reasons") ?: JSONArray()
                val panel =
                    ui.panel(
                        group.optString("match_type", "possible").replaceFirstChar(Char::uppercase),
                        (0 until reasons.length()).joinToString(" · ") { reasons.optString(it) },
                        "copy",
                        rows,
                    )
                for (track in tracks) {
                    val recommended = group.optLong("recommended_track_id") == track.optLong("id")
                    if (recommended) ui.label("Suggested copy to keep", 13, panel, ui.accent)
                    val seconds =
                        track
                            .optDouble("duration", 0.0)
                            .takeIf { it.isFinite() }
                            ?.toInt()
                            ?.coerceAtLeast(0) ?: 0
                    ui.label(
                        "${track.optString("format")} · ${seconds/60}:${(seconds%60).toString().padStart(2,'0')} · ${track.optInt("playlist_count")} collections · ${if(track.optBoolean("file_exists")) "File available" else "File missing"}",
                        12,
                        panel,
                        ui.muted,
                    )
                    adminTrack(track, panel) {
                        removed.add(track.getLong("id"))
                        render()
                    }
                }
            }
            if (limit < matches.size)
                ui.button("Show more", rows) {
                    limit += 20
                    render()
                }
        }
        typeButton.setOnClickListener {
            val types = listOf("all", "exact", "possible", "similar")
            ui.choose("Match type", listOf("All candidates", "Exact", "Possible", "Similar")) {
                type = types[it]
                limit = 20
                typeButton.text =
                    if (it == 0) "All candidates" else types[it].replaceFirstChar(Char::uppercase)
                render()
            }
        }
        query.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    limit = 20
                    render()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        render()
    }

    private fun adminLibrary(tab: String = "library") {
        ui.screen("Administration", { ui.home() }, key = "/admin/$tab")
        adminHeader(tab)
        if (tab == "library") {
            val body =
                ui.panel(
                    "Library explorer",
                    "Search and review tracks in the server library.",
                    "library",
                )
            val query = ui.labelledField("Search server library", body)
            val search = ui.button("Search", body) {}
            val rows = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            body.addView(rows)
            val owner = ui.content
            var serial = 0
            fun refresh() {
                val ticket = ++serial
                val value = query.text.toString().trim()
                search.isEnabled = false
                api.execute({
                    api.request("/admin/api/library/search?q=" + NativeApi.encode(value))
                }) { data, error ->
                    if (ui.content !== owner || ticket != serial) return@execute
                    search.isEnabled = true
                    if (error != null) {
                        ui.toast(error.message ?: "Library search failed")
                        return@execute
                    }
                    rows.removeAllViews()
                    val tracks = NativeTrack.items(data)
                    if (tracks.isEmpty()) ui.label("No matching tracks", 14, rows, ui.muted)
                    for (track in tracks) adminTrack(track, rows)
                }
            }
            search.setOnClickListener { refresh() }
            query.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            query.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    refresh()
                    true
                } else false
            }
            refresh()
        }
        if (tab == "duplicates") {
            val body =
                ui.panel(
                    "Find duplicates",
                    "Review exact matches, matching tags and similar tracks. Compare titles, artists and duration before deleting a recording.",
                    "copy",
                )
            ui.button("Find duplicates", body, true) {
                startAdminJob("/admin/api/library/duplicates/jobs", "Finding duplicates")
            }
        }
        if (tab == "maintenance") {
            val body =
                ui.panel(
                    "Library maintenance",
                    "Audit missing files, refresh incomplete metadata, or measure playback loudness.",
                    "settings",
                )
            for ((name, path) in
                listOf(
                    "Scan duplicates" to "duplicates",
                    "Audit library" to "audit",
                    "Rescan metadata" to "metadata-rescan",
                    "Scan loudness" to "loudness-scan",
                )) ui.button(name, body) { startAdminJob("/admin/api/library/$path/jobs", name) }
        }
    }

    private fun adminDownloads(tab: String = "runtime") {
        val path =
            if (tab == "providers") "/admin/api/downloader/providers"
            else "/admin/api/downloader/status"
        ui.load("Download Manager", path, restore = { adminDownloads(tab) }) { value ->
            val data = value as JSONObject
            ui.label("Download Manager", 28)
            ui.label(
                "Monitor the isolated downloader, manage provider sessions, and choose the download fallback policy.",
                14,
                color = ui.muted,
            )
            ui.chipBar(
                listOf("User Admin" to { admin() }, "System Monitor" to { system() }),
                top = 12,
                bottom = 16,
            )
            val sections = listOf("runtime", "providers", "policy")
            ui.tabs(
                listOf("Runtime", "Provider sessions", "Engine policy"),
                sections.indexOf(tab),
                icons = listOf("activity", "key-round", "sliders-horizontal"),
            ) {
                adminDownloads(sections[it])
            }
            ui.space(16)
            if (tab == "runtime") {
                val runtime = ui.panel("Isolated worker", glyph = "activity")
                ui.label(if (data.optBoolean("available")) "Online" else "Unavailable", 24, runtime)
                ui.label(
                    "${data.optInt("active_jobs")} active · ${data.optInt("failed_jobs")} failed",
                    14,
                    runtime,
                    ui.muted,
                )
                val versions = data.optJSONObject("versions") ?: JSONObject()
                for (key in listOf("yt-dlp", "spotdl", "SpotiFLAC")) {
                    val card = ui.panel(if (key == "spotdl") "spotDL" else key)
                    ui.label(versions.optString(key, "Unavailable"), 18, card)
                }
                if (!data.optBoolean("available") && data.optString("error").isNotBlank())
                    ui.label(data.optString("error"), 14, color = ui.muted)
            } else if (tab == "policy") {
                val panel =
                    ui.panel(
                        "Download engine policy",
                        "Automatic is recommended: it tries the isolated worker first and preserves the legacy downloader as a compatibility fallback.",
                        "sliders-horizontal",
                    )
                ui.label("Active policy", 13, panel, ui.muted)
                val modes = listOf("automatic", "worker", "legacy")
                val names =
                    listOf(
                        "Automatic — worker first, legacy fallback",
                        "Isolated worker only",
                        "Legacy Concierge only",
                    )
                var selected = modes.indexOf(data.optString("mode", "automatic")).coerceAtLeast(0)
                val policy = ui.button(names[selected], panel) {}
                policy.setOnClickListener {
                    ui.choose("Active policy", names) {
                        selected = it
                        policy.text = names[it]
                    }
                }
                ui.button("Save policy", panel, true) {
                    ui.request(
                        "/admin/system/downloader/mode",
                        form = mapOf("downloader_mode" to modes[selected]),
                        done = { adminDownloads(tab) },
                    )
                }
                ui.label(
                    "Automatic mode preserves the legacy Concierge downloader when the worker cannot complete a download.",
                    14,
                    color = ui.muted,
                )
            } else {
                ui.sectionHeader("Lossless provider sessions")
                ui.label(
                    "Connected sessions are stored in the private worker volume and checked every 15 minutes for renewal.",
                    14,
                    color = ui.muted,
                )
                ui.button("Refresh") { providers() }
                ui.button("Active verification session") {
                    ui.load("Verification session", "/admin/api/downloader/auth-browser/status") {
                        status ->
                        val session = status as JSONObject
                        val id = session.optString("session_id")
                        if (id.isBlank()) ui.label("No active verification session")
                        else remoteBrowser(id, session.optString("provider"))
                    }
                }
                val providers = NativeTrack.items(data.optJSONArray("providers"))
                if (providers.isEmpty())
                    ui.label(
                        "Provider status is unavailable. Confirm that the isolated worker is online, then refresh.",
                        14,
                        color = ui.muted,
                    )
                for (provider in providers) {
                    val key = provider.optString("provider", provider.optString("id"))
                    val panel =
                        ui.panel(
                            provider.optString("label", key),
                            "SpotiFLAC service ${provider.optString("service")}",
                            "radio",
                        )
                    ui.label(provider.optString("status").replace('_', ' '), 14, panel, ui.accent)
                    ui.label("Session expiry", 13, panel, ui.muted)
                    ui.label(
                        provider.optString("expires_at").takeIf { it.isNotBlank() && it != "null" }
                            ?: "Not connected",
                        15,
                        panel,
                    )
                    val path = "/admin/api/downloader/providers/${NativeApi.encode(key)}"
                    if (!provider.optBoolean("connected"))
                        ui.button("Open verification browser", panel, true) {
                            ui.request(
                                "$path/auth-browser/start",
                                done = { data ->
                                    val result = data as JSONObject
                                    val session = result.optJSONObject("browser_session")
                                    if (session == null) providers()
                                    else remoteBrowser(session.getString("session_id"), key)
                                },
                            )
                        }
                    else
                        ui.button("Disconnect", panel) {
                            ui.confirm("Disconnect provider", key) {
                                ui.request(path, "DELETE", done = { providers() })
                            }
                        }
                    ui.button("Check verification", panel) {
                        ui.request("$path/auth-browser/check", done = { providers() })
                    }
                }
            }
        }
    }

    private fun providers() = adminDownloads("providers")

    @Suppress("ClickableViewAccessibility")
    private fun remoteBrowser(id: String, provider: String) {
        require(Regex("[A-Za-z0-9_-]{8,128}").matches(id))
        ui.screen("Verify · $provider", { providers() })
        ui.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val body = ui.content
        remoteBody = body
        // A verification form must receive Tab/text/Enter in exactly the order pressed.
        val inputs = java.util.ArrayDeque<Pair<JSONObject, () -> Unit>>()
        var sending = false
        fun pump() {
            if (sending) return
            if (ui.content !== body) {
                inputs.clear()
                return
            }
            val item = inputs.pollFirst() ?: return
            sending = true
            api.execute({
                api.request("/api/mobile/v2/admin/browser/$id/input", "POST", item.first)
            }) { _, error ->
                sending = false
                if (ui.content !== body) {
                    inputs.clear()
                    return@execute
                }
                if (error == null) {
                    item.second()
                    pump()
                } else {
                    inputs.clear()
                    ui.toast(error.message ?: "Browser input unavailable")
                }
            }
        }
        fun sendInput(value: JSONObject, done: () -> Unit = {}) {
            if (inputs.size >= 32) {
                ui.toast("Wait for the browser to finish receiving input")
                return
            }
            inputs.addLast(value to done)
            pump()
        }
        ui.label(
            "Tap the remote page to focus a field. Text is sent only to your authenticated verification session.",
            14,
            color = ui.muted,
        )
        val image =
            android.widget.ImageView(ui.activity).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = "Private verification browser"
                isClickable = true
                isFocusableInTouchMode = true
            }
        body.addView(image, LinearLayout.LayoutParams(-1, ui.dp(280)))
        var imageWidth = 1024
        var imageHeight = 768
        image.setOnTouchListener { view, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val scale =
                    minOf(view.width.toFloat() / imageWidth, view.height.toFloat() / imageHeight)
                val x = (event.x - (view.width - imageWidth * scale) / 2) / scale
                val y = (event.y - (view.height - imageHeight * scale) / 2) / scale
                if (x >= 0 && y >= 0 && x < imageWidth && y < imageHeight)
                    sendInput(JSONObject().put("action", "click").put("x", x).put("y", y))
                view.requestFocus()
                view.performClick()
            }
            true
        }
        image.setOnKeyListener { _, code, event ->
            val key =
                when (code) {
                    android.view.KeyEvent.KEYCODE_TAB ->
                        if (event.isShiftPressed) "Shift+Tab" else "Tab"
                    android.view.KeyEvent.KEYCODE_SPACE -> "Space"
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
                    android.view.KeyEvent.KEYCODE_DEL -> "Backspace"
                    android.view.KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
                    android.view.KeyEvent.KEYCODE_ESCAPE -> "Escape"
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
                    android.view.KeyEvent.KEYCODE_MOVE_HOME -> "Home"
                    android.view.KeyEvent.KEYCODE_MOVE_END -> "End"
                    android.view.KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
                    android.view.KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
                    android.view.KeyEvent.KEYCODE_A ->
                        if (event.isCtrlPressed) "Control+A" else null
                    else -> null
                }
            if (key == null) false
            else {
                if (event.action == android.view.KeyEvent.ACTION_DOWN)
                    sendInput(JSONObject().put("action", "key").put("key", key))
                true
            }
        }
        val input = ui.field("Text for the focused field", password = true)
        ui.button("Send text") {
            val value = input.text.toString()
            if (value.length > 2048) {
                input.error = "Text too long"
                return@button
            }
            sendInput(JSONObject().put("action", "text").put("text", value)) {
                if (input.text.toString() == value) input.text.clear()
            }
        }
        ui.label("Keyboard", 15)
        val keys =
            listOf(
                "Tab" to "Tab",
                "Shift + Tab" to "Shift+Tab",
                "Space" to "Space",
                "Enter" to "Enter",
                "Backspace" to "Backspace",
                "Delete" to "Delete",
                "Escape" to "Escape",
                "←" to "ArrowLeft",
                "↑" to "ArrowUp",
                "→" to "ArrowRight",
                "↓" to "ArrowDown",
                "Home" to "Home",
                "End" to "End",
                "Page up" to "PageUp",
                "Page down" to "PageDown",
                "Select all" to "Control+A",
            )
        for (row in keys.chunked(8)) ui.chipBar(
            row.map { (label, key) ->
                label to { sendInput(JSONObject().put("action", "key").put("key", key)) }
            }
        )
        ui.button("Scroll page") {
            ui.choose("Scroll", listOf("Down", "Up")) { direction ->
                sendInput(
                    JSONObject()
                        .put("action", "scroll")
                        .put("x", imageWidth / 2)
                        .put("y", imageHeight / 2)
                        .put("delta", if (direction == 0) 500 else -500)
                )
            }
        }
        ui.button("Check verification", primary = true) {
            ui.request(
                "/admin/api/downloader/providers/${NativeApi.encode(provider)}/auth-browser/check",
                done = { providers() },
            )
        }
        ui.button("Stop verification") {
            ui.request("/admin/api/downloader/auth-browser", "DELETE", done = { providers() })
        }
        remotePoll =
            object : Runnable {
                override fun run() {
                    if (ui.content !== body) return
                    api.image({
                        val c = api.connection("/api/mobile/v2/admin/browser/$id/frame")
                        try {
                            require(c.responseCode == 200)
                            val bytes = c.inputStream.use { it.readBytesBounded(1024 * 1024) }
                            val bounds =
                                android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                            android.graphics.BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.size,
                                bounds,
                            )
                            require(bounds.outWidth in 1..2560 && bounds.outHeight in 1..1600)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } finally {
                            c.disconnect()
                        }
                    }) { value, error ->
                        if (ui.content !== body) return@image
                        if (value is android.graphics.Bitmap) {
                            imageWidth = value.width
                            imageHeight = value.height
                            image.setImageBitmap(value)
                        }
                        if (error == null) main.postDelayed(this, 1000)
                        else ui.toast("Verification frame unavailable. Check the session status.")
                    }
                }
            }
        main.post(remotePoll!!)
    }

    fun pause() {
        foreground = false
        monitorPolls.forEach { main.removeCallbacks(it.second) }
        downloadPoll?.let(main::removeCallbacks)
        remotePoll?.let(main::removeCallbacks)
    }

    fun resume() {
        foreground = true
        if (downloadDialog?.isShowing == true)
            downloadPoll?.let {
                main.removeCallbacks(it)
                main.post(it)
            }
        monitorPolls
            .filter { it.first === ui.content }
            .forEach {
                main.removeCallbacks(it.second)
                main.post(it.second)
            }
        if (ui.content === remoteBody)
            remotePoll?.let {
                main.removeCallbacks(it)
                main.post(it)
            }
    }

    private fun federation() {
        ui.load("Administration", "/api/admin/federation/instances") { value ->
            adminHeader("federation")
            ui.button("Add node") {
                form(
                    "Connected node",
                    "/api/admin/federation/instances",
                    listOf("name", "base_url", "api_token"),
                    json = true,
                ) {
                    federation()
                }
            }
            for (peer in NativeTrack.items(value)) ui.row(
                peer.optString("name"),
                peer.optString("status"),
            ) {
                val id = peer.get("id")
                ui.choose("Node", listOf("Sync", "Enable / disable", "Remove")) { action ->
                    when (action) {
                        0 ->
                            ui.request(
                                "/api/admin/federation/instances/$id/sync",
                                done = { federation() },
                            )
                        1 ->
                            ui.request(
                                "/api/admin/federation/instances/$id",
                                "PATCH",
                                JSONObject().put("enabled", !peer.optBoolean("enabled")),
                                done = { federation() },
                            )
                        2 ->
                            ui.confirm("Remove node", peer.optString("name")) {
                                ui.request(
                                    "/api/admin/federation/instances/$id",
                                    "DELETE",
                                    done = { federation() },
                                )
                            }
                    }
                }
            }
            ui.button("Outbound peers") { outboundPeers() }
        }
    }

    private fun outboundPeers() {
        ui.load("Outbound peers", "/api/admin/federation/outbound") { value ->
            ui.label("Tokens issued to connected nodes", 24)
            ui.button("Issue token", primary = true) {
                ui.sheet("Issue federation token") { body, dialog ->
                    val name = ui.labelledField("Peer name", body)
                    val url = ui.labelledField("Peer URL (optional)", body)
                    ui.button("Issue token", body, true) {
                        if (name.text.isBlank()) {
                            name.error = "Enter a peer name"
                            return@button
                        }
                        ui.request(
                            "/api/admin/federation/outbound",
                            body =
                                JSONObject()
                                    .put("name", name.text.toString().trim())
                                    .put("peer_url", url.text.toString().trim()),
                            done = { data ->
                                if (!dialog.isShowing) return@request
                                val token = (data as? JSONObject)?.optString("token").orEmpty()
                                body.removeAllViews()
                                dialog.window?.addFlags(
                                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                                )
                                ui.label("Copy this token now. It is shown only once.", 16, body)
                                ui.label(token, 14, body).apply {
                                    setTextIsSelectable(true)
                                    isSaveEnabled = false
                                }
                                ui.button("Copy token", body, true) {
                                    val clipboard =
                                        ui.activity.getSystemService(
                                            android.content.Context.CLIPBOARD_SERVICE
                                        ) as android.content.ClipboardManager
                                    val clip =
                                        android.content.ClipData.newPlainText(
                                            "Aoede federation token",
                                            token,
                                        )
                                    if (android.os.Build.VERSION.SDK_INT >= 33)
                                        clip.description.extras =
                                            android.os.PersistableBundle().apply {
                                                putBoolean(
                                                    "android.content.extra.IS_SENSITIVE",
                                                    true,
                                                )
                                            }
                                    clipboard.setPrimaryClip(clip)
                                    ui.toast("Token copied")
                                }
                                ui.button("Done", body) {
                                    dialog.dismiss()
                                    outboundPeers()
                                }
                            },
                        )
                    }
                }
            }
            for (peer in NativeTrack.items(value)) {
                val panel =
                    ui.panel(
                        peer.optString("name"),
                        peer.optString("peer_url").takeUnless { it == "null" }.orEmpty(),
                        "globe",
                    )
                ui.label(
                    if (peer.optBoolean("revoked")) "Revoked" else "Active",
                    14,
                    panel,
                    ui.muted,
                )
                ui.label(
                    "${peer.optString("status", "Unknown")} · Last seen ${peer.optString("last_seen_at").takeUnless { it.isBlank() || it == "null" } ?: "Never"}",
                    13,
                    panel,
                    ui.muted,
                )
                val id = peer.getLong("id")
                if (!peer.optBoolean("revoked"))
                    ui.button("Revoke token", panel) {
                        ui.confirm(
                            "Revoke token",
                            "This peer will lose federation access immediately.",
                        ) {
                            ui.request(
                                "/api/admin/federation/outbound/$id/revoke",
                                done = { outboundPeers() },
                            )
                        }
                    }
                ui.button("Remove peer", panel) {
                    ui.confirm(
                        "Remove peer",
                        "Permanently remove this peer and its access token?",
                    ) {
                        ui.request(
                            "/api/admin/federation/outbound/$id",
                            "DELETE",
                            done = { outboundPeers() },
                        )
                    }
                }
            }
        }
    }

    private fun form(
        title: String,
        path: String,
        names: List<String>,
        json: Boolean = false,
        done: () -> Unit,
    ) {
        ui.screen(title, { admin() }, key = path)
        val fields =
            names.associateWith {
                ui.labelledField(
                    it.replace('_', ' ').replaceFirstChar(Char::uppercase),
                    password =
                        it.contains("password") || it.contains("token") || it.contains("secret"),
                )
            }
        ui.button("Save", primary = true) {
            val values = fields.mapValues { it.value.text.toString() }
            ui.request(
                path,
                body = if (json) JSONObject(values) else null,
                form = if (json) null else values,
                done = { done() },
            )
        }
    }

    private fun summary(data: JSONObject, parent: LinearLayout = ui.content) {
        for (key in data.keys()) {
            if (
                key.contains("token", true) ||
                    key.contains("secret", true) ||
                    key.contains("password", true) ||
                    key.contains("cookie", true)
            )
                continue
            val value = data.opt(key)
            if (value is JSONObject) {
                ui.label(key.replace('_', ' '), 20, parent)
                summary(value, parent)
            } else if (value !is JSONArray)
                ui.row(
                        key.replace('_', ' ').replaceFirstChar(Char::uppercase),
                        value?.toString().orEmpty().take(240),
                        parent = parent,
                        coverSize = 0,
                    ) {}
                    .apply {
                        isClickable = false
                        isFocusable = false
                        removeViewAt(0)
                        background = null
                    }
        }
    }

    fun saveState() =
        android.os.Bundle().apply {
            putString("settings", settingsTab)
            putInt("system", systemTab)
            putString("radio", radioHub)
        }

    fun restoreState(state: android.os.Bundle) {
        settingsTab =
            state.getString("settings")?.takeIf {
                it in listOf("account", "appearance", "playback", "integrations", "youtube")
            } ?: "account"
        systemTab = state.getInt("system").coerceIn(0, 5)
        radioHub = state.getString("radio", radioHub).orEmpty().take(200)
    }

    fun restorePage(path: String): Boolean {
        when {
            path == "/api/mobile/v2/settings" -> settings()
            path == "/api/mobile/v2/admin/system" -> system()
            path == "/api/mobile/v2/admin/users" -> admin()
            path == "/api/admin/federation/outbound" -> outboundPeers()
            path == "/api/admin/federation/instances" -> federation()
            Regex("/admin/api/system/jobs/[1-9][0-9]*").matches(path) ->
                adminJob(path.substringAfterLast('/').toInt())
            path == "/api/mobile/v2/admin/delete-requests" -> adminDeletions()
            path.startsWith("/user/profile/") ->
                profile(android.net.Uri.decode(path.removePrefix("/user/profile/")))
            path.startsWith("/api/artist/") ->
                artist(android.net.Uri.decode(path.removePrefix("/api/artist/")))
            path == "radios" -> radios()
            path == "Device storage" || path == "Pending offline changes" -> storage()
            path.startsWith("/admin/api/downloader") ->
                adminDownloads(if (path.endsWith("providers")) "providers" else "runtime")
            path in
                listOf(
                    "/admin/library",
                    "/admin/duplicates",
                    "/admin/maintenance",
                    "/admin/federation",
                ) -> admin(path.substringAfterLast('/'))
            path.startsWith("/admin/") -> admin()
            else -> return false
        }
        return true
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        remotePoll = null
        remoteBody = null
        downloadPoll = null
        downloadDialog = null
        monitorPolls.clear()
        partyBody = null
        partyState = null
        partyNow = null
        partyQueue = null
        partyCover = null
        partyArtist = null
        partyParticipants = null
        partyToggle = null
    }
}
