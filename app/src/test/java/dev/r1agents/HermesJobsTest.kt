package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesJobsTest {
    @Test fun cronFromAlarmDays() {
        assertEquals("30 7 * * mon,tue,wed,thu,fri", cronExpression(7, 30, 31))
        assertEquals("0 9 * * sat,sun", cronExpression(9, 0, 96))
        assertEquals("15 18 * * *", cronExpression(18, 15, 127))
        assertEquals("0 8 * * *", cronExpression(8, 0, 0))
    }

    @Test fun instantsFromDashboardValues() {
        assertEquals(1_700_000_000_000L, parseInstant(1_700_000_000))
        assertEquals(1_700_000_000_000L, parseInstant(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, parseInstant("2023-11-14T22:13:20+00:00"))
        assertEquals(1_700_000_000_000L, parseInstant("2023-11-14T22:13:20Z"))
        assertEquals(0L, parseInstant(null)); assertEquals(0L, parseInstant("")); assertEquals(0L, parseInstant("non una data"))
    }

    @Test fun jobsFromTheDashboardList() {
        val json = """[{"id":"a1","name":"Riassunto","prompt":"riassumi","schedule":{"display":"0 8 * * *"},"schedule_display":"every day at 8:00","enabled":true,"state":"scheduled","next_run_at":"2026-11-08T08:00:00+01:00","last_run_at":null,"last_status":"ok","profile":"default"},
            {"id":"unknown","name":"scartata"},{"id":"b2","prompt":"fai una cosa lunga lunga","enabled":false,"state":"paused"}]"""
        val jobs = parseJobs(json)
        assertEquals(2, jobs.size)
        assertEquals("every day at 8:00", jobs[0].schedule); assertTrue(jobs[0].enabled); assertTrue(jobs[0].nextRun > 0); assertEquals("ok", jobs[0].lastStatus)
        assertFalse(jobs[1].enabled); assertEquals("fai una cosa lunga lunga", jobs[1].name)
    }

    @Test fun jobsFromAnObjectAndSkills() {
        assertEquals(1, parseJobs("""{"jobs":[{"id":"x","name":"n","prompt":"p"}]}""").size)
        val skills = parseSkills("""[{"name":"web","description":"cerca\nsul web","enabled":false},{"name":"","description":"x"}]""")
        assertEquals(1, skills.size); assertFalse(skills[0].enabled); assertEquals("cerca sul web", skills[0].description)
    }
}
