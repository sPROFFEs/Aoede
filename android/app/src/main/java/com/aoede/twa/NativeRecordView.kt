package com.aoede.twa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.os.SystemClock
import androidx.appcompat.widget.AppCompatImageView

/** The disc spins inside the artwork view so track swipes can still transform the entire view. */
internal open class NativeRecordView(context: Context, val vinyl: Boolean) :
    AppCompatImageView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Path()
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
        val radius = minOf(w, h) / 2f
        label.rewind()
        label.addCircle(w / 2f, h / 2f, radius * .56f, Path.Direction.CW)
        reflection =
            SweepGradient(
                w / 2f,
                h / 2f,
                intArrayOf(0x00ffffff, 0x38ffffff, 0x00ffffff, 0x00ff8cb4, 0x55ff8cb4, 0x00ffffff),
                floatArrayOf(0f, .18f, .28f, .56f, .69f, 1f),
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
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = 0xff3b2e45.toInt()
        paint.strokeWidth = (radius * .009f).coerceAtLeast(.6f)
        for (groove in 6..14) canvas.drawCircle(x, y, radius * groove / 15f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = reflection
        canvas.drawCircle(x, y, radius, paint)
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
        paint.color = 0xff1b1524.toInt()
        canvas.drawCircle(x, y, (radius * .045f).coerceAtLeast(1f), paint)
        canvas.restoreToCount(layer)
        if (startedAt != 0L && isShown && windowVisibility == VISIBLE) postInvalidateDelayed(33)
    }
}
