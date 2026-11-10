package dev.r1agents

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.r1os.host.ui.CharacterView
import dev.r1os.host.ui.Theme
import dev.r1os.host.ui.dp
import dev.r1os.host.ui.dpi
import dev.r1os.host.ui.keepCase
import dev.r1os.host.ui.text

fun roundRect(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

/** Disegna un agente col suo aspetto completo (forma, colore, finitura, occhi, accessori). */
fun CharacterView.show(a: Agent, state: String = "idle", activity: String? = null) =
    set(a.form, a.color, a.finish, state, activity, 0, a.eyes, a.wear.split(",").filter { it.isNotEmpty() })

/** Che cosa sta facendo l'agente, dalla riga di attivita' (italiano), per l'icona e il movimento del personaggio: search, files, shell, memory, build. */
fun activityKind(label: String): String {
    val l = label.lowercase()
    return when {
        listOf("web", "cerc", "pagina", "internet", "naviga").any { it in l } -> "search"
        listOf("file", "legg", "scriv", "modific", "cartella").any { it in l } -> "files"
        listOf("comando", "terminale", "esegu", "shell").any { it in l } -> "shell"
        listOf("memor", "ricord", "nota", "note").any { it in l } -> "memory"
        else -> "build"
    }
}

private fun rest(c: Context, on: Boolean, accent: Int, normal: android.graphics.drawable.Drawable?) = if (on) Jelly(accent, c.dp(14f)) else normal

/** Il riempimento a gelatina di r1os: i quattro bordi seguono ognuno una molla, e al focus si allarga dal centro con un piccolo rimbalzo. */
class Jelly(color: Int, private val radius: Float) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var l = 0f; private var t = 0f; private var r = 0f; private var b = 0f
    private var started = false
    private val sl = dev.r1os.host.ui.Spring { l = it; invalidateSelf() }
    private val st = dev.r1os.host.ui.Spring { t = it; invalidateSelf() }
    private val sr = dev.r1os.host.ui.Spring { r = it; invalidateSelf() }
    private val sb = dev.r1os.host.ui.Spring { b = it; invalidateSelf() }
    override fun onBoundsChange(bounds: android.graphics.Rect) {
        if (started || bounds.isEmpty) return
        started = true
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        sl.snapTo(w * 0.12f); sr.snapTo(w * 0.88f); st.snapTo(h * 0.38f); sb.snapTo(h * 0.62f)
        sl.animateTo(0f, 520f, 0.62f); sr.animateTo(w, 520f, 0.62f); st.animateTo(0f, 420f, 0.62f); sb.animateTo(h, 420f, 0.62f)
    }
    override fun draw(c: android.graphics.Canvas) {
        val o = bounds.left.toFloat(); val y = bounds.top.toFloat()
        if (b > t) c.drawRoundRect(o + l, y + t, o + r, y + b, radius, radius, paint)
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { paint.colorFilter = f }
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Movimento condiviso: tutte le animazioni usano le stesse curve di r1os (SETTLE: parte veloce e si posa piano; POP: piccolo rimbalzo). */
object Motion {
    // Token di movimento (vedi DESIGN.md): nessuna schermata scrive durate a mano.
    const val PRESS = 90L
    const val FAST = 140L
    const val BASE = 280L
    const val SLOW = 380L
    const val STEP = 24L
    /** Cicli continui: respiro lento (punti di scrittura), giro del buddy al lavoro, pulsazione di attesa. */
    const val LOOP_SLOW = 1100L
    const val LOOP = 1400L
    const val PULSE = 650L
    /** Uscite: partono piano e finiscono svelte (durano meno degli ingressi). */
    val EXIT: android.view.animation.Interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
    /** Vero se l'utente ha messo a zero la scala delle animazioni del sistema. */
    fun reduced(ctx: Context): Boolean = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** Un elemento che se ne va: prima si restringe e sfuma, poi la sua altezza si chiude e gli altri salgono; solo allora il chiamante toglie/ricostruisce (niente salti). */
    fun dismiss(v: View, then: () -> Unit) {
        v.animate().cancel()
        val h = v.height; val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
        v.animate().alpha(0f).scaleY(0.86f).scaleX(0.96f).translationX(-v.width * 0.05f).setDuration(FAST + 20).setInterpolator(EXIT).withEndAction {
            if (lp == null || h <= 0) { then(); return@withEndAction }
            val top = lp.topMargin
            android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = FAST + 40; interpolator = EXIT
                addUpdateListener { a -> val f = a.animatedValue as Float; lp.height = (h * f).toInt(); lp.topMargin = (top * f).toInt(); v.layoutParams = lp }
                addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { then() } })
                start()
            }
        }.start()
    }
    /** Cambio di testo con dissolvenza incrociata (esce il vecchio, entra il nuovo). */
    fun crossfade(tv: android.widget.TextView, text: CharSequence) {
        if (tv.text.toString() == text.toString()) return
        tv.animate().cancel()
        if (tv.visibility != View.VISIBLE || tv.alpha == 0f) { tv.text = text; tv.alpha = 0f; tv.animate().alpha(1f).setDuration(FAST).start(); return }
        tv.animate().alpha(0f).setDuration(FAST / 2).withEndAction { tv.text = text; tv.animate().alpha(1f).setDuration(FAST).start() }.start()
    }
    fun enter(v: View, dx: Float = 0f, dy: Float = 0f, delay: Long = 0L, dur: Long = BASE) {
        v.animate().cancel(); v.alpha = 0f; v.translationX = dx; v.translationY = dy
        v.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(delay).setDuration(dur).setInterpolator(Theme.SETTLE).start()
    }
    /** Le prime righe di `group` entrano una dopo l'altra; il resto resta pronto per lo scorrimento. */
    fun stagger(group: ViewGroup, dy: Float, from: Int = 0, step: Long = STEP, max: Int = 9) {
        // Dopo le prime righe (quelle visibili sull'R1) non avviare animatori per tutta la lista fuori schermo.
        for (i in from until group.childCount) {
            val child = group.getChildAt(i)
            if (i - from <= max) enter(child, dy = dy, delay = step * (i - from), dur = BASE)
            else { child.animate().cancel(); child.alpha = 1f; child.translationX = 0f; child.translationY = 0f }
        }
    }
    /** Pressione: si stringe appena e torna con un piccolo rimbalzo. */
    fun press(v: View, down: Boolean) {
        v.animate().cancel()
        // Come un dito sull'acqua: la scheda si avvalla appena (1,5%) e risale piano, senza rimbalzi.
        if (down) v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(FAST).setInterpolator(Theme.EASE_OUT).start()
        else v.animate().scaleX(1f).scaleY(1f).setDuration(SLOW).setInterpolator(Theme.EASE_OUT).start()
    }
    /** Un errore: scossa breve e smorzata (come un "no" con la testa), da dopo l'ingresso. */
    fun shake(v: View) {
        if (reduced(v.context)) return
        val d = v.resources.displayMetrics.density
        android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -9f * d, 8f * d, -6f * d, 4f * d, -2f * d, 0f).apply { duration = SLOW; interpolator = android.view.animation.LinearInterpolator(); startDelay = BASE; start() }
    }
    // ---- comparsa/scomparsa di pezzi d'interfaccia: mai di colpo -------------------------------------
    private val hiding = java.util.WeakHashMap<View, Boolean>()
    private var swapGen = 0
    private fun rest(v: View) { v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f }
    /** Fa comparire `v` con una leggera salita e un respiro di scala; se è già visibile non fa nulla. */
    fun show(v: View) {
        hiding.remove(v)
        if (reduced(v.context)) { v.visibility = View.VISIBLE; rest(v); return }
        if (v.visibility == View.VISIBLE && v.alpha >= .99f) return
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.translationY = v.resources.displayMetrics.density * 6f; v.scaleX = .94f; v.scaleY = .94f; v.visibility = View.VISIBLE }
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(BASE).setInterpolator(Theme.SETTLE).start()
    }
    /** Fa sparire `v` sfumando e rimpicciolendo appena, poi lo toglie dal layout. */
    fun hide(v: View, then: () -> Unit = {}) {
        if (v.visibility != View.VISIBLE) { then(); return }
        if (reduced(v.context)) { v.visibility = View.GONE; then(); return }
        if (hiding[v] == true) return
        hiding[v] = true; v.animate().cancel()
        v.animate().alpha(0f).scaleX(.94f).scaleY(.94f).setDuration(FAST).setInterpolator(EXIT).withEndAction {
            if (hiding.remove(v) != true) return@withEndAction // nel frattempo è stato rimostrato
            v.visibility = View.GONE; rest(v); then()
        }.start()
    }
    /** Scambio di pulsanti nello stesso posto (microfono, invio, stop): quelli attuali escono in fretta, il nuovo entra con rimbalzo. */
    fun swap(all: List<View>, inn: View) {
        val gen = ++swapGen
        if (reduced(inn.context)) { all.forEach { it.visibility = if (it === inn) View.VISIBLE else View.GONE; rest(it) }; return }
        fun enter() {
            if (gen != swapGen) return
            inn.animate().cancel()
            if (inn.visibility != View.VISIBLE) { inn.alpha = 0f; inn.scaleX = .7f; inn.scaleY = .7f; inn.visibility = View.VISIBLE }
            inn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(BASE).setInterpolator(Theme.POP).start()
        }
        val leaving = all.filter { it !== inn && it.visibility == View.VISIBLE }
        if (leaving.isEmpty()) { enter(); return }
        var left = leaving.size
        leaving.forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).scaleX(.7f).scaleY(.7f).setDuration(PRESS).setInterpolator(EXIT).withEndAction { v.visibility = View.GONE; rest(v); if (--left == 0) enter() }.start()
        }
    }
    /** L'ultimo elemento toccato: la pagina che si apre parte da li' (vedi MainActivity.tapOrigin). */
    var lastTap: java.lang.ref.WeakReference<View>? = null
    var lastTapAt = 0L
    /** Chi guarda gli elementi (il buddy compagno dei menu): avvisato a ogni tocco e a ogni cambio di fuoco della rotella. */
    var onAttention: ((View) -> Unit)? = null
    /** Inclinazione massima verso il dito (gradi): la scheda si "affossa" dove la tocchi. */
    private const val TILT = 1.6f
    private fun tilt(v: View, e: android.view.MotionEvent) {
        if (v.width <= 0 || v.height <= 0 || v.height > v.resources.displayMetrics.density * 220f || reduced(v.context)) return
        val nx = ((e.x / v.width) * 2f - 1f).coerceIn(-1f, 1f); val ny = ((e.y / v.height) * 2f - 1f).coerceIn(-1f, 1f)
        v.cameraDistance = v.resources.displayMetrics.density * 900f
        // Inclinazione morbida: segue il dito con una breve animazione invece di scattare.
        v.animate().rotationY(nx * TILT).rotationX(-ny * TILT).translationX(nx * v.resources.displayMetrics.density * 0.6f).setDuration(FAST).setInterpolator(Theme.EASE_OUT).start()
    }
    private fun untilt(v: View) {
        v.animate().rotationX(0f).rotationY(0f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(SLOW).setInterpolator(Theme.EASE_OUT).start()
    }
    fun touch(v: View, e: android.view.MotionEvent) {
        if (e.action == android.view.MotionEvent.ACTION_DOWN) { onAttention?.invoke(v); lastTap = java.lang.ref.WeakReference(v); lastTapAt = android.os.SystemClock.uptimeMillis() }
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> { press(v, true); tilt(v, e); v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
            android.view.MotionEvent.ACTION_MOVE -> tilt(v, e)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> untilt(v)
        }
    }
}

/** Una riga in forma di scheda (come le impostazioni): fondo scuro arrotondato, icona opzionale su un tondo colorato, e a fuoco si riempie a gelatina. `check` mette un segno a destra. */
fun Context.row(label: String, accent: Int, bold: Boolean = false, sub: String? = null, raw: Boolean = false, check: Boolean = false, icon: String? = null, onClick: () -> Unit): LinearLayout {
    val gap = dpi(8f); val r = dp(20f)
    val l = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(11f) + gap); minimumHeight = dpi(64f) + gap
        setOnClickListener { onClick() }
    }
    val chip = icon?.let { chipOf(it, accent, 44f) }
    chip?.let { l.addView(it, LinearLayout.LayoutParams(dpi(44f), dpi(44f)).apply { marginEnd = dpi(12f) }) }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val title = text(if (sub != null) 20f else 21f, if (bold || sub != null) 700 else 500).also { if (raw) it.keepCase() }.apply { text = label; maxLines = if (sub != null) 1 else 2; ellipsize = TextUtils.TruncateAt.END }
    texts.addView(title)
    val subView = sub?.let { s -> text(14f, 500, Theme.MUTED).keepCase().apply { text = s; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(2f), 0, 0) }.also { texts.addView(it) } }
    l.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    if (check) l.addView(IconView(this, "check", accent, Theme.ON_ACCENT), LinearLayout.LayoutParams(dpi(24f), dpi(24f)).apply { marginStart = dpi(8f) })
    fun paint(on: Boolean) {
        l.background = android.graphics.drawable.InsetDrawable(if (on) Jelly(accent, r) else roundRect(Theme.CARD, r), 0, 0, 0, gap)
        l.setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(11f) + gap)   // setBackground sostituisce il padding con quello del drawable (qui solo lo spazio sotto)
        title.setTextColor(if (on) Theme.ON_ACCENT else Theme.FG)
        subView?.setTextColor(if (on) Theme.withAlpha(Theme.ON_ACCENT, 0.7f) else Theme.MUTED)
        chip?.background = roundRect(if (on) Theme.withAlpha(Theme.ON_ACCENT, 0.16f) else Theme.FOCUS, dp(15f))
    }
    paint(false)
    l.setOnFocusChangeListener { _, f -> paint(f) }
    l.setOnTouchListener { v, e -> Motion.touch(v, e); false }   // al tocco solo un movimento lieve: niente riempimenti colorati
    return l
}

/** Il testo del titolo di una riga (per cambiarlo dopo). */
fun rowTitle(row: LinearLayout): TextView {
    fun find(v: View): TextView? = if (v is TextView) v else if (v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) } else null
    return find(row)!!
}

/** Stato vuoto: un titolo e, se serve, una riga di aiuto, al centro. */
/** L'agente in vista (home), che fa da mascotte negli stati vuoti. */
object Mascot { var agent: Agent? = null }

fun Context.emptyState(title: String, hint: String? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dpi(16f), dpi(28f), dpi(16f), dpi(24f))
    // Il buddy vive nell'intestazione fissa: lo stato vuoto non ne crea una seconda copia nel contenuto.
    addView(text(19f, 600, Theme.MUTED).apply { text = title; gravity = Gravity.CENTER })
    hint?.let { addView(text(14f, 500, Theme.MUTED).keepCase().apply { text = it; gravity = Gravity.CENTER; alpha = 0.8f; setPadding(0, dpi(6f), 0, 0) }) }
}

/** Un bottone come FocusList.button di r1os: etichetta 20/700 nell'accento su pillola scura (primario) o muta (secondario). */
fun Context.button(label: String, accent: Int, primary: Boolean = true, small: Boolean = false, filled: Boolean = primary && !small, onClick: () -> Unit): TextView {
    val color = if (filled) Theme.ON_ACCENT else if (primary) accent else Theme.MUTED
    // Il gesto principale di una schermata e' pieno nell'accento (si vede subito quale tasto premere); gli altri restano pillole scure.
    val normal = if (filled) roundRect(accent, dp(16f)) else if (primary) roundRect(Theme.FOCUS, dp(14f)) else null
    val v = if (small) 10f else 13f; val hp = if (small) 16f else 14f
    return text(if (small) 15f else 20f, 700, color).apply {
        text = label; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false; minHeight = dpi(if (small) 44f else 56f)
        setPadding(dpi(hp), dpi(v), dpi(hp), dpi(v)); background = normal
        fun paint(on: Boolean) {
            setTextColor(if (filled || on) Theme.ON_ACCENT else color)
            background = if (filled) (if (on) Jelly(Theme.mix(accent, android.graphics.Color.WHITE, 0.28f), dp(16f)) else normal) else if (on) roundRect(accent.takeIf { primary } ?: Theme.FOCUS, dp(14f)) else normal
            setPadding(dpi(hp), dpi(v), dpi(hp), dpi(v))
        }
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnTouchListener { v, e -> Motion.touch(v, e); false }   // al tocco solo un movimento lieve: niente riempimenti colorati
        setOnClickListener { onClick() }
    }
}

/** Sfondo "mesh" di r1os: macchie sfumate dei colori della palette su fondo scuro. */
class MeshDrawable(private val base: Int, private val colors: List<Int>) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val spots = listOf(0.12f to 0.18f, 0.95f to 0.38f, 0.45f to 1.0f)
    // Fondale che fluttua (come Backdrop di r1os): ogni luce scivola a modo suo a ogni tacca e una molla morbida la riporta a posto.
    private val dx = FloatArray(3); private val dy = FloatArray(3)
    private val sx = Array(3) { i -> dev.r1os.host.ui.Spring { dx[i] = it; invalidateSelf() } }
    private val sy = Array(3) { i -> dev.r1os.host.ui.Spring { dy[i] = it; invalidateSelf() } }
    private val weight = floatArrayOf(1f, 0.65f, 1.3f)
    private val swirl = floatArrayOf(0.6f, -0.8f, 0.4f)
    private val gradients = colors.map { col -> android.graphics.RadialGradient(0f, 0f, 1f, (col and 0xffffff) or (0xd0 shl 24), col and 0xffffff, android.graphics.Shader.TileMode.CLAMP) }
    private val gradientMatrix = android.graphics.Matrix()
    fun nudge(d: Int, kick: Float) {
        for (i in 0 until minOf(3, colors.size)) {
            sy[i].shift(-d * kick * weight[i]); sx[i].shift(d * kick * 0.8f * swirl[i])
            sy[i].animateTo(0f, 38f, 0.55f); sx[i].animateTo(0f, 38f, 0.55f)
        }
    }
    override fun draw(c: android.graphics.Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        c.drawColor(base)
        gradients.forEachIndexed { i, shader ->
            val (x, y) = spots[i % spots.size]
            val k = i % 3
            val radius = maxOf(w, h) * .8f
            gradientMatrix.setScale(radius, radius); gradientMatrix.postTranslate(x * w + dx[k], y * h + dy[k])
            shader.setLocalMatrix(gradientMatrix); p.shader = shader
            c.drawRect(0f, 0f, w, h, p)
        }
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/** Foto di sfondo a tutto schermo (ritagliata al centro) con una velatura scura. */
class PhotoDrawable(private val bmp: android.graphics.Bitmap, private val dim: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val veil = android.graphics.Paint().apply { color = android.graphics.Color.argb((dim * 255).toInt(), 0, 0, 0) }
    private val matrix = android.graphics.Matrix()
    override fun draw(c: android.graphics.Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val s = maxOf(w / bmp.width, h / bmp.height)
        matrix.setScale(s, s); matrix.postTranslate((w - bmp.width * s) / 2, (h - bmp.height * s) / 2)
        c.drawBitmap(bmp, matrix, p); c.drawRect(0f, 0f, w, h, veil)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}


/** Icone semplici (griglia 24, tratto arrotondato) nello stile di Phosphor "bold" di r1os: back, plus, send, mic, close, check. */
class IconView(ctx: Context, private val kind: String, private val color: Int, private val onColor: Int) : android.view.View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; strokeWidth = 2.4f }
    // La geometria resta identica tra i frame: non si riallocano Path e RectF durante le animazioni.
    private val path = createPath()
    private fun createPath(): android.graphics.Path {
        val path = android.graphics.Path()
        when (kind) {
            "back" -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f) }
            "plus" -> { path.moveTo(12f, 5f); path.lineTo(12f, 19f); path.moveTo(5f, 12f); path.lineTo(19f, 12f) }
            "send" -> { path.moveTo(12f, 19f); path.lineTo(12f, 5f); path.moveTo(6f, 11f); path.lineTo(12f, 5f); path.lineTo(18f, 11f) }
            "attach" -> { // graffetta
                path.moveTo(16f, 7.5f); path.lineTo(16f, 15f); path.arcTo(android.graphics.RectF(8f, 11f, 16f, 19f), 0f, 180f, false)
                path.lineTo(8f, 7f); path.arcTo(android.graphics.RectF(8f, 4f, 13f, 9f), 180f, 180f, false); path.lineTo(13f, 14f)
            }
            "more" -> { for (cx in floatArrayOf(5.5f, 12f, 18.5f)) path.addCircle(cx, 12f, 1.9f, android.graphics.Path.Direction.CW) }
            "close" -> { path.moveTo(6f, 6f); path.lineTo(18f, 18f); path.moveTo(18f, 6f); path.lineTo(6f, 18f) }
            "check" -> { path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f) }
            "lock" -> { path.addRoundRect(android.graphics.RectF(5.5f, 10.5f, 18.5f, 20.5f), 2.5f, 2.5f, android.graphics.Path.Direction.CW)
                path.moveTo(8.5f, 10.5f); path.lineTo(8.5f, 8f); path.arcTo(android.graphics.RectF(8.5f, 3.5f, 15.5f, 12.5f), 180f, 180f, false); path.lineTo(15.5f, 10.5f) }
            "chat" -> { path.moveTo(5f, 6f); path.lineTo(19f, 6f); path.lineTo(19f, 16f); path.lineTo(11f, 16f); path.lineTo(7f, 19.5f); path.lineTo(7f, 16f); path.lineTo(5f, 16f); path.close() }
            "chevron" -> { path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f) }
            "alarm" -> { path.moveTo(6f, 17f); path.lineTo(18f, 17f); path.moveTo(7f, 17f); path.lineTo(7f, 11f); path.arcTo(android.graphics.RectF(7f, 4f, 17f, 14f), 180f, 180f, false); path.lineTo(17f, 17f)
                path.moveTo(10f, 20f); path.arcTo(android.graphics.RectF(10f, 18f, 14f, 22f), 180f, -180f, false) }
            "timer" -> { path.addCircle(12f, 13.5f, 7f, android.graphics.Path.Direction.CW); path.moveTo(12f, 13.5f); path.lineTo(12f, 9.5f); path.moveTo(10f, 3f); path.lineTo(14f, 3f); path.moveTo(12f, 3f); path.lineTo(12f, 6.5f) }
            "globe" -> { path.addCircle(12f, 12f, 9f, android.graphics.Path.Direction.CW); path.addOval(android.graphics.RectF(8f, 3f, 16f, 21f), android.graphics.Path.Direction.CW); path.moveTo(3f, 12f); path.lineTo(21f, 12f) }
            "note" -> { path.addRoundRect(android.graphics.RectF(5f, 3f, 19f, 21f), 3f, 3f, android.graphics.Path.Direction.CW); path.moveTo(9f, 9f); path.lineTo(15f, 9f); path.moveTo(9f, 13f); path.lineTo(15f, 13f); path.moveTo(9f, 17f); path.lineTo(12f, 17f) }
            "repeat" -> { path.moveTo(17f, 3.5f); path.lineTo(20f, 7f); path.lineTo(17f, 10.5f); path.moveTo(20f, 7f); path.lineTo(9f, 7f); path.arcTo(android.graphics.RectF(4f, 7f, 8f, 11f), 270f, -90f, false)
                path.moveTo(7f, 20.5f); path.lineTo(4f, 17f); path.lineTo(7f, 13.5f); path.moveTo(4f, 17f); path.lineTo(15f, 17f); path.arcTo(android.graphics.RectF(16f, 13f, 20f, 17f), 90f, -90f, false) }
            "look" -> { path.addCircle(12f, 12f, 4f, android.graphics.Path.Direction.CW)
                for (i in 0 until 8) { val an = Math.toRadians(i * 45.0); path.moveTo(12f + 7f * Math.cos(an).toFloat(), 12f + 7f * Math.sin(an).toFloat()); path.lineTo(12f + 9.5f * Math.cos(an).toFloat(), 12f + 9.5f * Math.sin(an).toFloat()) } }
            "data" -> { path.moveTo(12f, 4f); path.lineTo(12f, 14f); path.moveTo(8f, 10f); path.lineTo(12f, 14f); path.lineTo(16f, 10f); path.moveTo(5f, 18f); path.lineTo(19f, 18f) }
            "spark" -> { path.moveTo(12f, 3f); path.lineTo(14.2f, 9.8f); path.lineTo(21f, 12f); path.lineTo(14.2f, 14.2f); path.lineTo(12f, 21f); path.lineTo(9.8f, 14.2f); path.lineTo(3f, 12f); path.lineTo(9.8f, 9.8f); path.close() }
            "agent" -> { path.addCircle(12f, 12f, 8.5f, android.graphics.Path.Direction.CW); path.moveTo(9f, 10f); path.lineTo(9f, 11.5f); path.moveTo(15f, 10f); path.lineTo(15f, 11.5f); path.moveTo(9f, 15f); path.arcTo(android.graphics.RectF(9f, 12.5f, 15f, 17f), 160f, -140f, false) }
            "info" -> { path.addCircle(12f, 12f, 9f, android.graphics.Path.Direction.CW); path.moveTo(12f, 11f); path.lineTo(12f, 17f); path.addCircle(12f, 7.6f, 0.4f, android.graphics.Path.Direction.CW) }
            "sliders" -> { path.moveTo(4f, 7f); path.lineTo(13f, 7f); path.moveTo(19f, 7f); path.lineTo(20f, 7f); path.addCircle(16f, 7f, 2.6f, android.graphics.Path.Direction.CW)
                path.moveTo(4f, 17f); path.lineTo(5f, 17f); path.moveTo(11f, 17f); path.lineTo(20f, 17f); path.addCircle(8f, 17f, 2.6f, android.graphics.Path.Direction.CW) }
            "mic" -> {
                path.addRoundRect(android.graphics.RectF(9f, 3f, 15f, 14f), 3f, 3f, android.graphics.Path.Direction.CW)
                path.moveTo(5.5f, 11f); path.arcTo(android.graphics.RectF(5.5f, 5f, 18.5f, 18f), 180f, -180f, false)
                path.moveTo(12f, 18f); path.lineTo(12f, 21f)
            }
        }
        return path
    }
    init { isDuplicateParentStateEnabled = true }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(c: android.graphics.Canvas) {
        val s = minOf(width, height).toFloat(); if (s <= 0f) return
        val on = drawableState.any { it == android.R.attr.state_focused }   // la pressione non cambia colore: solo il fuoco della rotella
        p.color = if (on) onColor else color
        c.save(); c.translate((width - s) / 2, (height - s) / 2); c.scale(s / 24f, s / 24f)
        if (kind == "more") p.style = android.graphics.Paint.Style.FILL
        c.drawPath(path, p); c.restore()
        p.style = android.graphics.Paint.Style.STROKE
    }
}

/** Tasto tondo da 48 dp (minimo per il tocco): pieno nell'accento o scuro con icona nell'accento; si riempie col fuoco o al tocco. */
fun Context.iconButton(kind: String, accent: Int, filled: Boolean = true, size: Float = 48f, onClick: () -> Unit): android.widget.FrameLayout {
    val r = dp(size / 2)
    val rest = roundRect(if (filled) accent else Theme.FOCUS, r)
    val on = roundRect(accent, r)
    return android.widget.FrameLayout(this).apply {
        isClickable = true; defaultFocusHighlightEnabled = false; isFocusable = true
        contentDescription = when (kind) { "back" -> UiText.text(context, R.string.ui_e5844b95db); "plus" -> UiText.text(context, R.string.ui_7f4849bfb0); "send" -> UiText.text(context, R.string.ui_d554d69f55); "mic" -> UiText.text(context, R.string.ui_1cbfd76bb1); "close" -> UiText.text(context, R.string.ui_78be0e73b2); "more" -> UiText.text(context, R.string.ui_bcfaf5fac6); "attach" -> UiText.text(context, R.string.ui_0fd7b04a53); "check" -> UiText.text(context, R.string.ui_3cd50c52c0); else -> kind }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), on); addState(intArrayOf(), rest)
        }
        addView(IconView(context, kind, if (filled) Theme.ON_ACCENT else accent, Theme.ON_ACCENT), android.widget.FrameLayout.LayoutParams(dpi(size), dpi(size)).also { it.setMargins(0, 0, 0, 0) })
        layoutParams = android.widget.LinearLayout.LayoutParams(dpi(size), dpi(size))
        setOnClickListener { onClick() }
        setOnTouchListener { v, e -> Motion.touch(v, e); false }
    }
}

/** Vita di una schermata: i lavori in secondo piano controllano `alive` prima di toccare la UI (si spegne quando la schermata viene lasciata). */
class Scope { @Volatile var alive = true }

/** Lavori in secondo piano su un pool condiviso (thread riusati e con nome); un'eccezione non uccide più l'app, finisce nel log. */
object Io {
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "r1agents-io-${seq.incrementAndGet()}").apply { isDaemon = true } }
    fun run(work: () -> Unit) { pool.execute { try { work() } catch (t: Throwable) { android.util.Log.e("r1agents", "lavoro in secondo piano fallito", t) } } }
}

/** Dopo un po' senza tocchi l'agente si addormenta (sbadiglia, poi dorme); al primo tocco si sveglia di soprassalto. Chiamare `poke()` a ogni input. */
class Sleeper(private val view: View, private val ch: () -> CharacterView?, private val wake: () -> Unit, private val sleep: () -> Unit, private val afterMs: Long = 75_000L, private val afterProvider: (() -> Long)? = null) {
    var asleep = false
        private set
    private val nod = Runnable {
        if (!view.isAttachedToWindow) return@Runnable
        ch()?.react("yawn"); view.postDelayed({ if (view.isAttachedToWindow) { asleep = true; sleep() } }, 1700)
    }
    fun poke() {
        view.removeCallbacks(nod)
        if (asleep) { asleep = false; wake(); ch()?.react("surprised") }
        val wait = afterProvider?.invoke() ?: afterMs
        if (wait > 0L) view.postDelayed(nod, wait)   // 0 = mai (impostazione)
    }
    fun stop() { view.removeCallbacks(nod) }
}

/** Il cerchio tratteggiato di "nuovo agente" (come DashedRing di r1os). */
class DashedRing(ctx: Context) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; color = Theme.SUBTLE
        strokeWidth = ctx.dp(3f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.dp(1f), ctx.dp(11f)), 0f)
    }
    override fun draw(c: android.graphics.Canvas) { val r = Math.min(bounds.width(), bounds.height()) / 2f - p.strokeWidth; c.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, p) }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Un tamburo come quelli di r1os: una colonna di valori che scorre con una molla e si ferma su uno scatto. Col dito (o la rotella, che arriva
 * come scorrimento) segue il movimento, con uno scatto va oltre e si posa piano; coi tasti su/giu' fa un passo. `wrap` lo chiude in cerchio.
 */
class Drum(ctx: Context, private val labels: List<String>, initial: Int, private val wrap: Boolean, private val onChange: (Int) -> Unit) : View(ctx) {
    private val rowH = ctx.dp(40f)
    private var pos = initial.toFloat()
    private var last = initial
    private val spring = dev.r1os.host.ui.Spring { pos = it; check(); invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; typeface = Theme.font(700); isFakeBoldText = false; fontFeatureSettings = "tnum" }
    private val band = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Theme.FOCUS }
    private var startPos = 0f; private var downY = 0f; private var vt: android.view.VelocityTracker? = null
    private var settlingTo: Int? = null
    private fun normalized(index: Int) = ((index % labels.size) + labels.size) % labels.size
    val value get() = settlingTo ?: normalized(Math.round(pos))
    init { isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false; contentDescription = labels.getOrNull(initial) }
    /** Porta il tamburo su un valore (per i preset rapidi). */
    fun select(index: Int) = settle(index)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w).let { if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) context.dpi(90f) else it }, (rowH * 5).toInt())

    private fun clampPos(p: Float) = if (wrap) p else p.coerceIn(-0.4f, labels.size - 0.6f)
    private fun check() {
        if (settlingTo != null) {
            if (kotlin.math.abs(pos - spring.target) > .01f) return
            settlingTo = null
        }
        val v = value; if (v != last) { last = v; contentDescription = labels[v]; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onChange(v) } }
    private fun settle(target: Int) {
        val t = if (wrap) target else target.coerceIn(0, labels.size - 1)
        // La scelta è immediata: i fotogrammi intermedi non cambiano il valore
        // che verrà salvato o avviato mentre il tamburo raggiunge la destinazione.
        settlingTo = normalized(t)
        if (last != settlingTo) { last = settlingTo!!; contentDescription = labels[last]; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onChange(last) }
        spring.animateTo(t.toFloat(), 230f, 0.85f)
    }
    fun step(d: Int) = settle(Math.round(spring.target) + d)

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); downY = e.y; startPos = pos; spring.cancel(); settlingTo = null; check(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(e); requestFocus() }
            android.view.MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); pos = clampPos(startPos - (e.y - downY) / rowH); check(); invalidate() }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f; vt?.recycle(); vt = null
                spring.snapTo(pos)
                // Un tocco su una riga vicina la porta al centro; uno scatto va oltre.
                if (Math.abs(e.y - downY) < context.dp(8f) && e.actionMasked == android.view.MotionEvent.ACTION_UP) settle(Math.round(pos + (e.y - height / 2f) / rowH))
                else settle(Math.round(pos - vy / rowH * 0.12f))
            }
        }
        return true
    }
    override fun onKeyDown(k: Int, e: android.view.KeyEvent): Boolean = when (k) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> { step(-1); true }
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { step(1); true }
        else -> super.onKeyDown(k, e)
    }
    override fun onDraw(c: android.graphics.Canvas) {
        val cy = height / 2f
        c.drawRoundRect(0f, cy - rowH / 2f, width.toFloat(), cy + rowH / 2f, rowH / 2.6f, rowH / 2.6f, band)
        val base = Math.round(pos)
        for (i in base - 3..base + 3) {
            if (!wrap && (i < 0 || i >= labels.size)) continue
            val d = i - pos; val ad = Math.abs(d)
            if (ad > 2.6f) continue
            paint.textSize = rowH * (0.78f - 0.16f * Math.min(ad, 2f) / 2f)
            paint.color = if (ad < 0.5f) Theme.accent else Theme.FG
            paint.alpha = ((1f - ad / 2.8f).coerceIn(0f, 1f) * 255).toInt()
            val label = labels[((i % labels.size) + labels.size) % labels.size]
            c.drawText(label, width / 2f, cy + d * rowH + paint.textSize * 0.36f, paint)
        }
    }
}


private fun cardPaint(l: android.view.View, accent: Int, radius: Float, chip: android.view.View?, title: android.widget.TextView, sub: android.widget.TextView?, on: Boolean) {
    l.background = if (on) Jelly(accent, radius) else roundRect(Theme.CARD, radius)
    title.setTextColor(if (on) Theme.ON_ACCENT else Theme.FG)
    sub?.setTextColor(if (on) Theme.withAlpha(Theme.ON_ACCENT, 0.72f) else Theme.MUTED)
    chip?.background = roundRect(if (on) Theme.withAlpha(Theme.ON_ACCENT, 0.16f) else Theme.FOCUS, l.context.dp(16f))
}

/** Tondo delle icone dei menu: stesso stile ovunque (fondo neutro, icona nell'accento dell'app; rosso solo per le azioni distruttive), niente colori pastello. */
private fun Context.chipOf(icon: String, accent: Int, size: Float): android.widget.FrameLayout = android.widget.FrameLayout(this).apply {
    background = roundRect(Theme.FOCUS, dp(16f))
    addView(IconView(this@chipOf, icon, if (accent == Theme.BAD) Theme.BAD else Theme.accent, Theme.ON_ACCENT), android.widget.FrameLayout.LayoutParams(dpi(size * 0.5f), dpi(size * 0.5f), Gravity.CENTER))
}

private fun android.view.View.cardInput(accent: Int, radius: Float, chip: android.view.View?, title: android.widget.TextView, sub: android.widget.TextView?, onClick: () -> Unit) {
    isFocusable = true; isClickable = true; setOnClickListener { onClick() }
    setOnFocusChangeListener { _, f -> cardPaint(this, accent, radius, chip, title, sub, f) }
    setOnTouchListener { v, e ->
        // Al tocco niente riempimento colorato: la scheda si avvalla appena (Motion.touch) e l'icona si alza di un soffio.
        if (chip != null) {
            if (e.action == android.view.MotionEvent.ACTION_DOWN) chip.animate().cancel().also { chip.animate().scaleX(1.04f).scaleY(1.04f).setDuration(Motion.FAST).setInterpolator(Theme.EASE_OUT).start() }
            else if (e.action == android.view.MotionEvent.ACTION_UP || e.action == android.view.MotionEvent.ACTION_CANCEL) chip.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Theme.EASE_OUT).start()
        }
        Motion.touch(v, e); false
    }
}

/** Una riga grande per le impostazioni: icona su un tondo colorato, titolo, riga di aiuto e freccia; a fuoco si riempie a gelatina. */
fun Context.bigRow(icon: String, title: String, sub: String?, accent: Int, onClick: () -> Unit): LinearLayout {
    val r = dp(22f)
    val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; defaultFocusHighlightEnabled = false
        setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)); minimumHeight = dpi(78f); background = roundRect(Theme.CARD, r) }
    val chip = chipOf(icon, accent, 52f)
    val t = text(22f, 700).apply { text = title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val s = sub?.let { text(14f, 500, Theme.MUTED).keepCase().apply { text = it; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(2f), 0, 0) } }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t); s?.let { addView(it) } }
    l.addView(chip, LinearLayout.LayoutParams(dpi(52f), dpi(52f)))
    l.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(14f); marginEnd = dpi(8f) })
    l.addView(IconView(this, "chevron", Theme.MUTED, Theme.ON_ACCENT), LinearLayout.LayoutParams(dpi(22f), dpi(22f)))
    l.cardInput(accent, r, chip, t, s, onClick)
    return l
}

/** Una scheda quadrata grande (menu, strumenti): icona in alto, titolo e riga di aiuto in basso. */
fun Context.tile(icon: String, title: String, sub: String?, accent: Int, onClick: () -> Unit): LinearLayout {
    val r = dp(24f)
    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; defaultFocusHighlightEnabled = false
        setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(14f)); minimumHeight = dpi(138f); background = roundRect(Theme.CARD, r) }
    val chip = chipOf(icon, accent, 52f)
    val t = text(22f, 700).apply { text = title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val s = sub?.let { text(13f, 500, Theme.MUTED).keepCase().apply { text = it; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(2f), 0, 0) } }
    l.addView(chip, LinearLayout.LayoutParams(dpi(52f), dpi(52f)))
    l.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    l.addView(t); s?.let { l.addView(it) }
    l.cardInput(accent, r, chip, t, s, onClick)
    return l
}


/** Raggruppa in schede: ogni titolo (`label`) apre una scheda con il suo contenuto fino al titolo dopo. I primi `head` figli e gli ultimi `tail` restano fuori (anteprima, pulsante "salva"). */
fun Context.cardify(col: LinearLayout, head: Int = 0, tail: Int = 0) {
    val kids = (0 until col.childCount).map { col.getChildAt(it) }
    val tailStart = kids.size - tail
    kids.forEach { col.removeView(it) }
    var card: LinearLayout? = null
    kids.forEachIndexed { i, v ->
        if (i < head || i >= tailStart) { card = null; col.addView(v); return@forEachIndexed }
        if (v.tag == "label") {
            card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(14f)) }
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
            v.setPadding(dpi(2f), dpi(2f), 0, dpi(8f))
            card!!.addView(v)
        } else if (card != null) {
            val lp = v.layoutParams as? LinearLayout.LayoutParams
            card!!.addView(v, lp ?: LinearLayout.LayoutParams(-1, -2))
        } else col.addView(v)
    }
}


/** I tre puntini che "respirano" mentre l'agente scrive (come Typing di r1os): ognuno pulsa un poco dopo il precedente. */
class TypingDots(private val ctx: Context) : android.graphics.drawable.Drawable(), android.graphics.drawable.Animatable {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED }
    private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = Motion.LOOP_SLOW; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator(); addUpdateListener { invalidateSelf() } }
    override fun getIntrinsicWidth() = ctx.dpi(34f)
    override fun getIntrinsicHeight() = ctx.dpi(14f)
    init { anim.start() }
    override fun draw(c: android.graphics.Canvas) {
        val t = anim.animatedFraction
        for (i in 0 until 3) {
            val ph = ((t - i * 0.16f) % 1f + 1f) % 1f
            val k = (Math.sin(ph * Math.PI * 2).toFloat() * 0.5f + 0.5f)
            paint.alpha = (90 + 165 * k).toInt()
            c.drawCircle(bounds.left + bounds.width() * (0.16f + 0.34f * i), bounds.centerY() - k * ctx.dp(2.5f), ctx.dp(3f) + k * ctx.dp(1.2f), paint)
        }
    }
    override fun start() { if (!anim.isRunning) anim.start() }
    override fun stop() { anim.cancel() }
    override fun isRunning() = anim.isRunning
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: android.graphics.ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Contenitore che prende per se' i gesti della rotella (dispositivi "...wheel...") prima dei figli: la rotella manda ogni scatto come un piccolo
 * tocco che parte da un punto fisso, e se un figlio (un messaggio che reagisce al tocco lungo) riceveva il DOWN, la prima mossa veniva calcolata dal
 * punto del gesto precedente e dava uno strappo nella direzione opposta. Qui il DOWN e' sempre nostro. Col dito `onFinger` ferma i movimenti in corso.
 */
class WheelFrame(ctx: Context, private val onWheel: (Float) -> Unit, private val onFinger: () -> Unit) : android.widget.FrameLayout(ctx) {
    private var last = 0f
    private var lastX = 0f
    private fun wheel(e: android.view.MotionEvent) = e.device?.name?.contains("wheel", ignoreCase = true) == true
    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        if (wheel(e)) return true
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) onFinger()
        return false
    }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!wheel(e)) return super.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { last = e.y; lastX = e.x }
            android.view.MotionEvent.ACTION_MOVE -> { onWheel(r1WheelDelta(lastX - e.x, last - e.y, display?.rotation ?: 0)); last = e.y; lastX = e.x }
        }
        return true
    }
}


/** ScrollView che non supera un'altezza massima (per i fogli: il contenuto scorre dentro il pannello). */
class MaxHeightScrollView(ctx: Context, private val maxPx: () -> Int) : R1ScrollView(ctx) {
    init { buddyFx.enabled = false }   // il buddy del pannello lo gestisce il pannello (placePeek), non lo scroll
    override fun onMeasure(w: Int, h: Int) {
        val cap = maxPx().coerceAtLeast(1)
        super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(Math.min(android.view.View.MeasureSpec.getSize(h).takeIf { it > 0 } ?: cap, cap), android.view.View.MeasureSpec.AT_MOST))
    }
}

/** Sfondo con soli angoli superiori arrotondati (pannelli che salgono dal basso). */
fun topRoundRect(color: Int, radius: Float): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) }


/**
 * Interruttore a pillola: la manopola scorre con una molla e il colore della traccia sfuma tra spento e acceso. Tocca per cambiare; non ricostruisce
 * la lista che lo contiene (chi lo usa aggiorna solo cio' che cambia).
 */
class SwitchView(ctx: Context, on: Boolean, private val accent: Int, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var checked = on
        private set
    private var pos = if (on) 1f else 0f
    private val spring = dev.r1os.host.ui.Spring { pos = it; invalidate() }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init {
        isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
        setOnClickListener { set(!checked, true) }
        setOnTouchListener { v, e -> Motion.touch(v, e); false }
    }
    // Per TalkBack e la rotella: e' un interruttore, con il suo stato acceso/spento.
    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.isCheckable = true; info.isChecked = checked
    }
    fun set(value: Boolean, notify: Boolean) {
        checked = value; spring.animateTo(if (value) 1f else 0f, 460f, 0.72f)
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = if (value) "on" else "off"
        if (notify) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onChange(value) }
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(64f), context.dpi(48f))
    override fun onDraw(c: android.graphics.Canvas) {
        val tw = context.dp(52f); val th = context.dp(30f); val cx = width / 2f; val cy = height / 2f
        paint.color = Theme.mix(Theme.SUBTLE, accent, pos.coerceIn(0f, 1f)); c.drawRoundRect(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2, th / 2, th / 2, paint)
        val r = context.dp(11f); val travel = tw / 2 - th / 2
        paint.color = if (checked) Theme.ON_ACCENT else Theme.FG
        c.drawCircle(cx - travel + 2 * travel * pos, cy, r + context.dp(1.5f) * (1f - Math.abs(pos - 0.5f) * 2f).coerceIn(0f, 1f), paint)
    }
}
