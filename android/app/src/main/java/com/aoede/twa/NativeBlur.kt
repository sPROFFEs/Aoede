package com.aoede.twa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View

/** Small separable box passes approximate the same Gaussian material on API 21+. */
internal fun blurNative(source: Bitmap, radius: Int = 4): Bitmap {
    val width = source.width
    val height = source.height
    var pixels = IntArray(width * height)
    var output = IntArray(pixels.size)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val r = radius.coerceIn(1, 16)
    val window = r * 2 + 1
    repeat(3) {
        for (vertical in listOf(false, true)) {
            val major = if (vertical) width else height
            val length = if (vertical) height else width
            val step = if (vertical) width else 1
            for (line in 0 until major) {
                val start = if (vertical) line else line * width
                var red = 0
                var green = 0
                var blue = 0
                var alpha = 0
                for (k in -r..r) {
                    val c = pixels[start + k.coerceIn(0, length - 1) * step]
                    red += Color.red(c)
                    green += Color.green(c)
                    blue += Color.blue(c)
                    alpha += Color.alpha(c)
                }
                for (i in 0 until length) {
                    output[start + i * step] =
                        Color.argb(alpha / window, red / window, green / window, blue / window)
                    val remove = pixels[start + (i - r).coerceIn(0, length - 1) * step]
                    val add = pixels[start + (i + r + 1).coerceIn(0, length - 1) * step]
                    red += Color.red(add) - Color.red(remove)
                    green += Color.green(add) - Color.green(remove)
                    blue += Color.blue(add) - Color.blue(remove)
                    alpha += Color.alpha(add) - Color.alpha(remove)
                }
            }
            val swap = pixels
            pixels = output
            output = swap
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

/** Crops one scene in screen coordinates, including surfaces in a separate dialog window. */
class NativeBackdrop(
    private val scene: View,
    private val target: View,
    private val tint: Int,
    private val radius: Float,
    private val rim: Int,
    private val glint: Int = Color.TRANSPARENT,
    private val shade: Int = Color.TRANSPARENT,
    private val bottomRadius: Float = radius,
    private val blurDp: Float = 22f,
    private val saturation: Float = 1.35f,
) : Drawable(), View.OnAttachStateChangeListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var snapshot: Bitmap? = null
    private var last = 0L
    private val location = IntArray(2)
    private val origin = IntArray(2)
    private var observer: android.view.ViewTreeObserver? = null
    private val scroll =
        android.view.ViewTreeObserver.OnScrollChangedListener { target.invalidate() }
    private val layout =
        android.view.ViewTreeObserver.OnGlobalLayoutListener { target.invalidate() }

    init {
        target.addOnAttachStateChangeListener(this)
        if (target.isAttachedToWindow) onViewAttachedToWindow(target)
    }

    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds)
        canvas.save()
        val corners =
            floatArrayOf(
                radius,
                radius,
                radius,
                radius,
                bottomRadius,
                bottomRadius,
                bottomRadius,
                bottomRadius,
            )
        canvas.clipPath(Path().apply { addRoundRect(rect, corners, Path.Direction.CW) })
        if (
            scene.width > 0 &&
                scene.height > 0 &&
                (snapshot == null || SystemClock.uptimeMillis() - last >= 40)
        ) {
            last = SystemClock.uptimeMillis()
            val bitmap =
                Bitmap.createBitmap(
                    (scene.width / 7).coerceAtLeast(1),
                    (scene.height / 7).coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            val capture = Canvas(bitmap)
            capture.scale(
                bitmap.width.toFloat() / scene.width,
                bitmap.height.toFloat() / scene.height,
            )
            (scene.parent as? View)?.let { parent ->
                parent.getLocationOnScreen(origin)
                scene.getLocationOnScreen(location)
                capture.save()
                capture.translate(
                    (origin[0] - location[0]).toFloat(),
                    (origin[1] - location[1]).toFloat(),
                )
                parent.background?.draw(capture)
                capture.restore()
            }
            scene.draw(capture)
            val sigma =
                blurDp * target.resources.displayMetrics.density * bitmap.width / scene.width
            val kernel =
                ((kotlin.math.sqrt(1f + 4f * sigma * sigma) - 1f) / 2f + .5f)
                    .toInt()
                    .coerceIn(0, 32)
            val blurred = blurNative(bitmap, kernel)
            bitmap.recycle()
            snapshot?.recycle()
            snapshot = blurred
        } else if (scene.width > 0 && scene.height > 0) {
            target.postInvalidateDelayed(
                (40 - (SystemClock.uptimeMillis() - last)).coerceAtLeast(1)
            )
        }
        snapshot?.let { image ->
            scene.getLocationOnScreen(origin)
            target.getLocationOnScreen(location)
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            val x = (origin[0] - location[0]).toFloat()
            val y = (origin[1] - location[1]).toFloat()
            paint.colorFilter =
                android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(saturation) }
                )
            canvas.drawBitmap(image, null, RectF(x, y, x + scene.width, y + scene.height), paint)
            paint.colorFilter = null
        }
        paint.style = Paint.Style.FILL
        paint.color = shade
        canvas.drawRect(rect, paint)
        paint.color = tint
        canvas.drawRect(rect, paint)
        if (Color.alpha(glint) > 0) {
            paint.color = Color.WHITE
            paint.shader =
                android.graphics.LinearGradient(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    intArrayOf(glint, Color.TRANSPARENT, Color.TRANSPARENT),
                    floatArrayOf(0f, .55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            canvas.drawRect(rect, paint)
            paint.shader = null
        }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = target.resources.displayMetrics.density
        paint.color = rim
        val edge = RectF(rect).apply { inset(paint.strokeWidth / 2, paint.strokeWidth / 2) }
        canvas.drawPath(Path().apply { addRoundRect(edge, corners, Path.Direction.CW) }, paint)
    }

    override fun onViewAttachedToWindow(view: View) {
        observer = scene.viewTreeObserver
        observer?.addOnScrollChangedListener(scroll)
        observer?.addOnGlobalLayoutListener(layout)
    }

    override fun onViewDetachedFromWindow(view: View) {
        observer
            ?.takeIf { it.isAlive }
            ?.let {
                it.removeOnScrollChangedListener(scroll)
                it.removeOnGlobalLayoutListener(layout)
            }
        observer = null
        snapshot?.eraseColor(Color.TRANSPARENT)
        snapshot?.recycle()
        snapshot = null
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(filter: ColorFilter?) {}

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The one Glass body artwork layer; individual materials crop this same scene. */
class NativeArtworkBackground(private val base: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var original: Bitmap? = null
    private var blurred: Bitmap? = null

    fun artwork(bitmap: Bitmap?) {
        if (original === bitmap) return
        original = bitmap
        blurred?.recycle()
        blurred = null
        if (bitmap != null) {
            val tiny = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            blurred = blurNative(tiny, 4)
            if (tiny !== bitmap) tiny.recycle()
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(base)
        val image = blurred
        if (image != null) {
            paint.alpha = 31
            paint.colorFilter =
                android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(.75f) }
                )
            val size = maxOf(bounds.width(), bounds.height()) * 1.3f
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            canvas.drawBitmap(
                image,
                null,
                RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2),
                paint,
            )
            paint.colorFilter = null
            paint.alpha = 255
        } else {
            canvas.save()
            canvas.clipRect(bounds)
            paint.shader =
                android.graphics.RadialGradient(
                    bounds.width() * .12f,
                    bounds.height() * .08f,
                    maxOf(1, bounds.height()).toFloat() * .6f,
                    0x079a76d1,
                    Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            canvas.drawRect(bounds, paint)
            paint.shader =
                android.graphics.RadialGradient(
                    bounds.width() * .95f,
                    bounds.height() * .85f,
                    maxOf(1, bounds.height()).toFloat() * .55f,
                    0x0540b9ae,
                    Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            canvas.drawRect(bounds, paint)
            paint.shader = null
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(filter: ColorFilter?) {}

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}
