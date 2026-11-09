package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdTableTest {
    @Test fun tabellaAllineata() {
        val out = Md.alignTables(listOf("ciao", "| a | bb |", "|---|---|", "| ccc | d |", "fine"))
        assertEquals("ciao", out.first()); assertEquals("fine", out.last())
        assertEquals("```", out[1]); assertEquals("a   │ bb", out[2]); assertTrue(out[3].contains("┼")); assertEquals("ccc │ d", out[4]); assertEquals("```", out[5])
    }
    @Test fun codiceNonToccato() {
        val src = listOf("```", "| a | b |", "|---|---|", "```")
        assertEquals(src, Md.alignTables(src))
    }
}
