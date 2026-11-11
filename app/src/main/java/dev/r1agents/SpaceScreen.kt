package dev.r1agents

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.r1os.host.ui.*
import dev.r1agents.MainActivity.Screen

/** Pagina di un agente («spazio») e schermata della suoneria. Spostate da MainActivity. */
internal fun MainActivity.ringScreen(title: String, label: String, kind: String): Screen {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    val ringBuddy = companion(128f)?.also { it.setScenePose("waiting"); col.addView(it, LinearLayout.LayoutParams(dpi(128f), dpi(128f))) }
    if (ringBuddy != null && !packageName.endsWith(".qa") && !Motion.reduced(this)) { // allarmato: sorpresa all'arrivo, poi brividi e sguardi, al massimo 8 volte
        var rings = 0
        val alarmLoop = object : Runnable { override fun run() {
            if (!col.isAttachedToWindow || rings++ >= 8) return
            if (visible) { ringBuddy.react("shiver"); ringBuddy.lookAt(if (rings % 2 == 0) 22f else -22f, -6f, hold = Motion.FAST) }
            col.postDelayed(this, Motion.LOOP_SLOW * 3)
        } }
        col.postDelayed({ if (col.isAttachedToWindow) ringBuddy.react("surprised") }, Motion.BASE)
        col.postDelayed(alarmLoop, Motion.BASE + 900L)
    }
    col.addView(text(64f, 700).apply { text = title; gravity = Gravity.CENTER })
    col.addView(text(20f, 600, Theme.MUTED).keepCase().apply { text = label.ifBlank { if (kind == Sched.KIND_TIMER) UiText.text(this@ringScreen, R.string.ui_1bb7fcaedb) else UiText.text(this@ringScreen, R.string.ui_4b15f21af0) }; gravity = Gravity.CENTER; setPadding(0, dpi(6f), 0, dpi(28f)) })
    fun send(a: String) { startService(Intent(this, RingService::class.java).setAction(a).putExtra("label", label)); pop() }
    col.addView(button("stop", Theme.colorOf("green")) { send("stop") }, LinearLayout.LayoutParams(-1, -2))
    col.addView(button(UiText.text(this@ringScreen, R.string.ui_8d6a685440), toolAccent, primary = false) { send("snooze") }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    return Screen(page(col).also { it.tag = "ring" }, buddyContext = BuddySceneContext.ALERT)
}

internal fun MainActivity.space(start: Agent): Screen {
    val scope = Scope()
    var agent = start
    var showAll = false; var confirmRemove = false; var expandFrom = -1
    var live = emptyMap<String, String>()
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }
    val headerBuddy = companion(64f, start)!!
    val headerName = text(28f, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val headerSummary = text(16f, 650, Theme.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    // Come tutti i sotto menu: indietro, titolo, buddy a destra (qui il titolo e' il nome dell'agente con la sua descrizione sotto).
    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(70f); setPadding(0, 0, 0, dpi(6f))
        addView(iconButton("back", Theme.accent, filled = false) { pop() })
        addView(LinearLayout(this@space).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12f), 0, 0, 0); addView(headerName); addView(headerSummary) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(headerBuddy, LinearLayout.LayoutParams(dpi(64f), dpi(64f)).apply { marginStart = dpi(4f) })
    }
    val pageBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(header); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)) }
    var headerLook = ""
    val shown = 8
    fun render() {
        agent = db.agents().firstOrNull { it.id == agent.id } ?: return
        val accent = Theme.colorOf(agent.color)
        col.removeAllViews()
        headerName.text = agent.name
        headerSummary.text = agent.prompt.lineSequence().firstOrNull().orEmpty().ifBlank { UiText.text(this@space, R.string.ui_45c37524b3) }
        val look = "${agent.form}:${agent.color}:${agent.finish}:${agent.eyes}:${agent.wear}"
        if (look != headerLook) { headerBuddy.show(agent); headerLook = look }
        col.addView(row(UiText.text(this@space, R.string.ui_7f4849bfb0), accent, bold = true, icon = "plus") { push(chat(agent, db.newChat(agent.id))) })
        val chats = db.chats(agent.id).filter { it.count > 0 || it.title.isNotEmpty() }
        if (chats.isNotEmpty()) col.addView(section("chat"))
        val rowsBase = col.childCount
        (if (showAll) chats else chats.take(shown)).forEach { c ->
            col.addView(row(c.title.ifBlank { "chat" }, accent, sub = "${c.count} ${if (c.count == 1) UiText.text(this@space, R.string.ui_151a6c958d) else UiText.text(this@space, R.string.ui_947032528d)} · ${ago(c.updated)}" + (if (live[db.hsid(c.id)] in listOf("working", "waiting")) UiText.text(this@space, R.string.ui_4d3d99856c) else ""), icon = if (live[db.hsid(c.id)] in listOf("working", "waiting")) "spark" else "chat") { openChat(agent, c.id) }.apply { rowTitle(this).maxLines = 2 })
        }
        if (!showAll && chats.size > shown) col.addView(row(UiText.text(this@space, R.string.ui_6e3552c6eb), Theme.MUTED, sub = UiText.text(this@space, R.string.ui_abec4365e1, chats.size - shown)) { showAll = true; expandFrom = shown; render() })
        col.addView(section(UiText.text(this@space, R.string.ui_a299eba5bf)))
        val n = db.memories(agent.id).size
        if (agent.profile.isEmpty()) col.addView(row(UiText.text(this@space, R.string.ui_7333237b9c), accent, icon = "note", sub = if (n == 0) UiText.text(this@space, R.string.ui_7b938546c6) else "$n ${if (n == 1) "nota" else UiText.text(this@space, R.string.ui_c51048b732)}") { push(memoryScreen(agent)) })
        col.addView(row(UiText.text(this@space, R.string.ui_fd7448c50e), accent, icon = "look", sub = UiText.text(this@space, R.string.ui_e573d2350b)) { push(editor(agent)) })
        if (hermesMode() && agent.profile.isNotEmpty()) col.addView(row(UiText.text(this@space, R.string.ui_d2d5097861), accent, icon = "spark", sub = UiText.text(this@space, R.string.ui_7ca2785861, agent.name)) { push(skillsScreen(agent)) })
        col.addView(row(UiText.text(this@space, R.string.ui_07832915bd), accent, icon = "sliders", sub = UiText.text(this@space, R.string.ui_2ad1ad3777)) { push(manageChats(agent)) })
        val all = visibleAgents(); val at = all.indexOfFirst { it.id == agent.id }
        if (at > 0) col.addView(row(UiText.text(this@space, R.string.ui_a61739c1b8), Theme.MUTED, icon = "chevron") { db.move(agent.id, -1, all.map { it.id }); homeIndex = (at - 1).coerceAtLeast(0); render() })
        if (at in 0 until all.size - 1) col.addView(row(UiText.text(this@space, R.string.ui_18e4c8d6fb), Theme.MUTED, icon = "chevron") { db.move(agent.id, 1, all.map { it.id }); homeIndex = at + 1; render() })
        if (agent.profile.isEmpty()) col.addView(row(if (confirmRemove) UiText.text(this@space, R.string.ui_39ced40b15) else UiText.text(this@space, R.string.ui_235936fba8), Theme.BAD, icon = "close", sub = if (confirmRemove) UiText.text(this@space, R.string.ui_ff36018e8f) else null) {
            if (!confirmRemove) { confirmRemove = true; render() } else { db.routines().filter { it.agentId == agent.id }.forEach { Sched.cancel(this, Sched.KIND_ROUTINE, it.id) }; db.delete(agent.id); homeIndex = 0; pop() }
        })
        val firstChatRow = if (chats.isNotEmpty()) col.getChildAt(rowsBase) else null
        groupRows(col) // come le altre pagine: righe vicine in un'unica scheda con divisori e freccia
        // Chat appena mostrate con «chat meno recenti»: entrano a cascata, dentro la scheda delle chat.
        if (expandFrom >= 0) { val chatCard = firstChatRow?.parent as? LinearLayout ?: col; Motion.stagger(chatCard, dp(10f), from = expandFrom.coerceAtMost(chatCard.childCount)); expandFrom = -1 }
    }
    render()
    if (hermesMode() && agent.profile.isNotEmpty()) Io.run { try { syncSessions(agent) } catch (e: Exception) { }; live = dash.live(); runOnUiThread { if (scope.alive) render() } }
    return Screen(page(pageBody), onShow = { confirmRemove = false; render() }, scope = scope)
}

/** Il contesto del telefono arriva agli agenti solo se l'utente lo ha acceso in Sicurezza. */
