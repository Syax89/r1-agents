package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActionLogTest {
    @Test fun ultimeInCimaELimitate() {
        var j: String? = null
        for (i in 1..50) j = ActionLog.append(j, ActionLog.Entry(i.toLong(), "timer", "${i}m", i % 2 == 0))
        val l = ActionLog.parse(j)
        assertEquals(ActionLog.MAX, l.size)
        assertEquals(50L, l.first().time); assertEquals(11L, l.last().time)
        assertFalse(l[1].ok)
    }
    @Test fun jsonRottoNonFaCrollare() {
        assertEquals(0, ActionLog.parse("{non json").size)
        assertEquals(1, ActionLog.parse(ActionLog.append("{rotto", ActionLog.Entry(1, "apri", "x".repeat(300), true))).size)
        assertEquals(120, ActionLog.parse(ActionLog.append(null, ActionLog.Entry(1, "apri", "x".repeat(300), true)))[0].arg.length)
    }
}
