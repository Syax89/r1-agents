package dev.r1agents

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.r1os.host.ui.*
import dev.r1agents.MainActivity.Screen
import dev.r1agents.MainActivity.SheetItem

/** Parti di interfaccia condivise spostate da MainActivity: sfondi, fogli, messaggi brevi, allegati, strumenti. */
internal fun MainActivity.softPalette(name: String?): List<Int> =
    (palettes[name] ?: palettes.getValue("sunset")).map { Theme.mix(Theme.BG, Color.parseColor(it), if (Theme.light) 0.4f else 0.55f) }

internal fun MainActivity.bgDrawable(screen: String): android.graphics.drawable.Drawable {
    val cols = softPalette(prefs.getString("bgp_$screen", "sunset"))
    return when (prefs.getString("bg_$screen", "plain")) {
        "tint" -> android.graphics.drawable.ColorDrawable(Theme.mix(Theme.BG, Theme.accent, 0.16f))
        "gradient" -> android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, cols.toIntArray())
        "mesh" -> MeshDrawable(Theme.BG, cols)
        "photo" -> java.io.File(filesDir, "bg_$screen.jpg").takeIf { it.exists() }?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.let { PhotoDrawable(it, prefs.getFloat("bgd_$screen", 0.4f)) } }
            ?: android.graphics.drawable.ColorDrawable(Theme.BG)
        else -> android.graphics.drawable.ColorDrawable(Theme.BG)
    }
}

/** Foto scelta: ridotta a 720 px sul lato lungo e salvata in JPEG (qualità 80), per non pesare sul dispositivo. */
internal fun MainActivity.savePhoto(uri: android.net.Uri, screen: String) {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
    val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
    val k = 720f / maxOf(bmp.width, bmp.height)
    val out = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
    java.io.File(filesDir, "bg_$screen.jpg").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
    prefs.edit().putString("bg_$screen", "photo").putString("reopen", "look").apply(); recreate()
}

/** Un messaggio breve in basso: pillola chiara sul tema scuro (e scura sul chiaro), con ingresso a molla; sparisce da sola. */
/** Errore in parole semplici: causa nota se riconosciuta, altrimenti il messaggio originale. */
internal fun MainActivity.errorText(e: Throwable): String = when (ErrorKind.of(e)) {
    ErrorKind.KEY -> UiText.text(this, R.string.ui_6503aaa0d4)
    ErrorKind.NETWORK -> UiText.text(this, R.string.ui_db32442bd0)
    ErrorKind.TIMEOUT -> UiText.text(this, R.string.ui_423e5043b2)
    ErrorKind.RATE -> UiText.text(this, R.string.ui_0d3b9428e5)
    ErrorKind.SERVER -> UiText.text(this, R.string.ui_a8a7633f72)
    ErrorKind.CONFIG -> UiText.text(this, R.string.ui_7726650957)
    ErrorKind.OTHER -> UiText.message(this, e.message) ?: UiText.text(this, R.string.ui_00ea95d6d1)
}

internal fun MainActivity.copyText(t: String) {
    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("codice", t))
    snack(UiText.text(this, R.string.ui_b6ec5021d5))
}

/** Esegue [f] quando la vista e' in finestra: un risultato (o un errore) che arriva prima dell'ingresso della pagina non va perso. */
internal fun MainActivity.whenAttached(v: View, f: () -> Unit) {
    if (v.isAttachedToWindow) { f(); return }
    v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(x: View) { v.removeOnAttachStateChangeListener(this); f() }
        override fun onViewDetachedFromWindow(x: View) {}
    })
}

/** Azione distruttiva: il primo tocco avvisa, il secondo entro 4 secondi la esegue. */
internal fun MainActivity.confirmTap(key: String, act: () -> Unit) {
    val now = android.os.SystemClock.uptimeMillis()
    if (armedKey == key && now - armedAt < 4000) { armedKey = null; act() }
    else { armedKey = key; armedAt = now; snack(UiText.text(this, R.string.ui_af2b0de88b)) }
}

internal fun MainActivity.snack(message: String?, long: Boolean = false) {
    val text = message?.takeIf { it.isNotBlank() } ?: return
    root.post {
        root.removeCallbacks(snackHide); snackView?.let { root.removeView(it) }
        val v = text(15f, 650, Theme.BG).keepCase().apply {
            this.text = UiText.message(this@snack, text) ?: text; gravity = Gravity.CENTER; maxLines = 4
            setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f)); background = roundRect(Theme.FG, dp(22f)); alpha = 0f; translationY = dp(22f); isClickable = true
            setOnClickListener { root.removeCallbacks(snackHide); snackHide.run() }
        }
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(96f); marginStart = dpi(20f); marginEnd = dpi(20f) })
        v.animate().alpha(1f).translationY(0f).setDuration(Motion.BASE).setInterpolator(Theme.POP).start()
        snackView = v
        root.postDelayed(snackHide, if (long) 4200L else 2400L)
    }
}

internal fun MainActivity.hermesMode() = settings.provider.id == "dashboard"

internal fun MainActivity.visibleAgents(): List<Agent> = db.agents().filter { if (hermesMode()) it.profile.isNotEmpty() else it.profile.isEmpty() }

/** Legge i profili da Hermes e li rispecchia come agenti (l'aspetto resta locale e dipende dal nome). */
internal fun MainActivity.syncProfiles(scope: Scope, error: (String) -> Unit, done: () -> Unit) {
    Io.run {
        try {
            val forms = CharacterView.FORMS; val colors = Theme.COLORS.keys.toList()
            for (p in dash.profiles()) {
                val h = p.name.hashCode() and 0x7fffffff; val name = p.display.ifBlank { p.name }
                val ex = db.agentByProfile(p.name)
                if (ex == null) db.save(Agent(0, name, p.description, "", forms[h % forms.size], colors[(h / 7) % colors.size], "soft", "classic", "", p.name))
                else if (ex.name != name || ex.prompt != p.description) { ex.name = name; ex.prompt = p.description; db.save(ex) }
            }
            runOnUiThread { if (scope.alive) done() }
        } catch (e: Exception) { runOnUiThread { if (scope.alive) error(errorText(e)) } }
    }
}

/** Legge le sessioni di un profilo e le rispecchia come chat (bloccante: da un thread). */
internal fun MainActivity.syncSessions(a: Agent) {
    for (s in dash.sessions(a.profile)) db.upsertChat(a.id, s.id, s.title.ifBlank { s.preview }.replace('\n', ' ').take(60).ifBlank { "chat" }, s.startedAt, s.count)
    sessionSyncAt[a.id] = System.currentTimeMillis()
}

internal fun MainActivity.loadingScreen(a: Agent, t: String): Screen {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    val loadingBuddy = CharacterView(this, dpi(92f)).also { it.show(a, "thinking") }
    col.addView(loadingBuddy)
    if (!packageName.endsWith(".qa")) BuddyCuriosity.start(loadingBuddy, 2.5f) { isCurrentSurface(col) } // sta cercando: ogni tanto si guarda intorno
    col.addView(text(18f, 600, Theme.MUTED).keepCase().apply { text = t; gravity = Gravity.CENTER; setPadding(0, dpi(10f), 0, 0) })
    return Screen(page(col), buddyContext = BuddySceneContext.LIVE)
}

/** Apre una chat; se è di Hermes ne legge prima la cronologia dal dashboard. */
internal fun MainActivity.openChat(a: Agent, chatId: Long, say: String? = null, share: Share? = null) {
    if (hermesRuntime.active(chatId) != null) { push(chat(a, chatId, say, share)); return }
    val hsid = db.hsid(chatId)
    if (!(hermesMode() && a.profile.isNotEmpty() && hsid.isNotEmpty())) { push(chat(a, chatId, say, share)); return }
    // Chat gia' letta altre volte: si apre SUBITO con quello che c'e' in locale e la cronologia di Hermes si rilegge in secondo piano; se cambia (o la
    // sessione sta lavorando altrove) la chat si aggiorna da sola, conservando la bozza.
    if (db.messagesPage(chatId, 1, null).isNotEmpty()) {
        push(chat(a, chatId, say, share))
        Io.run {
            val before = chatSignature(chatId)
            var err: String? = null; var working = false
            try { db.replaceMessages(a.id, chatId, dash.open(chatId, hsid, a.profile)); working = dash.liveChecked()[hsid] in listOf("working", "waiting") } catch (e: Exception) { err = UiText.message(this@openChat, e.message) }
            val changed = chatSignature(chatId) != before
            runOnUiThread {
                if (stack.lastOrNull()?.chatKey == chatId && (changed || working)) { stack.lastOrNull()?.onHandOff?.invoke(); replaceTop(chat(a, chatId, null, null, observeHsid = if (working) hsid else null)) }
                err?.let { snack(UiText.text(this@openChat, R.string.ui_cd083a17e7, it), long = true) }
            }
        }
        return
    }
    // Prima volta: la home resta (con l'avatar che "pensa") e la chat arriva con la dissolvenza e il volo; senza volo, la pagina "carico".
    if (!opening.add(chatId)) return // doppio tocco: una sola apertura alla volta
    val flying = hero != null
    val origin = stack.lastOrNull()
    val loading = if (flying) null else loadingScreen(a, UiText.text(this@openChat, R.string.ui_6c766bf934)).also { push(it) }
    Io.run {
        var err: String? = null
        var working = false
        try { db.replaceMessages(a.id, chatId, dash.open(chatId, hsid, a.profile)); working = dash.liveChecked()[hsid] in listOf("working", "waiting") } catch (e: Exception) { err = UiText.message(this@openChat, e.message) }
        runOnUiThread {
            opening.remove(chatId)
            // Risposta tardiva: se nel frattempo l'utente è andato altrove la chat non si apre sopra la sua pagina.
            val stillHere = !isDestroyed && stack.lastOrNull() === origin
            if (loading == null) { if (err == null && stillHere) push(chat(a, chatId, say, share, observeHsid = if (working) hsid else null)) else hero = null }
            else if (stack.lastOrNull() === loading) replaceTop(chat(a, chatId, say, share, observeHsid = if (working) hsid else null))
            err?.let { snack(UiText.text(this@openChat, R.string.ui_cd083a17e7, it), long = true) }
        }
    }
}

/** Impronta del contenuto locale di una chat (ultimi messaggi): serve a capire se la rilettura da Hermes ha cambiato qualcosa. */
internal fun MainActivity.chatSignature(chatId: Long): String = db.messagesPage(chatId, 3, null).joinToString("|") { it.second + it.third.hashCode() }

/** L'ultima chat dell'agente (per gli agenti di Hermes: dopo aver riletto le sessioni). */
internal fun MainActivity.openLatest(a: Agent, say: String? = null, share: Share? = null) {
    if (!(hermesMode() && a.profile.isNotEmpty())) { push(chat(a, db.lastChat(a.id) ?: db.newChat(a.id), say, share)); return }
    // Sessioni lette da poco: si apre subito l'ultima chat nota e l'elenco si aggiorna in secondo piano.
    val known = db.lastChat(a.id)
    if (known != null && System.currentTimeMillis() - (sessionSyncAt[a.id] ?: 0L) < 120_000L) {
        Io.run { try { syncSessions(a) } catch (e: Exception) { } }
        openChat(a, known, say, share); return
    }
    if (!opening.add(-a.id)) return // doppio tocco sullo stesso agente
    val flying = hero != null
    val origin = stack.lastOrNull()
    val loading = if (flying) null else loadingScreen(a, UiText.text(this@openLatest, R.string.ui_d4758167e0, a.name)).also { push(it) }
    Io.run {
        try { syncSessions(a) } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@openLatest, R.string.ui_af1c39a0e8, UiText.message(this@openLatest, e.message)), long = true) } }
        val id = db.lastChat(a.id) ?: db.newChat(a.id)
        runOnUiThread {
            opening.remove(-a.id)
            if (isDestroyed) return@runOnUiThread
            if (loading == null) { if (stack.lastOrNull() === origin) openChat(a, id, say, share) else hero = null }
            else if (stack.lastOrNull() === loading) { pop(); openChat(a, id, say, share) }
        }
    }
}

internal fun MainActivity.removeChat(agent: Agent, id: Long, done: () -> Unit = {}) {
    if (hermesRuntime.active(id) != null) { snack(UiText.text(this@removeChat, R.string.ui_8bd1630032), long = true); return }
    val hs = db.hsid(id)
    if (hs.isEmpty() || agent.profile.isEmpty()) { dash.forget(id); db.deleteChat(id); done(); return }
    // Prima l'esito remoto: se Hermes rifiuta (sessione ancora aperta) il record locale resta.
    Io.run {
        try { dash.delete(hs, agent.profile); dash.forget(id); db.deleteChat(id); runOnUiThread { done() } }
        catch (e: Exception) { runOnUiThread { done(); snack(UiText.message(this@removeChat, e.message) ?: UiText.text(this@removeChat, R.string.ui_0f2784f610), long = true) } }
    }
}

internal fun MainActivity.root(): Screen { rootProvider = settings.provider.id; return rootScreen() }

internal fun MainActivity.rootScreen(): Screen = if (prefs.getString("start", "home") == "glance") glance() else home()

internal fun MainActivity.pickPhoto(requestCode: Int = 9) {
    val f = java.io.File(java.io.File(cacheDir, "photos").also { it.mkdirs() }, "foto-${System.currentTimeMillis()}.jpg")
    photoFile = f
    if (requestCode == 9) photoAttachTarget = attachHook
    if (requestCode == 15) prefs.edit().putString("pending_qr_photo", f.path).apply()
    val uri = Uri.parse("content://${packageName}.photos/${f.name}")
    val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    i.clipData = android.content.ClipData.newRawUri("foto", uri)
    if (i.resolveActivity(packageManager) == null) { snack(UiText.text(this@pickPhoto, R.string.ui_1fe81db21a), long = true); return }
    @Suppress("DEPRECATION") startActivityForResult(i, requestCode)
}

internal fun MainActivity.pickFile(imagesOnly: Boolean) {
    fileAttachTarget = attachHook
    @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (imagesOnly) "image/*" else "*/*"), 10)
}

internal fun MainActivity.handleShare(i: Intent) {
    val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
    val uris = ArrayList<Uri>()
    (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
    i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
    i.action = null
    Io.run {
        val files = uris.mapNotNull { runCatching { Attach.fromUri(this, it) }.getOrNull() }.filter { it.kind != Kind.OTHER }
        runOnUiThread {
            val agents = visibleAgents()
            val share = Share(text, files)
            when {
                text.isBlank() && files.isEmpty() -> snack(UiText.text(this@handleShare, R.string.ui_8078441d49), long = true)
                agents.isEmpty() -> snack(UiText.text(this@handleShare, R.string.ui_62dde8cda6), long = true)
                agents.size == 1 -> openLatest(agents[0], null, share)
                else -> sheet(UiText.text(this@handleShare, R.string.ui_263a8abb66), agents.map { a -> a.name to { openLatest(a, null, share) } })
            }
        }
    }
}

/** Intestazione comune a ogni schermata: tasto indietro (l'R1 non ne ha uno) e titolo. */
internal fun MainActivity.framed(title: String, body: View, trailing: View? = null, buddyHeader: Boolean = true, buddyOverflow: Boolean = false): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    // Solo le liste con il buddy fisso (listPage): il buddy sta SOPRA la barra del titolo. Le altre pagine tagliano il contenuto che scorre sotto la barra.
    clipChildren = !buddyOverflow; clipToPadding = !buddyOverflow
    // Stessa altezza con o senza il buddy piccolo: il titolo sta alla stessa quota in tutti i sotto menu.
    val head = LinearLayout(this@framed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(70f); setPadding(0, 0, 0, dpi(6f)) }
    head.addView(iconButton("back", Theme.accent, filled = false) { pop() })
    head.addView(text(28f, 700).apply { letterSpacing = -0.01f; text = title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dpi(12f), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
    trailing?.let { head.addView(it) }
    if (buddyHeader && body.findViewWithTag<CharacterView>("scene-buddy") == null) companion(64f)?.let { cv -> head.addView(cv, LinearLayout.LayoutParams(dpi(64f), dpi(64f)).apply { marginStart = dpi(4f) }) }
    addView(head); addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
}

internal fun MainActivity.listPage(title: String, onLeave: (() -> Unit)? = null, context: BuddySceneContext = BuddySceneContext.READ, onSuspend: (() -> Unit)? = null, build: (LinearLayout) -> Unit): Screen {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }
    val pinned = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
    scroll.buddyFx.root = pinned
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; addView(pinned, LinearLayout.LayoutParams(-1, -2)); addView(FrameLayout(this@listPage).apply { clipChildren = true; setBackgroundColor(Theme.BG); addView(scroll, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f)) }   /* sfondo opaco: la lista copre la metà bassa del buddy e la sfumatura va verso il colore pagina, non verso il buddy */
    var rendered = false
    fun render() {
        if (rendered && !Motion.reduced(this@listPage)) { scroll.animate().cancel(); scroll.alpha = .55f; scroll.animate().alpha(1f).setDuration(Motion.FAST).setInterpolator(Theme.EASE_OUT).start() } // il contenuto si riassesta invece di tagliare di netto
        val firstRender = !rendered
        rendered = true
        col.removeAllViews()
        build(col)
        groupRows(col) // righe vicine in un'unica scheda con divisori
        if (firstRender) unfold(col) // all'apertura le voci si dispiegano a fisarmonica
        pinned.removeAllViews()
        (0 until col.childCount).map { col.getChildAt(it) }.firstOrNull { it.tag == "scene-companion-row" }?.let { row ->
            val height = row.layoutParams.height; col.removeView(row); pinned.addView(row, FrameLayout.LayoutParams(-1, height))
        }
        col.addView(View(this), LinearLayout.LayoutParams(-1, dpi(24f)))
    }
    // La lista si prepara prima dell'ingresso; costruirla nel primo frame interrompeva la transizione.
    render()
    body.post { // buddy piccolo in testata: ogni tanto una occhiata, mai invadente (passo lungo)
        if (body.findViewWithTag<CharacterView>("scene-companion-row") == null && !packageName.endsWith(".qa")) {
            var up: View? = body
            repeat(3) { up = up?.parent as? View; up?.findViewWithTag<CharacterView>("scene-buddy")?.let { cv -> BuddyCuriosity.start(cv, 8f) { visible && isCurrentSurface(body) && BuddyEntrancesIdle.ok() }; return@post } }
        }
    }
    return Screen(page(framed(title, body, buddyOverflow = true)), onShow = ::render, onLeave = onLeave, buddyContext = context, onSuspend = onSuspend)
}

/** Due schede per riga (l'ultima, se dispari, a tutta larghezza). */
internal fun MainActivity.grid(items: List<View>): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    var i = 0
    while (i < items.size) {
        val r = LinearLayout(this@grid).apply { isBaselineAligned = false }
        if (i + 1 < items.size) {
            r.addView(items[i], LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dpi(4f) })
            r.addView(items[i + 1], LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dpi(4f) }); i += 2
        } else { r.addView(items[i], LinearLayout.LayoutParams(-1, -2)); i++ }
        addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
    }
}

/** Due pulsanti affiancati. */
internal fun MainActivity.pairRow(a: View, b: View): View = LinearLayout(this).apply {
    addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpi(4f) })
    addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(4f) })
}

internal fun MainActivity.menuSheet(agent: Agent?) {
    companionAgent = agent ?: companionAgent
    sheetPanel("menu", peek = agent) { col, _, then ->
        col.addView(grid(listOfNotNull(
            agent?.let { a -> tile("agent", UiText.text(this@menuSheet, R.string.ui_1f5f0d75b2), a.name, Theme.colorOf(a.color)) { then { push(space(a)) } } },
            tile("plus", UiText.text(this@menuSheet, R.string.ui_b1a1efd9f5), UiText.text(this@menuSheet, R.string.ui_7bdba79e65), Theme.colorOf("green")) { then { push(editor(null)) } },
            tile("timer", UiText.text(this@menuSheet, R.string.ui_6bceb5c35b), UiText.text(this@menuSheet, R.string.ui_fa1f94b99f), Theme.colorOf("amber")) { then { push(tools()) } },
            tile("data", UiText.text(this@menuSheet, R.string.ui_7d1043473d), UiText.text(this@menuSheet, R.string.ui_be6f615b92), Theme.colorOf("teal")) { then { push(appsScreen()) } },
            tile("sliders", UiText.text(this@menuSheet, R.string.ui_0c375bfee9), UiText.text(this@menuSheet, R.string.ui_6a107bf8d4), Theme.colorOf("blue")) { then { push(settingsScreen()) } },
            tile("recents", UiText.text(this@menuSheet, R.string.ui_950486e052), UiText.text(this@menuSheet, R.string.ui_b106e64b3f), Theme.colorOf("purple")) { then { openSwitcher() } },
        )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
    }
}
