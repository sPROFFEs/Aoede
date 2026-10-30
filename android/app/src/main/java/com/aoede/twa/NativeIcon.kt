package com.aoede.twa

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.PathParser
import org.json.JSONObject

/** The web themes' original SVG paths, including their coordinate systems. */
@android.annotation.SuppressLint(
    "RestrictedApi"
) // Core 1.12 is pinned; only bundled web vectors reach its parser.
class NativeIcon(
    glyph: String,
    ink: Int,
    theme: String = "classic",
    filled: Boolean = false,
    private val size: Int = 24,
) : Drawable() {
    companion object {
        private var definitions = JSONObject()
        private var intrinsic = 24

        fun install(context: Context) {
            intrinsic = (24 * context.resources.displayMetrics.density + .5f).toInt()
            if (definitions.length() == 0)
                definitions =
                    JSONObject(
                        context.assets.open("native/icons.json").bufferedReader().use {
                            it.readText()
                        }
                    )
        }

        private val names =
            mapOf(
                "▶" to "play",
                "Ⅱ" to "pause",
                "›|" to "skip-forward",
                "|‹" to "skip-back",
                "‹" to "chevron-left",
                "⌄" to "chevron-down",
                "♡" to "heart",
                "⤨" to "shuffle",
                "↻" to "repeat",
                "≡" to "list-music",
                "⇩" to "download",
                "discover" to "compass",
                "⋮" to "ellipsis-vertical",
                "•••" to "ellipsis",
                "trash" to "trash-2",
                "+" to "plus",
            )
    }

    private val name = names[glyph] ?: glyph
    private val definition =
        definitions.optJSONObject(theme)?.let {
            (if (filled) it.optJSONObject("$name-filled") else null) ?: it.optJSONObject(name)
        }
    private val box =
        definition?.optString("viewBox")?.split(' ')?.map(String::toFloat)
            ?: listOf(0f, 0f, 24f, 24f)
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style =
                if (definition?.optBoolean("fill") == true || filled) Paint.Style.FILL
                else Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val paths = mutableListOf<Pair<Path, String>>()

    init {
        val nodes = definition?.optJSONArray("nodes")
        for (i in 0 until (nodes?.length() ?: 0)) {
            val node = nodes!!.getJSONArray(i)
            val a = node.getJSONObject(1)
            fun n(key: String) = a.optDouble(key, 0.0).toFloat()
            val p =
                when (node.getString(0)) {
                    "path" -> PathParser.createPathFromPathData(a.getString("d")) ?: Path()
                    "circle" ->
                        Path().apply { addCircle(n("cx"), n("cy"), n("r"), Path.Direction.CW) }
                    "ellipse" ->
                        Path().apply {
                            addOval(
                                RectF(
                                    n("cx") - n("rx"),
                                    n("cy") - n("ry"),
                                    n("cx") + n("rx"),
                                    n("cy") + n("ry"),
                                ),
                                Path.Direction.CW,
                            )
                        }
                    "rect" ->
                        Path().apply {
                            addRoundRect(
                                RectF(n("x"), n("y"), n("x") + n("width"), n("y") + n("height")),
                                n("rx"),
                                n("rx"),
                                Path.Direction.CW,
                            )
                        }
                    "line" ->
                        Path().apply {
                            moveTo(n("x1"), n("y1"))
                            lineTo(n("x2"), n("y2"))
                        }
                    "polyline",
                    "polygon" ->
                        Path().apply {
                            val points =
                                a.getString("points")
                                    .trim()
                                    .split(Regex("[ ,]+"))
                                    .map(String::toFloat)
                            points.chunked(2).forEachIndexed { j, pair ->
                                if (j == 0) moveTo(pair[0], pair[1]) else lineTo(pair[0], pair[1])
                            }
                            if (node.getString(0) == "polygon") close()
                        }
                    else -> Path()
                }
            paths.add(p to a.optString("transform"))
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        val scale = minOf(bounds.width() / box[2], bounds.height() / box[3])
        canvas.translate(
            bounds.left + (bounds.width() - box[2] * scale) / 2,
            bounds.top + (bounds.height() - box[3] * scale) / 2,
        )
        canvas.scale(scale, scale)
        canvas.translate(-box[0], -box[1])
        for ((path, transform) in paths) {
            canvas.save()
            for (match in Regex("(translate|scale)\\(([^)]+)\\)").findAll(transform)) {
                val values = match.groupValues[2].trim().split(Regex("[ ,]+")).map(String::toFloat)
                if (match.groupValues[1] == "translate")
                    canvas.translate(values[0], values.getOrElse(1) { 0f })
                else canvas.scale(values[0], values.getOrElse(1) { values[0] })
            }
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getIntrinsicWidth() = intrinsic * size / 24

    override fun getIntrinsicHeight() = intrinsic * size / 24

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class NativeIconButton(
    context: Context,
    private val ink: Int,
    private val theme: String = "classic",
) : AppCompatImageButton(context) {
    private var drawnColor = ink
    private var drawnFilled = false

    init {
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }

    var text: String = ""
        set(value) {
            if (field == value && drawable != null) return
            field = value
            setImageDrawable(NativeIcon(value, drawnColor, theme, drawnFilled))
        }

    fun color(value: Int, filled: Boolean = false) {
        if (drawnColor == value && drawnFilled == filled && drawable != null) return
        drawnColor = value
        drawnFilled = filled
        setImageDrawable(NativeIcon(text, value, theme, filled))
    }
}
