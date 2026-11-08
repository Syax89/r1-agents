package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryTest {
    private val old = listOf("user" to "ciao", "assistant" to "salve")

    @Test fun answerAfterOurMessageIsAccepted() {
        val h = old + listOf("user" to "[Istruzioni] fammi un riepilogo", "assistant" to "ecco il riepilogo")
        assertEquals(Recovery.Reply("ecco il riepilogo"), judgeRecovery(h, "fammi un riepilogo"))
    }

    @Test fun oldAnswerIsNeverShownWhenOurMessageNeverArrived() {
        assertEquals(Recovery.NotSent, judgeRecovery(old, "fammi un riepilogo"))
    }

    @Test fun messageArrivedButNoAnswerYet() {
        val h = old + listOf("user" to "fammi un riepilogo")
        assertEquals(Recovery.NoReply, judgeRecovery(h, "fammi un riepilogo"))
    }

    @Test fun blankAnswerDoesNotCount() {
        val h = old + listOf("user" to "domanda", "assistant" to "  ")
        assertEquals(Recovery.NoReply, judgeRecovery(h, "domanda"))
    }

    @Test fun attachmentOnlySendIsRecognised() {
        val h = old + listOf("user" to "Guarda l'allegato.", "assistant" to "e' una foto")
        assertEquals(Recovery.Reply("e' una foto"), judgeRecovery(h, ""))
    }

    @Test fun emptyHistoryMeansNotSent() {
        assertEquals(Recovery.NotSent, judgeRecovery(emptyList(), "ciao"))
    }
}
