package dev.r1agents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.r1os.host.ui.CharacterView
import dev.r1os.host.ui.Theme
import kotlin.math.*

/** Una sola callback per frame, nessuna allocazione di Path o animatore per campione del microfono. */
internal class BuddyMotionLayer(ctx: Context, private val source: CharacterView, private val agent: Agent) : FrameLayout(ctx), Choreographer.FrameCallback {
    private val envelope = VoiceEnvelope()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var state = BuddyVisualState.of("idle")
    private var previous = 0L
    private var phase = 0f
    private var scheduled = false
    private var pathWidth = -1f
    private var pathHeight = -1f
    private var recipient: CharacterView? = null
    private val canvas = object : View(ctx) {
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
            paint.color = Theme.colorOf(agent.color)
            if (state.kind == "listening") {
                if (pathWidth != w || pathHeight != h) {
                    pathWidth = w; pathHeight = h
                    val x = w / 2; val y = h / 2; val r = s * .31f; val k = r * .58f
                    path.rewind(); path.moveTo(x, y - r)
                    path.cubicTo(x + k, y - r * 1.06f, x + r, y - k, x + r, y)
                    path.cubicTo(x + r * 1.04f, y + k, x + k, y + r, x, y + r)
                    path.cubicTo(x - k, y + r * .95f, x - r, y + k, x - r, y)
                    path.cubicTo(x - r * .96f, y - k, x - k, y - r, x, y - r)
                }
                path.close(); paint.style = Paint.Style.FILL; paint.alpha = 52; c.drawPath(path, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = max(1f, s * .009f); paint.alpha = 170; c.drawPath(path, paint)
            } else if (state.kind == "handoff") {
                val t = if (Motion.reduced(context)) .5f else (phase / 1.4f) % 1f
                val x = w * (.50f + .34f * t); val y = h * (.50f + .25f * t) - sin(t * Math.PI).toFloat() * s * .12f
                paint.style = Paint.Style.FILL; paint.alpha = 230
                c.drawCircle(x, y, max(2f, s * .028f), paint)
            }
        }
    }
    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; clipChildren = false; addView(canvas, LayoutParams(-1, -1)) }
    fun show(next: BuddyVisualState) {
        val changed = state != next
        state = next
        if (next.kind != "listening") { envelope.reset(); canvas.scaleX = 1f; canvas.scaleY = 1f; canvas.rotation = 0f }
        if (changed) {
            recipient?.let { it.setActive(false); removeView(it) }; recipient = null
            if (next.kind == "handoff" && next.targetName != null) {
                Db.get(context).agents().firstOrNull { it.id != agent.id && it.name.equals(next.targetName, true) }?.let { target ->
                    val size = (source.layoutParams?.width ?: 80).let { (it * .30f).toInt().coerceAtLeast(24) }
                    recipient = CharacterView(context, size).also { it.show(target); it.isClickable = false; addView(it, LayoutParams(size, size, Gravity.BOTTOM or Gravity.END)) }
                }
            }
        }
        canvas.invalidate(); updateLoop()
    }
    fun level(value: Float) { if (state.kind == "listening") { envelope.accept(value); if (Motion.reduced(context)) { canvas.scaleX = 1f + envelope.target * .25f; canvas.scaleY = canvas.scaleX } } }
    private fun enabled() = isAttachedToWindow && isShown && windowVisibility == VISIBLE && state.kind in listOf("listening", "handoff")
    private fun updateLoop() {
        val active = enabled() && !Motion.reduced(context)
        canvas.setLayerType(if (active && state.kind == "listening") View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
        recipient?.setActive(enabled())
        if (active && !scheduled) { scheduled = true; previous = 0L; Choreographer.getInstance().postFrameCallback(this) }
        else if (!active) stop()
    }
    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!enabled() || Motion.reduced(context)) { stop(); return }
        val dt = if (previous == 0L) 1f / 90 else ((frameTimeNanos - previous) / 1e9f).coerceIn(0f, .05f)
        previous = frameTimeNanos; phase += dt
        if (state.kind == "listening") {
            val level = envelope.advance(dt)
            val ripple = sin(phase * 4f) * .025f * level
            canvas.scaleX = 1f + level * .25f + ripple
            canvas.scaleY = 1f + level * .25f - ripple
            canvas.rotation = sin(phase * 2f) * 5f * level
        } else canvas.invalidate()
        scheduled = true; Choreographer.getInstance().postFrameCallback(this)
    }
    private fun stop() { Choreographer.getInstance().removeFrameCallback(this); scheduled = false; previous = 0L; canvas.setLayerType(View.LAYER_TYPE_NONE, null) }
    fun dismiss() { show(BuddyVisualState.of("idle")); stop() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateLoop() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateLoop() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateLoop() }
    override fun onDetachedFromWindow() { stop(); envelope.reset(); recipient?.setActive(false); source.scaleX = 1f; source.scaleY = 1f; super.onDetachedFromWindow() }
}
