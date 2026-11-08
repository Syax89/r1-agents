package dev.r1agents

import org.junit.Assert.*
import org.junit.Test

class VoiceSpeechTest {
    @Test fun longReplyKeepsItsEndingWithinAndroidLimits() {
        val text = (1..2400).joinToString(" ") { "parola$it" } + " Fine della risposta."
        val chunks = VoiceSpeech.chunks(text, 4000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length in 1..4000 })
        assertEquals(text, chunks.joinToString(" "))
    }
    @Test fun unbrokenTextAndEmojiNeverLoseCharacters() {
        val text = "a" + "🙂".repeat(47) + "z"
        val chunks = VoiceSpeech.chunks(text, 10)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 10 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
    }
    @Test fun codeOnlyReplyDoesNotAskTtsToSpeakEmptyText() {
        assertEquals(emptyList<String>(), VoiceSpeech.chunks("```kotlin\nval x = 1\n```", 4000))
    }
}
