package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    private val plain = """{"app":"r1agents","version":1,"agents":[{"name":"kiwi","prompt":"segreto"}]}"""

    @Test fun andataERitorno() {
        val enc = Backup.encrypt(plain, "cavallo-batteria".toCharArray())
        assertTrue(Backup.isEncrypted(enc)); assertFalse(Backup.isEncrypted(plain))
        assertFalse(enc.contains("segreto")); assertFalse(enc.contains("kiwi"))
        assertEquals(plain, Backup.decrypt(enc, "cavallo-batteria".toCharArray()))
        assertNotEquals(enc, Backup.encrypt(plain, "cavallo-batteria".toCharArray()))   // sale e IV nuovi ogni volta
    }

    @Test fun passwordSbagliata() {
        val enc = Backup.encrypt(plain, "giusta".toCharArray())
        try { Backup.decrypt(enc, "sbagliata".toCharArray()); fail() } catch (e: IllegalArgumentException) { assertEquals("Password sbagliata o file danneggiato.", e.message) }
    }

    @Test fun fileAlterato() {
        val j = org.json.JSONObject(Backup.encrypt(plain, "pw".toCharArray()))
        val data = java.util.Base64.getDecoder().decode(j.getString("data")); data[0] = (data[0].toInt() xor 1).toByte()
        j.put("data", java.util.Base64.getEncoder().encodeToString(data))
        try { Backup.decrypt(j.toString(), "pw".toCharArray()); fail() } catch (e: IllegalArgumentException) { }
    }
}
