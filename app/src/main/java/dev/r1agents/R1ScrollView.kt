package dev.r1agents

import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import dev.r1os.host.ui.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** La rotella dell'R1 può emettere tocchi sintetici: si scorrono i contenuti senza premere le righe. */
open class R1ScrollView(context: Context) : ScrollView(context), Choreographer.FrameCallback {
    private var wheelGesture = false
    private var lastY = 0f
    private var lastX = 0f
    private var position = 0f
    private var target = 0f
    private var moving = false
    private var previousFrame = 0L
    private var downY = 0f
    private var wheelPull = 0f
    /** Il buddy compagno della pagina (se c'è) reagisce allo scroll: vedi BuddyScrollFx. */
    val buddyFx by lazy { BuddyScrollFx(this) }
    // Le righe che escono in alto (o in basso) sfumano invece di essere tagliate di netto: sopra si intravede il buddy.
    init { isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(context.dp(56f).toInt()) }
    private val wheelReset = Runnable { wheelPull = 0f; buddyFx.calm() }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            wheelGesture = event.device?.name?.contains("wheel", ignoreCase = true) == true
            lastY = event.y; lastX = event.x; downY = event.y
            if (!wheelGesture) stopWheel()
        }
        if (!wheelGesture) {
            // Tiro oltre l'inizio: il buddy si allunga (e si offende se si esagera). Non consuma l'evento.
            if (event.actionMasked == MotionEvent.ACTION_MOVE && scrollY == 0 && event.y - downY > ViewConfiguration.get(context).scaledTouchSlop) buddyFx.pull(event.y - downY)
            else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) buddyFx.calm()
            return super.dispatchTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { nudge(r1WheelDelta(lastX - event.x, lastY - event.y, display?.rotation ?: 0) * 1.8f); lastY = event.y; lastX = event.x }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> wheelGesture = false
        }
        return true
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta != 0f) { nudge(-delta * context.dp(48f)); return true }
        }
        return super.onGenericMotionEvent(event)
    }
    fun nudge(delta: Float) {
        val limit = ((getChildAt(0)?.height ?: 0) + paddingTop + paddingBottom - height).coerceAtLeast(0).toFloat()
        if (!moving || abs(scrollY - position) > 12f) { position = scrollY.toFloat(); target = position }
        if (delta < 0f && position <= .5f && target <= .5f) { wheelPull -= delta; buddyFx.pull(wheelPull * 2.2f); removeCallbacks(wheelReset); postDelayed(wheelReset, 320L) } else if (wheelPull > 0f) { wheelPull = 0f; buddyFx.calm() }
        target = (target + delta).coerceIn(0f, limit)
        if (Motion.reduced(context)) { stopWheel(); scrollTo(0, target.roundToInt()); return }
        if (!moving && abs(target - position) > .5f) { moving = true; previousFrame = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!moving || !isAttachedToWindow || !isShown) { stopWheel(); return }
        val dt = if (previousFrame == 0L) 1f / 60f else ((frameTimeNanos - previousFrame) / 1e9f).coerceIn(0f, .05f)
        previousFrame = frameTimeNanos
        position += (target - position) * (1f - exp(-dt * 16f))
        if (abs(target - position) < .6f) { position = target; moving = false }
        scrollTo(0, position.roundToInt())
        if (moving) Choreographer.getInstance().postFrameCallback(this)
    }
    private fun stopWheel() { moving = false; previousFrame = 0L; Choreographer.getInstance().removeFrameCallback(this) }
    // Inclinazione 3D: scorrendo veloce le schede dei gruppi si piegano appena nel verso del movimento e una molla le
    // rimette dritte con un piccolo rimbalzo. Lavora solo mentre si scorre.
    private val tilt = dev.r1os.host.ui.Spring { v ->
        val col = getChildAt(0) as? android.view.ViewGroup ?: return@Spring
        val cam = 12000f * resources.displayMetrics.density
        // Anche le schede dentro un contenitore (lista delle app, attività di Hermes): un livello sotto.
        for (i in 0 until col.childCount) when (val c = col.getChildAt(i)) {
            is GroupCard -> { c.cameraDistance = cam; c.rotationX = v }
            is android.view.ViewGroup -> for (j in 0 until c.childCount) (c.getChildAt(j) as? GroupCard)?.let { it.cameraDistance = cam; it.rotationX = v }
        }
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt); buddyFx.onScrolled(t)
        val dy = (t - oldt).toFloat()
        if (dy != 0f && !Motion.reduced(context)) {
            val to = (dy * 0.18f).coerceIn(-6f, 6f)
            tilt.snapTo(tilt.value + (to - tilt.value) * 0.5f); tilt.animateTo(0f, 160f, 0.5f)
        }
    }
    override fun onDetachedFromWindow() { stopWheel(); super.onDetachedFromWindow() }
}

/** In orizzontale il verso della rotella va invertito rispetto al calcolo teorico (segnalato sul Rabbit); regolabile in Aspetto. */
internal var r1WheelLandscapeFlip = true

/** Segno con cui la rotella spinge: Android ruota i tocchi sintetici con il display (0/90/180/270 gradi). */
internal fun r1WheelSign(rotation: Int): Float {
    val base = if (rotation == 2 || rotation == 3) -1f else 1f
    return if (r1WheelLandscapeFlip && (rotation == 1 || rotation == 3)) -base else base
}

/** Delta fisico della rotella (asse dominante) nel verso giusto per l'orientamento corrente. */
internal fun r1WheelDelta(dx: Float, dy: Float, rotation: Int): Float {
    val dominant = if (abs(dx) > abs(dy)) dx else dy
    return dominant * r1WheelSign(rotation)
}
