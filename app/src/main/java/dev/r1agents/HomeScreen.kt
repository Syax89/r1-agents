package dev.r1agents

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.r1os.host.ui.*
import android.net.Uri
import org.json.JSONArray
import dev.r1agents.MainActivity.AppEntry
import dev.r1agents.MainActivity.Hero
import dev.r1agents.MainActivity.HomeSlide
import dev.r1agents.MainActivity.Screen
import dev.r1agents.MainActivity.SheetItem
import android.content.Context.CLIPBOARD_SERVICE

// Spostate da MainActivity parola per parola (fase D): estensioni con accesso ai membri internal.

/** Primo avvio: una sola volta, se non c'è ancora un fornitore configurato, propone da dove cominciare. */
internal fun MainActivity.maybeWelcome() {
    if (welcomeChecked) return
    welcomeChecked = true
    if ((packageName.endsWith(".qa") && !intent.getBooleanExtra("qa_welcome", false)) || prefs.getBoolean("welcomed", false)) return
    prefs.edit().putBoolean("welcomed", true).apply()
    val cur = settings.provider
    if (cur.id == "dashboard" || settings.key(cur).isNotBlank() || nous.loggedIn) return
    sheet(UiText.text(this, R.string.ui_5e5f6a3ffb), listOf(
        UiText.text(this, R.string.ui_688aad2b45) to { push(providerScreen()) },
        UiText.text(this, R.string.ui_936217976e) to { if (!pinEnabled()) pinSetup() },
        UiText.text(this, R.string.ui_bc159b0503) to {}))
}

internal fun MainActivity.home(): Screen {
    val scope = Scope()
    window.decorView.postDelayed({ if (scope.alive) maybeWelcome() }, 1200)
    var stopHomeState: (() -> Unit)? = null
    var agents = visibleAgents()
    val stage = FrameLayout(this).apply { defaultFocusHighlightEnabled = false }
    val wheel = FrameLayout(this).apply { clipChildren = false; defaultFocusHighlightEnabled = false }
    val dots = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    val view = page(stage).also { it.background = bgDrawable("home"); it.tag = "home-screen" }
    val slides = HashMap<Int, HomeSlide>()
    var emptyNote: String? = null
    var sel = homeIndex.coerceIn(0, agents.size)
    fun n() = agents.size + 1
    fun roleOf(a: Agent) = a.prompt.lineSequence().firstOrNull().orEmpty().substringBefore(". ").take(40)
    fun focusAvatar() = slides[sel]?.avatar
    fun signature() = agents.joinToString("|") { "${it.id},${it.name},${it.form},${it.color},${it.finish},${it.eyes},${it.wear}" }
    var sig = signature()

    fun make(i: Int): HomeSlide {
        val a = agents.getOrNull(i)
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        val name = text(40f, 800).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = -0.02f; text = a?.name ?: UiText.text(this@home, R.string.ui_b1a1efd9f5); if (a != null) setTextColor(Theme.mix(Theme.FG, Theme.colorOf(a.color), 0.22f)); setShadowLayer(dp(10f), 0f, dp(2f), Theme.withAlpha(if (a != null) Theme.colorOf(a.color) else Theme.MUTED, 0.35f)) }
        val role = text(17f, 650, Theme.MUTED).apply {
            gravity = Gravity.CENTER; maxLines = if (a == null && emptyNote != null) 3 else 1; ellipsize = android.text.TextUtils.TruncateAt.END
            text = if (a != null) "" else emptyNote.orEmpty(); visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val size = dpi(250f)
        val body: View = if (a != null) CharacterView(this, size).also { it.show(a); it.tag = "home-agent-${a.id}" } else FrameLayout(this).apply {
            background = DashedRing(this@home); clipChildren = false
            addView(CharacterView(this@home, size).also { it.show(neutralBuddy); it.tag = "home-agent-${neutralBuddy.id}" }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(IconView(this@home, "plus", Theme.accent, Theme.ON_ACCENT), FrameLayout.LayoutParams(dpi(28f), dpi(28f), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dpi(18f), dpi(18f)) })
        }
        v.addView(name, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6f) })
        v.addView(role, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
        // Il cerchio "nuovo" sta dove sta il corpo di un personaggio (che riempie circa l'85% della sua scatola).
        val ring = (size * 0.85f).toInt(); val m = (size - ring) / 2
        v.addView(body, if (a != null) LinearLayout.LayoutParams(size, size) else LinearLayout.LayoutParams(ring, ring).apply { setMargins(m, m, m, m) })
        wheel.addView(v, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val character = (body as? CharacterView) ?: body.findViewWithTag<CharacterView>("home-agent-${neutralBuddy.id}")
        return HomeSlide(v, body, character).also { slides[i] = it; character?.setActive(i == sel) }
    }

    fun step(): Float = (slides.values.firstOrNull { it.v.height > 0 }?.v?.height?.toFloat() ?: dp(290f).toFloat()) * 0.70f
    /** Mette ogni pagina al suo posto per una posizione `p` (in agenti, con la virgola): la grande al centro, i vicini piccoli e sfumati sopra e sotto. */
    fun layout(p: Float) {
        val st = step()
        for (i in 0 until n()) {
            val d = i - p; val ad = Math.abs(d)
            if (ad > 2f) { slides[i]?.v?.visibility = View.GONE; continue }
            val s = slides[i] ?: make(i).also { it.v.post { if (it.v.isAttachedToWindow) { /* misurata: ricalcola */ } } }
            val k = Math.min(ad, 1f)
            val sc = 1f - 0.58f * k
            s.v.visibility = View.VISIBLE
            s.v.scaleX = sc; s.v.scaleY = sc
            s.v.alpha = if (ad <= 1f) 1f - 0.82f * k else (0.18f * (2f - ad)).coerceAtLeast(0f)
            s.v.translationY = d * st
        }
    }
    val pos = dev.r1os.host.ui.Spring { layout(it) }

    fun paintDots() {
        dots.removeAllViews()
        for (i in 0 until n()) {
            val a = agents.getOrNull(i); val on = i == sel
            dots.addView(View(this).apply { background = roundRect(if (on) (a?.let { Theme.colorOf(it.color) } ?: Theme.MUTED) else Theme.SUBTLE, dpi(4f).toFloat()) },
                LinearLayout.LayoutParams(dpi(if (on) 6f else 5f), dpi(if (on) 16f else 5f)).apply { topMargin = dpi(5f) })
        }
    }
    val pillText = text(18f, 700, Theme.accent).apply { gravity = Gravity.CENTER }
    fun onFocus(first: Boolean = false): Unit {
        Mascot.agent = agents.getOrNull(sel)
        companionAgent = agents.getOrNull(sel) ?: neutralBuddy
        slides.forEach { (i, s) -> s.avatar?.setActive(i == sel) }
        if (!first) { slides[sel]?.land(); focusAvatar()?.lookAt(0f, 8f, 600) }
        paintDots()
        pillText.text = if (agents.getOrNull(sel) != null) UiText.text(this@home, R.string.ui_1cbfd76bb1) else UiText.text(this@home, R.string.ui_9c6c16c4f7)
    }
    fun rebuild() {
        wheel.removeAllViews(); slides.clear()
        sel = homeIndex.coerceIn(0, agents.size); homeIndex = sel
        sig = signature()
        pos.snapTo(sel.toFloat())
        wheel.post { layout(pos.value) }
        onFocus(first = true)
    }
    var lastBump = 0L; var lastStep = 0L
    fun goTo(t: Int, fromWheel: Boolean = false) {
        val c = t.coerceIn(0, agents.size)
        val now = android.os.SystemClock.uptimeMillis()
        // Rotella: non piu' di un passo ogni 90 ms (la rotella manda raffiche), e oltre il capo un solo rimbalzo finche' continui a girare.
        if (fromWheel && c != sel && now - lastStep < 90) return
        if (c == sel && t != c && fromWheel) {
            val quiet = now - lastBump > 700
            lastBump = now
            if (!quiet) return
        }
        if (c != sel) lastStep = now
        if (c == sel) {
            if (t != c) { pos.shift(if (t > c) 0.14f else -0.14f); focusAvatar()?.let { it.lookAt(0f, if (t > c) 14f else -14f, 500); it.react("surprised") } }
            pos.animateTo(sel.toFloat(), 260f, 0.8f); return
        }
        sel = c; homeIndex = c; prefs.edit().putInt("home_index", c).apply(); stage.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        pos.animateTo(c.toFloat(), 260f, 0.82f)
        onFocus()
    }
    fun open() {
        val a = agents.getOrNull(sel)
        if (a == null) { push(editor(null)); return }
        // L'avatar a fuoco vola nell'intestazione della chat (vedi launchHero); se la chat non si apre entro 3 s il volo viene dimenticato.
        focusAvatar()?.let { v -> if (v.width > 0) { val l = IntArray(2); v.getLocationInWindow(l); hero = Hero(l[0] + v.width / 2f, l[1] + v.height / 2f, v.width, a); stage.postDelayed({ hero = null }, 8000); if (hermesMode()) v.let { (it as? CharacterView)?.show(a, "thinking") } } }
        openLatest(a)
    }
    fun menu() { companionAgent = agents.getOrNull(sel) ?: neutralBuddy; menuSheet(agents.getOrNull(sel)) }

    stage.addView(wheel, FrameLayout.LayoutParams(-1, -1))
    // ---- carosello orizzontale: scorrendo a destra/sinistra il buddy mostra la funzione con il suo oggetto in mano ----
    class Lane(val id: String, val title: String, val sub: String, val prop: String, val go: () -> Unit)
    val leftLanes = listOf(
        Lane("settings", UiText.text(this@home, R.string.ui_0c375bfee9), UiText.text(this@home, R.string.ui_6a107bf8d4), "tools") { push(settingsScreen()) },   // a sinistra: dal centro verso l'esterno
        Lane("apps", UiText.text(this@home, R.string.ui_7d1043473d), UiText.text(this@home, R.string.ui_be6f615b92), "apps") { push(appsScreen()) },
    )
    val rightLanes = listOfNotNull(
        Lane("camera", UiText.text(this@home, R.string.ui_10315f3cb1), UiText.text(this@home, R.string.ui_aaa045009b), "camera") { sheet(UiText.text(this@home, R.string.ui_10315f3cb1), listOf(UiText.text(this@home, R.string.ui_57289acd6d) to { pickPhoto(16) }, UiText.text(this@home, R.string.ui_b1a6079206) to { launchCamera() })) },
        Lane("tools", UiText.text(this@home, R.string.ui_6bceb5c35b), UiText.text(this@home, R.string.ui_fa1f94b99f), "stopwatch") { push(tools()) },
        Lane("translate", UiText.text(this@home, R.string.ui_1a8a49ab36), UiText.text(this@home, R.string.ui_9383d2036e), "globe") { push(translator()) },
        Lane("controls", UiText.text(this@home, R.string.ui_4d538300d5), UiText.text(this@home, R.string.ui_3dd6153b9c), "controls") { push(controlsScreen()) },
        Lane("notes", UiText.text(this@home, R.string.ui_c51048b732), UiText.text(this@home, R.string.ui_32be769c09), "notes") { push(notesScreen()) },
        if (hermesMode()) Lane("skills", UiText.text(this@home, R.string.ui_d2d5097861), UiText.text(this@home, R.string.ui_2d73049f78), "skills") { agents.getOrNull(sel)?.let { push(skillsScreen(it)) } } else null,
        if (hermesMode()) Lane("jobs", UiText.text(this@home, R.string.ui_e8250ed32d), UiText.text(this@home, R.string.ui_2515690daf), "jobs") { push(routinesScreen()) } else null,
    )
    var lanePos = 0
    val laneCaption = text(14f, 700, Theme.MUTED).apply { gravity = Gravity.CENTER; letterSpacing = 0.08f }
    val laneLayer = FrameLayout(this).apply { visibility = View.INVISIBLE; defaultFocusHighlightEnabled = false }
    stage.addView(laneLayer, FrameLayout.LayoutParams(-1, -1))
    val laneDots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    stage.addView(laneDots, FrameLayout.LayoutParams(-2, dpi(16f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(2f) })
    stage.addView(laneCaption, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(18f) })
    fun laneAt(pos: Int): Lane? = if (pos < 0) leftLanes.getOrNull(-pos - 1) else if (pos > 0) rightLanes.getOrNull(pos - 1) else null
    fun paintLaneDots() {
        laneDots.removeAllViews()
        for (i in -leftLanes.size..rightLanes.size) laneDots.addView(View(this).apply { background = roundRect(if (i == lanePos) Theme.accent else Theme.SUBTLE, dp(4f)) }, LinearLayout.LayoutParams(if (i == lanePos) dpi(16f) else dpi(7f), dpi(7f)).apply { marginStart = dpi(3f); marginEnd = dpi(3f) })
    }
    // La colonna del buddy si chiama «agenti»; le altre portano il nome della funzione. I puntini di rotazione degli agenti (a destra) esistono solo lì.
    // Dentro una funzione il titolo grande dice gia' il nome: la didascalia lo ripeterebbe (due "fotocamera" uno sull'altro).
    fun paintLaneCaption() { laneCaption.text = if (laneAt(lanePos) != null) "" else UiText.text(this@home, R.string.ui_a27ab2d196).lowercase() }
    paintLaneDots(); paintLaneCaption()
    var laneView: View? = null
    var laneFlight: BuddyFlight? = null
    fun buildLane(l: Lane): View {
        val a = agents.getOrNull(sel) ?: neutralBuddy
        val size = dpi(250f)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        col.addView(text(40f, 800).apply { gravity = Gravity.CENTER; maxLines = 1; letterSpacing = -0.02f; text = l.title }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6f) })
        val subV = text(17f, 650, Theme.MUTED).keepCase().apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; text = l.sub }
        col.addView(subV, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
        if (l.id == "controls") subV.text = batteryLine().ifBlank { l.sub }
        if (l.id == "skills") agents.getOrNull(sel)?.takeIf { it.profile.isNotEmpty() }?.let { ag -> Io.run { // quante competenze ha attive l'agente su Hermes
            val n = runCatching { dash.skills(ag.profile) }.getOrNull()?.count { it.enabled }
            if (n != null) runOnUiThread { if (col.isAttachedToWindow) subV.text = UiText.text(this@home, R.string.ui_3b73f5eb84, n) }
        } }
        if (l.id == "jobs") Io.run { // dal vivo da Hermes: la prossima attività pianificata
            val next = runCatching { dash.cronJobs() }.getOrNull()?.filter { it.enabled && it.nextRun > 0 }?.minByOrNull { it.nextRun }
            if (next != null) runOnUiThread { if (col.isAttachedToWindow) subV.text = next.name + " · " + relativeIn(next.nextRun - System.currentTimeMillis()) }
        }
        val body = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val buddy = CharacterView(this, size).also { it.show(a); it.tag = "lane-buddy" }
        val prop = PropView(this, l.prop)
        body.addView(buddy, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        body.addView(prop, FrameLayout.LayoutParams(dpi(104f), dpi(104f), Gravity.BOTTOM or Gravity.END).apply { marginEnd = dpi(6f); bottomMargin = dpi(14f) })
        col.addView(body, LinearLayout.LayoutParams(size, size))
        col.tag = Triple(buddy, prop, l)
        if (!Motion.reduced(this)) { // l'oggetto entra con un'oscillazione, il buddy lo guarda e saltella
            prop.rotation = -38f; prop.scaleX = .7f; prop.scaleY = .7f
            prop.animate().rotation(-8f).scaleX(1f).scaleY(1f).setStartDelay(Motion.BASE).setDuration(Motion.SLOW).setInterpolator(Theme.POP).start()
            buddy.postDelayed({ if (buddy.isAttachedToWindow) { buddy.lookAt(16f, 14f, hold = Motion.SLOW * 2); buddy.react("bounce") } }, Motion.BASE)
        }
        return col
    }
    // Rete di sicurezza: a riposo, nel livello delle funzioni c'e' al piu' la colonna corrente; le altre sono residui di gesti interrotti.
    var laneStale = 0
    val laneJanitor = object : Runnable {
        override fun run() {
            if (!scope.alive) return
            val moving = laneFlight?.isActive == true || laneKick != null
            val keep = if (lanePos == 0) null else laneView
            var extra = 0
            for (i in 0 until laneLayer.childCount) if (laneLayer.getChildAt(i) !== keep) extra++
            if (moving || extra == 0) laneStale = 0
            else if (++laneStale >= 2) {
                laneStale = 0
                for (i in laneLayer.childCount - 1 downTo 0) { val c = laneLayer.getChildAt(i); if (c !== keep) { c.animate().cancel(); laneLayer.removeView(c) } }
            }
            laneLayer.postDelayed(this, 1000L)
        }
    }
    laneLayer.postDelayed(laneJanitor, 1500L)
    fun laneGo(target: Int, instant: Boolean = false) {
        val t = target.coerceIn(-leftLanes.size, rightLanes.size)
        if (t == lanePos && !instant) return
        val dir = if (t > lanePos) 1 else -1
        val outgoing: View = if (lanePos == 0) wheel else (laneView ?: wheel)
        // Colonne rimaste da gesti precedenti interrotti (animate().cancel() non esegue la rimozione a fine corsa): con scatti rapidi
        // restavano nel livello con il loro buddy, sovrapposte a quello giusto. Qui si tiene solo quella che sta uscendo.
        for (i in laneLayer.childCount - 1 downTo 0) { val c = laneLayer.getChildAt(i); if (c !== outgoing) { c.animate().cancel(); laneLayer.removeView(c) } }
        lanePos = t
        paintLaneDots(); paintLaneCaption()
        dots.animate().cancel(); dots.animate().alpha(if (t == 0) 1f else 0f).setDuration(Motion.BASE).start()
        val l = laneAt(t)
        val incoming: View = if (l == null) wheel else buildLane(l).also { laneLayer.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) }
        val w = stage.width.coerceAtLeast(1).toFloat()
        laneLayer.visibility = View.VISIBLE
        if (instant || Motion.reduced(this)) {
            outgoing.alpha = 0f; if (outgoing !== wheel) laneLayer.removeView(outgoing) else wheel.visibility = View.INVISIBLE
            incoming.visibility = View.VISIBLE; incoming.alpha = 1f; incoming.translationX = 0f
            laneView = if (l == null) null else incoming; if (l == null) laneLayer.visibility = View.INVISIBLE
            return
        }
        val ot = outgoing.tag as? Triple<*, *, *>; val nt = incoming.tag as? Triple<*, *, *>
        if (outgoing !== wheel && incoming !== wheel && ot != null && nt != null) {
            // Da una pagina all'altra il buddy resta lì: cambia l'attrezzatura. L'oggetto vecchio esce ruotando, quello nuovo entra, i titoli scivolano.
            val ob = ot.first as CharacterView; val oldProp = ot.second as PropView; val nb = nt.first as CharacterView; val newProp = nt.second as PropView
            val oc = outgoing as LinearLayout; val ic = incoming as LinearLayout
            cancelLaneKick(); laneFlight?.dispose(); laneFlight = null
            val nbParent = nb.parent as ViewGroup; val lp = nb.layoutParams
            (ob.parent as ViewGroup).removeView(ob); nbParent.removeView(nb); nbParent.addView(ob, 0, lp)   // lo stesso buddy passa alla nuova colonna: nessuna dissolvenza
            incoming.tag = Triple(ob, newProp, l)
            incoming.visibility = View.VISIBLE; incoming.alpha = 1f; incoming.translationX = 0f
            val shift = dpi(36f).toFloat() * dir
            for (i in 0..1) {
                val inText = ic.getChildAt(i); inText.translationX = shift; inText.alpha = 0f
                inText.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
                oc.getChildAt(i).animate().translationX(-shift).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).start()
            }
            newProp.alpha = 0f; newProp.animate().alpha(1f).setStartDelay(Motion.PRESS).setDuration(Motion.BASE).start()
            oldProp.animate().cancel(); oldProp.animate().rotation(-dir * 60f).scaleX(.5f).scaleY(.5f).translationX(-shift).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction { laneLayer.removeView(outgoing) }.start()
            ob.lookAt(dir * 18f, 10f, hold = Motion.SLOW * 2)
            laneView = incoming
            return
        }
        // Dalla rotella (agenti) a una pagina e ritorno: il buddy vola da una posizione all'altra (un solo buddy in scena), la rotella si dissolve sul posto e titoli e oggetto scivolano.
        val wheelBuddy = focusAvatar()
        val laneCol = if (outgoing === wheel) incoming else outgoing
        val laneTri = laneCol.tag as? Triple<*, *, *>
        if (wheelBuddy != null && laneTri != null && laneCol !== wheel && (outgoing === wheel) != (incoming === wheel)) {
            val toLane = outgoing === wheel
            val laneBuddy = laneTri.first as CharacterView; val laneProp = laneTri.second as PropView
            val lc = laneCol as LinearLayout; val shift = dpi(36f).toFloat() * dir
            cancelLaneKick(); laneFlight?.dispose(); laneFlight = null
            wheel.animate().cancel(); wheel.translationX = 0f
            if (toLane) {
                incoming.visibility = View.VISIBLE; incoming.alpha = 1f; incoming.translationX = 0f
                for (i in 0..1) { val t = lc.getChildAt(i); t.translationX = shift; t.alpha = 0f; t.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start() }
                wheel.animate().alpha(0f).setDuration(Motion.FAST).setInterpolator(Theme.EASE_OUT).withEndAction { if (lanePos != 0) wheel.visibility = View.INVISIBLE }.start()
            } else {
                wheel.visibility = View.VISIBLE; wheel.alpha = 0f; wheel.animate().alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
                for (i in 0..1) lc.getChildAt(i).animate().translationX(-shift).alpha(0f).setDuration(Motion.FAST).setInterpolator(Theme.EASE_OUT).start()
                laneProp.animate().cancel(); laneProp.animate().rotation(-dir * 60f).scaleX(.5f).scaleY(.5f).translationX(-shift).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction { laneLayer.removeView(laneCol) }.start()
            }
            // Il buddy: un clone vola dalla posizione della rotella a quella della pagina (o al contrario) dopo il primo layout.
            laneBuddy.alpha = 0f; if (!toLane) wheelBuddy.alpha = 1f
            val kickoff = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    laneCol.viewTreeObserver.removeOnPreDrawListener(this)
                    if (laneKick === this) { laneKick = null; laneKickHost = null }
                    if (!laneCol.isAttachedToWindow || wheelBuddy.width <= 0 || laneBuddy.width <= 0) { laneBuddy.alpha = 1f; return true }
                    val f = BuddyFlight(root, wheel, laneCol, wheelBuddy, laneBuddy, if (toLane) 0f else 1f, fadePages = false) { c, _ -> c.dispose(restoreSource = true); if (laneFlight === c) laneFlight = null }
                    laneFlight = f; f.animateTo(if (toLane) 1f else 0f)
                    return true
                }
            }
            laneKick = kickoff; laneKickHost = laneCol
            laneCol.viewTreeObserver.addOnPreDrawListener(kickoff)
            laneView = if (l == null) null else incoming
            if (l == null) laneLayer.postDelayed({ if (lanePos == 0) laneLayer.visibility = View.INVISIBLE }, Motion.SLOW + 60L)
            return
        }
        incoming.visibility = View.VISIBLE; incoming.translationX = dir * w * 0.4f; incoming.alpha = 0f
        incoming.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
        outgoing.animate().translationX(-dir * w * 0.4f).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction {
            if (outgoing === wheel) { if (lanePos != 0) wheel.visibility = View.INVISIBLE } else laneLayer.removeView(outgoing)
            outgoing.translationX = 0f
        }.start()
        if (incoming === wheel) { wheel.visibility = View.VISIBLE; wheel.alpha = 0f; wheel.animate().alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start() }
        laneView = if (l == null) null else incoming
        if (l == null) laneLayer.postDelayed({ if (lanePos == 0) laneLayer.visibility = View.INVISIBLE }, Motion.SLOW + 60L)
    }
    fun laneStep(d: Int) = laneGo(lanePos + d)
    homeLaneBack = { if (scope.alive && lanePos != 0) { laneGo(0); true } else false }
    /** Tocco sulla funzione: l'oggetto fa il suo gesto, poi si apre. */
    fun laneAction() {
        val l = laneAt(lanePos) ?: return
        (laneView?.tag as? Triple<*, *, *>)?.let { (it.first as? CharacterView)?.react("bounce"); (it.second as? PropView)?.act() }
        laneLayer.postDelayed({ l.go() }, if (Motion.reduced(this)) 0L else Motion.BASE)
    }
    stage.addView(dots, FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL))
    // In basso un solo gesto: tieni premuto e parla all'agente a fuoco (o, sul cerchio, creane uno).
    val pill = LinearLayout(this).apply {
        gravity = Gravity.CENTER; setPadding(dpi(22f), dpi(12f), dpi(22f), dpi(12f)); background = roundRect(Theme.FOCUS, dp(26f)); isClickable = true
        addView(IconView(this@home, "mic", Theme.accent, Theme.ON_ACCENT), LinearLayout.LayoutParams(dpi(22f), dpi(22f)).apply { marginEnd = dpi(8f) })
        addView(pillText)
    }
    var talking = false
    var talkStartY = 0f
    var talkCancelled = false
    // Con TalkBack o la tastiera non si puo' "tenere premuto": un clic avvia l'ascolto senza tenere, un altro lo ferma e invia (come la doppia pressione del tasto laterale).
    pill.setOnClickListener { if (pttLatched) { pttLatched = false; pttStop() } else { pttLatched = true; pttStart() } }
    pill.setOnTouchListener { v, e ->
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> { talkStartY = e.rawY; talkCancelled = false; focusAvatar()?.lookAt(0f, 20f, -1); v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(Motion.PRESS).start(); if (agents.getOrNull(sel) != null) { talking = true; homeVoiceGesture = true; homeVoiceStartY = e.rawY; pttStart() } }
            android.view.MotionEvent.ACTION_UP -> { focusAvatar()?.lookBack(); v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Theme.EASE_OUT).start(); if (talking) { talking = false; pttStop() } else if (!talkCancelled) open() }
            android.view.MotionEvent.ACTION_MOVE -> if (talking && talkStartY - e.rawY > dp(64f)) { talking = false; talkCancelled = true; pttClose(); v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.BASE).start(); snack(UiText.text(this@home, R.string.ui_4435ce3621)) }
            android.view.MotionEvent.ACTION_CANCEL -> { v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.BASE).start(); talking = false; talkCancelled = true; if (!homeVoiceGesture && !drainingHomeVoiceTouch) pttClose() }
        }
        true
    }
    stage.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(6f) })
    stage.addView(iconButton("more", Theme.accent, filled = false, size = 48f) { menu() }, FrameLayout.LayoutParams(dpi(48f), dpi(48f), Gravity.TOP or Gravity.END))

    // Dito: il carosello segue il dito (con un po' di resistenza ai capi) e al rilascio si ferma sull'agente piu' vicino, o va avanti di uno con uno scatto.
    // Rotella (r1-wheel-touch): un agente ogni ~36 dp. Tocco sul grande = apre la chat; su un vicino = lo porta al centro. Pressione lunga = spazio.
    var startPos = 0f; var startSel = 0; var total = 0f; var dragging = false; var flung = false; var isWheel = false; var acc = 0f; var moved = false; var petted = false
    fun indexAt(y: Float): Int = Math.round((y - stage.height / 2f) / step())
    val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent): Boolean {
            acc = 0f; moved = false; total = 0f; dragging = false; flung = false; petted = false
            startPos = pos.value; startSel = sel
            isWheel = e.device?.name?.contains("wheel", ignoreCase = true) == true
            return true
        }
        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
            if (isWheel) {
                acc += r1WheelDelta(dx, dy, stage.display?.rotation ?: 0)
                if (Math.abs(acc) >= dpi(36f)) { if (lanePos != 0) laneStep(if (acc > 0) 1 else -1) else goTo(sel + if (acc > 0) 1 else -1, fromWheel = true); acc = 0f; moved = true }
            } else if (lanePos != 0 || Math.abs(e2.x - (e1?.x ?: e2.x)) > Math.abs(e2.y - (e1?.y ?: e2.y)) * 1.4f && Math.abs(e2.x - (e1?.x ?: e2.x)) > dp(24f)) {
                // gesto orizzontale o dentro una funzione: lo gestisce il fling, il carosello degli agenti resta fermo
            } else {
                total = e2.y - (e1?.y ?: e2.y); dragging = true
                var p = startPos - total / (step() * 0.8f)
                val top = (n() - 1).toFloat()
                if (p < 0f) p *= 0.35f else if (p > top) p = top + (p - top) * 0.35f
                pos.snapTo(p)
                val ax = e2.x - (e1?.x ?: e2.x)
                focusAvatar()?.lookAt((ax / dp(40f)).coerceIn(-1f, 1f) * 8f, (total / dp(40f)).coerceIn(-1f, 1f) * 10f, -1, 90)
                if (!petted && Math.abs(ax) > dp(90f) && Math.abs(total) < dp(40f)) { petted = true; focusAvatar()?.react("love") }
            }
            return true
        }
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { if (lanePos != 0) { laneAction(); return true }; val d = indexAt(e.y); if (d == 0) open() else goTo(sel + d); return true }
        override fun onLongPress(e: android.view.MotionEvent) { if (lanePos == 0 && indexAt(e.y) == 0) agents.getOrNull(sel)?.let { push(space(it)) } }
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
            if (isWheel || moved) return true
            if (Math.abs(vx) > 700f && Math.abs(vx) > Math.abs(vy) * 1.4f) { flung = true; laneStep(if (vx < 0) 1 else -1); return true }   // scorri a destra/sinistra: le altre funzioni
            if (lanePos != 0 && Math.abs(vy) > 600f && Math.abs(vy) > Math.abs(vx)) { flung = true; laneGo(0); return true }                 // su/giù dentro una funzione: torna all'agente
            if (Math.abs(vy) < 600f || Math.abs(vy) < Math.abs(vx)) return false
            flung = true; goTo(startSel + if (vy < 0) 1 else -1); return true
        }
    })
    stage.isClickable = true
    val sleeper = Sleeper(stage, { focusAvatar() }, { agents.getOrNull(sel)?.let { a -> focusAvatar()?.show(a) } }, { agents.getOrNull(sel)?.let { a -> focusAvatar()?.show(a, "sleeping") } }, afterProvider = ::idleMs)
    stage.post { sleeper.poke() }
    stage.setOnTouchListener { _, e ->
        sleeper.poke()
        val r = gd.onTouchEvent(e)
        if ((e.action == android.view.MotionEvent.ACTION_UP || e.action == android.view.MotionEvent.ACTION_CANCEL) && dragging) {
            dragging = false; focusAvatar()?.lookBack()
            if (!flung) { val d = pos.value - startSel; goTo(startSel + if (Math.abs(d) > 0.3f) (if (d > 0) 1 else -1) else 0) }
        }
        r
    }
    // Rotelle e mouse che mandano eventi di scorrimento (AXIS_VSCROLL / AXIS_HSCROLL).
    stage.setOnGenericMotionListener { _, e ->
        if (e.actionMasked == android.view.MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL).let { if (it != 0f) -it else -e.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL) }
            if (v != 0f) { if (lanePos != 0) laneStep(if (v > 0) 1 else -1) else goTo(sel + if (v > 0) 1 else -1, fromWheel = true); true } else false
        } else false
    }
    rebuild()
    stage.post { layout(pos.value); stage.post { layout(pos.value) } }
    // Hermes dal vivo: chi sta lavorando o ti aspetta lo dice sotto il nome e assume la posa giusta.
    val liveStates = HashMap<Long, String>()
    fun refreshLive() {
        if (!hermesMode() || !scope.alive) return
        Io.run {
            val live = dash.live()
            val states = HashMap<Long, String>()
            for (a in agents) for (c in db.chats(a.id)) { val st = live[db.hsid(c.id)]; if (st == "working" || (st == "waiting" && states[a.id] != "working")) states[a.id] = st!! }
            runOnUiThread {
                if (!scope.alive) return@runOnUiThread
                slides.forEach { (i, sl) ->
                    val a = agents.getOrNull(i) ?: return@forEach
                    val now = states[a.id]; val was = liveStates[a.id]
                    if (now == was) return@forEach
                    val roleV = sl.v.getChildAt(1) as? TextView
                    when (now) {
                        "working" -> { roleV?.text = UiText.text(this@home, R.string.ui_9449f5bbc8); roleV?.visibility = View.VISIBLE; sl.avatar?.setScenePose("working", "search") }
                        "waiting" -> { roleV?.text = UiText.text(this@home, R.string.ui_70ac6d7132); roleV?.visibility = View.VISIBLE; sl.avatar?.setScenePose("waiting"); if (!Motion.reduced(this@home)) sl.avatar?.react("surprised") }
                        else -> { roleV?.text = ""; roleV?.visibility = View.GONE; sl.avatar?.setScenePose("idle") }
                    }
                }
                liveStates.clear(); liveStates.putAll(states)
            }
        }
    }
    val liveLoop = object : Runnable { override fun run() { if (!scope.alive) return; if (visible && stack.lastOrNull()?.scope === scope) refreshLive(); stage.postDelayed(this, 15_000L) } }
    stage.postDelayed(liveLoop, 1500L)
    if (hermesMode()) {
        // Pallino di stato del collegamento con Hermes (verde pronto, giallo in collegamento, rosso errore, grigio spento).
        val dot = View(this)
        fun paintDot() {
            dot.background = roundRect(when (dash.state) { HermesDash.State.READY -> Theme.OK; HermesDash.State.CONNECTING -> Theme.colorOf("amber"); HermesDash.State.ERROR -> Theme.BAD; else -> Theme.SUBTLE }, dp(6f))
            (dot.tag as? android.animation.ObjectAnimator)?.cancel(); dot.alpha = 1f
            if (dash.state == HermesDash.State.CONNECTING) dot.tag = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply { duration = Motion.PULSE; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start() }
        }
        paintDot(); stopHomeState = dash.watchState { runOnUiThread { if (scope.alive) paintDot() } }
        // La pulsazione "in collegamento" è infinita: si ferma quando la home è coperta da un'altra pagina o chiusa, e riparte al ritorno.
        dot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (scope.alive) paintDot() }
            override fun onViewDetachedFromWindow(v: View) { (dot.tag as? android.animation.Animator)?.cancel() }
        })
        // Il pallino e' piccolo: sta in una zona da 40 dp che, toccata, dice a parole lo stato del collegamento.
        val dotBox = FrameLayout(this).apply {
            contentDescription = UiText.text(this@home, R.string.ui_62db8301e1)
            addView(dot, FrameLayout.LayoutParams(dpi(12f), dpi(12f), Gravity.CENTER))
            setOnClickListener { if (dash.state == HermesDash.State.ERROR || dash.state == HermesDash.State.OFF) sheet(UiText.text(this@home, R.string.ui_4814f6f7f6), listOf(UiText.text(this@home, R.string.ui_6ed04a5eb7) to { Io.run { runCatching { dash.profiles() } } }, UiText.text(this@home, R.string.ui_64ac619989) to { push(providerScreen()) })) else snack(UiText.text(this@home, when (dash.state) { HermesDash.State.READY -> R.string.ui_f24ee25561; else -> R.string.ui_7c1cae1c78 })) }
        }
        stage.addView(dotBox, FrameLayout.LayoutParams(dpi(48f), dpi(48f), Gravity.TOP or Gravity.END).apply { rightMargin = dpi(52f) })
    }
    // Prima volta con piu' di un agente: un suggerimento una volta sola.
    if (agents.size > 1 && !prefs.getBoolean("hint_home_seen", false)) stage.postDelayed({ if (scope.alive && stack.lastOrNull()?.scope === scope) { prefs.edit().putBoolean("hint_home_seen", true).apply(); snack(UiText.text(this@home, R.string.ui_d3aff5ed26), long = true) } }, 1800)
    // Una volta sola, anche con un agente solo: il carosello laterale e il tocco prolungato non si scoprono da soli.
    if (agents.isNotEmpty() && !packageName.endsWith(".qa") && prefs.getBoolean("hint_home_seen", false) && !prefs.getBoolean("hint_lane_seen", false)) stage.postDelayed({ if (scope.alive && stack.lastOrNull()?.scope === scope) { prefs.edit().putBoolean("hint_lane_seen", true).apply(); snack(UiText.text(this@home, R.string.ui_570d5b3ff8), long = true) } }, 2600L)
    val now = System.currentTimeMillis()
    if (hermesMode() && (agents.isEmpty() || now - lastProfileSync > 60_000L)) { lastProfileSync = now; syncProfiles(scope,
        { err -> if (agents.isEmpty()) { emptyNote = err; rebuild() } },
        { agents = visibleAgents(); emptyNote = null; rebuild() }) }
    var centerLong = false
    return Screen(view, { e ->
        // Dentro una funzione: click della rotella = esegui; tenuto premuto = torna all'agente (come il fling verticale col dito).
        if (lanePos != 0 && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (e.action == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) centerLong = false else if (!centerLong) { centerLong = true; laneGo(0) } }
            else if (e.action == KeyEvent.ACTION_UP && !centerLong) laneAction()
            true
        } else
        if (e.action != KeyEvent.ACTION_DOWN) false else
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { laneStep(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { laneStep(1); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_UP, 260 -> { if (lanePos != 0) laneStep(-1) else goTo(sel - 1, fromWheel = true); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 261 -> { if (lanePos != 0) laneStep(1) else goTo(sel + 1, fromWheel = true); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (lanePos != 0) laneAction() else open(); true }
            else -> false
        }
    }, buddyContext = BuddySceneContext.LIVE, scope = scope, isHome = true, onLeave = { stopHomeState?.invoke(); sleeper.stop(); homeLaneBack = null }, onShow = { laneLayer.alpha = 1f; refreshLive(); sleeper.poke(); agents = visibleAgents(); if (signature() != sig || homeIndex != sel) rebuild() else agents.getOrNull(sel)?.let { focusAvatar()?.show(it) } })
}
