package dev.r1agents

import android.content.SharedPreferences
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Frase breve per l'attività dell'agente a partire da uno strumento di Hermes (`tool.start`). */
fun toolLabel(name: String, context: String): String {
    // Un agente che ne chiama un altro (message_agent): "chiede a <nome>", per far vedere il passaggio della parola.
    if (name.lowercase() == "message_agent") Regex("\"agent\"\\s*:\\s*\"([^\"]{1,40})").find(context)?.let { return "chiede a ${it.groupValues[1]}" }
    val what = when (name.lowercase()) {
        "terminal", "bash", "shell", "execute_command" -> "esegue un comando"
        "web_search", "search", "web.search" -> "cerca sul web"
        "web_fetch", "web_extract", "fetch", "browser", "browse" -> "legge una pagina"
        "read_file", "file_read", "read" -> "legge un file"
        "write_file", "file_write", "write", "patch", "edit", "skill_manage" -> "scrive un file"
        "search_files", "grep", "glob", "find" -> "cerca nei file"
        "memory", "remember" -> "aggiorna la memoria"
        "delegate", "delegate_task", "subagent" -> "chiede a un sotto-agente"
        "image_generate", "image.generate" -> "crea un'immagine"
        "todo" -> "aggiorna l'elenco"
        "clarify" -> "ha una domanda"
        else -> "usa $name"
    }
    val c = context.trim().replace('\n', ' ')
    return if (c.isEmpty()) what else "$what: ${if (c.length > 70) c.take(69) + "…" else c}"
}

/** Frame JSON-RPC di risposta a un'approvazione (`choice`: once, session, always, deny). */
fun approvalReply(id: Any, choice: String): String =
    JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("choice", choice).put("all", false)).toString()

/** Una password in chiaro è accettabile solo verso la rete locale: https sempre, http solo per indirizzi privati, loopback, Tailscale (100.64/10) e nomi locali. */
fun httpAllowed(origin: String): Boolean {
    if (origin.startsWith("https://")) return true
    val host = origin.substringAfter("://").substringBefore('/').substringBefore(':').trim('[', ']').lowercase()
    return isPrivateHost(if (origin.substringAfter("://").startsWith("[")) origin.substringAfter("[").substringBefore("]") else host)
}

fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || !h.contains('.') && !h.contains(':')) return true // un nome senza punti è locale
    if (listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".fritz.box", ".ts.net").any { h.endsWith(it) }) return true
    if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
    val p = h.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4 || p.any { it !in 0..255 }) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254) || (p[0] == 100 && p[1] in 64..127)
}

/** `192.168.1.20:9119` -> `http://192.168.1.20:9119` (solo l'origine: niente percorso). */
fun normalizeOrigin(raw: String): String {
    var u = raw.trim().trimEnd('/')
    if (u.isEmpty()) return ""
    if (!u.contains("://")) u = "http://$u"
    val scheme = u.substringBefore("://"); val rest = u.substringAfter("://").substringBefore('/')
    return "$scheme://$rest"
}

/**
 * Collegamento al dashboard di Hermes Agent sul tuo PC, come fa Hermes Desktop con un "remote gateway":
 * login utente/password (`POST /auth/password-login`, provider `basic`), poi un ticket monouso
 * (`POST /api/auth/ws-ticket`) per il WebSocket `/api/ws`, dove si parla JSON-RPC (`session.create`,
 * `prompt.submit`, eventi `message.delta` / `message.complete`). Protocollo ricavato dal codice MIT di hermes-agent;
 * non è un'API documentata: può cambiare con le versioni.
 */
class HermesDash(private val p: SharedPreferences) {
    private val jar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val old = store[url.host].orEmpty().filter { o -> cookies.none { it.name == o.name } }
            store[url.host] = old + cookies
        }
        override fun loadForRequest(url: HttpUrl) = store[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
        fun clear() = store.clear()
    }
    private val http = OkHttpClient.Builder().cookieJar(jar).pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    var url: String
        get() = p.getString("dash_url", "") ?: ""
        set(v) { p.edit().putString("dash_url", v.trim()).apply() }
    var user: String
        get() = p.getString("dash_user", "") ?: ""
        set(v) { p.edit().putString("dash_user", v.trim()).apply() }
    var password: String
        get() = p.secret("dash_pass")
        set(v) { p.edit().putSecret("dash_pass", v).apply() }
    private val origin get() = normalizeOrigin(url)

    // ---- HTTP -----------------------------------------------------------------------------------

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val b = Request.Builder().url(origin + path).header("Origin", origin)
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()) ?: if (method == "POST") "".toRequestBody() else null)
        http.newCall(b.build()).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    /** Controlla che il dashboard accetti utente e password. Lancia con un messaggio comprensibile. */
    fun login() {
        if (origin.isEmpty()) throw IllegalStateException("Inserisci l'indirizzo del dashboard.")
        if (!httpAllowed(origin)) throw IllegalStateException("Indirizzo pubblico senza https: la password viaggerebbe in chiaro. Usa https://… oppure un indirizzo di rete locale o Tailscale.")
        val (sc, st) = call("GET", "/api/status") // un errore di rete esce come IOException: connect() lo riprova
        if (sc != 200) throw IllegalStateException("Il server ha risposto HTTP $sc: è il dashboard di Hermes?")
        val j = runCatching { JSONObject(st) }.getOrNull() ?: throw IllegalStateException("Risposta inattesa: è il dashboard di Hermes?")
        if (!j.optBoolean("auth_required")) throw IllegalStateException(
            "Il dashboard è in ascolto solo su questo PC. Avvialo con --host 0.0.0.0 e imposta utente e password (vedi sotto).")
        serverVersion = j.optString("version")
        val provs = j.optJSONArray("auth_providers")
        if (provs == null || (0 until provs.length()).none { provs.optString(it) == "basic" })
            throw IllegalStateException("Il dashboard non ha il login con utente e password: imposta HERMES_DASHBOARD_BASIC_AUTH_*.")
        val (lc, _) = call("POST", "/auth/password-login", JSONObject().put("provider", "basic").put("username", user).put("password", password).put("next", ""))
        when (lc) {
            200 -> {}
            401 -> throw IllegalStateException("Utente o password errati.")
            429 -> throw IllegalStateException("Troppi tentativi: aspetta un momento.")
            else -> throw IllegalStateException("Accesso rifiutato (HTTP $lc).")
        }
    }

    private fun ticket(): String {
        var (c, b) = call("POST", "/api/auth/ws-ticket")
        if (c == 401) { login(); val r = call("POST", "/api/auth/ws-ticket"); c = r.first; b = r.second }
        if (c != 200) throw IllegalStateException("Ticket negato (HTTP $c).")
        return JSONObject(b).getString("ticket")
    }

    // ---- WebSocket JSON-RPC ---------------------------------------------------------------------

    enum class State { OFF, CONNECTING, READY, ERROR }

    /** Stato del collegamento, per l'indicatore nella home. `onState` può arrivare da qualunque thread. */
    @Volatile var state = State.OFF
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var serverVersion: String = ""
        private set
    var onState: ((State) -> Unit)? = null
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()
    fun watchState(listener: (State) -> Unit): () -> Unit {
        stateListeners.add(listener)
        return { stateListeners.remove(listener) }
    }
    private fun setState(s: State, err: String? = null) { state = s; lastError = err; onState?.invoke(s); stateListeners.forEach { it(s) } }

    private var ws: WebSocket? = null
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val sessions = ConcurrentHashMap<Long, String>() // chat locale -> session_id di runtime (valido per questa connessione)

    /** Un turno di chat in corso: ogni sessione ha la sua coda, così più chat possono rispondere insieme. */
    private class Turn(val sid: String) {
        val inbox = LinkedBlockingQueue<Pair<String, String>>() // ("done"|"err"|"closed", testo)
        @Volatile var cancelled = false
        @Volatile var onEvent: ((JSONObject) -> Unit)? = null
    }
    private val turns = ConcurrentHashMap<String, Turn>()

    /** Una domanda di Hermes al client: `approval` (consenti o nega un comando) o `clarify` (domande all'utente). */
    class SrvRequest(val id: Any, val method: String, val params: JSONObject)
    var onRequest: ((SrvRequest) -> Unit)? = null
    var onRequestCancel: ((String) -> Unit)? = null
    private val seenRequests = ConcurrentHashMap.newKeySet<String>()

    private fun deliver(r: SrvRequest) { if (seenRequests.add(r.id.toString())) onRequest?.invoke(r) }

    /** Risposta a un'approvazione. */
    fun forgetRequest(id: String) { seenRequests.remove(id) }

    fun answerApproval(id: Any, choice: String) { ws?.send(approvalReply(id, choice)) }

    /** Risposta a una domanda di un chiarimento (`null` = salta). Con l'ultima risposta Hermes chiude la richiesta. */
    fun answerClarify(requestId: String, questionId: String, answer: Any?) {
        rpc("clarify.lock", JSONObject().put("request_id", requestId).put("question_id", questionId).put("answer", answer ?: JSONObject.NULL), 15, retry = false)
    }

    /** Riconfigurare solo quando il chiamante ha escluso turni attivi. Nessuna password del vecchio server viene riusata implicitamente. */
    @Synchronized fun reconfigure(address: String, username: String, pass: String?) {
        val oldOrigin = normalizeOrigin(url)
        val changed = oldOrigin != address || user != username
        val previous = ws; ws = null; previous?.cancel()
        sessions.clear(); jar.clear(); seenRequests.clear()
        pending.values.forEach { it.completeExceptionally(java.io.IOException("Collegamento modificato")) }; pending.clear()
        url = address; user = username
        if (pass != null) password = pass else if (changed) password = ""
        setState(State.OFF)
    }

    private fun stored(a: Long) = p.getString("dash_sid_$a", null)?.let { Vault.open(it) }?.takeIf { it.isNotEmpty() }
    fun needsIntro(a: Long) = stored(a) == null
    fun forget(a: Long) { p.edit().remove("dash_sid_$a").apply(); sessions.remove(a) }

    /** Collega (login, ticket, WebSocket). Riprova per errori di rete con attesa crescente; per credenziali e configurazione no. */
    @Synchronized private fun connect() {
        if (ws != null) return
        setState(State.CONNECTING)
        var last: java.io.IOException? = null
        for (attempt in 0 until 3) {
            try { openSocket(); return }
            catch (e: IllegalStateException) { setState(State.ERROR, e.message); throw e }
            catch (e: java.io.IOException) { last = e; if (attempt < 2) Thread.sleep(600L * (attempt + 1)) }
        }
        val msg = "Dashboard non raggiungibile (${last?.message}). PC acceso, stessa rete, dashboard avviato?"
        setState(State.ERROR, msg); throw java.io.IOException(msg)
    }

    private fun openSocket() {
        login()
        val t = ticket()
        val wsUrl = origin.replaceFirst("http", "ws") + "/api/ws"
        val ready = CountDownLatch(1)
        val req = Request.Builder().url(wsUrl).header("Origin", origin)
            .header("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket.$t").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    val j = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (j.optString("method") == "request.cancel") {
                        j.optJSONObject("params")?.optString("id")?.let { onRequestCancel?.invoke(it) }
                    } else if (j.optString("method") == "event" || j.optString("method") == "gateway.ready") {
                        val ev = j.optJSONObject("params") ?: continue
                        if (ev.optString("type") == "gateway.ready") ready.countDown()
                        else ev.optString("session_id").takeIf { it.isNotEmpty() }?.let { turns[it]?.onEvent?.invoke(ev) }
                    } else if (j.has("method") && j.has("id")) {
                        val method = j.optString("method")
                        if ((method == "approval" || method == "clarify") && onRequest != null) deliver(SrvRequest(j.get("id"), method, j.optJSONObject("params") ?: JSONObject()))
                        else webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", j.get("id"))   // le altre domande (sudo, segreti, anteprime…) non le gestiamo
                            .put("error", JSONObject().put("code", -32601).put("message", "unsupported by this client")).toString())
                    } else if (j.has("id")) {
                        pending.remove(j.optLong("id"))?.complete(j)
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, "chiuso ($code ${reason.take(60)})")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, t.message ?: "errore di rete")
        })
        ready.await(6, TimeUnit.SECONDS) // alcune versioni non annunciano gateway.ready: si prosegue comunque
        // Diciamo a Hermes che sappiamo rispondere alle sue domande (approvazioni, chiarimenti): senza questo le scarta.
        ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", ids.getAndIncrement()).put("method", "client.capabilities").put("params", JSONObject().put("server_requests", true)).toString() + "\n")
        setState(State.READY)
    }

    private fun closed(w: WebSocket, why: String) {
        val mine = synchronized(this) { if (ws === w) { ws = null; true } else false }
        if (!mine) return
        sessions.clear()
        pending.values.forEach { it.completeExceptionally(java.io.IOException(why)) }; pending.clear()
        turns.values.forEach { it.onEvent?.invoke(JSONObject().put("type", "__closed").put("why", why)) }
        setState(State.OFF)
    }

    /** Una chiamata JSON-RPC. Se la connessione cade prima della risposta la riprova una volta (`retry`), tranne per l'invio dei messaggi. */
    private fun rpc(method: String, params: JSONObject, timeoutS: Long = 30, retry: Boolean = true): JSONObject {
        var attempt = 0
        while (true) {
            connect()
            val id = ids.getAndIncrement()
            val f = CompletableFuture<JSONObject>(); pending[id] = f
            val w = ws
            val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString() + "\n"
            if (w == null || !w.send(frame)) {
                pending.remove(id); synchronized(this) { if (ws === w) ws = null }
                if (retry && attempt++ < 1) continue
                throw java.io.IOException("connessione persa")
            }
            val r = try { f.get(timeoutS, TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.TimeoutException) { pending.remove(id); throw java.io.IOException("Hermes non risponde.") }
            catch (e: java.util.concurrent.ExecutionException) {
                if (retry && attempt++ < 1) continue
                throw java.io.IOException(e.cause?.message ?: "connessione persa")
            }
            r.optJSONObject("error")?.let { err ->
                if (err.optInt("code") == -32601) throw IllegalStateException("Questa versione di Hermes non supporta «$method»: aggiorna il dashboard.")
                throw RuntimeException(err.optString("message", "errore Hermes"))
            }
            return r.optJSONObject("result") ?: JSONObject()
        }
    }

    class Profile(val name: String, val display: String, val description: String)
    class HSession(val id: String, val title: String, val preview: String, val startedAt: Long, val count: Int)

    /** Chiamato quando una nuova sessione Hermes è stata creata per la chat locale `key`. */
    var onStored: ((Long, String) -> Unit)? = null

    private fun scope(profile: String) = JSONObject().also { if (profile.isNotEmpty() && profile != "default") it.put("profile", profile) }

    /** I profili di Hermes: sono gli agenti. */
    fun profiles(): List<Profile> {
        val arr = rpc("profiles.list", JSONObject().put("include_sessions", false)).optJSONArray("profiles") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { Profile(it.getString("name"), it.optString("display_name"), it.optString("description")) }
    }

    /** Le sessioni (chat) di un profilo, le più recenti per prime. */
    fun sessions(profile: String): List<HSession> {
        val arr = rpc("session.list", scope(profile).put("limit", 60)).optJSONArray("sessions") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            HSession(it.getString("id"), it.optString("title"), it.optString("preview"), (it.optDouble("started_at", 0.0) * 1000).toLong(), it.optInt("message_count")) }
            .sortedByDescending { it.startedAt }
    }

    /** Apre una sessione esistente (la collega alla chat locale `key`) e ne legge la cronologia. */
    fun open(key: Long, hsid: String, profile: String): List<Pair<String, String>> {
        val r = rpc("session.resume", scope(profile).put("session_id", hsid), 60)
        val runtimeId = r.optString("session_id")
        check(runtimeId.isNotBlank()) { "Hermes non ha restituito una sessione valida" }
        sessions[key] = runtimeId
        p.edit().putSecret("dash_sid_$key", hsid).apply()
        r.optJSONArray("open_requests")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> if (o.optString("method") in listOf("approval", "clarify")) deliver(SrvRequest(o.get("id"), o.getString("method"), o.optJSONObject("params") ?: JSONObject())) } }
        val arr = r.optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("role") in listOf("user", "assistant") && it.optString("text").isNotBlank() }
            .map { it.getString("role") to it.getString("text") }
    }

    /** Crea un profilo Hermes (un nuovo agente) con la descrizione data; eredita modello e credenziali del dashboard. */
    fun createProfile(name: String, description: String) { rpc("profiles.create", JSONObject().put("name", name).put("description", description), 90, retry = false) }

    /** Elimina una sessione salvata. Hermes rifiuta quelle ancora "live" (anche solo aperte da noi): prima la riprende per averne l'id runtime e la chiude. */
    fun delete(hsid: String, profile: String) {
        val live = runCatching { rpc("session.resume", scope(profile).put("session_id", hsid), 20, retry = false).optString("session_id") }.getOrDefault("")
        if (live.isNotEmpty()) {
            runCatching { rpc("session.close", scope(profile).put("session_id", live), 15, retry = false) }
            sessions.entries.removeAll { it.value == live }
        }
        rpc("session.delete", scope(profile).put("session_id", hsid), 15)
    }

    private fun session(key: Long, profile: String = ""): String {
        sessions[key]?.let { return it }
        stored(key)?.let { st ->
            runCatching { rpc("session.resume", scope(profile).put("session_id", st)).optString("session_id").takeIf { it.isNotEmpty() } }.getOrNull()
                ?.let { sessions[key] = it; return it }
        }
        val r = rpc("session.create", scope(profile))
        val sid = r.getString("session_id")
        r.optString("stored_session_id").takeIf { it.isNotEmpty() }?.let { p.edit().putSecret("dash_sid_$key", it).apply(); onStored?.invoke(key, it) }
        sessions[key] = sid
        return sid
    }

    /** Mette in coda un'immagine per il prossimo messaggio della chat (`image.attach_bytes`). */
    fun attachImage(key: Long, profile: String, bytes: ByteArray, name: String) {
        val sid = session(key, profile)
        rpc("image.attach_bytes", JSONObject().put("session_id", sid).put("content_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            .put("filename", name.substringBeforeLast('.') + ".jpg"), 90, retry = false)
    }

    /** Mette in coda un PDF (Hermes lo trasforma in immagini delle pagine: serve `pdftoppm` sul PC). */
    fun attachPdf(key: Long, profile: String, bytes: ByteArray, name: String) {
        val sid = session(key, profile)
        rpc("pdf.attach", JSONObject().put("session_id", sid).put("content_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).put("filename", name), 180, retry = false)
    }

    /** Esegue un comando con la barra (es. `/compress`, `/undo`, `/model nome`) nella sessione della chat e ne ritorna l'output. */
    fun command(key: Long, profile: String, cmd: String): String {
        val sid = session(key, profile)
        return rpc("slash.exec", JSONObject().put("session_id", sid).put("command", cmd.trim().let { if (it.startsWith("/")) it else "/$it" }), 120, retry = false)
            .optString("output").ifBlank { "(nessun output)" }
    }

    /** Ferma tutti i turni in corso (chiede a Hermes di interrompere le sessioni). */
    fun cancel(key: Long? = null) {
        for (t in turns.values.filter { key == null || it.sid == sessions[key] }) {
            t.cancelled = true; t.inbox.offer("done" to "")
            val sid = t.sid
            Io.run { runCatching { rpc("session.interrupt", JSONObject().put("session_id", sid), 5, retry = false) } }
        }
    }

    /** Un turno di chat. Ritorna il testo completo della risposta. Più chat possono avere un turno insieme (uno per sessione). */
    fun turn(key: Long, profile: String, text: String, onActivity: ((String) -> Unit)? = null, onText: (String) -> Unit): String {
        val sid = try { session(key, profile) } catch (e: java.io.IOException) { synchronized(this) { ws?.cancel(); ws = null }; throw e }
        val t = Turn(sid); val acc = StringBuilder()
        t.onEvent = { ev ->
            when (ev.optString("type")) {
                "message.delta" -> ev.optJSONObject("payload")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { acc.append(it); onText(it) }
                "message.complete" -> {
                    val pl = ev.optJSONObject("payload")
                    if (pl?.optString("status") == "error") t.inbox.offer("err" to (pl.optString("text").ifBlank { "errore Hermes" }))
                    else t.inbox.offer("done" to (pl?.optString("text").orEmpty()))
                }
                "tool.start" -> ev.optJSONObject("payload")?.let { pl -> onActivity?.invoke(toolLabel(pl.optString("name"), pl.optString("context"))) }
                "tool.progress" -> ev.optJSONObject("payload")?.optString("preview")?.takeIf { it.isNotBlank() }?.let { onActivity?.invoke(it.replace('\n', ' ').take(80)) }
                "tool.complete" -> onActivity?.invoke("")
                "reasoning.delta", "thinking.delta" -> onActivity?.invoke("ragiona…")
                "status.update" -> ev.optJSONObject("payload")?.optString("text")?.takeIf { it.isNotBlank() }?.let { onActivity?.invoke(it.take(80)) }
                "__closed" -> t.inbox.offer("closed" to ev.optString("why"))
            }
        }
        turns[sid] = t
        try {
            rpc("prompt.submit", JSONObject().put("session_id", sid).put("text", text), retry = false)
            val deadline = System.currentTimeMillis() + 15 * 60_000
            while (System.currentTimeMillis() < deadline) {
                val (kind, s) = t.inbox.poll(1, TimeUnit.SECONDS) ?: continue
                when (kind) {
                    "err" -> throw RuntimeException(s)
                    "closed" -> throw java.io.IOException("Connessione con Hermes persa: $s")
                    else -> {
                        if (acc.isEmpty() && s.isNotEmpty() && !t.cancelled) { acc.append(s); onText(s) }
                        return acc.toString()
                    }
                }
            }
            throw java.io.IOException("Hermes ha impiegato troppo.")
        } finally { turns.remove(sid) }
    }

    // ---- funzioni di sessione di Hermes (rinomina, archivia, biforca, comprimi, modello, turni attivi) ----------

    class ModelRow(val provider: String, val providerName: String, val model: String, val current: Boolean)

    /** I modelli disponibili (`model.options`), con quello in uso. Tollera le forme diverse di risposta delle versioni di Hermes. */
    fun models(profile: String): List<ModelRow> {
        val r = rpc("model.options", scope(profile), 45)
        val curModel = r.optString("model"); val curProv = r.optString("provider")
        val out = ArrayList<ModelRow>()
        val provs = r.optJSONArray("providers") ?: return emptyList()
        for (i in 0 until provs.length()) {
            val pv = provs.optJSONObject(i) ?: continue
            val slug = pv.optString("slug"); val name = pv.optString("name").ifBlank { slug }
            val isCur = pv.optBoolean("is_current") || (curProv.isNotEmpty() && slug == curProv)
            val ms = pv.optJSONArray("models") ?: continue
            for (k in 0 until ms.length()) {
                val m = ms.opt(k)
                val id = when (m) { is String -> m; is JSONObject -> m.optString("id").ifBlank { m.optString("name") }; else -> "" }
                if (id.isNotBlank()) out.add(ModelRow(slug, name, id, isCur && (curModel.isEmpty() || id == curModel)))
            }
        }
        return out
    }

    /** Cambia modello per questa chat (`config.set model`); se Hermes chiede conferma (modello costoso) lo dice. */
    fun setModel(key: Long, profile: String, row: ModelRow, confirm: Boolean = false): String {
        val sid = session(key, profile)
        val r = rpc("config.set", JSONObject().put("session_id", sid).put("key", "model").put("value", "${row.model} --provider ${row.provider}").put("confirm_expensive_model", confirm), 60, retry = false)
        if (r.optBoolean("confirm_required")) return "CONFERMA"
        return r.optString("value").ifBlank { row.model }
    }

    fun rename(key: Long, profile: String, title: String) { rpc("session.title", JSONObject().put("session_id", session(key, profile)).put("title", title), 15, retry = false) }

    fun archive(hsid: String, profile: String) { rpc("session.archive", scope(profile).put("session_id", hsid).put("archived", true), 15, retry = false) }

    /** Biforca una sessione salvata: ritorna l'id della nuova sessione salvata. */
    fun branch(hsid: String, profile: String): String =
        rpc("session.branch_stored", scope(profile).put("parent_session_id", hsid), 60, retry = false).getString("stored_session_id").also {
            check(it.isNotBlank()) { "Hermes non ha restituito una sessione valida" }
        }

    /** Comprime il contesto (`session.compress`); ritorna lo stato. */
    fun compress(key: Long, profile: String): String =
        rpc("session.compress", JSONObject().put("session_id", session(key, profile)), 240, retry = false).let { it.optString("status").ifBlank { "fatto" } }

    /** Sessioni vive sul dashboard: chiave salvata -> stato ("working", "waiting", "idle"…), per vedere cosa gira anche dal PC o da Telegram. */
    fun liveChecked(): Map<String, String> {
        val arr = rpc("session.active_list", JSONObject(), 10).optJSONArray("sessions") ?: throw IllegalStateException("Elenco attività Hermes non valido")
        return (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.associate { it.optString("session_key") to it.optString("status") }
    }
    fun live(): Map<String, String> = runCatching { liveChecked() }.getOrDefault(emptyMap())

    /** Segue un turno che gira già (avviato altrove): inoltra testo e attività finché finisce. Ritorna il testo ricevuto. */
    fun observe(key: Long, profile: String, hsid: String, onActivity: ((String) -> Unit)?, onText: (String) -> Unit): String {
        val r = rpc("session.resume", scope(profile).put("session_id", hsid), 60)
        val sid = r.optString("session_id").also { check(it.isNotEmpty()) { "Hermes non ha restituito una sessione valida" } }
        sessions[key] = sid
        val t = Turn(sid); val acc = StringBuilder()
        t.onEvent = { ev ->
            when (ev.optString("type")) {
                "message.delta" -> ev.optJSONObject("payload")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { acc.append(it); onText(it) }
                "message.complete", "turn.complete" -> t.inbox.offer("done" to "")
                "tool.start" -> ev.optJSONObject("payload")?.let { pl -> onActivity?.invoke(toolLabel(pl.optString("name"), pl.optString("context"))) }
                "tool.complete" -> onActivity?.invoke("")
                "__closed" -> t.inbox.offer("closed" to ev.optString("why"))
            }
        }
        turns[sid] = t
        try {
            val deadline = System.currentTimeMillis() + 15 * 60_000
            var complete = false
            while (System.currentTimeMillis() < deadline) {
                val (kind, _) = t.inbox.poll(1, TimeUnit.SECONDS) ?: continue
                if (kind == "closed") throw java.io.IOException("Connessione con Hermes persa.")
                complete = true; break
            }
            if (!complete) throw java.io.IOException("Il turno continua su Hermes: riapri la chat per riprenderlo.")
        } finally { turns.remove(sid) }
        return acc.toString()
    }

    class Sub(val id: String, val goal: String, val status: String, val tools: Int, val canSteer: Boolean)

    /** I sotto-agenti che lavorano per questa chat (`subagent.list`). */
    fun subagents(key: Long, profile: String): List<Sub> {
        val arr = rpc("subagent.list", JSONObject().put("session_id", session(key, profile)), 15).optJSONArray("subagents") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Sub(it.optString("subagent_id"), it.optString("goal").replace('\n', ' '), it.optString("status"), it.optInt("tool_count"), it.optBoolean("accepting_steer")) }
    }

    fun stopSub(key: Long, profile: String, id: String): Boolean = rpc("subagent.interrupt", JSONObject().put("session_id", session(key, profile)).put("subagent_id", id), 15, retry = false).optBoolean("found")

    fun steerSub(key: Long, profile: String, id: String, text: String): Boolean =
        rpc("subagent.steer", JSONObject().put("session_id", session(key, profile)).put("subagent_id", id).put("text", text), 15, retry = false).optString("status") == "queued"

    /** Una riga di riepilogo dell'account Hermes (`billing.state`): piano gratuito, organizzazione e saldo, o non collegato. */
    fun account(profile: String): String {
        val r = rpc("billing.state", scope(profile), 20)
        return when {
            r.optBoolean("free_tier_account") -> "piano gratuito" + r.optString("free_tier_model").takeIf { it.isNotBlank() && it != "null" }?.let { " · $it" }.orEmpty()
            r.optBoolean("logged_in") -> listOf(r.optString("org_name").takeIf { it.isNotBlank() && it != "null" }, r.optString("balance_display").takeIf { it.isNotBlank() && it != "null" }?.let { "saldo $it" } ?: r.optString("balance_usd").takeIf { it.isNotBlank() && it != "null" }?.let { "saldo $$it" }).filterNotNull().joinToString(" · ").ifBlank { "accesso attivo" }
            else -> "account non collegato"
        }
    }

    // ---- attivita' pianificate (cron) e competenze: REST del dashboard con la stessa sessione ----------------------

    /** REST autenticato: se la sessione e' scaduta (401) rifa' il login una volta. */
    private fun rest(method: String, path: String, body: JSONObject? = null): String {
        var (c, b) = call(method, path, body)
        if (c == 401) { login(); val r = call(method, path, body); c = r.first; b = r.second }
        if (c !in 200..299) throw java.io.IOException(runCatching { JSONObject(b).optString("detail") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "HTTP $c")
        return b
    }
    private fun profileQuery(profile: String) = if (profile.isBlank() || profile == "default") "" else "?profile=" + java.net.URLEncoder.encode(profile, "UTF-8")

    /** Tutte le attivita' pianificate, di tutti i profili. */
    fun cronJobs(): List<HermesJob> = parseJobs(rest("GET", "/api/cron/jobs?profile=all"))
    fun cronAction(job: HermesJob, action: String) { rest("POST", "/api/cron/jobs/" + java.net.URLEncoder.encode(job.id, "UTF-8") + "/" + action + profileQuery(job.profile)) }   // pause | resume | trigger
    fun cronDelete(job: HermesJob) { rest("DELETE", "/api/cron/jobs/" + java.net.URLEncoder.encode(job.id, "UTF-8") + profileQuery(job.profile)) }
    /** Crea un'attivita' per un profilo (`repeatOnce`: una sola esecuzione). */
    fun cronCreate(profile: String, name: String, prompt: String, schedule: String, repeatOnce: Boolean) {
        val body = JSONObject().put("name", name).put("prompt", prompt).put("schedule", schedule).put("deliver", "local")
        if (repeatOnce) body.put("repeat", 1)
        rest("POST", "/api/cron/jobs" + profileQuery(profile), body)
    }

    fun skills(profile: String): List<HermesSkill> = parseSkills(rest("GET", "/api/skills" + profileQuery(profile)))
    fun toggleSkill(profile: String, name: String, enabled: Boolean) {
        rest("PUT", "/api/skills/toggle" + profileQuery(profile), JSONObject().put("name", name).put("enabled", enabled).apply { if (profile.isNotBlank() && profile != "default") put("profile", profile) })
    }

    /** Prova completa: login, ticket, WebSocket. Ritorna un messaggio per l'utente. */
    fun test(): String = try {
        synchronized(this) { ws?.close(1000, null); ws = null }; sessions.clear()
        connect()
        "OK: connesso a Hermes" + serverVersion.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    } catch (e: Exception) { e.message ?: "errore" }
}
