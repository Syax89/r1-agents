package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebGuardTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    @Test fun privatiRifiutati() {
        for (a in listOf("127.0.0.1", "10.1.2.3", "192.168.1.11", "172.20.0.1", "169.254.1.1", "100.100.1.1", "0.0.0.0", "::1", "fd12::1", "fe80::1", "::ffff:192.168.1.1"))
            assertFalse(a, WebGuard.publicAddress(ip(a)))
    }
    @Test fun pubbliciAccettati() {
        for (a in listOf("1.1.1.1", "8.8.8.8", "100.128.0.1", "2606:4700::1111")) assertTrue(a, WebGuard.publicAddress(ip(a)))
    }
    @Test fun letturaCompleta() {
        val r = object : java.io.Reader() {
            var left = 30000
            override fun read(c: CharArray, off: Int, len: Int): Int { if (left <= 0) return -1; val n = minOf(len, 100, left); java.util.Arrays.fill(c, off, off + n, 'a'); left -= n; return n }
            override fun close() {}
        }
        assertEquals(20000, WebGuard.readUpTo(r, 20000).length)
    }
}
