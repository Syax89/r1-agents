package dev.r1agents

import org.junit.Assert.*
import org.junit.Test

class TurnProgressTest {
    @Test fun reattachingGetsEntireStream() {
        val turn = TurnProgress(); turn.append("ciao ")
        val first = mutableListOf<TurnProgress.Snapshot>(); val remove = turn.subscribe { first.add(it) }
        turn.append("mondo"); remove(); turn.append("!")
        assertEquals("ciao mondo", first.last().text)
        val second = mutableListOf<TurnProgress.Snapshot>(); turn.subscribe { second.add(it) }
        assertEquals("ciao mondo!", second.single().text)
    }
    @Test fun cancellationRejectsLateOutput() {
        val turn = TurnProgress(); turn.append("parziale"); turn.cancel(); val revision = turn.snapshot().revision
        turn.append("tardivo"); turn.finish()
        assertEquals(TurnProgress.Status.CANCELLED, turn.snapshot().status)
        assertEquals("parziale", turn.snapshot().text); assertEquals(revision, turn.snapshot().revision)
    }
    @Test fun errorsCannotBecomeSuccess() {
        val turn = TurnProgress(); turn.fail("rete"); turn.finish()
        assertEquals(TurnProgress.Status.ERROR, turn.snapshot().status); assertEquals("rete", turn.snapshot().error)
    }
    @Test fun revisionsIncreaseAndListenerFailuresAreIsolated() {
        val turn = TurnProgress(); turn.subscribe { if (it.revision > 0) error("listener chiuso") }
        val revisions = mutableListOf<Long>(); turn.subscribe { revisions.add(it.revision) }
        turn.activity("leggo"); turn.append("ok"); turn.finish()
        assertEquals(listOf(0L, 1L, 2L, 3L), revisions)
        assertEquals(TurnProgress.Status.DONE, turn.snapshot().status)
    }
}
