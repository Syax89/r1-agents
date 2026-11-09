package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneContextTest {
    @Test fun testoCompletoESenzaDati() {
        assertEquals("", PhoneContext.format(null, false, null, null, 0, emptyList()))
        val t = PhoneContext.format(42, true, "wifi", "07:30", 2, listOf("Calcolatrice", "Mappe"))
        assertTrue(t.contains("batteria 42% (in carica)") && t.contains("rete: wifi") && t.contains("prossima sveglia 07:30") && t.contains("2 timer attivi") && t.contains("Calcolatrice, Mappe"))
    }
    @Test fun prossimaSveglia() {
        val a = listOf(Alarm(1, 6, 0, 0, "", true), Alarm(2, 23, 0, 0, "", true), Alarm(3, 12, 0, 0, "", false))
        assertEquals("23:00", PhoneContext.nextAlarm(a, 22, 0)); assertEquals("06:00", PhoneContext.nextAlarm(a, 23, 30))
        assertEquals(null, PhoneContext.nextAlarm(listOf(Alarm(3, 12, 0, 0, "", false)), 8, 0))
    }
}
