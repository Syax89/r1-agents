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

// ---- aspetto e voce (Settings › Look di r1os: modo, accento, font, schermata iniziale) -----------

internal fun MainActivity.applyLook(key: String, value: String) { prefs.edit().putString(key, value).putString("reopen", "look").apply(); if (key == "app_language") { prefs.edit().remove("w_at").remove("w_text").apply(); Sched.channels(this); hermesRuntime.refreshChannels() }; lookSnapshot = runCatching { android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888).also { root.draw(android.graphics.Canvas(it)) } }.getOrNull(); recreate(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }

internal fun MainActivity.lookScreen(): Screen = listPage(UiText.text(this@lookScreen, R.string.ui_20d6155d10), context = BuddySceneContext.CONFIGURE) { col ->
    /** Applica una scelta (stessa logica per righe, interruttori e chip). */
    fun choose(key: String, id: String) { if (key == "dev_actions") { prefs.edit().putString("dev_actions", id).apply(); DeviceActions.mode = id; stack.last().onShow?.invoke() } else if (key == "idle_after") { prefs.edit().putString("idle_after", id).apply(); stack.last().onShow?.invoke() } else if (key == "wheel_flip") { prefs.edit().putBoolean("wheel_flip_land", id == "flip").apply(); r1WheelLandscapeFlip = id == "flip"; stack.last().onShow?.invoke() } else if (key == "tts" || key == "voice_tts") { prefs.edit().putBoolean(key, id == "on").apply(); stack.last().onShow?.invoke() } else if (key == "stt") { prefs.edit().putString("stt", id).apply(); stack.last().onShow?.invoke() } else if (key == "clock" || key == "clock_align") { prefs.edit().putString(key, id).apply(); stack.last().onShow?.invoke() } else applyLook(key, id) }
    /** Scelta a testo: riga con il valore a destra; il tocco apre il foglio con le opzioni (spuntata quella attiva). */
    fun pick(title: String, options: List<Pair<String, String>>, current: String, key: String): View =
        settingRow(title, options.firstOrNull { it.first == current }?.second ?: current) {
            sheetOf(title, options.map { (id, lab) -> MainActivity.SheetItem(lab, selected = id == current) { if (id != current) choose(key, id) } })
        }
    /** Scelta sì/no: interruttore. */
    fun switch(title: String, on: Boolean, key: String, onId: String = "on", offId: String = "off"): View = settingSwitch(title, on) { v -> choose(key, if (v) onId else offId) }
    fun chips(options: List<Pair<String, String>>, current: String, key: String, font: Boolean = false): View {
        val row = LinearLayout(this)
        options.forEach { (id, label) ->
            val on = id == current
            row.addView(chipView(label).apply {
                chipPaint(this, on)
                if (font) typeface = Theme.fontOf(id, 700)
                setOnClickListener { if (!on) choose(key, id) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) })
        }
        // La scelta attiva resta visibile anche quando è in fondo alla riga (es. "offline" nel riconoscimento vocale).
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row)
            post { options.indexOfFirst { it.first == current }.takeIf { it > 0 }?.let { row.getChildAt(it) }?.let { c -> scrollTo((c.right - width + dpi(16f)).coerceAtLeast(0), 0) } } }
    }
    col.addView(groupTitle(UiText.text(this@lookScreen, R.string.ui_147f3d4b53)))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_1909c29a8f), listOf("it" to "Italiano", "en" to "English"), AppLanguage.selected(this), "app_language"))
    col.addView(hint(UiText.text(this@lookScreen, R.string.ui_7df11394ff)))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_b828c7599e), listOf("dark" to UiText.text(this@lookScreen, R.string.ui_67d1d02f39), "light" to UiText.text(this@lookScreen, R.string.ui_e7e35d97b7)), prefs.getString("look_mode", "dark")!!, "look_mode"))
    col.addView(label(UiText.text(this@lookScreen, R.string.ui_bdbffaa426)))
    col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(LinearLayout(this@lookScreen).apply {
        Theme.COLORS.forEach { (n, c) ->
            val on = n == Theme.accentName
            addView(View(this@lookScreen).apply {
                background = android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius = dp(20f); if (on) setStroke(dpi(3f), Theme.FG) }
                contentDescription = n; isSelected = on; isClickable = true; setOnClickListener { if (!on) applyLook("look_accent", n) } }, LinearLayout.LayoutParams(dpi(40f), dpi(40f)).apply { marginEnd = dpi(8f) })
        } }) })
    col.addView(groupTitle(UiText.text(this@lookScreen, R.string.ui_6742e04458)))
    col.addView(switch(UiText.text(this@lookScreen, R.string.ui_449d113dca), r1WheelLandscapeFlip, "wheel_flip", onId = "flip", offId = "normal"))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_2c24e821f5), listOf("1" to UiText.text(this@lookScreen, R.string.ui_3eb8a90885), "2" to UiText.text(this@lookScreen, R.string.ui_47742d68e8), "5" to UiText.text(this@lookScreen, R.string.ui_b45ebd80de), "10" to UiText.text(this@lookScreen, R.string.ui_4605ee7759), "never" to UiText.text(this@lookScreen, R.string.ui_ea4440bd94)), prefs.getString("idle_after", "1")!!, "idle_after"))
    col.addView(groupTitle(UiText.text(this@lookScreen, R.string.ui_15a1464f73)))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_78cb0ec0e3), listOf("ask" to UiText.text(this@lookScreen, R.string.ui_3cd50c52c0), "auto" to UiText.text(this@lookScreen, R.string.ui_9e67d0a179), "off" to UiText.text(this@lookScreen, R.string.ui_22938aeb5f)), DeviceActions.mode, "dev_actions"))
    col.addView(groupTitle(UiText.text(this@lookScreen, R.string.ui_e0f65ded6a)))
    col.addView(label(UiText.text(this@lookScreen, R.string.ui_1b496fbbd9))); col.addView(chips((listOf("rubik") + Theme.FAMILIES.keys).map { it to it }, prefs.getString("look_font", "rubik")!!, "look_font", font = true))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_1456190f29), listOf("glance" to UiText.text(this@lookScreen, R.string.ui_3d9a5cdfac), "home" to "home"), prefs.getString("start", "home")!!, "start"))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_18da3e53ab), listOf("glance" to UiText.text(this@lookScreen, R.string.ui_3d9a5cdfac), "home" to "home"), prefs.getString("bg_edit", "glance")!!, "bg_edit"))
    val scr = prefs.getString("bg_edit", "glance")!!
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_90cbd0f729), listOf("plain" to UiText.text(this@lookScreen, R.string.ui_7ef76e6237), "tint" to UiText.text(this@lookScreen, R.string.ui_8e3547d8f5), "gradient" to UiText.text(this@lookScreen, R.string.ui_c59119b0bf), "mesh" to UiText.text(this@lookScreen, R.string.ui_5cf2b74e33), "photo" to UiText.text(this@lookScreen, R.string.ui_0eb57679c4)), prefs.getString("bg_$scr", "plain")!!, "bg_$scr"))
    if (prefs.getString("bg_$scr", "plain") in listOf("gradient", "mesh")) {
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(LinearLayout(this@lookScreen).apply {
            palettes.keys.forEach { n ->
                val on = n == prefs.getString("bgp_$scr", "sunset")
                addView(View(this@lookScreen).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, palettes.getValue(n).map { Color.parseColor(it) }.toIntArray()).apply { cornerRadius = dp(15f); if (on) setStroke(dpi(3f), Theme.FG) }
                    isClickable = true; setOnClickListener { if (!on) applyLook("bgp_$scr", n) } }, LinearLayout.LayoutParams(dpi(40f), dpi(40f)).apply { marginEnd = dpi(8f); topMargin = dpi(4f) })
            } }) })
    }
    if (prefs.getString("bg_$scr", "plain") == "photo") {
        col.addView(button(UiText.text(this@lookScreen, R.string.ui_660ca97329), toolAccent, primary = false) { bgPick = scr; startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 8) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        val cur = prefs.getFloat("bgd_$scr", 0.4f)
        val veils = listOf(0f, 0.25f, 0.4f, 0.6f, 0.75f)
        fun veil(d: Float) = UiText.text(this@lookScreen, R.string.ui_e2344072da, (d * 100).toInt())
        col.addView(settingRow(UiText.text(this@lookScreen, R.string.ui_d084e1da9a), veil(veils.minByOrNull { Math.abs(it - cur) } ?: cur)) {
            sheetOf(UiText.text(this@lookScreen, R.string.ui_d084e1da9a), veils.map { d -> MainActivity.SheetItem(veil(d), selected = Math.abs(cur - d) < 0.01f) {
                prefs.edit().putFloat("bgd_$scr", d).putString("reopen", "look").apply(); recreate() } })
        })
    }
    col.addView(label(UiText.text(this@lookScreen, R.string.ui_1b435ea125)))
    /** Scelte multiple (cosa mostra il glance, i widget): un interruttore per voce, nella stessa scheda. */
    fun toggles(key: String, options: List<Pair<String, String>>, defaults: String) {
        val on = prefs.getString(key, defaults)!!.split(",").filter { it.isNotEmpty() }.toMutableSet()
        options.forEach { (id, lab) -> col.addView(settingSwitch(lab, id in on) { v -> if (v) on.add(id) else on.remove(id); prefs.edit().putString(key, on.joinToString(",")).apply(); if (key == "gw" && id == "meteo") stack.last().onShow?.invoke() }) }
    }
    toggles("gshow", listOf("date" to UiText.text(this@lookScreen, R.string.ui_a17c9aaa61), "status" to UiText.text(this@lookScreen, R.string.ui_1a3700624b), "alarm" to UiText.text(this@lookScreen, R.string.ui_4b15f21af0)), "date,status,alarm")
    col.addView(label("widget"))
    toggles("gw", listOf("hermes" to "hermes", "meteo" to UiText.text(this@lookScreen, R.string.ui_4028f305d5), "timer" to "timer", "routine" to UiText.text(this@lookScreen, R.string.ui_149d78e63c), "nota" to UiText.text(this@lookScreen, R.string.ui_2b295425f3)), "")
    if ("meteo" in prefs.getString("gw", "")!!.split(",")) {
        val city = field(UiText.text(this@lookScreen, R.string.ui_dbf977e9ce), prefs.getString("weather_city", "").orEmpty())
        col.addView(city, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        col.addView(button(UiText.text(this@lookScreen, R.string.ui_1fa7ec8671), toolAccent, primary = false, small = true) { prefs.edit().putString("weather_city", city.text.toString().trim()).remove("w_at").apply() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4f) })
    }
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_3d9a5cdfac), listOf("bold", "light", "condensed", "stacked", "serif", "mono", "words", "dial").map { it to it }, prefs.getString("clock", "bold")!!, "clock"))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_41b75bd493), listOf("left" to UiText.text(this@lookScreen, R.string.ui_3b72eca895), "center" to UiText.text(this@lookScreen, R.string.ui_d6b2c56f12)), prefs.getString("clock_align", "left")!!, "clock_align"))
    col.addView(switch(getString(R.string.voice_read_chat), prefs.getBoolean("tts", false), "tts"))
    col.addView(switch(getString(R.string.voice_read_fullscreen), prefs.getBoolean("voice_tts", true), "voice_tts"))
    col.addView(hint(getString(if (SpeechSupport.hasEngine(this@lookScreen)) R.string.voice_read_fullscreen_hint else R.string.voice_engine_help)))
    col.addView(pick(UiText.text(this@lookScreen, R.string.ui_5393e82f8d), listOf("device" to UiText.text(this@lookScreen, R.string.ui_843e6206e1), "groq" to "groq whisper", "openai" to UiText.text(this@lookScreen, R.string.ui_976e75efae), "hermes" to "hermes", "offline" to UiText.text(this@lookScreen, R.string.ui_d1ffda5dc9)), prefs.getString("stt", "device")!!, "stt"))
    sttExtras(col, prefs.getString("stt", "device")!!)
    if (prefs.getString("stt", "device") == "groq") {
        val key = field(UiText.text(this@lookScreen, R.string.ui_6d3fd91ed0), prefs.secret("groq_key"), secret = true)
        val model = field(UiText.text(this@lookScreen, R.string.ui_6bec3945e2), prefs.getString("groq_model", "whisper-large-v3-turbo").orEmpty())
        val lang = field(UiText.text(this@lookScreen, R.string.ui_b5b1267dab), prefs.getString("groq_lang", AppLanguage.selected(this@lookScreen)).orEmpty())
        val st = text(13f, 600, Theme.MUTED).keepCase().apply { setPadding(dpi(4f), dpi(6f), 0, 0) }
        col.addView(key, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        col.addView(model, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        col.addView(lang, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        col.addView(button(UiText.text(this@lookScreen, R.string.ui_41a6b92e3b), toolAccent) {
            prefs.edit().putSecret("groq_key", key.text.toString().trim()).putString("groq_model", model.text.toString().trim().ifEmpty { "whisper-large-v3-turbo" }).putString("groq_lang", lang.text.toString().trim()).apply()
            st.setTextColor(Theme.MUTED); st.text = UiText.text(this@lookScreen, R.string.ui_369c3bf02a)
            Io.run { val r = GroqStt.test(this@lookScreen, key.text.toString().trim()); runOnUiThread { st.text = UiText.owned(this@lookScreen, r); st.setTextColor(if (r.startsWith("OK")) Theme.colorOf("green") else Theme.BAD) } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        col.addView(st)
        col.addView(hint(UiText.text(this@lookScreen, R.string.ui_08e8413321)))
    }
    if (prefs.getString("stt", "device") == "device") col.addView(hint(UiText.text(this@lookScreen, R.string.ui_c3c6e6b033)))
    cardify(col)
}

internal fun MainActivity.actionLogScreen(): Screen = listPage(UiText.text(this@actionLogScreen, R.string.ui_793ade4b26), context = BuddySceneContext.SECURITY) { col ->
    val list = ActionLog.entries(prefs)
    if (list.isEmpty()) col.addView(emptyState(UiText.text(this@actionLogScreen, R.string.ui_7aac7be00d), UiText.text(this@actionLogScreen, R.string.ui_edaedc429e)))
    list.forEach { e ->
        val a = DeviceActions.Action(e.kind, e.arg)
        col.addView(row(describeAction(a), if (e.ok) Theme.accent else Theme.BAD, sub = ago(e.time) + if (e.ok) "" else " · " + UiText.text(this@actionLogScreen, R.string.ui_7ef44652ba), icon = if (e.ok) "check" else "close") { },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    }
    if (list.isNotEmpty()) col.addView(button(UiText.text(this@actionLogScreen, R.string.ui_1dcba78d37), Theme.BAD) { confirmTap("actlog") { ActionLog.clear(prefs); pop() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
}

internal fun MainActivity.memoryScreen(agent: Agent): Screen {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }
    val accent = Theme.colorOf(agent.color)
    val input = field(UiText.text(this@memoryScreen, R.string.ui_9abd0dbc2a), "")
    fun render() {
        col.removeAllViews()
        col.addView(intro(UiText.text(this@memoryScreen, R.string.ui_635a84c89c, agent.name)))
        val notes = db.memories(agent.id)
        if (notes.isEmpty()) col.addView(emptyState(UiText.text(this@memoryScreen, R.string.ui_7b938546c6), UiText.text(this@memoryScreen, R.string.ui_2a838553c5)))
        notes.forEach { (id, t) ->
            val card = LinearLayout(this).apply { tag = LIST_ITEM; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundRect(Theme.CARD, dp(22f)); setPadding(dpi(14f), dpi(10f), dpi(8f), dpi(10f)) ; isClickable = true; setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("nota", t)); it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); snack(UiText.text(this@memoryScreen, R.string.ui_2eba76fd11)) }}
            card.addView(text(17f, 500).keepCase().apply { text = t }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(button(UiText.text(this@memoryScreen, R.string.ui_dcfbe248ac), Theme.BAD, small = true) { Motion.dismiss(card) { db.deleteMemory(id); render() } })
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        }
        input.setText("")
        col.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
        col.addView(button(UiText.text(this@memoryScreen, R.string.ui_6c15f34ba8), accent) { val t = input.text.toString().trim(); if (t.isNotEmpty()) { cue("nod", 0f, 10f); db.addMemory(agent.id, t); render() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        groupRows(col) // righe vicine in un'unica scheda con divisori
    }
    render()
    return Screen(page(framed(UiText.text(this@memoryScreen, R.string.ui_7333237b9c), scroll)), buddyContext = BuddySceneContext.MEMORY)
}

internal fun MainActivity.manageChats(agent: Agent): Screen {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }
    var pending: Long? = null; var pendingAll = false
    fun render() {
        col.removeAllViews()
        val chats = db.chats(agent.id)
        if (chats.isEmpty()) col.addView(emptyState(UiText.text(this@manageChats, R.string.ui_fdb6359504)))
        chats.forEach { c ->
            val sure = pending == c.id
            lateinit var rv: LinearLayout
            rv = row(if (sure) UiText.text(this@manageChats, R.string.ui_66bebee3af) else c.title.ifBlank { "chat" }, Theme.BAD, icon = "chat", sub = if (sure) c.title.ifBlank { "chat" } else UiText.text(this@manageChats, R.string.ui_d8b94a17c8, c.count, ago(c.updated))) {
                if (!sure) { cue("surprised", force = true); pending = c.id; pendingAll = false; render() } else Motion.dismiss(rv) { cue("shiver", force = true); pending = null; removeChat(agent, c.id) { render() } }
            }
            col.addView(rv)
        }
        if (chats.size > 1) col.addView(row(if (pendingAll) UiText.text(this@manageChats, R.string.ui_5f3fa10d52) else UiText.text(this@manageChats, R.string.ui_92b3efd6d5), Theme.BAD, icon = "close") {
            if (!pendingAll) { pendingAll = true; pending = null; render() } else { pendingAll = false; val ids = chats.map { it.id }; var left = ids.size; ids.forEach { removeChat(agent, it) { if (--left == 0) render() } } }
        })
        groupRows(col) // righe vicine in un'unica scheda con divisori
    }
    render()
    return Screen(page(framed(UiText.text(this@manageChats, R.string.ui_07832915bd), scroll)), buddyContext = BuddySceneContext.CONFIGURE)
}

// ---- editor agente (forma, colore, finitura, occhi, accessori: dal guardaroba di r1os) --------

/**
 * L'aspetto di un agente, al centro il buddy grande. Ogni "proprietà" (forma, colore, finitura, occhi, cappelli e altri accessori, nome,
 * istruzioni) si sceglie con le frecce ai lati del corpo o con uno scorrimento orizzontale; la rotella (o uno scorrimento verticale, o su/giu')
 * cambia la proprietà, il cui nome sta sopra. Cosi' tutto si fa con un dito o con la rotella, senza scorrere una pagina lunga.
 */
internal fun MainActivity.editor(existing: Agent?): Screen {
    val a = existing ?: Agent(0, "", "", "", "orb", "orange")
    class Prop(val title: String, val kind: String, val values: List<Pair<String, String>>)
    val props = ArrayList<Prop>()
    props += Prop(UiText.text(this@editor, R.string.ui_80c34c3af8), "form", CharacterView.FORMS.map { it to Appearance.form(this, it, Wardrobe.catalog.form(it).label) })
    props += Prop(UiText.text(this@editor, R.string.ui_4fd52272a1), "color", Theme.COLORS.keys.map { it to Appearance.color(this, it) })
    props += Prop(UiText.text(this@editor, R.string.ui_d5fea1049c), "finish", CharacterView.FINISHES.map { it to Appearance.finish(this, it) })
    props += Prop(UiText.text(this@editor, R.string.ui_5298edb3b5), "eyes", CharacterView.EYES.map { it to Appearance.eyes(this, it, Wardrobe.catalog.eyes(it).label) })
    for (slot in Wardrobe.catalog.slots) {
        val items = Wardrobe.catalog.itemsIn(slot.id)
        if (items.isNotEmpty()) props += Prop(Appearance.slot(this, slot.id, slot.label), "wear:" + slot.id, listOf("" to UiText.text(this@editor, R.string.ui_6ebc1b6951)) + items.map { it.id to Appearance.item(this, it.id, it.label) })
    }
    props += Prop(UiText.text(this@editor, R.string.ui_de4da44e7e), "name", emptyList())
    if (a.profile.isEmpty()) props += Prop(if (hermesMode()) UiText.text(this@editor, R.string.ui_5000750434) else UiText.text(this@editor, R.string.ui_81d613b06c), "prompt", emptyList())

    val name = field(UiText.text(this@editor, R.string.ui_de4da44e7e), a.name)
    val prompt = field(UiText.text(this@editor, R.string.ui_6ee82c757b), a.prompt, multi = true).apply { minLines = 3; maxLines = 5 }
    // Mentre scrivi il buddy segue il cursore con lo sguardo (da sinistra a destra lungo la riga).
    listOf(name to 30, prompt to 60).forEach { (f, span) -> f.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(t: CharSequence?, a0: Int, b: Int, c: Int) {}
        override fun onTextChanged(t: CharSequence?, a0: Int, b: Int, c: Int) { cue(null, (((t?.length ?: 0) % span) / span.toFloat() - .5f) * 40f, 10f) }
        override fun afterTextChanged(t: android.text.Editable?) {}
    }) }
    val buddy = CharacterView(this, dpi(210f)).also { it.show(a); it.tag = "scene-buddy" }
    val propTitle = text(28f, 800).apply { gravity = Gravity.CENTER; letterSpacing = -0.01f }
    val valueLabel = text(22f, 700, Theme.accent).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
    var pi = 0
    var placeBuddy: (Boolean) -> Unit = {}
    var setTextLayout: (Boolean) -> Unit = {}

    fun valueIndex(p: Prop): Int = when {
        p.kind == "form" -> p.values.indexOfFirst { it.first == a.form }
        p.kind == "color" -> p.values.indexOfFirst { it.first == a.color }
        p.kind == "finish" -> p.values.indexOfFirst { it.first == a.finish }
        p.kind == "eyes" -> p.values.indexOfFirst { it.first == a.eyes }
        p.kind.startsWith("wear:") -> { val worn = a.wear.split(",").firstOrNull { id -> p.values.any { it.first == id && id.isNotEmpty() } } ?: ""; p.values.indexOfFirst { it.first == worn } }
        else -> -1
    }.coerceAtLeast(0)
    fun setValue(p: Prop, id: String) {
        when {
            p.kind == "form" -> a.form = id
            p.kind == "color" -> a.color = id
            p.kind == "finish" -> a.finish = id
            p.kind == "eyes" -> a.eyes = id
            p.kind.startsWith("wear:") -> a.wear = (a.wear.split(",").filter { w -> w.isNotEmpty() && p.values.none { it.first == w } } + listOfNotNull(id.ifEmpty { null })).joinToString(",")
        }
    }
    val isText = { p: Prop -> p.kind == "name" || p.kind == "prompt" }

    // Frecce ai lati del corpo (a meta' altezza del buddy) e area di scorrimento.
    val left = iconButton("back", Theme.accent, filled = false, size = 48f) {}
    val right = iconButton("chevron", Theme.accent, filled = false, size = 48f) {}
    val stage = FrameLayout(this).apply { clipChildren = false }
    stage.addView(left, FrameLayout.LayoutParams(dpi(48f), dpi(48f), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dpi(2f) })
    stage.addView(right, FrameLayout.LayoutParams(dpi(48f), dpi(48f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dpi(2f) })
    val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; alpha = 0f }
    listOf<View>(name, prompt).forEach { (it.parent as? ViewGroup)?.removeView(it) }
    fields.addView(name, LinearLayout.LayoutParams(-1, -2)); fields.addView(prompt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })

    fun paintDots() {
        dots.removeAllViews()
        props.forEachIndexed { i, _ -> dots.addView(View(this).apply { background = roundRect(if (i == pi) Theme.accent else Theme.SUBTLE, dp(4f)) }, LinearLayout.LayoutParams(dpi(if (i == pi) 16f else 6f), dpi(6f)).apply { marginStart = dpi(3f); marginEnd = dpi(3f) }) }
    }
    fun refreshValue(dir: Int) {
        val p = props[pi]
        if (isText(p)) return
        val v = p.values[valueIndex(p)]
        Motion.crossfade(valueLabel, v.second)
        buddy.show(a, "working", "build")
        placeBuddy(true)
        buddy.react(if (p.kind == "color" || p.kind == "finish") "bounce" else "wink")
    }
    fun showProp(dir: Int) {
        val p = props[pi]; val text = isText(p)
        propTitle.animate().cancel()
        propTitle.animate().translationY(-dir * dp(16f)).alpha(0f).setDuration(Motion.FAST / 2).withEndAction {
            propTitle.text = p.title; propTitle.translationY = dir * dp(16f)
            propTitle.animate().translationY(0f).alpha(1f).setDuration(Motion.BASE).setInterpolator(Theme.SETTLE).start()
        }.start()
        paintDots(); setTextLayout(text)
        // Proprieta' di testo: il buddy si rimpicciolisce e compare il campo; altrimenti frecce e valore.
        listOf(left, right).forEach { it.animate().alpha(if (text) 0f else 1f).setDuration(Motion.BASE).start(); it.isEnabled = !text }
        if (text) {
            valueLabel.animate().alpha(0f).setDuration(Motion.FAST).start()

            fields.visibility = View.VISIBLE; fields.animate().alpha(1f).setDuration(Motion.BASE).start()
            (if (p.kind == "name") name else prompt).post { (if (p.kind == "name") name else prompt).requestFocus() }
            name.visibility = if (p.kind == "name") View.VISIBLE else View.GONE; prompt.visibility = if (p.kind == "prompt") View.VISIBLE else View.GONE
        } else {
            hideKeyboard()
            fields.animate().alpha(0f).setDuration(Motion.FAST).withEndAction { if (!isText(props[pi])) fields.visibility = View.GONE }.start()

            valueLabel.animate().alpha(1f).setDuration(Motion.BASE).start(); valueLabel.text = p.values[valueIndex(p)].second
        }
        buddy.post { placeBuddy(true) }
    }
    fun moveProp(d: Int) { val n = (pi + d).coerceIn(0, props.size - 1); if (n == pi) { buddy.react("surprised"); return }; pi = n; showProp(d); performHapticFeedbackSafe() }
    fun moveValue(d: Int) {
        val p = props[pi]; if (isText(p)) return
        val n = (valueIndex(p) + d + p.values.size) % p.values.size
        setValue(p, p.values[n].first); refreshValue(d); performHapticFeedbackSafe()
    }
    left.setOnClickListener { moveValue(-1) }; right.setOnClickListener { moveValue(1) }
    // Dito: scorrimento orizzontale = valore, verticale = proprieta'. Rotella: la proprieta' (un passo ogni ~40 dp).
    val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
            if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 500f) moveValue(if (vx < 0) 1 else -1)
            else if (Math.abs(vy) > 500f) moveProp(if (vy < 0) 1 else -1)
            return true
        }
    })
    stage.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
    var wheelAcc = 0f
    val wheelBox = WheelFrame(this, { d ->
        wheelAcc += d
        if (Math.abs(wheelAcc) >= dp(40f)) { moveProp(if (wheelAcc < 0) 1 else -1); wheelAcc = 0f }
    }, { wheelAcc = 0f })

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    body.addView(propTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
    body.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6f); bottomMargin = dpi(2f) })
    body.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
    body.addView(valueLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
    val formColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    formColumn.addView(fields, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6f) })
    formColumn.addView(button(UiText.text(this@editor, R.string.ui_3517056179), Theme.colorOf(a.color)) {
        a.name = name.text.toString().trim().ifEmpty { UiText.text(this@editor, R.string.ui_168fedf261) }
        if (a.profile.isEmpty()) a.prompt = prompt.text.toString().trim()
        if (existing == null && hermesMode()) {
            // Con Hermes un nuovo agente è un nuovo profilo sul dashboard (nome: lettere minuscole, cifre e trattini).
            val slug = a.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)
            if (slug.isEmpty()) { cue("annoyed", force = true); snack(UiText.text(this@editor, R.string.ui_f209be3b5a), long = true); return@button }
            if (db.agentByProfile(slug) != null) { cue("annoyed", force = true); snack(UiText.text(this@editor, R.string.ui_7eced4aef8), long = true); return@button }
            snack(UiText.text(this@editor, R.string.ui_3267135c48))
            Io.run {
                try {
                    dash.createProfile(slug, a.prompt)
                    db.save(Agent(0, slug, a.prompt, "", a.form, a.color, a.finish, a.eyes, a.wear, slug))
                    runOnUiThread { homeIndex = visibleAgents().indexOfFirst { it.profile == slug }.coerceAtLeast(0); prefs.edit().putInt("home_index", homeIndex).apply(); pop() }
                } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@editor, R.string.ui_55684ca59e, UiText.message(this@editor, e.message)), long = true) } }
            }
            return@button
        }
        db.save(a); pop()
    }, LinearLayout.LayoutParams(-1, -2))
    val formScroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(formColumn, -1, -2) }
    body.addView(formScroll, LinearLayout.LayoutParams(-1, -2))
    setTextLayout = { text ->
        stage.visibility = if (text) View.GONE else View.VISIBLE
        valueLabel.visibility = if (text) View.GONE else View.VISIBLE
        formScroll.layoutParams = if (text) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2)
    }
    wheelBox.addView(body, FrameLayout.LayoutParams(-1, -1))
    propTitle.text = props[0].title; paintDots(); valueLabel.text = props[0].values[valueIndex(props[0])].second
    // Un solo personaggio sopra il layout: la tastiera può comprimere l'anteprima,
    // ma non può ritagliarlo. Nei campi di testo raggiunge la testata fissa.
    val headerSlot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dpi(64f), dpi(64f)) }
    val editorSurface = FrameLayout(this)
    editorSurface.addView(framed(if (existing == null) UiText.text(this@editor, R.string.ui_b1a1efd9f5) else UiText.text(this@editor, R.string.ui_20d6155d10), wheelBox, headerSlot, buddyHeader = false), FrameLayout.LayoutParams(-1, -1))
    editorSurface.addView(buddy, FrameLayout.LayoutParams(dpi(210f), dpi(210f)))
    var lastX = Float.NaN; var lastY = Float.NaN; var lastScale = Float.NaN
    placeBuddy = place@{ animate ->
        if (editorSurface.width > 0 && headerSlot.width > 0) {
            val target = if (isText(props[pi]) || stage.height < dpi(64f)) headerSlot else stage
            val at = IntArray(2); val origin = IntArray(2)
            target.getLocationInWindow(at); editorSurface.getLocationInWindow(origin)
            val size = if (target === headerSlot) dpi(64f).toFloat() else minOf(dpi(210f), stage.height, stage.width).toFloat()
            val x = at[0] - origin[0] + target.width / 2f - buddy.width / 2f
            val y = at[1] - origin[1] + target.height / 2f - buddy.height / 2f
            val scale = size / dpi(210f)
            if (kotlin.math.abs(lastX - x) < .5f && kotlin.math.abs(lastY - y) < .5f && kotlin.math.abs(lastScale - scale) < .001f) return@place
            val hadPosition = lastX.isFinite(); lastX = x; lastY = y; lastScale = scale
            buddy.animate().cancel()
            if (animate && hadPosition && !Motion.reduced(this)) buddy.animate().translationX(x).translationY(y).scaleX(scale).scaleY(scale).setDuration(Motion.BASE).setInterpolator(Theme.SETTLE).start()
            else { buddy.translationX = x; buddy.translationY = y; buddy.scaleX = scale; buddy.scaleY = scale }
        }
    }
    editorSurface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBuddy(true) }
    stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBuddy(true) }
    headerSlot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBuddy(true) }
    val screen = Screen(page(editorSurface), { e ->
        if (e.action != KeyEvent.ACTION_DOWN || currentFocus is EditText) false else when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveValue(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveValue(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveProp(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveProp(1); true }
            else -> false
        }
    }, buddyContext = BuddySceneContext.CONFIGURE)
    return screen
}

// ---- login Nous (device code) ---------------------------------------------------------------

internal fun MainActivity.qr(content: String, px: Int): android.graphics.Bitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 0)
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, px, px, hints)
    val px2 = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) Color.parseColor("#0b0b0c") else Color.WHITE }
    return android.graphics.Bitmap.createBitmap(px2, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
}

/** Login a Nous: avatar, titolo, QR su carta bianca, codice a pillola, un solo gesto ("annulla"). */
internal fun MainActivity.nousLogin(startFlow: Boolean = true): Screen {
    var cancelled = false
    val green = Theme.colorOf("green")
    val avatar = companion(96f)!!
    fun think(on: Boolean) = avatar.setScenePose(if (on) "thinking" else "idle")
    val title = text(24f, 800).apply { text = UiText.text(this@nousLogin, R.string.ui_e05f08f5bb); gravity = Gravity.CENTER }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    col.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
    val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }

    var restart: () -> Unit = {}
    fun hint(t: String, color: Int = Theme.MUTED) = text(14f, 500, color).keepCase().apply { text = t; gravity = Gravity.CENTER }
    fun cancelRow(label: String = UiText.text(this@nousLogin, R.string.ui_9cb9b85e99)) = button(label, Theme.MUTED, primary = false) { pop() }
    fun show(error: String?, retry: Boolean) {
        if (error != null) avatar.setScenePose("error")
        body.removeAllViews()
        body.addView(hint(error ?: "", Theme.BAD))
        if (retry) body.addView(button(UiText.text(this@nousLogin, R.string.ui_6ed04a5eb7), green) { restart() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
        body.addView(cancelRow(UiText.text(this@nousLogin, R.string.ui_e5844b95db)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    }
    fun waiting(c: NousAuth.Code) {
        avatar.setScenePose("waiting")
        body.removeAllViews()
        think(true)
        val card = FrameLayout(this).apply { background = roundRect(Color.WHITE, dpi(18f).toFloat()); setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f)) }
        card.addView(ImageView(this).apply { setImageBitmap(qr(c.uriComplete, dpi(150f))) }, FrameLayout.LayoutParams(dpi(150f), dpi(150f)))
        body.addView(card)
        body.addView(hint(UiText.text(this@nousLogin, R.string.ui_ab51840837)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        body.addView(text(15f, 650, Theme.FG).keepCase().apply { text = c.uri.removePrefix("https://"); gravity = Gravity.CENTER })
        body.addView(hint(UiText.text(this@nousLogin, R.string.ui_b6b51de0f5)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        body.addView(text(26f, 800, Theme.ON_ACCENT).keepCase().apply {
            text = c.userCode; letterSpacing = 0.14f; gravity = Gravity.CENTER
            background = roundRect(green, dpi(16f).toFloat()); setPadding(dpi(18f), dpi(9f), dpi(18f), dpi(9f))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(8f) })
        body.addView(cancelRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16f); bottomMargin = dpi(12f) })
    }
    fun start() {
        body.removeAllViews(); think(false)
        body.addView(hint(UiText.text(this@nousLogin, R.string.ui_af341a1d55)))
        Io.run {
            try {
                val c = nous.requestCode()
                runOnUiThread { if (!cancelled) waiting(c) }
                val ok = nous.poll(c) { cancelled }
                if (ok != null && !cancelled) runOnUiThread {
                    think(false); avatar.react("love"); title.text = UiText.text(this@nousLogin, R.string.ui_6b696c71a8)
                    body.removeAllViews(); body.addView(hint(UiText.text(this@nousLogin, R.string.ui_d450171bd7), green))
                    body.postDelayed({ if (!cancelled) pop() }, 1200)
                }
            } catch (e: Exception) {
                runOnUiThread { if (!cancelled) { think(false); avatar.react("surprised"); show(errorText(e), true) } }
            }
        }
    }
    restart = { start() }
    if (startFlow) start() else show(null, false)
    val fixed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; addView(avatar, LinearLayout.LayoutParams(dpi(96f), dpi(96f))); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)) }
    // Stessa intestazione di tutte le pagine (titolo e indietro in alto); il buddy grande resta nel contenuto.
    return Screen(page(framed(title.text.toString(), fixed, buddyHeader = false)), onLeave = { cancelled = true }, buddyContext = BuddySceneContext.LIVE)
}

// ---- impostazioni: fornitore di servizi -----------------------------------------------------

internal fun MainActivity.confirmPairing(pairing: Pairing) {
    val id = "pairing"
    overlay(id, UiText.text(this@confirmPairing, R.string.ui_30fe006e29), { closeOverlay(id) }) { col, close ->
        col.addView(text(18f, 650).keepCase().apply { text = pairing.origin })
        col.addView(intro(UiText.text(this@confirmPairing, R.string.ui_a4a9d83109, pairing.user.ifBlank { UiText.text(this@confirmPairing, R.string.ui_197bbb6280) })))
        col.addView(button(UiText.text(this@confirmPairing, R.string.ui_b4630216c8), Theme.accent) {
            Io.run {
                val configured = runCatching { hermesRuntime.configure(pairing) }
                runOnUiThread { configured.onSuccess { settings.provider = Providers.get("dashboard"); close(); push(providerScreen()) }.onFailure { snack(UiText.message(this@confirmPairing, it.message) ?: UiText.text(this@confirmPairing, R.string.ui_6a0ee75384), long = true) } }
            }
        })
        col.addView(button(UiText.text(this@confirmPairing, R.string.ui_9cb9b85e99), Theme.MUTED, primary = false) { close() })
    }
}

internal fun MainActivity.readPairingQr(uri: Uri, cleanup: () -> Unit = {}) {
    Io.run {
        val result = runCatching { PairingQr.read { contentResolver.openInputStream(uri) } }
        cleanup()
        runOnUiThread { result.onSuccess { confirmPairing(it) }.onFailure { snack(UiText.message(this@readPairingQr, it.message) ?: UiText.text(this@readPairingQr, R.string.ui_ddcf65537c), long = true) } }
    }
}

internal fun MainActivity.showPairingQr() {
    val result = runCatching { Pairing.create(dash.url, dash.user) }
    result.onFailure { snack(UiText.message(this@showPairingQr, it.message) ?: UiText.text(this@showPairingQr, R.string.ui_e416804a5e), long = true) }
    result.onSuccess { pair ->
        val id = "pairing-qr"
        overlay(id, UiText.text(this@showPairingQr, R.string.ui_6157f65d6b), { closeOverlay(id) }) { col, close ->
            val size = dpi(260f)
            col.addView(android.widget.ImageView(this).apply { setImageBitmap(PairingQr.image(pair, size)); contentDescription = UiText.text(this@showPairingQr, R.string.ui_19e8e1e64e) }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL })
            col.addView(hint(UiText.text(this@showPairingQr, R.string.ui_ccbb348a08, pair.origin, pair.user)))
            col.addView(button(UiText.text(this@showPairingQr, R.string.ui_a7712b5b03), Theme.MUTED, primary = false) { close() })
        }
    }
}

internal fun MainActivity.pairingMenu() = sheet(UiText.text(this@pairingMenu, R.string.ui_14d48b2cdd), listOf(
    UiText.text(this@pairingMenu, R.string.ui_1bdaa9d7c8) to { pickPhoto(15) },
    UiText.text(this@pairingMenu, R.string.ui_e5f9aa3385) to { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 14) },
    UiText.text(this@pairingMenu, R.string.ui_bd82ba58da) to { showPairingQr() }
), mapOf(UiText.text(this@pairingMenu, R.string.ui_1bdaa9d7c8) to "look", UiText.text(this@pairingMenu, R.string.ui_e5f9aa3385) to "attach", UiText.text(this@pairingMenu, R.string.ui_bd82ba58da) to "globe"))

internal fun MainActivity.providerScreen(): Screen {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }
    var build: () -> Unit = {}
    fun buildRaw(): Unit {
        col.removeAllViews()
        col.addView(label(UiText.text(this@providerScreen, R.string.ui_64ac619989)))
        val cur = settings.provider
        val currentLabel = UiText.owned(this, cur.label)
        col.addView(bigRow("spark", currentLabel.substringBefore(" ("), UiText.text(this@providerScreen, R.string.ui_ab9e87232e), Theme.accent) {
            sheet(UiText.text(this@providerScreen, R.string.ui_64ac619989), Providers.all.map { provider -> UiText.owned(this, provider.label) to { if (settings.provider.id != provider.id) cancelLocalTurns(); settings.provider = provider; build() } })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
        if (cur.id == "dashboard") {
            col.addView(bigRow("look", UiText.text(this@providerScreen, R.string.ui_14d48b2cdd), UiText.text(this@providerScreen, R.string.ui_f3589f24a4), Theme.accent) { pairingMenu() }) // stessa misura della voce sopra, nello stesso gruppo
            val addr = field(UiText.text(this@providerScreen, R.string.ui_e4edbc6b81), dash.url)
            val usr = field(UiText.text(this@providerScreen, R.string.ui_6b4422410d), dash.user)
            val pwd = field("password", dash.password, secret = true)
            val status = text(13f, 650, Theme.MUTED).keepCase().apply { setPadding(dpi(4f), dpi(8f), 0, 0) }
            col.addView(label(UiText.text(this@providerScreen, R.string.ui_e074fcb38e))); col.addView(addr)
            col.addView(label(UiText.text(this@providerScreen, R.string.ui_ee4b96bfac))); col.addView(usr)
            col.addView(pwd, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
            col.addView(button(UiText.text(this@providerScreen, R.string.ui_3b9b4c05a8), toolAccent) {
                val pairing = runCatching { Pairing.create(addr.text.toString(), usr.text.toString()) }
                pairing.onFailure { status.text = UiText.message(this@providerScreen, it.message); status.setTextColor(Theme.BAD) }
                pairing.onSuccess { config ->
                    val password = pwd.text.toString()
                    status.setTextColor(Theme.MUTED); status.text = UiText.text(this@providerScreen, R.string.ui_369c3bf02a)
                    Io.run {
                        val r = runCatching { hermesRuntime.configure(config, password); dash.test() }.getOrElse { UiText.message(this@providerScreen, it.message) ?: UiText.text(this@providerScreen, R.string.ui_6a0ee75384) }
                        runOnUiThread { status.text = UiText.owned(this@providerScreen, r); status.setTextColor(if (r.startsWith("OK")) Theme.colorOf("green") else Theme.BAD) }
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
            col.addView(status)
            col.addView(hint(UiText.text(this@providerScreen, R.string.ui_f120652037)))
            col.addView(button(UiText.text(this@providerScreen, R.string.ui_c6afddbe7d), toolAccent, primary = false, small = true) {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("hermes", "hermes dashboard --host 0.0.0.0 --no-open"))
                snack(UiText.text(this@providerScreen, R.string.ui_032d1653f8))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
            return
        }
        if (cur.id == "hermes") {
            col.addView(label(UiText.text(this@providerScreen, R.string.ui_f4e4b935cf)))
            if (nous.loggedIn) {
                col.addView(row(UiText.text(this@providerScreen, R.string.ui_c7770892d2), Theme.colorOf("green"), bold = true) {}, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6f) })
                col.addView(button(UiText.text(this@providerScreen, R.string.ui_c75e7e5ea0), Theme.BAD, filled = false) { nous.logout(); build() }, LinearLayout.LayoutParams(-1, -2))
            } else col.addView(row(UiText.text(this@providerScreen, R.string.ui_8b19beadc3), Theme.colorOf("green"), bold = true, sub = UiText.text(this@providerScreen, R.string.ui_1bf30cdcd1)) { push(nousLogin()) }, LinearLayout.LayoutParams(-1, -2))
        }
        val key = field(if (cur.id == "hermes") UiText.text(this@providerScreen, R.string.ui_96ed6b40c3) else UiText.text(this@providerScreen, R.string.ui_c65a02daf4), settings.key(cur), secret = true)
        val base = field(UiText.text(this@providerScreen, R.string.ui_47d34ef65f), settings.base(cur))
        val model = field(UiText.text(this@providerScreen, R.string.ui_6bec3945e2), settings.model(cur))
        col.addView(label(UiText.text(this@providerScreen, R.string.ui_c65a02daf4))); col.addView(key)
        col.addView(label(UiText.text(this@providerScreen, R.string.ui_47d34ef65f))); col.addView(base)

        col.addView(label(UiText.text(this@providerScreen, R.string.ui_6bec3945e2))); col.addView(model)
        if (cur.id == "hermes") col.addView(hint(UiText.text(this@providerScreen, R.string.ui_1d8ca53d81)))
        col.addView(button(UiText.text(this@providerScreen, R.string.ui_3517056179), toolAccent) {
            settings.set(cur, key.text.toString(), base.text.toString(), model.text.toString()); pop()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
    }
    // Ogni ricostruzione raggruppa le righe vicine (anche quando il ramo di Hermes esce prima dalla funzione).
    build = { buildRaw(); groupRows(col) }
    scroll.post { build() }
    return Screen(page(framed(UiText.text(this@providerScreen, R.string.ui_64ac619989), scroll)), buddyContext = BuddySceneContext.CONFIGURE)
}

/** Le impostazioni: un riassunto del fornitore in alto e poi le sezioni, in righe grandi. */
internal fun MainActivity.settingsScreen(): Screen = listPage(UiText.text(this@settingsScreen, R.string.ui_0c375bfee9), context = BuddySceneContext.CONFIGURE) { col ->
    companionRow(140f)?.let { col.addView(it, LinearLayout.LayoutParams(-1, dpi(124f)).apply { bottomMargin = dpi(4f) }) }
    val cur = settings.provider
    val status = when {
        cur.id == "dashboard" -> when (dash.state) { HermesDash.State.READY -> UiText.text(this@settingsScreen, R.string.ui_26d3536b7e) ; HermesDash.State.CONNECTING -> UiText.text(this@settingsScreen, R.string.ui_8ae4c57b7e); HermesDash.State.ERROR -> UiText.text(this@settingsScreen, R.string.ui_0a79810a06); else -> UiText.text(this@settingsScreen, R.string.ui_4a85016ff2) } + " · " + dash.url.ifBlank { UiText.text(this@settingsScreen, R.string.ui_f613d8d937) }
        cur.id == "hermes" && nous.loggedIn -> UiText.text(this@settingsScreen, R.string.ui_79b1c92384)
        settings.key(cur).isNotBlank() -> UiText.text(this@settingsScreen, R.string.ui_09099b15ae) + settings.model(cur).ifBlank { UiText.text(this@settingsScreen, R.string.ui_a80f6d0b7b) }
        else -> UiText.text(this@settingsScreen, R.string.ui_649e90a1ed)
    }
    col.addView(bigRow("spark", UiText.owned(this@settingsScreen, cur.label).substringBefore(" ("), (UiText.owned(this@settingsScreen, cur.label).substringAfter(" (", "").removeSuffix(")").let { if (it.isNotEmpty()) "$it · " else "" }) + status, Theme.colorOf("orange")) { push(providerScreen()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f); bottomMargin = dpi(4f) })
    if (cur.id == "dashboard" && dash.state == HermesDash.State.READY) {
        val acct = bigRow("agent", UiText.text(this@settingsScreen, R.string.ui_5851d53b86), UiText.text(this@settingsScreen, R.string.ui_11720c9cf2), Theme.colorOf("teal")) {}
        col.addView(acct, LinearLayout.LayoutParams(-1, -2))
        Io.run { val t = try { dash.account("") } catch (e: Exception) { UiText.text(this@settingsScreen, R.string.ui_6ef97386ba) }; runOnUiThread { ((acct.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView)?.text = UiText.owned(this@settingsScreen, t) } }
    }
    col.addView(label("app"))
    col.addView(grid(listOf(
        tile("look", UiText.text(this@settingsScreen, R.string.ui_20d6155d10), UiText.text(this@settingsScreen, R.string.ui_b2a183c667), Theme.colorOf("purple")) { push(lookScreen()) },
        tile("data", UiText.text(this@settingsScreen, R.string.ui_2953d06507), UiText.text(this@settingsScreen, R.string.ui_2cddbe429a), Theme.colorOf("teal")) { sheet(UiText.text(this@settingsScreen, R.string.ui_2953d06507), listOf(UiText.text(this@settingsScreen, R.string.ui_273893985c) to { startExport() }, UiText.text(this@settingsScreen, R.string.ui_77c7dfe5ae) to { startProtectedExport() }, UiText.text(this@settingsScreen, R.string.ui_26962ddc5b) to { startImport() })) },
    )))
    col.addView(row(UiText.text(this@settingsScreen, R.string.ui_addbf9ddc4), Theme.accent, sub = sideButtonSummary(), icon = "power") { push(sideButtonScreen()) })
    col.addView(row(UiText.text(this@settingsScreen, R.string.ui_9729d2de44), Theme.accent, sub = if (pinEnabled()) UiText.text(this@settingsScreen, R.string.ui_c10e8db395) else UiText.text(this@settingsScreen, R.string.ui_db99ab2043), icon = "lock") { push(securityScreen()) })
    col.addView(label(UiText.text(this@settingsScreen, R.string.ui_03b76df6e0)))
    col.addView(bigRow("sliders", UiText.text(this@settingsScreen, R.string.ui_3bab362b6a), UiText.text(this@settingsScreen, R.string.ui_db09e3de46), Theme.colorOf("amber")) {
        try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { snack(UiText.text(this@settingsScreen, R.string.ui_f2c6915e4c)) }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    // Schermata iniziale (launcher): l'app è selezionabile dal sistema; qui si apre solo la pagina di scelta, l'utente decide.
    col.addView(bigRow("data", UiText.text(this@settingsScreen, R.string.ui_1456190f29), if (isDefaultLauncher()) UiText.text(this@settingsScreen, R.string.ui_eddfb6c071) else UiText.text(this@settingsScreen, R.string.ui_5993e82081), Theme.colorOf("teal")) {
        try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    col.addView(bigRow("info", "r1agents", UiText.text(this@settingsScreen, R.string.ui_18b999e9d9, packageManager.getPackageInfo(packageName, 0).versionName), Theme.colorOf("blue")) { push(aboutScreen()) })
}

internal fun MainActivity.aboutScreen(): Screen = listPage(UiText.text(this@aboutScreen, R.string.ui_03b76df6e0)) { col ->
    companionRow(112f)?.let { col.addView(it, LinearLayout.LayoutParams(-1, dpi(112f))) }
    col.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dpi(6f), 0, dpi(10f))
        addView(text(26f, 800).apply { text = "R1 Agents"; gravity = Gravity.CENTER; setPadding(0, dpi(6f), 0, 0) })
        addView(text(14f, 600, Theme.MUTED).apply { text = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(); gravity = Gravity.CENTER })
    })
    col.addView(intro(UiText.text(this@aboutScreen, R.string.ui_391553b02f)))
    // Aggiornamenti: l'indirizzo è fisso nell'app (non si mostra). Aprendo la pagina il controllo parte da solo e la scheda dice
    // in parole semplici com'è la situazione: aggiornata, nuova versione con le note e un solo pulsante, oppure perché non si può.
    col.addView(label(UiText.text(this@aboutScreen, R.string.ui_f43d53399f)))
    val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundRect(Theme.CARD, dp(Shapes.CARD)); setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(16f)) }
    val status = text(18f, 700).keepCase()
    val detail = text(14f, 500, Theme.MUTED).keepCase().apply { setPadding(0, dpi(4f), 0, 0); maxLines = 10; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE }
    val action = button(UiText.text(this@aboutScreen, R.string.ui_922654d58f), toolAccent) {}.apply { visibility = View.GONE }
    card.addView(status); card.addView(detail); card.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
    col.addView(card, LinearLayout.LayoutParams(-1, -2))
    val ui = UpdateUi(status, detail, action)
    col.addView(button(UiText.text(this@aboutScreen, R.string.ui_56169e629e), Theme.MUTED, primary = false, small = true) { checkUpdates(ui) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    checkUpdates(ui)
}

/** Gli elementi della scheda aggiornamenti. */
internal class UpdateUi(val status: TextView, val detail: TextView, val action: TextView)

/** Da dove si legge il manifest: fisso; solo le build di prova possono indicarne un altro (`update_url`). */
internal fun MainActivity.updateUrl(): String {
    val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    return prefs.getString("update_url", null)?.takeIf { debuggable && it.isNotBlank() } ?: Updater.DEFAULT_URL
}

internal fun MainActivity.updateMessage(status: TextView, e: Exception) {
    val text = if (e is UpdateError) (if (e.arg != null) UiText.text(this, e.resource, e.arg) else UiText.text(this, e.resource)) else UiText.text(this, R.string.ui_483f5c9b20, errorText(e) ?: e::class.java.simpleName)
    status.setTextColor(Theme.BAD); status.text = text
}

internal fun MainActivity.checkUpdates(ui: UpdateUi) {
    ui.status.setTextColor(Theme.MUTED); ui.status.text = UiText.text(this, R.string.ui_bc1ba41905)
    ui.detail.visibility = View.GONE; ui.action.visibility = View.GONE
    Io.run {
        try {
            val manifest = Updater.fetchManifest(this, updateUrl())
            val installed = packageManager.getPackageInfo(packageName, 0)
            val signer = runCatching { Updater.signerMatches(this, manifest) }.getOrNull()
            runOnUiThread {
                whenAttached(ui.status) {
                    // Lo stato cambia in dissolvenza e i dettagli entrano morbidi: niente scatti nella scheda.
                    if (!Updater.isNewer(manifest, installed.longVersionCode)) {
                        ui.status.setTextColor(Theme.colorOf("green")); Motion.crossfade(ui.status, UiText.text(this, R.string.ui_3d49cf80ba, installed.versionName))
                        return@whenAttached
                    }
                    ui.status.setTextColor(Theme.FG); Motion.crossfade(ui.status, UiText.text(this, R.string.ui_5fe0888cab, manifest.versionName))
                    Motion.show(ui.detail)
                    if (signer == false) {
                        // Firma diversa: lo si dice subito, invece di scaricare l'APK per poi rifiutarlo.
                        ui.detail.setTextColor(Theme.BAD); ui.detail.text = UiText.text(this, R.string.ui_4b07cc5415)
                    } else {
                        ui.detail.setTextColor(Theme.MUTED); ui.detail.text = manifest.notes.take(600)
                        Motion.show(ui.action)
                        ui.action.setOnClickListener { downloadUpdate(manifest, ui.status) }
                    }
                }
            }
        } catch (e: Exception) { runOnUiThread { updateMessage(ui.status, e) } }
    }
}

internal fun MainActivity.downloadUpdate(manifest: UpdateManifest, status: TextView) {
    if (!Updater.canInstall(this)) {
        snack(UiText.text(this, R.string.ui_9d4279bfc6), long = true)
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
        return
    }
    status.setTextColor(Theme.MUTED); status.text = UiText.text(this, R.string.ui_2cf76d597f)
    Io.run {
        try {
            val dir = java.io.File(cacheDir, "updates"); dir.listFiles()?.forEach { it.delete() }
            val apk = java.io.File(dir, "r1agents-${manifest.versionCode}.apk")
            var lastPercent = -1
            Updater.download(manifest, apk) { done, total -> val percent = (done * 100 / total).toInt(); if (percent != lastPercent) { lastPercent = percent; runOnUiThread { status.text = UiText.text(this, R.string.ui_2cf76d597f) + " " + percent + "%" } } }
            Updater.verifyApk(this, apk, manifest)
            Updater.install(this, apk)
            runOnUiThread { status.setTextColor(Theme.colorOf("green")); status.text = UiText.text(this, R.string.ui_eb668afd93) }
        } catch (e: Exception) { runOnUiThread { updateMessage(status, e) } }
    }
}

/** Le opzioni del riconoscimento vocale diverse da Groq: altro Whisper (indirizzo, chiave, modello), Hermes, offline. */
private fun MainActivity.sttExtras(col: LinearLayout, stt: String) {
    when (stt) {
        "openai" -> {
            val url = field(UiText.text(this, R.string.ui_d5c892d1a4), prefs.getString("stt_url", "").orEmpty())
            val key = field(UiText.text(this, R.string.ui_1f042d08d6), prefs.secret("stt_key"), secret = true)
            val model = field(UiText.text(this, R.string.ui_6bec3945e2), prefs.getString("stt_model", "whisper-1").orEmpty())
            val st = text(13f, 600, Theme.MUTED).keepCase().apply { setPadding(dpi(4f), dpi(6f), 0, 0) }
            col.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
            col.addView(key, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
            col.addView(model, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
            col.addView(button(UiText.text(this, R.string.ui_41a6b92e3b), toolAccent) {
                val base = url.text.toString().trim()
                if (!safeBase(base)) { st.setTextColor(Theme.BAD); st.text = UiText.text(this, R.string.ui_3f95a6ee8b); return@button }
                prefs.edit().putString("stt_url", base).putSecret("stt_key", key.text.toString().trim()).putString("stt_model", model.text.toString().trim().ifEmpty { "whisper-1" }).apply()
                st.setTextColor(Theme.MUTED); st.text = UiText.text(this, R.string.ui_369c3bf02a)
                Io.run { val r = GroqStt.test(this, key.text.toString().trim(), base); runOnUiThread { st.text = UiText.owned(this, r); st.setTextColor(if (r.startsWith("OK")) Theme.colorOf("green") else Theme.BAD) } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
            col.addView(st)
            col.addView(hint(UiText.text(this, R.string.ui_6ed73af004)))
        }
        "hermes" -> col.addView(hint(UiText.text(this, if (prefs.getString("provider", "") == "dashboard") R.string.ui_8a68ece775 else R.string.ui_e376d3513b)))
        "offline" -> {
            val lang = OfflineModels.lang(this)
            val st = text(13f, 600, Theme.MUTED).keepCase().apply { setPadding(dpi(4f), dpi(6f), 0, 0) }
            if (OfflineModels.ready(this, lang)) {
                st.setTextColor(Theme.colorOf("green")); st.text = UiText.text(this, R.string.ui_73dc130ca9)
                col.addView(st)
                col.addView(button(UiText.text(this, R.string.ui_b128736752), Theme.MUTED, primary = false, small = true) { OfflineModels.delete(this, lang); stack.last().onShow?.invoke() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
            } else {
                col.addView(button(UiText.text(this, R.string.ui_4b25b806c4, if (lang == "en") "40" else "50"), toolAccent) {
                    if (OfflineModels.downloading) return@button
                    OfflineModels.download(this, lang, { pc -> st.text = UiText.text(this, R.string.ui_ce9d757bc7, "$pc%") }) { err ->
                        if (err != null) { st.setTextColor(Theme.BAD); st.text = UiText.text(this, R.string.ui_fe76631f65, err) } else stack.lastOrNull()?.onShow?.invoke()
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
                col.addView(st)
            }
            col.addView(hint(UiText.text(this, R.string.ui_adbebd9ed5)))
        }
    }
}
