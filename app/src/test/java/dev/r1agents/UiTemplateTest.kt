package dev.r1agents

import org.junit.Assert.*
import org.junit.Test

class UiTemplateTest {
    @Test fun capturesNamesVerbatim() {
        val match = uiTemplatePattern("ingrandisci %1\$s").matchEntire("ingrandisci impostazioni")!!
        assertEquals("impostazioni", match.groupValues[1])
    }
    @Test fun preservesMultipleArgumentsAndNewlines() {
        val match = uiTemplatePattern("utente: %1\$s\n%2\$s").matchEntire("utente: Nome È\nriga uno\nriga due")!!
        assertEquals(listOf("Nome È", "riga uno\nriga due"), match.groupValues.drop(1))
    }
    @Test fun punctuationIsLiteral() {
        assertNotNull(uiTemplatePattern("errore (%1\$s) [100%]?").matchEntire("errore (rete) [100%]?"))
        assertNull(uiTemplatePattern("errore (%1\$s) [100%]?").matchEntire("errore rete 100"))
    }
    @Test fun italianRemainsDefault() {
        assertEquals("it", uiLanguageChoice(null)); assertEquals("it", uiLanguageChoice("fr")); assertEquals("en", uiLanguageChoice("en"))
    }
    @Test fun weatherIsLocalisedWithoutRewritingCityInput() {
        assertEquals("poco nuvoloso", Weather.describe(2, "it")); assertEquals("partly cloudy", Weather.describe(2, "en")); assertEquals("", Weather.describe(-1, "en"))
    }
}
