package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherCommandTest {
    private val apps = listOf("Calcolatrice", "Calendario", "Camera", "Chrome", "Impostazioni", "Orologio", "Telefono")
    @Test fun nomeIntero() { assertEquals(0, appForCommand("apri calcolatrice", apps)); assertEquals(5, appForCommand("Avvia l'orologio", apps)); assertEquals(2, appForCommand("open camera", apps)) }
    @Test fun articoliEPunteggiatura() { assertEquals(1, appForCommand("apri il calendario.", apps)); assertEquals(6, appForCommand("  apri  il  telefono! ", apps)) }
    @Test fun inizioUnico() { assertEquals(4, appForCommand("apri impost", apps)) }
    @Test fun ambiguoNonApre() { assertNull(appForCommand("apri cal", apps)); assertNull(appForCommand("apri ca", apps)) }
    @Test fun nonEUnComando() { assertNull(appForCommand("che cos'è un calendario?", apps)); assertNull(appForCommand("apri il file di ieri", apps)); assertNull(appForCommand("apri", apps)) }
    @Test fun accentiIgnorati() { assertEquals(0, appForCommand("apri calcolatrice", listOf("Càlcolatrice", "Camera"))); assertNull(appForCommand("apri calcolatrice", listOf("Calcolatrice", "Càlcolatrice"))) }
}
