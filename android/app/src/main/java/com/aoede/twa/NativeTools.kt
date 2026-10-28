package com.aoede.twa

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    private var partySignature = ""
    private var remotePoll: Runnable? = null
    private var remoteBody: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    fun settings() {
        ui.load("Appearance & settings", "/api/mobile/v2/settings") { value ->
            val data = value as JSONObject
            ui.label("Appearance", 23)
            ui.button("Theme · ${data.optString("theme")}") {
                ui.choose("Choose a theme", listOf("Classic", "Glass", "Vitrine")) { i ->
                    val theme = listOf("classic", "glass", "vitrine")[i]
                    ui.request(
                        "/api/mobile/v2/settings",
                        "PATCH",
                        JSONObject().put("theme", theme),
                        done = {
                            api.saveContextPreference("theme", theme)
                            settings()
                        },
                    )
                }
            }
            ui.button("Language") {
                ui.choose("Language", listOf("English", "Español", "Deutsch")) { i ->
                    val language = listOf("en", "es", "de")[i]
                    ui.request(
                        "/api/mobile/v2/settings",
                        "PATCH",
                        JSONObject().put("language", language),
                        done = {
                            api.saveContextPreference("language", language)
                            settings()
                        },
                    )
                }
            }
            ui.button("Dynamic Island · this device") { ui.activity.openIslandSettings() }
            val prefs = ui.activity.getSharedPreferences(MainActivity.PREFS, 0)
            ui.button("Reduce effects · ${prefs.getBoolean("reduce_effects",false)}") {
                prefs
                    .edit()
                    .putBoolean("reduce_effects", !prefs.getBoolean("reduce_effects", false))
                    .apply()
                settings()
            }
            ui.label("Playback", 23)
            ui.button("Sleep timer") {
                ui.choose(
                    "Pause playback after",
                    listOf("Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "2 hours"),
                ) { i ->
                    player.setSleepTimer(listOf(0, 15, 30, 45, 60, 120)[i])
                    ui.toast("Sleep timer updated")
                }
            }
            ui.button("Loudness normalization · ${prefs.getBoolean("native_replaygain",false)}") {
                prefs
                    .edit()
                    .putBoolean("native_replaygain", !prefs.getBoolean("native_replaygain", false))
                    .apply()
                player.refreshAudioPreferences()
                settings()
            }
            ui.button("Track fade · ${prefs.getInt("native_fade_seconds",0)} seconds") {
                ui.choose(
                    "Fade out / fade in",
                    listOf(
                        "Off",
                        "1 second",
                        "2 seconds",
                        "3 seconds",
                        "5 seconds",
                        "8 seconds",
                        "12 seconds",
                    ),
                ) { i ->
                    prefs
                        .edit()
                        .putInt("native_fade_seconds", listOf(0, 1, 2, 3, 5, 8, 12)[i])
                        .apply()
                    settings()
                }
            }
            ui.button("Profile photo") {
                ui.activity.pickNativeFile("image/*") {
                    upload("/user/upload-avatar", "avatar_file", it) { profile(api.username) }
                }
            }
            ui.label("Account & integrations", 23)
            ui.button("Change password") { password() }
            ui.button("Provider credentials") {
                integrations(data.optJSONObject("integrations") ?: JSONObject())
            }
            ui.button("Metadata provider priority") {
                ui.choose("First provider", listOf("spotify", "lastfm", "musicbrainz")) { first ->
                    val providers = listOf("spotify", "lastfm", "musicbrainz")
                    ui.request(
                        "/api/mobile/v2/settings",
                        "PATCH",
                        JSONObject()
                            .put(
                                "metadata_preferences",
                                JSONArray(
                                    listOf(providers[first]) +
                                        providers.filterIndexed { i, _ -> i != first }
                                ),
                            ),
                        done = { settings() },
                    )
                }
            }
            ui.button("Import YouTube cookies") {
                ui.activity.pickNativeFile("text/*") {
                    upload("/user/upload-cookies", "cookies_file", it) {
                        ui.toast("Cookies updated")
                    }
                }
            }
            ui.button("Storage & migration") { storage() }
            ui.button("Installed web client · compatibility") {
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
            ui.button("Deletion request responses") {
                ui.load("Your requests", "/api/tracks/delete-requests/mine") { rows ->
                    for (r in NativeTrack.items(rows)) ui.row(
                        r.optString("track_title"),
                        r.optString("status") + " · " + r.optString("review_note"),
                    ) {}
                }
            }
        }
    }

    private fun password() {
        val body =
            LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), 0, ui.dp(20), 0)
            }
        val current = ui.field("Current password", body, true)
        val next = ui.field("New password", body, true)
        val confirm = ui.field("Confirm new password", body, true)
        val dialog =
            AlertDialog.Builder(ui.activity)
                .setTitle("Change password")
                .setView(body)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (next.text.toString() != confirm.text.toString()) {
                    confirm.error = "Passwords do not match"
                    return@setOnClickListener
                }
                ui.request(
                    "/user/change-password",
                    form =
                        mapOf(
                            "current_password" to current.text.toString(),
                            "new_password" to next.text.toString(),
                            "confirm_password" to confirm.text.toString(),
                        ),
                    done = {
                        current.text.clear()
                        next.text.clear()
                        confirm.text.clear()
                        dialog.dismiss()
                        player.release()
                        api.clearSession()
                        ui.login("Password changed. Sign in again.")
                    },
                )
            }
        }
        dialog.show()
    }

    private fun integrations(configured: JSONObject) {
        ui.screen("Provider credentials", { settings() })
        ui.label(
            "Stored secrets are never returned. Leave a field blank to keep it.",
            14,
            color = ui.muted,
        )
        val fields =
            listOf(
                    "spotify_client_id",
                    "spotify_client_secret",
                    "lastfm_api_key",
                    "lastfm_shared_secret",
                )
                .associateWith { key ->
                    ui.field(
                        key.replace('_', ' ') +
                            if (configured.optBoolean(key)) " · configured" else "",
                        password = true,
                    )
                }
        ui.button("Save credentials", primary = true) {
            val body = JSONObject()
            for ((key, input) in fields) if (input.text.isNotBlank())
                body.put(key, input.text.toString())
            ui.request(
                "/api/mobile/v2/integrations",
                "PUT",
                body,
                done = {
                    fields.values.forEach { it.text.clear() }
                    settings()
                },
            )
        }
        ui.button("Remove a stored credential") {
            ui.choose("Remove credential", fields.keys.toList()) { i ->
                val key = fields.keys.elementAt(i)
                ui.confirm("Remove credential", key) {
                    ui.request(
                        "/api/mobile/v2/integrations",
                        "PUT",
                        JSONObject().put(key, JSONObject.NULL),
                        done = { settings() },
                    )
                }
            }
        }
    }

    fun profile(username: String) {
        ui.load("Profile", "/user/profile/${NativeApi.encode(username)}") { value ->
            val data = value as JSONObject
            ui.row(
                data.optString("username"),
                if (data.optBoolean("is_federated")) data.optString("fed_instance_name")
                else "Aoede",
                data.optString("avatar_url"),
            ) {}
            val stats = data.optJSONObject("stats")
            ui.label(
                "${stats?.optInt("total_listens")?:0} listens · ${stats?.optInt("listening_minutes")?:0} minutes",
                16,
            )
            ui.label("Public playlists & albums", 22)
            for (p in NativeTrack.items(data.optJSONArray("public_playlists"))) ui.row(
                p.optString("name"),
                p.optString("kind"),
                p.optString("thumbnail"),
            ) {
                ui.collection(p.get("id").toString())
            }
            ui.label("Favorites", 22)
            ui.tracks(NativeTrack.items(data.optJSONArray("favorites")))
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
            ui.label(name, 28)
            for (key in listOf("library_tracks", "top_tracks", "tracks")) {
                val tracks = NativeTrack.items(data.optJSONArray(key))
                if (tracks.isNotEmpty()) {
                    ui.label(key.replace('_', ' '), 20)
                    ui.tracks(tracks)
                }
            }
            for (album in NativeTrack.items(data.optJSONArray("albums"))) ui.row(
                album.optString("name"),
                album.optString("artist"),
                album.optString("thumbnail"),
            ) {
                resolveCollection(album.optString("url"))
            }
        }
    }

    fun smartPlaylist() {
        ui.screen("Smart playlist", { ui.collections() })
        val name = ui.field("Name")
        val artist = ui.field("Artist contains")
        val genre = ui.field("Genre contains")
        ui.button("Create smart playlist", primary = true) {
            ui.request(
                "/api/smart-playlists",
                body =
                    JSONObject()
                        .put("name", name.text.toString())
                        .put(
                            "rules",
                            JSONObject()
                                .put("artist", artist.text.toString())
                                .put("genre", genre.text.toString()),
                        ),
                done = { ui.collections() },
            )
        }
    }

    fun resolveCollection(url: String) {
        if (url.isBlank()) {
            ui.toast("No collection URL available")
            return
        }
        ui.load("Import collection", "/api/collections/resolve?url=" + NativeApi.encode(url)) {
            value ->
            val data = (value as JSONObject).optJSONObject("collection") ?: value
            ui.label(data.optString("name", "Collection"), 26)
            ui.label("${data.optInt("track_count",NativeTrack.items(data).size)} tracks", 14)
            ui.button("Import to server library", primary = true) {
                ui.request(
                    "/api/download/collection",
                    body =
                        JSONObject()
                            .put("url", url)
                            .put("kind", data.optString("kind", "playlist"))
                            .put("name", data.optString("name")),
                    done = { downloads() },
                )
            }
            ui.tracks(NativeTrack.items(data))
        }
    }

    fun downloads() {
        ui.load("Downloads", "/api/jobs") { value ->
            val url = ui.field("Spotify / YouTube track, playlist or album URL")
            val collection =
                android.widget.Switch(ui.activity).apply {
                    text = "Album or playlist URL"
                    setTextColor(ui.ink)
                    minHeight = ui.dp(48)
                }
            ui.content.addView(collection)
            ui.button("Download", primary = true) {
                val target = url.text.toString().trim()
                if (collection.isChecked) resolveCollection(target)
                else
                    ui.request(
                        "/api/download",
                        body = JSONObject().put("url", target),
                        done = { downloads() },
                    )
            }
            ui.button("Device downloads") { ui.offlineDownloads() }
            for (job in NativeTrack.items(value)) {
                ui.row(
                    job.optString("title", job.optString("url", "Download")),
                    job.optString("status"),
                ) {
                    if (job.optString("status") in listOf("error", "failed"))
                        ui.request("/api/downloads/${job.get("id")}/retry", done = { downloads() })
                }
            }
        }
    }

    fun radios() {
        ui.load("Radio & mixes", "/api/mixes") { value ->
            ui.button("Saved radio stations") {
                ui.load("Radio", "/api/radio/list") { stations ->
                    for (s in NativeTrack.items(stations)) ui.row(s.optString("name")) {
                        val t =
                            JSONObject()
                                .put("id", "radio-" + s.optString("id"))
                                .put("title", s.optString("name"))
                                .put("artist", "Radio")
                                .put(
                                    "stream_url",
                                    "/api/proxy/radio?url=" +
                                        NativeApi.encode(
                                            s.optString("streamUrl", s.optString("url"))
                                        ),
                                )
                        player.play(listOf(t))
                    }
                }
            }
            ui.button("Search radio") {
                ui.prompt("Find a radio station") { q ->
                    ui.load("Radio search", "/api/radio/search?q=" + NativeApi.encode(q)) { stations
                        ->
                        for (s in NativeTrack.items(stations)) ui.row(
                            s.optString("name"),
                            s.optString("country"),
                        ) {
                            ui.request(
                                "/api/radio/inject",
                                form =
                                    mapOf(
                                        "channel_id" to s.optString("id"),
                                        "name" to s.optString("name"),
                                    ),
                                done = { radios() },
                            )
                        }
                    }
                }
            }
            for (mix in NativeTrack.items(value)) {
                val key = mix.optString("key", mix.optString("mix_key"))
                ui.row(mix.optString("name", mix.optString("title", "Mix"))) {
                    ui.load("Mix", "/api/mixes/" + NativeApi.encode(key)) { detail ->
                        val data = detail as JSONObject
                        ui.button("Save as playlist") {
                            ui.request(
                                "/api/mixes/${NativeApi.encode(key)}/save",
                                body = JSONObject().put("name", data.optString("name", "My mix")),
                                done = { ui.collections() },
                            )
                        }
                        ui.tracks(NativeTrack.items(data))
                    }
                }
            }
        }
    }

    fun lyrics() {
        ui.hidePlayer()
        val t = player.current ?: return
        val ref = NativeTrack.reference(t)
        ui.load(
            "Lyrics",
            "/api/lyrics?title=${NativeApi.encode(t.optString("title"))}&artist=${NativeApi.encode(t.optString("artist"))}&album=${NativeApi.encode(t.optString("album"))}&duration=${t.optDouble("duration").coerceIn(0.0,86400.0)}",
        ) { value ->
            val data = value as JSONObject
            val synced = data.optString("synced_lyrics", data.optString("synced"))
            val plain = data.optString("plain_lyrics", data.optString("plain"))
            val parsed =
                Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)")
                    .findAll(synced)
                    .map {
                        Triple(
                            (it.groupValues[1].toLong() * 60000 +
                                    it.groupValues[2].toDouble() * 1000)
                                .toLong(),
                            it.groupValues[3],
                            ui.label(it.groupValues[3], 24).apply {
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                minHeight = ui.dp(60)
                            },
                        )
                    }
                    .toList()
            if (parsed.isEmpty())
                ui.label(plain.ifBlank { "No lyrics available for this recording" }, 22)
            if (parsed.isNotEmpty()) {
                val body = ui.content
                main.post(
                    object : Runnable {
                        override fun run() {
                            if (
                                ui.content !== body ||
                                    player.current?.let(NativeTrack::reference) != ref
                            )
                                return
                            val active = parsed.indexOfLast { it.first <= player.position }
                            parsed.forEachIndexed { i, line ->
                                line.third.setTextColor(if (i == active) ui.ink else ui.muted)
                            }
                            main.postDelayed(this, 200)
                        }
                    }
                )
            }
            ui.button("Return to player") { ui.showPlayer() }
        }
    }

    fun wrapped() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        ui.load("Aoede Wrapped", "/api/wrapped/$year") { value ->
            val data = value as JSONObject
            if (
                data.optBoolean("visible", true) == false ||
                    data.optBoolean("enabled", true) == false
            ) {
                ui.label("Wrapped is not available yet")
                return@load
            }
            ui.label("Your year in music", 30)
            for (key in
                listOf(
                    "minutes_listened",
                    "event_count",
                    "total_minutes",
                    "listening_minutes",
                    "total_streams",
                    "total_listens",
                    "unique_artists",
                )) if (data.has(key)) ui.label(key.replace('_', ' ') + " · " + data.opt(key), 21)
            ui.label("Top songs", 23)
            ui.tracks(NativeTrack.items(data.optJSONArray("top_songs")))
            ui.button("Add Top 100 playlist", primary = true) {
                ui.request(
                    "/api/wrapped/${data.optInt("year",year)}/top-songs/playlist",
                    done = { ui.collections() },
                )
            }
            ui.button("Show recap") { wrappedStory(data) }
        }
    }

    private fun wrappedStory(data: JSONObject) {
        val pages = mutableListOf("Aoede Wrapped ${data.optInt("year")}" to "Your year in music")
        for (artist in NativeTrack.items(data.optJSONArray("top_artists"))) pages.add(
            "Your top artist" to artist.optString("artist", artist.optString("name"))
        )
        for (track in NativeTrack.items(data.optJSONArray("top_songs"))) pages.add(
            track.optString("title") to track.optString("artist")
        )
        var index = 0
        fun render() {
            ui.screen("Aoede Wrapped", { wrapped() })
            val page = pages[index]
            ui.label("${index+1} / ${pages.size}", 14, color = ui.muted)
            ui.label(page.first, 36).setTypeface(null, android.graphics.Typeface.BOLD)
            ui.label(page.second, 24)
            if (!ui.reduced()) {
                ui.content.alpha = 0f
                ui.content.translationY = ui.dp(20).toFloat()
                ui.content.animate().alpha(1f).translationY(0f).setDuration(280).start()
            }
            if (index > 0)
                ui.button("Previous") {
                    index--
                    render()
                }
            if (index < pages.lastIndex)
                ui.button("Next", primary = true) {
                    index++
                    render()
                }
            else ui.button("Back to summary", primary = true) { wrapped() }
        }
        render()
    }

    fun partyDetails(party: NativeParty, open: Boolean) {
        if (open) {
            ui.screen("Party · ${party.room.optString("name")}", { ui.parties() })
            partyBody = ui.content
            partyState = ui.label("", 14, color = ui.muted)
            partyNow = ui.label("", 23)
            ui.button("Play", primary = true) { app.mediaController.command("play") }
            if (party.room.optBoolean("is_owner"))
                ui.button("Pause room") { party.control("pause") }
            ui.button("Add songs") { ui.search() }
            ui.button("Open player") { ui.showPlayer() }
            ui.button("Leave party") {
                party.close()
                partyBody = null
                ui.parties()
            }
            if (party.room.optBoolean("is_owner"))
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
            partyQueue = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
            ui.content.addView(partyQueue)
            partySignature = ""
        }
        if (ui.content !== partyBody) return
        val room = party.room
        partyState?.text = room.optString("playback_status")
        partyNow?.text = room.optJSONObject("current_track")?.optString("title").orEmpty()
        val items = NativeTrack.items(room.optJSONArray("queue"))
        val signature = items.joinToString { it.optString("item_id") }
        if (signature != partySignature) {
            partySignature = signature
            val list = partyQueue ?: return
            list.removeAllViews()
            for (item in items) {
                val track = item.optJSONObject("track") ?: item
                ui.row(track.optString("title"), track.optString("artist"), parent = list) {
                    ui.choose("Party queue", listOf("Remove track")) {
                        ui.request(
                            party.path + "/queue/" + item.get("item_id"),
                            "DELETE",
                            done = {},
                        )
                    }
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
        api.execute({ api.upload(path, field, uri) }) { _, error ->
            if (error != null) ui.toast(error.message ?: "Upload failed") else done()
        }
    }

    fun about() {
        ui.load("About & updates", "/api/about") { value ->
            val data = value as JSONObject
            ui.label(
                "Aoede ${data.optJSONObject("current")?.optString("display_version","Unknown")}",
                28,
            )
            ui.label("Android ${BuildConfig.VERSION_NAME} · Native Android interface", 15)
            ui.button("Check Android update", primary = true) {
                app.updater.check { app.updater.offer(ui.activity) }
            }
            val release = data.optJSONObject("release") ?: JSONObject()
            ui.label(release.optString("summary"), 15)
            ui.button("All official downloads") {
                ui.activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/sPROFFEs/Aoede/releases/latest"),
                    )
                )
            }
        }
    }

    fun admin() {
        ui.screen("Administration", { ui.home() })
        if (!api.isAdmin) {
            ui.label("Administrator access required")
            return
        }
        ui.button("Users") { adminUsers() }
        ui.button("System monitor & updates") { system() }
        ui.button("Server download manager") { adminDownloads() }
        ui.button("Library & duplicates") { adminLibrary() }
        ui.button("Federation") { federation() }
        ui.button("Track deletion requests") {
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
                                done = { admin() },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun adminUsers() {
        ui.load("Users", "/api/mobile/v2/admin/users") { value ->
            ui.button("Create user", primary = true) {
                form("New user", "/admin/users/create", listOf("username", "password")) {
                    adminUsers()
                }
            }
            for (user in NativeTrack.items((value as JSONObject).optJSONArray("users"))) ui.row(
                user.optString("username"),
                if (user.optBoolean("is_admin")) "Administrator" else "Listener",
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
                        0 ->
                            ui.load("Login restriction", "/admin/api/users/$id/login-lock") { r ->
                                ui.label(
                                    (r as JSONObject).optInt("remaining_seconds").toString() +
                                        " seconds remaining"
                                )
                            }
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
        }
    }

    private fun system() {
        ui.load("System monitor", "/api/mobile/v2/admin/system") { value ->
            val data = value as JSONObject
            summary(data.optJSONObject("build") ?: JSONObject())
            ui.button("Live resource usage") {
                ui.load("Resource usage", "/admin/api/system-stats") { summary(it as JSONObject) }
            }
            ui.button("Check server update", primary = true) {
                ui.request("/admin/system/updates/check", done = { system() })
            }
            ui.button("Apply server update") {
                ui.confirm("Update server", "Back up the server and apply the available update?") {
                    ui.request("/admin/system/updates/apply", done = { system() })
                }
            }
            ui.button("Create backup") {
                ui.request("/admin/system/backups/create", done = { system() })
            }
            ui.button("Restore backup") {
                ui.choose("Restore slot", listOf("current", "previous")) { slot ->
                    ui.confirm(
                        "Restore server",
                        "Restore this backup and replace current server data?",
                    ) {
                        ui.request(
                            "/admin/system/backups/restore",
                            form = mapOf("slot" to listOf("current", "previous")[slot]),
                            done = { system() },
                        )
                    }
                }
            }
            ui.button("Automatic backup settings") {
                form(
                    "Automatic backups",
                    "/admin/system/backups/settings",
                    listOf(
                        "autobackup_enabled",
                        "autobackup_hour",
                        "autobackup_minute",
                        "autobackup_timezone",
                    ),
                ) {
                    system()
                }
            }
            ui.button("Wrapped schedule") {
                form(
                    "Wrapped schedule",
                    "/admin/system/wrapped/settings",
                    listOf(
                        "wrapped_enabled",
                        "wrapped_visible_from_date",
                        "wrapped_visible_from_time",
                        "wrapped_visible_until_date",
                        "wrapped_visible_until_time",
                        "wrapped_artist_clip_message",
                    ),
                ) {
                    system()
                }
            }
            ui.button("Pool storage limit") {
                form("Pool limit", "/admin/system/pool-limit", listOf("limit_gb")) { system() }
            }
            for (job in NativeTrack.items(data.optJSONArray("jobs"))) ui.row(
                job.optString("message", job.optString("kind")),
                job.optString("status"),
            ) {
                ui.load("Operation", "/admin/api/system/jobs/" + job.get("id")) {
                    summary(it as JSONObject)
                }
            }
        }
    }

    private fun adminLibrary() {
        ui.screen("Library tools", { admin() })
        val query = ui.field("Search server library")
        ui.button("Search") {
            ui.load(
                "Server library",
                "/admin/api/library/search?q=" + NativeApi.encode(query.text.toString()),
            ) { data ->
                for (t in NativeTrack.items(data)) ui.row(
                    t.optString("title"),
                    t.optString("artist"),
                ) {
                    ui.confirm(
                        "Delete server track",
                        "Permanently delete this track from the server?",
                    ) {
                        ui.request(
                            "/admin/api/library/track/" + t.get("id"),
                            "DELETE",
                            done = { adminLibrary() },
                        )
                    }
                }
            }
        }
        ui.button("Find duplicates") {
            ui.load("Duplicates", "/admin/api/library/duplicates") { value ->
                val data = value as JSONObject
                summary(data)
                for (group in NativeTrack.items(data.optJSONArray("groups"))) {
                    ui.label(group.optString("reason", "Similar recordings"), 21)
                    for (t in NativeTrack.items(group)) ui.row(
                        t.optString("title"),
                        t.optString("artist") + " · ID " + t.get("id"),
                    ) {
                        ui.confirm(
                            "Delete duplicate",
                            "Permanently delete this server recording? Keep at least one verified copy.",
                        ) {
                            ui.request(
                                "/admin/api/library/track/${t.get("id")}",
                                "DELETE",
                                done = { adminLibrary() },
                            )
                        }
                    }
                }
            }
        }
        for ((name, path) in
            listOf(
                "Scan duplicates" to "duplicates",
                "Audit library" to "audit",
                "Rescan metadata" to "metadata-rescan",
                "Scan loudness" to "loudness-scan",
            )) ui.button(name) {
            ui.request(
                "/admin/api/library/$path/jobs",
                body = JSONObject(),
                done = { ui.toast("Operation queued") },
            )
        }
    }

    private fun adminDownloads() {
        ui.load("Download manager", "/admin/api/downloader/status") { value ->
            summary(value as JSONObject)
            ui.button("Providers & verification") { providers() }
            ui.button("Active verification session") {
                ui.load("Verification session", "/admin/api/downloader/auth-browser/status") { data
                    ->
                    val session = data as JSONObject
                    val id = session.optString("session_id")
                    if (id.isBlank()) ui.label("No active verification session")
                    else remoteBrowser(id, session.optString("provider"))
                }
            }
            ui.button("Downloader mode") {
                form("Downloader mode", "/admin/system/downloader/mode", listOf("mode")) {
                    adminDownloads()
                }
            }
        }
    }

    private fun providers() {
        ui.load("Providers", "/admin/api/downloader/providers") { value ->
            for (provider in NativeTrack.items((value as JSONObject).optJSONArray("providers"))) {
                val key = provider.optString("provider", provider.optString("id"))
                ui.row(provider.optString("label", key), provider.optString("status")) {
                    ui.choose(key, listOf("Verify account", "Check status", "Disconnect")) { action
                        ->
                        val path = "/admin/api/downloader/providers/${NativeApi.encode(key)}"
                        when (action) {
                            0 ->
                                ui.request(
                                    "$path/auth-browser/start",
                                    done = { data ->
                                        val result = data as JSONObject
                                        val session = result.optJSONObject("browser_session")
                                        if (session == null) providers()
                                        else remoteBrowser(session.getString("session_id"), key)
                                    },
                                )
                            1 -> ui.request("$path/auth-browser/check", done = { providers() })
                            2 ->
                                ui.confirm("Disconnect provider", key) {
                                    ui.request(path, "DELETE", done = { providers() })
                                }
                        }
                    }
                }
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun remoteBrowser(id: String, provider: String) {
        require(Regex("[A-Za-z0-9_-]{8,128}").matches(id))
        ui.screen("Verify · $provider", { providers() })
        ui.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val body = ui.content
        remoteBody = body
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
                    ui.request(
                        "/api/mobile/v2/admin/browser/$id/input",
                        body = JSONObject().put("action", "click").put("x", x).put("y", y),
                        done = {},
                    )
                view.performClick()
            }
            true
        }
        val input = ui.field("Text for the focused field", password = true)
        ui.button("Send text") {
            val value = input.text.toString()
            if (value.length > 2048) {
                input.error = "Text too long"
                return@button
            }
            ui.request(
                "/api/mobile/v2/admin/browser/$id/input",
                body = JSONObject().put("action", "text").put("text", value),
                done = { input.text.clear() },
            )
        }
        ui.button("Keyboard controls") {
            val keys = listOf("Tab", "Enter", "Backspace", "Escape", "ArrowUp", "ArrowDown")
            ui.choose("Send key", keys) {
                ui.request(
                    "/api/mobile/v2/admin/browser/$id/input",
                    body = JSONObject().put("action", "key").put("key", keys[it]),
                    done = {},
                )
            }
        }
        ui.button("Scroll page") {
            ui.choose("Scroll", listOf("Down", "Up")) { direction ->
                ui.request(
                    "/api/mobile/v2/admin/browser/$id/input",
                    body =
                        JSONObject()
                            .put("action", "scroll")
                            .put("x", imageWidth / 2)
                            .put("y", imageHeight / 2)
                            .put("delta", if (direction == 0) 500 else -500),
                    done = {},
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
        remotePoll?.let(main::removeCallbacks)
    }

    fun resume() {
        if (ui.content === remoteBody)
            remotePoll?.let {
                main.removeCallbacks(it)
                main.post(it)
            }
    }

    private fun federation() {
        ui.load("Federation", "/api/admin/federation/instances") { value ->
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
            ui.button("Outbound peers") {
                ui.load("Outbound peers", "/api/admin/federation/outbound") { value ->
                    summary(value as JSONObject)
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
        ui.screen(title, { admin() })
        val fields =
            names.associateWith {
                ui.field(
                    it.replace('_', ' '),
                    password = it.contains("password") || it.contains("token"),
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

    private fun summary(data: JSONObject) {
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
                ui.label(key.replace('_', ' '), 20)
                summary(value)
            } else if (value !is JSONArray)
                ui.label("${key.replace('_',' ')}: ${value?.toString().orEmpty().take(240)}", 15)
        }
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        remotePoll = null
        remoteBody = null
    }
}
