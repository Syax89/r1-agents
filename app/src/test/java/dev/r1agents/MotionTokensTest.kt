package dev.r1agents

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Nessuna transizione e' lasciata al caso (DESIGN.md §2): le durate delle schermate vengono dai token di `Motion`, non da numeri scritti a mano. */
class MotionTokensTest {
    private fun source(name: String): List<String> =
        listOf("src/main/java/dev/r1agents/$name", "app/src/main/java/dev/r1agents/$name").map(::File).first { it.exists() }.readLines()

    @Test fun screensUseMotionTokensForDurations() {
        val offenders = listOf("MainActivity.kt", "BuddyStage.kt").flatMap { f ->
            source(f).withIndex().filter { (_, l) -> Regex("setDuration\\(\\s*[0-9]|\\bdur\\s*=\\s*[0-9]").containsMatchIn(l) }.map { (i, l) -> "$f:${i + 1}: ${l.trim().take(80)}" }
        }
        assertTrue("Durate scritte a mano (usa Motion.PRESS/FAST/BASE/SLOW): $offenders", offenders.isEmpty())
    }

    @Test fun tokensAreOrderedAndExitsAreShorterThanEntrances() {
        assertTrue(Motion.PRESS < Motion.FAST && Motion.FAST < Motion.BASE && Motion.BASE < Motion.SLOW)
        assertTrue(Motion.STEP < Motion.FAST)
    }
}
