package com.aoede.twa

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject

/** The web's wrapping chip row; children keep their measured text width. */
class NativeFlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val limit = MeasureSpec.getSize(widthSpec)
        var x = 0
        var y = 0
        var row = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            if (x > 0 && x + child.measuredWidth > limit) {
                y += row + gap
                x = 0
                row = 0
            }
            x += child.measuredWidth + gap
            row = maxOf(row, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(limit, widthSpec), resolveSize(y + row, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = 0
        var y = 0
        var row = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > width) {
                y += row + gap
                x = 0
                row = 0
            }
            val start =
                if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - child.measuredWidth else x
            child.layout(start, y, start + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            row = maxOf(row, child.measuredHeight)
        }
    }
}

/** Values measured from the final mobile CSS cascade, not a separate theme palette. */
class NativeDesign(
    context: Context,
    val theme: String,
    val light: Boolean,
    private val effectsReduced: Boolean = false,
) {
    companion object {
        private var themes: JSONObject? = null
    }

    private val density = context.resources.displayMetrics.density
    private val values =
        (themes
                ?: JSONObject(
                        context.assets.open("native/themes.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                    .also { themes = it })
            .getJSONObject(theme)
            .getJSONObject(if (light) "light" else "dark")
    private val colors = values.getJSONObject("colors")

    fun color(name: String): Int {
        if (effectsReduced && theme != "classic")
            when (name) {
                "surface-0",
                "square-film",
                "square-content" ->
                    return if (light) 0xffebecf2.toInt()
                    else if (name == "square-content") 0xff17191f.toInt() else 0xff22242d.toInt()
                "surface-1",
                "modal-background",
                "square-film-strong" -> return if (light) 0xfff7f8fb.toInt() else 0xff292c36.toInt()
            }
        return Color.parseColor(colors.getString(name))
    }

    val background = if (theme == "classic") Color.rgb(18, 18, 18) else color("background")
    val surface = color("surface-0")
    val strong = color("surface-1")
    val content = if (theme == "classic") color("surface-0") else color("square-content")
    val wash = color("surface-2")
    val ink = color("text-main")
    val muted = color("text-sub")
    val faint = color("text-faint")
    var accent = color("primary")
        private set

    fun artworkSeed(seed: Int) {
        if (theme == "glass")
            accent = nativeAccentMix(seed, if (light) 0xff15161a.toInt() else 0xfff7f8fa.toInt())
    }

    val border = color("border-subtle")
    val modal: JSONObject
        get() = values.getJSONObject("modal")

    fun component(name: String): JSONObject = values.getJSONObject("components").getJSONObject(name)

    val blurDp
        get() = values.getJSONObject("material").optDouble("blur", 0.0).toFloat()

    val saturation
        get() = values.getJSONObject("material").optDouble("saturation", 1.0).toFloat()

    val onAccent =
        if (theme == "classic" || (theme == "glass" && !light)) Color.BLACK else Color.WHITE
    val nav = values.getJSONArray("nav").let { a -> (0 until a.length()).map(a::getString) }
    val gutter = if (context.resources.configuration.screenWidthDp < 360) 16 else 20
    val chipRadius = if (theme == "vitrine") 8 else 999
    val cardRadius =
        when (theme) {
            "classic" -> 4
            "glass" -> 24
            else -> 10
        }
    val miniRadius =
        when (theme) {
            "classic" -> 8
            "glass" -> 999
            else -> 12
        }
    val miniHeight = if (theme == "classic") 56 else 64

    fun likedCover(radius: Int) =
        GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (theme == "vitrine") intArrayOf(0xfff97f9f.toInt(), 0xffbd2547.toInt())
                else intArrayOf(0xff4f46e5.toInt(), 0xffec4899.toInt()),
            )
            .apply { cornerRadius = dp(radius).toFloat() }

    fun dp(value: Int) = (value * density + .5f).toInt()

    fun shape(color: Int, radius: Int, outlined: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (outlined) setStroke(maxOf(1, dp(1)), border)
        }

    fun touch(color: Int, radius: Int, outlined: Boolean = false) =
        RippleDrawable(
            ColorStateList.valueOf(if (light) 0x14000000 else 0x20ffffff),
            shape(color, radius, outlined),
            shape(Color.WHITE, radius),
        )

    fun typography(view: TextView, size: Float, weight: Int = 400) {
        view.textSize = size
        view.includeFontPadding = false
        view.typeface =
            if (android.os.Build.VERSION.SDK_INT >= 28)
                Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
            else
                Typeface.create(
                    if (weight in 500..600) "sans-serif-medium" else "sans-serif",
                    if (weight >= 700) Typeface.BOLD else Typeface.NORMAL,
                )
        view.setTextColor(ink)
        lines(view, 1.25f)
        view.letterSpacing = if (size >= 20) -.025f else 0f
    }

    fun lines(view: TextView, multiplier: Float) {
        // CSS line-height is based on font size; Android's multiplier uses font metrics.
        val target = (view.textSize * multiplier + .5f).toInt()
        val metrics = view.paint.fontMetricsInt
        view.setLineSpacing((target - (metrics.descent - metrics.ascent)).toFloat(), 1f)
        view.minHeight =
            if (view is android.widget.Button || view is android.widget.EditText)
                maxOf(view.minHeight, target)
            else target
    }

    fun press(view: View, reduced: Boolean) {
        // API 21 clears the previous animator's target without cancelling its frames.
        view.stateListAnimator?.jumpToCurrentState()
        if (view is android.widget.Button) {
            view.elevation = 0f
            view.translationZ = 0f
        }
        if (reduced) {
            view.stateListAnimator = null
            return
        }
        fun animation(scale: Float, duration: Long) =
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, "scaleX", scale),
                    ObjectAnimator.ofFloat(view, "scaleY", scale),
                )
                this.duration = duration
            }
        view.stateListAnimator =
            StateListAnimator().apply {
                addState(intArrayOf(android.R.attr.state_pressed), animation(.97f, 80))
                addState(intArrayOf(), animation(1f, 160))
            }
    }
}

/** Same sidebar/cards/player miniature used by .theme-preview in the web chooser. */
class NativeThemePreview(context: Context, private val theme: String) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 6f) {
            paint.shader = null
            paint.color = color
            canvas.drawRoundRect(l, t, r, b, radius, radius, paint)
        }
        val classic = theme == "classic"
        val glass = theme == "glass"
        canvas.save()
        canvas.clipPath(
            android.graphics.Path().apply {
                addRoundRect(
                    android.graphics.RectF(0f, 0f, w, h),
                    14 * d,
                    14 * d,
                    android.graphics.Path.Direction.CW,
                )
            }
        )
        rect(
            0f,
            0f,
            w,
            h,
            if (classic) 0xff121212.toInt()
            else if (glass) 0xffe8e5f0.toInt() else 0xffe1e4f1.toInt(),
            0f,
        )
        if (!classic) {
            for ((x, y, color) in
                listOf(
                    Triple(
                        .9f,
                        if (glass) .8f else .9f,
                        if (glass) 0xff88d8cc.toInt() else 0xff88bacc.toInt(),
                    ),
                    Triple(
                        if (glass) .15f else .1f,
                        if (glass) .15f else .1f,
                        if (glass) 0xfff5a88b.toInt() else 0xffefafc6.toInt(),
                    ),
                )) {
                val stop = if (glass && x == .9f) .70f else .65f
                val rx = 1.4142136f * maxOf(x, 1 - x) * w * stop
                val ry = 1.4142136f * maxOf(y, 1 - y) * h * stop
                paint.color = Color.WHITE
                paint.shader =
                    android.graphics
                        .RadialGradient(
                            w * x,
                            h * y,
                            rx,
                            color,
                            Color.TRANSPARENT,
                            android.graphics.Shader.TileMode.CLAMP,
                        )
                        .apply {
                            setLocalMatrix(
                                android.graphics.Matrix().apply {
                                    setScale(1f, ry / rx, w * x, h * y)
                                }
                            )
                        }
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }
        rect(
            10 * d,
            10 * d,
            10 * d + w * .22f,
            h - 30 * d,
            if (classic) 0xff242424.toInt() else if (glass) 0x8cffffff.toInt() else 0x36ffffff,
            if (glass) h else if (classic) 8 * d else 12 * d,
        )
        val left = w * .38f
        val gap = 8 * d
        val cw = (w - 14 * d - left - gap * 2) / 3
        for (i in 0..2) {
            val top = if (!classic && !glass) 28 * d else 30 * d
            val bottom = h - if (!classic && !glass) 46 * d else 45 * d
            if (glass)
                rect(
                    left + i * (cw + gap),
                    top,
                    left + i * (cw + gap) + cw,
                    bottom,
                    0x61ffffff,
                    12 * d,
                )
            else {
                paint.color = Color.WHITE
                val colors =
                    if (classic) intArrayOf(0xff7260bb.toInt(), 0xfff0a97b.toInt())
                    else
                        listOf(
                            intArrayOf(0xffeb8f85.toInt(), 0xffa43061.toInt()),
                            intArrayOf(0xffbba4d9.toInt(), 0xff395b8c.toInt()),
                            intArrayOf(0xffefca7b.toInt(), 0xffa25c38.toInt()),
                        )[i]
                paint.shader =
                    android.graphics.LinearGradient(
                        left + i * (cw + gap),
                        top,
                        left + i * (cw + gap) + cw,
                        bottom,
                        colors,
                        null,
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                canvas.drawRoundRect(
                    left + i * (cw + gap),
                    top,
                    left + i * (cw + gap) + cw,
                    bottom,
                    if (classic) 6 * d else 8 * d,
                    if (classic) 6 * d else 8 * d,
                    paint,
                )
            }
        }
        rect(
            10 * d,
            h - (if (classic || glass) 24 else 32) * d,
            w - 10 * d,
            h - 10 * d,
            if (classic) 0xff1ed760.toInt() else if (glass) 0x8cffffff.toInt() else 0x45ffffff,
            if (classic) 3 * d else h,
        )
        canvas.restore()
    }
}

/** Same 8×8 opaque-pixel sample as appearance.js. */
internal fun nativeArtworkSeed(bitmap: android.graphics.Bitmap): Int {
    val tiny = android.graphics.Bitmap.createScaledBitmap(bitmap, 8, 8, true)
    var r = 0
    var g = 0
    var b = 0
    var count = 0
    for (y in 0..7) for (x in 0..7) {
        val c = tiny.getPixel(x, y)
        if (Color.alpha(c) >= 128) {
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
            count++
        }
    }
    if (tiny !== bitmap) tiny.recycle()
    return if (count == 0) 0xff7c5ce6.toInt()
    else
        Color.rgb(
            (r.toFloat() / count + .5f).toInt(),
            (g.toFloat() / count + .5f).toInt(),
            (b.toFloat() / count + .5f).toInt(),
        )
}

/**
 * CSS color-mix(in oklab, seed 40%, base), with sRGB transfer functions. Public-domain matrices:
 * https://bottosson.github.io/posts/oklab/ .
 */
internal fun nativeAccentMix(seed: Int, base: Int): Int {
    fun lab(color: Int): DoubleArray {
        fun linear(n: Int): Double {
            val x = n / 255.0
            return if (x <= .04045) x / 12.92 else Math.pow((x + .055) / 1.055, 2.4)
        }
        val r = linear(Color.red(color))
        val g = linear(Color.green(color))
        val b = linear(Color.blue(color))
        val l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
        val m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
        val s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
        return doubleArrayOf(
            .2104542553 * l + .7936177850 * m - .0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
            .0259040371 * l + .7827717662 * m - .8086757660 * s,
        )
    }
    val first = lab(seed)
    val second = lab(base)
    val c = DoubleArray(3) { first[it] * .4 + second[it] * .6 }
    val l = Math.pow(c[0] + .3963377774 * c[1] + .2158037573 * c[2], 3.0)
    val m = Math.pow(c[0] - .1055613458 * c[1] - .0638541728 * c[2], 3.0)
    val s = Math.pow(c[0] - .0894841775 * c[1] - 1.2914855480 * c[2], 3.0)
    fun gamma(x: Double): Int {
        val y = if (x <= .0031308) 12.92 * x else 1.055 * Math.pow(x, 1 / 2.4) - .055
        return (y * 255 + .5).toInt().coerceIn(0, 255)
    }
    return Color.rgb(
        gamma(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s),
        gamma(-1.2684380046 * l + 2.6097574011 * m - .3413193965 * s),
        gamma(-.0041960863 * l - .7034186147 * m + 1.7076147010 * s),
    )
}
