package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineEventsTest {
    /** Una routine a evento non riparte prima di 10 minuti (cavo che balla, wifi che va e viene). */
    @Test fun pausaTraDuePartenze() {
        val t = 1_000_000_000L
        assertTrue(RoutineEvents.due(0L, t))
        assertFalse(RoutineEvents.due(t, t + 60_000L))
        assertTrue(RoutineEvents.due(t, t + RoutineEvents.COOLDOWN_MS))
        assertTrue(RoutineEvents.due(t + 5_000L, t))   // orologio tornato indietro: non resta bloccata
    }

    @Test fun eventiNoti() { assertEquals(listOf("carica", "scollega", "wifi", "cuffie"), RoutineEvents.TRIGGERS) }
}
