package dev.r1agents

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHash {
    fun valid(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }
    fun create(pin: String): String {
        require(valid(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "v1:120000:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derive(pin, salt, 120000))}"
    }
    fun verify(pin: String, stored: String): Boolean = runCatching {
        if (!valid(pin)) return false
        val parts = stored.split(':'); if (parts.size != 4 || parts[0] != "v1") return false
        val iterations = parts[1].toInt(); if (iterations !in 100000..500000) return false
        val salt = Base64.getDecoder().decode(parts[2]); val expected = Base64.getDecoder().decode(parts[3])
        if (salt.size != 16 || expected.size != 32) return false
        MessageDigest.isEqual(expected, derive(pin, salt, iterations))
    }.getOrDefault(false)
    private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
