package dev.r1agents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewServiceTest {
    /** Le finestre protette (FLAG_SECURE) escono nere: non si salvano come anteprima. */
    @Test fun scartaLeImmaginiNere() {
        assertTrue(PreviewService.mostlyBlack(IntArray(100) { 0xff000000.toInt() }))
        assertTrue(PreviewService.mostlyBlack(IntArray(0)))
        assertFalse(PreviewService.mostlyBlack(IntArray(100) { if (it < 50) 0xff000000.toInt() else 0xffffffff.toInt() }))
    }
}
