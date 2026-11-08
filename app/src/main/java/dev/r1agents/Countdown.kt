package dev.r1agents

internal object Countdown {
    /** Un secondo parziale resta 1; lo zero indica una scadenza effettivamente raggiunta. */
    fun seconds(endAt: Long, now: Long): Long {
        val remaining = (endAt - now).coerceAtLeast(0)
        return remaining / 1000 + if (remaining % 1000 != 0L) 1 else 0
    }
}
