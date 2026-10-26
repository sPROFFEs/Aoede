package com.aoede.twa

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.Insets
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
import kotlin.math.roundToInt

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
        activity!!.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun cleanup() {
        island.setEnabled(false)
        app.destroyPlayer()
        activity?.destroy()
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
        assertTrue((view().layoutParams as WindowManager.LayoutParams).y < 44)
        assertNotNull(view().findViewWithTag<View>("handle"))
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

    @Test fun foregroundActivitiesHideTheIslandButAllowLiveCalibration() {
        island.setEnabled(true)
        assertTrue(island.visible)
        activity!!.restart().start().resume()
        assertFalse(island.visible)
        island.refresh(); assertFalse(island.visible)
        island.showSettings(activity!!.get()) {}
        assertTrue(island.visible)
        assertNotNull((view() as FrameLayout).foreground)
        island.closeSettings(activity!!.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(island.visible)
        val setup = Robolectric.buildActivity(SetupActivity::class.java).setup()
        activity!!.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(island.visible) // Another Aoede screen is still visible.
        setup.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(island.visible)
        assertEquals("paused", app.mediaController.state)
    }

    @Test fun horizontalRevealAndHideSurviveRapidForegroundChanges() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean("reduce_effects", false).commit()
        island.setEnabled(true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(1f, view().scaleX, .01f)
        assertEquals(1f, view().scaleY, .01f)
        activity!!.restart().start().resume()
        assertTrue(ReflectionHelpers.getField(island, "hiding"))
        assertTrue((view().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertFalse(island.visible)
        activity!!.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertTrue(island.visible)
        activity!!.restart().start().resume().pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertTrue(island.visible) // A cancelled close cannot remove a newer window.
        assertEquals(1f, view().scaleX, .01f)
    }

    @Test fun handleAndTouchExtensionHaveIndependentSizeAndPositionControls() {
        island.setEnabled(true)
        island.showSettings(activity!!.get()) {}
        val content = ReflectionHelpers.getField<androidx.appcompat.app.AlertDialog>(island, "dialog").window!!.decorView
        val host = view()
        val prefs = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        for (part in listOf("handle", "touch")) for (axis in listOf("width", "height", "top", "horizontal")) {
            val key = "island_${part}_$axis"
            val slider = content.findViewWithTag<android.widget.SeekBar>(key)
            val initial = slider.progress
            content.findViewWithTag<android.widget.Button>("$key-increase").performClick()
            assertEquals(initial + 1, slider.progress)
            assertTrue(prefs.contains(key)); assertSame(host, view())
        }
        content.findViewWithTag<android.widget.SeekBar>("island_handle_horizontal").progress = 0
        content.findViewWithTag<android.widget.SeekBar>("island_touch_horizontal").progress = 200
        content.findViewWithTag<android.widget.SeekBar>("island_touch_height").progress = 112
        val params = host.layoutParams as WindowManager.LayoutParams
        val density = app.resources.displayMetrics.density
        val handle = host.findViewWithTag<View>("handle")
        assertTrue(params.width > 200 * density) // Extension and handle can sit on opposite sides.
        assertEquals((33 * density).roundToInt(), handle.layoutParams.width)
        assertTrue(params.height >= 160 * density)
        assertNotNull((host as FrameLayout).foreground)
        content.findViewWithTag<android.widget.SeekBar>("island_handle_height").progress = 0
        assertNull(host.findViewWithTag<View>("handle"))
        val dialog = ReflectionHelpers.getField<androidx.appcompat.app.AlertDialog>(island, "dialog")
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        for (part in listOf("handle", "touch")) for (axis in listOf("width", "height", "top", "horizontal")) {
            assertFalse(prefs.contains("island_${part}_$axis"))
        }
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

    @Test fun openingTheAppDoesNotRestartTheCompactAnalyserOrPlayback() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean("reduce_effects", false).commit()
        val commands = mutableListOf<String>()
        app.mediaController.setActionInvoker { action, _ -> commands.add(action) }
        app.mediaController.updateState("playing", 125000, 257000)
        island.setEnabled(true); island.show(true)
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
        val player = app.player
        val url = shadowOf(player!!).lastLoadedUrl
        view().findViewWithTag<TextView>("open").performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
        activity!!.newIntent(intent).restart().start().resume()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2500))
        assertSame(player, app.player); assertEquals(url, shadowOf(player).lastLoadedUrl)
        assertEquals(listOf("openplayer"), commands)
        assertEquals("playing", app.mediaController.state)
        assertTrue(app.mediaController.mediaSession.controller.playbackState!!.position in 128000L..128100L)
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
        activity!!.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(island, "sampling"))
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

    @Test fun screenLockRetainsTheWindowAndRevocationOrDisablingRemovesIt() {
        island.setEnabled(true); island.show(true)
        val host = view()
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(island.visible); assertSame(host, view()); assertFalse(island.expanded)
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(island.visible); assertSame(host, view())
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

    @Test fun backgroundPlaybackSurvivesEarlyUnlockSignalsWithoutReopeningActivity() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean("reduce_effects", false).commit()
        app.mediaController.updateState("playing", 125000, 257000)
        island.setEnabled(true); island.show(true)
        val host = view()
        val power = shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        val keyguard = shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        power.setIsInteractive(false); keyguard.setKeyguardLocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(Looper.getMainLooper()).idle()
        assertSame(host, view()); assertFalse(island.expanded)
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); shadowOf(Looper.getMainLooper()).idle()
        power.setIsInteractive(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); shadowOf(Looper.getMainLooper()).idle()
        assertSame(host, view()) // Even an early unlock signal must not remove the window.
        keyguard.setKeyguardLocked(false)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertSame(host, view()); assertTrue(island.visible)
        assertTrue(ReflectionHelpers.getField(island, "sampling"))
        assertTrue((host.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED != 0)
        assertEquals("playing", app.mediaController.state)
        // A completely missed unlock broadcast cannot leave the island detached, either.
        power.setIsInteractive(false); keyguard.setKeyguardLocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(Looper.getMainLooper()).idle()
        power.setIsInteractive(true); keyguard.setKeyguardLocked(false)
        assertSame(host, view())
        app.mediaController.updateState("playing", 126000, 257000)
        assertTrue(ReflectionHelpers.getField(island, "sampling"))
    }

    @Test fun cameraPositionAndAccessibleHandleSupportSizeAndSideCameras() {
        val prefs = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val density = app.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        prefs.edit().putInt("island_offset", 0).putInt("island_height", 28).putInt("island_width", 240)
            .putInt("island_horizontal", -100).commit()
        island.updateSafeInsets(Insets.of(dp(16), dp(48), dp(12), dp(24)))
        island.setEnabled(true)
        val host = view()
        var params = host.layoutParams as WindowManager.LayoutParams
        assertEquals(0, params.y) // Preserve a legacy camera-aligned visual offset.
        assertTrue(params.x >= dp(24))
        assertTrue(params.height >= dp(48))
        assertEquals(dp(28), host.findViewWithTag<View>("capsule").layoutParams.height)
        val handle = host.findViewWithTag<View>("handle")
        assertTrue(params.y + (handle.layoutParams as FrameLayout.LayoutParams).topMargin >= dp(52))
        assertTrue(params.y + params.height >= dp(100)) // 48 dp of input remains below the protected bar.
        host.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, params.width, params.height)
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(0, 0, action, params.width / 2f, dp(76).toFloat(), 0)
            host.dispatchTouchEvent(event); event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(island.expanded) // The part below the status bar opens the same controls.
        island.collapse()
        val left = params.x
        prefs.edit().putInt("island_horizontal", 100).putInt("island_width", 112).putInt("island_height", 72).commit()
        island.relayout()
        assertSame(host, view())
        params = view().layoutParams as WindowManager.LayoutParams
        assertTrue(params.x > left)
        assertTrue(params.height >= dp(100))
        assertEquals(dp(112), params.width)
        val right = params.x + params.width
        val screenWidth = app.resources.displayMetrics.widthPixels
        assertTrue(right <= screenWidth - dp(20))
        island.updateSafeTop(0) // A hidden/edge-to-edge activity must not erase the protected bar inset.
        assertEquals(0, (view().layoutParams as WindowManager.LayoutParams).y)
        assertTrue((view().layoutParams as WindowManager.LayoutParams).height >= dp(100))
        // The new slider is absolute, so users can also move the entire capsule clear of the bar.
        prefs.edit().putInt("island_top", 100).commit(); island.relayout()
        params = view().layoutParams as WindowManager.LayoutParams
        assertEquals(dp(100), params.y)
        assertNull(view().findViewWithTag<View>("handle"))
        assertEquals(dp(72), params.height)
        // Existing nonzero gap preferences keep their intentional lower placement.
        prefs.edit().remove("island_top").putInt("island_gap", 20).commit(); island.relayout()
        assertTrue((view().layoutParams as WindowManager.LayoutParams).y >= dp(72))
    }

    @Test fun repeatedLocksAndExpansionsRetainPlaybackWithAndWithoutEffects() {
        val retained = app.player
        val commands = mutableListOf<String>()
        app.mediaController.setActionInvoker { action, _ -> commands.add(action) }
        val power = shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        val keyguard = shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        for (reduced in listOf(false, true)) {
            app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean("reduce_effects", reduced).commit()
            island.setEnabled(true)
            repeat(20) { cycle ->
                app.mediaController.updateState("playing", 1000L + cycle * 1000, 257000)
                island.refresh()
                assertEquals(!reduced, ReflectionHelpers.getField<Boolean>(island, "sampling"))
                power.setIsInteractive(false); keyguard.setKeyguardLocked(true)
                app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(Looper.getMainLooper()).idle()
                assertFalse(ReflectionHelpers.getField(island, "sampling"))
                power.setIsInteractive(true); keyguard.setKeyguardLocked(false)
                app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); shadowOf(Looper.getMainLooper()).idle()
                island.show(true); assertFalse(ReflectionHelpers.getField(island, "sampling"))
                island.collapse()
                assertEquals(!reduced, ReflectionHelpers.getField<Boolean>(island, "sampling"))
                assertSame(retained, app.player); assertEquals("playing", app.mediaController.state)
            }
            island.setEnabled(false)
        }
        assertTrue(commands.isEmpty()) // No hidden play/pause/seek or player recreation.
    }

    @Test fun spectrumUsesValidatedLevelsAndStopsWithOverlayOrPlayback() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean("reduce_effects", false).commit()
        island.setEnabled(true)
        app.mediaController.updateState("playing", 125000, 257000)
        assertTrue(ReflectionHelpers.getField(island, "sampling"))
        val bars = view().findViewWithTag<View>("bars")
        fun levels() = ReflectionHelpers.getField<FloatArray>(bars, "levels")
        island.updateSpectrum("[0.8,0.3,0.1,0.5,1]")
        assertTrue(levels()[0] > levels()[1]); assertTrue(levels()[4] > levels()[0])
        for (invalid in listOf("null", "[]", "[2,0,0,0,0]", "[null,0,0,0,0]", "[0,0,0,0]")) {
            island.updateSpectrum(invalid)
            assertNull(ReflectionHelpers.getField<FloatArray?>(bars, "levels"))
            assertTrue(ReflectionHelpers.getField(bars, "playing")) // Restore the original animation when analysis is unavailable.
        }
        island.updateSpectrum("[0,0,0,0,0]")
        assertTrue(levels().all { it == 0f }) // Real silence is distinct from unavailable data.
        island.show(true)
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
        island.collapse()
        assertTrue(ReflectionHelpers.getField(island, "sampling"))
        app.mediaController.pauseLocal()
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
        assertTrue(ReflectionHelpers.getField<FloatArray>(view().findViewWithTag<View>("bars"), "levels").all { it == 0f })
        island.setEnabled(false)
        assertFalse(ReflectionHelpers.getField(island, "sampling"))
    }

    @Test fun firstConfigurationAndModeChangesKeepExactlyOneManagedOverlayWindow() {
        val manager = ReflectionHelpers.callInstanceMethod<WindowManager>(island, "getWindows")
        val windows = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(manager)
        val baseline = windows.views.size
        island.setEnabled(true)
        val host = view()
        repeat(6) {
            island.show(true)
            island.relayout()
            app.mediaController.receive("""{"type":"metadata","title":"Song $it","artist":"Aoede"}""")
            island.collapse()
            assertSame(host, view())
            assertEquals(baseline + 1, windows.views.size)
        }
        island.showSettings(activity!!.get()) {}
        island.showSettings(activity!!.get()) {} // A late dismissal must not clear the replacement dialog.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(island.visible)
        assertTrue(ReflectionHelpers.getField<androidx.appcompat.app.AlertDialog>(island, "dialog").isShowing)
        assertTrue((view().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        island.closeSettings(activity!!.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(island.visible)
        assertEquals(0, (view().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(baseline + 1, windows.views.size)
        island.setEnabled(false)
        assertEquals(baseline, windows.views.size)
    }

    @Test fun preciseSettingsStepsUpdateTheSameLivePreviewAndRespectLimits() {
        island.setEnabled(true)
        island.showSettings(activity!!.get()) {}
        val dialog = ReflectionHelpers.getField<androidx.appcompat.app.AlertDialog>(island, "dialog")
        val content = dialog.window!!.decorView
        val host = view()
        val prefs = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        for ((key, value) in listOf("island_width" to 156, "island_height" to 48, "island_top" to 8, "island_horizontal" to 0)) {
            content.findViewWithTag<android.widget.Button>("$key-increase").performClick()
            assertEquals(value + 1, prefs.getInt(key, -999))
            assertSame(host, view())
            content.findViewWithTag<android.widget.Button>("$key-decrease").performClick()
            assertEquals(value, prefs.getInt(key, -999))
        }
        val top = content.findViewWithTag<android.widget.SeekBar>("island_top")
        top.progress = 0
        assertFalse(content.findViewWithTag<android.widget.Button>("island_top-decrease").isEnabled)
        assertEquals(0, (host.layoutParams as WindowManager.LayoutParams).y)
        content.findViewWithTag<android.widget.Button>("island_top-increase").performClick()
        assertTrue((host.layoutParams as WindowManager.LayoutParams).y > 0)
        host.performClick(); assertFalse(island.expanded) // The preview cannot steal input from its controls.
        island.closeSettings(activity!!.get()); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, (view().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        view().performClick(); assertTrue(island.expanded)
    }

    @Test fun recentsHomeFocusLossAndBackCollapseWithoutStoppingMusic() {
        island.setEnabled(true)
        val host = view()
        // A navigation swipe produces DOWN then CANCEL, without the click used by the old backdrop.
        for (ending in listOf(android.view.MotionEvent.ACTION_CANCEL, android.view.MotionEvent.ACTION_UP)) {
            island.show(true)
            val width = app.resources.displayMetrics.widthPixels
            val height = app.resources.displayMetrics.heightPixels
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, ending)) {
                val event = android.view.MotionEvent.obtain(0, 0, action, 1f, height - 1f, 0)
                host.dispatchTouchEvent(event); event.recycle()
                assertFalse(island.expanded)
            }
        }
        for (reason in listOf("recentapps", "homekey")) {
            island.show(true)
            app.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", reason))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(island.expanded)
            assertSame(host, view())
        }
        island.show(true)
        host.onWindowFocusChanged(true)
        host.onWindowFocusChanged(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(island.expanded)
        island.show(true)
        host.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        assertFalse(island.expanded)
        assertTrue(island.visible)
        assertEquals("paused", app.mediaController.state)
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
        val seek = root.findViewWithTag<android.widget.SeekBar>("seek")
        assertTrue("Progress track must stay thin inside its touch target", seek.progressDrawable.bounds.height() <= 4 * density)
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
            island.collapse()
            val compact = view()
            val params = compact.layoutParams as WindowManager.LayoutParams
            compact.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
            compact.layout(0, 0, params.width, params.height)
            val preview = Bitmap.createBitmap(params.width, params.height, Bitmap.Config.ARGB_8888)
            compact.draw(Canvas(preview))
            File(folder, "island-compact-${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

}
