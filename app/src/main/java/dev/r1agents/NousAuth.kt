package dev.r1agents

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Login a Nous Portal con il flusso OAuth "device code" (come `hermes auth add nous --type oauth`).
 * Endpoint ricavati dal codice MIT di hermes-agent (hermes_cli/auth_device_flow.py, auth_nous.py).
 * Il refresh token resta nelle preferenze private dell'app; l'access token (JWT) fa da chiave per l'inferenza.
 */
class NousAuth(private val p: SharedPreferences) {
    class Code(val deviceCode: String, val userCode: String, val uri: String, val uriComplete: String, val expiresIn: Int, val interval: Int)

    val portal = "https://portal.nousresearch.com"
    /** `hermes-cli` è il client di Hermes; se Nous ne registra uno per quest'app si cambia qui o via intent (`nous_client_id`). */
    var clientId: String
        get() = p.getString("nous_client_id", "hermes-cli") ?: "hermes-cli"
        set(v) { p.edit().putString("nous_client_id", v.trim().ifEmpty { "hermes-cli" }).apply() }

    val loggedIn get() = p.secret("nous_refresh").isNotEmpty()

    fun logout() { p.edit().remove("nous_refresh").remove("nous_access").remove("nous_expires").remove("nous_inference").apply() }

    /** Solo https verso *.nousresearch.com: l'indirizzo d'inferenza arriva dalla rete e riceve il token. */
    private fun safeBase(u: String?): String? = runCatching {
        val url = URL(u ?: return null)
        if (url.protocol == "https" && (url.host == "nousresearch.com" || url.host.endsWith(".nousresearch.com"))) u.trimEnd('/') else null
    }.getOrNull()

    fun inferenceBase(): String = p.getString("nous_inference", null) ?: "https://inference-api.nousresearch.com/v1"

    private fun form(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): Pair<Int, JSONObject> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("content-type", "application/x-www-form-urlencoded"); setRequestProperty("accept", "application/json")
            setRequestProperty("user-agent", "r1agents/0.1")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            return code to (runCatching { JSONObject(body) }.getOrNull() ?: JSONObject().put("error", "http_$code").put("error_description", body.take(120)))
        } finally { c.disconnect() }
    }

    fun requestCode(): Code {
        val (st, j) = form("$portal/api/oauth/device/code", mapOf("client_id" to clientId, "scope" to "inference:invoke"))
        if (st !in 200..299) throw RuntimeException(j.optString("error_description").ifEmpty { "Portal: HTTP $st" })
        return Code(j.getString("device_code"), j.getString("user_code"), j.getString("verification_uri"),
            j.getString("verification_uri_complete"), j.optInt("expires_in", 600), j.optInt("interval", 5))
    }

    /** Aspetta l'approvazione. `cancelled` permette di uscire. Ritorna null se annullato; lancia se scade o è rifiutato. */
    fun poll(c: Code, cancelled: () -> Boolean): Unit? {
        var wait = c.interval.coerceIn(1, 15)
        val deadline = System.currentTimeMillis() + c.expiresIn * 1000L
        while (!cancelled()) {
            if (System.currentTimeMillis() > deadline) throw RuntimeException("Codice scaduto: riprova.")
            Thread.sleep(wait * 1000L)
            if (cancelled()) return null
            val (st, j) = form("$portal/api/oauth/token", mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to c.deviceCode))
            if (st in 200..299 && j.has("access_token")) { store(j, null); return Unit }
            when (j.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> wait = (wait + 5).coerceAtMost(30)
                "expired_token" -> throw RuntimeException("Codice scaduto: riprova.")
                "access_denied" -> throw RuntimeException("Accesso rifiutato.")
                else -> if (st >= 500) {} else throw RuntimeException(j.optString("error_description").ifEmpty { j.optString("error", "errore $st") })
            }
        }
        return null
    }

    private fun store(j: JSONObject, oldRefresh: String?) {
        val ttl = j.optLong("expires_in", 900)
        p.edit().putSecret("nous_access", j.getString("access_token"))
            .putSecret("nous_refresh", j.optString("refresh_token").ifEmpty { oldRefresh ?: "" })
            .putLong("nous_expires", System.currentTimeMillis() + ttl * 1000)
            .apply { safeBase(j.optString("inference_base_url").ifEmpty { null })?.let { putString("nous_inference", it) } }
            .apply()
    }

    /** Access token valido (rinnovato se scade entro 2 minuti). */
    @Synchronized fun accessToken(): String {
        val refresh = p.secret("nous_refresh")
        if (refresh.isEmpty()) throw IllegalStateException("Non hai fatto l'accesso a Nous. Aprilo dalle Impostazioni.")
        val tok = p.secret("nous_access")
        if (tok.isNotEmpty() && p.getLong("nous_expires", 0) - System.currentTimeMillis() > 120_000) return tok
        val (st, j) = form("$portal/api/oauth/token", mapOf("grant_type" to "refresh_token", "client_id" to clientId),
            mapOf("x-nous-refresh-token" to refresh))
        if (st in 200..299 && j.has("access_token")) { store(j, refresh); return j.getString("access_token") }
        if (st in listOf(400, 401)) { logout(); throw IllegalStateException("Sessione Nous scaduta: accedi di nuovo dalle Impostazioni.") }
        throw RuntimeException("Nous Portal non raggiungibile (HTTP $st). Riprova tra poco.")
    }
}
