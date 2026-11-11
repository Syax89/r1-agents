package dev.r1agents

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import dev.r1agents.MainActivity.AppEntry
import dev.r1os.host.ui.Spring
import dev.r1os.host.ui.Theme
import dev.r1os.host.ui.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Multitasking di R1 Agents: su sfondo nero le app recenti sono rettangoli affiancati che fluttuano piano.
 * Rotella, frecce o dito scorrono a destra e sinistra; tocco o tasto centrale aprono quella al centro, che si allarga
 * fino a tutto schermo come acqua; un colpo verso l'alto su una scheda la toglie dall'elenco.
 *
 * Android non lascia a un'app normale le anteprime dei recenti di sistema: ogni scheda mostra icona e colore dell'app.
 */
internal class AppSwitcher(
    ctx: Context,
    apps: List<AppEntry>,
    private val title: String,
    private val emptyText: String,
    private val onPick: (AppEntry) -> Boolean,
    private val onRemove: (AppEntry) -> Unit,
    private val onClosed: () -> Unit,
) : FrameLayout(ctx), Choreographer.FrameCallback {

    private val items = apps.toMutableList()
    private val cards = items.map { Card(ctx, it, PreviewService.load(ctx, it.pkg)) }.toMutableList()
    private val pos = Spring { layoutCards() }
    private val d = ctx.resources.displayMetrics.density
    private var startedAt = 0L
    private var closing = false
    private var opening = false
    /** 0 → 1 all'ingresso: sfondo che si scurisce e schede che salgono. */
    private var enter = 0f

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 150; textSize = 13f * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.08f; textAlign = Paint.Align.CENTER }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 170; textSize = 16f * d; textAlign = Paint.Align.CENTER }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        isClickable = true; isFocusable = true; isFocusableInTouchMode = true; defaultFocusHighlightEnabled = false
        cards.forEach { addView(it) }
        contentDescription = title
        translationZ = 200f * resources.displayMetrics.density   // sopra pagine e buddy in volo (che hanno una loro elevazione)
    }

    val selected: Int get() = pos.target.roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))

    // ---- disposizione ------------------------------------------------------------------------------

    private fun cardW() = (width * 0.56f).roundToInt()
    private fun cardH() = (height * 0.6f).roundToInt()
    private fun spacing() = cardW() * 0.92f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val cw = MeasureSpec.makeMeasureSpec((measuredWidth * 0.56f).roundToInt(), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec((measuredHeight * 0.6f).roundToInt(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        layoutCards()
    }

    private fun layoutCards() {
        if (width == 0 || opening) return
        val t = (android.os.SystemClock.uptimeMillis() - startedAt) / 1000f
        val cw = cardW(); val ch = cardH()
        val cx = width / 2f; val cy = height * 0.53f
        cards.forEachIndexed { i, c ->
            val off = i - pos.value
            val a = abs(off)
            // Fluttuano: ognuna con la sua fase, così il gruppo sembra posato sull'acqua.
            val bob = sin(t * 1.6f + i * 1.3f) * 4f * d
            val k = enter
            c.pivotX = cw / 2f; c.pivotY = ch / 2f
            c.translationX = cx - cw / 2f + off * spacing()
            c.translationY = cy - ch / 2f + bob + (1f - k) * 70f * d + c.lift
            val s = (1f - 0.2f * a.coerceAtMost(2f)) * (0.9f + 0.1f * k)
            c.scaleX = s; c.scaleY = s
            c.rotationY = (-off * 10f).coerceIn(-20f, 20f); c.cameraDistance = 12000f * d
            c.rotation = sin(t * 1.1f + i) * 0.8f
            c.alpha = ((1f - 0.38f * a).coerceIn(0f, 1f) * k * c.fade).coerceIn(0f, 1f)
            c.translationZ = -a * 4f * d
        }
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAttachedToWindow || closing && enter <= 0f) return
        layoutCards()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = android.os.SystemClock.uptimeMillis()
        pos.snapTo(if (items.size > 1) 1f else 0f) // la prima è l'app da cui vieni, quella da riaprire è la seconda (come i recenti di sistema)
        ValueAnimator.ofFloat(0f, 1f).apply { duration = Motion.FLOW_SHORT; interpolator = Motion.LIQUID; addUpdateListener { enter = it.animatedValue as Float; layoutCards() }; start() }
        Choreographer.getInstance().postFrameCallback(this)
        requestFocus()
    }

    override fun onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.argb((enter * 255).roundToInt().coerceIn(0, 255), 0, 0, 0))
        titlePaint.alpha = (enter * 150).roundToInt()
        c.drawText(title.uppercase(), width / 2f, 34f * d, titlePaint)
        if (items.isEmpty()) { emptyPaint.alpha = (enter * 170).roundToInt(); c.drawText(emptyText, width / 2f, height / 2f, emptyPaint); return }
        // Puntini: dove sei nell'elenco.
        val n = items.size; val gap = 10f * d; val y = height - 22f * d; val x0 = width / 2f - (n - 1) * gap / 2f
        for (i in 0 until n) {
            val on = 1f - abs(i - pos.value).coerceAtMost(1f)
            dotPaint.color = Color.WHITE; dotPaint.alpha = ((60 + 170 * on) * enter).roundToInt()
            c.drawCircle(x0 + i * gap, y, (2.5f + 1.5f * on) * d, dotPaint)
        }
    }

    // ---- scelta --------------------------------------------------------------------------------------

    fun goTo(i: Int) {
        if (items.isEmpty() || opening) return
        val to = i.coerceIn(0, items.size - 1)
        if (to != selected) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        pos.animateTo(to.toFloat(), 260f, 0.78f)
        cards.getOrNull(to)?.contentDescription?.let { announceForAccessibility(it) }
    }

    fun openSelected() {
        val i = selected; val card = cards.getOrNull(i) ?: return
        if (opening || closing) return
        opening = true
        val sx = width / card.width.toFloat(); val sy = height / card.height.toFloat()
        val fromX = card.translationX; val fromY = card.translationY; val fromS = card.scaleX
        val others = cards.map { Triple(it, it.translationX, it.alpha) }
        val began = android.os.SystemClock.uptimeMillis()
        // La scheda scelta si allarga fino a tutto schermo come acqua; le altre scivolano via ai lati.
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.FLOW_SHORT; interpolator = Motion.LIQUID
            addUpdateListener {
                val k = it.animatedValue as Float
                card.translationX = fromX + (0f - fromX) * k + (width - card.width) / 2f * k
                card.translationY = fromY + (0f - fromY) * k + (height - card.height) / 2f * k
                card.scaleX = fromS + (sx - fromS) * k; card.scaleY = fromS + (sy - fromS) * k
                card.rotationY = card.rotationY * (1f - k); card.rotation = card.rotation * (1f - k)
                card.corner = 1f - k; card.alpha = 1f
                others.forEachIndexed { j, (o, x0, a0) -> if (j != i) { o.alpha = a0 * (1f - k); o.translationX = x0 + (if (j < i) -1 else 1) * 60f * d * k } }
                Water.apply(this@AppSwitcher, width / 2f, height / 2f, height * (0.2f + k), 1f - k * 0.6f, (android.os.SystemClock.uptimeMillis() - began) / 1000f, blur = k * 6f * d)
                if (k > 0.92f && card.tag == null) { card.tag = true; if (!onPick(items[i])) { opening = false; Water.clear(this@AppSwitcher); layoutCards() } }
            }
            start()
        }
    }

    private fun remove(i: Int) {
        val card = cards.getOrNull(i) ?: return
        val app = items[i]
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.BASE; interpolator = Motion.EXIT
            addUpdateListener { val k = it.animatedValue as Float; card.lift = -height * 0.6f * k; card.fade = 1f - k }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    removeView(card); cards.remove(card); items.remove(app); onRemove(app)
                    pos.animateTo(pos.target.coerceIn(0f, (items.size - 1).coerceAtLeast(0).toFloat()), 220f, 0.85f)
                    if (items.isEmpty()) invalidate()
                }
            })
            start()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        ValueAnimator.ofFloat(enter, 0f).apply {
            duration = Motion.BASE; interpolator = Motion.EXIT
            addUpdateListener { enter = it.animatedValue as Float; layoutCards() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { (parent as? android.view.ViewGroup)?.removeView(this@AppSwitcher); onClosed() } })
            start()
        }
    }

    /** Dopo il ritorno da un'app aperta da qui: via subito, senza animazione. */
    fun dismissNow() { closing = true; Water.clear(this); (parent as? android.view.ViewGroup)?.removeView(this); onClosed() }

    // ---- input ---------------------------------------------------------------------------------------

    fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> goTo(selected + 1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> goTo(selected - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (items.isEmpty()) close() else openSelected()
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> if (items.isNotEmpty()) remove(selected)
            else -> return false
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(e)
        val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL).let { if (it != 0f) -it else -e.getAxisValue(MotionEvent.AXIS_HSCROLL) }
        if (v != 0f) goTo(selected + if (v > 0) 1 else -1)
        return true
    }

    private var downX = 0f; private var downY = 0f; private var downPos = 0f; private var dragging = false; private var vertical = false
    private var wheel = false; private var wheelAcc = 0f; private var lastX = 0f; private var lastY = 0f
    private var tracker: android.view.VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (opening || closing) return true
        Motion.touch(this, e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downPos = pos.value; dragging = false; vertical = false; wheelAcc = 0f
                wheel = e.device?.name?.contains("wheel", ignoreCase = true) == true
                tracker?.recycle(); tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (wheel) {
                    wheelAcc += r1WheelDelta(lastX - e.x, lastY - e.y, display?.rotation ?: 0); lastX = e.x; lastY = e.y
                    if (abs(wheelAcc) >= 36f * d) { goTo(selected + if (wheelAcc > 0) 1 else -1); wheelAcc = 0f }
                    return true
                }
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && !vertical) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) dragging = true
                    else if (abs(dy) > slop && abs(dy) > abs(dx)) vertical = true
                }
                if (dragging && items.isNotEmpty()) {
                    var p = downPos - dx / spacing()
                    val top = (items.size - 1).toFloat()
                    if (p < 0f) p *= 0.35f else if (p > top) p = top + (p - top) * 0.35f
                    pos.snapTo(p)
                }
                if (vertical) cards.getOrNull(selected)?.let { it.lift = dy.coerceAtMost(0f) * 0.9f; it.fade = (1f + dy / (height * 0.7f)).coerceIn(0.2f, 1f) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(e); tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f; val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                if (wheel) return true
                val dy = e.y - downY
                when {
                    e.actionMasked == MotionEvent.ACTION_CANCEL -> goTo(selected)
                    vertical -> {
                        val card = cards.getOrNull(selected)
                        if (card != null && (dy < -height * 0.22f || vy < -1400f)) remove(selected)
                        else if (dy > height * 0.18f || vy > 1400f) { card?.let { springBack(it) }; close() }   // giù: chiude il selettore
                        else card?.let { springBack(it) }
                    }
                    dragging -> {
                        val base = pos.value.roundToInt()
                        val to = if (abs(vx) > 600f) (if (vx < 0) downPos.roundToInt() + 1 else downPos.roundToInt() - 1) else base
                        goTo(to)
                    }
                    else -> tap(e.x, e.y)
                }
            }
        }
        return true
    }

    private fun springBack(c: Card) {
        val l = c.lift; val f = c.fade
        ValueAnimator.ofFloat(1f, 0f).apply { duration = Motion.BASE; interpolator = android.view.animation.OvershootInterpolator(1.2f); addUpdateListener { val k = it.animatedValue as Float; c.lift = l * k; c.fade = 1f - (1f - f) * k }; start() }
    }

    private fun tap(x: Float, y: Float) {
        if (items.isEmpty()) { close(); return }
        // Tocco fuori dalle schede (sopra o sotto): chiude.
        val ch = cardH() * cards[selected].scaleY
        if (y < height * 0.53f - ch / 2f - 12f * d || y > height * 0.53f + ch / 2f + 12f * d) { close(); return }
        val off = ((x - width / 2f) / spacing()).roundToInt()
        if (off == 0) openSelected() else goTo(selected + off)
    }

    // ---- scheda --------------------------------------------------------------------------------------

    private class Card(ctx: Context, app: AppEntry, private val preview: Bitmap?) : View(ctx) {
        var lift = 0f
        var fade = 1f
        var corner = 1f; set(v) { field = v; invalidate() }
        private val d = ctx.resources.displayMetrics.density
        private val icon: Drawable = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
        private val label = app.label
        private val tint = averageColor(app.icon)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17f * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        private val r = RectF()

        init { contentDescription = label; setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val top = blend(tint, Color.WHITE, 0.10f); val bottom = blend(tint, Color.BLACK, 0.72f)
            bg.shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            glow.shader = RadialGradient(w / 2f, h * 0.42f, w * 0.55f, Color.argb(90, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            rim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(110, 255, 255, 255), Color.argb(20, 255, 255, 255), Shader.TileMode.CLAMP)
        }

        private val shot = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
        private val clip = Path()

        override fun onDraw(c: Canvas) {
            val rad = Shapes.CARD * d * corner
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            if (preview != null) { drawPreview(c, rad); return }
            c.drawRoundRect(r, rad, rad, bg)
            c.drawRoundRect(r, rad, rad, glow)
            if (corner > 0f) { r.inset(rim.strokeWidth / 2, rim.strokeWidth / 2); c.drawRoundRect(r, rad, rad, rim) }
            val s = (width * 0.36f).roundToInt(); val cx = width / 2; val cy = (height * 0.42f).roundToInt()
            icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2); icon.draw(c)
            c.drawText(TextUtilsCompat.ellipsize(label, text, width - 24f * d), width / 2f, height * 0.42f + s / 2f + 30f * d, text)
        }

        /** Con l'anteprima: l'ultima schermata dell'app riempie la scheda, icona e nome in una pillola in basso. */
        private fun drawPreview(c: Canvas, rad: Float) {
            val p = preview ?: return
            clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            c.drawColor(blend(tint, Color.BLACK, 0.6f))
            val scale = maxOf(width / p.width.toFloat(), height / p.height.toFloat())
            val dw = p.width * scale; val dh = p.height * scale
            c.drawBitmap(p, null, RectF((width - dw) / 2f, 0f, (width + dw) / 2f, dh), shot)   // in alto: si vede l'intestazione dell'app
            val ph = 44f * d; val py = height - ph - 10f * d
            val pr = RectF(10f * d, py, width - 10f * d, py + ph)
            c.drawRoundRect(pr, ph / 2, ph / 2, badge)
            val s = (ph - 14f * d).toInt(); val ix = (pr.left + 8f * d).toInt(); val iy = (py + 7f * d).toInt()
            icon.setBounds(ix, iy, ix + s, iy + s); icon.draw(c)
            text.textAlign = Paint.Align.LEFT; text.textSize = 15f * d
            c.drawText(TextUtilsCompat.ellipsize(label, text, pr.width() - s - 26f * d), ix + s + 8f * d, py + ph / 2 + 5.5f * d, text)
            text.textAlign = Paint.Align.CENTER; text.textSize = 17f * d
            c.restore()
            if (corner > 0f) { val rr = RectF(r); rr.inset(rim.strokeWidth / 2, rim.strokeWidth / 2); c.drawRoundRect(rr, rad, rad, rim) }
        }

        companion object {
            fun blend(a: Int, b: Int, k: Float) = Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).roundToInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).roundToInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).roundToInt())

            /** Colore medio dell'icona, saturato: il "colore" della scheda. */
            fun averageColor(dr: Drawable): Int = runCatching {
                val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp); val copy = dr.constantState?.newDrawable()?.mutate() ?: dr
                copy.setBounds(0, 0, 24, 24); copy.draw(cv)
                var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
                for (y in 0 until 24) for (x in 0 until 24) {
                    val p = bmp.getPixel(x, y); if (Color.alpha(p) < 128) continue
                    val hsv = FloatArray(3); Color.colorToHSV(p, hsv); if (hsv[1] < 0.15f && (hsv[2] > 0.9f || hsv[2] < 0.15f)) continue
                    rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p); n++
                }
                bmp.recycle()
                if (n == 0L) Theme.colorOf("teal") else {
                    val hsv = FloatArray(3); Color.colorToHSV(Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt()), hsv)
                    hsv[1] = (hsv[1] * 1.25f).coerceIn(0.35f, 0.9f); hsv[2] = hsv[2].coerceIn(0.45f, 0.85f); Color.HSVToColor(hsv)
                }
            }.getOrDefault(Theme.colorOf("teal"))
        }
    }

    private object TextUtilsCompat {
        fun ellipsize(s: String, p: Paint, w: Float): String = android.text.TextUtils.ellipsize(s, android.text.TextPaint(p), w, android.text.TextUtils.TruncateAt.END).toString()
    }
}

/** Apre il multitasking sopra la pagina attuale (menu → recenti, gesto dal fondo, schermata di prova). */
internal fun MainActivity.openSwitcher() {
    if (appSwitcher != null || pinGate != null) return
    if (pttView != null) pttClose()
    val apps = appCache ?: loadApps().also { appCache = it }
    val recent = recentApps().mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
    val sw = AppSwitcher(this, recent, UiText.text(this, R.string.ui_950486e052), UiText.text(this, R.string.ui_110d1350b1),
        onPick = { a ->
            try {
                noteLaunched(a.pkg); switcherLaunched = true
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(a.pkg, a.cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NO_ANIMATION)); true
            } catch (e: Exception) { switcherLaunched = false; snack(UiText.text(this, R.string.ui_f2c6915e4c)); false }
        },
        onRemove = { a -> forgetRecent(a.pkg); PreviewService.forget(this, a.pkg) },
        onClosed = { appSwitcher = null; switcherLaunched = false; stack.lastOrNull()?.view?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO })
    appSwitcher = sw
    stack.lastOrNull()?.view?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    root.addView(sw, ViewGroup.LayoutParams(-1, -1)); sw.bringToFront(); pinGate?.bringToFront()
    root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
}
