package com.aoede.twa

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34], application = AoedeApp::class)
class MusicIslandTest {
    private val app get() = RuntimeEnvironment.getApplication() as AoedeApp
    private val island get() = app.musicIsland
    private var activity: org.robolectric.android.controller.ActivityController<MainActivity>? = null
    private fun view(): View = ReflectionHelpers.getField(island, "root")

    @Before fun setup() {
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString(MainActivity.KEY_URL, "https://example.test").putBoolean("reduce_effects", true).commit()
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        app.mediaController.receive("""{"type":"metadata","title":"The City After Dark","artist":"Aoede Sessions"}""")
        app.mediaController.updateState("paused", 125000, 257000)
    }

    @After fun cleanup() {
        island.setEnabled(false)
        app.destroyPlayer()
        activity?.pause()?.stop()?.destroy()
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun optInPermissionTaskLifetimeAndPlayerDestruction() {
        assertFalse(island.enabled)
        assertFalse(island.visible)
        ShadowSettings.setCanDrawOverlays(false)
        island.setEnabled(true)
        assertFalse(island.enabled)
        assertFalse(island.visible)
        ShadowSettings.setCanDrawOverlays(true)
        island.updateSafeTop(44)
        island.setEnabled(true)
        assertTrue(island.visible)
        assertEquals(44, (view().layoutParams as android.view.WindowManager.LayoutParams).y)
        island.taskRemoved()
        assertFalse(island.visible) // A paused player cannot keep a dismissed app's island alive.
        app.mediaController.updateState("playing", 125000, 257000)
        assertTrue(island.visible) // Active background playback still owns the island.
        app.mediaController.updateState("none", 0, 0)
        assertFalse(island.visible)
        island.taskActive = true; island.refresh()
        assertTrue(island.visible)
        app.destroyPlayer()
        assertFalse(island.visible)
    }

    @Test fun expandControlsAndOutsideTouchShareRealPlayerActions() {
        val commands = mutableListOf<Pair<String, Long>>()
        app.mediaController.setActionInvoker { action, position -> commands.add(action to position) }
        app.mediaController.receive("""{"type":"action","action":"nexttrack","enabled":true}""")
        app.mediaController.receive("""{"type":"action","action":"previoustrack","enabled":true}""")
        app.mediaController.receive("""{"type":"controls","canFavorite":true,"favorite":true,"canShuffle":true,"shuffle":true,"canRepeat":true,"repeat":"one"}""")
        island.setEnabled(true)
        view().performClick()
        assertTrue(island.expanded)
        val panel = view()
        assertEquals("The City After Dark", panel.findViewWithTag<TextView>("title").text.toString())
        assertEquals("2:05", panel.findViewWithTag<TextView>("elapsed").text.toString())
        assertTrue(panel.findViewWithTag<ImageButton>("favorite").isSelected)
        assertEquals(app.getString(R.string.island_repeat_one), panel.findViewWithTag<ImageButton>("repeat").contentDescription)
        for (name in listOf("favorite", "next", "previous", "shuffle", "repeat", "toggle")) panel.findViewWithTag<ImageButton>(name).performClick()
        assertEquals(listOf("favorite", "nexttrack", "previoustrack", "shuffle", "repeat", "play"), commands.map { it.first })
        app.mediaController.updateState("playing", 125000, 257000)
        panel.findViewWithTag<ImageButton>("toggle").performClick()
        assertEquals("pause", commands.last().first)
        assertTrue(app.mediaController.locallyPaused) // Never pauses an entire remote party.
        panel.performClick() // The full-screen area outside the card only collapses it.
        assertFalse(island.expanded)
        assertTrue(island.visible)
        assertTrue(island.enabled)
    }

    @Test fun cardOpensCurrentPlayerWithoutPlaybackAndHonorsUnavailableActions() {
        island.setEnabled(true); island.show(true)
        assertFalse(view().findViewWithTag<ImageButton>("favorite").isEnabled)
        assertFalse(view().findViewWithTag<ImageButton>("next").isEnabled)
        assertFalse(view().findViewWithTag<ImageButton>("shuffle").isEnabled)
        while (shadowOf(app).nextStartedActivity != null) { /* Ignore setup navigation. */ }
        view().findViewWithTag<TextView>("open").performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.getBooleanExtra(MainActivity.OPEN_PLAYER, false))
        assertFalse(island.expanded)
        assertEquals("paused", app.mediaController.state)
    }

    @Test fun settingExplainsPermissionAndOnlyRequestsItAfterUserOptIn() {
        ShadowSettings.setCanDrawOverlays(false)
        var requests = 0
        island.showSettings(activity!!.get()) { requests++ }
        assertEquals(0, requests)
        val dialog = ReflectionHelpers.getField<androidx.appcompat.app.AlertDialog>(island, "dialog")
        val toggle = dialog.window!!.decorView.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("island-toggle")
        assertFalse(toggle.isChecked)
        toggle.performClick()
        assertEquals(1, requests)
        assertFalse(island.enabled)
        assertFalse(island.visible)
    }

    @Test fun screenLockPermissionRevocationAndDisablingRemoveTheWindow() {
        island.setEnabled(true); island.show(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(island.visible)
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(island.visible)
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(island.visible); assertFalse(island.expanded)
        ShadowSettings.setCanDrawOverlays(false); island.refresh()
        assertFalse(island.visible)
        ShadowSettings.setCanDrawOverlays(true); island.refresh()
        assertTrue(island.visible)
        island.setEnabled(false)
        assertFalse(island.visible)
    }

    @Test @Config(sdk = [34]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun panelRespectsLargeTextAndCanProduceNativePreviews() {
        RuntimeEnvironment.setFontScale(1.8f)
        app.mediaController.receive("""{"type":"action","action":"nexttrack","enabled":true}""")
        app.mediaController.receive("""{"type":"controls","canFavorite":true,"canShuffle":true,"canRepeat":true}""")
        island.setEnabled(true); island.show(true)
        val root = view()
        val density = app.resources.displayMetrics.density
        val w = (app.resources.displayMetrics.widthPixels).coerceAtLeast((320 * density).toInt())
        val h = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        for (name in listOf("toggle", "previous", "next", "favorite", "shuffle", "repeat")) {
            val button = root.findViewWithTag<ImageButton>(name)
            assertTrue("$name touch target", button.width / density >= 48 && button.height / density >= 48)
            val rect = android.graphics.Rect(); button.getDrawingRect(rect)
            (root as FrameLayout).offsetDescendantRectToMyCoords(button, rect)
            assertTrue("$name clipped: $rect", rect.left >= 0 && rect.right <= w && rect.top >= 0 && rect.bottom <= h)
        }
        System.getenv("AOEDE_ISLAND_PREVIEWS")?.let { folder ->
            File(folder).mkdirs()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(folder, "island-expanded-${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

}
