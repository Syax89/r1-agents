package dev.r1agents

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheTest {
    /** Il blocco stabile porta il punto di cache, l'ora viene dopo e non lo invalida. */
    @Test fun sistemaInDueBlocchi() {
        val sys = cachedSystem("sei utile", "\n\nOra locale: lunedì")
        assertEquals(2, sys.length())
        assertTrue(sys.getJSONObject(0).has("cache_control"))
        assertFalse(sys.getJSONObject(1).has("cache_control"))
        assertEquals(1, cachedSystem("sei utile", "").length())
    }

    /** Punti di cache: ultimo strumento e ultimo blocco della conversazione, nient'altro (massimo 4 per richiesta). */
    @Test fun puntiDiCache() {
        val tools = JSONArray().put(JSONObject().put("name", "a")).put(JSONObject().put("name", "b"))
        val msgs = JSONArray()
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ciao"))))
            .put(JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "x")).put(JSONObject().put("type", "text").put("text", "y"))))
        markCache(tools, msgs)
        assertFalse(tools.getJSONObject(0).has("cache_control"))
        assertTrue(tools.getJSONObject(1).has("cache_control"))
        assertFalse(msgs.getJSONObject(0).getJSONArray("content").getJSONObject(0).has("cache_control"))
        assertTrue(msgs.getJSONObject(1).getJSONArray("content").getJSONObject(1).has("cache_control"))
        markCache(JSONArray(), JSONArray()) // vuoti: nessun errore
    }
}
