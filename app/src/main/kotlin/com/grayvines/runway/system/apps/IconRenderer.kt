package com.grayvines.runway.system.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import com.grayvines.runway.data.settings.IconShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Renders app icons to a uniform [IconShape]. [AdaptiveIconDrawable] layers are drawn with their
 * standard extra inset and clipped to the shape mask; legacy non-adaptive drawables are scaled down
 * onto a soft background tinted from the icon's own pixels before applying the same mask.
 */
internal object IconRenderer {
    private const val ROUNDED_SQUARE_RADIUS_FRACTION = 0.22f
    private const val LEGACY_INSET_FRACTION = 0.16f
    private const val SQUIRCLE_EXPONENT = 0.5f // |x|^4 + |y|^4 = 1 => cos/sin raised to 2/4 = 0.5
    private const val SQUIRCLE_STEPS = 64
    private const val MIN_OPAQUE_ALPHA = 128
    private const val TINT_STRENGTH = 0.20f
    private const val WHITE_CHANNEL = 255f
    private const val FULL_ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF

    fun render(
        drawable: Drawable,
        sizePx: Int,
        shape: IconShape,
        badge: ((Bitmap) -> Bitmap)? = null,
    ): Bitmap {
        val output = createBitmap(sizePx, sizePx)
        val canvas = Canvas(output)
        val sizeF = sizePx.toFloat()
        val mask = maskPath(shape, sizePx)

        val maskPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
        val srcInPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }

        canvas.drawPath(mask, maskPaint)
        val layer = canvas.saveLayer(0f, 0f, sizeF, sizeF, srcInPaint)
        if (drawable is AdaptiveIconDrawable) {
            drawAdaptive(canvas, drawable, sizePx)
        } else {
            drawLegacy(canvas, drawable, sizePx)
        }
        canvas.restoreToCount(layer)

        return badge?.invoke(output) ?: output
    }

    private fun drawAdaptive(canvas: Canvas, drawable: AdaptiveIconDrawable, sizePx: Int) {
        val inset = (sizePx * AdaptiveIconDrawable.getExtraInsetFraction()).roundToInt()
        // A fallback surface in case an adaptive icon has a null or translucent background layer.
        canvas.drawColor(Color.WHITE)
        drawable.background?.let { bg ->
            bg.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
            bg.draw(canvas)
        }
        drawable.foreground?.let { fg ->
            fg.setBounds(-inset, -inset, sizePx + inset, sizePx + inset)
            fg.draw(canvas)
        }
    }

    private fun drawLegacy(canvas: Canvas, drawable: Drawable, sizePx: Int) {
        val scratch = createBitmap(sizePx, sizePx)
        val scratchCanvas = Canvas(scratch)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(scratchCanvas)

        val pixels = IntArray(sizePx * sizePx)
        scratch.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        canvas.drawColor(extractIconBackgroundColor(pixels))

        val inset = (sizePx * LEGACY_INSET_FRACTION).roundToInt()
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dst = Rect(inset, inset, sizePx - inset, sizePx - inset)
        canvas.drawBitmap(scratch, null, dst, bitmapPaint)
    }

    private fun maskPath(shape: IconShape, sizePx: Int): Path {
        val sizeF = sizePx.toFloat()
        return when (shape) {
            IconShape.SYSTEM -> systemMaskPath(sizePx)
            IconShape.CIRCLE -> circlePath(sizeF)
            IconShape.ROUNDED_SQUARE -> roundedSquarePath(sizeF)
            IconShape.SQUIRCLE -> squirclePath(sizeF)
        }
    }

    private fun systemMaskPath(sizePx: Int): Path {
        val probe = AdaptiveIconDrawable(null, null)
        probe.setBounds(0, 0, sizePx, sizePx)
        val systemMask = probe.iconMask
        return if (systemMask != null && !systemMask.isEmpty) {
            Path(systemMask)
        } else {
            circlePath(sizePx.toFloat())
        }
    }

    private fun circlePath(sizeF: Float): Path =
        Path().apply { addOval(0f, 0f, sizeF, sizeF, Path.Direction.CW) }

    private fun roundedSquarePath(sizeF: Float): Path =
        Path().apply {
            val r = sizeF * ROUNDED_SQUARE_RADIUS_FRACTION
            addRoundRect(0f, 0f, sizeF, sizeF, r, r, Path.Direction.CW)
        }

    private fun squirclePath(sizeF: Float): Path =
        Path().apply {
            val pts = squirclePoints(sizeF)
            val first = pts.first()
            moveTo(first.first, first.second)
            for (i in 1 until pts.size) {
                lineTo(pts[i].first, pts[i].second)
            }
            close()
        }

    /**
     * Vertices of a superellipse ($|x/r|^4 + |y/r|^4 = 1$) inscribed in `[0, size] × [0, size]`.
     */
    internal fun squirclePoints(
        size: Float,
        steps: Int = SQUIRCLE_STEPS,
    ): List<Pair<Float, Float>> {
        val r = size / 2f
        return List(steps) { i ->
            val theta = 2.0 * PI * i / steps
            val cosT = cos(theta).toFloat()
            val sinT = sin(theta).toFloat()
            val x = r + r * sign(cosT) * abs(cosT).pow(SQUIRCLE_EXPONENT)
            val y = r + r * sign(sinT) * abs(sinT).pow(SQUIRCLE_EXPONENT)
            x to y
        }
    }

    /**
     * A soft pastel background colour derived from the opaque pixels of a legacy icon: 20% of the
     * icon's average opaque RGB blended onto white, so the scaled-down icon always has contrast
     * against its plate.
     */
    internal fun extractIconBackgroundColor(pixels: IntArray): Int {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (pixel in pixels) {
            val alpha = pixel ushr FULL_ALPHA_SHIFT and CHANNEL_MASK
            if (alpha >= MIN_OPAQUE_ALPHA) {
                sumR += pixel ushr RED_SHIFT and CHANNEL_MASK
                sumG += pixel ushr GREEN_SHIFT and CHANNEL_MASK
                sumB += pixel and CHANNEL_MASK
                count++
            }
        }
        if (count == 0) return Color.WHITE
        val avgR = sumR.toFloat() / count
        val avgG = sumG.toFloat() / count
        val avgB = sumB.toFloat() / count
        val base = WHITE_CHANNEL * (1f - TINT_STRENGTH)
        val r = (base + avgR * TINT_STRENGTH).roundToInt().coerceIn(0, CHANNEL_MASK)
        val g = (base + avgG * TINT_STRENGTH).roundToInt().coerceIn(0, CHANNEL_MASK)
        val b = (base + avgB * TINT_STRENGTH).roundToInt().coerceIn(0, CHANNEL_MASK)
        return CHANNEL_MASK shl FULL_ALPHA_SHIFT or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
    }
}
