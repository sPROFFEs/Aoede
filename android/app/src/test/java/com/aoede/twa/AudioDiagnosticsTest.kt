package com.aoede.twa

import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class AudioDiagnosticsTest {
    private val app get() = RuntimeEnvironment.getApplication() as AoedeApp
    @After fun cleanup() { app.audioDiagnostics.stop(); ShadowLog.setLoggable(AudioDiagnostics.TAG, Log.INFO) }

    @Test fun optInBoundedAndPrivacyFilteredWithoutChangingPlayback() {
        ShadowLog.clear()
        ShadowLog.setLoggable(AudioDiagnostics.TAG, Log.INFO)
        app.audioDiagnostics.event("probe")
        assertTrue(ShadowLog.getLogsForTag(AudioDiagnostics.TAG).isEmpty())
        ShadowLog.setLoggable(AudioDiagnostics.TAG, Log.DEBUG)
        val sample = JSONObject("""{"position":42,"paused":false,"context":"running","visibility":"hidden","url":"https://secret/?token=PRIVATE","title":"PRIVATE","error":"PRIVATE","volume":"PRIVATE","unknown":"PRIVATE"}""")
        app.audioDiagnostics.receive(JSONObject().put("event", "playing").put("sample", sample))
        val entry = ShadowLog.getLogsForTag(AudioDiagnostics.TAG).single().msg
        assertFalse(entry.contains("PRIVATE")); assertFalse(entry.contains("https://"))
        val logged = JSONObject(entry)
        assertEquals(42.0, logged.getDouble("position"), 0.0)
        assertEquals("running", logged.getString("context"))
        assertTrue(logged.has("wallMs")); assertTrue(logged.has("monoMs"))
        assertFalse(logged.has("error")); assertFalse(logged.has("volume"))
        app.audioDiagnostics.receive(JSONObject().put("event", "PRIVATE").put("sample", sample))
        repeat(700) { app.audioDiagnostics.event("probe") }
        assertEquals(600, ShadowLog.getLogsForTag(AudioDiagnostics.TAG).size)
        assertEquals("none", app.mediaController.state)
        assertFalse(PlaybackService.running)
    }

    @Test fun recordingStopsAfterTenMinutes() {
        ShadowLog.setLoggable(AudioDiagnostics.TAG, Log.DEBUG)
        app.audioDiagnostics.event("probe")
        val before = ShadowLog.getLogsForTag(AudioDiagnostics.TAG).size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        app.audioDiagnostics.event("probe")
        assertEquals(before, ShadowLog.getLogsForTag(AudioDiagnostics.TAG).size)
        ShadowLog.setLoggable(AudioDiagnostics.TAG, Log.INFO)
        app.audioDiagnostics.event("probe")
        ShadowLog.setLoggable(AudioDiagnostics.TAG, Log.DEBUG)
        app.audioDiagnostics.event("probe")
        assertEquals(before + 1, ShadowLog.getLogsForTag(AudioDiagnostics.TAG).size)
    }
}
