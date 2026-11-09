package dev.r1agents

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Regressioni aggiuntive: solo pacchetto QA, nessun microfono o servizio remoto. */
internal class DevelopmentQa(private val test: Instrumentation) {
    private val ctx get() = test.targetContext
    private fun ui(action: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun step(s: String) = test.sendStatus(1, Bundle().apply { putString("stream", "$s\n") })
    private fun shot(name: String) {
        val image = test.uiAutomation.takeScreenshot() ?: error("Screenshot assente")
        try { File(ctx.getExternalFilesDir(null), "development-qa/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
    fun run() {
        check(ctx.packageName.endsWith(".qa"))
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        check(power.isInteractive) { "Riattiva e sblocca il Rabbit" }
        @Suppress("DEPRECATION") val awake = power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "r1agents.qa:development")
        awake.acquire(180000)
        val prefs = ctx.getSharedPreferences("settings", 0)
        val db = Db.get(ctx)
        var activity: MainActivity? = null
        try {
            prefs.edit().putString("dev_actions", "ask").putString("provider", "custom").putString("start", "home").remove("app_pin_hash").remove("running_hermes_chats").commit()
            db.agents().forEach { db.delete(it.id) }
            val agent = Agent(0, "kiwi", "", "", "blob", "green").also { db.save(it) }
            val target = Agent(0, "luna", "", "", "orb", "purple").also { db.save(it) }
            val chat = db.newChat(agent.id)
            val before = db.messages(chat).size
            val cancelled = Engine(db, Settings(prefs, "custom"), NousAuth(prefs), HermesRuntime.get(ctx).dash)
            cancelled.cancel()
            cancelled.turn(agent, chat, "NON INVIARE") { error("Evento dopo annullamento") }
            check(db.messages(chat).size == before) { "Un turno annullato prima dell'avvio ha salvato un messaggio" }
            step("annullamento prima dell'avvio senza invio né persistenza: OK")
            LocalTurnQa.run(ctx, agent)
            step("SSE locale: stop durante streaming, provider fissato e turno successivo indipendente: OK")
            // Buddy legato allo scroll: affonda a metà, si offende se tirato troppo, un tocco perdona (BuddyScrollFx).
            prefs.edit().putString("app_language", "it").putString("look_mode", "dark").commit()
            activity = test.startActivitySync(Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            run { val ready = System.currentTimeMillis() + 6000; var ok = false
                while (!ok && System.currentTimeMillis() < ready) { ui { ok = views(activity!!.window.decorView).let { v -> v.filterIsInstance<R1ScrollView>().any { it.isShown } && v.filterIsInstance<dev.r1os.host.ui.CharacterView>().any { it.isShown && it.tag == "scene-buddy" } } }; Thread.sleep(100) } }
            Thread.sleep(500)
            lateinit var fxScroll: R1ScrollView; lateinit var fxBuddy: dev.r1os.host.ui.CharacterView
            ui { val d = activity!!.window.decorView; fxScroll = views(d).filterIsInstance<R1ScrollView>().first { it.isShown }; fxBuddy = views(d).filterIsInstance<dev.r1os.host.ui.CharacterView>().first { it.isShown && it.tag == "scene-buddy" }
                check(fxBuddy.alpha > .99f && fxBuddy.scaleX > .99f) { "Buddy non intero in cima" } }
            ui { fxScroll.scrollTo(0, 600) }; Thread.sleep(400)
            ui { check(fxBuddy.translationY > ctx.resources.displayMetrics.density * 4f && fxBuddy.alpha > .9f) { "Il buddy non affonda a metà scorrendo: ty=${fxBuddy.translationY} alpha=${fxBuddy.alpha}" } }
            ui { fxScroll.scrollTo(0, 0) }; Thread.sleep(900)
            ui { check(fxBuddy.scaleX > .98f && fxBuddy.alpha > .98f) { "Il buddy non torna intero in cima: ${fxBuddy.scaleX}/${fxBuddy.alpha}" } }
            val pullPx = ctx.resources.displayMetrics.density * 170f
            repeat(2) { ui { fxScroll.buddyFx.pull(pullPx) }; Thread.sleep(150); ui { check(fxBuddy.scaleY > 1.1f && fxBuddy.scaleX < 1f) { "Tirando il buddy non si allunga" } }; ui { fxScroll.buddyFx.calm() }; Thread.sleep(500) }
            check(!BuddyMood.sulking()) { "Offeso troppo presto" }
            ui { fxScroll.buddyFx.pull(pullPx) }; Thread.sleep(150); ui { fxScroll.buddyFx.calm() }
            check(BuddyMood.sulking()) { "Dopo tre tiri troppo forti non fa il broncio" }
            ui { fxBuddy.performClick() }
            check(!BuddyMood.sulking()) { "Un tocco non lo fa perdonare" }
            // Repertorio di comparse e sparizioni (BuddyEntrances): ognuna parte, finisce e rimette la riga a riposo.
            lateinit var seekRow: View
            ui { seekRow = views(activity!!.window.decorView).first { it.tag == "scene-companion-row" } }
            for (variant in BuddyEntrances.IN + BuddyEntrances.OUT) {
                var started = false
                ui { seekRow = views(activity!!.window.decorView).first { it.tag == "scene-companion-row" }; started = BuddyEntrances.play(seekRow, variant) } // la pagina può ricostruirsi: riprendo la riga attuale
                check(started) { "Animazione ${variant.id} non partita" }
                Thread.sleep(150)
                ui { check(seekRow.translationX != 0f || seekRow.translationY != 0f || seekRow.scaleX != 1f || seekRow.rotation != 0f || seekRow.alpha != 1f || variant.id == "pop") { "${variant.id}: nessun movimento a metà corsa" } }
                Thread.sleep(3050)
                ui { if (variant in BuddyEntrances.OUT) BuddyEntrances.rest(seekRow) }
                ui { check(seekRow.translationX == 0f && seekRow.translationY == 0f && seekRow.scaleX == 1f && seekRow.rotation == 0f && seekRow.alpha == 1f) { "${variant.id}: la riga non torna a riposo" } }
            }
            var seeking = false; ui { seekRow = views(activity!!.window.decorView).first { it.tag == "scene-companion-row" }; seeking = BuddyEntrances.hideAndSeek(seekRow) }; check(seeking)
            Thread.sleep(5200)
            ui { check(seekRow.translationX == 0f && seekRow.translationY == 0f && seekRow.alpha == 1f && seekRow.scaleX == 1f) { "Nascondino: il buddy non è tornato" } }
            step("repertorio buddy: ${BuddyEntrances.IN.size} comparse e ${BuddyEntrances.OUT.size} sparizioni, nascondino compreso: OK")
            // Richiesta di permesso di Hermes: il buddy sobbalza (faccia «surprised») poco dopo l'apertura.
            ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_req", "approval")) }
            var surprised = false; val until = System.currentTimeMillis() + 4500
            while (!surprised && System.currentTimeMillis() < until) { ui { surprised = views(activity!!.window.decorView).filterIsInstance<dev.r1os.host.ui.CharacterView>().any { it.expression == "surprised" } }; Thread.sleep(60) }
            check(surprised) { "Alla richiesta di permesso il buddy non sobbalza" }
            step("richiesta Hermes: il buddy sobbalza all'apertura: OK")
            // Traduttore: senza chiave l'errore fa sobbalzare il buddy.
            ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "translator")) }
            Thread.sleep(1500)
            ui {
                val decor = activity!!.window.decorView
                views(decor).filterIsInstance<EditText>().first { it.isShown }.setText("ciao")
                views(decor).filterIsInstance<android.widget.TextView>().first { it.isShown && it.text.toString() == "traduci" }.performClick()
            }
            var trSurprised = false; val trUntil = System.currentTimeMillis() + 5000
            while (!trSurprised && System.currentTimeMillis() < trUntil) { ui { trSurprised = views(activity!!.window.decorView).filterIsInstance<dev.r1os.host.ui.CharacterView>().any { it.expression == "surprised" } }; Thread.sleep(40) }
            check(trSurprised) { "Errore del traduttore: il buddy non sobbalza" }
            step("traduttore: l'errore fa sobbalzare il buddy: OK")
            ui { activity!!.onBackPressed() }; Thread.sleep(900)
            // Editor agente: scrivendo, gli occhi si spostano lungo la riga.
            ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "agent_new")) }
            Thread.sleep(1800)
            var moved = false
            Thread.sleep(1200) // i gesti d'evento hanno un limite di frequenza: lascio passare l'ultimo
            for (attempt in 1..3) {
                if (moved) break
                ui { val field = views(activity!!.window.decorView).filterIsInstance<EditText>().first(); field.setText("un nome un po' più lungo del solito".take(8 + attempt * 9)) }
                val edUntil = System.currentTimeMillis() + 2500
                while (!moved && System.currentTimeMillis() < edUntil) { ui { moved = views(activity!!.window.decorView).filterIsInstance<dev.r1os.host.ui.CharacterView>().any { kotlin.math.abs(it.eyeOffsetX) > 1f } }; Thread.sleep(40) }
            }
            check(moved) { "Editor agente: gli occhi non seguono il testo" }
            step("editor agente: scrivendo gli occhi seguono il testo: OK")
            // Routine locali: riattivando l'interruttore il buddy fa l'occhiolino.
            val routine = Routine(0, agent.id, "QA routine", "x", 8, 0, 31, true).also { db.save(it) }
            try {
                ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "routines")) }
                Thread.sleep(1800)
                fun toggle() = ui { views(activity!!.window.decorView).filterIsInstance<SwitchView>().first { it.isShown }.performClick() }
                toggle(); Thread.sleep(1700) // spenta: nessun gesto
                toggle() // riaccesa: occhiolino
                var wink = false; val wUntil = System.currentTimeMillis() + 3000
                while (!wink && System.currentTimeMillis() < wUntil) { ui { wink = views(activity!!.window.decorView).filterIsInstance<dev.r1os.host.ui.CharacterView>().any { it.expression == "wink" } }; Thread.sleep(40) }
                check(wink) { "Routine riattivata: il buddy non fa l'occhiolino" }
                step("routine locali: riattivando fa l'occhiolino: OK")
            } finally { db.deleteRoutine(routine.id) }
            ui { activity!!.onBackPressed() }; Thread.sleep(700)
            // Job di Hermes (server simulato): riattivando l'interruttore il buddy fa l'occhiolino.
            val jobMock = MockHermes()
            val savedProvider = prefs.getString("provider", "custom")
            try {
                prefs.edit().putString("provider", "dashboard").commit()
                HermesRuntime.get(ctx).dash.reconfigure("http://127.0.0.1:${jobMock.port}", "qa", "qa")
                jobMock.jobs["job-1"] = JSONObject().put("id", "job-1").put("name", "Prova UI").put("prompt", "ricorda").put("schedule_display", "0 9 * * *").put("enabled", true).put("state", "scheduled").put("profile", "").put("next_run_at", System.currentTimeMillis() / 1000 + 3 * 3600)
                ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "jobs")) }
                var found = false; val jUntil = System.currentTimeMillis() + 8000
                while (!found && System.currentTimeMillis() < jUntil) { ui { found = views(activity!!.window.decorView).any { it is SwitchView && it.isShown } }; Thread.sleep(150) }
                check(found) { "Job Hermes: nessun interruttore nella pagina" }
                fun jobToggle() = ui { views(activity!!.window.decorView).filterIsInstance<SwitchView>().first { it.isShown }.performClick() }
                jobToggle(); Thread.sleep(1800)
                jobToggle()
                var jw = false; val jwUntil = System.currentTimeMillis() + 4000
                while (!jw && System.currentTimeMillis() < jwUntil) { ui { jw = views(activity!!.window.decorView).filterIsInstance<dev.r1os.host.ui.CharacterView>().any { it.expression == "wink" } }; Thread.sleep(40) }
                check(jw) { "Job Hermes riattivato: il buddy non fa l'occhiolino" }
                step("job Hermes: riattivando fa l'occhiolino: OK")
                // Standby con Hermes: il widget mostra la prossima attività dal vivo.
                prefs.edit().remove("gw").commit()
                ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "glance")) }
                var widgetText = false; val wtUntil = System.currentTimeMillis() + 8000
                while (!widgetText && System.currentTimeMillis() < wtUntil) { ui { widgetText = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().any { it.isShown && it.text.toString().startsWith("hermes ·") && it.text.toString().contains("Prova UI") } }; Thread.sleep(200) }
                check(widgetText) { "Standby: il widget Hermes non mostra la prossima attività" }
                step("standby: il widget Hermes mostra la prossima attività dal vivo: OK")
                ui { activity!!.onBackPressed() }; Thread.sleep(600)
                // Home con Hermes: l'agente con una chat in lavorazione lo dice sotto il nome.
                val hAgent = Agent(0, "Hermes QA", "", "", profile = "qa").also { db.save(it) }
                val liveChat = db.upsertChat(hAgent.id, "qa-live", "live", System.currentTimeMillis(), 0)
                try {
                    jobMock.markActive("qa-live")
                    ui { MainActivity::class.java.getDeclaredMethod("goHome").apply { isAccessible = true }.invoke(activity) }
                    val workingText = ctx.getString(R.string.ui_9449f5bbc8)
                    var working = false; val wUntil = System.currentTimeMillis() + 12000
                    while (!working && System.currentTimeMillis() < wUntil) { ui { working = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().any { it.isShown && it.text.toString() == workingText } }; Thread.sleep(250) }
                    check(working) { "Home: l'agente con una chat in lavorazione non lo dice" }
                    step("home: l'agente che lavora su Hermes lo dice sotto il nome: OK")
                } finally { db.deleteChat(liveChat); db.delete(hAgent.id) }
            } finally {
                prefs.edit().putString("provider", savedProvider).commit()
                jobMock.close()
            }
            run { val st = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }; if ((st.get(activity) as List<*>).size > 1) { ui { activity!!.onBackPressed() }; Thread.sleep(700) } }
            // Azioni dell'agente: una risposta con una direttiva mostra il testo pulito e un pulsante da toccare.
            val actionChat = db.newChat(agent.id)
            db.add(agent.id, actionChat, "assistant", JSONArray().put(JSONObject().put("type", "text").put("text", "Ti apro la calcolatrice.\n[[r1:apri=Calcolatrice]]")).toString())
            ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("open_chat", actionChat).putExtra("open_agent", agent.id)) }
            var cleanText = false; var chip = false; var leaked = false
            val chipUntil = System.currentTimeMillis() + 9000
            while (!(cleanText && chip) && System.currentTimeMillis() < chipUntil) {
                ui {
                    val texts = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().filter { it.isShown }.map { it.text.toString() }
                    cleanText = texts.any { it.contains("Ti apro la calcolatrice") }; chip = texts.any { it == "apri Calcolatrice" }; leaked = texts.any { it.contains("[[r1:") }
                }
                Thread.sleep(200)
            }
            check(cleanText) { "Azioni agente: il testo della risposta non compare" }
            check(chip) { "Azioni agente: manca il pulsante «apri Calcolatrice»" }
            check(!leaked) { "Azioni agente: la direttiva è visibile nel testo" }
            step("azioni dell'agente: direttive tolte dal testo e offerte come pulsanti: OK")
            run { val st = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }; if ((st.get(activity) as List<*>).size > 1) { ui { activity!!.onBackPressed() }; Thread.sleep(900) } }
            db.deleteChat(actionChat)
            // Costo del Markdown durante lo streaming: render completo di testi di 1, 5 e 10 KB (misura, soglia larga).
            run {
                val para = "Ecco **un paragrafo** con `codice`, un [link](https://example.com) e un elenco:\n- primo\n- secondo\n\n"
                for (kb in listOf(1, 5, 10)) {
                    val txt = buildString { while (length < kb * 1024) append(para) }
                    Md.render(txt, 0x22ffffff)
                    val t0 = System.nanoTime(); repeat(10) { Md.render(txt, 0x22ffffff) }
                    val ms = (System.nanoTime() - t0) / 10 / 1e6
                    android.util.Log.i("R1PERF", "Md.render ${kb}KB = %.1f ms".format(ms))
                    step("Md.render ${kb}KB: %.1f ms".format(ms))
                    // Streaming: il testo cresce a pezzi; il risultato deve restare identico al render completo, ma con costo quasi costante.
                    val cache = Md.Cache(); var inc = 0.0; var worst = 0.0
                    for (n in txt.indices step 400) {
                        val part = txt.substring(0, minOf(txt.length, n + 400))
                        val a = System.nanoTime(); val got = Md.render(part, 0x22ffffff, cache); inc = (System.nanoTime() - a) / 1e6; if (n > 0) worst = maxOf(worst, inc)
                        check(got.toString() == Md.render(part, 0x22ffffff).toString()) { "Md streaming: testo diverso dal render completo a $n" }
                    }
                    step("Md streaming ${kb}KB, passo peggiore: %.1f ms".format(worst))
                }
            }
            // Credenziali: un campo segreto mostra puntini (setSingleLine toglieva la maschera), uno normale no.
            run {
                val make = MainActivity::class.java.getDeclaredMethod("field", String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                var masked = false; var plain = true
                ui {
                    val secret = make.invoke(activity!!, "chiave", "abc123", false, true) as android.widget.EditText
                    val normal = make.invoke(activity!!, "nome", "abc123", false, false) as android.widget.EditText
                    masked = secret.transformationMethod is android.text.method.PasswordTransformationMethod
                    plain = normal.transformationMethod is android.text.method.PasswordTransformationMethod
                }
                check(masked) { "Credenziali: il campo segreto mostra il testo in chiaro" }
                check(!plain) { "Credenziali: un campo normale è mascherato" }
            }
            step("credenziali: campi segreti mascherati: OK")
            // Pressione: righe e schede non si riempiono di colore (solo un leggero movimento).
            run {
                var coloured = ""
                ui {
                    val a = activity!!
                    val cases = listOf("row" to a.row("prova", dev.r1os.host.ui.Theme.accent, icon = "lock") {}, "bigRow" to a.bigRow("lock", "prova", "sotto", dev.r1os.host.ui.Theme.accent) {})
                    for ((name, v) in cases) {
                        val t = android.os.SystemClock.uptimeMillis()
                        v.layout(0, 0, 400, 160)
                        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 200f, 80f, 0))
                        val bg = v.background; val fill = (bg as? android.graphics.drawable.InsetDrawable)?.drawable ?: bg
                        if (fill is Jelly) coloured += "$name "
                        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_CANCEL, 200f, 80f, 0))
                    }
                }
                check(coloured.isEmpty()) { "Pressione: si riempie di colore: $coloured" }
            }
            step("pressione di righe e schede senza riempimenti colorati: OK")
            // Volo del buddy: finché c'è il clone in volo, il buddy della pagina d'arrivo non deve vedersi (niente doppioni).
            run {
                val flightChat = db.newChat(agent.id)
                var doubled = 0; var frames = 0
                val stackField = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }
                val viewOf = Class.forName("dev.r1agents.MainActivity\$Screen").getDeclaredMethod("getView").apply { isAccessible = true }
                val root = activity!!.window.decorView.rootView
                val check = android.view.ViewTreeObserver.OnPreDrawListener {
                    val top = (stackField.get(activity) as List<*>).lastOrNull()?.let { viewOf.invoke(it) as View }
                    val clones = views(root).count { it.tag == "buddy-flight" && it.isShown && it.alpha > .05f }
                    val own = top?.let { t -> views(t).count { it is dev.r1os.host.ui.CharacterView && it.tag == "scene-buddy" && it.isShown && it.alpha > .05f } } ?: 0
                    if (clones > 0) { frames++; if (own > 0) doubled++ }
                    true
                }
                ui { root.viewTreeObserver.addOnPreDrawListener(check); test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("open_chat", flightChat).putExtra("open_agent", agent.id)) }
                Thread.sleep(2200)
                ui { root.viewTreeObserver.removeOnPreDrawListener(check) }
                android.util.Log.i("R1PERF", "volo: $frames frame con clone, $doubled con doppione")
                check(doubled == 0) { "Volo: buddy doppio in $doubled di $frames frame" }
                run { val st = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }; if ((st.get(activity) as List<*>).size > 1) { ui { activity!!.onBackPressed() }; Thread.sleep(900) } }
                db.deleteChat(flightChat)
            }
            step("volo del buddy: mai due buddy insieme: OK")
            // PIN solo sulle chat: con il PIN impostato la home resta libera; una chat bloccata chiede il PIN, una no.
            run {
                val lockedChat = db.newChat(agent.id); val freeChat = db.newChat(agent.id)
                prefs.edit().putString("app_pin_hash", PinHash.create("2468")).putStringSet("locked_chats", setOf(lockedChat.toString())).remove("lock_all_chats").commit()
                fun gate() = MainActivity::class.java.getDeclaredField("pinGate").apply { isAccessible = true }.get(activity)
                ui { test.callActivityOnPause(activity!!); test.callActivityOnResume(activity!!) }; Thread.sleep(500)
                check(gate() == null) { "PIN: la home non deve chiedere il PIN" }
                ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("open_chat", freeChat).putExtra("open_agent", agent.id)) }; Thread.sleep(1500)
                check(gate() == null) { "PIN: una chat non bloccata non deve chiedere il PIN" }
                ui { activity!!.onBackPressed() }; Thread.sleep(900)
                ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("open_chat", lockedChat).putExtra("open_agent", agent.id)) }; Thread.sleep(1500)
                run {
                    val st = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }.get(activity) as List<*>
                    val top = st.lastOrNull(); val ck = top?.let { it.javaClass.getDeclaredField("chatKey").apply { isAccessible = true }.get(it) }
                    android.util.Log.i("R1PERF", "pin-debug stack=${st.size} chatKey=$ck locked=$lockedChat pin=${prefs.getString("app_pin_hash", "")?.isNotEmpty()} set=${prefs.getStringSet("locked_chats", null)}")
                }
                check(gate() != null) { "PIN: una chat bloccata deve chiedere il PIN" }
                ui { activity!!.onBackPressed() }; Thread.sleep(900)   // indietro dal gate = esce dalla chat
                check(gate() == null) { "PIN: uscire dalla chat bloccata deve togliere il gate" }
                prefs.edit().remove("app_pin_hash").remove("locked_chats").commit()
                db.deleteChat(lockedChat); db.deleteChat(freeChat)
            }
            step("PIN solo sulle chat bloccate: home libera, chat libera, chat bloccata, uscita: OK")
            // Inattività: il tempo si legge dalle impostazioni (mai = 0).
            run {
                val idle = MainActivity::class.java.getDeclaredMethod("idleMs").apply { isAccessible = true }
                val got = mutableListOf<Long>()
                for (v in listOf("1", "5", "never")) { prefs.edit().putString("idle_after", v).commit(); ui { got += idle.invoke(activity!!) as Long } }
                prefs.edit().remove("idle_after").commit()
                check(got == listOf(60_000L, 300_000L, 0L)) { "Inattività: tempi letti male $got" }
            }
            step("inattività: tempo letto dalle impostazioni: OK")
            // Modalità automatica: la direttiva si esegue da sola una volta sola (qui una nota, innocua).
            run {
                val auto = MainActivity::class.java.getDeclaredMethod("autoRunActions", String::class.java).apply { isAccessible = true }
                val sentence = "Segnato.\n[[r1:nota=qa-auto-" + System.nanoTime() + "]]"
                val before = db.notes().size
                DeviceActions.mode = "auto"
                try { ui { auto.invoke(activity!!, sentence) }; Thread.sleep(400); ui { auto.invoke(activity!!, sentence) }; Thread.sleep(400) } finally { DeviceActions.mode = "ask" }
                val added = db.notes().size - before
                check(added == 1) { "Azioni automatiche: attese 1 nota aggiunta, trovate $added" }
                ui { auto.invoke(activity!!, sentence) } // modalità chiedi: non deve fare nulla
                check(db.notes().size - before == 1) { "Azioni automatiche: eseguita anche con modalità «chiedi»" }
                db.notes().filter { it.second.startsWith("qa-auto-") }.forEach { db.deleteNote(it.first) }
            }
            step("azioni automatiche: eseguite una volta sola, ferme in modalità chiedi: OK")
            // Launcher: l'app è selezionabile come schermata iniziale ma non si imposta da sola; il cassetto elenca le app e filtra.
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val homes = ctx.packageManager.queryIntentActivities(homeIntent, 0)
            check(homes.any { it.activityInfo.packageName == ctx.packageName }) { "L'app non è selezionabile come schermata iniziale" }
            check(homes.size >= 2) { "Il sistema non vede altre schermate iniziali oltre alla nostra (visibilità dei pacchetti)" }
            check(!ctx.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) { "L'app si è impostata da sola come schermata iniziale" }
            ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "apps")) }
            var appRows = 0; val appsUntil = System.currentTimeMillis() + 8000
            while (appRows < 3 && System.currentTimeMillis() < appsUntil) { ui { appRows = views(activity!!.window.decorView).count { it is android.widget.ImageView && it.isShown && it.contentDescription != null } }; Thread.sleep(150) }
            var shownTexts = ""; ui { shownTexts = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().filter { it.isShown }.map { it.text.toString() }.take(10).toString() }
            check(appRows >= 3) { "Cassetto app: righe trovate $appRows; a schermo: $shownTexts" }
            ui { views(activity!!.window.decorView).filterIsInstance<EditText>().first { it.isShown }.setText("zzqx_nessuna_app") }
            Thread.sleep(500)
            var askRow = false; ui { askRow = views(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().any { it.isShown && it.text.toString().startsWith("chiedi a ") } }
            check(askRow) { "Cassetto app: senza risultati manca «chiedi all'agente»" }
            step("launcher: selezionabile senza impostarsi, cassetto con app e ricerca con «chiedi all'agente»: OK")
            // Dock dei preferiti nello standby: un'app preferita compare come icona cliccabile.
            val calc = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).firstOrNull { it.activityInfo.packageName == "com.android.calculator2" }
            if (calc != null) {
                val calcLabel = calc.loadLabel(ctx.packageManager).toString()
                prefs.edit().putString("fav_apps", "com.android.calculator2").commit()
                try {
                    ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "glance")) }
                    var dockIcon = false; val dockUntil = System.currentTimeMillis() + 6000
                    while (!dockIcon && System.currentTimeMillis() < dockUntil) { ui { dockIcon = views(activity!!.window.decorView).any { it is android.widget.ImageView && it.isShown && it.contentDescription == calcLabel } }; Thread.sleep(150) }
                    check(dockIcon) { "Dock: l'app preferita non compare nello standby" }
                    step("launcher: il dock dello standby mostra le app preferite: OK")
                } finally { prefs.edit().remove("fav_apps").commit(); ui { activity!!.onBackPressed() }; Thread.sleep(700) }
            }
            // Tasto home (quando siamo la schermata iniziale): da qualunque profondità si torna alla prima schermata.
            ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "settings")) }; Thread.sleep(900)
            ui { test.callActivityOnNewIntent(activity!!, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "timers")) }; Thread.sleep(900)
            val stackField = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }
            val depthBefore = (stackField.get(activity) as List<*>).size
            check(depthBefore >= 3) { "Pile di prova troppo bassa: $depthBefore" }
            ui { MainActivity::class.java.getDeclaredMethod("goHome").apply { isAccessible = true }.invoke(activity) }
            val homeUntil = System.currentTimeMillis() + 5000
            while ((stackField.get(activity) as List<*>).size > 1 && System.currentTimeMillis() < homeUntil) Thread.sleep(100)
            check((stackField.get(activity) as List<*>).size == 1) { "Dopo home la pila non è tornata alla prima schermata" }
            step("launcher: home chiude le pagine e torna alla prima schermata: OK")
            ui { activity!!.onBackPressed() }; Thread.sleep(700)
            ui { activity?.finishAndRemoveTask() }; test.waitForIdleSync(); Thread.sleep(800); activity = null
            step("buddy e scroll: affonda a metà, si allunga, si infastidisce, fa il broncio e perdona al tocco: OK")
            val longText = (1..22).joinToString("\n\n") { "Risposta lunga $it · A long reply with a file, a translation and a question. " + "contenuto leggibile ".repeat(5) }
            db.add(agent.id, chat, "assistant", JSONArray().put(JSONObject().put("type", "text").put("text", longText)).toString())
            for (language in listOf("it", "en")) for (look in listOf("dark", "light")) {
                prefs.edit().putString("app_language", language).putString("look_mode", look).commit()
                activity = test.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val a = activity!!
                if (language == "it" && look == "dark" && !SpeechSupport.hasEngine(ctx)) {
                    ui {
                        prefs.edit().putBoolean("voice_tts", true).commit()
                        val mode = VoiceConversation(a, agent, false, { error("Nessun invio previsto") }, { _, _, _ -> error("Nessun microfono previsto") }, close = {})
                        a.addContentView(mode, ViewGroup.LayoutParams(-1, -1))
                        VoiceConversation::class.java.getDeclaredMethod("render", TurnProgress.Snapshot::class.java).apply { isAccessible = true }.invoke(mode, TurnProgress.Snapshot(1, "risposta leggibile", status = TurnProgress.Status.DONE))
                        check(views(mode).filterIsInstance<android.widget.TextView>().any { it.text.toString() == a.getString(R.string.voice_audio_missing_engine) }) { "Manca l'avviso persistente sul motore vocale assente" }
                        check(views(mode).filterIsInstance<android.widget.TextView>().none { it.text.toString() in listOf("voce sì", "voce no") })
                        mode.dispose(); (mode.parent as ViewGroup).removeView(mode)
                        prefs.edit().putBoolean("voice_tts", false).commit()
                    }
                    step("motore vocale assente: messaggio persistente e scena senza selettore audio: OK")
                }
                ui { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                test.waitForIdleSync(); Thread.sleep(450)
                for (screen in listOf("settings", "dash", "translator", "look")) {
                    ui { test.callActivityOnNewIntent(a, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", screen)) }
                    test.waitForIdleSync(); Thread.sleep(450)
                    if (screen == "look") {
                        ui {
                            val scroll = views(a.window.decorView).filterIsInstance<R1ScrollView>().first { it.isShown }
                            scroll.fullScroll(View.FOCUS_DOWN)
                        }
                        Thread.sleep(150)
                        ui {
                            views(a.window.decorView).filterIsInstance<android.widget.TextView>().last { it.text.toString() == a.getString(R.string.voice_sound_on) }.performClick()
                            check(prefs.getBoolean("voice_tts", false))
                            views(a.window.decorView).filterIsInstance<android.widget.TextView>().last { it.text.toString() == a.getString(R.string.voice_sound_off) }.performClick()
                            check(!prefs.getBoolean("voice_tts", true))
                        }
                    }
                    shot("$language-$look-$screen")
                    ui { a.onBackPressed() }; Thread.sleep(450)
                }
                ui { test.callActivityOnNewIntent(a, Intent(ctx, MainActivity::class.java).putExtra("open_chat", chat).putExtra("open_agent", agent.id)) }
                test.waitForIdleSync(); Thread.sleep(500); shot("$language-$look-chat-long")
                lateinit var edit: EditText
                ui {
                    edit = views(a.window.decorView).filterIsInstance<EditText>().first { it.isShown }
                    edit.setText("bozza / draft · 🙂"); edit.requestFocus()
                    a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(edit, 0)
                }
                Thread.sleep(600); shot("$language-$look-keyboard")
                ui { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                Thread.sleep(600); test.waitForIdleSync()
                ui { check(edit.text.toString() == "bozza / draft · 🙂"); a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(edit.windowToken, 0) }
                shot("$language-$look-chat-landscape")
                ui { a.onBackPressed() }; Thread.sleep(450)
                ui {
                    val layer = BuddyStage(a, agent)
                    val holder = android.widget.FrameLayout(a)
                    a.addContentView(holder, ViewGroup.LayoutParams(-1, -1)); holder.addView(layer, android.widget.FrameLayout.LayoutParams(-1, 90))
                    layer.show("handoff", "kiwi → luna", toolLabel("message_agent", "{\"agent\":\"luna\"}"))
                    check(views(layer).filterIsInstance<dev.r1os.host.ui.CharacterView>().size == 2) { "Destinatario del passaggio non visibile" }
                    layer.show("handoff", "sotto-agente", "chiede a un sotto-agente")
                    check(views(layer).filterIsInstance<dev.r1os.host.ui.CharacterView>().size == 1) { "Destinatario inventato" }
                    layer.show("listening", "ascolto"); layer.level(.85f)
                    (holder.parent as ViewGroup).removeView(holder)
                    val motion = views(layer).filterIsInstance<BuddyMotionLayer>().single()
                    check(!BuddyMotionLayer::class.java.getDeclaredField("scheduled").apply { isAccessible = true }.getBoolean(motion)) { "Animazione liquida ancora attiva fuori finestra" }
                    a.finishAndRemoveTask()
                }
                test.waitForIdleSync(); activity = null
                step("$language / $look: impostazioni, provider, traduttore, testo lungo, tastiera, rotazione e animazioni: OK")
            }
            // I cambi di lingua/tema ricreano l'Activity: devono riaprire Aspetto con
            // una sola ancora visibile e una catena Indietro completa.
            activity = test.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fun assertReopenedBuddy(a: MainActivity) {
                val root = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(a) as ViewGroup
                val stack = MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }.get(a) as List<*>
                val top = stack.last()!!
                val page = top.javaClass.getDeclaredField("view").apply { isAccessible = true }.get(top) as View
                val buddy = page.findViewWithTag<dev.r1os.host.ui.CharacterView>("scene-buddy")
                    ?: views(page).filterIsInstance<dev.r1os.host.ui.CharacterView>().firstOrNull { it.isShown && it.alpha > .99f && it.getGlobalVisibleRect(android.graphics.Rect()) }
                    ?: error("Buddy perso dopo ricreazione")
                val bounds = android.graphics.Rect()
                check(buddy.isShown && buddy.alpha > .99f && buddy.getGlobalVisibleRect(bounds) && bounds.height() > 0)
                check(page.tag == "home-screen" || views(root).filterIsInstance<dev.r1os.host.ui.CharacterView>().count { it.isShown && it.alpha > .99f && it.getGlobalVisibleRect(android.graphics.Rect()) } == 1) { "Buddy duplicato dopo ricreazione" }
            }
            for ((key, value) in listOf("look_mode" to "dark", "app_language" to "it")) {
                val previous = activity!!
                ui { test.callActivityOnNewIntent(previous, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "look")) }
                test.waitForIdleSync(); Thread.sleep(500)
                val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    ui { MainActivity::class.java.getDeclaredMethod("applyLook", String::class.java, String::class.java).apply { isAccessible = true }.invoke(previous, key, value) }
                    activity = test.waitForMonitorWithTimeout(monitor, 8000) as? MainActivity ?: error("Activity non ricreata: $key")
                } finally { test.removeMonitor(monitor) }
                test.waitForIdleSync(); Thread.sleep(700)
                val reopened = activity!!
                ui { assertReopenedBuddy(reopened) }; shot("recreated-$key")
                repeat(2) { ui { reopened.onBackPressed() }; Thread.sleep(450); ui { assertReopenedBuddy(reopened) } }
                step("$key: Aspetto ripristinato, buddy unico e ritorno completo: OK")
            }
            ui { activity?.finishAndRemoveTask() }; activity = null
            db.delete(target.id)
        } finally { ui { activity?.finishAndRemoveTask() }; if (awake.isHeld) awake.release() }
        test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "DEVELOPMENT_QA_RESULT=PASS\n") })
    }
}
