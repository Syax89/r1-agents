package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddyEntrancesTest {
    @Test fun repertorioAbbastanzaVarioEConNomiUnici() {
        assertTrue("almeno 10 comparse", BuddyEntrances.IN.size >= 10)
        assertTrue("almeno 6 sparizioni", BuddyEntrances.OUT.size >= 6)
        val ids = (BuddyEntrances.IN + BuddyEntrances.OUT).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
    @Test fun ognunoSiTrovaPerNome() {
        (BuddyEntrances.IN + BuddyEntrances.OUT).forEach { assertTrue(BuddyEntrances.find(it.id) === it) }
    }
    @Test fun lacomparsaCasualeEvitaLaPrecedente() {
        repeat(200) { assertTrue(BuddyEntrances.randomIn("pop").id != "pop") }
    }
}
