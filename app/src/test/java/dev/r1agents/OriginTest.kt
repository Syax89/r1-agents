package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginTest {
    @Test fun addsSchemeToBareAddress() = assertEquals("http://192.168.1.20:9119", normalizeOrigin("192.168.1.20:9119"))
    @Test fun stripsTrailingSlashAndPath() = assertEquals("http://pc.local:9119", normalizeOrigin("http://pc.local:9119/dashboard/"))
    @Test fun keepsHttps() = assertEquals("https://hermes.example.com", normalizeOrigin("https://hermes.example.com/"))
    @Test fun trimsSpaces() = assertEquals("http://10.0.0.5:9119", normalizeOrigin("  10.0.0.5:9119 "))
    @Test fun emptyStaysEmpty() = assertEquals("", normalizeOrigin("   "))
}
