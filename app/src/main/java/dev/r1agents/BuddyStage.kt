package dev.r1agents

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.r1os.host.ui.CharacterView
import dev.r1os.host.ui.Theme
import dev.r1os.host.ui.dp
import dev.r1os.host.ui.dpi
import dev.r1os.host.ui.keepCase
import dev.r1os.host.ui.text

/** Palco temporaneo del buddy: nessuna animazione continua quando nascosto o in background. */
class BuddyStage(ctx: Context, private val agent: Agent) : LinearLayout(ctx) {
    private val buddy = CharacterView(ctx, ctx.dpi(40f)).also { it.show(agent) }
    private val motion = BuddyMotionLayer(ctx, buddy, agent)
    private val words = ctx.text(14f, 600).keepCase().apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val cue = object : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            paint.color = Theme.colorOf(agent.color); paint.strokeWidth = context.dp(2f); paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
            paint.alpha = 255
            val cx = width / 2f; val cy = height / 2f; val r = context.dp(22f)
            when (mode) {
                "upload", "thinking", "reconnect" -> c.drawArc(cx-r, cy-r, cx+r, cy+r, phase * 360f, 85f, false, paint)
                "search" -> {
                    val x = cx + context.dp(kotlin.math.sin(phase * Math.PI * 2).toFloat() * 5f)
                    c.drawCircle(x, cy - context.dp(4f), context.dp(15f), paint)
                    c.drawLine(x + context.dp(11f), cy + context.dp(7f), x + context.dp(18f), cy + context.dp(14f), paint)
                }
                "files" -> {
                    val x = width - context.dp(16f); val y = context.dp(3f)
                    c.drawRoundRect(x, y, x + context.dp(13f), y + context.dp(19f), context.dp(2f), context.dp(2f), paint)
                    val line = y + context.dp(4f + phase * 11f)
                    c.drawLine(x + context.dp(2f), line, x + context.dp(11f), line, paint)
                }
                "shell" -> {
                    val x = context.dp(5f); val y = height - context.dp(9f)
                    c.drawLine(x, y - context.dp(4f), x + context.dp(4f), y, paint)
                    c.drawLine(x + context.dp(4f), y, x, y + context.dp(4f), paint)
                    if (phase < 0.6f) c.drawLine(x + context.dp(9f), y + context.dp(4f), x + context.dp(17f), y + context.dp(4f), paint)
                }
                "memory" -> {
                    val x = width - context.dp(11f); val y = context.dp(10f)
                    c.drawCircle(x, y, context.dp(4f), paint)
                    c.drawLine(x, y - context.dp(7f), x, y - context.dp(5f), paint)
                    c.drawLine(x + context.dp(5f), y, x + context.dp(7f), y, paint)
                }
                "offline", "connected" -> {
                    val x = width - context.dp(10f); val y = context.dp(9f)
                    if (mode == "offline") { c.drawCircle(x, y, context.dp(6f), paint); c.drawLine(x-context.dp(4f), y+context.dp(4f), x+context.dp(4f), y-context.dp(4f), paint) }
                    else { c.drawLine(x-context.dp(5f), y, x-context.dp(1f), y+context.dp(4f), paint); c.drawLine(x-context.dp(1f), y+context.dp(4f), x+context.dp(6f), y-context.dp(4f), paint) }
                }
                "waiting" -> {
                    paint.style = Paint.Style.FILL; paint.textSize = context.dp(18f); paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    c.drawText("?", width - context.dp(12f), context.dp(17f), paint)
                }
                "transcribing" -> {
                    paint.style = Paint.Style.FILL
                    for (i in 0..2) { paint.alpha = if ((phase * 3).toInt() == i) 255 else 65; c.drawCircle(cx + context.dp((i - 1) * 7f), height - context.dp(3f), context.dp(2f), paint) }
                }
                "file", "photo", "send_file" -> {
                    val x = width - context.dp(11f); val y = context.dp(3f)
                    c.drawRoundRect(x, y, x + context.dp(9f), y + context.dp(12f), context.dp(2f), context.dp(2f), paint)
                    c.drawLine(x + context.dp(2f), y + context.dp(8f), x + context.dp(7f), y + context.dp(8f), paint)
                }
            }
        }
    }
    private var phase = 0f
    private var level = 0f
    var mode = "idle"
        private set
    private var loop: ValueAnimator? = null
    private val discard = ctx.iconButton("close", Theme.MUTED, filled = false, size = 48f) {}.apply {
        visibility = GONE; contentDescription = UiText.text(context, R.string.ui_a789d492e3)
    }
    private val hide = Runnable { dismiss() }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        visibility = GONE; minimumHeight = ctx.dpi(52f)
        setPadding(0, ctx.dpi(2f), ctx.dpi(8f), ctx.dpi(2f))
        val frame = FrameLayout(ctx)
        frame.addView(motion, FrameLayout.LayoutParams(-1, -1))
        frame.addView(buddy, FrameLayout.LayoutParams(ctx.dpi(40f), ctx.dpi(40f), Gravity.CENTER))
        frame.addView(cue, FrameLayout.LayoutParams(-1, -1))
        addView(frame, LayoutParams(ctx.dpi(52f), ctx.dpi(48f)))
        addView(words, LayoutParams(0, -2, 1f))
        addView(discard)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun show(kind: String, label: String, rawActivity: String = label) {
        removeCallbacks(hide)
        val changed = kind != mode || visibility != VISIBLE
        discard.visibility = GONE
        mode = kind; words.text = label; Motion.show(this)
        if (changed) {
            cue.animate().cancel(); cue.translationX = 0f; cue.translationY = 0f; cue.alpha = 1f
            buddy.animate().cancel(); buddy.translationX = 0f; buddy.rotation = 0f; buddy.scaleX = 1f; buddy.scaleY = 1f
            val visual = BuddyVisualState.of(kind, rawActivity)
            buddy.show(agent, visual.phase, visual.activity)
            when (kind) {
                "file", "photo", "done", "review", "connected" -> { buddy.lookAt(8f, 3f, 900); buddy.react(if (kind == "photo") "wink" else "bounce") }
                "send", "send_file" -> {
                    if (kind == "send_file") cue.animate().translationX(context.dp(16f)).translationY(-context.dp(10f)).alpha(0f).setDuration(Motion.SLOW).start()
 buddy.lookAt(10f, -8f, 600); buddy.animate().translationX(context.dp(6f)).setDuration(Motion.FAST).withEndAction { buddy.animate().translationX(0f).setDuration(Motion.BASE).setInterpolator(Theme.SETTLE).start() }.start() }
                "cancel" -> { buddy.animate().rotation(-12f).setDuration(Motion.PRESS).withEndAction { buddy.animate().rotation(0f).setDuration(Motion.BASE).start() }.start() }
                "handoff" -> { buddy.lookAt(12f, 0f, 1400); buddy.react("wink") }
                "search" -> buddy.lookAt(8f, -4f, 900)
                "files" -> buddy.lookAt(7f, 7f, 900)
                "shell" -> buddy.lookAt(-6f, 8f, 900)
                "memory" -> buddy.react("wink")
                "waiting" -> buddy.lookAt(0f, 0f, -1, 180)
                "error" -> buddy.react("surprised")
            }
        }
        motion.show(BuddyVisualState.of(kind, rawActivity)); updateLoop(); cue.invalidate()
    }
    fun review(onDiscard: () -> Unit) {
        show("review", UiText.text(context, R.string.ui_4f71047608))
        discard.visibility = VISIBLE; discard.setOnClickListener { onDiscard() }
    }
    fun flash(kind: String, label: String, duration: Long = 1100) { show(kind, label); postDelayed(hide, duration) }
    fun level(value: Float) {
        if (mode != "listening") return
        level = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        motion.level(level)
        cue.invalidate()
    }
    fun followCancel(progress: Float) { if (mode == "listening") buddy.lookAt(0f, -progress.coerceIn(0f, 1f) * 12f, -1, 90) }
    fun dismiss() { motion.dismiss(); removeCallbacks(hide); Motion.hide(this); mode = "idle"; loop?.cancel(); loop = null; buddy.animate().cancel(); cue.animate().cancel(); buddy.setActive(false) }
    private fun updateLoop() {
        val active = isAttachedToWindow && isShown && !Motion.reduced(context) && windowVisibility == VISIBLE && visibility == VISIBLE && mode in listOf("upload", "thinking", "transcribing", "search", "files", "shell", "reconnect")
        buddy.setActive(isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE)
        if (!active) { loop?.cancel(); loop = null; return }
        if (loop != null) return
        loop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.LOOP; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; cue.invalidate() }; start()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateLoop() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateLoop() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateLoop() }
    override fun onDetachedFromWindow() { removeCallbacks(hide); loop?.cancel(); loop = null; buddy.setActive(false); buddy.animate().cancel(); cue.animate().cancel(); super.onDetachedFromWindow() }
}
