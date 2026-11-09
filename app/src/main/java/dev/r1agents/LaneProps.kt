package dev.r1agents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import dev.r1os.host.ui.dp

/**
 * Gli oggetti che il buddy tiene in mano nel carosello orizzontale della home: fotocamera, martello e chiave inglese,
 * griglia di app, cronometro, globo, taccuino con matita, calendario. Disegnati con tracciati (niente immagini), in un
 * riquadro 100×100; ognuno ha un respiro continuo e un gesto (`act`) per quando lo si tocca.
 */
class PropView(ctx: Context, val kind: String) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var spin = 0f      // giro del globo / lancetta
    private var flash = 0f     // lampo della fotocamera
    private val ticker = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Motion.LOOP * 4; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedFraction * 360f; if (kind == "globe" || kind == "stopwatch") invalidate() }
    }
    init { contentDescription = kind }
    private var live = false
    /** Gira solo quando si vede davvero: le altre sette pagine del carosello restano ferme (prima animavano tutte, sempre). */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val on = isVisible && isAttachedToWindow && alpha > 0.01f && !Motion.reduced(context)
        if (on == live) return
        live = on
        if (on) { if (kind == "globe" || kind == "stopwatch") ticker.start(); breathe() } else { ticker.cancel(); animate().cancel(); translationY = 0f }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); onVisibilityAggregated(isShown) }
    override fun onDetachedFromWindow() { live = false; ticker.cancel(); animate().cancel(); super.onDetachedFromWindow() }
    private fun breathe() {
        animate().translationY(-context.dp(4f)).rotation(rotation + 2f).setDuration(Motion.LOOP).setInterpolator(Theme_EASE).withEndAction {
            if (live) animate().translationY(0f).rotation(rotation - 2f).setDuration(Motion.LOOP).setInterpolator(Theme_EASE).withEndAction { if (live) breathe() }.start()
        }.start()
    }
    /** Il gesto dell'oggetto quando lo si tocca. */
    fun act() {
        if (Motion.reduced(context)) return
        when (kind) {
            "camera" -> android.animation.ValueAnimator.ofFloat(1f, 0f).apply { duration = Motion.SLOW; addUpdateListener { flash = it.animatedValue as Float; invalidate() }; start() }
            "tools" -> animate().rotationBy(-28f).setDuration(Motion.PRESS).withEndAction { animate().rotationBy(34f).setDuration(Motion.FAST).withEndAction { animate().rotationBy(-6f).setDuration(Motion.FAST).start() }.start() }.start()
            else -> animate().scaleX(1.18f).scaleY(1.18f).setDuration(Motion.PRESS).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(Motion.BASE).setInterpolator(dev.r1os.host.ui.Theme.POP).start() }.start()
        }
    }
    private val Theme_EASE get() = dev.r1os.host.ui.Theme.EASE_IN_OUT
    private fun fill(c: Int) = p.apply { style = Paint.Style.FILL; color = c; strokeWidth = 0f }
    private fun stroke(c: Int, w: Float) = p.apply { style = Paint.Style.STROKE; color = c; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 100f
        c.save(); c.scale(s, s)
        when (kind) {
            "camera" -> {
                c.drawRoundRect(RectF(6f, 26f, 94f, 82f), 10f, 10f, fill(Color.parseColor("#2b2d33")))
                c.drawRoundRect(RectF(6f, 26f, 94f, 42f), 10f, 10f, fill(Color.parseColor("#3a3d45")))
                c.drawRoundRect(RectF(30f, 16f, 56f, 28f), 4f, 4f, fill(Color.parseColor("#2b2d33")))
                c.drawCircle(50f, 55f, 22f, fill(Color.parseColor("#cfd3da")))
                c.drawCircle(50f, 55f, 16f, fill(Color.parseColor("#14161a")))
                c.drawCircle(50f, 55f, 10f, fill(Color.parseColor("#2f5fd0")))
                c.drawCircle(44f, 49f, 4f, fill(Color.parseColor("#cfe2ff")))
                c.drawCircle(80f, 34f, 3.6f, fill(Color.parseColor("#ff5a5a")))
                c.drawRoundRect(RectF(64f, 12f, 76f, 18f), 3f, 3f, fill(Color.parseColor("#cfd3da")))
                if (flash > 0f) c.drawCircle(50f, 55f, 60f * (1.2f - flash), fill(Color.argb((flash * 200).toInt(), 255, 255, 255)))
            }
            "tools" -> { // martello e chiave inglese incrociati
                c.save(); c.rotate(40f, 50f, 50f)
                c.drawRoundRect(RectF(45f, 16f, 55f, 92f), 4f, 4f, fill(Color.parseColor("#9a6a3a")))
                c.drawRoundRect(RectF(24f, 8f, 76f, 30f), 5f, 5f, fill(Color.parseColor("#9aa1ad")))
                c.drawRoundRect(RectF(24f, 8f, 76f, 16f), 5f, 5f, fill(Color.parseColor("#c7ccd6")))
                c.restore()
                c.save(); c.rotate(-40f, 50f, 50f)
                c.drawRoundRect(RectF(45f, 30f, 55f, 92f), 4f, 4f, fill(Color.parseColor("#c7ccd6")))
                val head = Path().apply { addCircle(50f, 22f, 15f, Path.Direction.CW) }
                c.drawPath(head, fill(Color.parseColor("#c7ccd6")))
                c.drawRoundRect(RectF(44f, 4f, 56f, 20f), 3f, 3f, fill(Color.BLACK))
                c.restore()
            }
            "apps" -> {
                val cols = intArrayOf(Color.parseColor("#ff7a45"), Color.parseColor("#3fb8e8"), Color.parseColor("#4ad48a"), Color.parseColor("#f5b942"), Color.parseColor("#a78bfa"), Color.parseColor("#ff6fa5"), Color.parseColor("#6ee7d4"), Color.parseColor("#5b8def"), Color.parseColor("#c7ccd6"))
                for (i in 0 until 9) { val x = 10f + (i % 3) * 30f; val y = 10f + (i / 3) * 30f; c.drawRoundRect(RectF(x, y, x + 24f, y + 24f), 7f, 7f, fill(cols[i])) }
            }
            "stopwatch" -> {
                c.drawRoundRect(RectF(42f, 4f, 58f, 14f), 3f, 3f, fill(Color.parseColor("#9aa1ad")))
                c.drawCircle(50f, 56f, 38f, fill(Color.parseColor("#e8eaee")))
                c.drawCircle(50f, 56f, 32f, fill(Color.parseColor("#14161a")))
                for (i in 0 until 12) { c.save(); c.rotate(i * 30f, 50f, 56f); c.drawRect(49f, 26f, 51f, i % 3 * 0f + 31f, fill(Color.parseColor("#cfd3da"))); c.restore() }
                c.save(); c.rotate(spin, 50f, 56f); c.drawRoundRect(RectF(48.5f, 30f, 51.5f, 58f), 1.5f, 1.5f, fill(Color.parseColor("#ff7a45"))); c.restore()
                c.drawCircle(50f, 56f, 4f, fill(Color.parseColor("#ff7a45")))
            }
            "globe" -> {
                c.drawCircle(50f, 50f, 40f, fill(Color.parseColor("#2f7fe0")))
                c.save(); c.clipPath(Path().apply { addCircle(50f, 50f, 40f, Path.Direction.CW) })
                val land = fill(Color.parseColor("#4ad48a"))
                val shift = (spin / 360f) * 80f
                for (k in -1..1) { c.save(); c.translate(k * 80f - shift, 0f); c.drawOval(RectF(20f, 28f, 46f, 52f), land); c.drawOval(RectF(52f, 52f, 82f, 74f), land); c.drawOval(RectF(60f, 22f, 78f, 38f), land); c.restore() }
                c.restore()
                c.drawCircle(50f, 50f, 40f, stroke(Color.parseColor("#cfe2ff"), 3f))
            }
            "notes" -> {
                c.drawRoundRect(RectF(16f, 8f, 78f, 92f), 8f, 8f, fill(Color.parseColor("#f6f1e4")))
                for (i in 0 until 5) c.drawLine(26f, 26f + i * 14f, 68f, 26f + i * 14f, stroke(Color.parseColor("#b9b2a0"), 2.4f))
                for (i in 0 until 4) c.drawCircle(16f, 18f + i * 20f, 3f, fill(Color.parseColor("#9aa1ad")))
                c.save(); c.rotate(35f, 78f, 70f)
                c.drawRoundRect(RectF(70f, 36f, 80f, 84f), 3f, 3f, fill(Color.parseColor("#f5b942")))
                c.drawPath(Path().apply { moveTo(70f, 84f); lineTo(80f, 84f); lineTo(75f, 96f); close() }, fill(Color.parseColor("#2b2d33")))
                c.restore()
            }
            "controls" -> { // tre cursori
                val ys = floatArrayOf(22f, 50f, 78f); val xs = floatArrayOf(64f, 30f, 52f); val cols = intArrayOf(Color.parseColor("#4ad48a"), Color.parseColor("#3fb8e8"), Color.parseColor("#ff7a45"))
                for (i in 0 until 3) {
                    c.drawRoundRect(RectF(8f, ys[i] - 5f, 92f, ys[i] + 5f), 5f, 5f, fill(Color.parseColor("#3a3d45")))
                    c.drawRoundRect(RectF(8f, ys[i] - 5f, xs[i], ys[i] + 5f), 5f, 5f, fill(cols[i]))
                    c.drawCircle(xs[i], ys[i], 11f, fill(Color.parseColor("#f6f1e4"))); c.drawCircle(xs[i], ys[i], 11f, stroke(cols[i], 3f))
                }
            }
            "skills" -> { // un pezzo di puzzle con una stella: le competenze
                c.drawRoundRect(RectF(14f, 30f, 74f, 90f), 10f, 10f, fill(Color.parseColor("#a78bfa")))
                c.drawCircle(44f, 30f, 11f, fill(Color.parseColor("#a78bfa"))); c.drawCircle(74f, 60f, 11f, fill(Color.parseColor("#a78bfa")))
                c.drawCircle(14f, 60f, 11f, fill(Color.parseColor("#14161a")))
                val star = Path().apply { moveTo(66f, 4f); lineTo(71f, 17f); lineTo(85f, 22f); lineTo(71f, 27f); lineTo(66f, 40f); lineTo(61f, 27f); lineTo(47f, 22f); lineTo(61f, 17f); close() }
                c.drawPath(star, fill(Color.parseColor("#ffd54a")))
            }
            "jobs" -> {
                c.drawRoundRect(RectF(10f, 16f, 90f, 90f), 10f, 10f, fill(Color.parseColor("#f6f1e4")))
                c.drawRoundRect(RectF(10f, 16f, 90f, 38f), 10f, 10f, fill(Color.parseColor("#ff7a45")))
                c.drawRect(10f, 28f, 90f, 38f, fill(Color.parseColor("#ff7a45")))
                c.drawRoundRect(RectF(26f, 6f, 34f, 24f), 3f, 3f, fill(Color.parseColor("#2b2d33"))); c.drawRoundRect(RectF(66f, 6f, 74f, 24f), 3f, 3f, fill(Color.parseColor("#2b2d33")))
                c.drawCircle(50f, 64f, 18f, stroke(Color.parseColor("#2b2d33"), 3.4f)); c.save(); c.rotate(spin, 50f, 64f); c.drawLine(50f, 64f, 50f, 52f, stroke(Color.parseColor("#2b2d33"), 3.4f)); c.restore(); c.drawLine(50f, 64f, 58f, 68f, stroke(Color.parseColor("#2b2d33"), 3.4f))
            }
        }
        c.restore()
    }
}
