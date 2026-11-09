package dev.r1agents

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class FunctionalUiQa(private val test: Instrumentation) {
    private val ctx get() = test.targetContext
    private val db get() = Db.get(ctx)
    private fun ui(block: () -> Unit) {
        var error: Throwable? = null
        test.runOnMainSync { try { block() } catch (e: Throwable) { error = e } }; error?.let { throw it }
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun field(a: MainActivity, name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(a, value)
    private fun await(label: String, check: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 6000
        while (android.os.SystemClock.uptimeMillis() < until) { if (check()) return; Thread.sleep(30) }; error("Timeout $label")
    }
    private fun activity(block: (MainActivity) -> Unit) {
        db.save(Agent(0, "QA locale", "istruzioni"))
        val a = test.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { test.waitForIdleSync(); block(a) }
        finally { ui { a.finishAndRemoveTask() }; test.waitForIdleSync() }
    }
    private fun screen(a: MainActivity, name: String) {
        ui { test.callActivityOnNewIntent(a, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", name)) }
        test.waitForIdleSync(); Thread.sleep(450)
    }
    private fun click(a: MainActivity, text: String) {
        var target: View? = views(a.window.decorView).filterIsInstance<TextView>().lastOrNull { it.isShown && it.text.toString() == text } ?: error("Controllo non trovato: $text")
        while (target != null && !target.isClickable) target = target.parent as? View
        check(target?.performClick() == true) { "Controllo non attivabile: $text" }
    }
    private fun result(a: MainActivity, req: Int, res: Int, data: Intent?) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }.invoke(a, req, res, data)
    }
    private class PausedMic(val final: (String) -> Unit, val end: (String?) -> Unit) : Mic {
        @Volatile var cancelled = false
        var started = false
        override fun start(): Boolean { started = true; cancelled = false; return true }
        override fun stop() {}
        override fun cancel() { cancelled = true }
    }
    fun run(checkCase: (String, () -> Unit) -> Unit) {
        checkCase("file interrotto: nessuna copia parziale resta salvata") {
            val before = File(ctx.filesDir, "attach").listFiles()?.map { it.name }?.toSet().orEmpty()
            var rejected = false
            try { Attach.fromUri(ctx, Uri.parse("content://${ctx.packageName}.functional-files/broken.txt")) } catch (_: java.io.IOException) { rejected = true }
            check(rejected && File(ctx.filesDir, "attach").listFiles()?.map { it.name }?.toSet().orEmpty() == before)
        }
        checkCase("allegato lento: cambio chat non consegna il file alla nuova chat") {
            activity { a ->
                FunctionalFiles.reset()
                val wrong = AtomicInteger(); val original = AtomicInteger()
                val source: (Attachment) -> Unit = { original.incrementAndGet() }
                val next: (Attachment) -> Unit = { wrong.incrementAndGet() }
                val before = File(ctx.filesDir, "attach").listFiles()?.map { it.name }?.toSet().orEmpty()
                try {
                    ui {
                        field(a, "attachHook", source); field(a, "fileAttachTarget", source)
                        result(a, 10, Activity.RESULT_OK, Intent().setData(Uri.parse("content://${ctx.packageName}.functional-files/slow.txt")))
                    }
                    check(FunctionalFiles.opened.await(5, TimeUnit.SECONDS))
                    ui { field(a, "attachHook", next) }; FunctionalFiles.release.countDown()
                    await("eliminazione allegato della chat chiusa") {
                        File(ctx.filesDir, "attach").listFiles()?.map { it.name }?.toSet().orEmpty() == before
                    }
                    test.waitForIdleSync(); check(wrong.get() == 0 && original.get() == 0)
                } finally { FunctionalFiles.release.countDown() }
            }
        }
        checkCase("foto annullata o destinazione chiusa: nessun file passa a un'altra chat") {
            activity { a ->
                val original: (Attachment) -> Unit = { error("Foto consegnata alla chat chiusa") }
                val next: (Attachment) -> Unit = { error("Foto consegnata alla nuova chat") }
                for (code in listOf(Activity.RESULT_CANCELED, Activity.RESULT_OK)) {
                    val file = File(ctx.cacheDir, "foto-annullata-qa.jpg").apply { writeText("dati QA") }
                    ui { field(a, "photoFile", file); field(a, "photoAttachTarget", original); field(a, "attachHook", next); result(a, 9, code, null) }
                    check(!file.exists())
                }
            }
        }
        for (name in listOf("notes", "translator", "chat")) checkCase("microfono $name: background annulla e scarta il risultato tardivo") {
            activity { a ->
                lateinit var fake: PausedMic
                val factory: ((String) -> Unit, (String) -> Unit, (String?) -> Unit) -> Mic = { _, final, end -> PausedMic(final, end).also { fake = it } }
                ui { field(a, "qaMicrophone", factory) }
                if (name == "chat") {
                    val agent = db.agents().single(); val chat = db.newChat(agent.id)
                    ui { test.callActivityOnNewIntent(a, Intent(ctx, MainActivity::class.java).putExtra("open_chat", chat).putExtra("open_agent", agent.id)) }
                    test.waitForIdleSync(); Thread.sleep(450)
                } else screen(a, name)
                lateinit var input: EditText
                ui {
                    input = views(a.window.decorView).filterIsInstance<EditText>().first { it.isShown }; input.setText("bozza intatta QA")
                    if (name == "chat") a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_1))
                    else {
                        val label = a.getString(if (name == "notes") R.string.ui_e4923b7b6f else R.string.ui_1158299b45)
                        val button = views(a.window.decorView).filterIsInstance<TextView>().last { it.isShown && it.text.toString() == label }
                        val now = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
                        try { button.dispatchTouchEvent(down) } finally { down.recycle() }
                    }
                    check(fake.started); a.moveTaskToBack(true)
                }
                await("microfono fermato in background") { fake.cancelled }
                ui { fake.final("TRASCRIZIONE TARDIVA"); check(input.text.toString() == "bozza intatta QA") }
                ui { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                test.waitForIdleSync(); ui { check(input.text.toString() == "bozza intatta QA") }
            }
        }
        checkCase("interfaccia note: aggiungi, copia e cancella") {
            activity { a ->
                screen(a, "notes")
                ui {
                    views(a.window.decorView).filterIsInstance<EditText>().first { it.isShown }.setText("nota funzionale QA")
                    click(a, a.getString(R.string.ui_6c15f34ba8))
                }
                await("nota aggiunta") { db.notes().any { it.second == "nota funzionale QA" } }
                ui {
                    click(a, "nota funzionale QA")
                    val clip = a.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                    check(clip?.getItemAt(0)?.text?.toString() == "nota funzionale QA")
                    click(a, a.getString(R.string.ui_dcfbe248ac)) // il primo tocco avvisa
                    click(a, a.getString(R.string.ui_dcfbe248ac)) // il secondo conferma
                }
                await("nota eliminata") { db.notes().isEmpty() }
            }
        }
        checkCase("interfaccia memoria agente: aggiungi, copia e cancella") {
            activity { a ->
                val agent = db.agents().single(); screen(a, "memory")
                ui {
                    views(a.window.decorView).filterIsInstance<EditText>().first { it.isShown }.setText("memoria funzionale QA")
                    click(a, a.getString(R.string.ui_6c15f34ba8))
                }
                await("memoria aggiunta") { db.memories(agent.id).size == 1 }
                ui {
                    click(a, "memoria funzionale QA")
                    check(a.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() == "memoria funzionale QA")
                    click(a, a.getString(R.string.ui_dcfbe248ac))
                }
                await("memoria eliminata") { db.memories(agent.id).isEmpty() }
            }
        }
        checkCase("interfaccia sveglia: crea, disattiva, modifica e cancella") {
            activity { a ->
                screen(a, "alarm_new")
                ui {
                    views(a.window.decorView).filterIsInstance<EditText>().first { it.isShown }.setText("sveglia funzionale QA")
                    click(a, a.getString(R.string.ui_bf2f34b0ac)); click(a, a.getString(R.string.ui_3517056179))
                }
                await("sveglia salvata") { db.alarms().size == 1 }
                check(db.alarms().single().days == 127)
                screen(a, "alarms")
                ui { views(a.window.decorView).filterIsInstance<SwitchView>().first { it.isShown }.performClick() }
                await("sveglia spenta") { !db.alarms().single().on }
                ui {
                    var target: View? = views(a.window.decorView).filterIsInstance<TextView>().last { it.isShown && "sveglia funzionale QA" in it.text.toString() }
                    while (target != null && !target.isClickable) target = target.parent as? View
                    check(target?.performClick() == true)
                }; test.waitForIdleSync(); Thread.sleep(450)
                ui { click(a, a.getString(R.string.ui_4a28ceb2d8)) }
                await("sveglia cancellata") { db.alarms().isEmpty() }
            }
        }
        checkCase("interfaccia routine: valida istruzioni, crea, modifica e cancella") {
            activity { a ->
                screen(a, "routine_new")
                ui { click(a, a.getString(R.string.ui_3517056179)) }; check(db.routines().isEmpty())
                ui {
                    val fields = views(a.window.decorView).filterIsInstance<EditText>().filter { it.isShown }
                    fields[0].setText("routine funzionale QA"); fields[1].setText("istruzioni QA senza esecuzione")
                    click(a, a.getString(R.string.ui_3517056179))
                }
                await("routine salvata") { db.routines().size == 1 }
                screen(a, "routines")
                ui { views(a.window.decorView).filterIsInstance<SwitchView>().first { it.isShown }.performClick() }
                await("routine spenta") { !db.routines().single().on }
                ui { click(a, "routine funzionale QA") }; test.waitForIdleSync(); Thread.sleep(450)
                ui { click(a, a.getString(R.string.ui_4a28ceb2d8)) }
                await("routine cancellata") { db.routines().isEmpty() }
            }
        }
        checkCase("editor agente: annulla non salva, conferma salva l'aspetto scelto") {
            activity { a ->
                val original = db.agents().single().form
                screen(a, "edit")
                ui { a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)); a.onBackPressed() }
                Thread.sleep(450); check(db.agents().single().form == original)
                screen(a, "edit")
                ui { a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)); click(a, a.getString(R.string.ui_3517056179)) }
                await("aspetto salvato") { db.agents().single().form != original }
            }
        }
        checkCase("interfaccia agenti: creazione, riordino ed eliminazione confermata") {
            activity { a ->
                val original = db.agents().single().id
                screen(a, "agent_new"); ui { click(a, a.getString(R.string.ui_3517056179)) }
                await("agente creato") { db.agents().size == 2 }
                screen(a, "space"); ui { click(a, a.getString(R.string.ui_18e4c8d6fb)) }
                check(db.agents().last().id == original)
                ui { click(a, a.getString(R.string.ui_235936fba8)) }; check(db.agents().size == 2)
                ui { click(a, a.getString(R.string.ui_39ced40b15)) }
                await("agente eliminato") { db.agents().size == 1 && db.agents().none { it.id == original } }
            }
        }
        checkCase("interfaccia timer: durata rapida, avvio e annullamento") {
            activity { a ->
                screen(a, "timers")
                val start = System.currentTimeMillis()
                ui { click(a, "1m"); click(a, a.getString(R.string.ui_1e70b53208)) }
                await("timer aggiunto") { db.timers().size == 1 }
                check(db.timers().single().endAt in (start + 59000)..(start + 65000))
                ui { click(a, a.getString(R.string.ui_9cb9b85e99)) }
                await("timer annullato") { db.timers().isEmpty() }
            }
        }
    }
}
