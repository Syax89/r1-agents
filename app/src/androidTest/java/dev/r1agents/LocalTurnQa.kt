package dev.r1agents

import android.content.Context
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** SSE locale: cancellazione durante il flusso e isolamento del turno successivo, senza API reali. */
internal object LocalTurnQa {
    fun run(ctx: Context, agent: Agent) {
        check(ctx.packageName.endsWith(".qa"))
        val prefs = ctx.getSharedPreferences("settings", 0)
        val db = Db.get(ctx)
        fun exercise(cancel: Boolean) {
            val server = ServerSocket(0).apply { soTimeout = 6000 }
            val resume = CountDownLatch(1)
            val received = CountDownLatch(1)
            val deltas = AtomicInteger()
            val chat = db.newChat(agent.id)
            val settings = Settings(prefs, "custom")
            settings.set(Providers.get("custom"), "qa-only-not-a-real-key", "http://127.0.0.1:${server.localPort}", "qa")
            val peer = thread(isDaemon = true, name = "qa-sse-peer") {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 6000
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: return@use
                            if (line.isEmpty()) break
                            if (line.startsWith("content-length:", true)) length = line.substringAfter(":").trim().toInt()
                        }
                        val body = CharArray(length)
                        var consumed = 0
                        while (consumed < length) { val n = input.read(body, consumed, length - consumed); if (n < 0) return@use; consumed += n }
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        fun delta(text: String) {
                            val chunk = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))))
                            out.write("data: $chunk\n\n".toByteArray()); out.flush()
                        }
                        delta("prima ")
                        resume.await(5, TimeUnit.SECONDS)
                        delta("ultima")
                        out.write("data: [DONE]\n\n".toByteArray()); out.flush()
                    }
                }
            }
            val engine = Engine(db, settings, NousAuth(prefs), HermesRuntime.get(ctx).dash)
            var failure: Throwable? = null
            val worker = thread(name = "qa-local-turn") {
                try { engine.turn(agent, chat, "qa stream") { deltas.incrementAndGet(); received.countDown() } }
                catch (e: Throwable) { failure = e; received.countDown() }
            }
            try {
                check(received.await(6, TimeUnit.SECONDS)) { "SSE locale non avviato" }
                failure?.let { throw it }
                // Un cambio nelle preferenze non deve cambiare il provider dell'engine già creato.
                prefs.edit().putString("provider", "anthropic").commit()
                if (cancel) engine.cancel()
                resume.countDown(); worker.join(6000)
                check(!worker.isAlive) { "Turno locale ancora attivo dopo la fine del test" }
                failure?.let { throw it }
                if (cancel) {
                    check(engine.isCancelled) { "Stato di annullamento perso a fine turno" }
                    check(deltas.get() == 1) { "Delta tardivo dopo annullamento" }
                    check(db.messages(chat).none { it.first == "assistant" }) { "Risposta annullata salvata" }
                } else {
                    check(db.messages(chat).any { it.first == "assistant" && it.second.contains("prima ultima") }) { "Turno indipendente successivo non completato" }
                }
            } finally { engine.cancel(); resume.countDown(); server.close(); peer.join(1000); worker.join(1000); db.deleteChat(chat); prefs.edit().putString("provider", "custom").commit() }
        }
        exercise(true)
        exercise(false)
    }
}
