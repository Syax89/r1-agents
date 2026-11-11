package dev.r1agents

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.Locale

/** Tieni premuto e parla: SpeechRecognizer con risultati parziali. Tutto sul thread principale. */
/** Un microfono "tieni premuto e parla". `start` ritorna false se manca il permesso (lo chiede) o il servizio non c'è. */
enum class MicPhase { LISTENING, TRANSCRIBING }

interface Mic {
    fun start(): Boolean; fun stop(); fun cancel(); fun onLevel(listener: (Float) -> Unit) {}; fun onPhase(listener: (MicPhase) -> Unit) {}
    /** Vero se `start` ha già riportato l'errore a `onEnd`: il chiamante non deve mostrare "in ascolto" né ripiegare su un altro riconoscitore. */
    val startFailed: Boolean get() = false
}

/** Groq scelto ma senza chiave: errore chiaro (e non il riconoscimento del dispositivo di nascosto). */
class UnavailableMic(private val ctx: Context, private val message: Int, private val onEnd: (String?) -> Unit) : Mic {
    override var startFailed = false
    override fun start(): Boolean { startFailed = true; onEnd(UiText.text(ctx, message)); return false }
    override fun stop() {}
    override fun cancel() {}
}

class Voice(private val act: Context, private val onPartial: (String) -> Unit, private val onFinal: (String) -> Unit, private val onEnd: (String?) -> Unit) : Mic {
    private var phaseListener: ((MicPhase) -> Unit)? = null
    override fun onPhase(listener: (MicPhase) -> Unit) { phaseListener = listener }
    private var levelListener: ((Float) -> Unit)? = null
    override fun onLevel(listener: (Float) -> Unit) { levelListener = listener }
    private var rec: SpeechRecognizer? = null
    private var gotFinal = false
    private var generation = 0

    fun available() = SpeechRecognizer.isRecognitionAvailable(act)

    /** False se manca il permesso (lo chiede) o il riconoscimento non c'è. */
    override fun start(): Boolean {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic(act); return false
        }
        if (!available()) return false
        cancel(); gotFinal = false
        val token = generation
        rec = SpeechRecognizer.createSpeechRecognizer(act).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(b: Bundle?) { if (token != generation) return; b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial) }
                override fun onResults(b: Bundle?) {
                    if (token != generation) return
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    gotFinal = true; if (t.isNotBlank()) onFinal(t) else onEnd(null)
                }
                override fun onError(error: Int) {
                    if (token != generation || gotFinal) return
                    onEnd(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> UiText.text(act, R.string.ui_b2a5d7ca89)
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> UiText.text(act, R.string.ui_7cdb40b380)
                        else -> UiText.text(act, R.string.ui_4ddcb61a11, error)
                    })
                }
                override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(r: Float) { if (token == generation) levelListener?.invoke(((r + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppLanguage.locale(act).toLanguageTag()))
        }
        phaseListener?.invoke(MicPhase.LISTENING)
        return true
    }

    override fun stop() { if (rec != null) phaseListener?.invoke(MicPhase.TRANSCRIBING); rec?.stopListening() }
    override fun cancel() { generation++; rec?.destroy(); rec = null }
}

/**
 * Riconoscimento con Groq (Whisper large-v3-turbo): registra in AAC mono 16 kHz mentre tieni premuto, poi invia il file a
 * `POST https://api.groq.com/openai/v1/audio/transcriptions`. L'audio lascia il dispositivo verso Groq; il file temporaneo
 * viene cancellato subito dopo.
 */
class GroqStt(private val act: Context, private val key: String, private val model: String, private val lang: String,
              private val onStatus: (String) -> Unit, private val onFinal: (String) -> Unit, private val onEnd: (String?) -> Unit,
              private val autoStop: Boolean = false,
              /** Qualunque servizio con `POST <base>/audio/transcriptions` (Groq, OpenAI, un Whisper sul PC di casa). */
              private val base: String = GROQ_BASE, private val label: String = "Groq",
              /** Al posto dell'invio a /audio/transcriptions: chi trascrive il file (es. Hermes). Gira fuori dal thread principale. */
              private val transcriber: ((java.io.File) -> String)? = null) : Mic {
    private var phaseListener: ((MicPhase) -> Unit)? = null
    override fun onPhase(listener: (MicPhase) -> Unit) { phaseListener = listener }
    private var levelListener: ((Float) -> Unit)? = null
    override fun onLevel(listener: (Float) -> Unit) { levelListener = listener }
    @Volatile private var generation = 0
    private var spoke = false
    private var lastLoud = 0L
    private var recorder: android.media.MediaRecorder? = null
    private var file: java.io.File? = null
    private var startedAt = 0L
    override var startFailed = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val http = Net.base.newBuilder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build()
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis(); val s = (now - startedAt) / 1000
            if (s >= 60) { stop(); return }
            val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            levelListener?.invoke((amp / 12000f).coerceIn(0f, 1f))
            if (autoStop) {
                // Senza rilascio del tasto (gesto da assistente): si ferma da sola dopo ~1,2 s di silenzio dopo aver parlato.
                if (amp > 2500) { spoke = true; lastLoud = now }
                else if (spoke && now - lastLoud > 1200) { stop(); return }
                else if (!spoke && now - startedAt > 7000) { cancel(); onEnd(null); return }
            }
            onStatus(UiText.text(act, R.string.ui_85da8e16e5, s)); handler.postDelayed(this, if (autoStop || levelListener != null) 120 else 500)
        }
    }

    @Suppress("DEPRECATION")
    override fun start(): Boolean {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic(act); return false
        }
        cancel()
        startFailed = false
        val f = java.io.File.createTempFile("stt_", ".m4a", act.cacheDir)
        file = f
        // Il registratore si crea a parte: se prepare() o start() falliscono va comunque rilasciato (prima restava aperto).
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(act) else android.media.MediaRecorder()
        return try {
            r.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000)
                setOutputFile(f.absolutePath); prepare(); start()
            }
            recorder = r
            file = f; startedAt = System.currentTimeMillis(); spoke = false; lastLoud = startedAt; phaseListener?.invoke(MicPhase.LISTENING); handler.post(tick); true
        } catch (e: Exception) {
            runCatching { r.release() }
            handler.removeCallbacks(tick); recorder = null; f.delete(); file = null
            startFailed = true
            onEnd(UiText.text(act, R.string.ui_f29f43c703, UiText.message(act, e.message)))
            false
        }
    }

    override fun stop() {
        val r = recorder ?: return
        handler.removeCallbacks(tick); recorder = null
        val long = System.currentTimeMillis() - startedAt
        try { r.stop() } catch (e: RuntimeException) { file?.delete(); r.release(); onEnd(null); return }
        r.release()
        val f = file ?: return
        if (long < 700) { f.delete(); onEnd(null); return }
        phaseListener?.invoke(MicPhase.TRANSCRIBING)
        onStatus(UiText.text(act, R.string.ui_ac288cfd8d))
        val token = generation
        Io.run {
            val res = runCatching { transcriber?.invoke(f) ?: upload(f) }
            f.delete()
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (token != generation) return@post; res.fold({ if (it.isBlank()) onEnd(null) else onFinal(it) }, { onEnd(it.message) }) }
        }
    }

    override fun cancel() {
        generation++
        handler.removeCallbacks(tick)
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null; file?.delete()
    }

    private fun upload(f: java.io.File): String {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", model.ifBlank { if (base == GROQ_BASE) "whisper-large-v3-turbo" else "whisper-1" }).addFormDataPart("response_format", "json").addFormDataPart("temperature", "0")
            .apply { if (lang.isNotBlank()) addFormDataPart("language", lang) }
            .addFormDataPart("file", "audio.m4a", f.asRequestBody("audio/mp4".toMediaType())).build()
        val req = okhttp3.Request.Builder().url(base.trimEnd('/') + "/audio/transcriptions").apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.post(body).build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { org.json.JSONObject(s).optJSONObject("error")?.optString("message") }.getOrNull()
                throw RuntimeException(when (r.code) { 401 -> UiText.text(act, R.string.ui_333bdf5763); 429 -> UiText.text(act, R.string.ui_895511bdf5); else -> "$label: ${msg ?: "HTTP ${r.code}"}" })
            }
            return org.json.JSONObject(s).optString("text").trim()
        }
    }

    companion object {
        /** Prova la chiave: elenco dei modelli. Ritorna un messaggio. */
        const val GROQ_BASE = "https://api.groq.com/openai/v1"
        fun test(ctx: Context, key: String, base: String = GROQ_BASE): String = try {
            val c = Net.base.newCall(okhttp3.Request.Builder().url(base.trimEnd('/') + "/models").apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.build()).execute()
            c.use { if (it.isSuccessful) UiText.text(ctx, R.string.ui_91864135d3) else if (it.code == 401) UiText.text(ctx, R.string.ui_7613169a51) else UiText.text(ctx, R.string.ui_9457120454, it.code) }
        } catch (e: Exception) { UiText.text(ctx, R.string.ui_bfc309c98a, UiText.message(ctx, e.message)) }
    }
}

/** Chiede il microfono: da un'attività con il dialogo di sistema, da un servizio con un invito ad aprire l'app. */
fun askMic(c: Context) {
    if (c is Activity) c.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6)
    else android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(c, UiText.text(c, R.string.ui_46343f38a5), android.widget.Toast.LENGTH_LONG).show() }
}

/** Il microfono scelto in Aspetto e voce: Groq Whisper (con la chiave) o il riconoscimento del dispositivo. */
object Mics {
    /** Vero se il riconoscimento vocale di sistema è il servizio vuoto di questa app (vedi AssistRecognition.kt). */
    fun ownRecognizerOnly(ctx: Context): Boolean {
        val set = android.provider.Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service").orEmpty()
        val others = ctx.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0).any { it.serviceInfo.name != AssistantRecognitionService::class.java.name }   // anche la copia di prova ha lo stesso servizio vuoto
        return set.startsWith(ctx.packageName + "/") || (set.isEmpty() && !others)
    }

    fun create(ctx: Context, prefs: android.content.SharedPreferences, onStatus: (String) -> Unit, onFinal: (String) -> Unit, onEnd: (String?) -> Unit, autoStop: Boolean = false): Mic {
        val stt = prefs.getString("stt", "device")
        val lang = prefs.getString("groq_lang", AppLanguage.selected(ctx)).orEmpty()
        return when (stt) {
            "groq" -> {
                val key = prefs.secret("groq_key")
                if (key.isBlank()) UnavailableMic(ctx, R.string.ui_9f8232d0cb, onEnd)
                else GroqStt(ctx, key, prefs.getString("groq_model", "whisper-large-v3-turbo").orEmpty(), lang, onStatus, onFinal, onEnd, autoStop)
            }
            // Qualunque /audio/transcriptions compatibile OpenAI (OpenAI, Whisper sul PC di casa...).
            "openai" -> {
                val base = prefs.getString("stt_url", "").orEmpty().trim()
                if (!safeBase(base)) UnavailableMic(ctx, R.string.ui_3f95a6ee8b, onEnd)
                else GroqStt(ctx, prefs.secret("stt_key"), prefs.getString("stt_model", "whisper-1").orEmpty(), lang, onStatus, onFinal, onEnd, autoStop, base = base, label = "Whisper")
            }
            // Sperimentale: il file va a Hermes, che lo trascrive (vedi HermesDash.transcribe).
            "hermes" -> {
                if (prefs.getString("provider", "") != "dashboard") UnavailableMic(ctx, R.string.ui_e376d3513b, onEnd)
                else { val dash = HermesRuntime.get(ctx).dash
                    GroqStt(ctx, "", "", lang, onStatus, onFinal, onEnd, autoStop, label = "Hermes", transcriber = { f -> dash.transcribe(f.readBytes(), lang) }) }
            }
            "offline" -> OfflineStt(ctx, onStatus, onFinal, onEnd, autoStop)
            // Il riconoscimento "del dispositivo" che rimanda a R1 Agents stesso (assistente digitale, nessun motore vero).
            else -> if (ownRecognizerOnly(ctx)) UnavailableMic(ctx, R.string.ui_369029220d, onEnd) else Voice(ctx, onStatus, onFinal, onEnd)
        }
    }

}
