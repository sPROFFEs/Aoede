package com.aoede.twa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class PlaybackTest {
    private val app get() = RuntimeEnvironment.getApplication() as AoedeApp

    @After fun cleanup() { app.destroyPlayer() }

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
