package dev.r1agents

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Un fornitore di servizi: Anthropic (API nativa) oppure un endpoint compatibile OpenAI (Hermes, OpenRouter, ...). */
class Provider(val id: String, val label: String, val anthropic: Boolean, val baseUrl: String, val model: String)

object Providers {
    val all = listOf(
        Provider("anthropic", "Claude (Anthropic)", true, "https://api.anthropic.com", "claude-sonnet-5-5"),
        // Nous Portal: endpoint compatibile OpenAI. Il nome del modello cambia con le release: modificabile nelle impostazioni.
        Provider("hermes", "Hermes (Nous Portal)", false, "https://inference-api.nousresearch.com/v1", "Hermes-4-70B"),
        // Hermes Agent sul tuo PC, attraverso il suo dashboard web (login utente/password + WebSocket).
        Provider("dashboard", "Hermes (dashboard sul PC)", false, "", ""),
        Provider("custom", "Personalizzato (OpenAI-compat.)", false, "https://openrouter.ai/api/v1", ""),
    )
    fun get(id: String) = all.firstOrNull { it.id == id } ?: all[0]
}

/** Vero se l'indirizzo è sicuro per mandarci una chiave: https o rete locale, senza userinfo né query. */
fun safeBase(url: String): Boolean {
    val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    val scheme = u.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (u.host.isNullOrBlank() || u.userInfo != null || u.rawQuery != null || u.rawFragment != null) return false
    return httpAllowed(scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else ""))
}

class Settings(val p: SharedPreferences, private val fixedProvider: String? = null) {
    var provider: Provider
        get() = Providers.get(fixedProvider ?: p.getString("provider", "anthropic")!!)
        set(v) { p.edit().putString("provider", v.id).apply() }
    fun key(pr: Provider) = p.secret("key_${pr.id}")
    /** L'indirizzo del provider: la chiave API viaggia solo verso https (o rete locale), senza utente:password né parametri nell'URL; altrimenti si usa quello predefinito. */
    fun base(pr: Provider): String {
        val saved = p.getString("base_${pr.id}", pr.baseUrl)?.ifBlank { pr.baseUrl } ?: pr.baseUrl
        return if (safeBase(saved)) saved else pr.baseUrl
    }
    fun model(pr: Provider) = p.getString("model_${pr.id}", pr.model)?.ifBlank { pr.model } ?: pr.model
    fun set(pr: Provider, key: String?, base: String?, model: String?) {
        p.edit().apply {
            key?.let { putSecret("key_${pr.id}", it.trim()) }
            base?.let { if (it.isBlank() || safeBase(it.trim())) putString("base_${pr.id}", it.trim()) }
            model?.let { putString("model_${pr.id}", it.trim()) }
        }.apply()
    }
}

class Engine(private val db: Db, private val settings: Settings, private val nous: NousAuth, private val dash: HermesDash) {
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled

    /** La chat Hermes di cui questo engine sta facendo il turno (se e' un turno Hermes) e l'engine di un eventuale agente chiamato da `message_agent`. */
    @Volatile private var hermesChat: Long? = null
    @Volatile private var child: Engine? = null

    /**
     * Ferma questo turno, qualunque sia il fornitore scelto adesso nelle impostazioni: la connessione locale, un eventuale agente chiamato e,
     * se il turno e' di Hermes, solo la sua chat (mai tutte le chat di Hermes insieme).
     */
    fun cancel(chatId: Long? = null) {
        cancelled = true; conn?.disconnect(); child?.cancel()
        (chatId ?: hermesChat)?.let { dash.cancel(it) }
    }

    /** Quanti agenti si sono passati la parola: gli strumenti di messaggio funzionano solo al primo livello. */
    var depth = 0
    /** Fornisce il testo di PhoneContext (impostato dall'activity solo se l'utente ha acconsentito). */
    companion object { @Volatile var phoneContext: (() -> String)? = null }

    /** Dove finiscono le direttive delle azioni sul telefono: il testo della risposta (poi gestite da DeviceActions: pulsanti o esecuzione automatica). */
    private var emit: ((String) -> Unit)? = null
    private val phoneTool = Triple("phone_action", "Agisce sul telefono dell'utente. kind: apri (nome app), timer (es. 10m), sveglia (es. 07:30), torcia (on/off), nota (testo), pannello (wifi, bluetooth, volume). L'utente conferma o la esecuzione è automatica, secondo le sue impostazioni.",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(DeviceActions.KINDS.sorted())))
            .put("arg", JSONObject().put("type", "string"))).put("required", JSONArray().put("kind").put("arg")))
    /** Gli strumenti del turno: quelli del telefono solo in chat (c'è una scheda di destinazione) e se l'utente non li ha spenti. */
    private fun activeTools() = if (DeviceActions.mode != "off" && onCard != null && emit != null) tools + phoneTool else tools

    /** Chiamata quando l'agente usa show_card: la chat disegna la scheda subito. */
    var onCard: ((JSONObject) -> Unit)? = null

    private fun schema(vararg props: String) = JSONObject().put("type", "object").put("properties", JSONObject().also { o -> props.forEach { o.put(it, JSONObject().put("type", "string")) } })
        .apply { if (props.isNotEmpty()) put("required", JSONArray(props.toList())) }

    private val tools = listOf(
        Triple("add_note", "Aggiunge una nota al blocco note dell'utente.", schema("text")),
        Triple("list_notes", "Elenca le note dell'utente (le più recenti per prime).", schema()),
        Triple("message_agent", "Manda un messaggio a un altro agente dell'utente e riporta la sua risposta.", schema("agent", "message")),
        Triple("show_card", "Mostra all'utente una scheda ben impaginata nella chat: un titolo e un testo breve in markdown (elenchi, grassetto). Per riepiloghi, risultati, liste; non per ogni risposta.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("body", JSONObject().put("type", "string"))
                .put("color", JSONObject().put("type", "string").put("description", "colore opzionale: orange, amber, green, teal, blue, purple, pink, red")))
                .put("required", JSONArray().put("title").put("body"))),
        Triple("get_time", "Data e ora correnti dell'utente.", JSONObject().put("type", "object").put("properties", JSONObject())),
        Triple("remember", "Salva una nota nella memoria a lungo termine di questo agente.",
            JSONObject().put("type", "object").put("properties", JSONObject().put("note", JSONObject().put("type", "string"))).put("required", JSONArray().put("note"))),
        Triple("web_fetch", "Scarica il testo di una pagina https pubblica.",
            JSONObject().put("type", "object").put("properties", JSONObject().put("url", JSONObject().put("type", "string"))).put("required", JSONArray().put("url"))),
    )

    /** Un turno: salva il messaggio, chiama il modello, esegue gli strumenti finché serve. `onText` riceve i delta. */
    /** Salva il messaggio dell'utente, a meno che sia un "riprova" e l'ultimo messaggio salvato sia già quello (un errore prima della risposta lo lascia nella chat). */
    private fun persistUser(agent: Agent, chatId: Long, userText: String, retry: Boolean, attachments: List<Attachment> = emptyList()) {
        val blocks = JSONArray().put(JSONObject().put("type", "text").put("text", userText))
        // Gli allegati restano nella chat come riferimenti (nome, tipo, file nella cartella privata): il contenuto si legge solo quando serve.
        attachments.forEach { blocks.put(JSONObject().put("type", "attachment").put("name", it.name).put("mime", it.mime).put("path", it.file.path)) }
        val json = blocks.toString()
        if (retry && db.messagesPage(chatId, 1, null).firstOrNull()?.let { it.second == "user" && it.third == json } == true) return
        db.add(agent.id, chatId, "user", json)
    }

    fun turn(agent: Agent, chatId: Long, userText: String, retry: Boolean = false, onActivity: ((String) -> Unit)? = null, attachments: List<Attachment> = emptyList(), onCard: ((JSONObject) -> Unit)? = null, onText: (String) -> Unit) {
        this.onCard = onCard; this.emit = onText
        if (cancelled) return
        val pr = settings.provider
        if (pr.id == "dashboard") {
            // La chat vive in Hermes (sessione sul PC): qui teniamo solo ciò che si vede.
            val mem = db.memories(agent.id).joinToString("\n") { "- " + it.second }
            val first = dash.needsIntro(chatId)
            val intro = (if (first && DeviceActions.mode != "off") DeviceActions.instruction() else "") + if (agent.profile.isEmpty() && first) buildString {
                if (agent.prompt.isNotBlank()) append("[Istruzioni per questa chat: ${agent.prompt}]\n")
                if (mem.isNotBlank()) append("[Cose da ricordare:\n$mem]\n")
                if (isNotEmpty()) append("\n")
            } else ""
            // Allegati verso Hermes: immagini e PDF si mettono in coda sulla sessione, i file di testo entrano nel messaggio.
            val inline = StringBuilder()
            for (att in attachments) when (att.kind) {
                Kind.IMAGE -> { onActivity?.invoke("invio ${att.name}…"); dash.attachImage(chatId, agent.profile, Attach.imageBytes(att), att.name) }
                Kind.PDF -> {
                    if (att.file.length() > 10L * 1024 * 1024) throw IllegalStateException("PDF troppo grande per Hermes (massimo 10 MB).")
                    onActivity?.invoke("invio ${att.name}…"); dash.attachPdf(chatId, agent.profile, att.file.readBytes(), att.name)
                }
                Kind.TEXT -> inline.append("\n\n[file: ${att.name}]\n```\n${Attach.text(att)}\n```")
                Kind.OTHER -> throw IllegalStateException("Tipo di file non supportato: ${att.name}")
            }
            onActivity?.invoke("")
            persistUser(agent, chatId, userText, retry, attachments)
            val body = userText.ifBlank { if (attachments.isNotEmpty()) "Guarda l'allegato." else "" } + inline
            hermesChat = chatId
            val out = try { dash.turn(chatId, agent.profile, intro + body, onActivity, onText) } finally { hermesChat = null }
            if (!cancelled && out.isNotBlank()) db.add(agent.id, chatId, "assistant", JSONArray().put(JSONObject().put("type", "text").put("text", out)).toString())
            return
        }
        val useNous = pr.id == "hermes" && nous.loggedIn
        val key = if (useNous) nous.accessToken() else settings.key(pr)
        if (key.isBlank()) throw IllegalStateException("Manca la chiave API di ${pr.label}. Inseriscila o accedi dalle Impostazioni.")
        val base = if (useNous) nous.inferenceBase() else settings.base(pr)
        if (base.isBlank()) throw IllegalStateException("Manca l'indirizzo di ${pr.label}. Inseriscilo dalle Impostazioni.")
        persistUser(agent, chatId, userText, retry, attachments)
        repeat(8) {
            val (content, stop) = if (pr.anthropic) streamAnthropic(agent, chatId, pr, key, base, onText) else streamOpenAi(agent, chatId, pr, key, base, onText)
            if (cancelled) return
            if (stop != "tool_use") {
                // Risposta tagliata (max_tokens) a metà di una chiamata: un tool_use senza tool_result rendeva la chat inutilizzabile (400 a ogni messaggio).
                val kept = withoutToolUse(content)
                if (kept.length() > 0) db.add(agent.id, chatId, "assistant", kept.toString())
                return
            }
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.getString("type") == "tool_use") {
                    onActivity?.invoke(toolLabel(b.getString("name"), b.optJSONObject("input")?.toString().orEmpty().take(60)))
                    results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.getString("id"))
                        .put("content", runTool(agent, b.getString("name"), b.optJSONObject("input") ?: JSONObject())))
                }
            }
            db.add(agent.id, chatId, "assistant", content.toString())
            db.add(agent.id, chatId, "user", results.toString())
            onText("\n")
        }
        onActivity?.invoke("")
    }

    // ---- storia ---------------------------------------------------------------------------------

    /** Ultimi messaggi, partendo da un messaggio utente di testo (mai da un tool_result orfano). */
    private val b64Cache = object : LinkedHashMap<String, String>(8, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 6 }

    /** Gli allegati recenti (gli ultimi 3 della conversazione) tornano al modello come immagini, PDF o testo; i più vecchi restano un riferimento. */
    private fun expandAttachments(c: JSONArray, live: () -> Boolean): JSONArray {
        val out = JSONArray()
        for (i in 0 until c.length()) {
            val b = c.getJSONObject(i)
            if (b.getString("type") != "attachment") { out.put(b); continue }
            val a = Attachment(b.optString("name"), b.optString("mime"), java.io.File(b.optString("path")))
            fun note(t: String) = out.put(JSONObject().put("type", "text").put("text", t))
            // Solo file nella cartella degli allegati dell'app: nessun altro percorso (da un backup o altro) viene letto e inviato.
            if (!Attach.inAttachDir(a.file) || !a.file.exists()) { note("[allegato non più disponibile: ${a.name}]"); continue }
            if (!live()) { note("[allegato: ${a.name}]"); continue }
            try {
                when (a.kind) {
                    Kind.IMAGE -> out.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", b64Cache.getOrPut(a.file.path) { android.util.Base64.encodeToString(Attach.imageBytes(a), android.util.Base64.NO_WRAP) })))
                    Kind.PDF -> out.put(JSONObject().put("type", "document").put("name", a.name).put("source", JSONObject().put("type", "base64").put("media_type", "application/pdf")
                        .put("data", b64Cache.getOrPut(a.file.path) { android.util.Base64.encodeToString(a.file.readBytes(), android.util.Base64.NO_WRAP) })))
                    Kind.TEXT -> note("[file: ${a.name}]\n```\n${Attach.text(a)}\n```")
                    Kind.OTHER -> note("[file: ${a.name}]")
                }
            } catch (e: Exception) { note("[allegato non leggibile: ${a.name}]") }
        }
        return out
    }

    private fun history(chatId: Long): List<Pair<String, JSONArray>> {
        var left = 3
        val all = db.messages(chatId).map { it.first to JSONArray(it.second) }.takeLast(60).asReversed()
            .map { (r, c) -> r to expandAttachments(c) { left-- > 0 } }.asReversed()
        val start = all.indexOfFirst { (r, c) -> r == "user" && c.optJSONObject(0)?.optString("type") == "text" }
        return if (start < 0) emptyList() else all.drop(start)
    }

    private fun system(a: Agent): String = buildString {
        append(a.prompt.ifBlank { "Sei un assistente utile e conciso." })
        append("\n\nParli su un piccolo schermo (240x282): risposte brevi, senza tabelle.")
        append("\nOra locale: ").append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm")))
        val others = db.agents().filter { it.id != a.id }.map { it.name }
        if (others.isNotEmpty() && depth == 0) append("\nAltri agenti con cui puoi parlare (strumento message_agent): ").append(others.joinToString())
        if (depth == 0) phoneContext?.invoke()?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        val mem = db.memories(a.id)
        if (mem.isNotEmpty()) append("\n\nMemoria (cose che ti ha chiesto di ricordare):\n").append(mem.joinToString("\n") { "- " + it.second })
    }

    // ---- Anthropic ------------------------------------------------------------------------------

    private fun streamAnthropic(a: Agent, chat: Long, pr: Provider, key: String, base: String, onText: (String) -> Unit): Pair<JSONArray, String> {
        val msgs = JSONArray()
        for ((role, c) in history(chat)) {
            val clean = JSONArray()
            for (i in 0 until c.length()) {
                val b = c.getJSONObject(i)
                if (b.getString("type") == "text" && b.optString("text").isBlank()) continue
                clean.put(b)
            }
            if (clean.length() > 0) msgs.put(JSONObject().put("role", role).put("content", clean))
        }
        val toolDefs = JSONArray().also { arr -> activeTools().forEach { (n, d, s) -> arr.put(JSONObject().put("name", n).put("description", d).put("input_schema", s)) } }
        val body = JSONObject().put("model", settings.model(pr)).put("max_tokens", 2048).put("stream", true)
            .put("system", system(a)).put("messages", msgs).put("tools", toolDefs)
        val blocks = sortedMapOf<Int, JSONObject>()
        val partial = HashMap<Int, StringBuilder>()
        var stop = "end_turn"
        post(base.trimEnd('/') + "/v1/messages", body, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")) { data ->
            val ev = JSONObject(data)
            when (ev.optString("type")) {
                "content_block_start" -> {
                    val cb = ev.getJSONObject("content_block")
                    if (cb.getString("type") == "text") cb.put("text", "")
                    blocks[ev.getInt("index")] = cb
                }
                "content_block_delta" -> {
                    val i = ev.getInt("index"); val d = ev.getJSONObject("delta")
                    when (d.optString("type")) {
                        "text_delta" -> { blocks[i]?.let { it.put("text", it.optString("text") + d.getString("text")) }; onText(d.getString("text")) }
                        "input_json_delta" -> partial.getOrPut(i) { StringBuilder() }.append(d.getString("partial_json"))
                    }
                }
                "content_block_stop" -> blocks[ev.getInt("index")]?.let {
                    if (it.getString("type") == "tool_use") it.put("input", runCatching { JSONObject(partial[ev.getInt("index")]?.toString() ?: "{}") }.getOrDefault(JSONObject()))
                }
                "message_delta" -> ev.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { stop = it }
                "error" -> throw RuntimeException(ev.optJSONObject("error")?.optString("message") ?: "errore API")
            }
        }
        val out = JSONArray()
        blocks.values.filterNot { it.getString("type") == "text" && it.optString("text").isBlank() }.forEach { out.put(it) }
        return out to stop
    }

    // ---- OpenAI-compatibile (Hermes / Nous Portal) ----------------------------------------------

    private fun streamOpenAi(a: Agent, chat: Long, pr: Provider, key: String, base: String, onText: (String) -> Unit): Pair<JSONArray, String> {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system(a)))
        for ((role, c) in history(chat)) {
            if (role == "user") {
                val text = StringBuilder(); val images = JSONArray()
                for (i in 0 until c.length()) {
                    val b = c.getJSONObject(i)
                    when (b.getString("type")) {
                        "tool_result" -> msgs.put(JSONObject().put("role", "tool").put("tool_call_id", b.getString("tool_use_id")).put("content", b.optString("content")))
                        "image" -> images.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${b.getJSONObject("source").optString("media_type")};base64,${b.getJSONObject("source").optString("data")}")))
                        "document" -> text.append("\n[PDF ${b.optString("name")}: questo fornitore non lo accetta]")
                        else -> text.append(b.optString("text"))
                    }
                }
                if (images.length() > 0) {
                    val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text.toString().ifBlank { "Guarda l'immagine." }))
                    for (k in 0 until images.length()) parts.put(images.getJSONObject(k))
                    msgs.put(JSONObject().put("role", "user").put("content", parts))
                } else if (text.isNotBlank()) msgs.put(JSONObject().put("role", "user").put("content", text.toString()))
            } else {
                val text = StringBuilder(); val calls = JSONArray()
                for (i in 0 until c.length()) {
                    val b = c.getJSONObject(i)
                    if (b.getString("type") == "text") text.append(b.optString("text"))
                    else if (b.getString("type") == "tool_use") calls.put(JSONObject().put("id", b.getString("id")).put("type", "function")
                        .put("function", JSONObject().put("name", b.getString("name")).put("arguments", b.optJSONObject("input")?.toString() ?: "{}")))
                }
                val m = JSONObject().put("role", "assistant").put("content", if (text.isBlank()) JSONObject.NULL else text.toString())
                if (calls.length() > 0) m.put("tool_calls", calls)
                msgs.put(m)
            }
        }
        val toolDefs = JSONArray().also { arr -> activeTools().forEach { (n, d, s) ->
            arr.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", d).put("parameters", s))) } }
        val body = JSONObject().put("model", settings.model(pr)).put("max_tokens", 2048).put("stream", true)
            .put("messages", msgs).put("tools", toolDefs)
        val text = StringBuilder()
        val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, args
        var finish = "stop"
        post(base.trimEnd('/') + "/chat/completions", body, mapOf("Authorization" to "Bearer $key")) { data ->
            if (data == "[DONE]") return@post
            val ev = JSONObject(data)
            ev.optJSONObject("error")?.let { throw RuntimeException(it.optString("message", "errore API")) }
            val ch = ev.optJSONArray("choices")?.optJSONObject(0) ?: return@post
            ch.optJSONObject("delta")?.let { d ->
                d.optString("content").takeIf { it.isNotEmpty() && it != "null" }?.let { text.append(it); onText(it) }
                d.optJSONArray("tool_calls")?.let { tc ->
                    for (i in 0 until tc.length()) {
                        val t = tc.getJSONObject(i)
                        val e = calls.getOrPut(t.optInt("index", i)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                        t.optString("id").takeIf { it.isNotEmpty() }?.let { e.first.append(it) }
                        t.optJSONObject("function")?.let { f ->
                            f.optString("name").takeIf { it.isNotEmpty() }?.let { e.second.append(it) }
                            f.optString("arguments").takeIf { it.isNotEmpty() }?.let { e.third.append(it) }
                        }
                    }
                }
            }
            ch.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
        }
        val out = JSONArray()
        if (text.isNotBlank()) out.put(JSONObject().put("type", "text").put("text", text.toString()))
        calls.values.forEach { (id, name, args) ->
            out.put(JSONObject().put("type", "tool_use").put("id", id.toString().ifEmpty { "call_" + UUID.randomUUID().toString().take(8) })
                .put("name", name.toString())
                .put("input", runCatching { JSONObject(args.toString().ifBlank { "{}" }) }.getOrDefault(JSONObject())))
        }
        // Con finish_reason "length" gli argomenti sono troncati: lo strumento non parte (niente note vuote o azioni a metà).
        return out to if (calls.isNotEmpty() && finish != "length") "tool_use" else finish
    }

    // ---- HTTP + SSE -----------------------------------------------------------------------------

    private fun post(url: String, body: JSONObject, headers: Map<String, String>, onData: (String) -> Unit) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 90000
            setRequestProperty("content-type", "application/json"); setRequestProperty("accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn = c
        try {
            if (cancelled) return
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                val msg = runCatching { JSONObject(err).let { it.optJSONObject("error")?.optString("message") ?: it.optString("message") } }.getOrNull()
                throw RuntimeException("HTTP ${c.responseCode}: ${Vault.redact(msg?.takeIf { it.isNotBlank() } ?: err.take(200))}")
            }
            BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                while (!cancelled) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("data:")) onData(line.removePrefix("data:").trim())
                }
            }
        } catch (e: java.io.IOException) {
            if (!cancelled) throw e
        } finally { c.disconnect() }
    }

    // ---- strumenti ------------------------------------------------------------------------------

    private fun runTool(a: Agent, name: String, input: JSONObject): String = try {
        when (name) {
            "get_time" -> ZonedDateTime.now().toString()
            "show_card" -> { onCard?.invoke(input); "scheda mostrata all'utente" }
            "remember" -> {
                db.addMemory(a.id, input.optString("note")); "salvato"
            }
            "web_fetch" -> fetch(input.optString("url"))
            "phone_action" -> {
                val kind = input.optString("kind").trim().lowercase(); val arg = input.optString("arg").trim()
                DeviceActions.validate(kind, arg) ?: run {
                    emit?.invoke("\n[[r1:$kind=$arg]]\n")
                    if (DeviceActions.mode == "auto") "eseguita sul telefono" else "mostrata all'utente come pulsante da confermare; non è ancora stata eseguita"
                }
            }
            "add_note" -> { db.addNote(input.optString("text")); "nota salvata" }
            "list_notes" -> db.notes().take(30).joinToString("\n") { "- " + it.second }.ifBlank { "nessuna nota" }
            "message_agent" -> {
                val target = db.agents().firstOrNull { it.name.equals(input.optString("agent").trim(), true) && it.id != a.id }
                when {
                    depth >= 1 -> "non puoi passare il messaggio oltre"
                    target == null -> "agente non trovato; disponibili: " + db.agents().filter { it.id != a.id }.joinToString { it.name }
                    settings.provider.id == "dashboard" -> "non disponibile con Hermes dashboard"
                    else -> {
                        val chat = db.newChat(target.id); db.title(chat, "da ${a.name}")
                        val sb = StringBuilder()
                        Engine(db, settings, nous, dash).also { it.depth = depth + 1; child = it }.turn(target, chat, "[Messaggio da ${a.name}] ${input.optString("message")}") { sb.append(it) }
                        sb.toString().take(2000).ifBlank { "(nessuna risposta)" }
                    }
                }
            }
            else -> "strumento sconosciuto"
        }
    } catch (e: Exception) { "errore: ${Vault.redact(e.message)}" }

    /**
     * Solo https verso host pubblici: l'agente non deve poter raggiungere la rete locale dell'utente. Il DNS si risolve
     * una volta sola e la connessione usa proprio gli indirizzi verificati (niente "DNS rebinding" tra controllo e uso).
     */
    private fun fetch(start: String): String {
        var url = start
        val client = Net.base.newBuilder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val all = InetAddress.getAllByName(hostname).toList()
                    require(all.isNotEmpty() && all.all { WebGuard.publicAddress(it) }) { "host non pubblico" }
                    return all
                }
            }).build()
        repeat(4) {
            val u = URL(url)
            require(u.protocol == "https") { "solo https" }
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                if (r.code in 300..399) { url = URL(u, r.header("Location") ?: error("redirect vuoto")).toString(); return@repeat }
                val raw = r.body?.charStream()?.use { WebGuard.readUpTo(it, 200_000) }.orEmpty()
                return raw.replace(Regex("(?is)<(script|style).*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim().take(6000)
            }
        }
        error("troppi redirect")
    }
}

/** Toglie i blocchi tool_use: si salvano solo quando seguono i loro tool_result. */
internal fun withoutToolUse(content: JSONArray): JSONArray {
    val out = JSONArray()
    for (i in 0 until content.length()) content.optJSONObject(i)?.takeIf { it.optString("type") != "tool_use" }?.let { out.put(it) }
    return out
}
