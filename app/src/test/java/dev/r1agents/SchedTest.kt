package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SchedTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    // 7 novembre 2026 è un sabato.
    private val sat10 = at(2026, 11, 7, 10, 0)

    @Test fun onceLaterToday() = assertEquals(at(2026, 11, 7, 11, 0), Sched.next(11, 0, 0, sat10))
    @Test fun onceAlreadyPassedGoesToTomorrow() = assertEquals(at(2026, 11, 8, 9, 0), Sched.next(9, 0, 0, sat10))
    @Test fun exactlyNowIsNotInTheFuture() = assertEquals(at(2026, 11, 8, 10, 0), Sched.next(10, 0, 0, sat10))
    @Test fun mondayOnlyFromSaturday() = assertEquals(at(2026, 11, 9, 9, 0), Sched.next(9, 0, 1, sat10))
    @Test fun sundayOnlyIsTomorrow() = assertEquals(at(2026, 11, 8, 7, 30), Sched.next(7, 30, 1 shl 6, sat10))
    @Test fun saturdayOnlyLaterTodayOrNextWeek() {
        assertEquals(at(2026, 11, 7, 18, 0), Sched.next(18, 0, 1 shl 5, sat10))
        assertEquals(at(2026, 11, 14, 8, 0), Sched.next(8, 0, 1 shl 5, sat10))
    }
    @Test fun weekdaysSkipTheWeekend() = assertEquals(at(2026, 11, 9, 8, 0), Sched.next(8, 0, 31, sat10))
    @Test fun nextIsAlwaysInTheFuture() { for (d in listOf(0, 1, 31, 127)) assertTrue(Sched.next(10, 0, d, sat10) > sat10) }

    @Test fun timeFormat() { assertEquals("07:05", Sched.time(7, 5)); assertEquals("23:59", Sched.time(23, 59)) }
    @Test fun daysText() {
        assertEquals("una volta", Sched.daysText(0)); assertEquals("ogni giorno", Sched.daysText(127))
        assertEquals("lun-ven", Sched.daysText(31)); assertEquals("lun mar", Sched.daysText(3)); assertEquals("sab dom", Sched.daysText(96))
    }
}
