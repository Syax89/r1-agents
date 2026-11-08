package dev.r1agents

import kotlin.math.exp

/** Vocabolario condiviso da chat e scena vocale; i nomi provengono solo dall'attività ricevuta. */
internal data class BuddyVisualState(val kind: String, val phase: String, val activity: String? = null, val targetName: String? = null) {
    companion object {
        fun of(kind: String, label: String = ""): BuddyVisualState {
            val target = label.takeIf { kind == "handoff" && it.startsWith("chiede a ") }
                ?.removePrefix("chiede a ")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("un sotto-agente") }
            val phase = when (kind) {
                "listening" -> "listening"
                "error" -> "error"
                "waiting", "reconnect", "offline" -> "waiting"
                "upload", "search", "files", "shell", "memory", "handoff" -> "working"
                "thinking", "transcribing" -> "thinking"
                "working" -> "working"
                "speaking" -> "speaking"
                else -> "idle"
            }
            return BuddyVisualState(kind, phase, when (kind) { "upload" -> "files"; "search", "files", "shell", "memory" -> kind; else -> null }, target)
        }
        fun fromActivity(label: String): BuddyVisualState = when {
            label.startsWith("chiede a ") -> of("handoff", label)
            label.startsWith("invio ") -> of("upload")
            label.isBlank() -> of("thinking")
            else -> of(activityKind(label).let { if (it == "build") "thinking" else it })
        }
    }
}

/** Filtro indipendente dalla cadenza dei campioni: attacco rapido, rilascio morbido. */
internal class VoiceEnvelope {
    var value = 0f
        private set
    var target = 0f
        private set
    fun accept(level: Float) { target = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f }
    fun advance(seconds: Float): Float {
        val tau = if (target > value) .055f else .22f
        value += (target - value) * (1f - exp(-seconds.coerceIn(0f, .05f) / tau))
        return value
    }
    fun reset() { value = 0f; target = 0f }
}
