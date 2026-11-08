package dev.r1agents

import java.text.Normalizer

private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

/**
 * Il comando a voce/testo «apri calendario», «avvia la calcolatrice», «open camera»: ritorna l'indice dell'app nell'elenco
 * `labels`, ma solo se la corrispondenza è unica e certa (nome intero, oppure un solo nome che comincia così); altrimenti
 * `null` e il messaggio va all'agente come sempre.
 */
fun appForCommand(text: String, labels: List<String>): Int? {
    val m = Regex("^\\s*(?:apri|avvia|lancia|open|launch|start)\\s+(?:l'|la |lo |il |le |i |gli |un |una |the )?(.+?)\\s*[.!?]?\\s*$", RegexOption.IGNORE_CASE).find(text) ?: return null
    val q = norm(m.groupValues[1])
    if (q.length < 2) return null
    val normalized = labels.map { norm(it) }
    val exact = normalized.indices.filter { normalized[it] == q }
    if (exact.size == 1) return exact[0]
    if (exact.size > 1) return null
    val starts = normalized.indices.filter { normalized[it].startsWith("$q ") || normalized[it] == q || normalized[it].startsWith(q) && q.length >= 3 }
    return if (starts.size == 1) starts[0] else null
}
