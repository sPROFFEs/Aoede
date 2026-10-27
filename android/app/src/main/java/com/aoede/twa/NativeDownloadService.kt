package com.aoede.twa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class NativeDownloadService : Service() {
    private val app
        get() = application as AoedeApp

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(
                NotificationChannel(
                    "native_downloads",
                    "Device downloads",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        startForeground(
            2,
            NotificationCompat.Builder(this, "native_downloads")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Aoede · Device downloads")
                .setContentText("Saving music for offline listening")
                .setOnlyAlertOnce(true)
                .build(),
        )
        if (!running) {
            running = true
            worker.execute {
                try {
                    val scope =
                        runCatching { app.nativeLibrary.scope() }.getOrNull() ?: return@execute
                    while (
                        !Thread.currentThread().isInterrupted &&
                            runCatching { scope == app.nativeLibrary.scope() }.getOrDefault(false)
                    ) {
                        val job =
                            app.nativeLibrary.list("download", scope).firstOrNull {
                                it.optString("status") == "pending"
                            } ?: break
                        val id = NativeTrack.reference(job)
                        try {
                            app.nativeLibrary.download(job, scope)
                            job.put("status", "complete")
                        } catch (_: Exception) {
                            job.put("status", "failed")
                        }
                        app.nativeLibrary.put("download", id, job, scope)
                        app.nativeApi.main.post { app.nativeChanged?.invoke() }
                    }
                } catch (_: Exception) {
                    // Keep the private queue for explicit retry after storage/session failure.
                } finally {
                    app.nativeApi.main.post {
                        running = false
                        if (latestStartId != startId && !worker.isShutdown)
                            onStartCommand(null, 0, latestStartId)
                        else stopSelfResult(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
