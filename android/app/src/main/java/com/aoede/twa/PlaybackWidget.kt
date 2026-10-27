package com.aoede.twa

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.util.SizeF
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.core.os.BundleCompat
import kotlin.math.max

class PlaybackWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        update(context, intArrayOf(id))

    override fun onDisabled(context: Context) {
        cachedArtwork = null
        cachedStyle = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CONTROL) return
        val action = intent.getStringExtra("control")
        if (action !in setOf("toggle", "next", "previous")) return
        val app = context.applicationContext as AoedeApp
        val media = app.mediaController
        if (!app.hasPlayer || media.state in setOf("none", "error")) {
            update(context)
            try { context.startActivity(MainActivity.playerIntent(context, false)) }
            catch (_: android.content.ActivityNotFoundException) { /* The refreshed widget still offers a direct activity intent. */ }
            catch (_: SecurityException) { /* A stale control intent must not crash when background launch is denied. */ }
            return
        }
        when (action) {
            "toggle" -> if (media.needsForeground) media.pauseLocal() else media.command("play")
            "next" -> if (media.supports("nexttrack")) media.command("nexttrack")
            "previous" -> if (media.supports("previoustrack")) media.command("previoustrack")
        }
    }

    companion object {
        const val ACTION_CONTROL = "com.aoede.twa.WIDGET_CONTROL"
        private var cachedArtwork: Bitmap? = null
        private var cachedStyle: ArtworkStyle? = null
        private var cachedEdge = 0

        internal data class ArtworkStyle(val cover: Bitmap?, val thumbnail: Bitmap?, val surface: Bitmap, val accent: Int, val ink: Int)

        // Sample a small grid; colour matching stays bounded regardless of the cover's resolution.
        internal fun artworkStyle(art: Bitmap?, maxEdge: Int = 480): ArtworkStyle {
            val edge = maxEdge.coerceIn(2, 480)
            cachedStyle?.takeIf { cachedArtwork === art && cachedEdge == edge }?.let { return it }
            val reds = IntArray(12); val greens = IntArray(12); val blues = IntArray(12); val weights = IntArray(12)
            val hsv = FloatArray(3)
            // Dominant hue, rather than mixing complementary colours into a grey background.
            if (art != null) for (y in 0 until 8) for (x in 0 until 8) {
                val pixel = art.getPixel(((x + .5f) * art.width / 8).toInt(), ((y + .5f) * art.height / 8).toInt())
                if (Color.alpha(pixel) < 128) continue
                Color.colorToHSV(pixel, hsv)
                val bucket = (hsv[0] / 30).toInt().coerceIn(0, 11)
                val weight = max(8, (hsv[1] * 255).toInt())
                reds[bucket] += Color.red(pixel) * weight; greens[bucket] += Color.green(pixel) * weight
                blues[bucket] += Color.blue(pixel) * weight; weights[bucket] += weight
            }
            val dominant = weights.indices.maxBy { weights[it] }
            var base = if (weights[dominant] > 0) Color.rgb(reds[dominant] / weights[dominant],
                greens[dominant] / weights[dominant], blues[dominant] / weights[dominant]) else Color.rgb(119, 101, 161)
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(base, hsl)
            if (hsl[1] > .12f) { hsl[2] = hsl[2].coerceIn(.42f, .6f); base = ColorUtils.HSLToColor(hsl) }
            var start = ColorUtils.blendARGB(base, Color.BLACK, .42f)
            while (ColorUtils.calculateContrast(Color.WHITE, start) < 7) start = ColorUtils.blendARGB(start, Color.BLACK, .06f)
            // Cover + thumbnail + gradient use at most 6 * edge² bytes, within the
            // launcher's 6 * screenWidth * screenHeight bitmap budget, even on old phones.
            val small = edge / 2
            val surface = Bitmap.createBitmap(small, small, Bitmap.Config.ARGB_8888)
            Canvas(surface).drawRect(0f, 0f, small.toFloat(), small.toFloat(), Paint(Paint.DITHER_FLAG).apply {
                shader = LinearGradient(0f, 0f, small.toFloat(), small.toFloat(), start, ColorUtils.blendARGB(start, Color.BLACK, .65f), Shader.TileMode.CLAMP)
            })
            return ArtworkStyle(art?.let { crop(it, edge, 0f) }, art?.let { crop(it, small, small * .175f) }, surface,
                ColorUtils.blendARGB(base, Color.WHITE, .78f), ColorUtils.blendARGB(base, Color.BLACK, .85f))
                .also { cachedArtwork = art; cachedStyle = it; cachedEdge = edge }
        }

        private fun crop(art: Bitmap, edge: Int, radius: Float): Bitmap {
            val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
            val scale = max(edge.toFloat() / art.width, edge.toFloat() / art.height)
            val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate((edge - art.width * scale) / 2, (edge - art.height * scale) / 2)
            })
            Canvas(bitmap).drawRoundRect(0f, 0f, edge.toFloat(), edge.toFloat(), radius, radius,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
            return bitmap
        }

        internal fun layoutForSize(width: Int, height: Int, fontScale: Float = 1f): Int {
            val extra = ((fontScale - 1).coerceAtLeast(0f) * 48).toInt()
            return when {
                height >= 220 + extra -> R.layout.widget_playback_expanded
                width >= 272 && height >= 136 + extra -> R.layout.widget_playback
                else -> R.layout.widget_playback_compact
            }
        }

        internal fun views(context: Context, layout: Int, decorated: Boolean = true): RemoteViews {
            val app = context.applicationContext as AoedeApp
            val media = app.mediaController
            val metadata = media.metadata
            val active = app.hasPlayer && media.state !in setOf("none", "error")
            val open = PendingIntent.getActivity(context, R.id.widget_info, MainActivity.playerIntent(context, active),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val metrics = context.resources.displayMetrics
            val edge = minOf(metrics.widthPixels, metrics.heightPixels, 480)
            val views = RemoteViews(context.packageName, layout)
            if (decorated) {
                val style = artworkStyle(if (active) metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) else null, edge)
                views.setViewVisibility(R.id.widget_surface, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_surface, style.surface)
                if (style.cover != null) views.setImageViewBitmap(R.id.widget_cover, if (layout == R.layout.widget_playback_expanded) style.cover else style.thumbnail)
                else views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher_foreground)
                views.setInt(R.id.widget_cover, "setImageAlpha", if (style.cover == null && layout == R.layout.widget_playback_expanded) 85 else 255)
                views.setInt(R.id.widget_toggle_surface, "setColorFilter", style.accent)
                views.setInt(R.id.widget_toggle, "setColorFilter", style.ink)
            } else {
                views.setViewVisibility(R.id.widget_surface, View.GONE)
                views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher_foreground)
            }
            views.setTextViewText(R.id.widget_title, if (active) metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
                .ifBlank { context.getString(R.string.app_name) } else context.getString(R.string.widget_open))
            views.setTextViewText(R.id.widget_artist, if (active) metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty()
                .ifBlank { metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM).orEmpty() } else context.getString(R.string.widget_empty_hint))
            val compact = layout == R.layout.widget_playback_compact
            views.setViewVisibility(R.id.widget_previous, if (compact) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_next, if (compact) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_artist, if (compact && context.resources.configuration.fontScale > 1.3f) View.GONE else View.VISIBLE)
            if (!compact) {
                views.setViewVisibility(R.id.widget_status, if (active) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.widget_status, context.getString(when (media.state) {
                    "playing" -> R.string.widget_playing
                    "buffering" -> R.string.widget_buffering
                    else -> R.string.widget_paused
                }))
            }
            views.setOnClickPendingIntent(android.R.id.background, open)
            views.setOnClickPendingIntent(R.id.widget_info, open)
            views.setOnClickPendingIntent(R.id.widget_cover, open)
            views.setImageViewResource(R.id.widget_toggle, if (media.needsForeground) R.drawable.ic_pause else R.drawable.ic_play)
            views.setContentDescription(R.id.widget_toggle, context.getString(if (!active) R.string.widget_cover else if (media.needsForeground) R.string.playback_action_pause else R.string.playback_action_play))
            for ((id, action, enabled) in listOf(Triple(R.id.widget_toggle, "toggle", active),
                Triple(R.id.widget_next, "next", active && media.supports("nexttrack")),
                Triple(R.id.widget_previous, "previous", active && media.supports("previoustrack")))) {
                val pending = if (enabled) PendingIntent.getBroadcast(context, id,
                    Intent(context, PlaybackWidget::class.java).setAction(ACTION_CONTROL).putExtra("control", action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else if (!active) open else null
                views.setOnClickPendingIntent(id, pending)
                views.setBoolean(id, "setEnabled", enabled || !active)
                views.setInt(id, "setImageAlpha", if (enabled || !active) 255 else 70)
            }
            return views
        }

        fun update(context: Context, ids: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val widgets = ids ?: manager.getAppWidgetIds(ComponentName(context, PlaybackWidget::class.java))
            if (widgets.isEmpty()) return
            val layouts = mutableMapOf<Int, RemoteViews>()
            fun layout(id: Int) = layouts.getOrPut(id) { views(context, id) }
            val scale = context.resources.configuration.fontScale
            for (id in widgets) try {
                val options = manager.getAppWidgetOptions(id)
                val sizes = if (Build.VERSION.SDK_INT >= 31) BundleCompat.getParcelableArrayList(options,
                    AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java) else null
                if (Build.VERSION.SDK_INT >= 31 && !sizes.isNullOrEmpty()) {
                    val extra = ((scale - 1).coerceAtLeast(0f) * 48)
                    manager.updateAppWidget(id, RemoteViews(mapOf(SizeF(200f, 72f) to layout(R.layout.widget_playback_compact),
                        SizeF(272f, 136f + extra) to layout(R.layout.widget_playback), SizeF(200f, 220f + extra) to layout(R.layout.widget_playback_expanded))))
                    continue
                }
                val portrait = layoutForSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 272),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 136), scale)
                val landscape = layoutForSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 272),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 136), scale)
                manager.updateAppWidget(id, if (portrait == landscape) layout(portrait)
                    else RemoteViews(layout(landscape), layout(portrait)))
            } catch (error: RuntimeException) {
                Log.w("AoedeWidget", "Widget host update failed; trying a basic layout", error)
                try { manager.updateAppWidget(id, views(context, R.layout.widget_playback_compact, decorated = false)) }
                catch (fallback: RuntimeException) { Log.w("AoedeWidget", "Widget host rejected the basic layout", fallback) }
            }
        }
    }
}
