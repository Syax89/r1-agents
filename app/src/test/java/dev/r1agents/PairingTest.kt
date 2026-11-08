package dev.r1agents

import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    @Test fun roundTripWithUnicodeUser() { val pair = Pairing.create("192.168.1.20:9119", "simone + è"); assertEquals(pair, Pairing.parse(pair.link())) }
    @Test fun plainLocalUrlIsAccepted() { assertEquals("http://192.168.1.20:9119", Pairing.parse("http://192.168.1.20:9119/").origin) }
    @Test fun secretsAndAmbiguousFieldsAreRejected() {
        val bad = listOf("http://user:password@192.168.1.20", "https://example.com/?token=secret", "r1agents://connect?url=https%3A%2F%2Fexample.com&password=secret", "r1agents://connect?url=https%3A%2F%2Fexample.com&url=https%3A%2F%2Fother.com")
        for (value in bad) try { Pairing.parse(value); fail(value) } catch (_: IllegalArgumentException) { }
    }
    @Test fun publicHttpIsRejected() { try { Pairing.create("http://example.com", "user"); fail() } catch (_: IllegalArgumentException) { }; assertEquals("https://example.com", Pairing.create("https://example.com", "user").origin) }
    @Test fun unsupportedVersionAndSchemesAreRejected() { for (value in listOf("file:///tmp/x", "javascript:alert(1)", "r1agents://connect?v=99&url=https%3A%2F%2Fexample.com", "r1agents://connect?url=http%3A%2F%2Flocalhost%3A99999")) try { Pairing.parse(value); fail(value) } catch (_: IllegalArgumentException) { } }
}
