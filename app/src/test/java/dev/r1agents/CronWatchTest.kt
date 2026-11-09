package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class CronWatchTest {
    private fun job(id: String, last: Long) = HermesJob(id, id, "p", "", true, "", 0, last, "", "")
    @Test fun soloLeEsecuzioniNuove() {
        val jobs = listOf(job("a", 100), job("b", 50), job("c", 0), job("d", 10))
        assertEquals(listOf("a", "d"), CronWatch.newRuns(jobs, mapOf("a" to 90L, "b" to 50L), false).map { it.id })
    }
    @Test fun alPrimoGiroNessunAvviso() { assertEquals(0, CronWatch.newRuns(listOf(job("a", 100)), emptyMap(), true).size) }
}
