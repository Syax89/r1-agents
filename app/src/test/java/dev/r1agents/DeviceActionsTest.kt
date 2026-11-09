package dev.r1agents

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceActionsTest {
    @After fun ripristino() { DeviceActions.mode = "ask" }

    @Test fun togliLeDirettiveEleElenca() {
        val p = DeviceActions.parse("Ok, ti apro il calendario.\n[[r1:apri=Calendario]]\nE metto un timer.\n[[r1:timer=10m]]")
        assertEquals("Ok, ti apro il calendario.\n\nE metto un timer.", p.text)
        assertEquals(listOf("apri=Calendario", "timer=10m"), p.actions.map { it.key })
    }
    @Test fun direttiveSconosciuteOVuoteSiIgnorano() {
        val p = DeviceActions.parse("[[r1:chiama=112]] [[r1:apri=]] [[r1:rm=-rf]] ciao")
        assertEquals(0, p.actions.size)
    }
    @Test fun direttivaParzialeNelloStreamingNonSiVede() {
        assertEquals("sto aprendo", DeviceActions.parse("sto aprendo [[r1:apri=Cal").text)
    }
    @Test fun duplicatiEMassimo() {
        val many = (1..9).joinToString(" ") { "[[r1:nota=n$it]]" } + " [[r1:nota=n1]]"
        assertEquals(5, DeviceActions.parse(many).actions.size)
        assertEquals(1, DeviceActions.parse("[[r1:torcia=on]][[r1:torcia=on]]").actions.size)
    }
    @Test fun modoSpentoNonToccaIlTesto() {
        DeviceActions.mode = "off"
        val p = DeviceActions.parse("x [[r1:apri=Calendario]]")
        assertEquals("x [[r1:apri=Calendario]]", p.text); assertEquals(0, p.actions.size)
    }
    @Test fun durate() {
        assertEquals(600, DeviceActions.seconds("10m")); assertEquals(7200, DeviceActions.seconds("2h")); assertEquals(90, DeviceActions.seconds("90"))
        assertNull(DeviceActions.seconds("1s")); assertNull(DeviceActions.seconds("99h")); assertNull(DeviceActions.seconds("abc"))
    }
    @Test fun orari() {
        assertEquals(7 to 30, DeviceActions.time("07:30")); assertEquals(19 to 0, DeviceActions.time("19")); assertEquals(7 to 5, DeviceActions.time("7.05"))
        assertNull(DeviceActions.time("25:00")); assertNull(DeviceActions.time("12:60")); assertNull(DeviceActions.time("mezzanotte"))
    }
    @Test fun istruzioniNascosteNonSiVedonoNellaCronologia() {
        val sent = DeviceActions.instruction() + "[Istruzioni per questa chat: sii breve]\n[Cose da ricordare:\n- caffè]\n\nCiao"
        assertEquals("Ciao", DeviceActions.stripIntro(sent))
        assertEquals("Grazie", DeviceActions.stripIntro(DeviceActions.instruction() + "Grazie"))
        assertEquals("Un messaggio [normale]\ncon parentesi", DeviceActions.stripIntro("Un messaggio [normale]\ncon parentesi"))
    }
    @Test fun validazioneStrumento() {
        assertNull(DeviceActions.validate("timer", "10m")); assertNull(DeviceActions.validate("apri", "Calcolatrice"))
        assertNull(DeviceActions.validate("sveglia", "07:30")); assertNull(DeviceActions.validate("pannello", "wifi"))
        assertNotNull(DeviceActions.validate("timer", "domani")); assertNotNull(DeviceActions.validate("sveglia", "25:00"))
        assertNotNull(DeviceActions.validate("chiama", "mamma")); assertNotNull(DeviceActions.validate("nota", " "))
        assertNotNull(DeviceActions.validate("pannello", "impostazioni"))
    }
}
