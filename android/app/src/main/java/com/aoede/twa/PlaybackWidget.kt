package com.aoede.twa

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.widget.RemoteViews

class PlaybackWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CONTROL) return
        val app = context.applicationContext as AoedeApp
        val media = app.mediaController
        if (app.player == null || media.state in setOf("none", "error")) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        when (intent.getStringExtra("control")) {
            "toggle" -> if (media.needsForeground) media.pauseLocal() else media.command("play")
            "next" -> if (media.supports("nexttrack")) media.command("nexttrack")
            "previous" -> if (media.supports("previoustrack")) media.command("previoustrack")
        }
    }

    companion object {
        const val ACTION_CONTROL = "com.aoede.twa.WIDGET_CONTROL"

        fun update(context: Context, ids: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val widgets = ids ?: manager.getAppWidgetIds(ComponentName(context, PlaybackWidget::class.java))
            if (widgets.isEmpty()) return
            val app = context.applicationContext as AoedeApp
            val media = app.mediaController
            val metadata = media.metadata
            val active = app.player != null && media.state !in setOf("none", "error")
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(context.packageName, R.layout.widget_playback)
            views.setTextViewText(R.id.widget_title, if (active) metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty().ifBlank { context.getString(R.string.app_name) } else context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_artist, if (active) metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty() else context.getString(R.string.widget_open))
            val art = if (active) metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) else null
            if (art != null) {
                val edge = maxOf(art.width, art.height)
                views.setImageViewBitmap(R.id.widget_cover, android.graphics.Bitmap.createScaledBitmap(art,
                    (art.width * 160 / edge).coerceAtLeast(1), (art.height * 160 / edge).coerceAtLeast(1), true))
            } else views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
            views.setOnClickPendingIntent(R.id.widget_info, open)
            views.setOnClickPendingIntent(R.id.widget_cover, open)
            views.setImageViewResource(R.id.widget_toggle, if (media.needsForeground) R.drawable.ic_pause else R.drawable.ic_play)
            views.setContentDescription(R.id.widget_toggle, context.getString(if (media.needsForeground) R.string.playback_action_pause else R.string.playback_action_play))
            for ((id, action, enabled) in listOf(Triple(R.id.widget_toggle, "toggle", active),
                Triple(R.id.widget_next, "next", active && media.supports("nexttrack")),
                Triple(R.id.widget_previous, "previous", active && media.supports("previoustrack")))) {
                val pending = if (enabled) PendingIntent.getBroadcast(context, id,
                    Intent(context, PlaybackWidget::class.java).setAction(ACTION_CONTROL).putExtra("control", action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else open
                views.setOnClickPendingIntent(id, pending)
                views.setInt(id, "setImageAlpha", if (enabled || !active) 255 else 90)
            }
            for (id in widgets) manager.updateAppWidget(id, views)
        }
    }
}
