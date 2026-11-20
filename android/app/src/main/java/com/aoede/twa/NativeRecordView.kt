package com.aoede.twa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import androidx.appcompat.widget.AppCompatImageView

/** The disc spins inside the artwork view so track swipes can still transform the entire view. */
internal open class NativeRecordView(context: Context, val vinyl: Boolean) :
    AppCompatImageView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Path()
    private val grooves = Path()
    private val bands = Path()
    private var vinylSurface: RadialGradient? = null
    private var labelLight: RadialGradient? = null
    private var rimLight: SweepGradient? = null
    private var reflection: SweepGradient? = null
    private var startedAt = 0L
    private var stoppedAngle = 0f

    val recordAngle: Float
        get() =
            if (startedAt == 0L) stoppedAngle
            else (stoppedAngle + (SystemClock.uptimeMillis() - startedAt) * .03f) % 360f

    fun spin(playing: Boolean) {
        if (!vinyl || playing == (startedAt != 0L)) return
        stoppedAngle = recordAngle
        startedAt = if (playing) SystemClock.uptimeMillis().coerceAtLeast(1) else 0L
        invalidate()
    }

    fun freeze(angle: Float) {
        spin(false)
        stoppedAngle = angle
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!vinyl) return
        val x = w / 2f
        val y = h / 2f
        val radius = (minOf(x, y) - .5f).coerceAtLeast(1f)
        label.rewind()
        label.addCircle(x, y, radius * .56f, Path.Direction.CW)
        grooves.rewind()
        bands.rewind()
        val count = (radius / 2.8f).toInt().coerceIn(4, 38)
        for (groove in 0 until count) {
            val path = if (groove % 7 == 0) bands else grooves
            path.addCircle(x, y, radius * (.595f + .365f * groove / count), Path.Direction.CW)
        }
        vinylSurface =
            RadialGradient(
                x,
                y,
                radius,
                intArrayOf(
                    0xff73436b.toInt(),
                    0xff73436b.toInt(),
                    0xff0d0814.toInt(),
                    0xff282038.toInt(),
                    0xff110c19.toInt(),
                    0xff31283b.toInt(),
                    0xff09060f.toInt(),
                ),
                floatArrayOf(0f, .55f, .573f, .6f, .94f, .985f, 1f),
                Shader.TileMode.CLAMP,
            )
        labelLight =
            RadialGradient(
                x,
                y,
                radius * .56f,
                intArrayOf(0x00ffffff, 0x06ffffff, 0x8c0f081b.toInt()),
                floatArrayOf(0f, .78f, 1f),
                Shader.TileMode.CLAMP,
            )
        rimLight =
            SweepGradient(
                x,
                y,
                intArrayOf(0x00ffffff, 0x99f6c9e7.toInt(), 0x00ffffff, 0x65ffc4ae, 0x00ffffff),
                floatArrayOf(0f, .18f, .4f, .7f, 1f),
            )
        reflection =
            SweepGradient(
                x,
                y,
                intArrayOf(
                    0x00ffffff,
                    0x00ffffff,
                    0x6db4dbff,
                    0x24ecbaff,
                    0x00ffffff,
                    0x00ff8cb4,
                    0x62ff8cb4,
                    0x24ffe1ce,
                    0x00ffffff,
                ),
                floatArrayOf(0f, .09f, .16f, .2f, .26f, .56f, .66f, .74f, 1f),
            )
    }

    override fun onDraw(canvas: Canvas) {
        if (!vinyl) {
            super.onDraw(canvas)
            return
        }
        val x = width / 2f
        val y = height / 2f
        val radius = minOf(x, y) - .5f
        if (radius <= 0) return
        val layer = canvas.save()
        canvas.rotate(recordAngle, x, y)
        paint.color = 0xff1b1524.toInt()
        paint.shader = vinylSurface
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = 0x42cdb3eb
        paint.strokeWidth = (radius * .0028f).coerceAtLeast(.65f)
        canvas.drawPath(grooves, paint)
        paint.color = 0x664b3a59
        paint.strokeWidth *= 2.2f
        canvas.drawPath(bands, paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        paint.shader = reflection
        canvas.drawCircle(x, y, radius * .976f, paint)
        paint.style = Paint.Style.STROKE
        paint.shader = rimLight
        paint.strokeWidth = (radius * .008f).coerceAtLeast(.9f)
        canvas.drawCircle(x, y, radius * .985f, paint)
        canvas.drawCircle(x, y, radius * .582f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0xff8d4d79.toInt()
        canvas.drawCircle(x, y, radius * .56f, paint)
        if (drawable != null) {
            val artwork = canvas.save()
            canvas.clipPath(label)
            canvas.scale(.56f, .56f, x, y)
            super.onDraw(canvas)
            canvas.restoreToCount(artwork)
        }
        paint.color = android.graphics.Color.WHITE
        paint.shader = labelLight
        canvas.drawCircle(x, y, radius * .56f, paint)
        paint.shader = null
        val spindle = (radius * .032f).coerceAtLeast(1f)
        paint.color = 0xffd5a3c0.toInt()
        canvas.drawCircle(x, y, spindle * 1.23f, paint)
        paint.color = 0xff100914.toInt()
        canvas.drawCircle(x, y, spindle, paint)
        canvas.restoreToCount(layer)
        if (startedAt != 0L && isShown && windowVisibility == VISIBLE) postInvalidateDelayed(33)
    }
}
