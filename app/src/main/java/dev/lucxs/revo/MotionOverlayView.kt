package dev.lucxs.revo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws two concentric rings of dots hugging the screen edge. Both rings
 * share the same motion cue from MotionFusion, but the outer ring moves
 * more than the inner one - the same parallax trick as looking out a car
 * window, where the near foreground slides past faster than the distant
 * background. That difference is what reads as "depth" instead of the
 * whole screen just wobbling uniformly.
 *
 * Dots sit only in a band near the edges (not scattered across the middle)
 * so they stay in peripheral vision and don't cover whatever the user is
 * actually reading.
 */
class MotionOverlayView(context: Context) : View(context) {

    private var dotCount = Prefs.PADRAO_QUANTIDADE_PONTOS
    private var opacity = Prefs.PADRAO_OPACIDADE / 100f

    private var offsetX = 0f
    private var offsetY = 0f
    private var spinRadians = 0f

    private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        // The overlay's window is already touch-transparent (see
        // OverlayService), but returning false here too means this view
        // never even tries to claim a touch sequence if that ever changes.
        isClickable = false
        isFocusable = false
    }

    fun setDotCount(count: Int) {
        dotCount = count.coerceIn(6, 48)
        invalidate()
    }

    fun setOpacity(percent: Int) {
        opacity = percent.coerceIn(0, 100) / 100f
        invalidate()
    }

    fun updateMotion(dx: Float, dy: Float, spin: Float) {
        offsetX = dx
        offsetY = dy
        spinRadians = spin
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val inset = min(w, h) * 0.05f
        val rx = cx - inset
        val ry = cy - inset

        // MotionFusion's posX/posY are arbitrary spring units (see its own
        // MAX_OFFSET comment); this is the one place that turns them into
        // an actual, screen-size-relative pixel distance.
        val maxPixelDrift = min(w, h) * 0.03f
        val pxX = (offsetX / 40f) * maxPixelDrift
        val pxY = (offsetY / 40f) * maxPixelDrift

        paintInner.alpha = (opacity * 255).toInt()
        paintOuter.alpha = (opacity * 0.7f * 255).toInt()

        drawRing(
            canvas,
            cx, cy, rx * 0.92f, ry * 0.92f,
            driftScale = 0.6f, pxX = pxX, pxY = pxY,
            radiusPx = min(w, h) * 0.006f,
            paint = paintInner,
        )
        drawRing(
            canvas,
            cx, cy, rx, ry,
            driftScale = 1.3f, pxX = pxX, pxY = pxY,
            radiusPx = min(w, h) * 0.009f,
            paint = paintOuter,
        )
    }

    private fun drawRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        driftScale: Float,
        pxX: Float,
        pxY: Float,
        radiusPx: Float,
        paint: Paint,
    ) {
        val driftedX = pxX * driftScale
        val driftedY = pxY * driftScale
        for (i in 0 until dotCount) {
            val angle = (i.toFloat() / dotCount) * TWO_PI + spinRadians
            val x = cx + rx * cos(angle) + driftedX
            val y = cy + ry * sin(angle) + driftedY
            canvas.drawCircle(x, y, radiusPx, paint)
        }
    }

    companion object {
        private const val TWO_PI = (Math.PI * 2).toFloat()
    }
}
