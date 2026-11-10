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

// ---- chat -----------------------------------------------------------------------------------

/** Scelta del modello di una chat Hermes: prima il fornitore, poi il modello (da `model.options`). */
internal fun MainActivity.pickModel(agent: Agent, chatId: Long, say: (String) -> Unit) {
    snack(UiText.text(this@pickModel, R.string.ui_ead8aeac15))
    Io.run {
        val rows = try { dash.models(agent.profile) } catch (e: Exception) { runOnUiThread { snack(errorText(e), long = true) }; return@run }
        runOnUiThread {
            if (rows.isEmpty()) { snack(UiText.text(this@pickModel, R.string.ui_473ec0d778), long = true); return@runOnUiThread }
            fun apply(r: HermesDash.ModelRow, confirm: Boolean = false): Unit = Io.run {
                val res = try { dash.setModel(chatId, agent.profile, r, confirm) } catch (e: Exception) { UiText.text(this@pickModel, R.string.ui_589110f583, errorText(e)) }
                runOnUiThread { if (res == "CONFERMA") sheet(UiText.text(this@pickModel, R.string.ui_99f20c7f7e), listOf(UiText.text(this@pickModel, R.string.ui_8e62ad1ad1, r.model) to { apply(r, true) })) else say(UiText.text(this@pickModel, R.string.ui_e308e43542, res)) }
            }
            val provs = rows.groupBy { it.provider }.entries.sortedByDescending { e -> e.value.any { it.current } }
            // Fornitori (quello in uso, con la spunta, per primo); toccandone uno si aprono i suoi modelli, con ricerca.
            sheetOf(UiText.text(this@pickModel, R.string.ui_64ac619989), provs.map { (_, ms) ->
                SheetItem(ms.first().providerName, sub = UiText.text(this@pickModel, R.string.ui_1f6c580980, ms.size), icon = "spark", selected = ms.any { it.current }) {
                    sheetOf(ms.first().providerName.lowercase(), ms.take(120).map { m -> SheetItem(m.model, selected = m.current) { apply(m) } })
                }
            })
        }
    }
}

// Una scheda dell'agente (show_card): barra del colore a sinistra, titolo e testo in markdown su fondo scuro.
internal fun MainActivity.cardView(agent: Agent, c: org.json.JSONObject): View {
    val col = Theme.colorOf(c.optString("color").ifBlank { agent.color })
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(14f)) }
    body.addView(text(20f, 800).keepCase().apply { text = c.optString("title"); letterSpacing = -0.01f })
    body.addView(text(16f, 500).keepCase().apply { setLineSpacing(0f, 1.25f); setPadding(0, dpi(6f), 0, 0); text = Md.render(c.optString("body"), Theme.withAlpha(Theme.FG, 0.14f)) })
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; background = roundRect(Theme.CARD, dp(20f))
        addView(View(this@cardView).apply { background = roundRect(col, dp(2f)) }, LinearLayout.LayoutParams(dpi(5f), -1).apply { setMargins(dpi(10f), dpi(12f), 0, dpi(12f)) })
        addView(body, LinearLayout.LayoutParams(0, -2, 1f))
    }
}

internal fun MainActivity.chat(agent: Agent, chatId: Long, say: String? = null, share: Share? = null, observeHsid: String? = null): Screen {
    val chatScope = Scope()
    var engine: Engine? = null
    var stopTaskWatch: (() -> Unit)? = null
    var bindHermes: (HermesRuntime.Task, TextView) -> Unit = { _, _ -> }
    var chatFeedback: (String, String) -> Unit = { _, _ -> }
    val accent = Theme.colorOf(agent.color)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(4f), 0, dpi(8f)) }
    val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dpi(28f)); addView(list, -1, -2) }
    var busy = false

    // Come ChatScreen.bubble di r1os: Rubik 18, angoli 20 con quello verso il suo lato a 6.
    fun bubble(mine: Boolean): TextView = text(18f, 500, if (mine) Theme.ON_ACCENT else Theme.FG).keepCase().apply {
        setOnLongClickListener {
            val raw = (tag as? String) ?: text.toString()
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(UiText.text(this@chat, R.string.ui_151a6c958d), raw))
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            snack(UiText.text(this@chat, R.string.ui_2eba76fd11)); true
        }
        setPadding(dpi(15f), dpi(12f), dpi(15f), dpi(12f)); setLineSpacing(0f, 1.28f)
        val r = dp(20f); val s = dp(6f)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadii = if (mine) floatArrayOf(r, r, r, r, s, s, r, r) else floatArrayOf(r, r, r, r, r, r, s, s)
            setColor(if (mine) accent else Theme.CARD)
        }
    }
    var lastMine: Boolean? = null
    fun lp(mine: Boolean) = LinearLayout.LayoutParams(-2, -2).apply {
        topMargin = if (lastMine == mine) dpi(4f) else dpi(12f); lastMine = mine; gravity = if (mine) Gravity.END else Gravity.START
        if (mine) marginStart = dpi(36f) else marginEnd = dpi(24f)
    }
    val codeBg = Theme.withAlpha(Theme.FG, 0.14f)
    val chipRows = java.util.WeakHashMap<View, LinearLayout>()
    val mdCaches = java.util.WeakHashMap<View, Md.Cache>()
    fun actionChips(v: View, acts: List<DeviceActions.Action>) {
        if (acts.isEmpty()) return
        val row = chipRows.getOrPut(v) { LinearLayout(this@chat).apply { orientation = LinearLayout.HORIZONTAL } }
        val have = (0 until row.childCount).map { row.getChildAt(it).tag as? String }
        acts.filter { it.key !in have }.forEach { a -> row.addView(button(describeAction(a), accent, primary = false, small = true) { reportAction(a) }.apply { tag = a.key }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) }) }
        fun attach() { if (row.parent == null) { val idx = list.indexOfChild(v); if (idx >= 0) list.addView(row, idx + 1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4f); gravity = Gravity.START }) } }
        attach(); v.post { attach() }
    }
    fun setMsg(v: TextView, raw: String, mine: Boolean, error: Boolean = false) {
        v.tag = raw
        if (error) { v.text = UiText.owned(this@chat, raw); return }
        if (mine) { v.text = raw; return }
        val p = DeviceActions.parse(raw)
        val body = p.text.ifBlank { p.actions.joinToString(", ") { describeAction(it) } }
        val mc = mdCaches.getOrPut(v) { Md.Cache() }
        v.text = Md.render(body, codeBg, mc)
        if (body.contains("```")) v.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        actionChips(v, p.actions)
    }
    // Chat vuota: invece di una pagina nera, un invito con l'agente (sparisce al primo messaggio).
    var emptyHint: View? = null
    var fillInput: (String) -> Unit = {}
    fun add(t: String, mine: Boolean, error: Boolean = false): TextView = bubble(mine).apply {
        emptyHint?.let { list.removeView(it); emptyHint = null }
        setMsg(this, t, mine, error); if (error) { setTextColor(Theme.BAD); post { Motion.shake(this) } }
        list.addView(this, lp(mine))
        if (mine) Motion.enter(this, dx = dp(10f), dy = dp(40f), dur = Motion.SLOW) // il messaggio inviato sale dal campo di testo
        else Motion.enter(this, dx = -dp(18f), dy = dp(12f), dur = Motion.BASE)
    }
    var unreadCue: () -> Unit = {}
    val latest = button(UiText.text(this@chat, R.string.ui_c85846ed60), accent, primary = false, small = true) {}.apply { visibility = View.GONE }
    fun nearBottom() = scroll.scrollY + scroll.height >= list.height - dpi(80f)
    fun bottom(smooth: Boolean = false) = scroll.post { if (smooth && !Motion.reduced(this)) scroll.smoothScrollTo(0, list.height) else scroll.scrollTo(0, list.height); Motion.hide(latest) }
    fun updated(follow: Boolean) {
        if (follow) bottom(true) else if (latest.visibility != View.VISIBLE) {
            Motion.show(latest); unreadCue()
        }
    }
    latest.setOnClickListener { bottom(true) }
    fun cardLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f); marginEnd = dpi(12f) }
    fun note(t: String) {
        list.addView(text(14f, 500, Theme.MUTED).keepCase().apply { text = t; gravity = Gravity.CENTER; setPadding(dpi(8f), dpi(6f), dpi(8f), dpi(6f)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        bottom()
    }
    fun runCmd(cmd: String) {
        note("$cmd …")
        Io.run {
            val out = try { dash.command(chatId, agent.profile, cmd) } catch (e: Exception) { UiText.text(this@chat, R.string.ui_2f38b99da3, UiText.message(this@chat, e.message)) }
            runOnUiThread { note(out.take(1500)) }
        }
    }
    fun showCommands() = sheet(UiText.text(this@chat, R.string.ui_bcfaf5fac6), listOf(
        UiText.text(this@chat, R.string.ui_03fdb016df) to { runCmd("/compress") },
        UiText.text(this@chat, R.string.ui_68cbd39916) to { runCmd("/undo") },
        UiText.text(this@chat, R.string.ui_d4c120052e) to { askText(UiText.text(this@chat, R.string.ui_9b4e9826bc), UiText.text(this@chat, R.string.ui_cbaa97c396)) { runCmd("/model $it") } },
        UiText.text(this@chat, R.string.ui_391532bba7) to { askText(UiText.text(this@chat, R.string.ui_ed549af454), UiText.text(this@chat, R.string.ui_166425b50b)) { runCmd(it) } }))

    // Cronologia a finestra: solo gli ultimi 40 messaggi; i più vecchi si caricano a richiesta (nessuna vista per ogni messaggio di una sessione lunga).
    val pageSize = 40
    var oldestId = Long.MAX_VALUE
    val moreBtn = button(UiText.text(this@chat, R.string.ui_889569e420), accent, primary = false, small = true) {}
    fun textOf(c: String): String {
        val arr = JSONArray(c); val sb = StringBuilder()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { b ->
            if (b.getString("type") == "text") sb.append(b.optString("text"))
            else if (b.getString("type") == "attachment") sb.append("\n📎 ").append(b.optString("name"))
        }
        return sb.toString().trim()
    }
    fun loadOlder() {
        val page = db.messagesPage(chatId, pageSize + 1, if (oldestId == Long.MAX_VALUE) null else oldestId)
        val more = page.size > pageSize
        val shown = if (more) page.drop(1) else page
        val before = list.height
        var pos = if (moreBtn.parent != null) 1 else 0
        for ((id, role, c) in shown) {
            oldestId = minOf(oldestId, id)
            val t = textOf(c)
            if (t.isNotBlank()) list.addView(bubble(role == "user").also { setMsg(it, t, role == "user") }, pos++, lp(role == "user"))
            if (role != "user") runCatching { JSONArray(c) }.getOrNull()?.let { arr ->
                for (k in 0 until arr.length()) arr.getJSONObject(k).let { b -> if (b.optString("type") == "tool_use" && b.optString("name") == "show_card") list.addView(cardView(agent, b.optJSONObject("input") ?: org.json.JSONObject()), pos++, cardLp()) }
            }
        }
        if (more && moreBtn.parent == null) list.addView(moreBtn, 0, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(4f) })
        if (!more && moreBtn.parent != null) list.removeView(moreBtn)
        if (before > 0) list.post { scroll.scrollTo(0, scroll.scrollY + (list.height - before)) }
    }
    moreBtn.setOnClickListener { loadOlder() }
    var first = db.messagesPage(chatId, 1, null).isEmpty()
    loadOlder()
    if (first && observeHsid == null && say == null && share == null) {
        emptyHint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dpi(24f), dpi(40f), dpi(24f), 0)
            addView(text(24f, 800).apply { text = UiText.text(this@chat, R.string.ui_d5f4254e31, agent.name); gravity = Gravity.CENTER; setPadding(0, dpi(10f), 0, dpi(4f)) })
            addView(text(15f, 500, Theme.MUTED).keepCase().apply { text = UiText.text(this@chat, R.string.ui_ccd94418dd); gravity = Gravity.CENTER })
            // Tre idee per cominciare: toccandone una finisce nel campo (non si invia da sola).
            listOf(R.string.ui_9d6297af1f, R.string.ui_48b918b31b, R.string.ui_591845331b).forEach { r -> val idea = UiText.text(this@chat, r)
                addView(button(idea, accent, small = true) { fillInput(idea) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6f) }) }
        }
        list.addView(emptyHint)
    }

    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    val headChar = CharacterView(this, dpi(56f)).also { it.show(agent); it.scaleX = 36f / 56f; it.scaleY = 36f / 56f }
    val avatarSlot = FrameLayout(this).apply {
        clipChildren = false; clipToPadding = false; isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
        contentDescription = UiText.text(this@chat, R.string.ui_799d550ed7, agent.name)
        addView(headChar, FrameLayout.LayoutParams(dpi(56f), dpi(56f), Gravity.CENTER))
    }
    var avatarExpanded = false
    var avatarMotion: android.animation.ValueAnimator? = null
    fun resizeAvatar(expanded: Boolean) {
        avatarExpanded = expanded
        avatarSlot.contentDescription = if (expanded) UiText.text(this@chat, R.string.ui_0d423ada7a, agent.name) else UiText.text(this@chat, R.string.ui_799d550ed7, agent.name)
        avatarMotion?.cancel()
        val start = avatarSlot.layoutParams.height
        val target = dpi(if (expanded) 64f else 48f)
        val fromScale = headChar.scaleX
        val toScale = if (expanded) 1f else 36f / 56f
        avatarMotion = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.BASE; interpolator = Theme.SETTLE
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                avatarSlot.layoutParams = avatarSlot.layoutParams.apply { height = (start + (target - start) * t).toInt() }
                val scale = fromScale + (toScale - fromScale) * t
                headChar.scaleX = scale; headChar.scaleY = scale
            }
            start()
        }
    }
    val collapseAvatar = Runnable { resizeAvatar(false) }
    avatarSlot.setOnClickListener {
        avatarSlot.removeCallbacks(collapseAvatar)
        resizeAvatar(!avatarExpanded)
        if (avatarExpanded) avatarSlot.postDelayed(collapseAvatar, 4000)
    }
    val backBtn = iconButton("back", accent, filled = false, size = 48f) { pop() }
    val nameV = text(22f, 800).apply { letterSpacing = -0.02f; text = agent.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dpi(8f), 0, 0, 0); setOnClickListener { if (!busy) push(space(agent)) } }
    head.addView(backBtn)
    head.addView(avatarSlot, LinearLayout.LayoutParams(dpi(48f), dpi(48f)).apply { marginStart = dpi(4f) })
    head.addView(nameV, LinearLayout.LayoutParams(0, -2, 1f))
    lateinit var openAttach: () -> Unit
    val menuBtn = iconButton("more", accent, filled = false, size = 48f) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += UiText.text(this@chat, R.string.ui_7f4849bfb0) to { if (!busy) replaceTop(chat(agent, db.newChat(agent.id))) else snack(UiText.text(this@chat, R.string.ui_8cec984fbd)) }
        items += UiText.text(this@chat, R.string.ui_0fd7b04a53) to { openAttach() }
        items += (if (!pinEnabled()) UiText.text(this@chat, R.string.ui_dc815e9fc7) else if (chatId.toString() in lockedChats()) UiText.text(this@chat, R.string.ui_9d86e57fdb) else UiText.text(this@chat, R.string.ui_768a2cb129)) to {
            if (!pinEnabled()) pinSetup() else { val on = chatId.toString() !in lockedChats(); setChatLocked(chatId, on); if (on) pinUnlocked = true; cue(if (on) "nod" else "wink", force = true) }
        }
        val hs = db.hsid(chatId)
        if (hermesMode() && agent.profile.isNotEmpty()) {
            items += UiText.text(this@chat, R.string.ui_eef051cd40) to { askText(UiText.text(this@chat, R.string.ui_00c9325b04), UiText.text(this@chat, R.string.ui_a87537ff47)) { t -> Io.run { try { dash.rename(chatId, agent.profile, t); db.title(chatId, t.take(60)); runOnUiThread { snack(UiText.text(this@chat, R.string.ui_9ea5d517e7)) } } catch (e: Exception) { runOnUiThread { snack(errorText(e), long = true) } } } } }
            items += UiText.text(this@chat, R.string.ui_6bec3945e2) to { pickModel(agent, chatId) { m -> note(m) } }
            items += UiText.text(this@chat, R.string.ui_03fdb016df) to { note(UiText.text(this@chat, R.string.ui_9c76b629d1)); Io.run { val r = try { dash.compress(chatId, agent.profile) } catch (e: Exception) { UiText.text(this@chat, R.string.ui_589110f583, errorText(e)) }; runOnUiThread { note(UiText.text(this@chat, R.string.ui_12aa930947, r)) } } }
            if (hs.isNotEmpty()) items += UiText.text(this@chat, R.string.ui_09af77c5ae) to {
                snack(UiText.text(this@chat, R.string.ui_7f784011b7))
                Io.run { try { val nh = dash.branch(hs, agent.profile); val id = db.upsertChat(agent.id, nh, (db.chats(agent.id).firstOrNull { it.id == chatId }?.title ?: "chat").take(50) + UiText.text(this@chat, R.string.ui_798b410cf8), System.currentTimeMillis(), 0); runOnUiThread { if (chatScope.alive) openChat(agent, id) } } catch (e: Exception) { runOnUiThread { if (chatScope.alive) snack(errorText(e), long = true) } } }
            }
            if (hs.isNotEmpty() && !busy) items += UiText.text(this@chat, R.string.ui_2e5a083bde) to { confirmTap("archive$chatId") {
                chatFeedback("upload", UiText.text(this@chat, R.string.ui_f3596da8e1))
                Io.run {
                    try {
                        dash.archive(hs, agent.profile)
                        dash.forget(chatId); db.deleteChat(chatId)
                        runOnUiThread { if (chatScope.alive) pop() }
                    } catch (e: Exception) { runOnUiThread { if (chatScope.alive) chatFeedback("error", UiText.text(this@chat, R.string.ui_0f2784f610)) } }
                }
            } }
            items += UiText.text(this@chat, R.string.ui_5f7b655465) to { Io.run { val subs = try { dash.subagents(chatId, agent.profile) } catch (e: Exception) { runOnUiThread { snack(errorText(e), long = true) }; return@run }
                runOnUiThread {
                    if (subs.isEmpty()) { snack(UiText.text(this@chat, R.string.ui_d2ba27adf3)); return@runOnUiThread }
                    sheet(UiText.text(this@chat, R.string.ui_5f7b655465), subs.map { sa -> ("${sa.goal.take(44)} · ${sa.status}" ) to {
                        sheet(sa.goal.take(30), listOfNotNull(UiText.text(this@chat, R.string.ui_78be0e73b2) to { Io.run { val ok = runCatching { dash.stopSub(chatId, agent.profile, sa.id) }.getOrDefault(false); runOnUiThread { note(if (ok) UiText.text(this@chat, R.string.ui_12fe7179c8) else UiText.text(this@chat, R.string.ui_b26f1e7af3)) } } },
                            if (sa.canSteer) UiText.text(this@chat, R.string.ui_b6a0d0a674) to { askText(UiText.text(this@chat, R.string.ui_217f40a226), UiText.text(this@chat, R.string.ui_04f5089419)) { t -> Io.run { val ok = runCatching { dash.steerSub(chatId, agent.profile, sa.id, t) }.getOrDefault(false); runOnUiThread { note(if (ok) UiText.text(this@chat, R.string.ui_550a3f682d) else UiText.text(this@chat, R.string.ui_f18cdbd92f)) } } } } else null),
                            mapOf(UiText.text(this@chat, R.string.ui_78be0e73b2) to "close", UiText.text(this@chat, R.string.ui_b6a0d0a674) to "chat"))
                    } })
                } } }
            items += UiText.text(this@chat, R.string.ui_5f694433cb) to { showCommands() }
        }
        sheet("chat", items, danger = setOf(UiText.text(this@chat, R.string.ui_2e5a083bde)), icons = mapOf(UiText.text(this@chat, R.string.ui_7f4849bfb0) to "plus", UiText.text(this@chat, R.string.ui_0fd7b04a53) to "attach", UiText.text(this@chat, R.string.ui_eef051cd40) to "note", UiText.text(this@chat, R.string.ui_6bec3945e2) to "spark", UiText.text(this@chat, R.string.ui_03fdb016df) to "sliders", UiText.text(this@chat, R.string.ui_09af77c5ae) to "repeat", UiText.text(this@chat, R.string.ui_2e5a083bde) to UiText.text(this@chat, R.string.ui_a17c9aaa61), UiText.text(this@chat, R.string.ui_5f7b655465) to "agent", UiText.text(this@chat, R.string.ui_5f694433cb) to "more"))
    }
    head.addView(menuBtn)

    unreadCue = { headChar.lookAt(0f, 12f, 1200); headChar.react("wink") }
    val buddyStage = BuddyStage(this, agent)
    chatFeedback = { kind, label -> buddyStage.flash(kind, label, 2800) }
    var connectionAlive = true
    var previousConnection = dash.state
    fun connectionChanged(state: HermesDash.State) {
        if (!connectionAlive || !hermesMode()) return
        val hadConnectionNotice = buddyStage.mode in listOf("reconnect", "offline")
        if (state == HermesDash.State.READY) {
            if (hadConnectionNotice) buddyStage.flash("connected", UiText.text(this@chat, R.string.ui_f24ee25561))
        } else if (buddyStage.mode == "idle" || hadConnectionNotice) {
            buddyStage.show(if (state == HermesDash.State.CONNECTING) "reconnect" else "offline", if (state == HermesDash.State.CONNECTING) UiText.text(this@chat, R.string.ui_7c1cae1c78) else UiText.text(this@chat, R.string.ui_4814f6f7f6))
        }
        previousConnection = state
    }
    val stopConnectionWatch = if (hermesMode()) dash.watchState { state -> runOnUiThread { if (state != previousConnection) connectionChanged(state) } } else ({})
    buddyStage.post { if (dash.state != HermesDash.State.READY) connectionChanged(dash.state) }
    var updateAction: () -> Unit = {}
    // Allegati in attesa di essere inviati: miniature a pillola sopra la barra, un tocco le toglie.
    val pending = ArrayList<Attachment>()
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(chips) }
    fun refreshChips() {
        chips.removeAllViews()
        pending.toList().forEach { a ->
            chips.addView(text(14f, 600).keepCase().apply {
                text = "📎 ${a.name.take(22)}  ✕"; setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f)); background = roundRect(Theme.CARD, dp(14f)); isClickable = true
                setOnClickListener { confirmTap("att${a.name}") { pending.remove(a); a.file.delete(); refreshChips(); if (buddyStage.mode in listOf("file", "photo")) buddyStage.dismiss() } }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) })
        }
        if (pending.isEmpty()) Motion.hide(chipsScroll) else Motion.show(chipsScroll)
        updateAction()
    }
    attachHook = { a -> runOnUiThread { pending.add(a); refreshChips(); Motion.enter(chipsScroll, dx = -dp(14f), dy = dp(6f), dur = Motion.BASE); headChar.lookAt(-9f, 9f, 900); buddyStage.flash(if (a.kind == Kind.IMAGE) "photo" else "file", UiText.text(this@chat, R.string.ui_da45045b61, a.name)) } }
    val activity = text(14f, 600, Theme.MUTED).keepCase().apply { visibility = View.GONE; gravity = Gravity.CENTER; setPadding(dpi(4f), dpi(6f), dpi(4f), dpi(4f)); maxLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }   // TalkBack annuncia "sto cercando...", "leggo il file..."
    var lastAct = ""
    // Riga di stato sotto la conversazione: solo mentre l'agente lavora, mai a riposo (come r1os).
    fun setActivity(s: String) {
        if (busy) {
            Motion.crossfade(activity, UiText.owned(this@chat, s.ifBlank { UiText.text(this@chat, R.string.ui_3433289441, agent.name) })); Motion.show(activity)
            val visual = BuddyVisualState.fromActivity(s)
            val kind = if (s.isBlank() && buddyStage.mode == "upload") "done" else visual.kind
            buddyStage.show(kind, UiText.owned(this@chat, s.ifBlank { UiText.text(this@chat, R.string.ui_3433289441, agent.name) }), s)
            if (s.isBlank()) headChar.show(agent, "speaking") else { if (s != lastAct) headChar.react("bounce"); headChar.show(agent, "working", activityKind(s)) }
            lastAct = s
        }
        else Motion.hide(activity)
    }
    val input = field(UiText.text(this@chat, R.string.ui_3265eeab7c), "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        setSingleLine(false); setHorizontallyScrolling(false); maxLines = 4   // cresce fino a quattro righe (impostare inputType dopo riporterebbe a una riga sola)
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI; background = roundRect(Theme.CARD, dp(26f)); setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f)) }
    fillInput = { t -> input.setText(t); input.setSelection(t.length); input.requestFocus() }
    input.addTextChangedListener(object : android.text.TextWatcher { override fun afterTextChanged(s: android.text.Editable?) {}; override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { if (!busy) headChar.lookAt(0f, 9f, 900); updateAction() } })
    var draftBeforeVoice: String? = null
    fun reviewVoice(transcript: String): Unit {
        if (transcript.isBlank() || busy) return
        if (draftBeforeVoice == null) draftBeforeVoice = input.text.toString()
        input.setSingleLine(false); input.maxLines = 3
        input.setText(appendTranscript(input.text.toString(), transcript)); input.setSelection(input.text.length)
        headChar.show(agent, "idle"); headChar.react("wink")
        buddyStage.review {
            input.setText(draftBeforeVoice.orEmpty()); draftBeforeVoice = null
            input.setSingleLine(true); buddyStage.dismiss()
        }
    }
    val send = iconButton("send", accent, size = 52f) {}
    var lastUser = ""
    lateinit var stopBtn: View
    lateinit var sendBtn: View
    var lastAtts: List<Attachment> = emptyList()
    fun submit(t: String, retry: Boolean = false) {
        if (busy || (t.isBlank() && pending.isEmpty() && !retry)) return
        if (!retry && pending.isEmpty() && tryOpenApp(t)) { input.setText(""); return }
        if (hermesRuntime.isConfiguring()) { snack(UiText.text(this@chat, R.string.ui_b121797238)); return }
        if (hermesRuntime.active(chatId) != null) { snack(UiText.text(this@chat, R.string.ui_c723f3fd80)); openChat(agent, chatId); return }
        val atts = if (retry) lastAtts else pending.toList()
        draftBeforeVoice = null; input.setSingleLine(true)
        lastUser = t; lastAtts = atts
        askNotificationsOnce(); list.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (!retry) { pending.clear(); refreshChips() }
        if (first) { first = false; db.title(chatId, t.ifBlank { atts.firstOrNull()?.name ?: "allegato" }.replace('\n', ' ').take(40)) }
        busy = true; input.setText(""); if (!retry) add((t + atts.joinToString("") { "\n📎 ${it.name}" }).trim(), true); headChar.tag = null; headChar.show(agent, "thinking"); headChar.react("bounce"); updateAction(); setActivity(""); lastAct = ""
        buddyStage.show(if (atts.isEmpty()) "send" else "send_file", if (atts.isEmpty()) UiText.text(this@chat, R.string.ui_bef0afceff) else UiText.text(this@chat, R.string.ui_c767defaed, atts.size))
        buddyStage.postDelayed({ if (busy && buddyStage.mode in listOf("send", "send_file")) setActivity(lastAct) }, 450)
        val reply = add("", false).apply { setCompoundDrawablesRelativeWithIntrinsicBounds(TypingDots(this@chat), null, null, null) }; bottom()
        if (hermesMode()) {
            bindHermes(hermesRuntime.start(agent, chatId, t, retry, atts), reply)
            return
        }
        // Gli aggiornamenti del testo si raggruppano ogni 50 ms (prima: una riassegnazione e uno scorrimento per ogni parola).
        val turnEngine = localEngine(); engine = turnEngine
        val acc = StringBuilder()
        val got = java.util.concurrent.atomic.AtomicBoolean(false)
        val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
        val flush = Runnable {
            scheduled.set(false)
            if (!chatScope.alive || engine !== turnEngine) return@Runnable
            val s = synchronized(acc) { acc.toString() }
            val near = nearBottom()
            reply.setCompoundDrawablesRelative(null, null, null, null)
            setMsg(reply, s, false)
            updated(near)
        }
        Io.run {
            try {
                turnEngine.turn(agent, chatId, t, retry, onActivity = { s -> runOnUiThread { if (chatScope.alive && engine === turnEngine) setActivity(s) } }, attachments = atts, onCard = { c -> runOnUiThread { if (!chatScope.alive || engine !== turnEngine) return@runOnUiThread; val follow = nearBottom(); val at = list.indexOfChild(reply); list.addView(cardView(agent, c), if (at >= 0) at else list.childCount, cardLp()); headChar.react("love"); updated(follow) } }) { d ->
                    synchronized(acc) { acc.append(d) }
                    if (!got.getAndSet(true)) runOnUiThread { if (chatScope.alive && engine === turnEngine) { buddyStage.dismiss(); headChar.show(agent, "speaking") } }
                    if (scheduled.compareAndSet(false, true)) scroll.postDelayed(flush, 50)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!chatScope.alive || engine !== turnEngine) return@runOnUiThread
                    buddyStage.flash("error", UiText.text(this@chat, R.string.ui_1410a06bb9), 2400); headChar.tag = "x"; headChar.show(agent, "error"); headChar.react("surprised"); if (!got.get()) list.removeView(reply)
                    add(errorText(e) + UiText.text(this@chat, R.string.ui_8eb654d9af), false, error = true).setOnClickListener { v -> if (!busy) { list.removeView(v); submit(lastUser, retry = true) } }
                    bottom()
                }
            }
            localTurns.remove(turnEngine)
            if (!chatScope.alive || engine !== turnEngine) return@run
            runOnUiThread { flush.run() }
            if (got.get() && !turnEngine.isCancelled && visible && (!gateWanted() || pinUnlocked) && prefs.getBoolean("tts", false)) { val spoken = synchronized(acc) { acc.toString() }; runOnUiThread { if (chatScope.alive && engine === turnEngine) speak(spoken) } }
            runOnUiThread { if (!chatScope.alive || engine !== turnEngine) return@runOnUiThread; busy = false; Motion.hide(activity); updateAction(); if (headChar.tag != "x") { buddyStage.dismiss(); headChar.show(agent, "idle"); if (got.get()) headChar.react("giggle") }; if (!got.get() && reply.parent != null && reply.text.isEmpty()) list.removeView(reply) }
        }
    }
    sendBtn = send; stopBtn = iconButton("close", Theme.BAD, size = 52f) { if (hermesRuntime.active(chatId) != null) hermesRuntime.cancel(chatId) else engine?.cancel(); val m = UiText.text(this@chat, R.string.ui_406565f0d9); snack(m); buddyStage.flash("cancel", m, 2400) }.apply { visibility = View.GONE }
    send.setOnClickListener { submit(input.text.toString()) }
    input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { submit(input.text.toString()); true } else false }
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
    val listen = text(16f, 600, Theme.MUTED).keepCase().apply { visibility = View.GONE; setPadding(dpi(4f), dpi(6f), 0, dpi(6f)); maxLines = 2 }
    var voiceCancelled = false
    var recording = false
    var voiceActive = false
    var micPhase = MicPhase.LISTENING
    val voice = newMic({ if (!voiceCancelled) { listen.text = if (micPhase == MicPhase.TRANSCRIBING) it else UiText.text(this@chat, R.string.ui_60da80eba1, it); buddyStage.show(if (micPhase == MicPhase.TRANSCRIBING || !recording) "transcribing" else "listening", listen.text.toString()) } }, { if (!voiceCancelled) { voiceActive = false; recording = false; Motion.hide(listen); buddyStage.dismiss(); reviewVoice(it) } },
        { err -> if (!voiceCancelled) { voiceActive = false; recording = false; Motion.hide(listen); buddyStage.flash(if (err == null) "cancel" else "error", err ?: UiText.text(this@chat, R.string.ui_1b534b080c)); if (!busy) headChar.show(agent, "idle"); if (err != null) add(err, false, error = true) } })
    val suspendVoice = { voiceCancelled = true; voiceActive = false; recording = false; voice.cancel(); Motion.hide(listen); buddyStage.dismiss(); if (!busy) headChar.show(agent, "idle") }
    cancelActiveMic = suspendVoice
    voice.onPhase { phase -> micPhase = phase; if (!voiceCancelled && phase == MicPhase.TRANSCRIBING) buddyStage.show("transcribing", UiText.text(this@chat, R.string.ui_79b0b7c1f7)) }
    voice.onLevel { buddyStage.level(it) }
    onVoice = { if (!voiceCancelled) reviewVoice(it) }
    fun talk() {
        if (busy) return
        voiceCancelled = false
        if (voice.start()) { voiceActive = true; recording = true; listen.text = UiText.text(this@chat, R.string.ui_3afbd0834d); Motion.show(listen); buddyStage.show("listening", listen.text.toString()); headChar.show(agent, "listening") }
        else if (!voice.startFailed && packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppLanguage.locale(this).toLanguageTag()), 7)
        else if (!voice.startFailed && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) add(UiText.text(this@chat, R.string.ui_4d225fc3f1), false, error = true)
    }
    pttHandler = { e -> if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) talk() else if (e.action == KeyEvent.ACTION_UP && recording) { recording = false; buddyStage.show("transcribing", UiText.text(this@chat, R.string.ui_79b0b7c1f7)); voice.stop() }; true }
    // Gli agganci di QUESTA chat: quando una chat viene sostituita (rilettura da Hermes) o ne copre un'altra, la vecchia azzerava
    // quelli della nuova (PTT, voce, allegati morti). Ora ognuna toglie solo i suoi e li rimette quando torna in cima.
    val myVoice = onVoice; val myMic = cancelActiveMic; val myPtt = pttHandler; val myAttach = attachHook
    fun restoreHooks() { onVoice = myVoice; cancelActiveMic = myMic; pttHandler = myPtt; attachHook = myAttach }
    openAttach = { headChar.lookAt(-10f, 10f, 1200); sheet(UiText.text(this@chat, R.string.ui_0fd7b04a53), listOf(UiText.text(this@chat, R.string.ui_aaa045009b) to { pickPhoto() }, UiText.text(this@chat, R.string.ui_9ef61a89bf) to { pickFile(true) }, UiText.text(this@chat, R.string.ui_7656d6d3ad) to { pickFile(false) }), mapOf(UiText.text(this@chat, R.string.ui_aaa045009b) to "attach", UiText.text(this@chat, R.string.ui_9ef61a89bf) to "look", UiText.text(this@chat, R.string.ui_7656d6d3ad) to "note")) }
    val attachBtn = iconButton("attach", accent, filled = false, size = 48f) { openAttach() }
    // Un solo tasto d'azione a destra: microfono (tieni premuto e parla) a campo vuoto, invio con del testo, stop mentre lavora.
    val mic = iconButton("mic", accent, size = 52f) {}
    var voiceStartY = 0f
    fun cancelVoice() {
        voiceCancelled = true; recording = false; voice.cancel(); Motion.hide(listen); buddyStage.flash("cancel", UiText.text(this@chat, R.string.ui_4435ce3621))
        if (!busy) headChar.show(agent, "idle")
    }
    // TalkBack, tastiera e rotella non possono "tenere premuto": un clic avvia l'ascolto, il clic successivo lo ferma e trascrive.
    mic.setOnClickListener {
        if (recording && !voiceCancelled) { recording = false; buddyStage.show("transcribing", UiText.text(this@chat, R.string.ui_79b0b7c1f7)); voice.stop() } else talk()
    }
    mic.setOnTouchListener { v, e ->
        Motion.touch(v, e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { voiceStartY = e.rawY; talk() }
            android.view.MotionEvent.ACTION_MOVE -> if (!voiceCancelled) { buddyStage.followCancel((voiceStartY - e.rawY) / dp(64f)); if (voiceStartY - e.rawY > dp(64f)) cancelVoice() }
            android.view.MotionEvent.ACTION_UP -> if (!voiceCancelled && recording) { recording = false; buddyStage.show("transcribing", UiText.text(this@chat, R.string.ui_79b0b7c1f7)); voice.stop() }
            android.view.MotionEvent.ACTION_CANCEL -> cancelVoice()
        }
        v.isPressed = !voiceCancelled && (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_MOVE)
        true
    }
    bar.addView(attachBtn, 0, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) })
    val actions = FrameLayout(this)
    actions.addView(mic); actions.addView(send); actions.addView(stopBtn)
    bar.addView(actions, LinearLayout.LayoutParams(dpi(52f), dpi(52f)).apply { marginStart = dpi(6f) })
    var shownAction: View? = null
    updateAction = {
        val want: View = if (busy) stopBtn else if (input.text.isNotBlank() || pending.isNotEmpty()) send else mic
        if (want !== shownAction) {
            shownAction = want
            Motion.swap(listOf(mic, send, stopBtn), want)
        }
    }
    updateAction()

    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // Testata stabile fuori dall'area di lettura: nessun testo passa sotto i controlli.
    head.setBackgroundColor(Theme.BG)
    lateinit var wheelNudge: (Float) -> Unit
    lateinit var fingerDown: () -> Unit
    val stack2 = WheelFrame(this, { d -> wheelNudge(d * 1.8f) }, { fingerDown() })
    stack2.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    list.setPadding(0, dpi(4f), 0, dpi(8f))
    scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (nearBottom()) Motion.hide(latest) }
    // Scorrimento a molla: la rotella manda piccoli scatti e i tasti passi fissi; una molla critica li fonde in un movimento continuo (come la home).
    // Seguimento esponenziale (nessuna velocita' residua): la rotella arriva a gesti (ognuno parte da un punto fisso e va in una sola direzione),
    // e una molla con velocita' superava il bersaglio e tornava indietro. Qui la chat si avvicina al bersaglio e basta.
    var sPos = 0f; var sTarget = 0f; var sRunning = false; var sLast = 0L
    val sFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            if (!sRunning) return
            val dt = if (sLast == 0L) 1f / 60 else ((ns - sLast) / 1e9f).coerceIn(0f, 0.05f); sLast = ns
            sPos += (sTarget - sPos) * (1f - Math.exp(-dt * 16.0).toFloat())
            if (Math.abs(sTarget - sPos) < 0.6f) { sPos = sTarget; sRunning = false }
            scroll.scrollTo(0, Math.round(sPos))
            if (sRunning) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun stopSmooth() { sRunning = false; android.view.Choreographer.getInstance().removeFrameCallback(sFrame) }
    fun nudge(dy: Float) {
        if (!sRunning && Math.abs(scroll.scrollY - sPos) > 2f) { sPos = scroll.scrollY.toFloat(); sTarget = sPos }
        if (sRunning && Math.abs(scroll.scrollY - sPos) > 12f) { sPos = scroll.scrollY.toFloat(); sTarget = sPos }
        val max = (list.height - scroll.height).coerceAtLeast(0).toFloat()
        sTarget = (sTarget + dy).coerceIn(0f, max)
        if (!sRunning) { sRunning = true; sLast = 0L; android.view.Choreographer.getInstance().postFrameCallback(sFrame) }
    }
    wheelNudge = { d -> nudge(d) }; fingerDown = { stopSmooth() }
    col.addView(head); col.addView(stack2, LinearLayout.LayoutParams(-1, 0, 1f)); col.addView(latest); col.addView(chipsScroll); col.addView(buddyStage); col.addView(bar)
    bottom()
    // Bozza della chat: quello che stavi scrivendo torna quando la riapri.
    if (share == null && observeHsid == null) prefs.getString("draft_$chatId", null)?.takeIf { it.isNotBlank() }?.let { input.setText(it); input.setSelection(it.length) }
    if (share != null) { // da "Condividi": testo nella casella e allegati pronti, l'utente può aggiungere due parole e inviare
        if (share.text.isNotBlank()) { if (share.voiceDraft) reviewVoice(share.text) else input.setText(share.text) }
        share.files.forEach { pending.add(it) }; refreshChips()
    }
    // Chat sostituita (rilettura da Hermes): bozza e allegati non inviati passano dalla vecchia alla nuova invece di andare persi.
    chatHandoff?.takeIf { it.first == chatId }?.let { h ->
        chatHandoff = null
        if (h.second.isNotBlank()) { input.setText(h.second); input.setSelection(h.second.length) }
        if (h.third.isNotEmpty()) { h.third.forEach { pending.add(it) }; refreshChips() }
    }
    bindHermes = { task, reply ->
        stopTaskWatch?.invoke()
        var lastRevision = -1L
        var lastRenderedText = ""
        var errorShown = false
        var flushPosted = false
        var newest = task.progress.snapshot()
        fun renderTask(): Unit {
            flushPosted = false
            if (!chatScope.alive) return
            val snapshot = newest
            if (snapshot.revision <= lastRevision) return
            lastRevision = snapshot.revision
            val follow = nearBottom()
            busy = snapshot.status == TurnProgress.Status.RUNNING
            if (snapshot.text.isNotEmpty() && snapshot.text != lastRenderedText) {
                lastRenderedText = snapshot.text
                reply.setCompoundDrawablesRelative(null, null, null, null)
                setMsg(reply, snapshot.text, false); updated(follow)
            }
            when (snapshot.status) {
                TurnProgress.Status.RUNNING -> {
                    if (snapshot.activity.isNotBlank() || snapshot.text.isEmpty()) setActivity(snapshot.activity)
                    else { buddyStage.dismiss(); headChar.show(agent, "speaking") }
                }
                TurnProgress.Status.ERROR -> {
                    buddyStage.flash("error", UiText.text(this@chat, R.string.ui_21cb865bd3), 2600)
                    headChar.show(agent, "error")
                    if (!errorShown) {
                        errorShown = true
                        add(UiText.owned(this@chat, snapshot.error) + UiText.text(this@chat, R.string.ui_f819674502), false, error = true).setOnClickListener { openChat(agent, chatId) }
                    }
                }
                else -> { buddyStage.dismiss(); headChar.show(agent, "idle"); if (snapshot.status == TurnProgress.Status.DONE) { headChar.react("giggle"); autoRunActions(snapshot.text) } }
            }
            if (!busy) {
                Motion.hide(activity)
                if (snapshot.text.isEmpty() && reply.parent != null) list.removeView(reply)
            }
            updateAction()
        }
        stopTaskWatch = task.progress.subscribe { snapshot -> runOnUiThread {
            if (chatScope.alive && snapshot.revision >= newest.revision) {
                newest = snapshot
                if (!flushPosted) { flushPosted = true; scroll.postDelayed({ renderTask() }, 50) }
            }
        } }
    }
    val ongoing = hermesRuntime.active(chatId)
    if (ongoing != null || observeHsid != null) {
        busy = true; updateAction()
        val reply = add("", false).apply { setCompoundDrawablesRelativeWithIntrinsicBounds(TypingDots(this@chat), null, null, null) }
        bindHermes(ongoing ?: hermesRuntime.observe(agent, chatId, observeHsid!!), reply)
        bottom()
    }
    if (say != null) list.post { submit(say) }
    return Screen(page(col, 14), { e ->
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_1 || e.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) pttHandler?.invoke(e) ?: false
        else if (currentFocus is EditText) false
        else when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { nudge(-dpi(120f).toFloat()); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { nudge(dpi(120f).toFloat()); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { input.requestFocus(); true }
            else -> false
        }
    }, scope = chatScope, onShow = { restoreHooks() }, onHandOff = { chatHandoff = Triple(chatId, input.text.toString(), pending.toList()); pending.clear() }, onLeave = { prefs.edit().apply { val d = input.text.toString(); if (d.isBlank() || busy) remove("draft_$chatId") else putString("draft_$chatId", d) }.apply(); stopSmooth(); connectionAlive = false; stopConnectionWatch(); buddyStage.dismiss(); avatarSlot.removeCallbacks(collapseAvatar); avatarMotion?.cancel(); engine?.let { it.cancel(); localTurns.remove(it) }; stopTaskWatch?.invoke(); if (onVoice === myVoice) onVoice = null; if (cancelActiveMic === myMic) cancelActiveMic = null; if (pttHandler === myPtt) pttHandler = null; if (attachHook === myAttach) attachHook = null; pending.forEach { it.file.delete() }; pending.clear(); voiceCancelled = true; voice.cancel(); tts?.stop() }, isChat = true, chatKey = chatId, onSuspend = { if (voiceActive) suspendVoice() }, leaveGuard = { if (busy && !hermesMode()) { val now = android.os.SystemClock.uptimeMillis(); if (armedKey == "leave" && now - armedAt < 4000) false else { armedKey = "leave"; armedAt = now; val m = UiText.text(this@chat, R.string.ui_8cec984fbd); snack(m); buddyStage.flash("cancel", m, 3000); true } } else false })
}
