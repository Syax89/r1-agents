package dev.r1agents

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra i segreti (chiavi API, password del dashboard, token di Nous) con una chiave AES-GCM nel Keystore di Android:
 * nelle preferenze resta solo `v1:<iv+testo cifrato>`. La chiave non esce dal Keystore. Se il Keystore non funziona
 * si salva in chiaro (meglio che perdere l'accesso) e al prossimo avvio si riprova a cifrare. I valori vecchi in chiaro si leggono lo stesso.
 */
object Vault {
    private const val ALIAS = "r1agents_secrets"
    private const val PREFIX = "v1:"
    val SECRET_KEYS = listOf("key_anthropic", "key_hermes", "key_dashboard", "key_custom", "groq_key", "dash_pass", "nous_refresh", "nous_access")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) { plain }
    }

    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) { "" }
    }

    private val secretPattern = Regex("(sk-[A-Za-z0-9_\\-]{8,}|Bearer\\s+\\S+|(?i:api[_-]?key|token|password)=[^&\\s]+)")
    /** Toglie chiavi e token da un testo prima di mostrarlo (errori del server, messaggi): mai credenziali sullo schermo. */
    fun redact(text: String?): String = secretPattern.replace(text.orEmpty(), "•••")

    /** Cifra i segreti ancora in chiaro (avvio dell'app). */
    fun migrate(p: SharedPreferences) {
        val e = p.edit(); var any = false
        for (k in SECRET_KEYS + p.all.keys.filter { it.startsWith("dash_sid_") }) { val v = p.getString(k, null) ?: continue; if (v.isNotEmpty() && !v.startsWith(PREFIX)) { val s = seal(v); if (s != v) { e.putString(k, s); any = true } } }
        if (any) e.apply()
    }
}

fun SharedPreferences.secret(name: String): String = Vault.open(getString(name, "").orEmpty())
fun SharedPreferences.Editor.putSecret(name: String, value: String): SharedPreferences.Editor = putString(name, Vault.seal(value))
