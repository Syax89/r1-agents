package dev.r1agents

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Agent(var id: Long, var name: String, var prompt: String, var memory: String = "", var form: String = "orb", var color: String = "orange",
            var finish: String = "soft", var eyes: String = "classic", var wear: String = "", var profile: String = "")

class Alarm(var id: Long, var hour: Int, var minute: Int, var days: Int, var label: String, var on: Boolean)
class Timer(val id: Long, val endAt: Long, val label: String)
class Routine(var id: Long, var agentId: Long, var name: String, var prompt: String, var hour: Int, var minute: Int, var days: Int, var on: Boolean)

class Chat(val id: Long, val agentId: Long, val title: String, val updated: Long, val count: Int)

/** Agenti, chat, memorie e messaggi. I messaggi sono salvati in formato neutro (blocchi stile Anthropic). */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "agents.db", null, 7) {
    companion object {
        @Volatile private var inst: Db? = null
        /** Un'unica istanza per processo: apre il database una volta sola ed è sicura tra thread, servizi e ricevitori. */
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
        private val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_msg_chat ON messages(chat_id, id)",
            "CREATE INDEX IF NOT EXISTS idx_chat_agent ON chats(agent_id, updated)",
            "CREATE INDEX IF NOT EXISTS idx_chat_hsid ON chats(agent_id, hsid)",
            "CREATE INDEX IF NOT EXISTS idx_mem_agent ON memories(agent_id, id)")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE agents(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, prompt TEXT, memory TEXT DEFAULT '', form TEXT DEFAULT 'orb', color TEXT DEFAULT 'orange', finish TEXT DEFAULT 'soft', eyes TEXT DEFAULT 'classic', wear TEXT DEFAULT '', pos INTEGER DEFAULT 0, profile TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id INTEGER, title TEXT DEFAULT '', updated INTEGER DEFAULT 0, hsid TEXT DEFAULT '', mcount INTEGER DEFAULT 0, ucount INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id INTEGER, chat_id INTEGER DEFAULT 0, role TEXT, content TEXT)")
        db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id INTEGER, text TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE alarms(id INTEGER PRIMARY KEY AUTOINCREMENT, hour INTEGER, minute INTEGER, days INTEGER DEFAULT 0, label TEXT DEFAULT '', on_ INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE timers(id INTEGER PRIMARY KEY AUTOINCREMENT, end_at INTEGER, label TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE routines(id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id INTEGER, name TEXT, prompt TEXT, hour INTEGER, minute INTEGER, days INTEGER DEFAULT 0, on_ INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, ts INTEGER)")
        INDEXES.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) for (c in listOf("finish TEXT DEFAULT 'soft'", "eyes TEXT DEFAULT 'classic'", "wear TEXT DEFAULT ''")) db.execSQL("ALTER TABLE agents ADD COLUMN $c")
        if (o < 3) {
            db.execSQL("ALTER TABLE agents ADD COLUMN pos INTEGER DEFAULT 0")
            db.execSQL("UPDATE agents SET pos = id")
            db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id INTEGER, title TEXT DEFAULT '', updated INTEGER DEFAULT 0)")
            db.execSQL("ALTER TABLE messages ADD COLUMN chat_id INTEGER DEFAULT 0")
            db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id INTEGER, text TEXT, ts INTEGER)")
            // una chat per agente con i messaggi che aveva; la vecchia memoria diventa una nota per riga
            db.rawQuery("SELECT DISTINCT agent_id FROM messages", null).use { c ->
                while (c.moveToNext()) {
                    val a = c.getLong(0)
                    val chat = db.insertOrThrow("chats", null, ContentValues().apply { put("agent_id", a); put("title", "chat"); put("updated", System.currentTimeMillis()) })
                    db.execSQL("UPDATE messages SET chat_id=? WHERE agent_id=?", arrayOf(chat, a))
                }
            }
            db.rawQuery("SELECT id, memory FROM agents WHERE memory <> ''", null).use { c ->
                while (c.moveToNext()) c.getString(1).lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }.forEach {
                    db.insertOrThrow("memories", null, ContentValues().apply { put("agent_id", c.getLong(0)); put("text", it); put("ts", System.currentTimeMillis()) })
                }
            }
        }
        if (o < 4) {
            db.execSQL("CREATE TABLE alarms(id INTEGER PRIMARY KEY AUTOINCREMENT, hour INTEGER, minute INTEGER, days INTEGER DEFAULT 0, label TEXT DEFAULT '', on_ INTEGER DEFAULT 1)")
            db.execSQL("CREATE TABLE timers(id INTEGER PRIMARY KEY AUTOINCREMENT, end_at INTEGER, label TEXT DEFAULT '')")
            db.execSQL("CREATE TABLE routines(id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id INTEGER, name TEXT, prompt TEXT, hour INTEGER, minute INTEGER, days INTEGER DEFAULT 0, on_ INTEGER DEFAULT 1)")
        }
        if (o < 6) {
            db.execSQL("ALTER TABLE agents ADD COLUMN profile TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE chats ADD COLUMN hsid TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE chats ADD COLUMN mcount INTEGER DEFAULT 0")
        }
        if (o < 7) {
            // contatore dei messaggi dell'utente già calcolato (prima era una sotto-query con LIKE per ogni chat) e indici
            db.execSQL("ALTER TABLE chats ADD COLUMN ucount INTEGER DEFAULT 0")
            db.execSQL("UPDATE chats SET ucount = (SELECT COUNT(*) FROM messages m WHERE m.chat_id=chats.id AND m.role='user' AND m.content LIKE '[{\"type\":\"text\"%')")
            INDEXES.forEach { db.execSQL(it) }
        }
        if (o < 5) db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, ts INTEGER)")
    }

    // ---- agenti ---------------------------------------------------------------------------------

    fun agents(): List<Agent> = readableDatabase.rawQuery("SELECT id,name,prompt,memory,form,color,finish,eyes,wear,profile FROM agents ORDER BY pos, id", null).use { c ->
        buildList { while (c.moveToNext()) add(Agent(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9))) }
    }

    fun save(a: Agent) {
        val v = ContentValues().apply { put("name", a.name); put("prompt", a.prompt); put("memory", a.memory); put("form", a.form); put("color", a.color); put("finish", a.finish); put("eyes", a.eyes); put("wear", a.wear); put("profile", a.profile) }
        if (a.id == 0L) {
            val next = readableDatabase.rawQuery("SELECT COALESCE(MAX(pos),0)+1 FROM agents", null).use { it.moveToFirst(); it.getInt(0) }
            v.put("pos", next); a.id = writableDatabase.insertOrThrow("agents", null, v)
        } else writableDatabase.update("agents", v, "id=?", arrayOf(a.id.toString()))
    }

    /** Sposta l'agente prima (-1) o dopo (+1) rispetto agli altri agenti visibili (`visible`, nell'ordine della home). */
    fun move(id: Long, dir: Int, visible: List<Long>) {
        val i = visible.indexOf(id); val j = i + dir
        if (i < 0 || j !in visible.indices) return
        val pos = readableDatabase.rawQuery("SELECT id,pos FROM agents", null).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) } }
        val a = pos[id] ?: return; val b = pos[visible[j]] ?: return
        writableDatabase.execSQL("UPDATE agents SET pos=? WHERE id=?", arrayOf(b, id)); writableDatabase.execSQL("UPDATE agents SET pos=? WHERE id=?", arrayOf(a, visible[j]))
        if (a == b) { val ids = agents().map { it.id }.toMutableList(); ids.remove(id); ids.add(ids.indexOf(visible[j]).let { if (dir > 0) it + 1 else it }, id); ids.forEachIndexed { p, x -> writableDatabase.execSQL("UPDATE agents SET pos=? WHERE id=?", arrayOf(p, x)) } }
    }

    fun delete(id: Long) {
        val sql = writableDatabase
        sql.beginTransaction()
        try {
            for (table in listOf("messages", "chats", "memories", "routines")) sql.delete(table, "agent_id=?", arrayOf(id.toString()))
            sql.delete("agents", "id=?", arrayOf(id.toString()))
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    // ---- chat -----------------------------------------------------------------------------------

    fun chats(agentId: Long): List<Chat> = readableDatabase.rawQuery(
        "SELECT id, title, updated, MAX(mcount, ucount) FROM chats WHERE agent_id=? ORDER BY updated DESC, id DESC",
        arrayOf(agentId.toString())).use { c -> buildList { while (c.moveToNext()) add(Chat(c.getLong(0), agentId, c.getString(1), c.getLong(2), c.getInt(3))) } }

    fun newChat(agentId: Long): Long = writableDatabase.insertOrThrow("chats", null, ContentValues().apply {
        put("agent_id", agentId); put("title", ""); put("updated", System.currentTimeMillis()) })

    /** L'ultima chat con contenuto (una sessione Hermes o con messaggi); solo se non ce n'è, una vuota. Le chat vuote create con "nuova" non coprono quelle vere. */
    fun lastChat(agentId: Long): Long? = readableDatabase.rawQuery(
        "SELECT id FROM chats WHERE agent_id=? ORDER BY (hsid<>'' OR mcount>0 OR ucount>0) DESC, updated DESC, id DESC LIMIT 1", arrayOf(agentId.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun title(chatId: Long, title: String) { writableDatabase.execSQL("UPDATE chats SET title=? WHERE id=?", arrayOf(title, chatId)) }

    fun deleteChat(chatId: Long) {
        writableDatabase.delete("messages", "chat_id=?", arrayOf(chatId.toString()))
        writableDatabase.delete("chats", "id=?", arrayOf(chatId.toString()))
    }

    fun messages(chatId: Long): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT role,content FROM messages WHERE chat_id=? ORDER BY id", arrayOf(chatId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    /** Una pagina di messaggi (id, ruolo, contenuto) in ordine cronologico: i `limit` più recenti prima di `beforeId` (o gli ultimi). */
    fun messagesPage(chatId: Long, limit: Int, beforeId: Long?): List<Triple<Long, String, String>> {
        val sql = "SELECT id,role,content FROM messages WHERE chat_id=?" + (if (beforeId != null) " AND id<?" else "") + " ORDER BY id DESC LIMIT ?"
        val args = if (beforeId != null) arrayOf(chatId.toString(), beforeId.toString(), limit.toString()) else arrayOf(chatId.toString(), limit.toString())
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) } }.asReversed()
    }

    fun add(agentId: Long, chatId: Long, role: String, content: String) {
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply { put("agent_id", agentId); put("chat_id", chatId); put("role", role); put("content", content) })
        val userText = role == "user" && content.startsWith("[{\"type\":\"text\"")
        writableDatabase.execSQL("UPDATE chats SET updated=?, ucount=ucount+? WHERE id=?", arrayOf(System.currentTimeMillis(), if (userText) 1 else 0, chatId))
    }

    // ---- memoria --------------------------------------------------------------------------------

    fun memories(agentId: Long): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT id,text FROM memories WHERE agent_id=? ORDER BY id", arrayOf(agentId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }

    fun addMemory(agentId: Long, text: String) {
        writableDatabase.insertOrThrow("memories", null, ContentValues().apply { put("agent_id", agentId); put("text", text.trim().take(500)); put("ts", System.currentTimeMillis()) })
        // al massimo 60 note: le più vecchie lasciano il posto
        writableDatabase.execSQL("DELETE FROM memories WHERE agent_id=? AND id NOT IN (SELECT id FROM memories WHERE agent_id=? ORDER BY id DESC LIMIT 60)", arrayOf(agentId, agentId))
    }

    fun deleteMemory(id: Long) { writableDatabase.delete("memories", "id=?", arrayOf(id.toString())) }

    // ---- sveglie, timer, routine ----------------------------------------------------------------

    fun alarms(): List<Alarm> = readableDatabase.rawQuery("SELECT id,hour,minute,days,label,on_ FROM alarms ORDER BY hour, minute", null).use { c ->
        buildList { while (c.moveToNext()) add(Alarm(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getString(4), c.getInt(5) == 1)) } }
    fun alarm(id: Long) = alarms().firstOrNull { it.id == id }
    fun save(a: Alarm) {
        val v = ContentValues().apply { put("hour", a.hour); put("minute", a.minute); put("days", a.days); put("label", a.label); put("on_", if (a.on) 1 else 0) }
        if (a.id == 0L) a.id = writableDatabase.insertOrThrow("alarms", null, v) else writableDatabase.update("alarms", v, "id=?", arrayOf(a.id.toString()))
    }
    fun deleteAlarm(id: Long) { writableDatabase.delete("alarms", "id=?", arrayOf(id.toString())) }

    fun timers(): List<Timer> = readableDatabase.rawQuery("SELECT id,end_at,label FROM timers ORDER BY end_at", null).use { c ->
        buildList { while (c.moveToNext()) add(Timer(c.getLong(0), c.getLong(1), c.getString(2))) } }
    fun addTimer(endAt: Long, label: String): Long = writableDatabase.insertOrThrow("timers", null, ContentValues().apply { put("end_at", endAt); put("label", label) })
    fun deleteTimer(id: Long) { writableDatabase.delete("timers", "id=?", arrayOf(id.toString())) }

    fun routines(): List<Routine> = readableDatabase.rawQuery("SELECT id,agent_id,name,prompt,hour,minute,days,on_ FROM routines ORDER BY hour, minute", null).use { c ->
        buildList { while (c.moveToNext()) add(Routine(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7) == 1)) } }
    fun routine(id: Long) = routines().firstOrNull { it.id == id }
    fun save(r: Routine) {
        val v = ContentValues().apply { put("agent_id", r.agentId); put("name", r.name); put("prompt", r.prompt); put("hour", r.hour); put("minute", r.minute); put("days", r.days); put("on_", if (r.on) 1 else 0) }
        if (r.id == 0L) r.id = writableDatabase.insertOrThrow("routines", null, v) else writableDatabase.update("routines", v, "id=?", arrayOf(r.id.toString()))
    }
    fun deleteRoutine(id: Long) { writableDatabase.delete("routines", "id=?", arrayOf(id.toString())) }

    // ---- note -----------------------------------------------------------------------------------

    fun notes(): List<Triple<Long, String, Long>> = readableDatabase.rawQuery("SELECT id,text,ts FROM notes ORDER BY id DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getLong(2))) } }
    fun addNote(text: String, timestamp: Long = System.currentTimeMillis()) { writableDatabase.insertOrThrow("notes", null, ContentValues().apply { put("text", text.trim().take(2000)); put("ts", timestamp) }) }
    fun deleteNote(id: Long) { writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }

    // ---- Hermes: profili e sessioni lette dal dashboard ------------------------------------------

    /** La chat locale collegata a una sessione Hermes (la crea se non c'è) e i suoi dati. */
    fun upsertChat(agentId: Long, hsid: String, title: String, updated: Long, count: Int): Long {
        val found = readableDatabase.rawQuery("SELECT id FROM chats WHERE agent_id=? AND hsid=?", arrayOf(agentId.toString(), hsid)).use { if (it.moveToFirst()) it.getLong(0) else null }
        val v = ContentValues().apply { put("title", title); put("updated", updated); put("mcount", count) }
        if (found != null) { writableDatabase.update("chats", v, "id=?", arrayOf(found.toString())); return found }
        v.put("agent_id", agentId); v.put("hsid", hsid)
        return writableDatabase.insertOrThrow("chats", null, v)
    }
    fun setHsid(chatId: Long, hsid: String) { writableDatabase.execSQL("UPDATE chats SET hsid=? WHERE id=?", arrayOf(hsid, chatId)) }
    fun hsid(chatId: Long): String = readableDatabase.rawQuery("SELECT hsid FROM chats WHERE id=?", arrayOf(chatId.toString())).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }

    /** Sostituisce i messaggi di una chat con la cronologia letta da Hermes (solo utente e assistente, come testo). */
    fun replaceMessages(agentId: Long, chatId: Long, msgs: List<Pair<String, String>>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", "chat_id=?", arrayOf(chatId.toString()))
            for ((role, text) in msgs) add(agentId, chatId, role, org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text", text)).toString())
            writableDatabase.execSQL("UPDATE chats SET ucount=? WHERE id=?", arrayOf(msgs.count { it.first == "user" }, chatId))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun agentByProfile(profile: String): Agent? = agents().firstOrNull { it.profile == profile }

    /** Imposta la data di ultimo aggiornamento di una chat (per l'importazione: `add` la porrebbe a "adesso"). */
    fun touchChat(chatId: Long, updated: Long) { writableDatabase.execSQL("UPDATE chats SET updated=? WHERE id=?", arrayOf(updated, chatId)) }

    /** Cancella tutti i dati dell'app (agenti, chat, messaggi, memorie, note, sveglie, timer, routine). Per "sostituisci tutto" nell'importazione. */
    fun wipeAll() {
        writableDatabase.beginTransaction()
        try { for (t in listOf("agents", "chats", "messages", "memories", "notes", "alarms", "timers", "routines")) writableDatabase.delete(t, null, null); writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
}
