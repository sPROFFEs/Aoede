package com.aoede.twa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class PlaybackTest {
    private val app get() = RuntimeEnvironment.getApplication() as AoedeApp
    private var service: org.robolectric.android.controller.ServiceController<PlaybackService>? = null

    @Before fun installedSignaturePermission() {
        // These cases protect the preserved installed-web-client compatibility path.
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean("use_native_player", false).commit()
        // Android grants this merged AndroidX signature permission at install time.
        // Robolectric API 21 needs the install-time grant represented explicitly.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @After fun cleanup() {
        app.destroyPlayer()
        service?.destroy()
        service = null
        PlaybackService.starting=false
    }

    @Test fun foregroundNotificationExistsBeforeFirstStartCommand(){
        val lifecycle=Robolectric.buildService(PlaybackService::class.java).create()
        service=lifecycle
        assertNotNull(shadowOf(lifecycle.get()).lastForegroundNotification)
        assertEquals(PlaybackService.NOTIFICATION_ID,shadowOf(lifecycle.get()).lastForegroundNotificationId)
        assertFalse(PlaybackService.starting)
    }

    @Test @Config(sdk = [34]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun offlineArtworkCrossesTheBridgeWithoutHttpAndRejectsActiveFormats() {
        val pixels = IntArray(128 * 128) { java.util.Random(it.toLong()).nextInt() or 0xFF000000.toInt() }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, 128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
        val encoded = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        assertTrue(encoded.length > 2048)
        fun receive(cover: String) {
            app.mediaController.receive(org.json.JSONObject().put("type", "metadata").put("title", "Offline track")
                .put("offline", true).put("artwork", cover).toString())
            org.robolectric.util.ReflectionHelpers.getField<java.util.concurrent.Future<*>>(app.mediaController, "artworkJob")
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        receive("data:image/png;base64,$encoded")
        val art = app.mediaController.metadata.getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        assertNotNull(art); assertEquals(128, art!!.width)
        receive("data:image/svg+xml;base64,$encoded")
        assertNull(app.mediaController.metadata.getBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART))
    }

    @Test fun originsAndArtworkAreBounded() {
        val origin = ServerOrigin.from(Uri.parse("https://EXAMPLE.com"))
        assertEquals(origin, ServerOrigin.from(Uri.parse("https://example.com:443/portal")))
        assertNotEquals(origin, ServerOrigin.from(Uri.parse("http://example.com")))
        assertNotEquals(origin, ServerOrigin.from(Uri.parse("https://example.com:8443")))
        for (url in listOf("file:///x", "https://user:password@example.com", "https://example.com:bad", "https://example.com:70000")) {
            assertNull(ServerOrigin.from(Uri.parse(url)))
        }
        assertEquals(32, MediaController.artworkSampleSize(32, 16000))
        assertNull(MediaController.artworkSampleSize(-1, 20))
        assertNull(MediaController.artworkSampleSize(20, 200000))
    }

    @Test fun activityRecreationRetainsTheDocumentAndControls() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString(MainActivity.KEY_URL, "https://example.com").commit()
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val view = app.player
        assertNotNull(view)
        first.pause().stop().destroy()
        assertSame(view, app.player)
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertSame(view, app.player)
        second.pause().stop().destroy()
        assertEquals("none", app.mediaController.state)
        assertFalse(PlaybackService.running)
    }

    @Test fun noisyPauseAndIdleStopDoNotResumeOrLeaveAGhostService() {
        val media = app.mediaController
        val calls = mutableListOf<String>()
        media.setActionInvoker { action, _ -> calls.add(action) }
        media.updateState("playing", 1000, 9000)
        val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        service = lifecycle
        assertEquals(android.app.Service.START_NOT_STICKY, lifecycle.get().onStartCommand(null, 0, 1))
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("pause"), calls)
        assertEquals("paused", media.state)
        media.updateState("playing", 2000, 9000)
        assertEquals("paused", media.state)
        assertEquals(0f, media.mediaSession.controller.playbackState!!.playbackSpeed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        lifecycle.destroy()
        service = null
        assertFalse(PlaybackService.running)
        media.command("play")
        media.updateState("playing", 3000, 9000)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, media.mediaSession.controller.playbackState!!.state)
    }

    @Test fun bridgeValidationAndActionsRunOnTheMainThread() {
        val media = app.mediaController
        val thread = Thread {
            media.receive("{\"type\":\"action\",\"action\":\"nexttrack\",\"enabled\":true}")
            media.receive("{\"type\":\"state\",\"state\":\"paused\",\"position\":-2,\"duration\":9000}")
            media.receive("invalid")
        }
        thread.start(); thread.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, media.mediaSession.controller.playbackState!!.position)
        assertTrue(media.mediaSession.controller.playbackState!!.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
        media.reset()
        assertFalse(media.mediaSession.isActive)
        assertEquals(PlaybackStateCompat.STATE_NONE, media.mediaSession.controller.playbackState!!.state)
    }
}
