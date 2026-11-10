package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorKindTest {
    @Test fun classifica() {
        assertEquals(ErrorKind.KEY, ErrorKind.of(RuntimeException("HTTP 401: no")))
        assertEquals(ErrorKind.RATE, ErrorKind.of(RuntimeException("HTTP 429: slow")))
        assertEquals(ErrorKind.SERVER, ErrorKind.of(RuntimeException("HTTP 503")))
        assertEquals(ErrorKind.NETWORK, ErrorKind.of(java.net.UnknownHostException("x")))
        assertEquals(ErrorKind.TIMEOUT, ErrorKind.of(java.net.SocketTimeoutException("t")))
        assertEquals(ErrorKind.KEY, ErrorKind.of(IllegalStateException("Manca la chiave API di X.")))
        assertEquals(ErrorKind.CONFIG, ErrorKind.of(IllegalArgumentException("expected url scheme")))
        assertEquals(ErrorKind.OTHER, ErrorKind.of(RuntimeException("boh")))
    }

    @Test fun causeAnnidateECodici() {
        assertEquals(ErrorKind.TIMEOUT, ErrorKind.of(RuntimeException("avvolto", java.net.SocketTimeoutException("t"))))
        assertEquals(ErrorKind.NETWORK, ErrorKind.of(java.io.IOException("x", java.net.UnknownHostException("h"))))
        assertEquals(ErrorKind.NETWORK, ErrorKind.of(java.net.SocketException("Connection reset")))
        assertEquals(ErrorKind.CONFIG, ErrorKind.of(RuntimeException("HTTP 404: model not found")))
        assertEquals(ErrorKind.CONFIG, ErrorKind.of(RuntimeException("HTTP 400: bad request")))
        assertEquals(ErrorKind.TIMEOUT, ErrorKind.of(java.io.IOException("Hermes non risponde.")))
    }
}
