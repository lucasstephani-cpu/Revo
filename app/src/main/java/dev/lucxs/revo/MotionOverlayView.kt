package dev.lucxs.revo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.random.Random

/**
 * A full-screen field of dots, each with a fixed random depth. Depth
 * controls how far a dot swings for a given camera motion (near = more,
 * far = less - see MotionFusion's class doc for why it's the *negative*
 * of the phone's own estimated displacement), plus its size and opacity,
 * so the field reads as points scattered through actual depth rather than
 * a flat image sliding around - the same cue as looking out a car window.
 *
 * Dots wrap around the screen edges instead of sliding off permanently
 * (like a scrolling starfield): the phone can keep drifting in one
 * direction for as long as the vehicle keeps accelerating that way, but
 * the field has to stay full of dots regardless.
 */
class MotionOverlayView(context: Context) : View(context) {

    private class Dot(val baseXFraction: Float, val baseYFraction: Float, val depth: Float)

    private val density = context.resources.displayMetrics.density

    private var dots: List<Dot> = emptyList()
    private var dotCount = Prefs.PADRAO_QUANTIDADE_PONTOS
    private var opacity = Prefs.PADRAO_OPACIDADE / 100f

    private var phoneShiftX = 0f
    private var phoneShiftY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        // The overlay's window is already touch-transparent (see
        // OverlayService), but returning false here too means this view
        // never even tries to claim a touch sequence if that ever changes.
        isClickable = false
        isFocusable = false
    }

    fun setDotCount(count: Int) {
        dotCount = count.coerceIn(MIN_DOTS, MAX_DOTS)
        regenerateDots()
    }

    fun setOpacity(percent: Int) {
        opacity = percent.coerceIn(0, 100) / 100f
        invalidate()
    }

    fun updateMotion(dx: Float, dy: Float) {
        phoneShiftX = dx
        phoneShiftY = dy
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        regenerateDots()
    }

    private fun regenerateDots() {
        if (width <= 0 || height <= 0) return
        // Fixed seed: regenerating (on a resize, or a dot-count change)
        // keeps the same field instead of visibly reshuffling every dot.
        val random = Random(SEED)
        dots = List(dotCount) {
            Dot(
                baseXFraction = random.nextFloat(),
                baseYFraction = random.nextFloat(),
                depth = MIN_DEPTH + random.nextFloat() * (MAX_DEPTH - MIN_DEPTH),
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || dots.isEmpty()) return

        val pxPerUnit = minOf(w, h) * WORLD_UNIT_TO_SCREEN_FRACTION

        for (dotSpec in dots) {
            val shiftX = -phoneShiftX / dotSpec.depth * pxPerUnit
            val shiftY = -phoneShiftY / dotSpec.depth * pxPerUnit

            val x = wrap(dotSpec.baseXFraction * w + shiftX, w)
            val y = wrap(dotSpec.baseYFraction * h + shiftY, h)

            // depth in [MIN_DEPTH, MAX_DEPTH] makes this land in
            // [1/MAX_DEPTH, 1/MIN_DEPTH] automatically - no extra clamping
            // needed for the radius.
            val depthScale = 1f / dotSpec.depth
            val alphaScale = depthScale.coerceIn(MIN_ALPHA_SCALE, MAX_ALPHA_SCALE)

            paint.alpha = (opacity * alphaScale * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, BASE_RADIUS_DP * density * depthScale, paint)
        }
    }

    private fun wrap(value: Float, max: Float): Float {
        val m = value % max
        return if (m < 0f) m + max else m
    }

    companion object {
        private const val SEED = 1L

        private const val MIN_DOTS = 12
        private const val MAX_DOTS = 240

        private const val MIN_DEPTH = 0.4f
        private const val MAX_DEPTH = 2.5f
        private const val MIN_ALPHA_SCALE = 0.35f
        private const val MAX_ALPHA_SCALE = 1f

        private const val BASE_RADIUS_DP = 2.2f

        // 1.0 "world unit" of MotionFusion's leaky-integrated signal maps
        // to this fraction of the screen's shorter side, for a depth=1
        // (mid-distance) dot. Tuned so a typical few-seconds brake/turn
        // sweeps a noticeable but not disorienting fraction of the screen
        // - see MotionFusion's class doc for the leak time constants this
        // multiplies against.
        private const val WORLD_UNIT_TO_SCREEN_FRACTION = 0.04f
    }
}
