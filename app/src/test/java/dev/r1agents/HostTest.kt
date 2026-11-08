package dev.r1agents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTest {
    @Test fun privateRangesAreAllowedInClear() {
        for (a in listOf("http://192.168.1.20:9119", "http://10.0.0.5:9119", "http://172.16.4.2", "http://172.31.255.1", "http://169.254.3.4", "http://127.0.0.1:9119", "http://100.101.102.103:9119", "http://[::1]:9119", "http://localhost:9119", "http://mypc:9119", "http://pc.local:9119", "http://box.tailnet.ts.net"))
            assertTrue(a, httpAllowed(a))
    }
    @Test fun publicAddressesNeedHttps() {
        for (a in listOf("http://8.8.8.8:9119", "http://172.32.0.1", "http://100.128.0.1", "http://192.169.1.1", "http://hermes.example.com", "http://11.0.0.1"))
            assertFalse(a, httpAllowed(a))
    }
    @Test fun httpsIsAlwaysAllowed() { assertTrue(httpAllowed("https://hermes.example.com")); assertTrue(httpAllowed("https://8.8.8.8")) }
}
