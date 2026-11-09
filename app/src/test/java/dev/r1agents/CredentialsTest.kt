package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    @Test fun keysOnlyGoToSafeAddresses() {
        assertTrue(safeBase("https://api.example.com/v1"))
        assertTrue(safeBase("http://192.168.1.20:9119"))
        assertFalse(safeBase("http://api.example.com/v1"))
        assertFalse(safeBase("https://user:pass@example.com"))
        assertFalse(safeBase("https://example.com/v1?api_key=abc"))
        assertFalse(safeBase("ftp://example.com"))
    }

    @Test fun redactHidesKeysInMessages() {
        val t = Vault.redact("HTTP 401: bad key sk-abcdef123456 and Bearer abc.def, api_key=zzz&x=1")
        assertFalse(t.contains("abcdef123456")); assertFalse(t.contains("abc.def")); assertFalse(t.contains("zzz"))
        assertTrue(t.contains("HTTP 401"))
    }

    @Test fun importCannotChangeDestinations() {
        for (k in listOf("base_anthropic", "base_custom", "dash_url", "dash_user", "update_url", "nous_client_id", "dev_actions")) assertTrue(k, Backup.isDestination(k))
        for (k in listOf("tts", "home_index", "provider", "app_language")) assertFalse(k, Backup.isDestination(k))
        assertEquals("•••", Vault.redact("sk-12345678abcd"))
    }
}
