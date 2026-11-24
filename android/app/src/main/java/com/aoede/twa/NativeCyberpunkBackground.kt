package com.aoede.twa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock

internal class NativeCyberpunkBackground(context: Context) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Path()
    private val spacing = 36 * context.resources.displayMetrics.density
    private val frame = Runnable { invalidateSelf() }
    private var scene: Shader? = null
    private var start = 0L

    var moving = false
        set(value) {
            if (field == value) return
            field = value
            start = SystemClock.uptimeMillis()
            unscheduleSelf(frame)
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        scene = LinearGradient(
            bounds.right.toFloat(), bounds.top.toFloat(),
            bounds.left.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(0xff472236.toInt(), BASE, 0xff14343c.toInt()),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP,
        )
        grid.reset()
        var x = bounds.left.toFloat()
        while (x < bounds.right) {
            grid.moveTo(x, bounds.top.toFloat())
            grid.lineTo(x, bounds.bottom.toFloat())
            x += spacing
        }
        var y = bounds.top.toFloat()
        while (y < bounds.bottom) {
            grid.moveTo(bounds.left.toFloat(), y)
            grid.lineTo(bounds.right.toFloat(), y)
            y += spacing
        }
    }

    override fun draw(canvas: Canvas) {
        paint.shader = scene
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = 0x1aff6177
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawPath(grid, paint)
        if (moving && isVisible) {
            val now = SystemClock.uptimeMillis()
            val phase = ((now - start) % 17000L) / 17000f
            val y = bounds.top + phase * bounds.height()
            paint.color = 0x4dff6177
            paint.alpha = (77 * (minOf(phase, 1f - phase) / .09f).coerceIn(0f, 1f)).toInt()
            canvas.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, paint)
            scheduleSelf(frame, now + 33L)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        val BASE = 0xff100d15.toInt()
        val RED = 0xffff6177.toInt()
        val CYAN = 0xff68f2e4.toInt()
    }
}
