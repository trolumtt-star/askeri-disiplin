package com.askeri.disiplin

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Hareket animasyonu: kod ile çizilen çöp adam, iki poz arasında gidip gelir.
 * Harici GIF/Lottie dosyası gerekmez.
 */
class ExerciseAnimView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** periodMs: tek yön hareket süresi, alternate: ikinci bacak ters fazda çizilir */
    enum class Move(val periodMs: Long, val alternate: Boolean) {
        CRUNCH(1300, false),
        LEG_RAISE(1800, false),
        PLANK(2500, false),
        BICYCLE(900, true),
        CLIMBER(550, true),
        TWIST(1000, false),
        FLUTTER(450, true),
        SIDE_PLANK(2500, false),
        DEAD_BUG(1600, true)
    }

    // Noktalar (x,y) 0..1: baş, omuz, dirsek, el, kalça, diz, ayak
    private val poses: Map<Move, Pair<FloatArray, FloatArray>> = mapOf(
        Move.CRUNCH to (floatArrayOf(0.20f,0.70f, 0.28f,0.73f, 0.22f,0.63f, 0.17f,0.68f, 0.55f,0.74f, 0.68f,0.55f, 0.80f,0.75f) to
                        floatArrayOf(0.32f,0.53f, 0.37f,0.61f, 0.30f,0.52f, 0.28f,0.56f, 0.55f,0.74f, 0.68f,0.55f, 0.80f,0.75f)),
        Move.LEG_RAISE to (floatArrayOf(0.16f,0.71f, 0.24f,0.73f, 0.34f,0.75f, 0.44f,0.75f, 0.55f,0.74f, 0.70f,0.74f, 0.86f,0.74f) to
                           floatArrayOf(0.16f,0.71f, 0.24f,0.73f, 0.34f,0.75f, 0.44f,0.75f, 0.55f,0.74f, 0.60f,0.56f, 0.63f,0.38f)),
        Move.PLANK to (floatArrayOf(0.20f,0.50f, 0.28f,0.55f, 0.28f,0.74f, 0.37f,0.74f, 0.55f,0.59f, 0.70f,0.66f, 0.86f,0.74f) to
                       floatArrayOf(0.20f,0.48f, 0.28f,0.53f, 0.28f,0.74f, 0.37f,0.74f, 0.55f,0.56f, 0.70f,0.65f, 0.86f,0.74f)),
        Move.BICYCLE to (floatArrayOf(0.27f,0.55f, 0.33f,0.62f, 0.40f,0.55f, 0.30f,0.52f, 0.55f,0.74f, 0.48f,0.57f, 0.60f,0.50f) to
                         floatArrayOf(0.27f,0.56f, 0.33f,0.63f, 0.26f,0.57f, 0.30f,0.52f, 0.55f,0.74f, 0.70f,0.66f, 0.86f,0.62f)),
        Move.CLIMBER to (floatArrayOf(0.20f,0.44f, 0.28f,0.50f, 0.28f,0.62f, 0.28f,0.74f, 0.55f,0.55f, 0.42f,0.62f, 0.50f,0.74f) to
                         floatArrayOf(0.20f,0.44f, 0.28f,0.50f, 0.28f,0.62f, 0.28f,0.74f, 0.55f,0.55f, 0.70f,0.64f, 0.86f,0.74f)),
        Move.TWIST to (floatArrayOf(0.40f,0.36f, 0.42f,0.45f, 0.53f,0.54f, 0.62f,0.58f, 0.48f,0.72f, 0.62f,0.57f, 0.76f,0.62f) to
                       floatArrayOf(0.40f,0.36f, 0.42f,0.45f, 0.35f,0.56f, 0.30f,0.62f, 0.48f,0.72f, 0.62f,0.57f, 0.76f,0.62f)),
        Move.FLUTTER to (floatArrayOf(0.16f,0.71f, 0.24f,0.73f, 0.34f,0.75f, 0.44f,0.75f, 0.55f,0.74f, 0.70f,0.68f, 0.86f,0.62f) to
                         floatArrayOf(0.16f,0.71f, 0.24f,0.73f, 0.34f,0.75f, 0.44f,0.75f, 0.55f,0.74f, 0.70f,0.71f, 0.86f,0.70f)),
        Move.SIDE_PLANK to (floatArrayOf(0.20f,0.38f, 0.28f,0.45f, 0.28f,0.62f, 0.28f,0.74f, 0.55f,0.60f, 0.70f,0.67f, 0.86f,0.74f) to
                            floatArrayOf(0.20f,0.35f, 0.28f,0.42f, 0.28f,0.62f, 0.28f,0.74f, 0.55f,0.54f, 0.70f,0.64f, 0.86f,0.74f)),
        Move.DEAD_BUG to (floatArrayOf(0.16f,0.71f, 0.24f,0.73f, 0.25f,0.60f, 0.26f,0.47f, 0.55f,0.74f, 0.55f,0.55f, 0.68f,0.55f) to
                          floatArrayOf(0.16f,0.71f, 0.24f,0.73f, 0.16f,0.66f, 0.08f,0.62f, 0.55f,0.74f, 0.70f,0.68f, 0.86f,0.70f))
    )

    var move: Move = Move.CRUNCH
        set(value) { if (field != value) { field = value; restart() } }

    /** Dinlenmede figür yavaşça "nefes alır" */
    var resting: Boolean = false
        set(value) { if (field != value) { field = value; restart() } }

    private var t = 0f
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8F0D0"); strokeWidth = 9 * density
        strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val ghost = Paint(body).apply { color = Color.parseColor("#7A8F4A") }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8F0D0") }
    private val floor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D4A2A"); strokeWidth = 4 * density
    }

    private fun restart() {
        animator?.cancel()
        if (!isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (resting) 2600 else move.periodMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (a, b) = poses.getValue(move)
        val tt = if (resting) t * 0.12f else t
        val pt = FloatArray(14) { i -> a[i] + (b[i] - a[i]) * tt }

        // 1.4:1 oranlı çizim kutusu, ortalanmış
        val bw = minOf(width.toFloat(), height * 1.4f)
        val bh = bw / 1.4f
        val ox = (width - bw) / 2f
        val oy = (height - bh) / 2f
        fun x(v: Float) = ox + v * bw
        fun y(v: Float) = oy + v * bh

        canvas.drawLine(x(0.03f), y(0.765f), x(0.97f), y(0.765f), floor)

        if (move.alternate && !resting) {
            val r = 1f - tt
            val kx = a[10] + (b[10] - a[10]) * r; val ky = a[11] + (b[11] - a[11]) * r
            val fx = a[12] + (b[12] - a[12]) * r; val fy = a[13] + (b[13] - a[13]) * r
            canvas.drawLine(x(pt[8]), y(pt[9]), x(kx), y(ky), ghost)
            canvas.drawLine(x(kx), y(ky), x(fx), y(fy), ghost)
        }
        // gövde + kol + bacak
        canvas.drawLine(x(pt[0]), y(pt[1]), x(pt[2]), y(pt[3]), body)   // boyun
        canvas.drawLine(x(pt[2]), y(pt[3]), x(pt[4]), y(pt[5]), body)   // üst kol
        canvas.drawLine(x(pt[4]), y(pt[5]), x(pt[6]), y(pt[7]), body)   // ön kol
        canvas.drawLine(x(pt[2]), y(pt[3]), x(pt[8]), y(pt[9]), body)   // gövde
        canvas.drawLine(x(pt[8]), y(pt[9]), x(pt[10]), y(pt[11]), body) // uyluk
        canvas.drawLine(x(pt[10]), y(pt[11]), x(pt[12]), y(pt[13]), body) // baldır
        canvas.drawCircle(x(pt[0]), y(pt[1]), bw * 0.045f, head)
    }
}
