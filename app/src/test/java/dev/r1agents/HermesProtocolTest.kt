package dev.r1agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesProtocolTest {
    @Test fun terminalLabel() = assertEquals("esegue un comando: ls -la", toolLabel("terminal", "ls -la"))
    @Test fun searchLabel() = assertEquals("cerca sul web: meteo roma", toolLabel("web_search", "meteo roma"))
    @Test fun unknownToolFallsBackToItsName() = assertEquals("usa my_tool", toolLabel("my_tool", ""))
    @Test fun longContextIsTruncated() { val l = toolLabel("read_file", "x".repeat(200)); assertTrue(l.length < 100 && l.endsWith("…")) }
    @Test fun newlinesInContextBecomeSpaces() = assertEquals("legge un file: a b", toolLabel("read_file", "a\nb"))

    @Test fun approvalReplyShape() {
        val j = JSONObject(approvalReply("srq-abc123", "once"))
        assertEquals("2.0", j.getString("jsonrpc")); assertEquals("srq-abc123", j.getString("id"))
        assertEquals("once", j.getJSONObject("result").getString("choice")); assertEquals(false, j.getJSONObject("result").getBoolean("all"))
    }
    @Test fun approvalReplyKeepsNumericIds() = assertEquals(7, JSONObject(approvalReply(7, "deny")).getInt("id"))
}
