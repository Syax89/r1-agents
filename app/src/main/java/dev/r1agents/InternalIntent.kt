package dev.r1agents

import android.content.Context
import android.content.Intent

/**
 * L'activity e' esportata (e' il launcher), quindi qualunque app puo' mandarle un intent. Gli extra che fanno cose delicate
 * (suoneria sopra il blocco, bozza vocale) sono accettati solo se portano il token di questa installazione: un valore casuale
 * nelle preferenze private, che le altre app non possono leggere. Prefisso `pending_`: escluso dai backup.
 */
object InternalIntent {
    const val EXTRA = "r1_token"
    private const val KEY = "pending_intent_token"

    fun token(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.length >= 32 }?.let { return it }
        val b = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        val t = b.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY, t).apply()
        return t
    }

    fun mark(i: Intent, ctx: Context): Intent = i.putExtra(EXTRA, token(ctx))

    /** Vero se l'intent viene da questa app; confronto a tempo costante. */
    fun ok(i: Intent, ctx: Context): Boolean = matches(i.getStringExtra(EXTRA), token(ctx))

    fun matches(given: String?, expected: String): Boolean =
        given != null && java.security.MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
}
