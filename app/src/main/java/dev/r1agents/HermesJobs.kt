package dev.r1agents

import org.json.JSONArray
import org.json.JSONObject

/** Un'attivita' pianificata di Hermes (cron del dashboard): un prompt che Hermes esegue da solo a orari fissi. */
class HermesJob(val id: String, val name: String, val prompt: String, val schedule: String, val enabled: Boolean, val state: String,
                val nextRun: Long, val lastRun: Long, val lastStatus: String, val profile: String)

/** L'ultima esecuzione e' fallita? Il dashboard non ha un campo fisso: si guarda il testo dello stato. */
fun HermesJob.failed(): Boolean = lastStatus.lowercase().let { it.contains("error") || it.contains("fail") || it.contains("errore") || it.contains("timeout") }

/** Una competenza (skill) di un profilo Hermes, attivabile o disattivabile. */
class HermesSkill(val name: String, val description: String, val enabled: Boolean)

/** Espressione cron "minuto ora * * giorni" da ora, minuto e giorni (bit 0 = lunedi', come le sveglie); senza giorni (0) o con tutti i giorni vale ogni giorno. */
fun cronExpression(hour: Int, minute: Int, days: Int): String {
    val names = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    val dow = if (days == 0 || days and 127 == 127) "*" else (0..6).filter { days and (1 shl it) != 0 }.joinToString(",") { names[it] }
    return "$minute $hour * * $dow"
}

/** Istante in millisecondi da un valore del dashboard: epoca in secondi o millisecondi, oppure testo ISO-8601; 0 se assente o illeggibile. */
fun parseInstant(v: Any?): Long = try {
    when (v) {
        null, JSONObject.NULL -> 0L
        is Number -> v.toLong().let { if (it in 1..999_999_999_999L) it * 1000 else it }
        is String -> v.trim().let { t ->
            if (t.isEmpty()) 0L
            else t.toLongOrNull()?.let { if (it in 1..999_999_999_999L) it * 1000 else it }
                ?: runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrElse { runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrElse { runCatching { java.time.LocalDateTime.parse(t).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0L) } }
        }
        else -> 0L
    }
} catch (e: Exception) { 0L }

/** Legge l'elenco di `GET /api/cron/jobs` (una lista, o un oggetto con `jobs`); le voci senza id si scartano. */
fun parseJobs(json: String): List<HermesJob> {
    val arr: JSONArray = runCatching { JSONArray(json) }.getOrNull() ?: JSONObject(json).optJSONArray("jobs") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
        val id = o.optString("id").ifBlank { o.optString("job_id") }
        if (id.isBlank() || id == "unknown") return@mapNotNull null
        val sched = o.optString("schedule_display").ifBlank { o.optJSONObject("schedule")?.optString("display").orEmpty().ifBlank { o.optString("schedule") } }
        val state = o.optString("state")
        HermesJob(id, o.optString("name").ifBlank { o.optString("prompt").lineSequence().firstOrNull().orEmpty().take(40) }, o.optString("prompt"), sched,
            o.optBoolean("enabled", state != "paused"), state, parseInstant(o.opt("next_run_at")), parseInstant(o.opt("last_run_at")), o.optString("last_status").takeIf { it != "null" }.orEmpty(),
            o.optString("profile").takeIf { it != "null" }.orEmpty())
    }
}

/** Legge `GET /api/skills`. */
fun parseSkills(json: String): List<HermesSkill> {
    val arr = runCatching { JSONArray(json) }.getOrNull() ?: JSONObject(json).optJSONArray("skills") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("name").isNotBlank() }
        .map { HermesSkill(it.optString("name"), it.optString("description").replace('\n', ' ').take(160), it.optBoolean("enabled", true)) }
}
