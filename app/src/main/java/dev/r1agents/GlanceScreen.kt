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

// ---- glance: l'orologio (da Glance di r1os, in versione semplice) ---------------------------

/** `standby`: l'orologio di riposo (Standby.kt), messo sopra la pagina in cui eri; toccandolo si torna lì, dopo lo sblocco di Android. */
internal fun MainActivity.glance(): Screen = glanceScreen(false)   // nome e firma usati dai test per riflessione

internal fun MainActivity.glanceScreen(standby: Boolean): Screen {
    val agent = visibleAgents().getOrNull(homeIndex.coerceAtLeast(0)) ?: neutralBuddy
    val frame = FrameLayout(this)
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(6f), dpi(12f), 0, 0) }
    val center = prefs.getString("clock_align", "left") == "center"
    val clock = dev.r1os.host.shell.Clocks.face(this, prefs.getString("clock", "bold") ?: "bold")
    val date = text(19f, 650, Theme.MUTED).apply { if (center) gravity = Gravity.CENTER }
    val status = text(15f, 600, Theme.MUTED).apply { fontFeatureSettings = "tnum"; setPadding(0, dpi(6f), 0, 0) }
    val alarm = text(15f, 600, Theme.accent).apply { setPadding(0, dpi(4f), 0, 0) }
    if (center) { col.gravity = Gravity.CENTER_HORIZONTAL; status.gravity = Gravity.CENTER; alarm.gravity = Gravity.CENTER }
    val shown = prefs.getString("gshow", "date,status,alarm")!!.split(",")
    col.addView(clock)
    if ("date" in shown) col.addView(date, LinearLayout.LayoutParams(-1, -2))
    if ("status" in shown) col.addView(status, LinearLayout.LayoutParams(-1, -2))
    if ("alarm" in shown) col.addView(alarm, LinearLayout.LayoutParams(-1, -2))
    val widgets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(12f), dpi(8f), 0) }
    col.addView(widgets, LinearLayout.LayoutParams(-1, -2))
    // Dock: le app preferite (scelte nel cassetto con il tocco prolungato), sempre a portata sulla schermata iniziale.
    val dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(6f), dpi(8f), 0); if (center) gravity = Gravity.CENTER }
    col.addView(dock, LinearLayout.LayoutParams(-1, -2))
    fun dockUpdate() {
        val favs = favoriteApps()
        if (favs.isEmpty()) { dock.removeAllViews(); return }
        Io.run {
            val apps = (appCache ?: loadApps().also { appCache = it }).filter { it.pkg in favs }.take(5)
            runOnUiThread {
                dock.removeAllViews()
                apps.forEach { a -> dock.addView(android.widget.ImageView(this@glanceScreen).apply {
                    setImageDrawable(a.icon); contentDescription = a.label; isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
                    setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
                    setOnClickListener { unlockThen { launchApp(a) } }; setOnTouchListener { v, e -> Motion.touch(v, e); false }
                }, LinearLayout.LayoutParams(dpi(56f), dpi(56f)).apply { marginEnd = dpi(8f) }) }
                if (dock.childCount > 0) Motion.stagger(dock, dp(8f))
            }
        }
    }
    dockUpdate()
    // Senza scelta esplicita, con Hermes attivo lo standby mostra lo stato dal vivo di Hermes.
    val wset = prefs.getString("gw", null)?.split(",")?.filter { it.isNotEmpty() } ?: if (hermesMode()) listOf("hermes") else emptyList()
    fun widgetCard(t: String): TextView = text(16f, 600).keepCase().apply {
        text = t; setPadding(dpi(14f), dpi(9f), dpi(14f), dpi(9f)); background = roundRect(Theme.withAlpha(Theme.CARD, 0.85f), dp(16f))
        widgets.addView(this, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dpi(6f) })
    }
    frame.addView(col, FrameLayout.LayoutParams(-1, -2))
    val size = dpi(232f)
    val peek = if (agent != null) CharacterView(this, size).also { it.show(agent); it.tag = "scene-buddy" } else null
    // L'agente sbircia da sotto con una molla; al tocco sale al centro e si rimpicciolisce fino a dove sta nella home (morph glance -> home).
    var morphing = false
    if (peek != null) {
        peek.translationY = size * 0.32f
        frame.addView(peek, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }
    val sleeper = peek?.let { ch -> Sleeper(frame, { ch }, { agent?.let { ch.show(it) } }, { agent?.let { ch.show(it, "sleeping") } }, afterProvider = ::idleMs) }
    frame.post { sleeper?.poke() }
    // Standby vivo: finché non dorme si guarda intorno, e ogni tanto si alza un poco per vedere meglio.
    if (peek != null) {
        BuddyCuriosity.start(peek, 4f) { sleeper?.asleep != true && !morphing }
        val lifter = object : Runnable { override fun run() {
            if (!peek.isAttachedToWindow) return
            if (sleeper?.asleep != true && !morphing && !Motion.reduced(this@glanceScreen)) {
                peek.animate().translationY(size * 0.22f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withEndAction { peek.animate().translationY(size * 0.32f).setStartDelay(1400L).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start() }.start()
            }
            peek.postDelayed(this, 9000L + (Math.random() * 7000).toLong())
        } }
        peek.postDelayed(lifter, 4000L)
    }
    frame.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) sleeper?.poke(); false }
    lateinit var self: Screen
    fun toHome() {
        // Sopra il blocco (standby): prima lo sblocco di Android; nessuna pagina dell'app si apre sopra il blocco.
        unlockThen {
            if (stack.lastOrNull() !== self) return@unlockThen
            standbyGone(self)
            if (standby) { if (stack.size > 1) pop() else push(home(), fade = true); return@unlockThen }
            sleeper?.stop()
            if (morphing) return@unlockThen
            morphing = true
            push(home(), fade = true)
        }
    }
    var prevCharging: Boolean? = null; var prevMinute = -1; var lowAlerted = false
    fun update() {
        val now = java.time.ZonedDateTime.now()
        date.text = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", AppLanguage.locale(this@glanceScreen)))
        val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.let { it.getIntExtra("level", -1) * 100 / maxOf(it.getIntExtra("scale", 100), 1) } ?: -1
        val charging = (b?.getIntExtra("plugged", 0) ?: 0) != 0
        if (peek != null && sleeper?.asleep != true && !morphing && !Motion.reduced(this@glanceScreen)) { // piccole reazioni, rare
            if (prevCharging == false && charging) peek.react("bounce")
            if (lvl in 0..14 && !charging && !lowAlerted) { lowAlerted = true; peek.react("shiver") }
            if (now.minute == 0 && prevMinute > 0) peek.react("nod")
        }
        prevCharging = charging; prevMinute = now.minute
        status.text = if (lvl >= 0) "$lvl%" + if (charging) UiText.text(this@glanceScreen, R.string.ui_c167ec74e0) else "" else ""
        val al = db.alarms().filter { it.on }.minByOrNull { Sched.next(it.hour, it.minute, it.days) }
        alarm.text = al?.let { UiText.text(this@glanceScreen, R.string.ui_c1f893519d, Sched.time(it.hour, it.minute)) }.orEmpty()
    }
    fun widgetsUpdate() {
        widgets.removeAllViews()
        if ("hermes" in wset && hermesMode()) {
            val card = widgetCard("hermes")
            Io.run { // dal vivo: chat in corso e prossima attività pianificata
                val running = dash.live().values.count { it == "working" || it == "waiting" }
                val next = runCatching { dash.cronJobs() }.getOrNull()?.filter { it.enabled && it.nextRun > 0 }?.minByOrNull { it.nextRun }
                val line = "hermes · " + (if (running > 0) UiText.text(this@glanceScreen, R.string.ui_c7c47efe18, running) else UiText.text(this@glanceScreen, R.string.ui_a6c333ad12)) + (next?.let { " · " + UiText.text(this@glanceScreen, R.string.ui_d54c848c40, next.name, relativeIn(next.nextRun - System.currentTimeMillis())) } ?: "")
                runOnUiThread { if (card.isAttachedToWindow) card.text = line }
            }
        }
        if ("timer" in wset) db.timers().firstOrNull { it.endAt > System.currentTimeMillis() }?.let { t -> val s = Countdown.seconds(t.endAt, System.currentTimeMillis()); widgetCard("timer ${s / 60}:%02d".format(s % 60)) }
        if ("routine" in wset) db.routines().filter { it.on }.minByOrNull { Sched.next(it.hour, it.minute, it.days) }?.let { widgetCard("${it.name} · ${Sched.time(it.hour, it.minute)}") }
        if ("nota" in wset) db.notes().firstOrNull()?.let { widgetCard(it.second.take(80)) }
        if ("meteo" in wset) {
            val city = prefs.getString("weather_city", "").orEmpty()
            if (city.isNotBlank()) {
                val cached = prefs.getString("w_text", "").orEmpty()
                if (cached.isNotEmpty() && prefs.getString("w_city", "") == city) widgetCard(cached)
                // al massimo una richiesta ogni 30 minuti, e solo con il glance in vista
                if (System.currentTimeMillis() - prefs.getLong("w_at", 0) > 30 * 60_000L || prefs.getString("w_city", "") != city) {
                    prefs.edit().putLong("w_at", System.currentTimeMillis()).putString("w_city", city).apply()
                    Io.run { runCatching { Weather.fetch(city, AppLanguage.selected(this@glanceScreen)) }.onSuccess { r -> prefs.edit().putString("w_text", r).apply(); runOnUiThread { if (frame.isAttachedToWindow) widgetsUpdate() } } }
                }
            }
        }
    }
    update(); widgetsUpdate()
    // Un solo tick al minuto, e solo con la schermata visibile (regola batteria di r1os).
    ViewTicker(frame, 60_000L, { visible && isCurrentSurface(frame) }) { update(); widgetsUpdate() }
    frame.setOnClickListener { toHome() }
    self = Screen(page(frame).also { it.background = bgDrawable("glance"); it.tag = "glance" }, { e -> if (e.action == KeyEvent.ACTION_DOWN && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) { toHome(); true } else false },
        buddyContext = BuddySceneContext.LIVE, onLeave = { sleeper?.stop(); standbyGone(self) }, onShow = { if (standbyScreen === self) setShowWhenLocked(true); morphing = false; peek?.translationY = size * .32f; col.alpha = 1f; col.translationY = 0f; sleeper?.poke(); update(); widgetsUpdate() }, onResume = { update(); widgetsUpdate() })
    return self
}
