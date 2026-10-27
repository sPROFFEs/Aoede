package com.aoede.twa

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.appcompat.widget.AppCompatImageButton

class NativeIcon(private val glyph: String, private val ink: Int) : Drawable() {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.style = if (glyph in setOf("▶", "Ⅱ")) Paint.Style.FILL else Paint.Style.STROKE
        val path =
            Path().apply {
                when (glyph) {
                    "▶" -> {
                        moveTo(8f, 4f)
                        lineTo(21f, 12f)
                        lineTo(8f, 20f)
                        close()
                    }
                    "Ⅱ" -> {
                        addRect(6f, 4f, 10f, 20f, Path.Direction.CW)
                        addRect(14f, 4f, 18f, 20f, Path.Direction.CW)
                    }
                    "›|" -> {
                        moveTo(5f, 4f)
                        lineTo(16f, 12f)
                        lineTo(5f, 20f)
                        close()
                        moveTo(20f, 4f)
                        lineTo(20f, 20f)
                    }
                    "|‹" -> {
                        moveTo(19f, 4f)
                        lineTo(8f, 12f)
                        lineTo(19f, 20f)
                        close()
                        moveTo(4f, 4f)
                        lineTo(4f, 20f)
                    }
                    "‹" -> {
                        moveTo(15f, 5f)
                        lineTo(8f, 12f)
                        lineTo(15f, 19f)
                    }
                    "⌄" -> {
                        moveTo(5f, 8f)
                        lineTo(12f, 15f)
                        lineTo(19f, 8f)
                    }
                    "♡" -> {
                        moveTo(12f, 21f)
                        cubicTo(-10f, 6f, 4f, -2f, 12f, 6f)
                        cubicTo(20f, -2f, 34f, 6f, 12f, 21f)
                        close()
                    }
                    "⤨" -> {
                        moveTo(3f, 4f)
                        lineTo(20f, 20f)
                        moveTo(3f, 20f)
                        lineTo(8f, 15f)
                        moveTo(13f, 9f)
                        lineTo(20f, 3f)
                        lineTo(15f, 3f)
                        moveTo(20f, 3f)
                        lineTo(20f, 8f)
                        moveTo(15f, 20f)
                        lineTo(20f, 20f)
                        lineTo(20f, 15f)
                    }
                    "↻" -> {
                        moveTo(17f, 2f)
                        lineTo(21f, 6f)
                        lineTo(17f, 10f)
                        moveTo(3f, 11f)
                        lineTo(3f, 8f)
                        quadTo(3f, 6f, 5f, 6f)
                        lineTo(21f, 6f)
                        moveTo(7f, 22f)
                        lineTo(3f, 18f)
                        lineTo(7f, 14f)
                        moveTo(21f, 13f)
                        lineTo(21f, 16f)
                        quadTo(21f, 18f, 19f, 18f)
                        lineTo(3f, 18f)
                    }
                    "≡" -> {
                        for (y in listOf(6f, 12f, 18f)) {
                            moveTo(4f, y)
                            lineTo(if (y == 18f) 15f else 20f, y)
                        }
                    }
                    "⇩" -> {
                        moveTo(12f, 3f)
                        lineTo(12f, 16f)
                        moveTo(6f, 10f)
                        lineTo(12f, 16f)
                        lineTo(18f, 10f)
                        moveTo(4f, 18f)
                        lineTo(4f, 21f)
                        lineTo(20f, 21f)
                        lineTo(20f, 18f)
                    }
                    "home" -> {
                        moveTo(3f, 10f)
                        lineTo(12f, 3f)
                        lineTo(21f, 10f)
                        lineTo(21f, 21f)
                        lineTo(15f, 21f)
                        lineTo(15f, 14f)
                        lineTo(9f, 14f)
                        lineTo(9f, 21f)
                        lineTo(3f, 21f)
                        close()
                    }
                    "search" -> {
                        addCircle(10f, 10f, 8f, Path.Direction.CW)
                        moveTo(16f, 16f)
                        lineTo(21f, 21f)
                    }
                    "library" -> {
                        moveTo(4f, 3f)
                        lineTo(4f, 21f)
                        moveTo(9f, 3f)
                        lineTo(9f, 21f)
                        moveTo(14f, 4f)
                        lineTo(20f, 20f)
                    }
                    "discover" -> {
                        addCircle(12f, 12f, 10f, Path.Direction.CW)
                        moveTo(16f, 8f)
                        lineTo(13f, 13f)
                        lineTo(8f, 16f)
                        lineTo(11f, 11f)
                        close()
                    }
                    else -> {
                        paint.style = Paint.Style.FILL
                        for (i in listOf(5f, 12f, 19f)) if (glyph == "⋮")
                            addCircle(12f, i, 1.8f, Path.Direction.CW)
                        else addCircle(i, 12f, 1.8f, Path.Direction.CW)
                    }
                }
            }
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class NativeIconButton(context: Context, private val ink: Int) : AppCompatImageButton(context) {
    var text: String = ""
        set(value) {
            field = value
            setImageDrawable(NativeIcon(value, ink))
        }
}
