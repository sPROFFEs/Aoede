package com.aoede.twa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class NativeDesignTest {
    private val app
        get() = RuntimeEnvironment.getApplication() as AoedeApp

    @Before
    fun setup() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("use_native_player", true)
            .putString(MainActivity.KEY_URL, "https://music.test")
            .commit()
        app.nativeApi.configure("https://music.test")
    }

    @After
    fun cleanup() {
        app.destroyPlayer()
        PlaybackService.starting = false
    }

    private fun theme(value: String) {
        val session =
            org.json
                .JSONObject()
                .put("context", org.json.JSONObject().put("theme", value).put("language", "en"))
        NativeApi::class
            .java
            .getDeclaredField("session")
            .apply { isAccessible = true }
            .set(app.nativeApi, session)
    }

    @Test
    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun themesKeepTheirNavigationAndCanonicalIcons() {
        NativeIcon.install(app)
        for (name in listOf("classic", "glass", "vitrine")) for (light in listOf(false, true)) {
            val design = NativeDesign(app, name, light)
            assertEquals(
                if (name == "vitrine") listOf("library", "home", "discovery", "search")
                else listOf("home", "search", "discovery", "library"),
                design.nav,
            )
            assertEquals(if (name == "classic") 56 else 64, design.miniHeight)
            for (glyph in
                listOf(
                    "home",
                    "search",
                    "library",
                    "play",
                    "pause",
                    "heart",
                    "eye",
                    "chevron-down",
                )) {
                val image = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                NativeIcon(glyph, Color.WHITE, name).apply {
                    setBounds(0, 0, 48, 48)
                    draw(Canvas(image))
                }
                assertTrue(
                    "$name/$glyph must remain visible",
                    (0 until 48).any { x ->
                        (0 until 48).any { y -> Color.alpha(image.getPixel(x, y)) > 0 }
                    },
                )
                image.recycle()
            }
        }
    }

    @Test
    fun backdropBlurPreservesFlatMaterialAndDoesNotModifyItsSource() {
        val image =
            Bitmap.createBitmap(31, 19, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xff426789.toInt())
            }
        val blurred = blurNative(image, 5)
        assertEquals(image.width, blurred.width)
        assertEquals(image.getPixel(15, 9), blurred.getPixel(15, 9))
        assertFalse(image === blurred)
        for (y in 6..12) for (x in 12..18) image.setPixel(x, y, Color.WHITE)
        val impulse = blurNative(image, 3)
        assertEquals(Color.WHITE, image.getPixel(15, 9))
        assertTrue(Color.red(impulse.getPixel(15, 9)) < 255)
        assertTrue(Color.red(impulse.getPixel(15, 9)) > 0x42)
        blurred.eraseColor(Color.TRANSPARENT)
        assertEquals(Color.TRANSPARENT, blurred.getPixel(15, 9))
        assertEquals(Color.WHITE, image.getPixel(15, 9))
    }

    @Test
    fun wrappedReadsTheServerPlaylistAndFormatsAllListeningDurationRanges() {
        val data =
            org.json.JSONObject(
                """{"top_songs_playlist":{"tracks":[{"id":"old-id","db_id":7,"title":"Sample"}]}}"""
            )
        val song = NativeWrapped.tracks(data).single()
        assertEquals("7", NativeTrack.reference(song))
        assertTrue(song.getBoolean("is_local"))
        assertEquals(
            "old-id",
            data
                .getJSONObject("top_songs_playlist")
                .getJSONArray("tracks")
                .getJSONObject(0)
                .getString("id"),
        )
        assertTrue(NativeWrapped.tracks(org.json.JSONObject()).isEmpty())
        for ((minutes, expected) in
            listOf(
                Double.NaN to "0 seconds",
                .5 to "30 seconds",
                1.0 to "1 minute",
                61.0 to "1 hour",
                1440.0 to "1 day",
                525600.0 to "1 year",
            )) assertEquals(expected, NativeWrapped.duration(minutes))
    }

    @Test
    fun detachedControlsReleaseTheirThemeBindingsAndNavigationClosesPlatformDialogs() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = lifecycle.get()
        val ui = NativeUi(activity, app)
        ui.create()
        ui.screen("Settings")
        val button = ui.button("Themed control", primary = true) {}
        @Suppress("UNCHECKED_CAST")
        val bindings =
            NativeUi::class
                .java
                .getDeclaredField("accentBindings")
                .apply { isAccessible = true }
                .get(ui) as Map<View, *>
        assertTrue(bindings.containsKey(button))
        ui.content.removeView(button)
        assertFalse(
            "A closed player or dialog must not retain its theme-bound controls",
            bindings.containsKey(button),
        )
        val dialog =
            ui.showDialog(
                android.app.Dialog(activity).apply {
                    setContentView(android.widget.TextView(activity))
                }
            )
        assertTrue(dialog.isShowing)
        ui.screen("Another account or page")
        assertFalse(dialog.isShowing)
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun backdropInADialogSamplesTheSameScreenPositionAsTheActivity() {
        val scene =
            object : View(app) {
                    override fun getLocationOnScreen(location: IntArray) {
                        location[0] = 0
                        location[1] = 100
                    }

                    override fun onDraw(canvas: Canvas) {
                        val paint = android.graphics.Paint()
                        paint.color = Color.RED
                        canvas.drawRect(0f, 0f, 400f, 400f, paint)
                        paint.color = Color.BLUE
                        canvas.drawRect(0f, 400f, 400f, 800f, paint)
                    }
                }
                .apply { layout(0, 0, 400, 800) }
        val target =
            object : View(app) {
                    override fun getLocationOnScreen(location: IntArray) {
                        location[0] = 0
                        location[1] = 600
                    }
                }
                .apply { layout(0, 0, 400, 60) }
        val material =
            NativeBackdrop(
                scene,
                target,
                Color.TRANSPARENT,
                0f,
                Color.TRANSPARENT,
                blurDp = 0f,
                saturation = 1f,
            )
        val image = Bitmap.createBitmap(400, 60, Bitmap.Config.ARGB_8888)
        material.setBounds(0, 0, 400, 60)
        material.draw(Canvas(image))
        assertEquals(Color.BLUE, image.getPixel(200, 30))
        material.onViewDetachedFromWindow(target)
        image.recycle()
    }

    @Test
    fun avatarDecodeKeepsEnoughPixelsForItsRenderedSize() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ui = NativeUi(lifecycle.get(), app)
        val image = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        val bytes =
            java.io
                .ByteArrayOutputStream()
                .apply { image.compress(Bitmap.CompressFormat.PNG, 100, this) }
                .toByteArray()
        val decoded =
            NativeUi::class
                .java
                .getDeclaredMethod(
                    "decodeArtwork",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
                .apply { isAccessible = true }
                .invoke(ui, bytes, 88) as Bitmap
        assertTrue(decoded.width >= 88)
        assertTrue(decoded.width < 176)
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun cssLineHeightUsesFontSizeWhenAccessibilityTextIsLarge() {
        RuntimeEnvironment.setFontScale(1.5f)
        val view = android.widget.TextView(app)
        val design = NativeDesign(app, "classic", false)
        design.typography(view, 22f)
        design.lines(view, 1.45f)
        assertEquals((view.textSize * 1.45f + .5f).toInt(), view.lineHeight)
        design.lines(view, 1.1f)
        assertEquals((view.textSize * 1.1f + .5f).toInt(), view.lineHeight)
    }

    @Test
    fun artworkAccentMatchesBrowserOklabMix() {
        val samples =
            listOf(
                0xff783cc8.toInt() to intArrayOf(57, 40, 90, 193, 176, 234),
                0xffdc465a.toInt() to intArrayOf(95, 45, 51, 244, 181, 184),
                0xff2896aa.toInt() to intArrayOf(36, 69, 79, 172, 209, 218),
            )
        for ((seed, expected) in samples) for ((index, base) in
            listOf(0xff15161a.toInt(), 0xfff7f8fa.toInt()).withIndex()) {
            val color = nativeAccentMix(seed, base)
            for ((channel, actual) in
                listOf(Color.red(color), Color.green(color), Color.blue(color))
                    .withIndex()) assertTrue(
                kotlin.math.abs(actual - expected[index * 3 + channel]) <= 1
            )
        }
    }

    @Test
    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fullscreenPaintsBehindSystemBarsButKeepsControlsInsideSafeArea() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = lifecycle.get()
        val ui = NativeUi(activity, app)
        ui.create()
        ui.screen("Playback")
        val root = activity.findViewById<android.widget.FrameLayout>(R.id.main_root)
        root.setPadding(0, 24, 0, 48)
        val track = org.json.JSONObject().put("id", 1).put("title", "Synthetic recording")
        NativePlayback::class
            .java
            .getDeclaredField("tracks")
            .apply { isAccessible = true }
            .set(app.nativePlayback, listOf(track))
        ui.showPlayer()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 850)
        val full = root.findViewWithTag<NativePlayerView>("native-fullscreen-player")
        assertEquals(0, full.top)
        assertEquals(850, full.height)
        val header = full.findViewWithTag<View>("native-fs-header")
        val box = android.graphics.Rect()
        header.getDrawingRect(box)
        full.offsetDescendantRectToMyCoords(header, box)
        assertTrue(box.top >= 24)
        val image = Bitmap.createBitmap(400, 850, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        assertEquals(
            (full.background as android.graphics.drawable.ColorDrawable).color,
            image.getPixel(0, 0),
        )
        assertEquals(image.getPixel(0, 0), image.getPixel(0, 849))
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun routingReplacesOnlyContentAndDiscardsStaleViewOwnership() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ui = NativeUi(lifecycle.get(), app)
        ui.create()
        ui.screen("First")
        val first = ui.content
        val parent = first.parent
        ui.screen("Second")
        assertNotSame(first, ui.content)
        assertNull(first.parent)
        assertSame(parent, ui.content.parent)
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [28, 34])
    fun typingHidesTheMobileDockUntilTheKeyboardCloses() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ui = NativeUi(lifecycle.get(), app)
        ui.create()
        ui.screen("Search")
        val root = lifecycle.get().findViewById<android.widget.FrameLayout>(R.id.main_root)
        val dock = root.findViewWithTag<View>("native-bottom-dock")
        val shown =
            androidx.core.view.WindowInsetsCompat.Builder()
                // Before API 30 the platform reports IME height in the system window inset.
                .setInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                    androidx.core.graphics.Insets.of(
                        0,
                        24,
                        0,
                        if (android.os.Build.VERSION.SDK_INT < 30) 300 else 0,
                    ),
                )
                .setInsets(
                    androidx.core.view.WindowInsetsCompat.Type.ime(),
                    androidx.core.graphics.Insets.of(0, 0, 0, 300),
                )
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true)
                .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, shown)
        assertEquals(View.GONE, dock.visibility)
        val hidden =
            androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(
                    androidx.core.view.WindowInsetsCompat.Type.ime(),
                    androidx.core.graphics.Insets.NONE,
                )
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), false)
                .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, hidden)
        assertEquals(View.VISIBLE, dock.visibility)
        assertFalse(app.nativePlayback.active)
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun backRestoresTheActualPreviousScreenAndLoginDiscardsHistory() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ui = NativeUi(lifecycle.get(), app)
        ui.create()
        fun first() {
            ui.screen("First", restore = { first() })
            ui.label("First content")
        }
        fun second() {
            ui.screen("Second", restore = { second() })
            ui.label("Second content")
        }
        first()
        second()
        ui.screen("Transient form")
        assertTrue(ui.back())
        assertEquals(
            "Second content",
            (ui.content.getChildAt(0) as android.widget.TextView).text.toString(),
        )
        assertTrue(ui.back())
        assertEquals(
            "First content",
            (ui.content.getChildAt(0) as android.widget.TextView).text.toString(),
        )
        ui.login()
        assertFalse(ui.back())
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun profileChipsWrapWithoutShrinkingOrOverlappingText() {
        val row = NativeFlowLayout(app, 10)
        repeat(3) {
            row.addView(
                android.widget.TextView(app).apply {
                    text = "1290 listens"
                    minimumWidth = 100
                    minimumHeight = 32
                }
            )
        }
        row.measure(
            View.MeasureSpec.makeMeasureSpec(230, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        row.layout(0, 0, 230, row.measuredHeight)
        assertEquals(0, row.getChildAt(0).top)
        assertEquals(0, row.getChildAt(1).top)
        assertTrue(row.getChildAt(2).top >= row.getChildAt(0).bottom + 10)
        for (i in 0 until row.childCount) assertTrue(row.getChildAt(i).right <= row.width)
    }

    @Test
    fun offlineFiltersKeepIndividualSongsSeparateFromCollectionDownloads() {
        theme("classic")
        app.nativeApi.account!!
            .put("storage_scope", "a".repeat(64))
            .put("account", org.json.JSONObject().put("id", 1).put("username", "visual"))
        app.nativeLibrary.put(
            "track",
            "11",
            org.json
                .JSONObject()
                .put("id", 11)
                .put("title", "Standalone recording")
                .put("is_single", true),
        )
        app.nativeLibrary.put(
            "track",
            "12",
            org.json
                .JSONObject()
                .put("id", 12)
                .put("title", "Collection recording")
                .put("is_single", false),
        )
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ui = NativeUi(lifecycle.get(), app)
        ui.create()
        fun texts(view: View): List<String> =
            if (view is android.widget.TextView) listOf(view.text.toString())
            else if (view is android.view.ViewGroup)
                (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList()
        ui.offlineDownloads("singles")
        val individual = texts(ui.content)
        assertTrue(individual.any { it.contains("Standalone recording") })
        assertFalse(individual.any { it.contains("Collection recording") })
        ui.offlineDownloads()
        assertTrue(texts(ui.content).any { it.contains("Collection recording") })
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun playerFitsPortraitLandscapeAndNarrowScreensForEveryTheme() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ui = NativeUi(lifecycle.get(), app)
        ui.create()
        for (name in listOf("classic", "glass", "vitrine")) {
            theme(name)
            ui.screen("Player")
            for ((w, h) in listOf(400 to 778, 320 to 568, 850 to 328)) {
                val player = NativePlayerView(ui)
                player.measure(
                    View.MeasureSpec.makeMeasureSpec(ui.dp(w), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ui.dp(h), View.MeasureSpec.EXACTLY),
                )
                player.layout(0, 0, player.measuredWidth, player.measuredHeight)
                for (key in listOf("header", "stage", "info", "progress", "controls", "actions")) {
                    val view = player.findViewWithTag<View>("native-fs-$key")
                    assertTrue("$name/$w/$key: left", view.left >= 0)
                    assertTrue("$name/$w/$key: right", view.right <= player.width)
                    assertTrue("$name/$w/$key: top", view.top >= 0)
                    assertTrue(
                        "$name/$w/$key: bottom ${view.bottom}>${player.height}",
                        view.bottom <= player.height,
                    )
                    assertTrue("$name/$w/$key: positive size", view.width > 0 && view.height > 0)
                }
                player.setMode("queue")
                player.measure(
                    View.MeasureSpec.makeMeasureSpec(ui.dp(w), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ui.dp(h), View.MeasureSpec.EXACTLY),
                )
                player.layout(0, 0, player.measuredWidth, player.measuredHeight)
                assertTrue(player.findViewWithTag<View>("native-fs-stage").height > 0)
            }
        }
        ui.close()
        lifecycle.pause().stop().destroy()
    }
}
