package com.aoede.twa

import android.app.KeyguardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Explicit ADB opt-in, no permissions, private metadata, output changes or playback recovery. */
class AudioDiagnostics(private val app: AoedeApp) {
    companion object {
        const val TAG = "AoedeAudio"
        private const val LIMIT = 600
        private const val WINDOW_MS = 10 * 60 * 1000L
        private val numeric = setOf("jsWallMs", "jsMonoMs", "resource", "position", "ready", "network", "volume", "rate", "error", "decodedBytes", "generation", "liveTracks", "crossfade", "replayLevel", "fadeLevel", "volumeLevel", "contextId", "observedContextId")
        private val boolean = setOf("paused", "ended", "muted", "blocked", "eventPlaying", "closing", "replayGain")
        internal fun sanitize(message: JSONObject): JSONObject {
            val result = JSONObject()
            for (key in numeric) (message.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { result.put(key, it) }
            for (key in boolean) (message.opt(key) as? Boolean)?.let { result.put(key, it) }
            message.optString("context").takeIf { it in setOf("absent", "running", "suspended", "closed", "interrupted") }?.let { result.put("context", it) }
            message.optString("observedContext").takeIf { it in setOf("absent", "running", "suspended", "closed", "interrupted") }?.let { result.put("observedContext", it) }
            message.optString("playbackContext").takeIf { it in setOf("absent", "running", "suspended", "closed", "interrupted") }?.let { result.put("playbackContext", it) }
            message.optString("visibility").takeIf { it in setOf("hidden", "visible", "prerender", "unknown") }?.let { result.put("visibility", it) }
            return result
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var started = -1L
    private var entries = 0
    private var monitoring = false
    private var playerCallback: AudioManager.AudioPlaybackCallback? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var pendingProbeAt = -1L
    private var probeSequence = 0
    private var wakeHeld: (() -> Boolean)? = null
    private var probedView: android.webkit.WebView? = null
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            event(when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_SCREEN_ON -> "screen_on"
                else -> "user_present"
            })
            sample()
        }
    }
    private val pulse = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 5000)
        }
    }

    private fun enabled(): Boolean {
        if (!Log.isLoggable(TAG, Log.DEBUG)) {
            started = -1; entries = 0
            stopMonitoring()
            return false
        }
        if (started < 0) started = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - started >= WINDOW_MS || entries >= LIMIT) {
            stopMonitoring()
            return false
        }
        return true
    }

    private fun monitor() {
        if (monitoring) return
        monitoring = true
        runCatching { ContextCompat.registerReceiver(app, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED) }
        if (Build.VERSION.SDK_INT >= 23) {
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { event("devices_added", devices.size) }
                override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) { event("devices_removed", devices.size) }
            }
            runCatching { audio.registerAudioDeviceCallback(deviceCallback, handler) }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            playerCallback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    write("global_players", JSONObject().put("value", configs?.size ?: 0)
                        .put("globalContentTypes", JSONArray(configs.orEmpty().map { it.audioAttributes.contentType }.distinct().take(16)))
                        .put("globalUsages", JSONArray(configs.orEmpty().map { it.audioAttributes.usage }.distinct().take(16))))
                }
            }
            runCatching { audio.registerAudioPlaybackCallback(playerCallback!!, handler) }
        }
    }

    private fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        runCatching { app.unregisterReceiver(screen) }
        if (Build.VERSION.SDK_INT >= 23) deviceCallback?.let { runCatching { audio.unregisterAudioDeviceCallback(it) } }
        if (Build.VERSION.SDK_INT >= 26) playerCallback?.let { runCatching { audio.unregisterAudioPlaybackCallback(it) } }
        deviceCallback = null; playerCallback = null
    }

    fun start(wakeHeld: () -> Boolean) {
        this.wakeHeld = wakeHeld
        handler.removeCallbacks(pulse)
        pulse.run()
    }

    fun stop() {
        handler.removeCallbacks(pulse)
        stopMonitoring()
        probedView?.takeIf { it === app.player }?.evaluateJavascript("window.__aoedeAudioSnapshot && window.__aoedeAudioSnapshot(false)", null)
        probedView = null
        wakeHeld = null
        pendingProbeAt = -1; probeSequence++
    }

    fun event(name: String, value: Int = 0) {
        if (!enabled()) return
        write(name, JSONObject().put("value", value))
    }

    private fun write(name: String, fields: JSONObject) {
        if (!enabled() || !name.matches(Regex("[a-z_]{1,48}"))) return
        entries++
        // Neither source URLs nor metadata/exception text enter this schema.
        runCatching {
            fields.put("event", name).put("wallMs", System.currentTimeMillis()).put("monoMs", SystemClock.elapsedRealtime())
                .put("nativeState", app.mediaController.state).put("hasWebView", app.player != null)
                .put("serviceRunning", PlaybackService.running).put("islandVisible", app.musicIsland.visible)
                .put("interactive", (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
                .put("keyguard", (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
                .put("globalMusicActive", audio.isMusicActive).put("mode", audio.mode)
                .put("musicVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            wakeHeld?.let { fields.put("wakeHeld", it()) }
            if (Build.VERSION.SDK_INT >= 23) {
                fields.put("musicMuted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
                // Available outputs are not proof of the selected/working Bluetooth route.
                fields.put("availableOutputTypes", JSONArray(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.distinct().take(16)))
            }
            Log.d(TAG, fields.toString())
        }
    }

    fun receive(message: JSONObject) {
        if (!enabled()) return
        val name = message.optString("event")
        val events = setOf("play", "playing", "pause", "ended", "waiting", "stalled", "emptied", "error", "timeupdate", "loadedmetadata", "seeked", "ratechange", "volumechange", "suspend", "abort", "visibilitychange", "pagehide", "pageshow", "context_state", "playback_context_state", "spectrum_create", "spectrum_close", "spectrum_closed", "spectrum_resume")
        val commands = setOf("play", "pause", "stop", "nexttrack", "previoustrack", "seekto", "openplayer", "favorite", "shuffle", "repeat")
        val failures = setOf("spectrum_cleanup_failed", "spectrum_close_failed", "spectrum_resume_failed", "spectrum_error")
        if (name !in events && name !in failures && !(name.startsWith("command_") && name.removePrefix("command_") in commands)) return
        write("web_$name", sanitize(message.optJSONObject("sample") ?: return))
    }

    internal fun sample() {
        val active = enabled()
        if (active) { monitor(); event("probe") }
        val view = app.player ?: return
        if (!active) {
            if (probedView != null) {
                view.evaluateJavascript("window.__aoedeAudioSnapshot && window.__aoedeAudioSnapshot(false)", null)
                probedView = null
            }
            return
        }
        // A blocked renderer cannot spawn an unbounded queue of evaluateJavascript requests.
        val now = SystemClock.elapsedRealtime()
        if (probedView !== view) { pendingProbeAt = -1; probeSequence++ }
        if (pendingProbeAt >= 0) { if (now - pendingProbeAt >= 10000) event("probe_pending"); return }
        val sequence = ++probeSequence
        pendingProbeAt = now
        probedView = view
        view.evaluateJavascript("window.__aoedeAudioSnapshot && window.__aoedeAudioSnapshot(true)") { raw ->
            if (sequence != probeSequence || app.player !== view) return@evaluateJavascript
            pendingProbeAt = -1
            if (enabled()) runCatching {
                write("web_snapshot", sanitize(JSONObject(raw)).put("probeDelayMs", SystemClock.elapsedRealtime() - now))
            }
            else view.evaluateJavascript("window.__aoedeAudioSnapshot && window.__aoedeAudioSnapshot(false)", null)
        }
    }
}
