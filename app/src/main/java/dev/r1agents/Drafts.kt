package dev.r1agents

/** Una trascrizione si aggiunge alla bozza scritta, senza sostituirla. */
fun appendTranscript(draft: String, transcript: String): String {
    if (transcript.isBlank()) return draft
    return if (draft.isBlank()) transcript.trim() else draft.trimEnd() + "\n" + transcript.trim()
}
