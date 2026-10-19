package com.aoede.twa

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class PlaybackWidgetTest {
    private val app get() = RuntimeEnvironment.getApplication() as AoedeApp
    private var lifecycle: org.robolectric.android.controller.ActivityController<MainActivity>? = null

    @After fun cleanup() {
        lifecycle?.pause()?.stop()?.destroy()
        app.destroyPlayer()
        PlaybackWidget().onDisabled(app)
    }

    private fun playing(state: String = "paused") {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString(MainActivity.KEY_URL, "https://example.com").commit()
        lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        app.mediaController.receive("""{"type":"metadata","title":"Midnight in Bloom","artist":"Aoede Sessions"}""")
        app.mediaController.updateState(state, 0, 90000)
    }

    @Test fun resizeKeepsEachWidgetsLayoutIndependentAndUpdatesOnOptionsChanges() {
        assertEquals(R.layout.widget_playback_compact, PlaybackWidget.layoutForSize(200, 72))
        assertEquals(R.layout.widget_playback, PlaybackWidget.layoutForSize(288, 144))
        assertEquals(R.layout.widget_playback_expanded, PlaybackWidget.layoutForSize(200, 240))
        assertEquals(R.layout.widget_playback_compact, PlaybackWidget.layoutForSize(320, 144, 2f))
        assertEquals(R.layout.widget_playback, PlaybackWidget.layoutForSize(320, 200, 2f))
        val manager = AppWidgetManager.getInstance(app)
        val first = shadowOf(manager).createWidget(PlaybackWidget::class.java, R.layout.widget_playback)
        val second = shadowOf(manager).createWidget(PlaybackWidget::class.java, R.layout.widget_playback)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 72)
        }
        manager.updateAppWidgetOptions(first, options)
        PlaybackWidget().onAppWidgetOptionsChanged(app, manager, first, options)
        assertNotNull(shadowOf(manager).getViewFor(second).findViewById<TextView>(R.id.widget_title))
        assertEquals(View.GONE, shadowOf(manager).getViewFor(first).findViewById<View>(R.id.widget_next).visibility)
        val largeText = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { fontScale = 2f })
        val compact = PlaybackWidget.views(largeText, R.layout.widget_playback_compact).apply(largeText, FrameLayout(largeText))
        assertEquals(View.GONE, compact.findViewById<View>(R.id.widget_artist).visibility)
        PlaybackWidget.views(app, R.layout.widget_playback_compact).reapply(app, compact)
        assertEquals(View.VISIBLE, compact.findViewById<View>(R.id.widget_artist).visibility)
    }

    @Test fun controlsRetainLocalPartyPauseAndDisabledSkipsCannotOpenTheApp() {
        playing()
        val calls = mutableListOf<String>()
        app.mediaController.setActionInvoker { action, _ -> calls.add(action) }
        val root = PlaybackWidget.views(app, R.layout.widget_playback).apply(app, FrameLayout(app))
        assertFalse(root.findViewById<ImageButton>(R.id.widget_next).isEnabled)
        root.findViewById<ImageButton>(R.id.widget_next).performClick()
        assertTrue(calls.isEmpty())
        assertNull(shadowOf(app).nextStartedActivity)
        app.mediaController.receive("""{"type":"action","action":"nexttrack","enabled":true}""")
        val enabled = PlaybackWidget.views(app, R.layout.widget_playback).apply(app, FrameLayout(app))
        enabled.findViewById<ImageButton>(R.id.widget_next).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("nexttrack"), calls)
        app.mediaController.updateState("playing", 0, 90000)
        PlaybackWidget().onReceive(app, Intent(app, PlaybackWidget::class.java).setAction(PlaybackWidget.ACTION_CONTROL).putExtra("control", "toggle"))
        assertEquals("pause", calls.last())
        assertTrue(app.mediaController.locallyPaused)
        assertEquals("paused", app.mediaController.state)
        val manager = AppWidgetManager.getInstance(app)
        val id = shadowOf(manager).createWidget(PlaybackWidget::class.java, R.layout.widget_playback)
        app.mediaController.receive("""{"type":"metadata","title":"A song","artist":"","album":"First album"}""")
        assertEquals("First album", shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_artist).text.toString())
        app.mediaController.receive("""{"type":"metadata","title":"A song","artist":"","album":"Second album"}""")
        assertEquals("Second album", shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_artist).text.toString())
    }

    @Test @Config(sdk = [34], qualifiers = "xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun artworkPaletteStaysLegibleAndLayoutsFitAtSmallAndLargeFontSizes() {
        val red = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val style = PlaybackWidget.artworkStyle(red)
        assertSame(style, PlaybackWidget.artworkStyle(red))
        assertTrue(Color.red(style.surface.getPixel(0, 0)) > Color.blue(style.surface.getPixel(0, 0)))
        assertTrue(ColorUtils.calculateContrast(Color.WHITE, style.surface.getPixel(0, 0)) >= 7)
        assertTrue(ColorUtils.calculateContrast(style.ink, style.accent) >= 7)
        assertEquals(0, Color.alpha(style.thumbnail!!.getPixel(0, 0)))
        assertEquals(255, Color.alpha(style.cover!!.getPixel(0, 0)))
        val smallScreen = PlaybackWidget.artworkStyle(red, 240)
        assertTrue(smallScreen.cover!!.byteCount + smallScreen.thumbnail!!.byteCount + smallScreen.surface.byteCount <= 6 * 240 * 320)
        val transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertNotNull(PlaybackWidget.artworkStyle(transparent).surface)
        playing()
        app.mediaController.receive("""{"type":"action","action":"nexttrack","enabled":true}""")
        app.mediaController.receive("""{"type":"action","action":"previoustrack","enabled":true}""")
        val art = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(art)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 480f, 480f, 0f, Color.rgb(30, 78, 116), Color.rgb(238, 132, 145), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 480f, 480f, paint)
        paint.shader = null; paint.color = Color.rgb(255, 208, 159)
        canvas.drawCircle(315f, 160f, 98f, paint)
        paint.color = Color.rgb(28, 48, 77)
        canvas.drawPath(Path().apply { moveTo(0f, 350f); lineTo(190f, 210f); lineTo(480f, 470f); lineTo(480f, 480f); lineTo(0f, 480f); close() }, paint)
        val colours = PlaybackWidget.artworkStyle(art)
        for (scale in listOf(1f, 1.3f, 2f)) {
            val configuration = android.content.res.Configuration(app.resources.configuration).apply { fontScale = scale }
            val context = app.createConfigurationContext(configuration)
            for ((width, height) in listOf(200 to 72, 320 to 72, 272 to 136, 288 to 144, 320 to 164, 220 to 260, 360 to 300, 320 to 210)) {
                val layout = PlaybackWidget.layoutForSize(width, height, scale)
                val remote = PlaybackWidget.views(context, layout)
                remote.setImageViewBitmap(R.id.widget_cover, if (layout == R.layout.widget_playback_expanded) colours.cover else colours.thumbnail)
                remote.setInt(R.id.widget_cover, "setImageAlpha", 255)
                remote.setImageViewBitmap(R.id.widget_surface, colours.surface)
                remote.setInt(R.id.widget_toggle_surface, "setColorFilter", colours.accent)
                remote.setInt(R.id.widget_toggle, "setColorFilter", colours.ink)
                val root = remote.apply(context, FrameLayout(context))
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt(); val h = (height * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                for (id in listOf(R.id.widget_title, R.id.widget_artist, R.id.widget_previous, R.id.widget_toggle, R.id.widget_next)) {
                    val child = root.findViewById<View>(id)
                    if (child.visibility != View.VISIBLE) continue
                    val rect = android.graphics.Rect(); child.getDrawingRect(rect)
                    (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(child, rect)
                    assertTrue("$width x $height font=$scale id=$id bounds=$rect", rect.left >= 0 && rect.top >= 0 && rect.right <= w && rect.bottom <= h)
                    if (child is ImageButton) assertTrue(child.width / density >= 48 && child.height / density >= 48)
                    if (child is TextView) assertTrue("Text clips at font $scale: ${child.text}", child.layout.height <= child.height)
                }
                System.getenv("AOEDE_WIDGET_PREVIEWS")?.let { folder ->
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val output = Canvas(bitmap)
                    // Launchers mask the root outline; a software screenshot has no host RenderNode.
                    val radius = context.resources.getDimension(R.dimen.widget_corner_radius)
                    output.clipPath(Path().apply { addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW) })
                    root.draw(output)
                    File(folder).mkdirs()
                    File(folder, "widget-${width}x$height-font$scale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }
}
