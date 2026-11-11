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

    @Test fun livelli() {
        assertEquals(50, DeviceActions.level("50")); assertEquals(70, DeviceActions.level("70%"))
        assertEquals(DeviceActions.UP, DeviceActions.level("su")); assertEquals(DeviceActions.DOWN, DeviceActions.level("giù")); assertEquals(DeviceActions.MUTE, DeviceActions.level("muto"))
        assertNull(DeviceActions.level("101")); assertNull(DeviceActions.level("forte"))
        assertNull(DeviceActions.validate("volume", "muto")); assertNotNull(DeviceActions.validate("luminosita", "muto")); assertNotNull(DeviceActions.validate("volume", "200"))
    }
    @Test fun media() {
        assertNull(DeviceActions.validate("media", "pausa")); assertNotNull(DeviceActions.validate("media", "spegni"))
    }
    @Test fun promemoria() {
        assertEquals(Triple(18, 30, "chiama Anna"), DeviceActions.reminder("18:30 chiama Anna"))
        assertNull(DeviceActions.reminder("chiama Anna")); assertNull(DeviceActions.reminder("25:00 x")); assertNull(DeviceActions.reminder("18:30"))
    }
    @Test fun eventi() {
        val now = java.time.LocalDateTime.of(2026, 11, 11, 16, 0)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 20, 15, 0) to "dentista", DeviceActions.event("2026-11-20 15:00 dentista", now))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 12, 9, 0) to "riunione", DeviceActions.event("9:00 riunione", now))   // già passato: domani
        assertEquals(java.time.LocalDateTime.of(2026, 11, 11, 18, 0) to "cena", DeviceActions.event("18 cena", now))
        assertNull(DeviceActions.event("2026-13-40 10:00 x", now)); assertNull(DeviceActions.event("domani dentista", now))
    }
    @Test fun nuoveDirettive() {
        val p = DeviceActions.parse("ok [[r1:volume=30]] [[r1:media=pausa]] [[r1:promemoria=18:30 chiama Anna]]")
        assertEquals(listOf("volume=30", "media=pausa", "promemoria=18:30 chiama Anna"), p.actions.map { it.key })
        assertEquals(true, DeviceActions.instruction().endsWith("Non esiste nessun'altra azione.]\n"))
    }
}
