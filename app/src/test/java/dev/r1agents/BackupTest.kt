package dev.r1agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val all: Map<String, Any?> = mapOf(
        "app_pin_hash" to "private-hash", "pin_failures" to 4, "pin_wait_until" to 99L, "provider" to "dashboard", "tts" to true, "bgd_glance" to 0.4f, "home_index" to 2, "w_at" to 12345L, "look_accent" to "teal",
        "key_anthropic" to "v1:xxxx", "groq_key" to "gsk_secret", "dash_pass" to "pw", "nous_refresh" to "tok", "nous_client_id" to "hermes-cli",
        "dash_sid_7" to "abc", "reopen" to "look", "w_text" to "Roma 18°", "gshow" to "date,alarm")

    @Test fun secretsAndTemporariesAreNeverExported() {
        val j = Backup.prefsToJson(all)
        for (k in listOf("app_pin_hash", "pin_failures", "pin_wait_until", "key_anthropic", "groq_key", "dash_pass", "nous_refresh", "nous_client_id", "dash_sid_7", "reopen", "w_text", "w_at")) assertFalse(k, j.has(k))
        for (k in listOf("provider", "tts", "bgd_glance", "home_index", "look_accent", "gshow")) assertTrue(k, j.has(k))
    }

    @Test fun typesSurviveTheTrip() {
        val j = JSONObject(Backup.prefsToJson(all).toString())          // passa da testo, come nel file
        assertEquals("b", j.getJSONObject("tts").getString("t")); assertEquals(true, j.getJSONObject("tts").getBoolean("v"))
        assertEquals("f", j.getJSONObject("bgd_glance").getString("t")); assertEquals(0.4, j.getJSONObject("bgd_glance").getDouble("v"), 1e-6)
        assertEquals("i", j.getJSONObject("home_index").getString("t")); assertEquals(2, j.getJSONObject("home_index").getInt("v"))
        assertEquals("s", j.getJSONObject("provider").getString("t"))
    }

    @Test fun importedFileCannotInjectSecrets() {
        val evil = JSONObject().put("app_pin_hash", JSONObject().put("t", "s").put("v", "injected")).put("pin_failures", JSONObject().put("t", "i").put("v", 0)).put("groq_key", JSONObject().put("t", "s").put("v", "x")).put("nous_refresh", JSONObject().put("t", "s").put("v", "x"))
            .put("dash_sid_1", JSONObject().put("t", "s").put("v", "x")).put("tts", JSONObject().put("t", "b").put("v", true)).put("bad", JSONObject().put("t", "zz").put("v", 1))
        val written = mutableListOf<String>()
        val e = object : android.content.SharedPreferences.Editor by NoopEditor { override fun putBoolean(k: String, v: Boolean) = apply { written.add(k) } }
        assertEquals(1, Backup.applyPrefs(evil, e)); assertEquals(listOf("tts"), written)
    }

    @Test fun rejectsFilesOfOtherApps() {
        for (bad in listOf("non json", "{\"app\":\"altro\"}", "{\"app\":\"r1agents\",\"version\":99}")) {
            try { Backup.parse(bad); throw AssertionError("doveva rifiutare: $bad") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.isNotBlank()) }
        }
    }

    @Test fun inspectCountsContent() {
        val f = """{"app":"r1agents","version":1,"agents":[{"name":"a","chats":[{"messages":[]},{"messages":[]}],"routines":[{"name":"r","hour":8,"minute":0}]},{"name":"b"}],"notes":[{"text":"x"}],"alarms":[{"hour":7,"minute":0},{"hour":8,"minute":0}],"settings":{"tts":{"t":"b","v":true}}}"""
        val s = Backup.inspect(f)
        assertEquals(2, s.agents); assertEquals(2, s.chats); assertEquals(1, s.notes); assertEquals(2, s.alarms); assertEquals(1, s.routines); assertEquals(1, s.settings)
    }
    @Test fun incompleteAndMalformedBackupsFailBeforeConfirmation() {
        val complete = """{"app":"r1agents","version":1,"agents":[],"notes":[],"alarms":[],"settings":{}}"""
        assertEquals(0, Backup.inspect(complete).agents)
        val bad = listOf(
            JSONObject(complete).apply { remove("notes") },
            JSONObject(complete).put("version", 0),
            JSONObject(complete).put("agents", JSONObject()),
            JSONObject(complete).put("alarms", org.json.JSONArray().put(JSONObject().put("hour", 25).put("minute", 0))),
            JSONObject(complete).put("settings", JSONObject().put("home_index", JSONObject().put("t", "i").put("v", "errato"))),
            JSONObject(complete).put("agents", org.json.JSONArray().put(JSONObject().put("chats", org.json.JSONArray().put(JSONObject().put("messages", org.json.JSONArray().put(JSONObject()))))))
        )
        for (j in bad) {
            try { Backup.inspect(j.toString()); throw AssertionError("backup invalido accettato") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.isNotBlank()) }
        }
    }

    @Test fun backupReaderStopsBeforeLoadingAnOversizeFile() {
        var consumed = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int { consumed++; return 32 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(32, off, off + len); consumed += len; return len }
        }
        try { Backup.read(input); throw AssertionError("file troppo grande accettato") }
        catch (e: IllegalArgumentException) { assertEquals(Backup.MAX_IMPORT_BYTES + 1, consumed) }
    }

}

/** Un Editor che non fa nulla: i test sovrascrivono solo ciò che controllano. */
private object NoopEditor : android.content.SharedPreferences.Editor {
    override fun putString(k: String, v: String?) = this; override fun putStringSet(k: String, v: MutableSet<String>?) = this
    override fun putInt(k: String, v: Int) = this; override fun putLong(k: String, v: Long) = this; override fun putFloat(k: String, v: Float) = this
    override fun putBoolean(k: String, v: Boolean) = this; override fun remove(k: String) = this; override fun clear() = this
    override fun commit() = true; override fun apply() {}
}
