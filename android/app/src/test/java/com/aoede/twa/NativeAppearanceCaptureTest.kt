package com.aoede.twa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.concurrent.ThreadPoolExecutor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in screenshots of real Android views against the loopback-only reference fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AoedeApp::class, qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppearanceCaptureTest {
    @Test
    fun captureCanonicalMobileRoutes() {
        val directory = System.getenv("AOEDE_NATIVE_PREVIEWS")
        assumeNotNull(directory)
        val output = File(directory!!).apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication() as AoedeApp
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("use_native_player", true)
            .putBoolean("reduce_effects", false)
            .putString(MainActivity.KEY_URL, "http://127.0.0.1:8765")
            .commit()
        android.provider.Settings.Global.putFloat(
            app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = lifecycle.get()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            activity.findViewById(R.id.main_root)
        ) { view, insets ->
            view.setPadding(0, 24, 0, 48)
            insets
        }
        val ui =
            MainActivity::class
                .java
                .getDeclaredField("nativeUi")
                .apply { isAccessible = true }
                .get(activity) as NativeUi
        val context =
            JSONObject()
                .put("protocol", 2)
                .put("storage_scope", "a".repeat(64))
                .put("language", "en")
                .put("theme", "classic")
                .put(
                    "account",
                    JSONObject().put("id", 1).put("username", "visual").put("is_admin", true),
                )
        NativeApi::class
            .java
            .getDeclaredField("session")
            .apply { isAccessible = true }
            .set(
                app.nativeApi,
                JSONObject()
                    .put("access_token", "fixture-only")
                    .put("remember", false)
                    .put("context", context),
            )
        val workers =
            listOf("workers", "images").map { name ->
                NativeApi::class
                    .java
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(app.nativeApi) as ThreadPoolExecutor
            }
        fun settle() {
            var stable = 0
            repeat(300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (workers.all { it.activeCount == 0 && it.queue.isEmpty() }) stable++
                else stable = 0
                if (stable >= 3) return
                Thread.sleep(10)
            }
            fail("Local visual fixture did not settle")
        }
        fun capture(name: String) {
            settle()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            val root = activity.findViewById<View>(R.id.main_root)
            root.setPadding(0, 24, 0, 48)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
            )
            (root as ViewGroup)
                .findViewWithTag<NativePlayerView>("native-fullscreen-player")
                ?.let { panel ->
                    NativeUi::class
                        .java
                        .getDeclaredMethod("placePlayer", NativePlayerView::class.java)
                        .apply { isAccessible = true }
                        .invoke(ui, panel)
                }
            root.layout(0, 0, 400, 850)
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 400, 850)
            fun check(view: View) {
                assertFalse(
                    "$name: native routes must not mount a WebView",
                    view is android.webkit.WebView,
                )
                if (view is TextView)
                    assertFalse(
                        "$name: incompatible response",
                        view.text.toString().contains("incompatible response"),
                    )
                if (view is ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
            }
            check(root)
            val bitmap = Bitmap.createBitmap(400, 850, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(output, "native-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        fun latestDialog(): android.app.Dialog {
            @Suppress("UNCHECKED_CAST")
            val dialogs =
                NativeUi::class
                    .java
                    .getDeclaredField("dialogs")
                    .apply { isAccessible = true }
                    .get(ui) as Set<android.app.Dialog>
            return dialogs.last { it.isShowing }
        }
        fun captureDialog(name: String) {
            settle()
            val dialog = latestDialog()
            val decor = requireNotNull(dialog.window).decorView
            val width = dialog.window!!.attributes.width.takeIf { it > 0 } ?: 400
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.AT_MOST),
            )
            decor.layout(0, 0, width, decor.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            val bitmap = Bitmap.createBitmap(width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(output, "native-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        fun findDescription(view: View, label: String): View? {
            if (view.contentDescription == label) return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) findDescription(view.getChildAt(i), label)?.let {
                    return it
                }
            return null
        }
        try {
            for (theme in listOf("classic", "glass", "vitrine")) for (scheme in
                listOf("dark", "light")) {
                RuntimeEnvironment.setQualifiers(
                    "w400dp-h850dp-" + (if (scheme == "dark") "night" else "notnight") + "-mdpi"
                )
                context.put("theme", theme)
                // Only the synthetic loopback fixture is mutated; no production credentials.
                val update = Thread {
                    app.nativeApi.request(
                        "/api/mobile/v2/settings",
                        "PATCH",
                        JSONObject().put("theme", theme),
                    )
                }
                update.start()
                update.join(3000)
                assertFalse(update.isAlive)
                val name = "$theme-$scheme"
                if (name == "classic-dark") {
                    ui.create()
                    ui.search("Keep this search")
                    settle()
                    assertEquals(
                        "Startup context must not replace a newer destination",
                        "search",
                        ui.saveState().getString("route"),
                    )
                }
                ui.home()
                capture("$name-home")
                ui.collections()
                capture("$name-library")
                ui.search()
                capture("$name-search")
                ui.discover()
                capture("$name-discovery")
                ui.collection("1")
                capture("$name-playlist")
                ui.favorites()
                capture("$name-favorites")
                ui.publicCollections()
                capture("$name-public")
                ui.community()
                capture("$name-community")
                ui.parties()
                capture("$name-parties")
                val party = NativeParty(app, "1")
                try {
                    val response = app.nativeApi.origin!!.rule + "/api/party/rooms"
                    var room: JSONObject? = null
                    val fetch = Thread {
                        room =
                            (app.nativeApi.request(response) as JSONObject)
                                .getJSONArray("rooms")
                                .getJSONObject(0)
                    }
                    fetch.start()
                    fetch.join(3000)
                    assertFalse(fetch.isAlive)
                    NativeParty::class
                        .java
                        .getDeclaredField("room")
                        .apply { isAccessible = true }
                        .set(party, requireNotNull(room))
                    ui.tools.partyDetails(party, true)
                    capture("$name-party-room")
                } finally {
                    party.close()
                }
                ui.tools.artist("M83")
                capture("$name-artist")
                ui.tools.radios("London")
                capture("$name-radios")
                val stations = arrayListOf<View>()
                ui.content.findViewsWithText(
                    stations,
                    "A sample station",
                    View.FIND_VIEWS_WITH_TEXT,
                )
                assertTrue(
                    "Radio searches must read the backend's flattened hits",
                    stations.isNotEmpty(),
                )
                ui.tools.downloads()
                captureDialog("$name-downloads")
                val downloadDialog = latestDialog()
                if (name == "classic-dark") {
                    val poll =
                        NativeTools::class
                            .java
                            .getDeclaredField("downloadPoll")
                            .apply { isAccessible = true }
                            .get(ui.tools) as Runnable
                    val before = workers[0].completedTaskCount
                    ui.pause()
                    poll.run()
                    settle()
                    assertEquals(before, workers[0].completedTaskCount)
                    ui.resume()
                    settle()
                    assertTrue(workers[0].completedTaskCount > before)
                    downloadDialog.dismiss()
                    settle()
                    val after = workers[0].completedTaskCount
                    poll.run()
                    settle()
                    assertEquals(
                        "Closing downloads must stop its polling",
                        after,
                        workers[0].completedTaskCount,
                    )
                    var staleMutationNavigated = false
                    ui.request("/fixture/mutation", done = { staleMutationNavigated = true })
                    ui.screen("New destination")
                    settle()
                    assertFalse(
                        "An old mutation response must not reopen the page that submitted it",
                        staleMutationNavigated,
                    )
                } else downloadDialog.dismiss()
                ui.collaborators("1")
                captureDialog("$name-collaborators")
                latestDialog().dismiss()
                ui.tools.resolveCollection("https://open.spotify.com/playlist/fixture")
                captureDialog("$name-import")
                latestDialog().dismiss()
                ui.tools.wrapped()
                capture("$name-wrapped")
                val songs = arrayListOf<View>()
                ui.content.findViewsWithText(songs, "Midnight City", View.FIND_VIEWS_WITH_TEXT)
                assertTrue("Wrapped must render top_songs_playlist.tracks", songs.isNotEmpty())
                val watch = arrayListOf<View>()
                ui.content.findViewsWithText(watch, "Watch Story", View.FIND_VIEWS_WITH_TEXT)
                assertTrue(watch.isNotEmpty())
                watch.first().performClick()
                capture("$name-wrapped-story")
                repeat(9) {
                    requireNotNull(
                            findDescription(activity.findViewById(R.id.main_root), "Next slide")
                        )
                        .performClick()
                }
                capture("$name-wrapped-summary-slide")
                requireNotNull(
                        findDescription(activity.findViewById(R.id.main_root), "Open summary")
                    )
                    .performClick()
                capture("$name-wrapped-return")
                assertNull(
                    activity
                        .findViewById<View>(R.id.main_root)
                        .findViewWithTag<View>("native-wrapped-story")
                )
                ui.tools.settings("account")
                capture("$name-settings")
                ui.tools.settings("appearance")
                capture("$name-appearance")
                ui.tools.profile("visual")
                capture("$name-profile")
                ui.tools.admin()
                capture("$name-admin")
                NativeTools::class
                    .java
                    .getDeclaredMethod("system")
                    .apply { isAccessible = true }
                    .invoke(ui.tools)
                capture("$name-monitor")
                if (name == "classic-dark") {
                    @Suppress("UNCHECKED_CAST")
                    val polls =
                        NativeTools::class
                            .java
                            .getDeclaredField("monitorPolls")
                            .apply { isAccessible = true }
                            .get(ui.tools) as List<Pair<android.widget.LinearLayout, Runnable>>
                    val snapshot = polls.toList()
                    val before = workers[0].completedTaskCount
                    ui.pause()
                    snapshot.forEach { it.second.run() }
                    settle()
                    assertEquals(
                        "A paused screen must not keep requesting monitoring data",
                        before,
                        workers[0].completedTaskCount,
                    )
                    ui.resume()
                    settle()
                    assertTrue(workers[0].completedTaskCount > before)
                    ui.screen("Navigation guard")
                    settle()
                    val routed = workers[0].completedTaskCount
                    snapshot.forEach { it.second.run() }
                    settle()
                    assertEquals(
                        "A previous page must not keep requesting monitoring data",
                        routed,
                        workers[0].completedTaskCount,
                    )
                }
                NativeTools::class
                    .java
                    .getDeclaredField("systemTab")
                    .apply { isAccessible = true }
                    .setInt(ui.tools, 1)
                NativeTools::class
                    .java
                    .getDeclaredMethod("system")
                    .apply { isAccessible = true }
                    .invoke(ui.tools)
                capture("$name-statistics")
                val statistics = arrayListOf<View>()
                ui.content.findViewsWithText(statistics, "42 listens", View.FIND_VIEWS_WITH_TEXT)
                assertTrue(
                    "User statistics must render qualified listens, not host CPU data",
                    statistics.isNotEmpty(),
                )
                NativeTools::class
                    .java
                    .getDeclaredField("systemTab")
                    .apply { isAccessible = true }
                    .setInt(ui.tools, 0)
                ui.offlineDownloads()
                capture("$name-offline")
                val track =
                    JSONObject()
                        .put("id", 1)
                        .put("db_id", 1)
                        .put("title", "Midnight City")
                        .put("artist", "M83")
                        .put("album", "Hurry Up, We're Dreaming")
                        .put("duration", 240)
                        .put("thumbnail", "/fixture/art/1.png")
                NativePlayback::class
                    .java
                    .getDeclaredField("tracks")
                    .apply { isAccessible = true }
                    .set(app.nativePlayback, listOf(track))
                activity.findViewById<View>(R.id.main_root).setPadding(0, 24, 0, 48)
                app.nativePlayback.setVolume(.7f)
                app.mediaController.receive(
                    JSONObject()
                        .put("type", "controls")
                        .put("favorite", true)
                        .put("canFavorite", true)
                        .put("canShuffle", true)
                        .put("canRepeat", true)
                        .toString()
                )
                ui.showPlayer()
                capture("$name-player")
                ui.showPlayer("lyrics")
                capture("$name-lyrics")
                ui.showPlayer("queue")
                capture("$name-queue")
                ui.hidePlayer()
                NativePlayback::class
                    .java
                    .getDeclaredField("tracks")
                    .apply { isAccessible = true }
                    .set(app.nativePlayback, emptyList<JSONObject>())
                ui.login()
                capture("$name-login")
            }
        } finally {
            ui.close()
            app.destroyPlayer()
            lifecycle.pause().stop().destroy()
            PlaybackService.starting = false
        }
    }
}
