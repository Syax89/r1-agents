package dev.r1agents

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import dev.r1os.host.ui.*

/** Conversazione a pressione: nessun ascolto automatico e nessuna trascrizione dell'utente a schermo. */
internal class VoiceConversation(
    private val act: Activity,
    private val agent: Agent,
    private val dashboard: Boolean,
    private val resolveChat: () -> Long,
    private val microphone: ((String) -> Unit, (String) -> Unit, (String?) -> Unit) -> Mic,
    private val onChatVisible: () -> Unit = {},
    private val close: () -> Unit
) : FrameLayout(act) {
    private val db = Db.get(act)
    private val prefs = act.getSharedPreferences("settings", 0)
    private val runtime = HermesRuntime.get(act)
    private val buddy = CharacterView(act, act.dpi(300f)).apply { tag = "scene-buddy" }
    private val motion = BuddyMotionLayer(act, buddy, agent)
    private val status = act.text(14f, 600, Theme.MUTED)
    private val reply = act.text(18f, 500).keepCase()
    private val bubble = R1ScrollView(act)
    private val scene = LinearLayout(act)
    private val stage = FrameLayout(act)
    private val avatarFit = FrameLayout(act)
    private val bubbleSpace = FrameLayout(act)
    private val hold = act.text(16f, 600)
    private val stop = act.text(13f, 600, Theme.BAD)
    private var mic: Mic? = null
    private var generation = 0
    private var alive = true
    private var recording = false
    private var transcribing = false
    private var busy = false
    private val muted = !prefs.getBoolean("voice_tts", true)
    private var right = false
    private var phase = ""
    private var chatId: Long? = null
    val visibleChatId: Long? get() = chatId
    private var unsubscribe: (() -> Unit)? = null
    private var localEngine: Engine? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var pendingSpeech: String? = null
    private var speechId = 0
    private val speechQueue = java.util.ArrayDeque<String>()
    private var startY = 0f
    private fun tr(id: Int) = act.getString(id)

    init {
        setBackgroundColor(Theme.BG); isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dpi(16f), act.dpi(8f), act.dpi(16f), act.dpi(8f)) }
        addView(column, LayoutParams(-1, -1))
        val header = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = act.text(22f, 600).apply { text = "×"; isFocusable = true; defaultFocusHighlightEnabled = false; gravity = Gravity.CENTER; contentDescription = tr(R.string.voice_close); setOnClickListener { close() } }
        header.addView(back, LinearLayout.LayoutParams(act.dpi(48f), act.dpi(48f)))
        header.addView(act.text(17f, 650).keepCase().apply { text = agent.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(header)
        scene.orientation = LinearLayout.VERTICAL
        avatarFit.addView(motion, LayoutParams(-1, -1))
        avatarFit.addView(buddy, LayoutParams(act.dpi(300f), act.dpi(300f)))
        stage.addView(avatarFit, LayoutParams(act.dpi(300f), act.dpi(300f), Gravity.CENTER))
        stage.clipChildren = false; stage.clipToPadding = false
        stage.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val scale = minOf(1f, (r - l) / act.dp(300f), (b - t) / act.dp(300f)).coerceAtLeast(.1f)
            avatarFit.scaleX = scale; avatarFit.scaleY = scale
        }
        scene.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        scene.addView(bubbleSpace, LinearLayout.LayoutParams(-1, 0, .65f))
        column.addView(scene, LinearLayout.LayoutParams(-1, 0, 1f))
        status.gravity = Gravity.CENTER; status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        column.addView(status, LinearLayout.LayoutParams(-1, act.dpi(28f)))
        reply.setPadding(act.dpi(18f), act.dpi(20f), act.dpi(18f), act.dpi(14f)); reply.setTextIsSelectable(true)
        bubble.addView(reply); bubble.isFillViewport = false; bubble.visibility = View.INVISIBLE
        bubbleSpace.addView(bubble, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { marginEnd = act.dpi(38f) })
        bubbleSpace.visibility = View.GONE
        stop.apply { text = tr(R.string.voice_stop); isFocusable = true; defaultFocusHighlightEnabled = false; gravity = Gravity.CENTER; visibility = View.GONE; setOnClickListener { stopTurn() } }
        column.addView(stop, LinearLayout.LayoutParams(-1, act.dpi(44f)))
        hold.apply {
            text = tr(R.string.voice_hold); gravity = Gravity.CENTER; background = roundRect(Theme.FOCUS, act.dp(Shapes.PILL)); isClickable = true
            contentDescription = tr(R.string.voice_hold); isFocusable = true; defaultFocusHighlightEnabled = false
            setOnKeyListener { _, key, event ->
                if (key != android.view.KeyEvent.KEYCODE_DPAD_CENTER && key != android.view.KeyEvent.KEYCODE_ENTER) false
                else { if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) startListening() else if (event.action == android.view.KeyEvent.ACTION_UP) release(); true }
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = event.rawY; startListening() }
                    MotionEvent.ACTION_MOVE -> if (recording && startY - event.rawY > act.dp(64f)) cancelRecording()
                    MotionEvent.ACTION_UP -> release()
                    MotionEvent.ACTION_CANCEL -> cancelRecording()
                }; true
            }
        }
        column.addView(hold, LinearLayout.LayoutParams(-1, act.dpi(52f)).apply { topMargin = act.dpi(8f) })
        reflow()
        showPhase("idle", R.string.voice_ready)
    }

    private fun reflow() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        scene.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        stage.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f)
        bubbleSpace.layoutParams = if (landscape) LinearLayout.LayoutParams(0, -1, 1.15f) else LinearLayout.LayoutParams(-1, 0, .65f)
    }
    fun viewportChanged() { cancelRecording(); reflow() }
    fun pauseForDialog() { cancelRecording(); silence() }

    private fun showPhase(state: String, label: Int, activity: String? = null) {
        val visual = BuddyVisualState.of(state, activity.orEmpty())
        status.text = if (visual.targetName != null) UiText.owned(act, activity.orEmpty()) else tr(label)
        if (phase != state + activity) { phase = state + activity; buddy.show(agent, visual.phase, visual.activity); motion.show(visual) }
    }
    fun startListening() {
        if (!alive || busy || transcribing || recording) return
        silence(); generation++; val token = generation
        recording = true
        showPhase("listening", R.string.voice_listening)
        hold.text = tr(R.string.voice_release); hold.setTextColor(Theme.accent)
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        mic = microphone({}, { text ->
            if (alive && token == generation) {
                recording = false; transcribing = false; mic?.cancel(); mic = null
                if (text.isNotBlank()) send(text) else reset()
            }
        }, { error ->
            if (alive && token == generation) {
                recording = false; transcribing = false; mic?.cancel(); mic = null
                reset()
                if (error != null) { showPhase("error", R.string.voice_error); Toast.makeText(act, error, Toast.LENGTH_LONG).show() }
            }
        })
        mic?.onLevel { level -> if (alive && token == generation && recording) motion.level(level) }
        mic?.onPhase { if (alive && token == generation && it == MicPhase.TRANSCRIBING) { recording = false; transcribing = true; showPhase("thinking", R.string.voice_transcribing) } }
        if (mic?.start() != true && alive && token == generation) {
            mic?.cancel(); mic = null; recording = false; transcribing = false; reset()
            Toast.makeText(act, tr(R.string.voice_mic_unavailable), Toast.LENGTH_LONG).show()
        }
    }
    fun release() {
        if (!recording) return
        recording = false; transcribing = true; hold.text = tr(R.string.voice_wait)
        buddy.animate().cancel(); buddy.scaleX = 1f; buddy.scaleY = 1f
        showPhase("thinking", R.string.voice_transcribing); mic?.stop()
    }
    fun cancelRecording() {
        if (!recording && !transcribing) return
        generation++; mic?.cancel(); mic = null; recording = false; transcribing = false; reset()
        showPhase("idle", R.string.voice_cancelled)
    }
    private fun reset() {
        busy = false; hold.isEnabled = true; hold.alpha = 1f; hold.text = tr(R.string.voice_hold); hold.setTextColor(Theme.FG); stop.visibility = View.GONE
        buddy.animate().cancel(); buddy.scaleX = 1f; buddy.scaleY = 1f
        showPhase("idle", R.string.voice_ready)
    }
    private fun send(text: String) {
        val token = ++generation
        busy = true; hold.isEnabled = false; hold.alpha = .45f; hold.text = tr(R.string.voice_wait); stop.visibility = View.VISIBLE
        reply.text = ""; bubble.visibility = View.INVISIBLE; bubbleSpace.visibility = View.GONE; showPhase("thinking", R.string.voice_thinking)
        right = !right
        (bubble.layoutParams as LayoutParams).apply { marginStart = if (right) act.dpi(38f) else 0; marginEnd = if (right) 0 else act.dpi(38f); bubble.layoutParams = this }
        bubble.background = SpeechBalloon(Theme.FOCUS, right, act.dp(20f))
        Io.run {
            try {
                val id = chatId ?: resolveChat()
                act.runOnUiThread {
                    if (!alive || token != generation) return@runOnUiThread
                    chatId = id
                    onChatVisible()
                    if (runtime.active(id) != null) { reset(); showPhase("working", R.string.voice_existing_turn); return@runOnUiThread }
                    if (db.messagesPage(id, 1, null).isEmpty()) db.title(id, text.replace('\n', ' ').take(40))
                    if (dashboard) {
                        // Siamo sul thread principale, fuori dal try di sotto: un rifiuto (Hermes in configurazione) faceva chiudere l'app.
                        val task = try { runtime.start(agent, id, text, false, emptyList()) }
                            catch (e: Exception) { reset(); showPhase("error", R.string.voice_error); Toast.makeText(act, UiText.message(act, e.message), Toast.LENGTH_LONG).show(); return@runOnUiThread }
                        unsubscribe?.invoke()
                        unsubscribe = task.progress.subscribe { snapshot -> act.runOnUiThread { if (alive && token == generation) render(snapshot) } }
                    } else {
                        val progress = TurnProgress()
                        val pending = StringBuilder()
                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                        var flushQueued = false
                        fun flush() { synchronized(pending) { flushQueued = false; if (pending.isNotEmpty()) { progress.append(pending.toString()); pending.setLength(0) } } }
                        unsubscribe = progress.subscribe { snapshot -> act.runOnUiThread { if (alive && token == generation) render(snapshot) } }
                        val engine = Engine(db, Settings(prefs, Settings(prefs).provider.id), NousAuth(prefs), runtime.dash); localEngine = engine
                        Io.run {
                            try { engine.turn(agent, id, text, onActivity = { progress.activity(it) }, onText = { delta -> synchronized(pending) { pending.append(delta); if (!flushQueued) { flushQueued = true; main.postDelayed({ flush() }, 50) } } }); flush(); progress.finish() }
                            catch (e: Exception) { flush(); progress.fail(e.message ?: tr(R.string.voice_error)) }
                        }
                    }
                }
            } catch (e: Exception) { act.runOnUiThread { if (alive && token == generation) { reset(); showPhase("error", R.string.voice_error); Toast.makeText(act, UiText.message(act, e.message), Toast.LENGTH_LONG).show() } } }
        }
    }
    private fun render(s: TurnProgress.Snapshot) {
        if (s.text.isNotBlank() && !android.text.TextUtils.equals(reply.text, s.text)) {
            val first = bubble.visibility != View.VISIBLE
            // Segue lo streaming solo se il lettore non sta scorrendo una parte precedente.
            val follow = !bubble.canScrollVertically(1)
            reply.text = s.text
            if (first) {
                bubbleSpace.visibility = View.VISIBLE; bubble.visibility = View.VISIBLE; bubble.scrollTo(0, 0)
                if (!Motion.reduced(act)) buddy.lookAt(if (right) 22f else -22f, -6f, hold = Motion.SLOW * 2) // guarda il fumetto che arriva
                if (!Motion.reduced(act)) { bubble.alpha = 0f; bubble.translationX = act.dp(if (right) 20f else -20f); bubble.animate().alpha(1f).translationX(0f).setDuration(Motion.BASE).setInterpolator(Theme.POP).start(); buddy.react("bounce") }
            } else if (follow) bubble.post { bubble.fullScroll(View.FOCUS_DOWN) }
        }
        when (s.status) {
            TurnProgress.Status.RUNNING -> if (s.activity.isNotBlank()) showPhase(BuddyVisualState.fromActivity(s.activity).kind, R.string.voice_working, s.activity) else showPhase(if (s.text.isBlank()) "thinking" else "speaking", if (s.text.isBlank()) R.string.voice_thinking else R.string.voice_responding)
            TurnProgress.Status.DONE -> { reset(); if (s.text.isNotBlank() && !muted) speakReply(s.text) }
            TurnProgress.Status.ERROR -> { reset(); showPhase("error", R.string.voice_error); Toast.makeText(act, UiText.message(act, s.error), Toast.LENGTH_LONG).show() }
            TurnProgress.Status.CANCELLED -> { reset(); showPhase("idle", R.string.voice_cancelled) }
        }
    }
    private fun stopTurn() {
        generation++; unsubscribe?.invoke(); unsubscribe = null
        if (dashboard) chatId?.let { runtime.cancel(it) } else localEngine?.cancel()
        silence(); reset(); showPhase("idle", R.string.voice_cancelled)
    }
    private fun silence() { pendingSpeech = null; speechQueue.clear(); speechId++; tts?.stop() }
    private fun speakReply(text: String) {
        if (!SpeechSupport.hasEngine(act)) { showPhase("idle", R.string.voice_audio_missing_engine); return }
        if (SpeechSupport.isMuted(act)) { showPhase("idle", R.string.voice_audio_volume_muted); return }
        pendingSpeech = text
        if (tts == null) {
            ttsInitializing = true
            tts = TextToSpeech(act.applicationContext, { result -> post {
                ttsInitializing = false
                if (!alive) return@post
                if (result == TextToSpeech.SUCCESS) {
                    tts?.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    val supported = tts?.setLanguage(AppLanguage.locale(act)) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    ttsReady = supported >= TextToSpeech.LANG_AVAILABLE
                    if (ttsReady) playSpeech() else { pendingSpeech = null; showPhase("idle", R.string.voice_audio_unavailable) }
                } else { pendingSpeech = null; showPhase("idle", R.string.voice_audio_unavailable) }
            } }, SpeechSupport.enginePackage(act))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = endSpeech(id, false)
                @Deprecated("Deprecated in Java") override fun onError(id: String?) = endSpeech(id, true)
                private fun endSpeech(id: String?, failed: Boolean) { act.runOnUiThread {
                    if (!alive || id != speechId.toString() || recording || busy || transcribing) return@runOnUiThread
                    if (failed) { speechQueue.clear(); showPhase("idle", R.string.voice_audio_unavailable) }
                    else if (speechQueue.isNotEmpty()) playNextSpeech()
                    else showPhase("idle", R.string.voice_ready)
                } }
            })
        } else if (ttsReady) playSpeech()
        else if (!ttsInitializing) { pendingSpeech = null; showPhase("idle", R.string.voice_audio_unavailable) }
    }
    private fun playSpeech() {
        val text = pendingSpeech ?: return
        pendingSpeech = null
        if (!alive || muted || recording || transcribing || busy) return
        // Lettura locale, mai voice.tts di Hermes (riprodurrebbe l'audio sul computer).
        speechQueue.clear()
        speechQueue.addAll(VoiceSpeech.chunks(text, TextToSpeech.getMaxSpeechInputLength()))
        if (speechQueue.isEmpty()) { showPhase("idle", R.string.voice_ready); return }
        playNextSpeech()
    }
    private fun playNextSpeech() {
        val part = speechQueue.pollFirst() ?: return
        if (tts?.speak(part, TextToSpeech.QUEUE_FLUSH, null, (++speechId).toString()) == TextToSpeech.SUCCESS) showPhase("speaking", R.string.voice_speaking)
        else { speechQueue.clear(); showPhase("idle", R.string.voice_audio_unavailable) }
    }
    /** Uscire non interrompe Hermes: il runtime continua e conserva la risposta nella chat. */
    fun dispose() {
        if (!alive) return
        alive = false; generation++; mic?.cancel(); mic = null
        unsubscribe?.invoke(); unsubscribe = null; localEngine?.cancel(); localEngine = null
        silence(); tts?.shutdown(); tts = null; motion.dismiss(); buddy.setActive(false); bubble.animate().cancel(); buddy.animate().cancel()
    }
}

/** Fumetto con una punta orientata verso il buddy, alternata tra i due lati. */
private class SpeechBalloon(color: Int, private val right: Boolean, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val body = RectF()
    private val tail = Path()
    override fun onBoundsChange(b: android.graphics.Rect) {
        val r = radius
        body.set(b.left.toFloat(), b.top + r * .45f, b.right.toFloat(), b.bottom.toFloat())
        val x = if (right) b.left + r * 1.5f else b.right - r * 1.5f
        tail.reset(); tail.moveTo(x - r * .6f, b.top + r * .65f); tail.lineTo(x, b.top.toFloat()); tail.lineTo(x + r * .6f, b.top + r * .65f); tail.close()
    }
    override fun draw(canvas: Canvas) { canvas.drawRoundRect(body, radius, radius, paint); canvas.drawPath(tail, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
