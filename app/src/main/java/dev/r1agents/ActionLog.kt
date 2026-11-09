package dev.r1agents

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Registro delle azioni eseguite sul telefono per conto degli agenti (aprire app, timer, sveglie…). Solo locale, mai nei backup (prefisso `actlog_`). */
object ActionLog {
    const val KEY = "actlog_entries"
    const val MAX = 40
    class Entry(val time: Long, val kind: String, val arg: String, val ok: Boolean)

    /** Aggiunge una voce in testa e tiene le ultime [MAX]. Restituisce il nuovo JSON. */
    fun append(json: String?, e: Entry): String {
        val old = runCatching { JSONArray(json ?: "[]") }.getOrDefault(JSONArray())
        val out = JSONArray().put(JSONObject().put("t", e.time).put("k", e.kind).put("a", e.arg.take(120)).put("ok", e.ok))
        for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
        return out.toString()
    }

    fun parse(json: String?): List<Entry> {
        val a = runCatching { JSONArray(json ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Entry(it.optLong("t"), it.optString("k"), it.optString("a"), it.optBoolean("ok")) } }
    }

    fun record(p: SharedPreferences, kind: String, arg: String, ok: Boolean) {
        p.edit().putString(KEY, append(p.getString(KEY, null), Entry(System.currentTimeMillis(), kind, arg, ok))).apply()
    }
    fun entries(p: SharedPreferences) = parse(p.getString(KEY, null))
    fun clear(p: SharedPreferences) { p.edit().remove(KEY).apply() }
}
