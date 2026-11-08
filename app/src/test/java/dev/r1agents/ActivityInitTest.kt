package dev.r1agents

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Una proprieta' di classe di MainActivity con inizializzatore che usa il contesto (UiText, AppLanguage, getSharedPreferences) gira nel costruttore,
 * quando l'Activity non ha ancora un contesto: l'app si chiude all'avvio con un NullPointerException. Deve essere un getter (`get() =`).
 */
class ActivityInitTest {
    @Test fun noContextInClassLevelInitializers() {
        val source = listOf("src/main/java/dev/r1agents/MainActivity.kt", "app/src/main/java/dev/r1agents/MainActivity.kt").map(::File).first { it.exists() }.readLines()
        val offenders = source.withIndex().filter { (_, line) ->
            Regex("^    (private |internal )?(val|var) [A-Za-z0-9_]+(: [^=]+)? = ").containsMatchIn(line) &&
                Regex("UiText\\.|AppLanguage\\.|getSharedPreferences|this@MainActivity").containsMatchIn(line)
        }.map { (i, line) -> "riga ${i + 1}: ${line.trim().take(90)}" }
        assertTrue("Inizializzatori che usano il contesto prima di onCreate: $offenders", offenders.isEmpty())
    }
}
