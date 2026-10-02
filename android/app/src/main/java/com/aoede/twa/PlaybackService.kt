package com.aoede.twa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver

class PlaybackService : Service() {
    companion object {
        const val CHANNEL_ID = "aoede_playback"
        const val NOTIFICATION_ID = 1
        var running = false
            private set
    }
    private val controller get() = (application as AoedeApp).mediaController
    private val notifications get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var noisyRegistered = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private val stopIdle = Runnable { stopSelf() }
    private var bufferingTimeoutScheduled = false
    private val stopBuffering = Runnable {
        bufferingTimeoutScheduled = false
        if (controller.state == "buffering") controller.pauseLocal()
    }
    private val renewWakeLock = object : Runnable {
        override fun run() {
            if (!controller.needsForeground) return
            wakeLock.acquire(10 * 60 * 1000L)
            handler.postDelayed(this, 9 * 60 * 1000L)
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) controller.pauseLocal()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                getString(R.string.playback_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.playback_channel_desc)
                setShowBadge(false)
            })
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aoede:Playback").apply { setReferenceCounted(false) }
        controller.onChanged = { updatePlayback() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always fulfil startForegroundService's deadline, including an obsolete media button intent.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
        if (intent?.action == "com.aoede.STOP") {
            controller.command("stop")
            controller.reset()
        } else MediaButtonReceiver.handleIntent(controller.mediaSession, intent)
        updatePlayback()
        return START_NOT_STICKY
    }

    // MediaStyle notifications carrying a MediaSession token are exempt from POST_NOTIFICATIONS.
    @SuppressLint("NotificationPermission")
    private fun updatePlayback() {
        handler.removeCallbacks(stopIdle)
        handler.removeCallbacks(renewWakeLock)
        if (controller.state == "buffering" && !bufferingTimeoutScheduled) {
            bufferingTimeoutScheduled = true
            handler.postDelayed(stopBuffering, 120000)
        } else if (controller.state != "buffering") {
            handler.removeCallbacks(stopBuffering)
            bufferingTimeoutScheduled = false
        }
        if (controller.needsForeground) {
            renewWakeLock.run()
            if (!noisyRegistered) {
                ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED)
                noisyRegistered = true
            }
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false }
            if (controller.state in setOf("none", "error")) { stopSelf(); return }
            // Keep controls briefly for a pause/resume, then relinquish the foreground service.
            handler.postDelayed(stopIdle, 60000)
        }
        if (foreground) notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val metadata = controller.mediaSession.controller.metadata
        val playing = controller.needsForeground
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PlaybackService::class.java).setAction("com.aoede.STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.playback_notif_title))
            .setContentText(metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            .setLargeIcon(metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART))
            .setSmallIcon(R.drawable.ic_notification).setContentIntent(open).setDeleteIntent(stop)
            .setOnlyAlertOnce(true).setOngoing(playing).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val actions = controller.mediaSession.controller.playbackState?.actions ?: 0
        var count = 0
        fun add(icon: Int, label: Int, action: Long) {
            builder.addAction(NotificationCompat.Action(icon, getString(label),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)))
            count++
        }
        if (actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L) {
            add(R.drawable.ic_skip_previous, R.string.playback_action_prev, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        }
        add(if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            if (playing) R.string.playback_action_pause else R.string.playback_action_play,
            if (playing) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY)
        if (actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L) {
            add(R.drawable.ic_skip_next, R.string.playback_action_next, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        }
        builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(controller.mediaSession.sessionToken).setShowActionsInCompactView(*(0 until count).toList().toIntArray()))
        return builder.build()
    }

    override fun onDestroy() {
        running = false
        controller.onChanged = null
        handler.removeCallbacksAndMessages(null)
        if (noisyRegistered) unregisterReceiver(noisy)
        if (wakeLock.isHeld) wakeLock.release()
        @Suppress("DEPRECATION")
        stopForeground(true)
        notifications.cancel(NOTIFICATION_ID)
        super.onDestroy()
        (application as AoedeApp).ensurePlaybackService()
    }
}
