package dev.r1agents

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Prove sui dati reali Android, esclusivamente nel pacchetto QA. Nessuna API remota o riproduzione audio. */
internal class FunctionalQa(private val test: Instrumentation) {
    private val ctx get() = test.targetContext
    private val db get() = Db.get(ctx)
    private val prefs get() = ctx.getSharedPreferences("settings", 0)
    private val results = ArrayList<String>()
    private var failed = 0
    private fun checkCase(name: String, action: () -> Unit) {
        try { reset(); action(); results += "PASS $name" }
        catch (e: Throwable) { failed++; results += "FAIL $name: ${e.message}\n${e.stackTraceToString()}" }
        test.sendStatus(1, Bundle().apply { putString("stream", results.last() + "\n") })
    }
    private fun reset() {
        db.alarms().forEach { Sched.cancel(ctx, Sched.KIND_ALARM, it.id) }
        db.routines().forEach { Sched.cancel(ctx, Sched.KIND_ROUTINE, it.id) }
        db.timers().forEach { Sched.cancel(ctx, Sched.KIND_TIMER, it.id) }
        db.wipeAll()
        prefs.edit().clear().putString("provider", "custom").putString("app_language", "it").putBoolean("voice_tts", false).commit()
    }
    private fun seed(): Agent {
        val a = Agent(0, "originale", "istruzioni", "", "blob", "green").also { db.save(it) }
        val c = db.newChat(a.id); db.title(c, "chat originale")
        db.add(a.id, c, "user", "[{\"type\":\"text\",\"text\":\"ciao\"}]")
        db.addMemory(a.id, "memoria"); db.addNote("nota originale")
        db.save(Alarm(0, 23, 59, 0, "spenta", false))
        db.save(Routine(0, a.id, "routine spenta", "nessun invio", 23, 59, 0, false))
        return a
    }
    private fun snapshot(): String {
        val j = JSONObject(Backup.export(db, prefs).first); j.remove("exported")
        return j.toString() + db.agents().map { it.id } + db.timers().map { it.id to it.endAt }
    }
    private fun malformedReplace(json: String) {
        seed(); val before = snapshot()
        var rejected = false
        try { Backup.import(ctx, db, prefs, json, true) } catch (_: Exception) { rejected = true }
        check(rejected) { "file invalido accettato" }
        check(snapshot() == before) { "dati modificati dopo importazione fallita" }
    }
    fun run() {
        check(ctx.packageName == "dev.r1agents.qa")
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        check(power.isInteractive) { "Riattiva e sblocca il Rabbit" }
        @Suppress("DEPRECATION") val wake = power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "r1agents.qa:functions")
        wake.acquire(120000)
        try {
            HermesRuntime.get(ctx).cancelAll()
            checkCase("backup malformato: sostituzione atomica") {
                malformedReplace("""{"app":"r1agents","version":1,"agents":[{"name":"nuovo","chats":[{"messages":[{}]}]}],"notes":[],"alarms":[],"settings":{}}""")
            }
            checkCase("backup incompleto rifiutato prima della cancellazione") { malformedReplace("""{"app":"r1agents","version":1}""") }
            checkCase("backup: impostazione invalida non altera dati o preferenze") {
                malformedReplace("""{"app":"r1agents","version":1,"agents":[],"notes":[],"alarms":[],"settings":{"home_index":{"t":"i","v":"non-numero"}}}""")
            }
            checkCase("backup completo: dati, cronologia note e segreti") {
                val a = seed(); val chat = db.chats(a.id).single().id
                db.writableDatabase.execSQL("UPDATE notes SET ts=?", arrayOf(1700000000000L))
                prefs.edit().putString("dash_pass", "qa-secret").putString("app_pin_hash", "qa-pin").putBoolean("voice_tts", true).commit()
                val json = Backup.export(db, prefs).first
                check(!JSONObject(json).getJSONObject("settings").has("dash_pass"))
                val sum = Backup.import(ctx, db, prefs, json, true)
                check(sum.agents == 1 && sum.chats == 1 && sum.notes == 1 && sum.routines == 1 && sum.alarms == 1)
                check(db.notes().single().third == 1700000000000L) { "data della nota persa" }
                val restored = db.agents().single(); check(restored.form == "blob" && restored.color == "green")
                check(db.messages(db.chats(restored.id).single().id).single().first == "user")
                check(db.memories(restored.id).single().second == "memoria")
                check(prefs.getString("dash_pass", "") == "qa-secret" && prefs.getString("app_pin_hash", "") == "qa-pin")
                check(prefs.getBoolean("voice_tts", false))
            }
            checkCase("backup: rollback anche se SQLite rifiuta una scrittura") {
                seed(); val before = snapshot()
                val broken = JSONObject(Backup.export(db, prefs).first).apply { getJSONArray("notes").getJSONObject(0).put("text", "reject-qa") }
                db.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_qa_note BEFORE INSERT ON notes WHEN NEW.text='reject-qa' BEGIN SELECT RAISE(ABORT, 'QA write failure'); END")
                try {
                    var rejected = false
                    try { Backup.import(ctx, db, prefs, broken.toString(), true) } catch (_: android.database.SQLException) { rejected = true }
                    check(rejected && snapshot() == before) { "scrittura fallita non ripristinata" }
                } finally { db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_qa_note") }
            }
            checkCase("backup aggiungi: conserva dati precedenti") {
                seed(); val json = Backup.export(db, prefs).first; val before = db.agents().single().id
                Backup.import(ctx, db, prefs, json, false)
                check(db.agents().size == 2 && db.agents().any { it.id == before })
                check(db.notes().size == 2 && db.routines().size == 2)
            }
            checkCase("elimina agente: nessuna routine orfana") {
                val a = seed(); db.delete(a.id)
                check(db.agents().isEmpty() && db.chats(a.id).isEmpty() && db.memories(a.id).isEmpty())
                check(db.routines().isEmpty()) { "routine rimasta senza agente" }
            }
            checkCase("ricevitore: sveglia disattivata non suona") {
                val a = Alarm(0, 23, 59, 0, "disattivata", false); db.save(a)
                val calls = ArrayList<Intent>()
                val noAudio = object : ContextWrapper(ctx) { override fun startForegroundService(intent: Intent): android.content.ComponentName? { calls += intent; return null } }
                AlarmReceiver().onReceive(noAudio, Intent().putExtra("kind", Sched.KIND_ALARM).putExtra("id", a.id))
                check(calls.isEmpty()) { "avviato RingService per sveglia spenta" }
            }
            checkCase("ricevitore: routine disattivata non parte") {
                val a = seed(); val r = db.routines().single(); val calls = ArrayList<Intent>()
                val noAudio = object : ContextWrapper(ctx) { override fun startForegroundService(intent: Intent): android.content.ComponentName? { calls += intent; return null } }
                AlarmReceiver().onReceive(noAudio, Intent().putExtra("kind", Sched.KIND_ROUTINE).putExtra("id", r.id))
                check(calls.isEmpty()) { "avviato RoutineService per routine spenta" }
            }
            checkCase("ricevitore: routine senza agente non eseguita") {
                val r = Routine(0, 9999999, "orfana QA", "nessun invio", 23, 59, 0, true); db.save(r)
                val calls = ArrayList<Intent>()
                val noAudio = object : ContextWrapper(ctx) { override fun startForegroundService(intent: Intent): android.content.ComponentName? { calls += intent; return null } }
                AlarmReceiver().onReceive(noAudio, Intent().putExtra("kind", Sched.KIND_ROUTINE).putExtra("id", r.id))
                check(calls.isEmpty())
            }
            checkCase("ricevitore: sveglia singola e timer consumati una sola volta") {
                val a = Alarm(0, 23, 59, 0, "QA", true); db.save(a)
                val calls = ArrayList<Intent>()
                val noAudio = object : ContextWrapper(ctx) { override fun startForegroundService(intent: Intent): android.content.ComponentName? { calls += intent; return null } }
                val alarmIntent = Intent().putExtra("kind", Sched.KIND_ALARM).putExtra("id", a.id)
                AlarmReceiver().onReceive(noAudio, alarmIntent); AlarmReceiver().onReceive(noAudio, alarmIntent)
                check(calls.size == 1 && db.alarm(a.id)?.on == false) { "sveglia singola avviata più volte" }
                val timer = db.addTimer(System.currentTimeMillis() - 1, "QA")
                val timerIntent = Intent().putExtra("kind", Sched.KIND_TIMER).putExtra("id", timer)
                AlarmReceiver().onReceive(noAudio, timerIntent); AlarmReceiver().onReceive(noAudio, timerIntent)
                check(calls.size == 2 && db.timers().isEmpty())
            }
            checkCase("allegati: copie indipendenti anche con lo stesso nome") {
                val file = File(ctx.cacheDir, "uguale.txt").apply { writeText("contenuto QA") }
                val copies = (1..64).map { Attach.fromUri(ctx, android.net.Uri.fromFile(file)) }
                try {
                    check(copies.map { it.file.canonicalPath }.distinct().size == copies.size) { "copie con nome uguale sovrascritte" }
                    check(copies.all { it.kind == Kind.TEXT && Attach.text(it) == "contenuto QA" })
                } finally { copies.forEach { it.file.delete() }; file.delete() }
            }
            checkCase("allegati: immagine ridotta, testo lungo e limite 20 MB") {
                val file = File(ctx.cacheDir, "immagine-qa.png")
                val bitmap = android.graphics.Bitmap.createBitmap(3200, 2000, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.GREEN)
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                val bytes = Attach.imageBytes(Attach.fromFile(file, "immagine.png", "image/png"))
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                check(bounds.outWidth == 1600 && bounds.outHeight == 1000)
                file.delete()
                val text = File(ctx.cacheDir, "testo-qa.txt").apply { writeText("a".repeat(Attach.MAX_TEXT + 10)) }
                check(Attach.text(Attach.fromFile(text, "testo.txt", "text/plain")).startsWith("a".repeat(Attach.MAX_TEXT)))
                text.delete()
                val large = File(ctx.cacheDir, "grande-qa.bin")
                java.io.RandomAccessFile(large, "rw").use { it.setLength(Attach.MAX_BYTES + 1) }
                val before = File(ctx.filesDir, "attach").listFiles()?.map { it.name }?.toSet().orEmpty()
                try {
                    var rejected = false
                    try { Attach.fromUri(ctx, android.net.Uri.fromFile(large)) } catch (_: IllegalStateException) { rejected = true }
                    check(rejected && File(ctx.filesDir, "attach").listFiles()?.map { it.name }?.toSet().orEmpty() == before)
                } finally { large.delete() }
            }
            checkCase("database: paginazione, ultima chat, rinomina e ordine agenti") {
                val a = seed(); val b = Agent(0, "secondo", "").also { db.save(it) }; val c = db.chats(a.id).single().id
                repeat(60) { db.add(a.id, c, "assistant", "[{\"type\":\"text\",\"text\":\"riga $it\"}]") }
                val last = db.messagesPage(c, 20, null); val previous = db.messagesPage(c, 20, last.first().first)
                check(last.size == 20 && previous.size == 20 && previous.last().first < last.first().first)
                db.newChat(a.id); check(db.lastChat(a.id) == c)
                db.title(c, "titolo nuovo"); check(db.chats(a.id).first { it.id == c }.title == "titolo nuovo")
                db.move(b.id, -1, listOf(a.id, b.id)); check(db.agents().map { it.id } == listOf(b.id, a.id))
            }
            FunctionalUiQa(test).run(::checkCase)
        } finally {
            val report = results.joinToString("\n") + "\nchecks=${results.size}; failed=$failed\n"
            File(ctx.getExternalFilesDir(null), "functional-qa/report.txt").apply { parentFile?.mkdirs() }.writeText(report)
            reset(); if (wake.isHeld) wake.release()
        }
        test.finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FUNCTIONAL_QA_RESULT=${if (failed == 0) "PASS" else "FAIL"}\nchecks=${results.size}; failed=$failed\n") })
    }
}
