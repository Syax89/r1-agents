package dev.r1agents

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Riconoscimento vocale offline con Vosk: niente rete, l'audio non lascia il Rabbit. Il modello piccolo della lingua
 * (italiano ~50 MB, inglese ~40 MB) si scarica una volta dalle impostazioni da alphacephei.com e resta nei file dell'app.
 * Più lento e meno preciso di Whisper, ma funziona senza chiavi e senza Internet.
 */
object OfflineModels {
    private val urls = mapOf(
        "it" to "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
        "en" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
    )
    fun lang(ctx: Context) = if (AppLanguage.selected(ctx) == "en") "en" else "it"
    fun dir(ctx: Context, lang: String = lang(ctx)) = File(ctx.filesDir, "vosk/$lang")
    fun ready(ctx: Context, lang: String = lang(ctx)) = File(dir(ctx, lang), "am").isDirectory || File(dir(ctx, lang), "conf").isDirectory

    @Volatile var downloading = false
        private set

    /** Scarica e scompatta il modello (su un thread di sfondo). `progress` 0..100. */
    fun download(ctx: Context, lang: String, progress: (Int) -> Unit, done: (String?) -> Unit) {
        if (downloading) return
        downloading = true
        val main = Handler(Looper.getMainLooper())
        Io.run {
            val tmp = File(ctx.cacheDir, "vosk-$lang.zip")
            val out = File(ctx.filesDir, "vosk/$lang.part")
            val err = runCatching {
                val req = okhttp3.Request.Builder().url(urls.getValue(lang)).build()
                Net.base.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val body = r.body ?: throw RuntimeException("risposta vuota")
                    val total = body.contentLength().coerceAtLeast(1)
                    var read = 0L; var last = -1
                    body.byteStream().use { input -> tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; o.write(buf, 0, n); read += n
                            val pc = (read * 90 / total).toInt(); if (pc != last) { last = pc; main.post { progress(pc) } } }
                    } }
                }
                out.deleteRecursively(); out.mkdirs()
                // Lo zip ha una cartella in cima (vosk-model-small-xx/...): si toglie. Nessun percorso può uscire dalla cartella.
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    val root = out.canonicalPath + File.separator
                    while (true) {
                        val e = z.nextEntry ?: break
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty()) continue
                        val f = File(out, rel)
                        if (!f.canonicalPath.startsWith(root)) throw SecurityException("percorso non valido nello zip")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                }
                val dest = dir(ctx, lang); dest.deleteRecursively()
                if (!out.renameTo(dest)) throw RuntimeException("impossibile salvare il modello")
                main.post { progress(100) }
            }.exceptionOrNull()
            tmp.delete(); out.deleteRecursively()
            downloading = false
            main.post { done(err?.let { UiText.message(ctx, it.message) ?: it.javaClass.simpleName }) }
        }
    }

    fun delete(ctx: Context, lang: String) { dir(ctx, lang).deleteRecursively() }

    /** Solo prove (build di debug): trascrive un WAV PCM 16 kHz mono già registrato, senza microfono. */
    fun transcribeWav(ctx: Context, wav: File): String {
        val m = model(ctx)
        val rec = try { org.vosk.Recognizer(m, 16000f) } catch (e: Throwable) { idle(); throw e }
        try {
            wav.inputStream().use { input ->
                input.skip(44)   // intestazione WAV
                val buf = ByteArray(4096)
                while (true) { val n = input.read(buf); if (n <= 0) break; rec.acceptWaveForm(buf, n) }
            }
            return org.json.JSONObject(rec.finalResult).optString("text")
        } finally { rec.close(); idle() }
    }

    /**
     * Il modello resta in memoria tra un vocale e l'altro (caricarlo costa ~4 s), ma pesa ~180 MB: dopo 2 minuti senza
     * vocali si libera. `idle()` va chiamato quando un riconoscimento finisce.
     */
    @Volatile private var loaded: Pair<String, org.vosk.Model>? = null
    private val unloader = Handler(Looper.getMainLooper())
    private val unload = Runnable { Io.run { synchronized(this) { if (inUse == 0) { loaded?.second?.close(); loaded = null } } } }
    private var inUse = 0
    @Synchronized fun model(ctx: Context): org.vosk.Model {
        unloader.removeCallbacks(unload)
        val l = lang(ctx)
        loaded?.takeIf { it.first == l }?.let { inUse++; return it.second }
        loaded?.second?.close(); loaded = null
        return org.vosk.Model(dir(ctx, l).absolutePath).also { loaded = l to it; inUse++ }
    }
    @Synchronized fun idle() { inUse = (inUse - 1).coerceAtLeast(0); unloader.removeCallbacks(unload); unloader.postDelayed(unload, 120_000L) }
}

class OfflineStt(private val ctx: Context, private val onStatus: (String) -> Unit, private val onFinal: (String) -> Unit,
                 private val onEnd: (String?) -> Unit) : Mic {
    private var phaseListener: ((MicPhase) -> Unit)? = null
    override fun onPhase(listener: (MicPhase) -> Unit) { phaseListener = listener }
    private var levelListener: ((Float) -> Unit)? = null
    override fun onLevel(listener: (Float) -> Unit) { levelListener = listener }
    override var startFailed = false
    private var service: org.vosk.android.SpeechService? = null
    private var recognizer: org.vosk.Recognizer? = null
    private var heard = ""
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    override fun start(): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { askMic(ctx); return false }
        cancel(); startFailed = false; heard = ""
        if (!OfflineModels.ready(ctx)) { startFailed = true; onEnd(UiText.text(ctx, R.string.ui_934732cd9d)); return false }
        return try {
            val m = OfflineModels.model(ctx)
            val rec = try { org.vosk.Recognizer(m, 16000f) } catch (e: Throwable) { OfflineModels.idle(); throw e }
            recognizer = rec
            val token = ++generation
            service = org.vosk.android.SpeechService(rec, 16000f).also { s ->
                s.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(h: String?) { if (token == generation) text(h, "partial")?.let { onStatus(it) } }
                    override fun onResult(h: String?) { if (token == generation) text(h, "text")?.let { heard = (heard + " " + it).trim(); onStatus(heard) } }
                    override fun onFinalResult(h: String?) {
                        if (token != generation) return
                        text(h, "text")?.let { heard = (heard + " " + it).trim() }
                        finish(token)
                    }
                    override fun onError(e: Exception?) { if (token == generation) { release(); onEnd(UiText.message(ctx, e?.message)) } }
                    override fun onTimeout() { if (token == generation) finish(token) }
                })
            }
            phaseListener?.invoke(MicPhase.LISTENING)
            onStatus(UiText.text(ctx, R.string.ui_77cfbcff1e))
            true
        } catch (e: Throwable) {   // anche UnsatisfiedLinkError (architettura senza libreria nativa)
            release(); startFailed = true
            onEnd(UiText.text(ctx, R.string.ui_f29f43c703, UiText.message(ctx, e.message) ?: e.javaClass.simpleName)); false
        }
    }

    private fun text(json: String?, key: String) = runCatching { org.json.JSONObject(json ?: "").optString(key).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun finish(token: Int) {
        main.post {
            if (token != generation) return@post
            generation++
            release()
            if (heard.isBlank()) onEnd(null) else onFinal(heard)
        }
    }

    /** Smette di ascoltare: Vosk consegna l'ultimo pezzo in onFinalResult. */
    override fun stop() { val s = service ?: return; phaseListener?.invoke(MicPhase.TRANSCRIBING); runCatching { s.stop() }.onFailure { finish(generation) } }

    override fun cancel() { generation++; release() }

    private fun release() {
        service?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }; service = null
        recognizer?.let { runCatching { it.close() }; OfflineModels.idle() }; recognizer = null
    }
}
