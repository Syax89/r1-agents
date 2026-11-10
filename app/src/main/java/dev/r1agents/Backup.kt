package dev.r1agents

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Esporta e importa i dati dell'app in un file JSON: agenti (con aspetto, memorie, chat locali e routine), note, sveglie e impostazioni.
 * Restano fuori i segreti (chiavi API, password del dashboard, token di Nous, chiave Groq: vanno reinseriti), le chat di Hermes (stanno sul PC),
 * gli allegati, le foto di sfondo, i timer in corso e le cache.
 */
object Backup {
    const val VERSION = 1
    const val MAX_IMPORT_BYTES = 20_000_000

    /** Legge fino al limite, senza caricare in memoria un file arbitrariamente grande. */
    fun read(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, MAX_IMPORT_BYTES - total + 1))
            if (n < 0) break
            total += n
            if (total > MAX_IMPORT_BYTES) throw IllegalArgumentException("File troppo grande.")
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
    /** Impostazioni che decidono verso quale server partono chiavi e password: mai da un file importato. */
    internal fun isDestination(k: String) = k.startsWith("base_") || k.startsWith("dash_") || k == "update_url" || k == "nous_client_id" || k == "dev_actions" || k == "ctx_phone" || k == "cron_notify"
    private val skipPrefix = listOf("nous_", "dash_sid_", "w_", "reopen", "app_pin_", "pin_", "running_", "pending_", "actlog_", "draft_", "hint_", "asked_")

    class Summary(val agents: Int, val chats: Int, val notes: Int, val alarms: Int, val routines: Int, val settings: Int) {
        override fun toString() = "$agents agenti, $chats chat, $notes note, $alarms sveglie, $routines routine e $settings impostazioni"
    }

    // ---- impostazioni (pure: testabili) ----------------------------------------------------------

    /** Le impostazioni da salvare, con il tipo; senza segreti né dati temporanei. */
    fun prefsToJson(all: Map<String, Any?>): JSONObject {
        val out = JSONObject()
        for ((k, v) in all.toSortedMap()) {
            if (k in Vault.SECRET_KEYS || skipPrefix.any { k.startsWith(it) } || v == null) continue
            val (type, value) = when (v) { is Boolean -> "b" to v; is Float -> "f" to v.toDouble(); is Int -> "i" to v; is Long -> "l" to v; is String -> "s" to v; else -> continue }
            out.put(k, JSONObject().put("t", type).put("v", value))
        }
        return out
    }

    /** Applica impostazioni lette da un file: solo tipi supportati, mai segreti o dati temporanei. Ritorna quante ne ha applicate. */
    /**
     * Un backup puo' venire da chiunque: un blocco "attachment" con un percorso a piacere farebbe leggere e inviare al
     * fornitore un file privato dell'app. All'import il percorso si toglie (resta il nome: "allegato non piu' disponibile").
     */
    fun stripAttachmentPaths(content: String): String = runCatching {
        val blocks = JSONArray(content)
        var changed = false
        for (i in 0 until blocks.length()) blocks.optJSONObject(i)?.let { b -> if (b.optString("type") == "attachment" && b.has("path")) { b.remove("path"); changed = true } }
        if (changed) blocks.toString() else content
    }.getOrDefault(content)

    fun applyPrefs(j: JSONObject, e: SharedPreferences.Editor): Int {
        var n = 0
        for (k in j.keys()) {
            if (k in Vault.SECRET_KEYS || skipPrefix.any { k.startsWith(it) } || isDestination(k)) continue   // un file importato non può cambiare dove vanno le chiavi
            val o = j.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v")); "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "i" -> e.putInt(k, o.getInt("v")); "l" -> e.putLong(k, o.getLong("v")); "s" -> e.putString(k, o.getString("v"))
                else -> continue
            }
            n++
        }
        return n
    }

    // ---- esportazione -----------------------------------------------------------------------------

    fun export(db: Db, prefs: SharedPreferences): Pair<String, Summary> {
        val agents = JSONArray(); var chatCount = 0
        val allRoutines = db.routines()
        for (a in db.agents()) {
            val chats = JSONArray()
            if (a.profile.isEmpty()) for (c in db.chats(a.id)) {          // le chat di Hermes restano sul PC
                val msgs = JSONArray(); db.messages(c.id).forEach { (role, content) -> msgs.put(JSONObject().put("role", role).put("content", content)) }
                if (msgs.length() == 0) continue
                chats.put(JSONObject().put("title", c.title).put("updated", c.updated).put("messages", msgs)); chatCount++
            }
            val routines = JSONArray()
            allRoutines.filter { it.agentId == a.id }.forEach { r -> routines.put(JSONObject().put("name", r.name).put("prompt", r.prompt).put("hour", r.hour).put("minute", r.minute).put("days", r.days).put("on", r.on)) }
            agents.put(JSONObject().put("name", a.name).put("prompt", a.prompt).put("form", a.form).put("color", a.color).put("finish", a.finish).put("eyes", a.eyes).put("wear", a.wear).put("profile", a.profile)
                .put("memories", JSONArray(db.memories(a.id).map { it.second })).put("chats", chats).put("routines", routines))
        }
        val notes = JSONArray(); db.notes().asReversed().forEach { notes.put(JSONObject().put("text", it.second).put("ts", it.third)) }
        val alarms = JSONArray(); db.alarms().forEach { a -> alarms.put(JSONObject().put("hour", a.hour).put("minute", a.minute).put("days", a.days).put("label", a.label).put("on", a.on)) }
        val settings = prefsToJson(prefs.all)
        val root = JSONObject().put("app", "r1agents").put("version", VERSION).put("exported", System.currentTimeMillis())
            .put("agents", agents).put("notes", notes).put("alarms", alarms).put("settings", settings)
        return root.toString(1) to Summary(agents.length(), chatCount, notes.length(), alarms.length(), allRoutines.size, settings.length())
    }

    // ---- importazione -----------------------------------------------------------------------------

    /** Legge il file e dice cosa contiene, senza toccare nulla. Lancia con un messaggio chiaro se non è un file valido. */
    fun inspect(json: String): Summary {
        val j = parse(json)
        val agents = j.optJSONArray("agents") ?: JSONArray()
        var chats = 0; var routines = 0
        for (i in 0 until agents.length()) { chats += agents.getJSONObject(i).optJSONArray("chats")?.length() ?: 0; routines += agents.getJSONObject(i).optJSONArray("routines")?.length() ?: 0 }
        return Summary(agents.length(), chats, j.optJSONArray("notes")?.length() ?: 0, j.optJSONArray("alarms")?.length() ?: 0, routines, j.optJSONObject("settings")?.length() ?: 0)
    }

    fun parse(json: String): JSONObject {
        if (json.length > MAX_IMPORT_BYTES) throw IllegalArgumentException("File troppo grande.")
        val j = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("Il file non è un'esportazione di R1 Agents.") }
        if (j.optString("app") != "r1agents") throw IllegalArgumentException("Il file non è un'esportazione di R1 Agents.")
        if (j.optInt("version") > VERSION) throw IllegalArgumentException("Il file viene da una versione più recente dell'app: aggiorna l'app.")
        try { validate(j) } catch (e: Exception) { throw IllegalArgumentException("Il backup contiene dati mancanti o non validi.", e) }
        return j
    }

    /** La conferma e l'importazione leggono la stessa struttura completa prima di modificare dati. */
    private fun validate(j: JSONObject) {
        require(j.getInt("version") == VERSION)
        fun strings(o: JSONObject, vararg keys: String) { for (key in keys) if (o.has(key)) require(o.get(key) is String) }
        fun rows(o: JSONObject, key: String, required: Boolean = false, row: (JSONObject) -> Unit) {
            if (!required && !o.has(key)) return
            val a = o.getJSONArray(key)
            for (i in 0 until a.length()) row(a.getJSONObject(i))
        }
        fun schedule(o: JSONObject) {
            require(o.getInt("hour") in 0..23 && o.getInt("minute") in 0..59 && o.optInt("days") in 0..127)
            if (o.has("on")) require(o.get("on") is Boolean)
        }
        rows(j, "agents", true) { a ->
            strings(a, "name", "prompt", "form", "color", "finish", "eyes", "wear", "profile")
            if (a.has("memories")) a.getJSONArray("memories").let { m -> for (i in 0 until m.length()) require(m.get(i) is String) }
            rows(a, "chats") { c ->
                strings(c, "title")
                if (c.has("updated")) require(c.getLong("updated") >= 0)
                rows(c, "messages", true) { m ->
                    require(m.get("role") is String && m.getString("role").isNotBlank())
                    require(m.get("content") is String)
                    val blocks = JSONArray(m.getString("content"))
                    for (i in 0 until blocks.length()) require(blocks.getJSONObject(i).get("type") is String)
                }
            }
            rows(a, "routines") { r -> schedule(r); strings(r, "name", "prompt") }
        }
        rows(j, "notes", true) { n -> require(n.get("text") is String); if (n.has("ts")) require(n.getLong("ts") >= 0) }
        rows(j, "alarms", true) { a -> schedule(a); strings(a, "label") }
        val settings = j.getJSONObject("settings")
        for (key in settings.keys()) {
            if (key in Vault.SECRET_KEYS || skipPrefix.any { key.startsWith(it) }) continue
            val o = settings.getJSONObject(key)
            when (o.optString("t")) {
                "b" -> o.getBoolean("v"); "i" -> o.getInt("v"); "l" -> o.getLong("v")
                "f" -> require(o.getDouble("v").toFloat().isFinite()); "s" -> require(o.get("v") is String)
            }
        }
    }

    /** `replace`: prima si cancella tutto (e si annullano sveglie e routine programmate); altrimenti si aggiunge ai dati attuali. */
    fun import(ctx: Context, db: Db, prefs: SharedPreferences, json: String, replace: Boolean): Summary {
        val j = parse(json)
        val oldAlarms = if (replace) db.alarms() else emptyList()
        val oldRoutines = if (replace) db.routines() else emptyList()
        val oldTimers = if (replace) db.timers() else emptyList()
        val sql = db.writableDatabase
        var agentsN = 0; var chatsN = 0; var routinesN = 0
        sql.beginTransaction()
        try {
            if (replace) db.wipeAll()
            val agents = j.getJSONArray("agents")
            for (i in 0 until agents.length()) {
                val o = agents.getJSONObject(i); val profile = o.optString("profile")
                val existing = if (profile.isNotEmpty()) db.agentByProfile(profile) else null
                val a = existing ?: Agent(0, o.optString("name", "agente"), o.optString("prompt"), "", "orb", "orange", "soft", "classic", "", profile)
                a.form = o.optString("form", a.form); a.color = o.optString("color", a.color); a.finish = o.optString("finish", a.finish); a.eyes = o.optString("eyes", a.eyes); a.wear = o.optString("wear", a.wear)
                if (existing == null) { a.name = o.optString("name", a.name); a.prompt = o.optString("prompt", a.prompt) }
                db.save(a); agentsN++
                o.optJSONArray("memories")?.let { m -> for (k in 0 until m.length()) db.addMemory(a.id, m.getString(k)) }
                o.optJSONArray("chats")?.let { cs ->
                    for (k in 0 until cs.length()) {
                        val c = cs.getJSONObject(k); val chat = db.newChat(a.id); db.title(chat, c.optString("title"))
                        c.optJSONArray("messages")?.let { ms -> for (q in 0 until ms.length()) ms.getJSONObject(q).let { db.add(a.id, chat, it.getString("role"), stripAttachmentPaths(it.getString("content"))) } }
                        db.touchChat(chat, c.optLong("updated", System.currentTimeMillis())); chatsN++
                    }
                }
                o.optJSONArray("routines")?.let { rs ->
                    for (k in 0 until rs.length()) { val r = rs.getJSONObject(k); db.save(Routine(0, a.id, r.optString("name", "routine"), r.optString("prompt"), r.optInt("hour"), r.optInt("minute"), r.optInt("days"), r.optBoolean("on", true))); routinesN++ }
                }
            }
            j.optJSONArray("notes")?.let { n -> for (k in 0 until n.length()) n.getJSONObject(k).let { db.addNote(it.getString("text"), it.optLong("ts", System.currentTimeMillis())) } }
            j.optJSONArray("alarms")?.let { al -> for (k in 0 until al.length()) { val x = al.getJSONObject(k); db.save(Alarm(0, x.optInt("hour"), x.optInt("minute"), x.optInt("days"), x.optString("label"), x.optBoolean("on", true))) } }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        // I vecchi allarmi restano validi se il database rifiuta l'importazione.
        oldAlarms.forEach { Sched.cancel(ctx, Sched.KIND_ALARM, it.id) }
        oldRoutines.forEach { Sched.cancel(ctx, Sched.KIND_ROUTINE, it.id) }
        oldTimers.forEach { Sched.cancel(ctx, Sched.KIND_TIMER, it.id) }
        val e = prefs.edit(); val settingsN = applyPrefs(j.getJSONObject("settings"), e); e.apply()
        Sched.all(ctx, db)
        return Summary(agentsN, chatsN, j.optJSONArray("notes")?.length() ?: 0, j.optJSONArray("alarms")?.length() ?: 0, routinesN, settingsN)
    }
}
