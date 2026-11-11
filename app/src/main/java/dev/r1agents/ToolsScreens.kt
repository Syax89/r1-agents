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

internal fun MainActivity.translator(): Screen {
    val input = field(UiText.text(this@translator, R.string.ui_ccb44386f7), "", multi = true).apply { minLines = 2 }
    val result = text(22f, 500).keepCase().apply { text = trResult; setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)); background = roundRect(Theme.CARD, dp(22f)); visibility = if (trResult.isEmpty()) View.GONE else View.VISIBLE }
    val status = text(14f, 600, Theme.MUTED).keepCase()
    var running = false
    val translationScope = Scope()
    var translationEngine: Engine? = null
    fun target() = UiText.translationLanguage(this@translator, prefs.getString("tr_lang", "en").orEmpty())
    fun run(t: String) {
        if (running || t.isBlank()) return
        running = true; status.setTextColor(Theme.MUTED); status.text = UiText.text(this@translator, R.string.ui_2a5a02c2b8); result.text = ""; Motion.show(result); trResult = ""; cue(null, -14f, 10f, force = true)
        val turnEngine = localEngine(); translationEngine = turnEngine
        val language = target()
        Io.run {
            val sb = StringBuilder()
            try {
                val ag = Agent(-1, UiText.text(this@translator, R.string.ui_1a8a49ab36), "Sei un traduttore. Traduci il testo dell'utente in ${language}. Rispondi solo con la traduzione, senza commenti né virgolette.")
                val chat = db.newChat(-1)
                try { turnEngine.turn(ag, chat, t) { d -> sb.append(d); runOnUiThread { if (translationScope.alive) { result.text = sb.toString(); cue(null, -18f + 36f * ((sb.length % 40) / 40f), 12f) } } } } finally { db.deleteChat(chat); localTurns.remove(turnEngine) }
                runOnUiThread { if (translationScope.alive) { trResult = sb.toString().trim(); status.text = ""; cue("nod", force = true) } }
            } catch (e: Exception) { runOnUiThread { if (!translationScope.alive) return@runOnUiThread; cue("surprised", force = true); status.setTextColor(Theme.BAD); status.text = errorText(e); if (sb.isEmpty()) Motion.hide(result) } }
            runOnUiThread { running = false }
        }
    }
    var acceptsVoice = false
    val mic = newMic({ if (acceptsVoice && translationScope.alive) { status.setTextColor(Theme.MUTED); status.text = it } }, { t -> if (acceptsVoice && translationScope.alive) { acceptsVoice = false; input.setText(t); status.text = ""; run(t) } }, { err -> if (acceptsVoice && translationScope.alive) { acceptsVoice = false; status.setTextColor(Theme.BAD); status.text = err.orEmpty() } })
    val suspendVoice = { acceptsVoice = false; mic.cancel(); status.text = "" }
    return listPage(UiText.text(this@translator, R.string.ui_1a8a49ab36), onLeave = { translationScope.alive = false; translationEngine?.let { it.cancel(); localTurns.remove(it) }; suspendVoice(); tts?.stop() }, onSuspend = suspendVoice) { col ->
        val langCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(14f)) }
        col.addView(langCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        langCard.addView(label(UiText.text(this@translator, R.string.ui_7811d136c1)).apply { setPadding(dpi(2f), dpi(2f), 0, dpi(8f)) })
        langCard.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(LinearLayout(this@translator).apply {
            trLangs.forEach { l ->
                val on = l == target()
                addView(chipView(l).apply { chipPaint(this, on)
                    setOnClickListener { prefs.edit().putString("tr_lang", UiText.translationCodeAt(trLangs.indexOf(l))).apply(); stack.last().onShow?.invoke() } }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) })
            } }) })
        (input.parent as? ViewGroup)?.removeView(input); (result.parent as? ViewGroup)?.removeView(result); (status.parent as? ViewGroup)?.removeView(status)
        col.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
        val talk = button(UiText.text(this@translator, R.string.ui_1158299b45), toolAccent, filled = false) {}
        holdToTalk(talk, mic) { acceptsVoice = true; status.setTextColor(Theme.MUTED); status.text = UiText.text(this@translator, R.string.ui_77cfbcff1e) }
        col.addView(pairRow(button(UiText.text(this@translator, R.string.ui_111b8e65f9), toolAccent) { run(input.text.toString()) }, talk), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dpi(4f) })
        col.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        col.addView(LinearLayout(this).apply {
            addView(button(UiText.text(this@translator, R.string.ui_18d1967ba7), toolAccent, primary = false, small = true) { if (trResult.isNotEmpty()) speak(trResult) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(8f) })
            addView(button(UiText.text(this@translator, R.string.ui_ac53b3388a), toolAccent, primary = false, small = true) { if (trResult.isNotEmpty()) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("traduzione", trResult)); snack(UiText.text(this@translator, R.string.ui_2eba76fd11)) } }, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4f) })
    }
}

// ---- note -------------------------------------------------------------------------------------

internal fun MainActivity.notesScreen(): Screen {
    val input = field(UiText.text(this@notesScreen, R.string.ui_82392454fe), "", multi = true).apply { minLines = 2 }
    val status = text(14f, 600, Theme.MUTED).keepCase()
    val scope = Scope(); var acceptsVoice = false
    val mic = newMic({ if (acceptsVoice && scope.alive) { status.setTextColor(Theme.MUTED); status.text = it } }, { t -> if (acceptsVoice && scope.alive) { acceptsVoice = false; input.setText((input.text.toString() + " " + t).trim()); status.text = "" } }, { err -> if (acceptsVoice && scope.alive) { acceptsVoice = false; status.setTextColor(Theme.BAD); status.text = err.orEmpty() } })
    val suspendVoice = { acceptsVoice = false; mic.cancel(); status.text = "" }
    return listPage(UiText.text(this@notesScreen, R.string.ui_c51048b732), context = BuddySceneContext.MEMORY, onLeave = { scope.alive = false; suspendVoice() }, onSuspend = suspendVoice) { col ->
        (input.parent as? ViewGroup)?.removeView(input); (status.parent as? ViewGroup)?.removeView(status)
        col.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        val talk = button(UiText.text(this@notesScreen, R.string.ui_e4923b7b6f), toolAccent, filled = false) {}
        holdToTalk(talk, mic) { acceptsVoice = true; status.setTextColor(Theme.MUTED); status.text = UiText.text(this@notesScreen, R.string.ui_77cfbcff1e) }
        col.addView(pairRow(button(UiText.text(this@notesScreen, R.string.ui_6c15f34ba8), toolAccent) { val t = input.text.toString().trim(); if (t.isNotEmpty()) { db.addNote(t); cue("nod", 0f, 14f); input.setText(""); stack.last().onShow?.invoke() } }, talk), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dpi(4f) })
        val notes = db.notes()
        if (notes.isEmpty()) col.addView(emptyState(UiText.text(this@notesScreen, R.string.ui_e48edacfc6), UiText.text(this@notesScreen, R.string.ui_9630587e64)))
        notes.forEach { (id, t, ts) ->
            val card = LinearLayout(this).apply { tag = LIST_ITEM; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(10f), dpi(8f), dpi(10f)) ; isClickable = true; setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("nota", t)); it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); snack(UiText.text(this@notesScreen, R.string.ui_2eba76fd11)) }}
            card.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
                addView(text(17f, 500).keepCase().apply { text = t })
                addView(text(12f, 500, Theme.MUTED).apply { text = ago(ts) }) }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(button(UiText.text(this@notesScreen, R.string.ui_dcfbe248ac), Theme.BAD, small = true) { confirmTap("note$id") { Motion.dismiss(card) { cue(null, 0f, 8f); db.deleteNote(id); stack.last().onShow?.invoke() } } })
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        }
    }
}

/** Un valore da girare con − / + (ore, minuti): si chiude su se stesso ai bordi. Titolo, valore e tasti sono su colonne fisse, così più righe restano allineate. */
internal fun MainActivity.stepper(title: String, get: () -> Int, set: (Int) -> Unit, min: Int, max: Int, steps: List<Int>, fmt: (Int) -> String = { "%02d".format(it) }): View {
    val value = text(34f, 700).apply { text = fmt(get()); gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
    val btns = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    steps.forEach { s ->
        btns.addView(button(if (s > 0) "+$s" else "−${-s}", toolAccent, small = true) {
            val n = max - min + 1; val v = (((get() + s - min) % n) + n) % n + min; set(v)
            value.text = fmt(v); value.animate().cancel(); value.scaleX = 1.12f; value.scaleY = 1.12f; value.animate().scaleX(1f).scaleY(1f).setDuration(Motion.BASE).setInterpolator(Theme.EASE_OUT).start()
        }.apply { minWidth = dpi(50f) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(6f) })
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (title.isNotBlank()) addView(text(15f, 600, Theme.MUTED).apply { text = title }, LinearLayout.LayoutParams(dpi(64f), -2))
        addView(value, LinearLayout.LayoutParams(dpi(64f), -2)); addView(btns, LinearLayout.LayoutParams(0, -2, 1f))
    }
}

/** Ora e minuti in un'unica carta. */
internal fun MainActivity.timeCard(hour: () -> Int, setH: (Int) -> Unit, minute: () -> Int, setM: (Int) -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(6f))
    addView(Drum(this@timeCard, (0..23).map { "%02d".format(it) }, hour(), true) { cue(null, 0f, if (it % 2 == 0) 8f else -8f); setH(it) }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(text(34f, 700, Theme.MUTED).apply { text = ":"; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dpi(24f), -2))
    addView(Drum(this@timeCard, (0..59).map { "%02d".format(it) }, minute(), true) { cue(null, 0f, if (it % 2 == 0) 8f else -8f); setM(it) }, LinearLayout.LayoutParams(0, -2, 1f))
}

internal fun MainActivity.daysChips(get: () -> Int, set: (Int) -> Unit): View {
    val row = LinearLayout(this); val views = ArrayList<TextView>()
    fun paint() = views.forEachIndexed { i, v -> chipPaint(v, get() and (1 shl i) != 0) }
    AppLanguage.wrap(this@daysChips).resources.getStringArray(R.array.weekdays_short).toList().forEachIndexed { i, d ->
        val v = chipView(d, 14f).apply { setPadding(dpi(9f), dpi(13f), dpi(9f), dpi(13f)); setOnClickListener { set(get() xor (1 shl i)); paint() } }
        views.add(v); row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(5f) })
    }
    paint(); daysRepaint = { paint() }
    return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}

/** "3h 12min", "45min", "2g 4h": quanto manca a un istante (g = giorni, d in inglese). */
internal fun MainActivity.relativeIn(ms: Long): String {
    val total = (ms / 60000).coerceAtLeast(0); val d = total / 1440; val h = (total % 1440) / 60; val m = total % 60
    val dayUnit = if (AppLanguage.selected(this) == "en") "d" else "g"
    return if (d > 0) "$d$dayUnit ${h}h" else if (h > 0) "${h}h ${m}min" else "${m}min"
}

internal fun MainActivity.appsScreen(): Screen {
    var apps: List<AppEntry> = emptyList(); var loaded = false
    val input = field(UiText.text(this@appsScreen, R.string.ui_7a0971edfc), "")
    val agent = visibleAgents().getOrNull(homeIndex.coerceAtLeast(0)) ?: visibleAgents().firstOrNull()
    lateinit var fill: () -> Unit
    var fillToken = 0
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) { fill() }
        override fun afterTextChanged(t: android.text.Editable?) {}
    })
    return listPage(UiText.text(this@appsScreen, R.string.ui_7d1043473d), context = BuddySceneContext.CONFIGURE) { col ->
        companionRow(112f)?.let { col.addView(it, LinearLayout.LayoutParams(-1, dpi(112f))) }
        (input.parent as? ViewGroup)?.removeView(input)
        col.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder)
        fun appRow(a: AppEntry): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
            setPadding(dpi(16f), dpi(8f), dpi(14f), dpi(8f)); minimumHeight = dpi(60f)
            addView(android.widget.ImageView(this@appsScreen).apply { setImageDrawable(a.icon); contentDescription = a.label }, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
            val name = text(20f, 600).keepCase().apply { text = a.label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(14f) })
            // Dentro la scheda della sezione: a fuoco (rotella) la stessa pillola a gelatina delle altre liste.
            setOnFocusChangeListener { v, f -> name.setTextColor(if (f) Theme.ON_ACCENT else Theme.FG); (v.parent as? GroupCard)?.let { if (f) it.focusTo(v) else it.blur(v) } }
            setOnClickListener { launchApp(a) }
            setOnTouchListener { v, e -> Motion.touch(v, e); liquidTouch(v, e); false }
            setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val fav = a.pkg in favoriteApps()
                sheetOf(a.label, listOf(
                    SheetItem(UiText.text(this@appsScreen, R.string.ui_6835df38c6), icon = "chevron") { launchApp(a) },
                    SheetItem(if (fav) UiText.text(this@appsScreen, R.string.ui_d314ad0a4a) else UiText.text(this@appsScreen, R.string.ui_8e2d0510c8), icon = if (fav) "close" else "spark") {
                        val now = favoriteApps().toMutableSet(); if (!now.add(a.pkg)) now.remove(a.pkg)
                        prefs.edit().putString("fav_apps", now.joinToString(",")).apply(); fill()
                    },
                    SheetItem(UiText.text(this@appsScreen, R.string.ui_90bc562f40), icon = "info") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    SheetItem(UiText.text(this@appsScreen, R.string.ui_aa9d8eccb4), icon = "close", danger = true) { runCatching { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                )); true
            }
        }
        fill = {
            holder.removeAllViews()
            val q = input.text.toString().trim().lowercase()
            val favs = favoriteApps()
            val shown = if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) }
            // Ogni sezione (recenti, preferite, tutte) è un'unica scheda con divisori sottili, come le altre liste.
            var card: GroupCard? = null
            fun add(a: AppEntry) {
                val c = card ?: groupCard(70f).also { card = it; holder.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) }) }
                c.addView(appRow(a), LinearLayout.LayoutParams(-1, -2))
            }
            fun section(t: String) { card = null; holder.addView(label(t)) }
            val recents = if (q.isEmpty()) recentApps().filter { it !in favs }.mapNotNull { p -> apps.firstOrNull { it.pkg == p } } else emptyList()
            if (recents.isNotEmpty()) { section(UiText.text(this@appsScreen, R.string.ui_950486e052)); recents.forEach { add(it) } }
            if (q.isEmpty() && favs.isNotEmpty()) { section(UiText.text(this@appsScreen, R.string.ui_b207efeb7b)); apps.filter { it.pkg in favs }.forEach { add(it) }; section(UiText.text(this@appsScreen, R.string.ui_9fa3124899)) }
            // Le righe si creano a gruppi (la prima schermata subito, il resto un fotogramma dopo l'altro): con decine di app il costo
            // di creare tutte le view insieme faceva saltare i fotogrammi all'apertura e nelle prime scorse.
            val token = ++fillToken
            fun finish() {
                if (q.isNotEmpty() && agent != null) holder.addView(row(UiText.text(this@appsScreen, R.string.ui_c024fcc5f9, agent.name, input.text.toString().trim()), Theme.colorOf(agent.color), icon = "chat") {
                    val id = db.lastChat(agent.id) ?: db.newChat(agent.id); openChat(agent, id, say = input.text.toString().trim())
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
                if (loaded && shown.isEmpty() && q.isEmpty()) holder.addView(emptyState(UiText.text(this@appsScreen, R.string.ui_9ba5c1d281), ""))
            }
            fun more(from: Int) {
                if (token != fillToken || !holder.isAttachedToWindow) return
                val end = minOf(from + 12, shown.size)
                for (k in from until end) add(shown[k])
                if (end < shown.size) holder.post { more(end) } else finish()
            }
            val first = minOf(8, shown.size)
            for (k in 0 until first) add(shown[k])
            Motion.stagger(holder, dp(8f), max = 7)
            if (first < shown.size) holder.post { more(first) } else finish()
        }
        if (loaded) fill() else Io.run { val list = loadApps(); runOnUiThread { apps = list; loaded = true; if (holder.isAttachedToWindow) fill() } }
    }
}

// ---- controlli Android: stato del dispositivo e scorciatoie di sistema, più domande pronte per l'agente ----
internal fun MainActivity.batteryLine(): String {
    val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val lvl = b?.let { it.getIntExtra("level", -1) * 100 / maxOf(it.getIntExtra("scale", 100), 1) } ?: -1
    val charging = (b?.getIntExtra("plugged", 0) ?: 0) != 0
    return if (lvl < 0) "" else UiText.text(this@batteryLine, R.string.ui_6640d5daa3, lvl, if (charging) UiText.text(this@batteryLine, R.string.ui_74cd9507a1) else UiText.text(this@batteryLine, R.string.ui_82d34ed5bc))
}

internal fun MainActivity.freeSpace(): String {
    val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val gb = st.availableBytes / 1_073_741_824.0
    return "%.1f GB".format(gb)
}

internal fun MainActivity.controlsScreen(): Screen = listPage(UiText.text(this@controlsScreen, R.string.ui_4d538300d5), context = BuddySceneContext.CONFIGURE) { col ->
    fun system(icon: String, title: String, sub: String?, vararg actions: String, accent: Int = Theme.accent) = bigRow(icon, title, sub, accent) {
        for (a in actions) if (runCatching { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return@bigRow
        snack(UiText.text(this@controlsScreen, R.string.ui_f2c6915e4c))
    }
    companionRow(112f)?.let { col.addView(it, LinearLayout.LayoutParams(-1, dpi(112f))) }
    col.addView(intro(batteryLine() + " · " + UiText.text(this@controlsScreen, R.string.ui_ac647b8dc0, freeSpace())))
    if (DeviceActions.hasFlash) col.addView(bigRow("spark", UiText.text(this@controlsScreen, R.string.ui_4d897fa9ab), if (torchOn) UiText.text(this@controlsScreen, R.string.ui_a7623e2fdf) else UiText.text(this@controlsScreen, R.string.ui_533deb16e2), Theme.colorOf("amber")) {
        torchOn = !torchOn; if (!runDeviceAction(DeviceActions.Action("torcia", if (torchOn) "on" else "off"), log = false)) torchOn = !torchOn
        cue(if (torchOn) "surprised" else "relief", force = true); stack.lastOrNull()?.onShow?.invoke()
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    for ((icon, t, acts) in listOf(
        Triple("globe", UiText.text(this@controlsScreen, R.string.ui_cc908be0bc), arrayOf(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY, android.provider.Settings.ACTION_WIFI_SETTINGS)),
        Triple("data", UiText.text(this@controlsScreen, R.string.ui_9b0034aa24), arrayOf(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)),
        Triple("mic", UiText.text(this@controlsScreen, R.string.ui_d387fc8d57), arrayOf(android.provider.Settings.Panel.ACTION_VOLUME, android.provider.Settings.ACTION_SOUND_SETTINGS)),
        Triple("look", UiText.text(this@controlsScreen, R.string.ui_aa486e4653), arrayOf(android.provider.Settings.ACTION_DISPLAY_SETTINGS)),
        Triple("repeat", UiText.text(this@controlsScreen, R.string.ui_78362540f0), arrayOf(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)),
        Triple("alarm", UiText.text(this@controlsScreen, R.string.ui_d3acbe1a50), arrayOf(android.provider.Settings.ACTION_SOUND_SETTINGS)),
    )) col.addView(system(icon, t, null, *acts), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    // L'agente a portata di mano: domande pronte.
    val ag = visibleAgents().getOrNull(homeIndex.coerceAtLeast(0)) ?: visibleAgents().firstOrNull()
    if (ag != null) {
        col.addView(label(UiText.text(this@controlsScreen, R.string.ui_b7671340f7)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
        for (q in listOf(UiText.text(this@controlsScreen, R.string.ui_bd01a2994e), UiText.text(this@controlsScreen, R.string.ui_a4c6e62a2b))) col.addView(row(q, Theme.colorOf(ag.color), icon = "chat") { val id = db.lastChat(ag.id) ?: db.newChat(ag.id); openChat(ag, id, say = q) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    }
}

internal fun MainActivity.tools(): Screen = listPage(UiText.text(this@tools, R.string.ui_6bceb5c35b), context = BuddySceneContext.CONFIGURE) { col ->
    val al = db.alarms().filter { it.on }.minByOrNull { Sched.next(it.hour, it.minute, it.days) }
    val t = db.timers().count { it.endAt > System.currentTimeMillis() }
    val r = db.routines().count { it.on }
    col.addView(grid(listOf(
        tile("alarm", UiText.text(this@tools, R.string.ui_70b85f50fe), al?.let { "${Sched.time(it.hour, it.minute)} · ${UiText.days(this@tools, it.days)}" } ?: UiText.text(this@tools, R.string.ui_a6bb6ebc6b), Theme.colorOf("amber")) { push(alarmsScreen()) },
        tile("timer", "timer", if (t == 0) UiText.text(this@tools, R.string.ui_cad10e601d) else UiText.text(this@tools, R.string.ui_1a5f5b15a3, t), Theme.colorOf("blue")) { push(timersScreen()) },
        tile("globe", UiText.text(this@tools, R.string.ui_1a8a49ab36), UiText.text(this@tools, R.string.ui_9383d2036e), Theme.colorOf("teal")) { push(translator()) },
        tile("note", UiText.text(this@tools, R.string.ui_c51048b732), UiText.text(this@tools, R.string.ui_aeccaf118f, db.notes().size), Theme.colorOf("pink")) { push(notesScreen()) },
        if (hermesMode()) tile("repeat", UiText.text(this@tools, R.string.ui_86e6aa070e), UiText.text(this@tools, R.string.ui_63f78e0d5f), Theme.colorOf("green")) { push(routinesScreen()) }
        else tile("repeat", "routine", UiText.text(this@tools, R.string.ui_4161247449, r, if (r == 1) UiText.text(this@tools, R.string.ui_c03424c65e) else UiText.text(this@tools, R.string.ui_1e6385e37a)), Theme.colorOf("green")) { push(routinesScreen()) },
    )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
}

// -- sveglie

internal fun MainActivity.alarmsScreen(): Screen = listPage(UiText.text(this@alarmsScreen, R.string.ui_70b85f50fe), context = BuddySceneContext.TIME) { col ->
    db.alarms().forEach { a ->
        val card = LinearLayout(this).apply { tag = LIST_ITEM; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(8f), dpi(10f), dpi(8f)); isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
            setOnClickListener { push(alarmEditor(a)) } }
        val timeV = text(34f, 700, Theme.FG).apply { text = Sched.time(a.hour, a.minute); alpha = if (a.on) 1f else 0.45f }
        fun alarmLine() = (UiText.days(this@alarmsScreen, a.days) + (if (a.label.isNotBlank()) " · ${a.label}" else "") + (if (a.on) " · " + UiText.text(this@alarmsScreen, R.string.ui_0ad2d49935, relativeIn(Sched.next(a.hour, a.minute, a.days) - System.currentTimeMillis())) else ""))
        val lineV = text(14f, 500, Theme.MUTED).apply { text = alarmLine(); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        card.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(timeV)
            addView(lineV) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(SwitchView(this, a.on, Theme.colorOf("green")) { on ->
            a.on = on; db.save(a); if (!on) Sched.cancel(this, Sched.KIND_ALARM, a.id); Sched.all(this, db)
            cue(if (on) "yawn" else "relief", 0f, 6f)
            timeV.animate().alpha(if (on) 1f else 0.45f).setDuration(Motion.BASE).start(); Motion.crossfade(lineV, alarmLine())   // cambia solo cio' che serve, senza ricostruire la lista
        })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    }
    if (db.alarms().isEmpty()) col.addView(emptyState(UiText.text(this@alarmsScreen, R.string.ui_e76de1bbe6), UiText.text(this@alarmsScreen, R.string.ui_4b236bc373)))
    col.addView(bigRow("plus", UiText.text(this@alarmsScreen, R.string.ui_0777f42593), null, Theme.colorOf("amber")) { push(alarmEditor(null)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
}

internal fun MainActivity.alarmEditor(existing: Alarm?): Screen {
    val now = java.util.Calendar.getInstance()
    val a = existing ?: Alarm(0, (now.get(java.util.Calendar.HOUR_OF_DAY) + 1) % 24, 0, 0, "", true)
    val label = field(UiText.text(this@alarmEditor, R.string.ui_a138cb1da6), a.label)
    var previewView: TextView? = null
    fun refreshPreview() { previewView?.text = UiText.text(this@alarmEditor, R.string.ui_0b43b978c7, relativeIn(Sched.next(a.hour, a.minute, a.days) - System.currentTimeMillis())) }
    return listPage(if (existing == null) UiText.text(this@alarmEditor, R.string.ui_0777f42593) else UiText.text(this@alarmEditor, R.string.ui_4b15f21af0), context = BuddySceneContext.TIME) { col ->
        col.addView(timeCard({ a.hour }, { a.hour = it; refreshPreview() }, { a.minute }, { a.minute = it; refreshPreview() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        // "suona tra 3h 12min": si aggiorna mentre giri il tamburo o cambi i giorni.
        col.addView(text(15f, 700, Theme.accent).apply { gravity = Gravity.CENTER; setPadding(0, dpi(8f), 0, 0); previewView = this }); refreshPreview()
        col.addView(label(UiText.text(this@alarmEditor, R.string.ui_aacd348c9b))); col.addView(daysChips({ a.days }, { a.days = it; refreshPreview() }))
        // Giorni rapidi.
        col.addView(LinearLayout(this).apply {
            listOf(R.string.ui_ac87b5e012 to 31, R.string.ui_505eabdfb8 to 96, R.string.ui_bf2f34b0ac to 127).forEach { (r, bits) ->
                addView(button(UiText.text(this@alarmEditor, r), toolAccent, small = true) { a.days = bits; daysRepaint?.invoke(); refreshPreview() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f); topMargin = dpi(6f) })
            }
        })
        col.addView(hint(UiText.text(this@alarmEditor, R.string.ui_cfd2c18236)))
        (label.parent as? ViewGroup)?.removeView(label)
        col.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        col.addView(button(UiText.text(this@alarmEditor, R.string.ui_3517056179), toolAccent) { askNotificationsOnce(); a.label = label.text.toString().trim(); a.on = true; db.save(a); Sched.all(this, db); pop() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        if (existing != null) col.addView(button(UiText.text(this@alarmEditor, R.string.ui_4a28ceb2d8), Theme.BAD, filled = false) { db.deleteAlarm(a.id); Sched.cancel(this, Sched.KIND_ALARM, a.id); pop() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    }
}

// -- timer

internal fun MainActivity.timersScreen(): Screen {
    var picked = 5
    return listPage("timer", context = BuddySceneContext.TIME) { col ->
        val ticking = ArrayList<Pair<TextView, Timer>>()
        val timers = db.timers().filter { it.endAt > System.currentTimeMillis() }
        timers.forEach { t ->
            val card = LinearLayout(this).apply { tag = LIST_ITEM; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(8f), dpi(10f), dpi(8f)) }
            val v = text(34f, 700).apply { fontFeatureSettings = "tnum" }
            ticking.add(v to t)
            card.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
                addView(v)
                addView(text(13f, 500, Theme.MUTED).apply { text = UiText.text(this@timersScreen, R.string.ui_2b804594a6, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, AppLanguage.locale(this@timersScreen)).format(java.util.Date(t.endAt))) }) }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(button(UiText.text(this@timersScreen, R.string.ui_9cb9b85e99), Theme.BAD, small = true) { Motion.dismiss(card) { cue("shiver"); db.deleteTimer(t.id); Sched.cancel(this, Sched.KIND_TIMER, t.id); stack.last().onShow?.invoke() } })
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        }
        fun tick() {
            val left = ticking.map { (v, t) -> val s = Countdown.seconds(t.endAt, System.currentTimeMillis()); v.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60); s }
            left.filter { it > 0 }.minOrNull()?.let { m ->
                if (m <= 10) cue(null, if (m % 2L == 0L) -8f else 8f, 6f, force = true)   // conta con gli occhi
                else if (m % 10L == 0L) cue(null, if ((m / 10) % 2L == 0L) -18f else 18f, 6f) // pendolo
            }
            if (left.any { it <= 0 }) { cue("surprised", force = true); stack.last().onShow?.invoke() }
        }
        if (ticking.isNotEmpty()) {
            // Ancorato al testo della lista corrente: una ricostruzione stacca il vecchio ticker.
            ViewTicker(ticking.first().first, 1000L, { visible && isCurrentSurface(ticking.first().first) }, ::tick)
        }
        col.addView(label(UiText.text(this@timersScreen, R.string.ui_faa3ef8f2f)))
        // Un tamburo di durate, da 10 secondi a 3 ore (in alto e' di meno, in basso di piu').
        val drum = Drum(this@timersScreen, timerLabels, picked, false) { cue(null, if (it > picked) 8f else -8f, 0f); picked = it }
        col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(6f))
            addView(drum, LinearLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        // Durate rapide: portano il tamburo su 1, 5, 10 o 30 minuti.
        col.addView(LinearLayout(this).apply {
            listOf("1m" to 60, "5m" to 300, "10m" to 600, "30m" to 1800).forEach { (name, secs) ->
                addView(button(name, toolAccent, small = true) { drum.select(timerSecs.indexOf(secs)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpi(6f); topMargin = dpi(8f) })
            }
        })
        col.addView(button(UiText.text(this@timersScreen, R.string.ui_1e70b53208), toolAccent) { cue("nod", 0f, 14f, force = true); startTimerSec(timerSecs[picked]); stack.last().onShow?.invoke() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
    }
}

internal fun MainActivity.startTimerSec(sec: Int) { askNotificationsOnce(); db.addTimer(System.currentTimeMillis() + sec * 1000L, ""); Sched.all(this, db) }

internal fun MainActivity.startTimer(minutes: Int) { db.addTimer(System.currentTimeMillis() + minutes * 60_000L, ""); Sched.all(this, db) }

// -- routine

internal fun MainActivity.routinesScreen(): Screen = if (hermesMode()) hermesJobsScreen() else localRoutinesScreen()

internal fun MainActivity.localRoutinesScreen(): Screen = listPage("routine", context = BuddySceneContext.CONFIGURE) { col ->
    val agents = visibleAgents()
    db.routines().forEach { r ->
        val card = LinearLayout(this).apply { tag = LIST_ITEM; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(10f), dpi(10f), dpi(10f)); isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
            setOnClickListener { push(routineEditor(r)) } }
        val nameV = text(21f, 700, Theme.FG).apply { text = r.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = if (r.on) 1f else 0.45f }
        card.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(nameV)
            addView(text(14f, 500, Theme.MUTED).apply { text = "${routineWhen(r)} · ${agents.firstOrNull { it.id == r.agentId }?.name ?: "?"}"; maxLines = 1 }) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(SwitchView(this, r.on, Theme.colorOf("green")) { on -> cue(if (on) "wink" else null, 0f, 6f); r.on = on; db.save(r); if (!on) Sched.cancel(this, Sched.KIND_ROUTINE, r.id); Sched.all(this, db); nameV.animate().alpha(if (on) 1f else 0.45f).setDuration(Motion.BASE).start() })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    }
    if (db.routines().isEmpty()) col.addView(emptyState(UiText.text(this@localRoutinesScreen, R.string.ui_82b4a5dab1), UiText.text(this@localRoutinesScreen, R.string.ui_1b43ea6de4)))
    col.addView(bigRow("plus", UiText.text(this@localRoutinesScreen, R.string.ui_16be2701de), null, Theme.colorOf("green")) { if (agents.isNotEmpty()) push(routineEditor(null)) else snack(UiText.text(this, R.string.ui_62dde8cda6), long = true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
}

/** Nome dell'evento di una routine («a un orario» se non ne ha). */
internal fun MainActivity.triggerLabel(t: String): String = UiText.text(this, when (t) {
    "carica" -> R.string.ui_74cd9507a1; "scollega" -> R.string.ui_323a8a8501; "wifi" -> R.string.ui_ef0cba7732; "cuffie" -> R.string.ui_65b3007ba9; else -> R.string.ui_ce5f4874c1 })

/** Quando parte una routine, per gli elenchi: «08:00 · lun-ven» oppure «in carica». */
internal fun MainActivity.routineWhen(r: Routine): String = if (r.trigger.isEmpty()) "${Sched.time(r.hour, r.minute)} · ${UiText.days(this, r.days)}" else triggerLabel(r.trigger)

internal fun MainActivity.routineEditor(existing: Routine?): Screen {
    val agents = visibleAgents()
    val r = existing ?: Routine(0, agents.first().id, "", "", 8, 0, 31, true)
    val name = field(UiText.text(this@routineEditor, R.string.ui_c9ec307ac9), r.name)
    val prompt = field(UiText.text(this@routineEditor, R.string.ui_2eb86892f8), r.prompt, multi = true)
    val status = text(14f, 600, Theme.MUTED).keepCase()
    return listPage(if (existing == null) UiText.text(this@routineEditor, R.string.ui_16be2701de) else "routine", context = BuddySceneContext.CONFIGURE) { col ->
        col.addView(label(UiText.text(this@routineEditor, R.string.ui_168fedf261)))
        val chips = LinearLayout(this); val views = ArrayList<Pair<Long, TextView>>()
        fun paint() = views.forEach { (id, v) -> chipPaint(v, r.agentId == id) }
        agents.forEach { ag -> views.add(ag.id to chipView(ag.name).apply { setOnClickListener { r.agentId = ag.id; paint() } }.also { chips.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) }) }) }
        paint(); col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        listOf<View>(name, prompt).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        col.addView(label(UiText.text(this@routineEditor, R.string.ui_de4da44e7e))); col.addView(name)
        col.addView(label(UiText.text(this@routineEditor, R.string.ui_d3610cf483))); col.addView(prompt)
        // Quando parte: a un orario (scheda dell'ora e giorni) o a un evento del telefono.
        col.addView(label(UiText.text(this@routineEditor, R.string.ui_d1faa1a845)))
        val whenChips = LinearLayout(this); val whenViews = ArrayList<Pair<String, TextView>>()
        val timed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val eventHint = hint(UiText.text(this@routineEditor, R.string.ui_dce6e6ddf5))
        fun paintWhen() {
            whenViews.forEach { (t, v) -> chipPaint(v, r.trigger == t) }
            if (r.trigger.isEmpty()) { Motion.show(timed); Motion.hide(eventHint) } else { Motion.hide(timed); Motion.show(eventHint) }
        }
        (listOf("") + RoutineEvents.TRIGGERS).forEach { t -> whenViews.add(t to chipView(triggerLabel(t)).apply { setOnClickListener { r.trigger = t; paintWhen() } }.also { whenChips.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) }) }) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(whenChips) })
        timed.addView(timeCard({ r.hour }, { r.hour = it }, { r.minute }, { r.minute = it }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        timed.addView(label(UiText.text(this@routineEditor, R.string.ui_aacd348c9b))); timed.addView(daysChips({ r.days }, { r.days = it }))
        col.addView(timed); col.addView(eventHint)
        if (r.trigger.isEmpty()) eventHint.visibility = View.GONE else timed.visibility = View.GONE
        paintWhen()
        fun commit(): Boolean {
            r.name = name.text.toString().trim().ifEmpty { "routine" }; r.prompt = prompt.text.toString().trim()
            if (r.prompt.isEmpty()) { status.setTextColor(Theme.BAD); status.text = UiText.text(this@routineEditor, R.string.ui_8037c3e45e); return false }
            if (agents.none { it.id == r.agentId }) { status.setTextColor(Theme.BAD); status.text = UiText.text(this, R.string.ui_62dde8cda6); return false }
            r.on = true; db.save(r); Sched.all(this, db); return true
        }
        col.addView(button(UiText.text(this@routineEditor, R.string.ui_3517056179), toolAccent) { if (commit()) pop() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
        col.addView(button(UiText.text(this@routineEditor, R.string.ui_46053fe150), toolAccent, primary = false) {
            if (commit()) { startForegroundService(Intent(this, RoutineService::class.java).putExtra("id", r.id)); status.setTextColor(Theme.colorOf("green")); status.text = UiText.text(this@routineEditor, R.string.ui_1bc14085f3) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dpi(4f) })
        if (existing != null) col.addView(button(UiText.text(this@routineEditor, R.string.ui_4a28ceb2d8), Theme.BAD, filled = false) { db.deleteRoutine(r.id); Sched.cancel(this, Sched.KIND_ROUTINE, r.id); pop() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    }
}

/** Le attivita' pianificate di Hermes: lista con interruttore (pausa/riprendi), tocco per eseguire subito o eliminare, e creazione di nuove. */
internal fun MainActivity.hermesJobsScreen(): Screen = listPage(UiText.text(this@hermesJobsScreen, R.string.ui_86e6aa070e), context = BuddySceneContext.CONFIGURE) { col ->
    val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(holder)
    col.addView(bigRow("plus", UiText.text(this@hermesJobsScreen, R.string.ui_f4266e420f), null, Theme.accent) { push(hermesJobEditor()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
    // Una sola pagina per tutte le attività: sotto i cron di Hermes anche le routine locali di questo telefono.
    val localRoutines = db.routines()
    if (localRoutines.isNotEmpty()) {
        col.addView(section(UiText.text(this@hermesJobsScreen, R.string.ui_91bfacd574)))
        val agentNames = visibleAgents()
        localRoutines.forEach { r ->
            col.addView(row(r.name, if (r.on) Theme.accent else Theme.MUTED, sub = "${routineWhen(r)} · ${agentNames.firstOrNull { it.id == r.agentId }?.name ?: "?"}", raw = true, icon = "repeat") { push(routineEditor(r)) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        }
    }
    fun jobLine(j: HermesJob): String {
        val parts = ArrayList<String>()
        if (j.schedule.isNotBlank()) parts += j.schedule
        if (!j.enabled) parts += UiText.text(this@hermesJobsScreen, R.string.ui_d1afa9b15a)
        else if (j.nextRun > 0) parts += UiText.text(this@hermesJobsScreen, R.string.ui_babc4a5bbf, relativeIn(j.nextRun - System.currentTimeMillis()))
        if (j.lastRun > 0) parts += UiText.text(this@hermesJobsScreen, R.string.ui_e4062a46e5, (if (j.failed()) UiText.text(this@hermesJobsScreen, R.string.ui_39d0eed3d8) else ago(j.lastRun)))
        return parts.joinToString(" · ")
    }
    fun card(j: HermesJob): View {
        val card = LinearLayout(this).apply { tag = LIST_ITEM; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(10f), dpi(10f), dpi(10f)); isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false }
        val nameV = text(20f, 700, Theme.FG).keepCase().apply { text = j.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = if (j.enabled) 1f else 0.45f }
        val lineV = text(14f, 500, Theme.MUTED).keepCase().apply { text = jobLine(j); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        card.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(nameV); addView(lineV) }, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var sw: SwitchView
        sw = SwitchView(this, j.enabled, Theme.colorOf("green")) { on ->
            nameV.animate().alpha(if (on) 1f else 0.45f).setDuration(Motion.BASE).start()
            Io.run { try { dash.cronAction(j, if (on) "resume" else "pause"); jobsCache = null; runOnUiThread { if (on) cue("wink") } } catch (e: Exception) { runOnUiThread { cue("surprised", force = true); sw.set(!on, false); nameV.alpha = if (!on) 1f else 0.45f; snack(UiText.text(this@hermesJobsScreen, R.string.ui_25d155617f, errorText(e)), long = true) } } }
        }
        card.addView(sw)
        card.setOnClickListener {
            sheetOf(j.name, listOf(
                SheetItem(UiText.text(this@hermesJobsScreen, R.string.ui_7bafdb870a), icon = "repeat", sub = if (j.lastStatus.isNotBlank()) UiText.text(this@hermesJobsScreen, R.string.ui_d304528aa4, j.lastStatus) else null) {
                    Io.run { try { dash.cronAction(j, "trigger"); runOnUiThread { cue("bounce", force = true); snack(UiText.text(this@hermesJobsScreen, R.string.ui_cdf273e773)) } } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@hermesJobsScreen, R.string.ui_25d155617f, errorText(e)), long = true) } } }
                },
                SheetItem(UiText.text(this@hermesJobsScreen, R.string.ui_4a28ceb2d8), icon = "close", danger = true) {
                  sheet(UiText.text(this@hermesJobsScreen, R.string.ui_66bebee3af), listOf(UiText.text(this@hermesJobsScreen, R.string.ui_4a28ceb2d8) to { Io.run { try { dash.cronDelete(j); jobsCache = jobsCache?.filter { it.id != j.id }; runOnUiThread { stack.lastOrNull()?.onShow?.invoke() } } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@hermesJobsScreen, R.string.ui_25d155617f, errorText(e)), long = true) } } } }))
                },
            ), subtitle = j.prompt.replace('\n', ' ').take(90))
        }
        card.setOnTouchListener { v, e -> Motion.touch(v, e); liquidTouch(v, e); false }
        return card
    }
    fun render(jobs: List<HermesJob>) {
        holder.removeAllViews()
        if (jobs.isEmpty()) holder.addView(emptyState(UiText.text(this@hermesJobsScreen, R.string.ui_6ac0c607ab), UiText.text(this@hermesJobsScreen, R.string.ui_83e9847a79)))
        else jobs.forEach { holder.addView(card(it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) }) }
        groupRows(holder)
        Motion.stagger(holder, dp(10f))
    }
    val cached = jobsCache
    if (cached != null) render(cached) else holder.addView(emptyState(UiText.text(this@hermesJobsScreen, R.string.ui_e400388645)))
    fun load(): Unit = Io.run {
        try { val jobs = dash.cronJobs(); jobsCache = jobs; runOnUiThread { whenAttached(holder) { render(jobs) } } }
        catch (e: Exception) { runOnUiThread { whenAttached(holder) { if (jobsCache == null) { holder.removeAllViews(); holder.addView(emptyState(UiText.text(this@hermesJobsScreen, R.string.ui_86750eb32f, errorText(e)))); holder.addView(button(UiText.text(this@hermesJobsScreen, R.string.ui_6ed04a5eb7), Theme.accent) { holder.removeAllViews(); holder.addView(emptyState(UiText.text(this@hermesJobsScreen, R.string.ui_e400388645))); load() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) }) } else snack(errorText(e)) } } }
    }
    load()
}

/** Nuova attivita' pianificata su Hermes: profilo (agente), nome, richiesta, ora e giorni (senza giorni parte una volta sola). */
internal fun MainActivity.hermesJobEditor(): Screen {
    val agents = visibleAgents()
    var agent = agents.firstOrNull()
    var hour = 8; var minute = 0; var days = 31
    val name = field(UiText.text(this@hermesJobEditor, R.string.ui_c9ec307ac9), "")
    val prompt = field(UiText.text(this@hermesJobEditor, R.string.ui_2eb86892f8), "", multi = true)
    val status = text(14f, 600, Theme.MUTED).keepCase()
    return listPage(UiText.text(this@hermesJobEditor, R.string.ui_f4266e420f), context = BuddySceneContext.CONFIGURE) { col ->
        col.addView(label(UiText.text(this@hermesJobEditor, R.string.ui_168fedf261)))
        val chips = LinearLayout(this); val views = ArrayList<Pair<Long, TextView>>()
        fun paint() = views.forEach { (id, v) -> chipPaint(v, agent?.id == id) }
        agents.forEach { ag -> views.add(ag.id to chipView(ag.name).apply { setOnClickListener { agent = ag; paint() } }.also { chips.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) }) }) }
        paint(); col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        listOf<View>(name, prompt).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        col.addView(label(UiText.text(this@hermesJobEditor, R.string.ui_de4da44e7e))); col.addView(name)
        col.addView(label(UiText.text(this@hermesJobEditor, R.string.ui_d3610cf483))); col.addView(prompt)
        col.addView(timeCard({ hour }, { hour = it }, { minute }, { minute = it }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        col.addView(label(UiText.text(this@hermesJobEditor, R.string.ui_aacd348c9b))); col.addView(daysChips({ days }, { days = it }))
        col.addView(hint(UiText.text(this@hermesJobEditor, R.string.ui_b30f24d128)))
        col.addView(button(UiText.text(this@hermesJobEditor, R.string.ui_3517056179), Theme.accent) {
            val ag = agent; val p = prompt.text.toString().trim()
            if (ag == null || p.isEmpty()) { status.setTextColor(Theme.BAD); status.text = UiText.text(this@hermesJobEditor, R.string.ui_8037c3e45e); return@button }
            status.setTextColor(Theme.MUTED); status.text = ""
            Io.run { try { dash.cronCreate(ag.profile, name.text.toString().trim().ifEmpty { p.take(30) }, p, cronExpression(hour, minute, days), days == 0); jobsCache = null; runOnUiThread { pop() } }
                catch (e: Exception) { runOnUiThread { status.setTextColor(Theme.BAD); status.text = UiText.text(this@hermesJobEditor, R.string.ui_25d155617f, errorText(e)) } } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dpi(4f) })
    }
}

/** Le competenze (skill) di un profilo Hermes, con interruttore per attivarle o disattivarle. */
internal fun MainActivity.skillsScreen(agent: Agent): Screen = listPage(UiText.text(this@skillsScreen, R.string.ui_d2d5097861)) { col ->
    val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(holder)
    holder.addView(emptyState(UiText.text(this@skillsScreen, R.string.ui_e400388645)))
    fun load(): Unit = Io.run {
        try {
            val list = dash.skills(agent.profile).sortedBy { it.name.lowercase() }
            runOnUiThread { whenAttached(holder) {
                holder.removeAllViews()
                if (list.isEmpty()) holder.addView(emptyState(UiText.text(this@skillsScreen, R.string.ui_9fb9f54c76)))
                val cards = ArrayList<Pair<HermesSkill, View>>()
                if (list.size > 8) holder.addView(field(UiText.text(this@skillsScreen, R.string.ui_cafd4e1fc8), "").apply {
                    setSingleLine(true)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) { val q = s.toString().trim().lowercase(); cards.forEach { (sk, v) -> v.visibility = if (q.isEmpty() || sk.name.lowercase().contains(q) || sk.description.lowercase().contains(q)) View.VISIBLE else View.GONE } }
                    })
                })
                list.forEach { sk ->
                    val card = LinearLayout(this).apply { tag = LIST_ITEM; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(10f), dpi(10f), dpi(10f)) }
                    val nameV = text(18f, 700, Theme.FG).keepCase().apply { text = sk.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = if (sk.enabled) 1f else 0.45f }
                    card.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(nameV); if (sk.description.isNotBlank()) addView(text(13f, 500, Theme.MUTED).keepCase().apply { text = sk.description; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }) }, LinearLayout.LayoutParams(0, -2, 1f))
                    lateinit var sw: SwitchView
                    sw = SwitchView(this, sk.enabled, Theme.colorOf("green")) { on ->
                        nameV.animate().alpha(if (on) 1f else 0.45f).setDuration(Motion.BASE).start()
                        Io.run { try { dash.toggleSkill(agent.profile, sk.name, on); runOnUiThread { if (on) cue("wink") } } catch (e: Exception) { runOnUiThread { cue("surprised", force = true); sw.set(!on, false); nameV.alpha = if (!on) 1f else 0.45f; snack(UiText.text(this@skillsScreen, R.string.ui_25d155617f, errorText(e)), long = true) } } }
                    }
                    card.addView(sw)
                    holder.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) }); cards += sk to card
                }
                groupRows(holder)
                Motion.stagger(holder, dp(10f))
            } }
        } catch (e: Exception) { runOnUiThread { whenAttached(holder) { holder.removeAllViews(); holder.addView(emptyState(UiText.text(this@skillsScreen, R.string.ui_86750eb32f, errorText(e)))); holder.addView(button(UiText.text(this@skillsScreen, R.string.ui_6ed04a5eb7), Theme.accent) { holder.removeAllViews(); holder.addView(emptyState(UiText.text(this@skillsScreen, R.string.ui_e400388645))); load() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) }) } } }
    }
    load()
}

// ---- spazio dell'agente (da SpaceScreen di r1os): chat, memoria, aspetto, ordine, rimozione ---

internal fun MainActivity.ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when { s < 60 -> UiText.text(this@ago, R.string.ui_b6ea728a1e); s < 3600 -> UiText.text(this@ago, R.string.ui_1aad585811, s / 60); s < 86400 -> UiText.text(this@ago, R.string.ui_21d1dcf49a, s / 3600); else -> UiText.text(this@ago, R.string.ui_1b030d23ea, s / 86400) }
}
