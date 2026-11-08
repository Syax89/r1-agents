package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftsTest {
    @Test fun preservesTypedDraft() { assertEquals("appunti\nnuova frase", appendTranscript("appunti", "nuova frase")) }
    @Test fun ignoresEmptyRecognition() { assertEquals("appunti  ", appendTranscript("appunti  ", "  ")) }
    @Test fun startsWithRecognizedWords() { assertEquals("nuova frase", appendTranscript("", " nuova frase ")) }
    @Test fun preservesExistingParagraphs() { assertEquals("uno\ndue\ntre", appendTranscript("uno\ndue\n", "tre")) }
}
