package dev.r1agents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import dev.r1os.host.ui.Theme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Il disegno dell'acqua sopra lo shader: niente colore, solo il bordo lucido del fronte e due onde chiare che lo
 * seguono, con un bordo che ondeggia appena. Sulle versioni senza shader è l'unica traccia dell'acqua.
 */
internal object Ripples {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val haze = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Un cerchio "liquido": raggio `r` con un'onda di ampiezza `amp`, ruotata di `phase`. */
    fun wobbly(cx: Float, cy: Float, r: Float, amp: Float, phase: Float, lobes: Int = 7): Path {
        path.reset()
        val steps = 72
        for (i in 0..steps) {
            val a = (i / steps.toFloat()) * 2f * PI.toFloat()
            val rr = r + amp * sin(a * lobes + phase) + amp * 0.5f * sin(a * (lobes - 3) - phase * 1.6f)
            val x = cx + rr * cos(a); val y = cy + rr * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); return path
    }

    /** `k` 0..1 quanto è aperto, `fade` 0..1 quanto si vede ancora, `mist` 0..1 velo chiaro "sott'acqua" (apertura delle app). */
    fun draw(c: Canvas, cx: Float, cy: Float, radius: Float, k: Float, fade: Float, density: Float, mist: Float = 0f) {
        if (radius <= 1f) return
        val amp = (2f + 4f * (1f - k)) * density
        val phase = k * 10f
        val light = if (Theme.light) Theme.FG else android.graphics.Color.WHITE
        if (mist > 0.01f) { haze.color = Theme.BG; haze.alpha = (mist * 0.35f * 255).toInt(); c.drawPath(wobbly(cx, cy, radius, amp, phase), haze) }
        if (fade <= 0.01f) return
        // Onde chiare che seguono il fronte, sempre più tenui.
        for (i in 1..2) {
            val rr = radius * (1f - 0.11f * i)
            if (rr <= 1f) continue
            ring.color = light; ring.alpha = (fade * (0.16f - 0.05f * i) * 255).toInt().coerceAtLeast(0)
            ring.strokeWidth = (1.2f - 0.3f * i) * density
            c.drawPath(wobbly(cx, cy, rr, amp * 0.7f, phase + i * 1.3f, 6 + i), ring)
        }
        // Il bordo del fronte: una linea lucida, come il riflesso sul pelo dell'acqua.
        ring.alpha = (fade * 0.24f * 255).toInt(); ring.strokeWidth = 1f * density
        c.drawPath(wobbly(cx, cy, radius, amp, phase), ring)
    }
}

/** Il punto da cui parte l'acqua, nelle coordinate di `space`: il dito se il tocco è recente e dentro `from`, altrimenti il centro di `from`. */
internal fun waterCentre(space: View, from: RectF): PointF {
    val loc = IntArray(2); space.getLocationOnScreen(loc)
    val x = Motion.lastTouchX - loc[0]; val y = Motion.lastTouchY - loc[1]
    val recent = android.os.SystemClock.uptimeMillis() - Motion.lastTapAt < 1500
    return if (recent && from.contains(x, y)) PointF(x, y) else PointF(from.centerX(), from.centerY())
}

/**
 * Transizione ad acqua tra le pagine: toccando una voce, dal punto del dito (o dal centro della voce scelta con la rotella)
 * parte un'onda che si allarga come su uno specchio d'acqua. Lo shader deforma il contenuto attorno al fronte; dentro il
 * cerchio la pagina nuova compare trasparente (si vede ancora quella sotto) e si posa mentre l'acqua si calma.
 * Chiudendo, l'onda si richiude verso la voce. Ritaglio con l'outline ovale (hardware) e shader solo durante la transizione.
 */
object ContainerReveal {
    private val running = java.util.WeakHashMap<View, Animator>()

    private class State { var cx = 0f; var cy = 0f; var radius = 0f; var k = 0f; var fade = 1f }

    private class Veil(private val st: State, private val density: Float) : android.graphics.drawable.Drawable() {
        override fun draw(c: Canvas) = Ripples.draw(c, st.cx, st.cy, st.radius, st.k, st.fade, density)
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun run(page: View, from: RectF, w: Float, h: Float, opening: Boolean, onEnd: () -> Unit) {
        running.remove(page)?.cancel()
        val d = page.resources.displayMetrics.density
        val space = page.parent as? View ?: page
        val p = waterCentre(space, from)
        val full = max(max(hypot(p.x, p.y), hypot(w - p.x, p.y)), max(hypot(p.x, h - p.y), hypot(w - p.x, h - p.y))) + 32f * d
        val start = max(from.height(), 24f * d) * 0.5f
        val st = State().apply { cx = p.x; cy = p.y }
        page.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setOval((st.cx - st.radius).toInt(), (st.cy - st.radius).toInt(), (st.cx + st.radius).toInt(), (st.cy + st.radius).toInt())
            }
        }
        page.clipToOutline = true
        val veil = Veil(st, d); page.foreground = veil
        val began = android.os.SystemClock.uptimeMillis()
        fun apply(k: Float, time: Float) {
            st.k = k; st.radius = start + (full - start) * k
            // Trasparente come l'acqua: la pagina nuova lascia vedere quella sotto e si posa solo alla fine.
            page.alpha = 0.30f + 0.70f * (k * k * k)
            // L'acqua si calma nell'ultima parte.
            st.fade = (1f - ((time - 0.40f) / 0.60f)).coerceIn(0f, 1f)
            page.invalidateOutline(); veil.invalidateSelf()
            Water.apply(space, st.cx, st.cy, st.radius, st.fade, (android.os.SystemClock.uptimeMillis() - began) / 1000f)
        }
        apply(if (opening) 0f else 1f, if (opening) 0f else 1f)
        // Come un liquido: parte morbida, si allarga a lungo e si posa senza fermarsi di colpo.
        val liquid = Motion.LIQUID
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (opening) Motion.FLOW else Motion.FLOW_SHORT
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val time = it.animatedValue as Float
                if (opening) apply(liquid.getInterpolation(time), time)
                else apply(1f - liquid.getInterpolation(time), 1f - time)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    running.remove(page)
                    Water.clear(space)
                    page.clipToOutline = false; page.outlineProvider = ViewOutlineProvider.BACKGROUND; page.foreground = null
                    page.alpha = 1f
                    if (!cancelled) onEnd()
                }
            })
        }
        running[page] = a
        a.start()
    }

    /** Apre `page` dalla voce in `from` (coordinate nel contenitore delle pagine, grande `w`×`h`). */
    fun open(page: View, from: RectF, w: Float, h: Float, onEnd: () -> Unit = {}) = run(page, from, w, h, true, onEnd)

    /** Richiude `page` verso la voce da cui era nata. */
    fun close(page: View, to: RectF, w: Float, h: Float, onEnd: () -> Unit) = run(page, to, w, h, false, onEnd)
}

/**
 * Apertura delle app come entrare in acqua: dal punto toccato un'onda si allarga sulla lista, lo schermo si fa
 * sfocato e velato come visto da sott'acqua, poi l'app compare (senza lo scorrimento di sistema). Tornando, l'acqua si
 * richiude verso la voce e la lista torna nitida. Una view sola sopra tutto, tolta appena finisce.
 */
object LiquidLaunch {
    private var veil: View? = null

    private class Drop(ctx: android.content.Context, val cx: Float, val cy: Float) : View(ctx) {
        var k = 0f; set(v) { field = v; invalidate() }
        var fade = 1f; set(v) { field = v; invalidate() }
        fun radius(): Float = context.resources.displayMetrics.density.let { d -> 20f * d + (hypot(max(cx, width - cx), max(cy, height - cy)) + 32f * d - 20f * d) * k }
        override fun onDraw(c: Canvas) {
            Ripples.draw(c, cx, cy, radius(), k, fade, resources.displayMetrics.density, mist = k * k)
        }
    }

    private fun water(root: ViewGroup, drop: Drop, time: Float) {
        val d = root.resources.displayMetrics.density
        // Più si entra in acqua, più l'immagine si sfoca.
        Water.apply(root, drop.cx, drop.cy, drop.radius(), drop.fade, time, blur = drop.k * 8f * d)
    }

    /** Fa partire l'acqua da `from` (coordinate in `root`) e poi chiama `launch`. */
    fun start(root: ViewGroup, from: RectF?, launch: () -> Boolean) {
        clear(root, animate = false)
        if (from == null || Motion.reduced(root.context)) { launch(); return }
        val p = waterCentre(root, from)
        val drop = Drop(root.context, p.x, p.y)
        root.addView(drop, ViewGroup.LayoutParams(-1, -1)); veil = drop
        var launched = false
        val began = android.os.SystemClock.uptimeMillis()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.FLOW_SHORT; interpolator = Motion.LIQUID
            addUpdateListener {
                drop.k = it.animatedValue as Float
                water(root, drop, (android.os.SystemClock.uptimeMillis() - began) / 1000f)
                // L'app parte quando l'acqua ha coperto lo schermo: la sua comparsa cade sotto il velo.
                if (!launched && drop.k > 0.92f) { launched = true; if (!launch()) clear(root, animate = true) }
            }
            start()
        }
        drop.postDelayed({ if (veil === drop && drop.isAttachedToWindow && root.hasWindowFocus()) clear(root, animate = true) }, 2500L)
    }

    /** Al ritorno nell'app (o se il lancio fallisce): niente acqua, la lista riappare subito nitida. */
    /** Ritorna true se c'era un'app aperta da qui (per far riaffiorare la schermata in dissolvenza). */
    @Suppress("UNUSED_PARAMETER")
    fun clear(root: ViewGroup, animate: Boolean = true): Boolean {
        val drop = veil ?: return false
        veil = null
        root.removeView(drop); Water.clear(root)
        return true
    }
}
