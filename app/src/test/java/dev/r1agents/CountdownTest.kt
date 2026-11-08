package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownTest {
    @Test fun lastPartialSecondDoesNotExpireTheTimerEarly() {
        for (remaining in listOf(1L, 500L, 999L, 1000L)) assertEquals(1L, Countdown.seconds(10_000, 10_000 - remaining))
        assertEquals(2L, Countdown.seconds(10_000, 8_999))
    }
    @Test fun reachedAndPastDeadlinesStayAtZero() {
        assertEquals(0L, Countdown.seconds(10_000, 10_000))
        assertEquals(0L, Countdown.seconds(10_000, 11_000))
    }
}
