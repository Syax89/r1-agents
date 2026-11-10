package dev.r1agents

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineToolUseTest {
    /** Una risposta tagliata da max_tokens a metà di una chiamata: si salva il testo, mai il tool_use senza tool_result. */
    @Test fun tagliaIToolUseIncompleti() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Ecco"))
            .put(JSONObject().put("type", "tool_use").put("id", "t1").put("name", "add_note").put("input", JSONObject()))
        val kept = withoutToolUse(content)
        assertEquals(1, kept.length())
        assertEquals("text", kept.getJSONObject(0).getString("type"))
        assertEquals(0, withoutToolUse(JSONArray().put(JSONObject().put("type", "tool_use"))).length())
    }
}
