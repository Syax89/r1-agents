package dev.r1agents

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.r1os.host.ui.*
import android.net.Uri
import org.json.JSONArray

/** Contenuto condiviso da un'altra app: testo e allegati già copiati nella cartella privata. */
/** La schermata prima del cambio di aspetto, per la dissolvenza dopo la ricostruzione. */
internal var lookSnapshot: android.graphics.Bitmap? = null

class Share(val text: String, val files: List<Attachment>, val voiceDraft: Boolean = false)

class MainActivity : Activity() {
    internal lateinit var db: Db
    internal lateinit var settings: Settings
    internal val localTurns = java.util.concurrent.ConcurrentHashMap.newKeySet<Engine>()
    internal fun localEngine() = Engine(db, Settings(prefs, settings.provider.id), nous, dash).also { localTurns.add(it) }
    internal fun cancelLocalTurns() { localTurns.forEach { it.cancel() }; localTurns.clear() }
    internal lateinit var nous: NousAuth
    internal lateinit var dash: HermesDash
    internal lateinit var hermesRuntime: HermesRuntime
    internal lateinit var root: FrameLayout
    internal class Screen(val view: View, val keys: ((KeyEvent) -> Boolean)? = null, val onLeave: (() -> Unit)? = null, val onShow: (() -> Unit)? = null, val isChat: Boolean = false,
                         val scope: Scope = Scope(), val onResume: (() -> Unit)? = null, val chatKey: Long? = null, val buddyContext: BuddySceneContext = BuddySceneContext.READ, val isVoice: Boolean = false, val onSuspend: (() -> Unit)? = null, val leaveGuard: (() -> Boolean)? = null,
                         /** La home: riceve anche il rilascio del tasto centrale (click = esegui, tenuto = torna all'agente) e gestisce Indietro nel carosello. */
                         val isHome: Boolean = false) {
        /** Da dove si e' aperta (il rettangolo dell'elemento toccato, nel root): chiudendola torna li'. */
        var pendingEntry: android.view.ViewTreeObserver.OnPreDrawListener? = null
        var posedBuddy: CharacterView? = null
        var sceneCue: Runnable? = null
        var origin: android.graphics.RectF? = null
        var heroOrigin: Hero? = null
    }
    internal val neutralBuddy by lazy { Agent(-99, "buddy", "", "", "orb", "orange") }
    internal val stack = ArrayList<Screen>()
    internal var homeIndex = 0
    internal var onVoice: ((String) -> Unit)? = null
    internal var cancelActiveMic: (() -> Unit)? = null
    internal var lastProfileSync = 0L
    internal var pttHandler: ((KeyEvent) -> Boolean)? = null
    internal lateinit var prefs: android.content.SharedPreferences
    internal var tts: android.speech.tts.TextToSpeech? = null
    internal var ttsReady = false
    internal var deferredPinIntent: Intent? = null
    internal var pinGate: View? = null
    internal var pinUnlocked = false
    internal fun pinEnabled() = prefs.getString("app_pin_hash", "").orEmpty().isNotEmpty()
    // Il PIN protegge le chat (alcune o tutte), non l'app: la home e le altre pagine restano sempre libere.
    internal fun lockedChats(): Set<String> = prefs.getStringSet("locked_chats", emptySet()) ?: emptySet()
    internal fun chatLocked(id: Long?) = id != null && pinEnabled() && (prefs.getBoolean("lock_all_chats", false) || id.toString() in lockedChats())
    internal fun gateWanted() = chatLocked(stack.lastOrNull()?.chatKey)
    internal fun setChatLocked(id: Long, on: Boolean) { prefs.edit().putStringSet("locked_chats", lockedChats().toMutableSet().also { if (on) it.add(id.toString()) else it.remove(id.toString()) }).apply() }
    /** Esce da una chat bloccata senza il PIN: toglie il gate e torna indietro. */
    internal fun leaveLockedChat() {
        pinGate?.let { root.removeView(it) }; pinGate = null; pinUnlocked = false; deferredPinIntent = null; updateSecure()
        cancelModalBuddyFlight(); clearCarriedBuddy(); hideKeyboard()
        if (stack.size > 1) pop()
    }
    @JvmName("idleMs") internal fun idleMs(): Long = prefs.getString("idle_after", "1").let { if (it == "never") 0L else (it?.toLongOrNull() ?: 1L) * 60_000L }
    internal fun pinPrompt(title: String, confirm: (String) -> Unit) {
        val id = "pin-setup"
        overlay(id, title, { closeOverlay(id) }, context = BuddySceneContext.SECURITY) { col, close ->
            val entry = field(UiText.text(this@MainActivity, R.string.ui_89eb8f4b8b), "").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            }
            col.addView(entry)
            col.addView(button(UiText.text(this@MainActivity, R.string.ui_3cd50c52c0), Theme.accent) { val value = entry.text.toString(); if (PinHash.valid(value)) { entry.setText(""); close(); confirm(value) } else entry.error = UiText.text(this@MainActivity, R.string.ui_33ca8b8dd4) })
            col.addView(button(UiText.text(this@MainActivity, R.string.ui_9cb9b85e99), Theme.MUTED, primary = false) { entry.setText(""); close() })
        }
    }
    internal fun pinSetup() {
        pinPrompt(UiText.text(this@MainActivity, R.string.ui_857ef10d0f)) { first ->
            pinPrompt(UiText.text(this@MainActivity, R.string.ui_e829f8e940)) { second ->
                if (first != second) snack(UiText.text(this@MainActivity, R.string.ui_8bbb3165d5), long = true)
                else Io.run { val hash = PinHash.create(first); runOnUiThread {
                    prefs.edit().putString("app_pin_hash", hash).remove("pin_failures").remove("pin_wait_until").apply()
                    pinUnlocked = true; updateSecure()
                    snack(UiText.text(this@MainActivity, R.string.ui_875796699c)); stack.lastOrNull()?.onShow?.invoke()
                } }
            }
        }
    }
    @JvmName("showPinGate") internal fun showPinGate() = showPinGate(false)
    /** Campi segreti a schermo (chiavi, password): finché ce n'è uno, niente screenshot. Prima un campo staccato toglieva la protezione anche con un altro ancora visibile. */
    internal var secretFields = 0
    /** Unico punto che decide FLAG_SECURE: gate del PIN, chat bloccata in cima o campo segreto a schermo. */
    internal fun updateSecure() {
        val on = pinGate != null || chatLocked(stack.lastOrNull()?.chatKey) || (secretFields > 0 && !qaVisible)
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    /** Solo nel pacchetto QA e su richiesta (`--ez qa_visible true`): permette gli screenshot delle pagine con campi segreti per le verifiche visive. Mai sul pacchetto dell'utente. */
    internal val qaVisible get() = packageName.endsWith(".qa") && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent?.getBooleanExtra("qa_visible", false) == true
    @JvmName("showPinGate") internal fun showPinGate(animate: Boolean) {
        if (!pinEnabled() || pinUnlocked || pinGate != null) return
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) // subito, prima che il gate sia costruito
        if (animate) carryMovingBuddy()
        val under = currentBuddyPage()
        val source = carriedBuddy ?: under?.let { sceneAvatar(it, companionAgent?.id) }
        val gate = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(24f), dpi(12f), dpi(24f), dpi(12f)); setBackgroundColor(Theme.BG); isClickable = true }
        val gateBuddy = companion(56f, neutralBuddy)!!.also { buddy -> source?.let { buddy.copyAppearanceFrom(it) }; buddy.setScenePose("waiting") }
        gate.addView(gateBuddy, LinearLayout.LayoutParams(dpi(56f), dpi(56f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val pinBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        gate.addView(R1ScrollView(this).apply { isFillViewport = true; addView(pinBody, -1, -2) }, LinearLayout.LayoutParams(-1, 0, 1f))
        pinBody.addView(text(28f, 800).apply { text = UiText.text(this@MainActivity, R.string.ui_0510dd2670) })
        val entry = field("PIN", "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; filters = arrayOf(android.text.InputFilter.LengthFilter(8)) }
        pinBody.addView(entry, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16f) })
        val hint = text(14f, 500, Theme.MUTED).keepCase().apply { text = UiText.text(this@MainActivity, R.string.ui_b755b3224f) }
        pinBody.addView(hint)
        var checking = false
        val unlock = button(UiText.text(this@MainActivity, R.string.ui_e01eaf5dd1), Theme.accent) {
            if (!checking) {
                val remaining = (prefs.getLong("pin_wait_until", 0) - System.currentTimeMillis()).coerceAtMost(30000)
                if (remaining > 0) hint.text = UiText.text(this@MainActivity, R.string.ui_88e8187f09, (remaining + 999) / 1000)
                else {
                    val pin = entry.text.toString(); entry.setText(""); checking = true; hint.text = UiText.text(this@MainActivity, R.string.ui_7384eb5911)
                    Io.run { val ok = PinHash.verify(pin, prefs.getString("app_pin_hash", "").orEmpty()); runOnUiThread {
                        checking = false
                        if (ok) {
                            pinUnlocked = true; prefs.edit().remove("pin_failures").remove("pin_wait_until").apply()
                            hideKeyboard()
                            val deferred = deferredPinIntent; deferredPinIntent = null
                            var finished = false
                            val finishUnlock = {
                                if (!finished) {
                                    finished = true; gate.animate().cancel(); root.removeView(gate); pinGate = null; updateSecure(); clearCarriedBuddy(); restoreBuddies()
                                    if (deferred != null) { applyIntent(deferred); handle(deferred) }
                                }
                            }
                            val returning = modalBuddyFlight?.takeIf { it.isActive && modalSurface === gate && currentBuddyPage() === under }
                            val back = currentBuddyPage(); val anchor = back?.let { sceneAvatar(it, companionAgent?.id) }
                            if (visible && back != null && anchor != null && anchor.width > 0) {
                                if (returning != null) { modalCarryCleanup = finishUnlock; returning.animateTo(0f) }
                                else modalFlight(back, gate, anchor, gateBuddy, 1f, onCarry = finishUnlock, done = finishUnlock).animateTo(0f)
                                gate.animate().alpha(0f).setDuration(Motion.SLOW).setInterpolator(Theme.EASE_OUT).start()
                            } else finishUnlock()
                        }
                        else { val fails = prefs.getInt("pin_failures", 0) + 1; gateBuddy.react(if (fails >= 5) "offended" else "annoyed"); prefs.edit().putInt("pin_failures", fails).putLong("pin_wait_until", if (fails >= 5) System.currentTimeMillis() + 30000 else 0).apply(); hint.text = if (fails >= 5) UiText.text(this@MainActivity, R.string.ui_ff1cd8227a) else UiText.text(this@MainActivity, R.string.ui_1265e93b8f) }
                    } }
                }
            }
        }
        pinBody.addView(unlock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        if (gateWanted()) pinBody.addView(button(UiText.text(this@MainActivity, R.string.ui_17e2587ab7), Theme.MUTED, primary = false) { leaveLockedChat() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        pinGate = gate; root.addView(gate, ViewGroup.LayoutParams(-1, -1))
        if (animate && under != null && source != null) gate.post {
            if (pinGate === gate && gate.isAttachedToWindow && source.width > 0) modalFlight(under, gate, source, gateBuddy, 0f) { }.animateTo(1f)
        }
    }
    internal fun securityScreen(): Screen = listPage(UiText.text(this@MainActivity, R.string.ui_9729d2de44), context = BuddySceneContext.SECURITY) { col ->
        col.addView(intro(UiText.text(this@MainActivity, R.string.ui_d683ad2559)))
        col.post { // occhi attenti: scansione sinistra, destra, poi dritto
            cue(null, -24f, 0f, force = true)
            col.postDelayed({ cue(null, 24f, 0f, force = true) }, Motion.BASE * 2)
        }
        // Il blocco di Android è una scelta del sistema: l'app non lo cambia, porta solo alla pagina giusta ("nessuno" lascia il posto al PIN dell'app).
        col.addView(row(UiText.text(this@MainActivity, R.string.ui_7b4f2d1fe7), Theme.accent, sub = UiText.text(this@MainActivity, R.string.ui_544aacf142), icon = "lock") {
            try { startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { snack(UiText.text(this@MainActivity, R.string.ui_7d0f161c22)) }
        })
        col.addView(row(UiText.text(this@MainActivity, R.string.ui_793ade4b26), Theme.accent, sub = UiText.text(this@MainActivity, R.string.ui_c5bcea219e), icon = "sliders") { push(actionLogScreen()) })
        if (hermesMode()) col.addView(row(UiText.text(this@MainActivity, R.string.ui_2718314a3d), Theme.accent, sub = UiText.text(this@MainActivity, R.string.ui_768e8b058b), check = prefs.getBoolean(CronWatch.PREF, false), icon = "sliders") {
            val on = !prefs.getBoolean(CronWatch.PREF, false); prefs.edit().putBoolean(CronWatch.PREF, on).remove("pending_cron_seen").apply(); CronWatch.arm(this, on); stack.last().onShow?.invoke()
        })
        col.addView(row(UiText.text(this@MainActivity, R.string.ui_7dd4ab62e7), Theme.accent, sub = UiText.text(this@MainActivity, R.string.ui_3b87e2a997), check = prefs.getBoolean(PhoneContext.PREF, false), icon = "sliders") {
            prefs.edit().putBoolean(PhoneContext.PREF, !prefs.getBoolean(PhoneContext.PREF, false)).apply(); applyPhoneContext(); stack.last().onShow?.invoke()
        })
        if (!pinEnabled()) col.addView(row(UiText.text(this@MainActivity, R.string.ui_e067157f40), Theme.accent, icon = "lock") { pinSetup() })
        else {
            col.addView(row(UiText.text(this@MainActivity, R.string.ui_8c9d3158f9), Theme.accent, icon = "sliders") { pinPrompt(UiText.text(this@MainActivity, R.string.ui_c64ec43f51)) { pin -> Io.run { val ok = PinHash.verify(pin, prefs.getString("app_pin_hash", "").orEmpty()); runOnUiThread { if (ok) pinSetup() else snack(UiText.text(this@MainActivity, R.string.ui_1265e93b8f)) } } } })
            col.addView(row(UiText.text(this@MainActivity, R.string.ui_d8ebc0990d), Theme.BAD, icon = "close") { pinPrompt(UiText.text(this@MainActivity, R.string.ui_c64ec43f51)) { pin -> Io.run { val ok = PinHash.verify(pin, prefs.getString("app_pin_hash", "").orEmpty()); runOnUiThread { if (ok) { prefs.edit().remove("app_pin_hash").remove("pin_failures").remove("pin_wait_until").apply(); updateSecure(); stack.lastOrNull()?.onShow?.invoke() } else snack(UiText.text(this@MainActivity, R.string.ui_1265e93b8f)) } } } })
            col.addView(row(UiText.text(this@MainActivity, R.string.ui_7789e4318d), Theme.accent, check = prefs.getBoolean("lock_all_chats", false), icon = "lock") { prefs.edit().putBoolean("lock_all_chats", !prefs.getBoolean("lock_all_chats", false)).apply(); stack.last().onShow?.invoke() })
        }
    }

    /** Il testo segue la dimensione scelta nelle impostazioni, ma al massimo 1,3×: le schermate sono pensate per 480×640. */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // Solo le proprieta' personalizzate: copiare l'intera Configuration congelava orientazione e misure.
        val c = android.content.res.Configuration().apply {
            fontScale = base.resources.configuration.fontScale.coerceAtMost(1.3f)
            setLocale(AppLanguage.locale(base)); setLayoutDirection(AppLanguage.locale(base))
        }
        applyOverrideConfiguration(c)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Se il sistema chiude l'app mentre la fotocamera è aperta, al ritorno si sa ancora quale file c'era (scatta e chiedi, o da cancellare).
        b?.getString("photo_file")?.let { photoFile = java.io.File(it) }
        Theme.init(this); Md.copyHandler = { t -> runOnUiThread { copyText(t) } }
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        Vault.migrate(prefs) // cifra nel Keystore i segreti salvati in chiaro dalle versioni precedenti
        r1WheelLandscapeFlip = prefs.getBoolean("wheel_flip_land", true)
        DeviceActions.mode = prefs.getString("dev_actions", "ask") ?: "ask"; applyPhoneContext()
        DeviceActions.hasFlash = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)
        Theme.setLook(prefs.getString("look_mode", "dark") == "light", prefs.getString("look_accent", "orange") ?: "orange", prefs.getString("look_font", "rubik") ?: "rubik")
        db = Db.get(this)
        settings = Settings(getSharedPreferences("settings", MODE_PRIVATE))
        nous = NousAuth(getSharedPreferences("settings", MODE_PRIVATE))
        hermesRuntime = HermesRuntime.get(this)
        dash = hermesRuntime.dash
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        setContentView(root)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        if (Theme.light) window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (!pinEnabled()) applyIntent(intent)
        if (db.agents().none { it.profile.isEmpty() }) db.save(Agent(0, "kiwi", "Sei kiwi, un assistente amichevole e conciso. Rispondi nella lingua dell'utente.", "", "blob", "green"))
        push(root())
        if (prefs.getString("reopen", null) == "look") { prefs.edit().remove("reopen").apply(); if (stack.size == 1 && prefs.getString("start", "home") == "glance") push(home()); push(settingsScreen()); push(lookScreen()) }
        Sched.channels(this); Sched.all(this, db)
        handle(intent)
        // Cambio di aspetto: la vecchia schermata (foto) sfuma sopra la nuova, senza il lampo della ricostruzione.
        lookSnapshot?.let { shot ->
            lookSnapshot = null
            val veil = android.widget.ImageView(this).apply { setImageBitmap(shot); isClickable = true; scaleType = android.widget.ImageView.ScaleType.FIT_XY }
            val top = window.decorView as ViewGroup // sopra a tutto, anche al buddy in volo durante la ricostruzione
            top.addView(veil, FrameLayout.LayoutParams(-1, -1))
            veil.animate().alpha(0f).setStartDelay(Motion.FAST).setDuration(Motion.SLOW).setInterpolator(Theme.EASE_OUT).withEndAction { top.removeView(veil); shot.recycle() }.start()
        }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); if (i.hasCategory(Intent.CATEGORY_HOME) && isDefaultLauncher()) goHome(); if (gateWanted() && !pinUnlocked) { deferredPinIntent = Intent(i); showPinGate() } else { applyIntent(i); handle(i) }; pinGate?.bringToFront() }

    /** Aperture dalle notifiche: suoneria (sveglia/timer) o risposta di una routine. */
    internal fun handle(i: Intent?) {
        i ?: return
        if (gateWanted() && !pinUnlocked) { deferredPinIntent = Intent(i); showPinGate(); return }
        i.data?.takeIf { it.scheme == "r1agents" && it.host == "connect" }?.let { uri ->
            i.data = null
            runCatching { Pairing.parse(uri.toString()) }.onSuccess { confirmPairing(it) }.onFailure { snack(UiText.message(this@MainActivity, it.message) ?: UiText.text(this@MainActivity, R.string.ui_4bd3645750), long = true) }
        }
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Suoneria e bozza vocale solo se l'intent viene da questa app (token privato): l'activity e' esportata.
        if (i.hasExtra("ring_kind") && !InternalIntent.ok(i, this)) i.removeExtra("ring_kind")
        if (i.hasExtra("say") && !InternalIntent.ok(i, this)) i.removeExtra("say")
        // Le schermate di prova da adb (debug_*) solo nelle build debuggabili: in release un'altra app non deve aprire pagine o finte richieste.
        if (!debuggable) { i.removeExtra("debug_req"); i.removeExtra("debug_screen"); i.removeExtra("debug_md") }
        if (i.hasExtra("ring_kind")) {
            // Solo per la suoneria: sopra il blocco e schermo acceso. Il resto dell'app non tocca il blocco né lo schermo.
            setShowWhenLocked(true); setTurnScreenOn(true)
            push(ringScreen(i.getStringExtra("ring_title").orEmpty(), i.getStringExtra("ring_label").orEmpty(), i.getStringExtra("ring_kind").orEmpty()))
            i.removeExtra("ring_kind")
        }
        i.getStringExtra("debug_req")?.let { kind -> // prove da adb dell'aspetto di permessi e domande: am start --es debug_req approval|clarify
            i.removeExtra("debug_req")
            showRequest(if (kind == "clarify") HermesDash.SrvRequest("debug-2", "clarify", org.json.JSONObject().put("questions", org.json.JSONArray()
                .put(org.json.JSONObject().put("qid", "q1").put("question", UiText.text(this@MainActivity, R.string.ui_f9558c4eaa)).put("choices", org.json.JSONArray(listOf(UiText.text(this@MainActivity, R.string.ui_3a9126a4ea), UiText.text(this@MainActivity, R.string.ui_bb96ed5cc3), UiText.text(this@MainActivity, R.string.ui_ef204ead21)))).put("multi_select", false))
                .put(org.json.JSONObject().put("qid", "q2").put("question", UiText.text(this@MainActivity, R.string.ui_7fd8d27a3b)).put("choices", org.json.JSONArray()).put("multi_select", false))))
            else HermesDash.SrvRequest("debug-1", "approval", org.json.JSONObject().put("description", UiText.text(this@MainActivity, R.string.ui_9beb5b4584)).put("command", "rm -rf /tmp/prova && echo fatto")
                .put("choices", org.json.JSONArray(listOf("once", "session", "always", "deny")))))
        }
        if (i.action == Intent.ACTION_PROCESS_TEXT) { i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { i.putExtra(Intent.EXTRA_TEXT, it) }; i.action = Intent.ACTION_SEND } // «chiedi a R1 Agents» dal menu di selezione testo di qualsiasi app
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) handleShare(i)
        i.getStringExtra("debug_screen")?.let { which -> // aprire una schermata per nome (prove visive da adb)
            i.removeExtra("debug_screen")
            val ag = visibleAgents().firstOrNull()
            when (which) {
                "apps" -> push(appsScreen()); "alarms" -> push(alarmsScreen()); "alarm_new" -> push(alarmEditor(null)); "timers" -> push(timersScreen())
                "routines" -> push(routinesScreen()); "routine_new" -> if (ag != null) push(routineEditor(null))
                "translator" -> push(translator()); "notes" -> push(notesScreen()); "look" -> push(lookScreen())
                "buddy_review" -> if (ag != null) push(listPage(UiText.text(this@MainActivity, R.string.ui_403a7969bc)) { col ->
                    val draft = field(UiText.text(this@MainActivity, R.string.ui_3265eeab7c), UiText.text(this@MainActivity, R.string.ui_edec2bab85)).apply { setSingleLine(false); maxLines = 3 }
                    col.addView(draft)
                    col.addView(BuddyStage(this, ag).apply { review { draft.setText("promemoria"); dismiss() } })
                })
                "buddy_connection" -> if (ag != null) push(listPage(UiText.text(this@MainActivity, R.string.ui_82f7240a9a)) { col ->
                    listOf("reconnect" to UiText.text(this@MainActivity, R.string.ui_7c1cae1c78), "offline" to UiText.text(this@MainActivity, R.string.ui_4814f6f7f6), "connected" to UiText.text(this@MainActivity, R.string.ui_f24ee25561), "handoff" to UiText.message(this@MainActivity, "chiede a default").orEmpty()).forEach { (kind, label) -> col.addView(BuddyStage(this, ag).apply { show(kind, label) }) }
                })
                "buddy_work" -> if (ag != null) push(listPage(UiText.text(this@MainActivity, R.string.ui_82cadbc6bc)) { col ->
                    listOf("search" to UiText.text(this@MainActivity, R.string.ui_d8acdda628), "files" to UiText.text(this@MainActivity, R.string.ui_13ff277389), "shell" to UiText.text(this@MainActivity, R.string.ui_42f7d1f613), "memory" to UiText.text(this@MainActivity, R.string.ui_da7baa1280), "waiting" to UiText.text(this@MainActivity, R.string.ui_a97b8607e0)).forEach { (kind, label) -> col.addView(BuddyStage(this, ag).apply { show(kind, label) }) }
                })
                "buddy" -> if (ag != null) push(listPage(UiText.text(this@MainActivity, R.string.ui_b7424e2d4b)) { col ->
                    listOf("listening" to UiText.text(this@MainActivity, R.string.ui_84e49980f7), "transcribing" to UiText.text(this@MainActivity, R.string.ui_79b0b7c1f7), "photo" to UiText.text(this@MainActivity, R.string.ui_2cda854d46), "upload" to UiText.text(this@MainActivity, R.string.ui_771c3a2e1a), "send" to UiText.text(this@MainActivity, R.string.ui_bef0afceff), "cancel" to UiText.text(this@MainActivity, R.string.ui_4435ce3621)).forEach { (kind, label) ->
                        col.addView(BuddyStage(this, ag).apply { show(kind, label); if (kind == "listening") level(0.6f) })
                    }
                })
                "loading_preview" -> if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) push(loadingScreen(ag ?: neutralBuddy, "QA"))
                "login_preview" -> if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) push(nousLogin(false))
                "ring_preview" -> if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) push(ringScreen("timer", "QA", Sched.KIND_TIMER))
                "security" -> push(securityScreen()); "actionlog" -> push(actionLogScreen()); "glance" -> push(glance())
                "settings" -> push(settingsScreen()); "tools" -> push(tools()); "agent_new" -> push(editor(null))
                "space" -> if (ag != null) push(space(ag)); "manage" -> if (ag != null) push(manageChats(ag)); "memory" -> if (ag != null) push(memoryScreen(ag))
                "card" -> if (ag != null) push(listPage(UiText.text(this@MainActivity, R.string.ui_3b225a819e)) { col -> col.addView(cardView(ag, org.json.JSONObject().put("title", UiText.text(this@MainActivity, R.string.ui_0ea049a68d)).put("body", UiText.text(this@MainActivity, R.string.ui_991379a059)))) }); "edit" -> if (ag != null) push(editor(ag)); "dash" -> push(providerScreen()); "jobs" -> push(hermesJobsScreen()); "skills" -> if (ag != null) push(skillsScreen(ag)); "switch" -> push(listPage("interruttori") { col -> col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(text(20f, 700).apply { text = "acceso" }, LinearLayout.LayoutParams(0, -2, 1f)); addView(SwitchView(this@MainActivity, true, Theme.colorOf("green")) {}) }); col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(text(20f, 700).apply { text = "spento" }, LinearLayout.LayoutParams(0, -2, 1f)); addView(SwitchView(this@MainActivity, false, Theme.colorOf("green")) {}) }) }); "md" -> push(listPage("md") { col -> col.addView(text(16f, 500).keepCase().apply { text = Md.render("# titolo\n1. uno\n2. due\n> citazione\n[link](https://example.org) e **grassetto**\n\n| nome | valore |\n|---|---|\n| alfa | 1 |\n| beta lungo | 22 |\n\n```\nprint(1)\n```", Theme.withAlpha(Theme.FG, 0.14f)); movementMethod = android.text.method.LinkMovementMethod.getInstance() }) }); "about" -> push(aboutScreen()); "menu" -> menuSheet(ag); "shake" -> onShake()
            }
        }
        i.getStringExtra("debug_do")?.let { spec -> // prova di una direttiva dell'agente da adb (solo build debuggabili): --es debug_do "apri=Calcolatrice"
            i.removeExtra("debug_do")
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) spec.split("=", limit = 2).takeIf { it.size == 2 }?.let { snack(if (runDeviceAction(DeviceActions.Action(it[0], it[1]))) "ok" else "no") }
        }
        i.getStringExtra("debug_entrance")?.let { id -> // prova di una comparsa/sparizione del buddy per nome (solo debug)
            i.removeExtra("debug_entrance")
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) root.postDelayed({
                root.findViewWithTag<View>("scene-companion-row")?.let { row -> BuddyEntrances.find(id)?.let { BuddyEntrances.play(row, it) } }
            }, 500L)
        }
        if (i.hasExtra("debug_md")) { // anteprima del markdown (per prove da adb: am start --ez debug_md true)
            i.removeExtra("debug_md")
            push(listPage(UiText.text(this@MainActivity, R.string.ui_3025b59543)) { col ->
                val sample = UiText.text(this@MainActivity, R.string.ui_226902e015)
                col.addView(text(18f, 500).keepCase().apply { text = Md.render(sample, Theme.withAlpha(Theme.FG, 0.14f)); setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(11f)); background = roundRect(Theme.CARD, dp(20f)) })
            })
        }
        i.getStringExtra("say")?.let { say ->
            val ag = db.agents().firstOrNull { it.id == i.getLongExtra("say_agent", 0) }
            i.removeExtra("say")
            if (ag != null) openLatest(ag, share = Share(say, emptyList(), voiceDraft = true))
        }
        val chatId = i.getLongExtra("open_chat", 0)
        if (chatId > 0) {
            db.agents().firstOrNull { it.id == i.getLongExtra("open_agent", 0) }?.let { push(chat(it, chatId)) }
            i.removeExtra("open_chat")
        }
    }

    /** Configurazione da adb (utile sull'R1, senza tastiera): `am start -n dev.r1agents/.MainActivity --es api_key ... [--es provider hermes] [--es model ...]`. */
    internal fun applyIntent(i: Intent?) {
        // Extra di provisioning solo nelle build debuggabili: un’altra app non deve deviare le chiavi API verso un proprio endpoint.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        i ?: return
        i.getStringExtra("nous_client_id")?.let { nous.clientId = it }
        // Solo prove visive su build debuggabili: `--ez qa_portrait true` blocca la SOLA activity in verticale (non cambia le impostazioni del telefono).
        if (i.getBooleanExtra("qa_portrait", false)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (i.getBooleanExtra("qa_landscape", false)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // Solo prove visive su build debuggabili (mai sul pacchetto dell'utente): `--es look_mode light|dark` cambia il tema di questa installazione.
        i.getStringExtra("look_mode")?.takeIf { it == "light" || it == "dark" && prefs.getString("look_mode", "dark") != it || it == "light" }?.let { mode ->
            if (prefs.getString("look_mode", "dark") != mode) { prefs.edit().putString("look_mode", mode).apply(); i.removeExtra("look_mode"); window.decorView.post { recreate() } }
        }
        i.getStringExtra("provider")?.let { settings.provider = Providers.get(it) }
        val pr = settings.provider
        if (i.hasExtra("api_key") || i.hasExtra("model") || i.hasExtra("base_url"))
            settings.set(pr, i.getStringExtra("api_key"), i.getStringExtra("base_url"), i.getStringExtra("model"))
    }

    // ---- sfondi (look.rs di r1os: plain, tint, gradient, mesh, photo) ---------------------------

    internal val palettes = linkedMapOf(
        "sunset" to listOf("#ff8a3d", "#f06aa8", "#6b4cff"), "lagoon" to listOf("#2fc4a8", "#3cc8e0", "#4e6bff"), "aurora" to listOf("#3ddc84", "#8b8cf8", "#1d4ed8"),
        "peach" to listOf("#ffb38a", "#ff6f91", "#ffc75f"), "dusk" to listOf("#4ea1ff", "#b07cf6", "#f06aa8"), "forest" to listOf("#a3d65c", "#2f8f5b", "#f5b83d"),
        "berry" to listOf("#b07cf6", "#f2555a", "#ff8a3d"), "ink" to listOf("#3a4a6b", "#7a5c99", "#1f2a44"))
    internal var bgPick = "glance"

    internal fun softPalette(name: String?): List<Int> =
        (palettes[name] ?: palettes.getValue("sunset")).map { Theme.mix(Theme.BG, Color.parseColor(it), if (Theme.light) 0.4f else 0.55f) }

    internal fun bgDrawable(screen: String): android.graphics.drawable.Drawable {
        val cols = softPalette(prefs.getString("bgp_$screen", "sunset"))
        return when (prefs.getString("bg_$screen", "plain")) {
            "tint" -> android.graphics.drawable.ColorDrawable(Theme.mix(Theme.BG, Theme.accent, 0.16f))
            "gradient" -> android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, cols.toIntArray())
            "mesh" -> MeshDrawable(Theme.BG, cols)
            "photo" -> java.io.File(filesDir, "bg_$screen.jpg").takeIf { it.exists() }?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.let { PhotoDrawable(it, prefs.getFloat("bgd_$screen", 0.4f)) } }
                ?: android.graphics.drawable.ColorDrawable(Theme.BG)
            else -> android.graphics.drawable.ColorDrawable(Theme.BG)
        }
    }

    /** Foto scelta: ridotta a 720 px sul lato lungo e salvata in JPEG (qualità 80), per non pesare sul dispositivo. */
    internal fun savePhoto(uri: android.net.Uri, screen: String) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
        val k = 720f / maxOf(bmp.width, bmp.height)
        val out = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        java.io.File(filesDir, "bg_$screen.jpg").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        prefs.edit().putString("bg_$screen", "photo").putString("reopen", "look").apply(); recreate()
    }

    // ---- finestre sopra tutto: richieste di Hermes (approvazioni, chiarimenti), menu comandi, testo libero ---------

    internal val overlays = LinkedHashMap<String, View>()
    internal val overlayBack = HashMap<String, () -> Unit>()

    internal val sheetClosers = HashMap<String, () -> Unit>()
    internal val overlayRotations = HashMap<String, () -> Unit>()

    @JvmName("closeOverlay") internal fun closeOverlay(id: String) {
        if (id.startsWith("req-") && ::hermesRuntime.isInitialized) hermesRuntime.dismissRequest(id.removePrefix("req-"))
        sheetClosers.remove(id)?.let { it(); return }
        val returning = modalBuddyFlight?.takeIf { it.isActive && modalSurface === overlays[id] }
        val leaving = overlays.remove(id)
        overlayBack.remove(id); overlayRotations.remove(id)
        leaving?.let { view ->
            if (pinGate != null) { root.removeView(view); return@let }
            val under = currentBuddyPage()
            val source = under?.let { sceneAvatar(it, companionAgent?.id) }
            val target = sceneAvatar(view, companionAgent?.id)
            view.animate().cancel()
            if (returning != null) {
                modalCarryCleanup = { view.animate().cancel(); root.removeView(view) }
                returning.animateTo(0f)
                view.animate().alpha(0f).translationY(dp(28f)).setDuration(Motion.SLOW).setInterpolator(Theme.EASE_OUT).start()
            } else if (under != null && source != null && target != null && source.width > 0) {
                modalFlight(under, view, source, target, 1f, onCarry = { view.animate().cancel(); root.removeView(view) }) { root.removeView(view) }.animateTo(0f)
                view.animate().alpha(0f).translationY(dp(28f)).setDuration(Motion.SLOW).setInterpolator(Theme.EASE_OUT).start()
            } else view.animate().alpha(0f).translationY(dp(28f)).setDuration(Motion.BASE).setInterpolator(Theme.EASE_OUT).withEndAction { root.removeView(view); if (overlays.isEmpty()) restoreBuddies() }.start()
        }
    }

    /** Una finestra a tutto schermo con titolo e contenuto scorrevole. `back` è ciò che fa il tasto indietro (nega, salta, chiudi). */
    internal fun overlay(id: String, title: String, back: () -> Unit, context: BuddySceneContext? = null, build: (LinearLayout, () -> Unit) -> Unit) {
        if (overlays.containsKey(id)) return
        if (pinGate == null) carryMovingBuddy()
        val under = currentBuddyPage()
        val source = carriedBuddy ?: under?.let { sceneAvatar(it, companionAgent?.id) }
        val ov = FrameLayout(this).apply { setBackgroundColor(Theme.BG); isClickable = true }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(16f)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(26f, 700).apply { letterSpacing = -0.01f; text = title }, LinearLayout.LayoutParams(0, -2, 1f))
        val surfaceContext = context ?: stack.lastOrNull()?.buddyContext?.takeIf { it != BuddySceneContext.LIVE } ?: BuddySceneContext.READ
        val buddy = companion(64f)?.also { it.setScenePose(surfaceContext.pose, surfaceContext.activity) }
        buddy?.let { head.addView(it, LinearLayout.LayoutParams(dpi(64f), dpi(64f))) }
        outer.addView(head)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ov.addView(outer, FrameLayout.LayoutParams(-1, -1))
        val close = { closeOverlay(id) }
        build(col, close)
        root.addView(ov, ViewGroup.LayoutParams(-1, -1)); overlays[id] = ov; overlayBack[id] = back
        pinGate?.bringToFront()
        overlayRotations[id] = { ov.animate().cancel(); ov.alpha = 1f; ov.translationY = 0f; buddy?.let { it.visibility = View.VISIBLE; it.alpha = 1f } }
        ov.post {
            if (overlays[id] !== ov || !ov.isAttachedToWindow) return@post
            if (pinGate != null) { ov.alpha = 1f; return@post }
            if (under != null && source != null && buddy != null && source.width > 0) {
                modalFlight(under, ov, source, buddy, 0f) { }.animateTo(1f)
            }
            Motion.enter(ov, dy = dp(36f), dur = Motion.SLOW)
        }
    }

    // ---- fogli (bottom sheet): un solo componente per menu, scelte e testo libero (vedi DESIGN.md §4) ---------

    /** Una voce di un foglio: icona, titolo, aiuto, spunta se selezionata, rosso se distruttiva, sezione opzionale. */
    internal class SheetItem(val label: String, val sub: String? = null, val icon: String? = null, val accent: Int = Theme.accent, val selected: Boolean = false,
                            val danger: Boolean = false, val section: String? = null, val action: () -> Unit)

    /**
     * Il pannello: sale dal basso con una molla mentre lo sfondo si scurisce, si chiude trascinando la testata verso il basso, toccando lo
     * sfondo, con il pulsante o con indietro. `then` esegue un'azione quando il foglio e' sparito (cosi' non scatta sopra un foglio che se ne va).
     */
    // ---- buddy compagno: sempre in primo piano nei menu; guarda l'elemento toccato o a fuoco (rotella) e saltella quando lo scegli ----
    internal var companionAgent: Agent? = null
    internal var focusWatch = false
    internal fun attend(cv: CharacterView, v: View) {
        if (!cv.isAttachedToWindow || !v.isAttachedToWindow || v === cv) return
        val a = IntArray(2); cv.getLocationInWindow(a); val b = IntArray(2); v.getLocationInWindow(b)
        val dx = ((b[0] + v.width / 2f) - (a[0] + cv.width / 2f)) / (root.width.coerceAtLeast(1) / 2f)
        val dy = ((b[1] + v.height / 2f) - (a[1] + cv.height / 2f)) / (root.height.coerceAtLeast(1) / 2f)
        cv.lookAt((dx * 30f).coerceIn(-26f, 26f), (dy * 22f).coerceIn(-14f, 18f), hold = 1400)
    }
    internal fun companion(size: Float, agent: Agent? = companionAgent ?: visibleAgents().firstOrNull() ?: db.agents().firstOrNull()): CharacterView? {
        val a = agent ?: neutralBuddy
        val cv = CharacterView(this, dpi(size)).also { it.show(a); it.tag = "scene-buddy" }
        Motion.onAttention = { v -> attend(cv, v) }
        if (!focusWatch) { focusWatch = true; root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> if (n != null) Motion.onAttention?.invoke(n) } }
        var taps = 0; var tapAt = 0L
        cv.setOnClickListener {
            if (BuddyMood.sulking()) { BuddyMood.sulkUntil = 0L; cv.react("love"); return@setOnClickListener }
            val now = android.os.SystemClock.uptimeMillis(); taps = if (now - tapAt < 4000L) taps + 1 else 1; tapAt = now
            val row = (cv.parent as? View)?.takeIf { it.tag == "scene-companion-row" }
            // Ogni terzo tocco ravvicinato: nascondino (sparisce in un modo, ricompare in un altro).
            if (row != null && taps >= 3) { taps = 0; if (!BuddyEntrances.hideAndSeek(row)) cv.react("giggle") } else cv.react("giggle")
        }
        return cv
    }
    /** Il buddy grande a meta' pagina: il suo corpo occupa circa la meta' del riquadro, quindi il riquadro sporge sopra e sotto la riga (senza ritagli). */
    internal fun companionRow(size: Float = 140f): View? = companion(size)?.let { cv ->
        FrameLayout(this).apply { tag = "scene-companion-row"; clipChildren = false; clipToPadding = false; addView(cv, FrameLayout.LayoutParams(dpi(size), dpi(size), Gravity.CENTER))
            // Ogni tanto fa capolino da solo, ma mai nel pacchetto QA, mentre sprofonda nello scroll, offeso o in volo.
            if (!packageName.endsWith(".qa")) BuddyEntrances.scheduleIdlePeeks(this) { visible && cv.scaleX > .99f && cv.alpha > .99f && !BuddyMood.sulking() && buddyFlight?.isActive != true && modalBuddyFlight?.isActive != true }
        }
    }

    internal fun sheetPanel(title: String, subtitle: String? = null, searchHint: String? = null, peek: Agent? = null, build: (col: LinearLayout, close: () -> Unit, then: (() -> Unit) -> Unit) -> Unit) {
        val id = "sheet-" + System.nanoTime()
        carryMovingBuddy()
        val under = currentBuddyPage()
        val originalSource = under?.let { sceneAvatar(it, (companionAgent ?: visibleAgents().getOrNull(homeIndex))?.id) }
        val sourceBuddy = carriedBuddy ?: originalSource
        val ov = FrameLayout(this).apply { isClickable = true }
        val scrim = View(this).apply { setBackgroundColor(Theme.BG); alpha = 0f }
        ov.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = topRoundRect(Theme.mix(Theme.BG, Theme.CARD, 0.62f), dp(28f)); isClickable = true; alpha = 0f }
        val peekView = companion(150f, peek ?: companionAgent ?: visibleAgents().firstOrNull() ?: db.agents().firstOrNull())?.apply {
            alpha = 0f
            val role = stack.lastOrNull()?.buddyContext?.takeIf { it != BuddySceneContext.LIVE } ?: BuddySceneContext.READ
            setScenePose(role.pose, role.activity)
        }
        peekView?.let { pv -> pv.postDelayed({ if (pv.isAttachedToWindow && !Motion.reduced(this)) pv.lookAt(0f, 22f, hold = Motion.SLOW * 2) }, Motion.SLOW * 2) } // guarda la lista che si apre
        var peekInFlight = false
        fun viewportHeight() = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        fun peekHeight() = minOf(dp(150f), viewportHeight() * .30f).coerceAtLeast(dp(36f)).coerceAtMost(viewportHeight().toFloat())
        fun placePeek(y: Float, ph: Int) { peekView?.let {
            val logical = it.height.takeIf { h -> h > 0 }?.toFloat() ?: dp(150f)
            val visual = peekHeight(); val scale = visual / logical
            it.scaleX = scale; it.scaleY = scale
            it.translationY = maxOf(y - ph + dp(50f) + (logical - visual) / 2f, -viewportHeight() + (logical + visual) / 2f)
            if (!peekInFlight) it.alpha = if (panel.alpha > 0f) (1f - (y / ph.coerceAtLeast(1)).coerceIn(0f, 1f)) else 0f
        } }
        var closing = false; var finished = false; var afterClose: (() -> Unit)? = null
        var sheetFlight: BuddyFlight? = null
        var buddyReturning = false
        var afterRan = false
        /** La pagina scelta nel pannello parte subito: il buddy portato vola mentre il pannello scende, invece di restare fermo ad aspettarlo. */
        fun runAfter() { val a = afterClose ?: return; afterClose = null; afterRan = true; a() }
        lateinit var spring: dev.r1os.host.ui.Spring
        fun finish() {
            if (finished || buddyReturning) return
            finished = true; spring.cancel(); overlayRotations.remove(id); root.removeView(ov)
            val hadAfter = afterClose != null || afterRan
            runAfter()
            if (hadAfter) root.post {
                if (carriedBuddy != null && overlays.isEmpty() && buddyFlight == null && stack.lastOrNull()?.pendingEntry == null) {
                    val page = stack.lastOrNull()?.view
                    val target = page?.let { sceneAvatar(it, companionAgent?.id) }
                    if (page != null && target != null) {
                        val carry = carriedBuddy!!
                        modalFlight(page, page, target, carry, 1f) { clearCarriedBuddy(); target.visibility = View.VISIBLE; target.alpha = 1f }.animateTo(0f)
                    } else clearCarriedBuddy()
                }
            }
        }
        spring = dev.r1os.host.ui.Spring { y ->
            panel.translationY = y
            val ph = panel.height.coerceAtLeast(1)
            scrim.alpha = 0.8f * (1f - (y / ph).coerceIn(0f, 1f)); placePeek(y, ph)
            if (closing && y >= ph) finish()
        }
        fun close(then: (() -> Unit)? = null) {
            if (closing) { if (then != null) afterClose = then; return }
            closing = true; afterClose = then
            overlays.remove(id); overlayBack.remove(id); sheetClosers.remove(id)
            if (peekView != null && under != null && originalSource != null && peekView.width > 0 && originalSource.width > 0) {
                peekInFlight = true
                if (then != null) {
                    carryBuddy(peekView, sheetFlight); if (modalBuddyFlight === sheetFlight) { modalBuddyFlight = null; modalCarryCleanup = null; modalSourceBuddy = null; modalSurface = null }; sheetFlight = null; ov.post { runAfter() }
                } else {
                    buddyReturning = true
                    val entering = sheetFlight?.takeIf { it.isActive }
                    if (entering != null) {
                        modalCarryCleanup = { buddyReturning = false; finish() }
                        entering.animateTo(0f)
                    } else {
                        sheetFlight = null
                        modalFlight(under, ov, originalSource, peekView, 1f, onCarry = { buddyReturning = false; finish() }) { buddyReturning = false; finish() }.animateTo(0f)
                    }
                }
            }
            spring.animateTo(panel.height.toFloat() + dp(12f), 460f, 1f) { finish() }
        }
        scrim.setOnClickListener { close() }
        // Testata: maniglia + titolo + chiudi; trascinarla verso il basso chiude il foglio (segue il dito e, al rilascio, completa o torna su).
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(18f), dpi(if (peekView != null) 22f else 8f), dpi(10f), dpi(4f)) }
        head.addView(View(this).apply { background = roundRect(Theme.SUBTLE, dp(2f)) }, LinearLayout.LayoutParams(dpi(40f), dpi(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dpi(10f) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(24f, 800).keepCase().apply { text = title; letterSpacing = -0.01f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        subtitle?.let { titles.addView(text(14f, 500, Theme.MUTED).keepCase().apply { text = it; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(iconButton("close", Theme.MUTED, filled = false, size = 44f) { close() }.apply { contentDescription = UiText.text(this@MainActivity, R.string.ui_a7712b5b03) })
        head.addView(titleRow)
        var downY = 0f; var vt: android.view.VelocityTracker? = null
        head.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; vt?.recycle(); vt = android.view.VelocityTracker.obtain().also { it.addMovement(e) }; spring.cancel(); true }
                android.view.MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); val dy = (e.rawY - downY).coerceAtLeast(0f); spring.snapTo(dy); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(e); vt?.computeCurrentVelocity(1000); val vy = vt?.yVelocity ?: 0f; vt?.recycle(); vt = null
                    if (e.actionMasked == android.view.MotionEvent.ACTION_UP && (spring.value > panel.height * 0.3f || vy > dp(1100f))) close() else spring.animateTo(0f, 380f, 0.85f)
                    true
                }
                else -> true
            }
        }
        panel.addView(head)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), dpi(4f), dpi(14f), dpi(16f)) }
        if (searchHint != null) {
            val search = field(searchHint, "").apply {
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) {
                        val q = e?.toString()?.trim()?.lowercase().orEmpty()
                        for (i in 0 until col.childCount) col.getChildAt(i).let { v -> val t = v.tag as? String; if (t != null) v.visibility = if (q.isEmpty() || (!t.startsWith("§") && t.contains(q))) View.VISIBLE else View.GONE }
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            panel.addView(search, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dpi(14f); marginEnd = dpi(14f); bottomMargin = dpi(6f) })
        }
        panel.addView(MaxHeightScrollView(this) {
            val h = viewportHeight()
            var fixed = panel.paddingTop + panel.paddingBottom
            for (i in 0 until panel.childCount) panel.getChildAt(i).let { child ->
                if (child !is ScrollView) {
                    val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
                    fixed += child.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                }
            }
            val reserve = if (peekView != null) (peekHeight() - dp(50f)).coerceAtLeast(0f).toInt() else 0
            minOf((h * .62f).toInt(), h - reserve - fixed).coerceAtLeast(dpi(48f))
        }.apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }, LinearLayout.LayoutParams(-1, -2))
        ov.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        peekView?.let { ov.addView(it, FrameLayout.LayoutParams(dpi(150f), dpi(150f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)) }
        build(col, { close() }, { then -> close(then) })
        root.addView(ov, ViewGroup.LayoutParams(-1, -1)); overlays[id] = ov; overlayBack[id] = { close() }; sheetClosers[id] = { close() }
        pinGate?.bringToFront()
        overlayRotations[id] = {
            sheetFlight?.dispose(restoreSource = closing); sheetFlight = null
            if (closing) { buddyReturning = false; finish() }
            else { peekInFlight = false; panel.alpha = 1f; spring.snapTo(0f); peekView?.alpha = 1f; placePeek(0f, panel.height) }
        }
        var sheetViewport = 0
        ov.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (sheetViewport != bottom - top) { sheetViewport = bottom - top; panel.requestLayout() }
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placePeek(panel.translationY, panel.height) }
        ov.post {
            if (closing || overlays[id] !== ov || !ov.isAttachedToWindow) return@post
            if (peekView != null && sourceBuddy != null && under != null && sourceBuddy.width > 0 && peekView.width > 0) {
                placePeek(0f, panel.height)
                peekInFlight = true
                sheetFlight = modalFlight(under, ov, sourceBuddy, peekView, 0f) { peekInFlight = false; peekView.visibility = View.VISIBLE; peekView.alpha = 1f; sheetFlight = null }
                sheetFlight?.animateTo(1f)
            }
            spring.snapTo(panel.height.toFloat() + dp(12f)); panel.alpha = 1f
            spring.animateTo(0f, 380f, 0.88f)
            Motion.stagger(col, dp(12f))   // le voci entrano a cascata mentre il pannello sale
        }
    }

    /** Un foglio di scelte: icona, aiuto, spunta, sezioni, voci distruttive in fondo in rosso; ricerca sopra le 8 voci. */
    internal fun sheetOf(title: String, items: List<SheetItem>, subtitle: String? = null, search: Boolean = items.size > 12) {
        sheetPanel(title, subtitle, if (search) UiText.text(this, R.string.ui_2060f21a0f) else null) { col, _, then ->
            var lastSection: String? = null; var dangerStarted = false
            items.sortedBy { if (it.danger) 1 else 0 }.forEach { it ->
                if (it.section != null && it.section != lastSection) { lastSection = it.section; col.addView(text(14f, 700, Theme.MUTED).apply { text = it.section; tag = "§"; setPadding(dpi(6f), dpi(12f), 0, dpi(4f)) }) }
                if (it.danger && !dangerStarted) { dangerStarted = true; col.addView(View(this).apply { tag = "§"; setBackgroundColor(Theme.withAlpha(Theme.FG, 0.08f)) }, LinearLayout.LayoutParams(-1, dpi(1f)).apply { topMargin = dpi(6f); bottomMargin = dpi(10f); marginStart = dpi(8f); marginEnd = dpi(8f) }) }
                col.addView(row(it.label, if (it.danger) Theme.BAD else it.accent, bold = true, sub = it.sub, icon = it.icon, check = it.selected) { then { it.action() } }.apply { tag = it.label.lowercase() })
            }
        }
    }

    /** Compatibilita': un elenco di (voce, azione) con icone opzionali e voci distruttive opzionali. */
    internal fun sheet(title: String, rows: List<Pair<String, () -> Unit>>, icons: Map<String, String> = emptyMap(), danger: Set<String> = emptySet()) =
        sheetOf(title, rows.map { (label, act) -> SheetItem(label, icon = icons[label], danger = label in danger, action = act) })

    internal fun askText(title: String, hint: String, onOk: (String) -> Unit) {
        sheetPanel(title) { col, close, then ->
            val f = field(hint, "").apply { imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI }
            col.addView(f, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
            fun submit() { val t = f.text.toString().trim(); if (t.isNotEmpty()) then { onOk(t) } }
            f.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
            col.addView(button(UiText.text(this@MainActivity, R.string.ui_cc123cba38), Theme.colorOf("green")) { submit() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
            f.post { f.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f, 0) }
        }
    }

    /** Una domanda di Hermes: consenti o nega un comando (`approval`), oppure una o più domande dell'agente (`clarify`). */
    @JvmName("showRequest") internal fun showRequest(r: HermesDash.SrvRequest) {
        voiceConversation?.pauseForDialog()
        val id = "req-" + r.id
        root.postDelayed({ cue("surprised", force = true) }, Motion.SLOW * 2) // chiede attenzione
        when (r.method) {
            "approval" -> overlay(id, UiText.text(this@MainActivity, R.string.ui_74be1d4823), { Io.run { dash.answerApproval(r.id, "deny") }; closeOverlay(id) }, context = BuddySceneContext.SECURITY) { col, close ->
                col.addView(text(15f, 500, Theme.MUTED).keepCase().apply { text = r.params.optString("description").ifBlank { UiText.text(this@MainActivity, R.string.ui_76522a8687) }; setPadding(0, 0, 0, dpi(8f)) })
                val cmd = r.params.optString("command")
                if (cmd.isNotBlank()) col.addView(text(14f, 500).keepCase().apply {
                    text = cmd.take(1200); typeface = android.graphics.Typeface.MONOSPACE; background = roundRect(Theme.CARD, dp(14f)); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f)) })
                val choices = r.params.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf("once", "deny")
                val labels = mapOf("once" to UiText.text(this@MainActivity, R.string.ui_9522540105), "session" to UiText.text(this@MainActivity, R.string.ui_f0a1e830c8), "always" to UiText.text(this@MainActivity, R.string.ui_2818f5e9df), "deny" to UiText.text(this@MainActivity, R.string.ui_fb80edc0ec))
                for (c in choices) col.addView(button(labels[c] ?: c, if (c == "deny") Theme.BAD else if (c == "always") Theme.colorOf("amber") else Theme.colorOf("green"), filled = c == "once") { Io.run { dash.answerApproval(r.id, c) }; close() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
            }
            "clarify" -> {
                val qs = r.params.optJSONArray("questions")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
                var idx = 0
                overlay(id, UiText.text(this@MainActivity, R.string.ui_72f473fe9e), {
                    for (k in idx until qs.size) Io.run { runCatching { dash.answerClarify(r.id.toString(), qs[k].optString("qid"), null) } }
                    closeOverlay(id)
                }, context = BuddySceneContext.SECURITY) { col, close ->
                    fun render() {
                        // Il buddy è nella testata fissa: il contenuto della domanda si sostituisce tutto.
                        col.removeAllViews()
                        if (idx >= qs.size) { close(); return }
                        val q = qs[idx]; val qid = q.optString("qid")
                        val choices = q.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                        fun lock(ans: Any?) { val rid = r.id.toString(); Io.run { runCatching { dash.answerClarify(rid, qid, ans) } }; idx++; render() }
                        if (qs.size > 1) col.addView(text(14f, 600, Theme.MUTED).apply { text = UiText.text(this@MainActivity, R.string.ui_f20fd57dff, idx + 1, qs.size) })
                        col.addView(text(19f, 600).keepCase().apply { text = q.optString("question"); setPadding(0, dpi(4f), 0, dpi(8f)) })
                        if (choices.isNotEmpty() && q.optBoolean("multi_select")) {
                            val sel = LinkedHashSet<String>()
                            choices.forEach { c ->
                                lateinit var rv: LinearLayout
                                rv = row(c, Theme.accent, raw = true) { if (!sel.add(c)) sel.remove(c); rowTitle(rv).text = (if (c in sel) "✓ " else "") + c }
                                col.addView(rv)
                            }
                            col.addView(button(UiText.text(this@MainActivity, R.string.ui_d554d69f55), Theme.colorOf("green")) { lock(org.json.JSONArray(sel.toList())) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
                        } else if (choices.isNotEmpty()) {
                            choices.forEach { c -> col.addView(row(c, Theme.accent, raw = true) { lock(c) }) }
                        } else {
                            val f = field(UiText.text(this@MainActivity, R.string.ui_90070cdbc6), "")
                            col.addView(f); col.addView(button(UiText.text(this@MainActivity, R.string.ui_d554d69f55), Theme.colorOf("green")) { lock(f.text.toString()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
                        }
                        col.addView(button(UiText.text(this@MainActivity, R.string.ui_6653fb8a8e), Theme.MUTED, primary = false) { lock(null) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
                    }
                    render()
                }
            }
        }
    }

    // ---- Hermes: agenti = profili, chat = sessioni, cronologia letta da Hermes -----------------------

    /** Le notifiche si chiedono nel momento in cui servono (primo messaggio, sveglia, timer) e una volta sola: non all'apertura, davanti a una home vuota. */
    internal fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 || prefs.getBoolean("asked_notifications", false)) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
    }

    // ---- messaggi brevi nello stile dell'app (al posto dei Toast di sistema) -----------------------

    internal var snackView: TextView? = null
    internal val snackHide = Runnable {
        val v = snackView ?: return@Runnable
        v.animate().alpha(0f).translationY(dp(18f)).setDuration(Motion.BASE).withEndAction { root.removeView(v); if (snackView === v) snackView = null }.start()
    }
    /** Un messaggio breve in basso: pillola chiara sul tema scuro (e scura sul chiaro), con ingresso a molla; sparisce da sola. */
    /** Errore in parole semplici: causa nota se riconosciuta, altrimenti il messaggio originale. */
    internal fun errorText(e: Throwable): String = when (ErrorKind.of(e)) {
        ErrorKind.KEY -> UiText.text(this, R.string.ui_6503aaa0d4)
        ErrorKind.NETWORK -> UiText.text(this, R.string.ui_db32442bd0)
        ErrorKind.TIMEOUT -> UiText.text(this, R.string.ui_423e5043b2)
        ErrorKind.RATE -> UiText.text(this, R.string.ui_0d3b9428e5)
        ErrorKind.SERVER -> UiText.text(this, R.string.ui_a8a7633f72)
        ErrorKind.CONFIG -> UiText.text(this, R.string.ui_7726650957)
        ErrorKind.OTHER -> UiText.message(this, e.message) ?: UiText.text(this, R.string.ui_00ea95d6d1)
    }

    internal fun copyText(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("codice", t))
        snack(UiText.text(this, R.string.ui_b6ec5021d5))
    }

    /** Esegue [f] quando la vista e' in finestra: un risultato (o un errore) che arriva prima dell'ingresso della pagina non va perso. */
    internal fun whenAttached(v: View, f: () -> Unit) {
        if (v.isAttachedToWindow) { f(); return }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { v.removeOnAttachStateChangeListener(this); f() }
            override fun onViewDetachedFromWindow(x: View) {}
        })
    }

    internal var armedKey: String? = null
    internal var armedAt = 0L
    /** Azione distruttiva: il primo tocco avvisa, il secondo entro 4 secondi la esegue. */
    internal fun confirmTap(key: String, act: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        if (armedKey == key && now - armedAt < 4000) { armedKey = null; act() }
        else { armedKey = key; armedAt = now; snack(UiText.text(this, R.string.ui_af2b0de88b)) }
    }

    internal fun snack(message: String?, long: Boolean = false) {
        val text = message?.takeIf { it.isNotBlank() } ?: return
        root.post {
            root.removeCallbacks(snackHide); snackView?.let { root.removeView(it) }
            val v = text(15f, 650, Theme.BG).keepCase().apply {
                this.text = UiText.message(this@MainActivity, text) ?: text; gravity = Gravity.CENTER; maxLines = 4
                setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f)); background = roundRect(Theme.FG, dp(22f)); alpha = 0f; translationY = dp(22f); isClickable = true
                setOnClickListener { root.removeCallbacks(snackHide); snackHide.run() }
            }
            root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(96f); marginStart = dpi(20f); marginEnd = dpi(20f) })
            v.animate().alpha(1f).translationY(0f).setDuration(Motion.BASE).setInterpolator(Theme.POP).start()
            snackView = v
            root.postDelayed(snackHide, if (long) 4200L else 2400L)
        }
    }

    internal fun hermesMode() = settings.provider.id == "dashboard"
    internal fun visibleAgents(): List<Agent> = db.agents().filter { if (hermesMode()) it.profile.isNotEmpty() else it.profile.isEmpty() }

    /** Legge i profili da Hermes e li rispecchia come agenti (l'aspetto resta locale e dipende dal nome). */
    internal fun syncProfiles(scope: Scope, error: (String) -> Unit, done: () -> Unit) {
        Io.run {
            try {
                val forms = CharacterView.FORMS; val colors = Theme.COLORS.keys.toList()
                for (p in dash.profiles()) {
                    val h = p.name.hashCode() and 0x7fffffff; val name = p.display.ifBlank { p.name }
                    val ex = db.agentByProfile(p.name)
                    if (ex == null) db.save(Agent(0, name, p.description, "", forms[h % forms.size], colors[(h / 7) % colors.size], "soft", "classic", "", p.name))
                    else if (ex.name != name || ex.prompt != p.description) { ex.name = name; ex.prompt = p.description; db.save(ex) }
                }
                runOnUiThread { if (scope.alive) done() }
            } catch (e: Exception) { runOnUiThread { if (scope.alive) error(errorText(e)) } }
        }
    }

    /** Legge le sessioni di un profilo e le rispecchia come chat (bloccante: da un thread). */
    internal fun syncSessions(a: Agent) {
        for (s in dash.sessions(a.profile)) db.upsertChat(a.id, s.id, s.title.ifBlank { s.preview }.replace('\n', ' ').take(60).ifBlank { "chat" }, s.startedAt, s.count)
        sessionSyncAt[a.id] = System.currentTimeMillis()
    }

    internal fun loadingScreen(a: Agent, t: String): Screen {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val loadingBuddy = CharacterView(this, dpi(92f)).also { it.show(a, "thinking") }
        col.addView(loadingBuddy)
        if (!packageName.endsWith(".qa")) BuddyCuriosity.start(loadingBuddy, 2.5f) { isCurrentSurface(col) } // sta cercando: ogni tanto si guarda intorno
        col.addView(text(18f, 600, Theme.MUTED).keepCase().apply { text = t; gravity = Gravity.CENTER; setPadding(0, dpi(10f), 0, 0) })
        return Screen(page(col), buddyContext = BuddySceneContext.LIVE)
    }

    @JvmName("replaceTop") internal fun replaceTop(s: Screen) {
        carryMovingBuddy(); cancelBuddyFlight()
        val visibleSource = visibleSceneScreen()
        val old = stack.removeAt(stack.size - 1)
        cancelPendingEntry(old)
        val from = visibleSource ?: old
        val selected = companionAgent ?: visibleAgents().getOrNull(homeIndex) ?: neutralBuddy
        val source = carriedBuddy ?: sceneAvatar(from.view, selected.id)
        old.scope.alive = false; old.onLeave?.invoke()
        s.heroOrigin = if (s.chatKey == old.chatKey && s.isChat) old.heroOrigin else Hero(0f, 0f, source?.width ?: 0, selected)
        stack.add(s); root.addView(s.view, ViewGroup.LayoutParams(-1, -1)); pinGate?.bringToFront(); runtimeVisibility()
        if (chatLocked(s.chatKey)) { pinUnlocked = false; root.post { if (stack.lastOrNull() === s) showPinGate(false) } }
        if (source != null && sceneAvatar(s.view, selected.id) != null) {
            if (s.heroOrigin == null) s.heroOrigin = Hero(0f, 0f, source.width, selected)
            s.view.alpha = 0f
            val entry = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    s.view.viewTreeObserver.removeOnPreDrawListener(this); s.pendingEntry = null
                    if (stack.lastOrNull() === s) prepareBuddyFlight(s, from, 0f)?.animateTo(1f)
                        ?: run { s.view.alpha = 1f; detach(old.view); clearCarriedBuddy() }
                    return true
                }
            }
            s.pendingEntry = entry; s.view.viewTreeObserver.addOnPreDrawListener(entry)
            return
        }
        clearCarriedBuddy()
        Motion.enter(s.view, dy = dp(14f), dur = Motion.BASE)
        old.view.animate().cancel(); old.view.animate().alpha(0f).setDuration(Motion.BASE).withEndAction { root.removeView(old.view) }.start()
    }

    /** Apre una chat; se è di Hermes ne legge prima la cronologia dal dashboard. */
    internal fun openChat(a: Agent, chatId: Long, say: String? = null, share: Share? = null) {
        if (hermesRuntime.active(chatId) != null) { push(chat(a, chatId, say, share)); return }
        val hsid = db.hsid(chatId)
        if (!(hermesMode() && a.profile.isNotEmpty() && hsid.isNotEmpty())) { push(chat(a, chatId, say, share)); return }
        // Chat gia' letta altre volte: si apre SUBITO con quello che c'e' in locale e la cronologia di Hermes si rilegge in secondo piano; se cambia (o la
        // sessione sta lavorando altrove) la chat si aggiorna da sola, conservando la bozza.
        if (db.messagesPage(chatId, 1, null).isNotEmpty()) {
            push(chat(a, chatId, say, share))
            Io.run {
                val before = chatSignature(chatId)
                var err: String? = null; var working = false
                try { db.replaceMessages(a.id, chatId, dash.open(chatId, hsid, a.profile)); working = dash.liveChecked()[hsid] in listOf("working", "waiting") } catch (e: Exception) { err = UiText.message(this@MainActivity, e.message) }
                val changed = chatSignature(chatId) != before
                runOnUiThread {
                    if (stack.lastOrNull()?.chatKey == chatId && (changed || working)) replaceTop(chat(a, chatId, null, null, observeHsid = if (working) hsid else null))
                    err?.let { snack(UiText.text(this@MainActivity, R.string.ui_cd083a17e7, it), long = true) }
                }
            }
            return
        }
        // Prima volta: la home resta (con l'avatar che "pensa") e la chat arriva con la dissolvenza e il volo; senza volo, la pagina "carico".
        if (!opening.add(chatId)) return // doppio tocco: una sola apertura alla volta
        val flying = hero != null
        val origin = stack.lastOrNull()
        val loading = if (flying) null else loadingScreen(a, UiText.text(this@MainActivity, R.string.ui_6c766bf934)).also { push(it) }
        Io.run {
            var err: String? = null
            var working = false
            try { db.replaceMessages(a.id, chatId, dash.open(chatId, hsid, a.profile)); working = dash.liveChecked()[hsid] in listOf("working", "waiting") } catch (e: Exception) { err = UiText.message(this@MainActivity, e.message) }
            runOnUiThread {
                opening.remove(chatId)
                // Risposta tardiva: se nel frattempo l'utente è andato altrove la chat non si apre sopra la sua pagina.
                val stillHere = !isDestroyed && stack.lastOrNull() === origin
                if (loading == null) { if (err == null && stillHere) push(chat(a, chatId, say, share, observeHsid = if (working) hsid else null)) else hero = null }
                else if (stack.lastOrNull() === loading) replaceTop(chat(a, chatId, say, share, observeHsid = if (working) hsid else null))
                err?.let { snack(UiText.text(this@MainActivity, R.string.ui_cd083a17e7, it), long = true) }
            }
        }
    }

    /** Impronta del contenuto locale di una chat (ultimi messaggi): serve a capire se la rilettura da Hermes ha cambiato qualcosa. */
    internal fun chatSignature(chatId: Long): String = db.messagesPage(chatId, 3, null).joinToString("|") { it.second + it.third.hashCode() }
    internal val sessionSyncAt = HashMap<Long, Long>()
    /** Aperture in corso (id chat, o -id agente): un doppio tocco apriva due chat una sopra l'altra. */
    internal val opening = HashSet<Long>()

    /** L'ultima chat dell'agente (per gli agenti di Hermes: dopo aver riletto le sessioni). */
    internal fun openLatest(a: Agent, say: String? = null, share: Share? = null) {
        if (!(hermesMode() && a.profile.isNotEmpty())) { push(chat(a, db.lastChat(a.id) ?: db.newChat(a.id), say, share)); return }
        // Sessioni lette da poco: si apre subito l'ultima chat nota e l'elenco si aggiorna in secondo piano.
        val known = db.lastChat(a.id)
        if (known != null && System.currentTimeMillis() - (sessionSyncAt[a.id] ?: 0L) < 120_000L) {
            Io.run { try { syncSessions(a) } catch (e: Exception) { } }
            openChat(a, known, say, share); return
        }
        if (!opening.add(-a.id)) return // doppio tocco sullo stesso agente
        val flying = hero != null
        val origin = stack.lastOrNull()
        val loading = if (flying) null else loadingScreen(a, UiText.text(this@MainActivity, R.string.ui_d4758167e0, a.name)).also { push(it) }
        Io.run {
            try { syncSessions(a) } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@MainActivity, R.string.ui_af1c39a0e8, UiText.message(this@MainActivity, e.message)), long = true) } }
            val id = db.lastChat(a.id) ?: db.newChat(a.id)
            runOnUiThread {
                opening.remove(-a.id)
                if (isDestroyed) return@runOnUiThread
                if (loading == null) { if (stack.lastOrNull() === origin) openChat(a, id, say, share) else hero = null }
                else if (stack.lastOrNull() === loading) { pop(); openChat(a, id, say, share) }
            }
        }
    }

    internal fun removeChat(agent: Agent, id: Long, done: () -> Unit = {}) {
        if (hermesRuntime.active(id) != null) { snack(UiText.text(this@MainActivity, R.string.ui_8bd1630032), long = true); return }
        val hs = db.hsid(id)
        if (hs.isEmpty() || agent.profile.isEmpty()) { dash.forget(id); db.deleteChat(id); done(); return }
        // Prima l'esito remoto: se Hermes rifiuta (sessione ancora aperta) il record locale resta.
        Io.run {
            try { dash.delete(hs, agent.profile); dash.forget(id); db.deleteChat(id); runOnUiThread { done() } }
            catch (e: Exception) { runOnUiThread { done(); snack(UiText.message(this@MainActivity, e.message) ?: UiText.text(this@MainActivity, R.string.ui_0f2784f610), long = true) } }
        }
    }

    /** La prima schermata: Glance (orologio) o la home, come scelto in Aspetto. */
    internal var rootProvider = ""
    internal fun root(): Screen { rootProvider = settings.provider.id; return rootScreen() }
    internal fun rootScreen(): Screen = if (prefs.getString("start", "home") == "glance") glance() else home()

    // ---- navigazione ----------------------------------------------------------------------------

    internal fun detach(v: View) { v.animate().cancel(); v.setLayerType(View.LAYER_TYPE_NONE, null); (v.parent as? ViewGroup)?.removeView(v); v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f }

    /** Avanti: la nuova schermata entra da destra mentre la vecchia scivola un poco a sinistra e sfuma. */
    internal fun applySceneContext(screen: Screen?, force: Boolean = false) {
        if (screen == null || screen.isChat || screen.buddyContext == BuddySceneContext.LIVE) return
        val buddy = sceneAvatar(screen.view, companionAgent?.id) ?: return
        if (!force && screen.posedBuddy === buddy) return
        screen.sceneCue?.let { screen.posedBuddy?.removeCallbacks(it) }
        screen.posedBuddy = buddy
        buddy.setScenePose(screen.buddyContext.pose, screen.buddyContext.activity)
        // Il gesto contestuale accompagna l'arrivo; poi il buddy riposa senza un ciclo costante.
        screen.sceneCue = Runnable {
            if (screen.scope.alive && screen.posedBuddy === buddy) {
                buddy.setScenePose("idle")
                if (visible && stack.lastOrNull() === screen && buddy.isAttachedToWindow && currentBuddyPage() === screen.view) buddy.lookAt(0f, 6f, 1200)
            }
        }.also { buddy.postDelayed(it, 1800L) }
    }
    internal fun cancelPendingEntry(screen: Screen?) {
        screen?.pendingEntry?.let { if (screen.view.viewTreeObserver.isAlive) screen.view.viewTreeObserver.removeOnPreDrawListener(it) }
        screen?.pendingEntry = null
    }
    internal fun visibleSceneScreen(): Screen? = stack.asReversed().firstOrNull { it.view.parent === root && it.view.alpha > .01f } ?: stack.lastOrNull()
    @JvmName("showScreen") internal fun showScreen(screen: Screen) { screen.onShow?.invoke(); applySceneContext(screen, true) }

    @JvmName("runtimeVisibility") internal fun runtimeVisibility() {
        applySceneContext(stack.lastOrNull())
        updateSecure()
        if (::hermesRuntime.isInitialized) { hermesRuntime.foreground = visible; hermesRuntime.visibleChat = if (visible) { if (voiceConversation != null) voiceConversation?.visibleChatId else stack.lastOrNull()?.chatKey } else null }
    }
    /** Quanto arretra la pagina sotto (parallasse) e quanto si scurisce mentre quella nuova scorre sopra. */
    internal val parallax = 0.28f
    internal val dimmed = 0.5f
    internal fun pageWidth() = root.width.toFloat().takeIf { it > 0f } ?: dp(320f)

    // ---- trasformazione condivisa: l'avatar della home vola nell'intestazione della chat (la pagina si dissolve, non scorre) ----
    internal class Hero(val cx: Float, val cy: Float, val size: Int, val agent: Agent)
    internal var hero: Hero? = null

    internal fun findAvatar(v: View, size: Int): CharacterView? {
        if (v is CharacterView && v.width == size) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findAvatar(v.getChildAt(i), size)?.let { return it }
        return null
    }

    internal var carriedBuddy: CharacterView? = null
    internal var modalSurface: View? = null
    internal var modalBuddyFlight: BuddyFlight? = null
    internal var modalCarryCleanup: (() -> Unit)? = null
    internal var modalSourceBuddy: CharacterView? = null
    internal val heldBuddies = LinkedHashSet<CharacterView>()

    internal fun holdBuddy(view: CharacterView) { heldBuddies.add(view); view.alpha = 0f; view.visibility = View.INVISIBLE; view.setActive(false) }
    /**
     * Rete di sicurezza contro i buddy doppi: a riposo (nessun volo, nessuna scheda aperta, nessuna entrata in attesa) un clone
     * "buddy-flight"/"buddy-carry" rimasto in root, o un carriedBuddy che nessuno ha rilasciato, e' un fantasma. Si toglie solo se
     * lo stesso stato si ripete per due controlli di fila, per non toccare un passaggio di mano legittimo.
     */
    internal var laneKick: android.view.ViewTreeObserver.OnPreDrawListener? = null
    internal var laneKickHost: View? = null
    internal fun cancelLaneKick() {
        val k = laneKick; val h = laneKickHost
        if (k != null && h != null && h.viewTreeObserver.isAlive) h.viewTreeObserver.removeOnPreDrawListener(k)
        laneKick = null; laneKickHost = null
    }
    internal var orphanTicks = 0
    internal val buddyReaper = object : Runnable {
        override fun run() {
            reapOrphanBuddies()
            if (visible) root.postDelayed(this, 1200L)
        }
    }
    internal fun reapOrphanBuddies() {
        val resting = overlays.isEmpty() && buddyFlight?.isActive != true && modalBuddyFlight?.isActive != true && stack.lastOrNull()?.pendingEntry == null && pttView == null
        val ghosts = ArrayList<View>()
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v is CharacterView && (v.tag == "buddy-flight" || v.tag == "buddy-carry") && v !== carriedBuddy) ghosts += v
        }
        if (!resting || (ghosts.isEmpty() && carriedBuddy == null)) { orphanTicks = 0; return }
        if (++orphanTicks < 2) return
        orphanTicks = 0
        ghosts.forEach { heldBuddies.remove(it); root.removeView(it) }
        if (carriedBuddy != null) clearCarriedBuddy() else if (heldBuddies.isNotEmpty()) restoreBuddies()
    }
    internal fun restoreBuddies() {
        if (overlays.isNotEmpty()) return   // sotto una scheda aperta i buddy restano nascosti: li riaccende la chiusura
        val current = currentBuddyPage()?.let { sceneAvatar(it, companionAgent?.id) }
        heldBuddies.forEach { it.visibility = View.VISIBLE; it.alpha = 1f; it.setActive(visible && it === current) }; heldBuddies.clear()
        current?.let { cv -> cv.setActive(visible); Motion.onAttention = { view -> attend(cv, view) } }
    }
    internal fun clearCarriedBuddy(restore: Boolean = true) {
        carriedBuddy?.let { heldBuddies.remove(it); root.removeView(it) }; carriedBuddy = null
        if (restore) restoreBuddies()
    }
    /** Un nuovo gesto prende il personaggio dove si trova, senza farlo tornare per un frame all'anchor. */
    internal fun carryMovingBuddy() {
        val flight = modalBuddyFlight?.takeIf { it.isActive } ?: buddyFlight?.takeIf { it.isActive } ?: return
        val modal = flight === modalBuddyFlight
        clearCarriedBuddy(restore = false)
        if (modal) currentBuddyPage()?.let { sceneAvatar(it, companionAgent?.id) }?.let(::holdBuddy)
        else {
            flightHome?.view?.let { sceneAvatar(it, companionAgent?.id) }?.let(::holdBuddy)
            flightChat?.view?.let { sceneAvatar(it, companionAgent?.id) }?.let(::holdBuddy)
        }
        val cleanup = if (modal) modalCarryCleanup else null
        val actor = flight.takeActor()
        flight.leftHidden.forEach(::holdBuddy)
        if (modal) { modalBuddyFlight = null; modalCarryCleanup = null; modalSourceBuddy = null; modalSurface = null } else {
            buddyFlight = null
            flightHome?.let { if (stack.lastOrNull() !== it) detach(it.view) }
            flightChat?.let { if (it !in stack) detach(it.view) }
            flightHome = null; flightChat = null
        }
        carriedBuddy = actor; actor.alpha = 1f; actor.bringToFront(); pinGate?.bringToFront()
        cleanup?.invoke()
    }
    internal fun currentBuddyPage(): View? = overlays.values.lastOrNull() ?: pttView ?: stack.lastOrNull()?.view

    internal var lastCueAt = 0L
    /**
     * Un gesto del buddy della pagina corrente legato a un evento: prima guarda dove serve (`dx`,`dy`), poi reagisce (`kind`).
     * Rispetta animazioni ridotte, broncio, voli in corso e intero/visibile; al massimo uno ogni 2×SLOW (salvo `force`).
     */
    internal fun cue(kind: String?, dx: Float = 0f, dy: Float = 0f, force: Boolean = false) {
        if (Motion.reduced(this) || BuddyMood.sulking() || buddyFlight?.isActive == true || modalBuddyFlight?.isActive == true || !visible) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastCueAt < Motion.SLOW * 2) return
        val cv = sceneAvatar(currentBuddyPage() ?: return, companionAgent?.id) ?: return
        if (!cv.isShown || cv.alpha < .99f || cv.scaleX < .98f) return
        lastCueAt = now
        if (dx != 0f || dy != 0f) cv.lookAt(dx, dy, hold = Motion.SLOW)
        kind?.let { cv.react(it) }
    }
    internal fun carryBuddy(anchor: CharacterView, flight: BuddyFlight? = null) {
        clearCarriedBuddy(restore = false)
        val actor = if (flight?.isActive == true) flight.takeActor().also { flight.leftHidden.forEach(::holdBuddy) } else {
            val matrix = android.graphics.Matrix(); anchor.transformMatrixToGlobal(matrix); root.transformMatrixToLocal(matrix)
            val bounds = android.graphics.RectF(0f, 0f, anchor.width.toFloat(), anchor.height.toFloat()).also { matrix.mapRect(it) }
            CharacterView(this, anchor.width).apply {
                copyAppearanceFrom(anchor); tag = "buddy-carry"; setActive(false); setOnTouchListener(null); isClickable = false; isFocusable = false
                root.addView(this, FrameLayout.LayoutParams(anchor.width, anchor.height))
                measure(View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(anchor.height, View.MeasureSpec.EXACTLY))
                layout(0, 0, anchor.width, anchor.height)
                translationX = bounds.centerX() - anchor.width / 2f; translationY = bounds.centerY() - anchor.height / 2f
                scaleX = bounds.width() / anchor.width; scaleY = bounds.height() / anchor.height
            }
        }
        holdBuddy(anchor); carriedBuddy = actor; actor.alpha = 1f; actor.bringToFront(); pinGate?.bringToFront()
    }
    internal fun cancelModalBuddyFlight(restoreSource: Boolean = true) {
        val flight = modalBuddyFlight
        val cleanup = modalCarryCleanup
        val source = modalSourceBuddy
        modalBuddyFlight = null; modalCarryCleanup = null; modalSourceBuddy = null; modalSurface = null
        val restore = restoreSource || cleanup != null
        flight?.dispose(restoreSource = restore)
        if (restore) source?.let { heldBuddies.remove(it) }
        cleanup?.invoke()
    }
    internal fun modalFlight(under: View, surface: View, source: CharacterView, target: CharacterView, initial: Float, onCarry: (() -> Unit)? = null, done: () -> Unit): BuddyFlight {
        cancelModalBuddyFlight(restoreSource = false)
        holdBuddy(source)
        val flight = BuddyFlight(root, under, surface, source, target, initial, fadePages = false,
            resolveHome = { sceneAvatar(under, companionAgent?.id) }, resolveChat = { sceneAvatar(surface, companionAgent?.id) }) { completed, destination ->
            val returnCleanup = if (destination == 0f && modalBuddyFlight === completed) modalCarryCleanup else null
            completed.dispose(restoreSource = destination == 0f)
            if (modalBuddyFlight === completed) { modalBuddyFlight = null; modalCarryCleanup = null; modalSourceBuddy = null; modalSurface = null }
            if (destination == 0f) {
                heldBuddies.remove(source)
                if (overlays.isEmpty()) restoreBuddies()
            } else { holdBuddy(sceneAvatar(under, companionAgent?.id) ?: source); clearCarriedBuddy(restore = false) }
            done(); returnCleanup?.invoke()
        }
        modalBuddyFlight = flight; modalCarryCleanup = onCarry; modalSourceBuddy = source; modalSurface = surface; pinGate?.bringToFront(); if (surface === pinGate) flight.bringActorToFront(); return flight
    }

    internal var buddyFlight: BuddyFlight? = null
    internal var flightChat: Screen? = null
    internal var flightHome: Screen? = null

    internal fun cancelBuddyFlight() {
        buddyFlight?.dispose(); buddyFlight = null
        flightHome?.let { if (stack.lastOrNull() !== it) detach(it.view) }
        flightChat?.let { if (it !in stack) detach(it.view) }
        flightHome = null; flightChat = null
    }

    @JvmName("sceneAvatar") internal fun sceneAvatar(view: View, agentId: Long? = null): CharacterView? {
        view.findViewWithTag<CharacterView>("scene-buddy")?.let { return it }
        if (view.tag == "home-screen") {
            // Su una pagina del carosello (fotocamera, app, strumenti…) il buddy che si vede è quello della pagina, non quello della rotella nascosto sotto: i voli devono partire e arrivare lì.
            view.findViewWithTag<CharacterView>("lane-buddy")?.takeIf { it.isShown && it.width > 0 }?.let { return it }
            val focused = visibleAgents().getOrNull(homeIndex)?.id ?: neutralBuddy.id
            view.findViewWithTag<CharacterView>("home-agent-$focused")?.let { return it }
        }
        if (agentId != null) view.findViewWithTag<CharacterView>("home-agent-$agentId")?.let { return it }
        findAvatar(view, dpi(56f))?.let { return it }
        fun first(v: View): CharacterView? = if (v is CharacterView) v else if (v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { first(v.getChildAt(it)) } else null
        return first(view)
    }

    internal fun prepareBuddyFlight(chat: Screen, home: Screen, initial: Float): BuddyFlight? {
        if (flightChat === chat && flightHome === home) return buddyFlight
        cancelBuddyFlight()
        modalBuddyFlight?.takeIf { it.isActive && carriedBuddy == null }?.let { cancelModalBuddyFlight(restoreSource = false) }   // un solo clone in volo
        val origin = chat.heroOrigin ?: return null
        if (home.view.parent == null) root.addView(home.view, 0, ViewGroup.LayoutParams(-1, -1))
        for (view in listOf(home.view, chat.view)) {
            view.animate().cancel(); view.translationX = 0f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
        }
        // La home puo' essere stata staccata prima di una rotazione: si misura nelle dimensioni correnti.
        if (home.view.width != root.width || home.view.height != root.height || !home.view.isLaidOut || home.view.isLayoutRequested) {
            home.view.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
            home.view.layout(0, 0, root.width, root.height)
        }
        val source = carriedBuddy ?: sceneAvatar(home.view, origin.agent.id) ?: return null
        val target = sceneAvatar(chat.view, origin.agent.id) ?: return null
        characters(home.view) { it.setActive(false) }
        val flight = BuddyFlight(root, home.view, chat.view, source, target, initial,
            resolveHome = { sceneAvatar(home.view, origin.agent.id) }, resolveChat = { sceneAvatar(chat.view, origin.agent.id) }) { completed, destination ->
            if (buddyFlight === completed) {
                completed.dispose(); buddyFlight = null; flightChat = null; flightHome = null
                clearCarriedBuddy()
                if (destination == 0f) { root.removeView(chat.view); showScreen(home) }
                else if (stack.lastOrNull() !== home) detach(home.view)
            }
        }
        buddyFlight = flight; flightChat = chat; flightHome = home
        pinGate?.bringToFront()
        return flight
    }

    internal fun isDescendant(parent: View, child: View): Boolean { var p: android.view.ViewParent? = child.parent; while (p != null) { if (p === parent) return true; p = p.parent }; return false }

    /** Il rettangolo (nel root) dell'elemento appena toccato, se appartiene alla schermata in cima e il tocco e' recente: la nuova pagina nasce da li'. */
    internal fun tapOrigin(): android.graphics.RectF? {
        val v = Motion.lastTap?.get() ?: return null
        val top = stack.lastOrNull()?.view ?: return null
        if (android.os.SystemClock.uptimeMillis() - Motion.lastTapAt > 900 || !v.isAttachedToWindow || v.width == 0 || !isDescendant(top, v)) return null
        val l = IntArray(2); v.getLocationInWindow(l); val r = IntArray(2); root.getLocationInWindow(r)
        return android.graphics.RectF((l[0] - r[0]).toFloat(), (l[1] - r[1]).toFloat(), (l[0] - r[0] + v.width).toFloat(), (l[1] - r[1] + v.height).toFloat())
    }

    /** Scala (uniforme: il testo non si deforma) con cui una pagina intera si riduce per coincidere con l'elemento da cui nasce. */
    internal fun originScale(o: android.graphics.RectF) = (o.width() / pageWidth()).coerceIn(0.35f, 0.96f)

    /** Avanti: la nuova pagina (opaca) scorre da destra; quella sotto arretra del 28% e si scurisce. Interrompibile: riparte dai valori correnti. */
    @JvmName("push") internal fun push(s: Screen, fade: Boolean = false) {
        if (stack.lastOrNull()?.isVoice == true && !s.isVoice) { replaceTop(s); return }
        carryMovingBuddy()
        cancelBuddyFlight()
        hideKeyboard()
        val previousTop = stack.lastOrNull()
        val prev = visibleSceneScreen()
        prev?.onSuspend?.invoke()
        cancelPendingEntry(previousTop)
        if (previousTop != null && previousTop !== prev) detach(previousTop.view)
        val selected = companionAgent ?: visibleAgents().getOrNull(homeIndex) ?: visibleAgents().firstOrNull() ?: db.agents().firstOrNull() ?: neutralBuddy
        val sourceBuddy = carriedBuddy ?: prev?.let { sceneAvatar(it.view, selected?.id) }
        val flying = prev != null && sourceBuddy != null && sceneAvatar(s.view, selected?.id) != null && selected != null
        val fadeIn = fade || flying
        stack.add(s); root.addView(s.view, ViewGroup.LayoutParams(-1, -1)); pinGate?.bringToFront(); runtimeVisibility()
        if (chatLocked(s.chatKey)) { pinUnlocked = false; root.post { if (stack.lastOrNull() === s) showPinGate(false) } }   // chat bloccata: chiede il PIN
        if (prev == null) return
        if (flying) {
            s.heroOrigin = hero ?: Hero(0f, 0f, sourceBuddy!!.width, selected!!); hero = null
            s.view.alpha = 0f
            val entry = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    s.view.viewTreeObserver.removeOnPreDrawListener(this); s.pendingEntry = null
                    if (stack.lastOrNull() === s) prepareBuddyFlight(s, prev, 0f)?.animateTo(1f)
                        ?: run { s.view.alpha = 1f; detach(prev.view); clearCarriedBuddy() }
                    return true
                }
            }
            s.pendingEntry = entry; s.view.viewTreeObserver.addOnPreDrawListener(entry)
            return
        }
        val w = pageWidth()
        prev.view.animate().cancel(); s.view.animate().cancel()
        if (fadeIn) {
            // La chat (costosa da disporre) viene misurata PRIMA di partire: l'animazione inizia al primo disegno, su un livello hardware, per ridurre il costo dei fotogrammi durante il movimento.
            // Dissolvenza della chat e della home e volo dell'avatar durano uguale (SLOW) e partono insieme: la chat compare piano, trasparente, mentre l'avatar arriva.
            s.view.alpha = 0f; s.view.translationY = dp(14f); s.view.setLayerType(View.LAYER_TYPE_HARDWARE, null); prev.view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val ease = Theme.EASE_OUT
            s.view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    s.view.viewTreeObserver.removeOnPreDrawListener(this)
                    s.view.animate().alpha(1f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(ease).withEndAction { s.view.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
                    prev.view.animate().alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction { prev.view.setLayerType(View.LAYER_TYPE_NONE, null); if (stack.lastOrNull() !== prev) detach(prev.view) }.start()
                    return true
                }
            })
            return
        }
        val origin = tapOrigin()
        if (origin != null) {
            // La pagina nasce dall'elemento toccato (riga, scheda, pulsante): parte piccola dov'era e si espande; quella sotto si allontana appena.
            s.origin = origin
            val k = originScale(origin); val h = root.height.toFloat().coerceAtLeast(dp(400f))
            s.view.scaleX = k; s.view.scaleY = k; s.view.translationX = origin.centerX() - w / 2f; s.view.translationY = origin.centerY() - h / 2f; s.view.alpha = 0f
            s.view.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
            android.animation.ObjectAnimator.ofFloat(s.view, View.ALPHA, 0f, 1f).setDuration(Motion.BASE).start()
            prev.view.animate().scaleX(0.94f).scaleY(0.94f).alpha(dimmed).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withEndAction { if (stack.lastOrNull() !== prev) detach(prev.view) }.start()
            return
        }
        s.view.translationX = w
        s.view.animate().translationX(0f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
        prev.view.animate().translationX(-w * parallax).alpha(dimmed).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withEndAction { if (stack.lastOrNull() !== prev) detach(prev.view) }.start()
    }

    /** Indietro: la pagina sotto torna al suo posto mentre quella in cima esce a destra (se il gesto e' partito dal bordo, riprende da dove l'ha lasciata il dito). */
    internal fun pop(fromDrag: Boolean = false) {
        if (stack.size <= 1) return
        hideKeyboard()
        val s = stack.removeAt(stack.size - 1); cancelPendingEntry(s); s.scope.alive = false
        if (chatLocked(s.chatKey)) pinUnlocked = false
        if (s.view.tag == "ring") { setShowWhenLocked(false); setTurnScreenOn(false) }
        s.onLeave?.invoke()
        if (stack.size == 1 && rootProvider != settings.provider.id) { stack[0].scope.alive = false; stack[0].onLeave?.invoke(); detach(stack[0].view); stack[0] = root() }
        val back = stack.last()
        // Da una chat bloccata a un'altra chat bloccata: prima si toglieva lo sblocco ma non si chiedeva il PIN, e la chat sotto restava leggibile.
        if (chatLocked(back.chatKey) && !pinUnlocked) root.post { if (stack.lastOrNull() === back) showPinGate(false) }
        if (s.heroOrigin != null && !s.view.isLaidOut) {
            root.removeView(s.view)
            if (back.view.parent == null) root.addView(back.view, 0, ViewGroup.LayoutParams(-1, -1))
            back.view.alpha = 1f; showScreen(back); clearCarriedBuddy(); runtimeVisibility(); return
        }
        if (s.heroOrigin != null) {
            if (buddyFlight == null) showScreen(back)
            val flight = prepareBuddyFlight(s, back, 1f)
            if (flight != null) { runtimeVisibility(); flight.animateTo(0f); return }
        }
        val w = pageWidth()
        val origin = if (fromDrag) null else s.origin
        if (!fromDrag || back.view.parent == null) {
            detach(back.view)
            if (origin != null) { back.view.scaleX = 0.94f; back.view.scaleY = 0.94f; back.view.alpha = dimmed } else { back.view.translationX = -w * parallax; back.view.alpha = dimmed }
            root.addView(back.view, 0, ViewGroup.LayoutParams(-1, -1))
        }
        back.view.animate().cancel()
        back.view.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(if (fromDrag) Motion.BASE else Motion.SLOW).setInterpolator(Theme.SETTLE).start()
        s.view.animate().cancel()
        if (origin != null) {
            // Torna nell'elemento da cui era nata: si rimpicciolisce verso di esso e sfuma.
            val k = originScale(origin); val h = root.height.toFloat().coerceAtLeast(dp(400f))
            s.view.animate().scaleX(k).scaleY(k).alpha(0f).translationX(origin.centerX() - w / 2f).translationY(origin.centerY() - h / 2f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT)
                .withEndAction { root.removeView(s.view); s.view.scaleX = 1f; s.view.scaleY = 1f; s.view.translationX = 0f; s.view.translationY = 0f; s.view.alpha = 1f }.start()
        } else {
            s.view.animate().translationX(w).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction { root.removeView(s.view); s.view.translationX = 0f }.start()
        }
        runtimeVisibility()
        showScreen(back)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pinGate != null) { if (gateWanted()) leaveLockedChat() else if (!isDefaultLauncher()) finish(); return }
        overlays.keys.lastOrNull()?.let { overlayBack[it]?.invoke(); return }   // prima le finestre sopra tutto (permessi, menu)
        if (pttView != null) { pttClose(); return }
        // Anche quando la radice è la glance e la home le sta sopra: Indietro prima riporta il carosello all'agente.
        if (stack.lastOrNull()?.isHome == true && homeLaneBack?.invoke() == true) return
        if (stack.size > 1 && stack.last().leaveGuard?.invoke() == true) return
        if (stack.size > 1) pop() else if (!isDefaultLauncher()) finish()   // come ogni app indietro esce; da schermata iniziale non esce (come ogni launcher)
    }

    /** Tasto laterale: una pressione tenuta = parla finché tieni; doppia pressione = parla senza tenere, si ferma alla pressione successiva. */
    internal var pttLatched = false
    internal var pttIgnoreUp = false
    internal var pttLastUp = 0L
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (pinGate != null) return super.dispatchKeyEvent(e)
        Dim.poke()
        val ptt = e.keyCode == KeyEvent.KEYCODE_BUTTON_1 || e.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        val modal = overlays.isNotEmpty() || voiceConversation != null || carriedBuddy != null || modalBuddyFlight != null
        // Una sola consegna: prima lo stesso evento veniva passato due volte al carosello.
        // Il rilascio del tasto centrale va solo alla home: senza, il click della rotella nel carosello non eseguiva mai l'azione.
        val centerUp = e.action == KeyEvent.ACTION_UP && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER) && stack.lastOrNull()?.isHome == true
        if (!modal && (e.action == KeyEvent.ACTION_DOWN || ptt || centerUp) && stack.lastOrNull()?.keys?.invoke(e) == true) return true
        if (ptt && voiceConversation == null && (carriedBuddy != null || modalBuddyFlight != null)) return true
        if (ptt && overlays.isEmpty() && (voiceConversation != null || stack.lastOrNull()?.isChat != true)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                when {
                    pttLatched -> { pttLatched = false; pttIgnoreUp = true; pttStop() }                      // terza pressione: ferma e invia
                    now - pttLastUp < 450L -> { pttLatched = true; pttIgnoreUp = true; pttStart() }          // doppia pressione: ascolto senza tenere premuto
                    else -> pttStart()
                }
            } else if (e.action == KeyEvent.ACTION_UP) {
                if (pttIgnoreUp) pttIgnoreUp = false
                else if (!pttLatched) { pttLastUp = now; pttStop() }
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> { firstScroll()?.let { if (it is R1ScrollView) it.nudge(it.height * .75f) else it.pageScroll(View.FOCUS_DOWN) }; return true }
            KeyEvent.KEYCODE_PAGE_UP -> { firstScroll()?.let { if (it is R1ScrollView) it.nudge(-it.height * .75f) else it.pageScroll(View.FOCUS_UP) }; return true }
            KeyEvent.KEYCODE_TAB, KEYCODE_NAV_NEXT -> { moveFocus(View.FOCUS_FORWARD); return true }
            KEYCODE_NAV_PREV -> { moveFocus(View.FOCUS_BACKWARD); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveFocus(View.FOCUS_FORWARD); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveFocus(View.FOCUS_BACKWARD); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    internal fun page(content: View, pad: Int = 14) = FrameLayout(this).apply {
        clipChildren = false; clipToPadding = false
        defaultFocusHighlightEnabled = false
        setBackgroundColor(Theme.BG); setPadding(dpi(pad.toFloat()), dpi(10f), dpi(pad.toFloat()), dpi(8f)); addView(content, -1, -1)
    }

    internal fun pill(label: String, accent: Int, onClick: () -> Unit): TextView = button(label, accent, small = true, onClick = onClick)

    @JvmName("field") internal fun field(hint: String, value: String, multi: Boolean = false, secret: Boolean = false) = EditText(this).apply {
        setText(value); setHint(hint); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Theme.font(500); setTextColor(Theme.FG); setHintTextColor(Theme.MUTED)
        background = roundRect(Theme.FOCUS, dp(14f)); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)); minHeight = dpi(50f)
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        if (multi) { minLines = 3; gravity = Gravity.TOP } else { setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI }   // Invio chiude la tastiera
        // setSingleLine toglie la maschera della password: la si rimette (le credenziali restano puntini) e niente suggerimenti/autofill.
        if (secret) {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance(); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            // Finché un campo segreto è sullo schermo, niente screenshot né anteprima nei recenti (anche senza PIN).
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { secretFields++; updateSecure() }
                override fun onViewDetachedFromWindow(v: View) { secretFields = (secretFields - 1).coerceAtLeast(0); updateSecure() }
            })
        }
    }

    internal fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0) }


    // ---- home: un agente alla volta, la rotella li gira -----------------------------------------

    /** Una pagina del carosello della home: nome, ruolo e personaggio (o il cerchio "nuovo"). */
    internal class HomeSlide(val v: LinearLayout, val body: View, val avatar: CharacterView?) {
        val sq = dev.r1os.host.ui.Spring { x -> body.pivotX = body.width / 2f; body.pivotY = body.height.toFloat(); body.scaleY = 1f - x; body.scaleX = 1f + x * 0.5f }
        fun land() { sq.snapTo(0.07f); sq.animateTo(0f, 320f, 0.4f) }
    }

    /** In home, dentro una funzione del carosello Indietro (o il tasto della rotella) torna all'agente. */
    internal var homeLaneBack: (() -> Boolean)? = null
    internal var welcomeChecked = false
    /** Primo avvio: una sola volta, se non c'è ancora un fornitore configurato, propone da dove cominciare. */
    internal fun maybeWelcome() {
        if (welcomeChecked) return
        welcomeChecked = true
        if ((packageName.endsWith(".qa") && !intent.getBooleanExtra("qa_welcome", false)) || prefs.getBoolean("welcomed", false)) return
        prefs.edit().putBoolean("welcomed", true).apply()
        val cur = settings.provider
        if (cur.id == "dashboard" || settings.key(cur).isNotBlank() || nous.loggedIn) return
        sheet(UiText.text(this, R.string.ui_5e5f6a3ffb), listOf(
            UiText.text(this, R.string.ui_688aad2b45) to { push(providerScreen()) },
            UiText.text(this, R.string.ui_936217976e) to { if (!pinEnabled()) pinSetup() },
            UiText.text(this, R.string.ui_bc159b0503) to {}))
    }

    internal fun home(): Screen {
        val scope = Scope()
        window.decorView.postDelayed({ if (scope.alive) maybeWelcome() }, 1200)
        var stopHomeState: (() -> Unit)? = null
        var agents = visibleAgents()
        val stage = FrameLayout(this).apply { defaultFocusHighlightEnabled = false }
        val wheel = FrameLayout(this).apply { clipChildren = false; defaultFocusHighlightEnabled = false }
        val dots = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val view = page(stage).also { it.background = bgDrawable("home"); it.tag = "home-screen" }
        val slides = HashMap<Int, HomeSlide>()
        var emptyNote: String? = null
        var sel = homeIndex.coerceIn(0, agents.size)
        fun n() = agents.size + 1
        fun roleOf(a: Agent) = a.prompt.lineSequence().firstOrNull().orEmpty().substringBefore(". ").take(40)
        fun focusAvatar() = slides[sel]?.avatar
        fun signature() = agents.joinToString("|") { "${it.id},${it.name},${it.form},${it.color},${it.finish},${it.eyes},${it.wear}" }
        var sig = signature()

        fun make(i: Int): HomeSlide {
            val a = agents.getOrNull(i)
            val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
            val name = text(40f, 800).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = -0.02f; text = a?.name ?: UiText.text(this@MainActivity, R.string.ui_b1a1efd9f5); if (a != null) setTextColor(Theme.mix(Theme.FG, Theme.colorOf(a.color), 0.22f)); setShadowLayer(dp(10f), 0f, dp(2f), Theme.withAlpha(if (a != null) Theme.colorOf(a.color) else Theme.MUTED, 0.35f)) }
            val role = text(17f, 650, Theme.MUTED).apply {
                gravity = Gravity.CENTER; maxLines = if (a == null && emptyNote != null) 3 else 1; ellipsize = android.text.TextUtils.TruncateAt.END
                text = if (a != null) "" else emptyNote.orEmpty(); visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            val size = dpi(250f)
            val body: View = if (a != null) CharacterView(this, size).also { it.show(a); it.tag = "home-agent-${a.id}" } else FrameLayout(this).apply {
                background = DashedRing(this@MainActivity); clipChildren = false
                addView(CharacterView(this@MainActivity, size).also { it.show(neutralBuddy); it.tag = "home-agent-${neutralBuddy.id}" }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                addView(IconView(this@MainActivity, "plus", Theme.accent, Theme.ON_ACCENT), FrameLayout.LayoutParams(dpi(28f), dpi(28f), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dpi(18f), dpi(18f)) })
            }
            v.addView(name, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6f) })
            v.addView(role, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
            // Il cerchio "nuovo" sta dove sta il corpo di un personaggio (che riempie circa l'85% della sua scatola).
            val ring = (size * 0.85f).toInt(); val m = (size - ring) / 2
            v.addView(body, if (a != null) LinearLayout.LayoutParams(size, size) else LinearLayout.LayoutParams(ring, ring).apply { setMargins(m, m, m, m) })
            wheel.addView(v, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            val character = (body as? CharacterView) ?: body.findViewWithTag<CharacterView>("home-agent-${neutralBuddy.id}")
            return HomeSlide(v, body, character).also { slides[i] = it; character?.setActive(i == sel) }
        }

        fun step(): Float = (slides.values.firstOrNull { it.v.height > 0 }?.v?.height?.toFloat() ?: dp(290f).toFloat()) * 0.70f
        /** Mette ogni pagina al suo posto per una posizione `p` (in agenti, con la virgola): la grande al centro, i vicini piccoli e sfumati sopra e sotto. */
        fun layout(p: Float) {
            val st = step()
            for (i in 0 until n()) {
                val d = i - p; val ad = Math.abs(d)
                if (ad > 2f) { slides[i]?.v?.visibility = View.GONE; continue }
                val s = slides[i] ?: make(i).also { it.v.post { if (it.v.isAttachedToWindow) { /* misurata: ricalcola */ } } }
                val k = Math.min(ad, 1f)
                val sc = 1f - 0.58f * k
                s.v.visibility = View.VISIBLE
                s.v.scaleX = sc; s.v.scaleY = sc
                s.v.alpha = if (ad <= 1f) 1f - 0.82f * k else (0.18f * (2f - ad)).coerceAtLeast(0f)
                s.v.translationY = d * st
            }
        }
        val pos = dev.r1os.host.ui.Spring { layout(it) }

        fun paintDots() {
            dots.removeAllViews()
            for (i in 0 until n()) {
                val a = agents.getOrNull(i); val on = i == sel
                dots.addView(View(this).apply { background = roundRect(if (on) (a?.let { Theme.colorOf(it.color) } ?: Theme.MUTED) else Theme.SUBTLE, dpi(4f).toFloat()) },
                    LinearLayout.LayoutParams(dpi(if (on) 6f else 5f), dpi(if (on) 16f else 5f)).apply { topMargin = dpi(5f) })
            }
        }
        val pillText = text(18f, 700, Theme.accent).apply { gravity = Gravity.CENTER }
        fun onFocus(first: Boolean = false): Unit {
            Mascot.agent = agents.getOrNull(sel)
            companionAgent = agents.getOrNull(sel) ?: neutralBuddy
            slides.forEach { (i, s) -> s.avatar?.setActive(i == sel) }
            if (!first) { slides[sel]?.land(); focusAvatar()?.lookAt(0f, 8f, 600) }
            paintDots()
            pillText.text = if (agents.getOrNull(sel) != null) UiText.text(this@MainActivity, R.string.ui_1cbfd76bb1) else UiText.text(this@MainActivity, R.string.ui_9c6c16c4f7)
        }
        fun rebuild() {
            wheel.removeAllViews(); slides.clear()
            sel = homeIndex.coerceIn(0, agents.size); homeIndex = sel
            sig = signature()
            pos.snapTo(sel.toFloat())
            wheel.post { layout(pos.value) }
            onFocus(first = true)
        }
        var lastBump = 0L; var lastStep = 0L
        fun goTo(t: Int, fromWheel: Boolean = false) {
            val c = t.coerceIn(0, agents.size)
            val now = android.os.SystemClock.uptimeMillis()
            // Rotella: non piu' di un passo ogni 90 ms (la rotella manda raffiche), e oltre il capo un solo rimbalzo finche' continui a girare.
            if (fromWheel && c != sel && now - lastStep < 90) return
            if (c == sel && t != c && fromWheel) {
                val quiet = now - lastBump > 700
                lastBump = now
                if (!quiet) return
            }
            if (c != sel) lastStep = now
            if (c == sel) {
                if (t != c) { pos.shift(if (t > c) 0.14f else -0.14f); focusAvatar()?.let { it.lookAt(0f, if (t > c) 14f else -14f, 500); it.react("surprised") } }
                pos.animateTo(sel.toFloat(), 260f, 0.8f); return
            }
            sel = c; homeIndex = c; prefs.edit().putInt("home_index", c).apply(); stage.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            pos.animateTo(c.toFloat(), 260f, 0.82f)
            onFocus()
        }
        fun open() {
            val a = agents.getOrNull(sel)
            if (a == null) { push(editor(null)); return }
            // L'avatar a fuoco vola nell'intestazione della chat (vedi launchHero); se la chat non si apre entro 3 s il volo viene dimenticato.
            focusAvatar()?.let { v -> if (v.width > 0) { val l = IntArray(2); v.getLocationInWindow(l); hero = Hero(l[0] + v.width / 2f, l[1] + v.height / 2f, v.width, a); stage.postDelayed({ hero = null }, 8000); if (hermesMode()) v.let { (it as? CharacterView)?.show(a, "thinking") } } }
            openLatest(a)
        }
        fun menu() { companionAgent = agents.getOrNull(sel) ?: neutralBuddy; menuSheet(agents.getOrNull(sel)) }

        stage.addView(wheel, FrameLayout.LayoutParams(-1, -1))
        // ---- carosello orizzontale: scorrendo a destra/sinistra il buddy mostra la funzione con il suo oggetto in mano ----
        class Lane(val id: String, val title: String, val sub: String, val prop: String, val go: () -> Unit)
        val leftLanes = listOf(
            Lane("settings", UiText.text(this@MainActivity, R.string.ui_0c375bfee9), UiText.text(this@MainActivity, R.string.ui_6a107bf8d4), "tools") { push(settingsScreen()) },   // a sinistra: dal centro verso l'esterno
            Lane("apps", UiText.text(this@MainActivity, R.string.ui_7d1043473d), UiText.text(this@MainActivity, R.string.ui_be6f615b92), "apps") { push(appsScreen()) },
        )
        val rightLanes = listOfNotNull(
            Lane("camera", UiText.text(this@MainActivity, R.string.ui_10315f3cb1), UiText.text(this@MainActivity, R.string.ui_aaa045009b), "camera") { sheet(UiText.text(this@MainActivity, R.string.ui_10315f3cb1), listOf(UiText.text(this@MainActivity, R.string.ui_57289acd6d) to { pickPhoto(16) }, UiText.text(this@MainActivity, R.string.ui_b1a6079206) to { launchCamera() })) },
            Lane("tools", UiText.text(this@MainActivity, R.string.ui_6bceb5c35b), UiText.text(this@MainActivity, R.string.ui_fa1f94b99f), "stopwatch") { push(tools()) },
            Lane("translate", UiText.text(this@MainActivity, R.string.ui_1a8a49ab36), UiText.text(this@MainActivity, R.string.ui_9383d2036e), "globe") { push(translator()) },
            Lane("controls", UiText.text(this@MainActivity, R.string.ui_4d538300d5), UiText.text(this@MainActivity, R.string.ui_3dd6153b9c), "controls") { push(controlsScreen()) },
            Lane("notes", UiText.text(this@MainActivity, R.string.ui_c51048b732), UiText.text(this@MainActivity, R.string.ui_32be769c09), "notes") { push(notesScreen()) },
            if (hermesMode()) Lane("skills", UiText.text(this@MainActivity, R.string.ui_d2d5097861), UiText.text(this@MainActivity, R.string.ui_2d73049f78), "skills") { agents.getOrNull(sel)?.let { push(skillsScreen(it)) } } else null,
            if (hermesMode()) Lane("jobs", UiText.text(this@MainActivity, R.string.ui_e8250ed32d), UiText.text(this@MainActivity, R.string.ui_2515690daf), "jobs") { push(routinesScreen()) } else null,
        )
        var lanePos = 0
        val laneCaption = text(14f, 700, Theme.MUTED).apply { gravity = Gravity.CENTER; letterSpacing = 0.08f }
        val laneLayer = FrameLayout(this).apply { visibility = View.INVISIBLE; defaultFocusHighlightEnabled = false }
        stage.addView(laneLayer, FrameLayout.LayoutParams(-1, -1))
        val laneDots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        stage.addView(laneDots, FrameLayout.LayoutParams(-2, dpi(16f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(2f) })
        stage.addView(laneCaption, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(18f) })
        fun laneAt(pos: Int): Lane? = if (pos < 0) leftLanes.getOrNull(-pos - 1) else if (pos > 0) rightLanes.getOrNull(pos - 1) else null
        fun paintLaneDots() {
            laneDots.removeAllViews()
            for (i in -leftLanes.size..rightLanes.size) laneDots.addView(View(this).apply { background = roundRect(if (i == lanePos) Theme.accent else Theme.SUBTLE, dp(4f)) }, LinearLayout.LayoutParams(if (i == lanePos) dpi(16f) else dpi(7f), dpi(7f)).apply { marginStart = dpi(3f); marginEnd = dpi(3f) })
        }
        // La colonna del buddy si chiama «agenti»; le altre portano il nome della funzione. I puntini di rotazione degli agenti (a destra) esistono solo lì.
        // Dentro una funzione il titolo grande dice gia' il nome: la didascalia lo ripeterebbe (due "fotocamera" uno sull'altro).
        fun paintLaneCaption() { laneCaption.text = if (laneAt(lanePos) != null) "" else UiText.text(this@MainActivity, R.string.ui_a27ab2d196).lowercase() }
        paintLaneDots(); paintLaneCaption()
        var laneView: View? = null
        var laneFlight: BuddyFlight? = null
        fun buildLane(l: Lane): View {
            val a = agents.getOrNull(sel) ?: neutralBuddy
            val size = dpi(250f)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
            col.addView(text(40f, 800).apply { gravity = Gravity.CENTER; maxLines = 1; letterSpacing = -0.02f; text = l.title }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6f) })
            val subV = text(17f, 650, Theme.MUTED).keepCase().apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; text = l.sub }
            col.addView(subV, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
            if (l.id == "controls") subV.text = batteryLine().ifBlank { l.sub }
            if (l.id == "skills") agents.getOrNull(sel)?.takeIf { it.profile.isNotEmpty() }?.let { ag -> Io.run { // quante competenze ha attive l'agente su Hermes
                val n = runCatching { dash.skills(ag.profile) }.getOrNull()?.count { it.enabled }
                if (n != null) runOnUiThread { if (col.isAttachedToWindow) subV.text = UiText.text(this@MainActivity, R.string.ui_3b73f5eb84, n) }
            } }
            if (l.id == "jobs") Io.run { // dal vivo da Hermes: la prossima attività pianificata
                val next = runCatching { dash.cronJobs() }.getOrNull()?.filter { it.enabled && it.nextRun > 0 }?.minByOrNull { it.nextRun }
                if (next != null) runOnUiThread { if (col.isAttachedToWindow) subV.text = next.name + " · " + relativeIn(next.nextRun - System.currentTimeMillis()) }
            }
            val body = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
            val buddy = CharacterView(this, size).also { it.show(a); it.tag = "lane-buddy" }
            val prop = PropView(this, l.prop)
            body.addView(buddy, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            body.addView(prop, FrameLayout.LayoutParams(dpi(104f), dpi(104f), Gravity.BOTTOM or Gravity.END).apply { marginEnd = dpi(6f); bottomMargin = dpi(14f) })
            col.addView(body, LinearLayout.LayoutParams(size, size))
            col.tag = Triple(buddy, prop, l)
            if (!Motion.reduced(this)) { // l'oggetto entra con un'oscillazione, il buddy lo guarda e saltella
                prop.rotation = -38f; prop.scaleX = .7f; prop.scaleY = .7f
                prop.animate().rotation(-8f).scaleX(1f).scaleY(1f).setStartDelay(Motion.BASE).setDuration(Motion.SLOW).setInterpolator(Theme.POP).start()
                buddy.postDelayed({ if (buddy.isAttachedToWindow) { buddy.lookAt(16f, 14f, hold = Motion.SLOW * 2); buddy.react("bounce") } }, Motion.BASE)
            }
            return col
        }
        // Rete di sicurezza: a riposo, nel livello delle funzioni c'e' al piu' la colonna corrente; le altre sono residui di gesti interrotti.
        var laneStale = 0
        val laneJanitor = object : Runnable {
            override fun run() {
                if (!scope.alive) return
                val moving = laneFlight?.isActive == true || laneKick != null
                val keep = if (lanePos == 0) null else laneView
                var extra = 0
                for (i in 0 until laneLayer.childCount) if (laneLayer.getChildAt(i) !== keep) extra++
                if (moving || extra == 0) laneStale = 0
                else if (++laneStale >= 2) {
                    laneStale = 0
                    for (i in laneLayer.childCount - 1 downTo 0) { val c = laneLayer.getChildAt(i); if (c !== keep) { c.animate().cancel(); laneLayer.removeView(c) } }
                }
                laneLayer.postDelayed(this, 1000L)
            }
        }
        laneLayer.postDelayed(laneJanitor, 1500L)
        fun laneGo(target: Int, instant: Boolean = false) {
            val t = target.coerceIn(-leftLanes.size, rightLanes.size)
            if (t == lanePos && !instant) return
            val dir = if (t > lanePos) 1 else -1
            val outgoing: View = if (lanePos == 0) wheel else (laneView ?: wheel)
            // Colonne rimaste da gesti precedenti interrotti (animate().cancel() non esegue la rimozione a fine corsa): con scatti rapidi
            // restavano nel livello con il loro buddy, sovrapposte a quello giusto. Qui si tiene solo quella che sta uscendo.
            for (i in laneLayer.childCount - 1 downTo 0) { val c = laneLayer.getChildAt(i); if (c !== outgoing) { c.animate().cancel(); laneLayer.removeView(c) } }
            lanePos = t
            paintLaneDots(); paintLaneCaption()
            dots.animate().cancel(); dots.animate().alpha(if (t == 0) 1f else 0f).setDuration(Motion.BASE).start()
            val l = laneAt(t)
            val incoming: View = if (l == null) wheel else buildLane(l).also { laneLayer.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) }
            val w = stage.width.coerceAtLeast(1).toFloat()
            laneLayer.visibility = View.VISIBLE
            if (instant || Motion.reduced(this)) {
                outgoing.alpha = 0f; if (outgoing !== wheel) laneLayer.removeView(outgoing) else wheel.visibility = View.INVISIBLE
                incoming.visibility = View.VISIBLE; incoming.alpha = 1f; incoming.translationX = 0f
                laneView = if (l == null) null else incoming; if (l == null) laneLayer.visibility = View.INVISIBLE
                return
            }
            val ot = outgoing.tag as? Triple<*, *, *>; val nt = incoming.tag as? Triple<*, *, *>
            if (outgoing !== wheel && incoming !== wheel && ot != null && nt != null) {
                // Da una pagina all'altra il buddy resta lì: cambia l'attrezzatura. L'oggetto vecchio esce ruotando, quello nuovo entra, i titoli scivolano.
                val ob = ot.first as CharacterView; val oldProp = ot.second as PropView; val nb = nt.first as CharacterView; val newProp = nt.second as PropView
                val oc = outgoing as LinearLayout; val ic = incoming as LinearLayout
                cancelLaneKick(); laneFlight?.dispose(); laneFlight = null
                val nbParent = nb.parent as ViewGroup; val lp = nb.layoutParams
                (ob.parent as ViewGroup).removeView(ob); nbParent.removeView(nb); nbParent.addView(ob, 0, lp)   // lo stesso buddy passa alla nuova colonna: nessuna dissolvenza
                incoming.tag = Triple(ob, newProp, l)
                incoming.visibility = View.VISIBLE; incoming.alpha = 1f; incoming.translationX = 0f
                val shift = dpi(36f).toFloat() * dir
                for (i in 0..1) {
                    val inText = ic.getChildAt(i); inText.translationX = shift; inText.alpha = 0f
                    inText.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
                    oc.getChildAt(i).animate().translationX(-shift).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).start()
                }
                newProp.alpha = 0f; newProp.animate().alpha(1f).setStartDelay(Motion.PRESS).setDuration(Motion.BASE).start()
                oldProp.animate().cancel(); oldProp.animate().rotation(-dir * 60f).scaleX(.5f).scaleY(.5f).translationX(-shift).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction { laneLayer.removeView(outgoing) }.start()
                ob.lookAt(dir * 18f, 10f, hold = Motion.SLOW * 2)
                laneView = incoming
                return
            }
            // Dalla rotella (agenti) a una pagina e ritorno: il buddy vola da una posizione all'altra (un solo buddy in scena), la rotella si dissolve sul posto e titoli e oggetto scivolano.
            val wheelBuddy = focusAvatar()
            val laneCol = if (outgoing === wheel) incoming else outgoing
            val laneTri = laneCol.tag as? Triple<*, *, *>
            if (wheelBuddy != null && laneTri != null && laneCol !== wheel && (outgoing === wheel) != (incoming === wheel)) {
                val toLane = outgoing === wheel
                val laneBuddy = laneTri.first as CharacterView; val laneProp = laneTri.second as PropView
                val lc = laneCol as LinearLayout; val shift = dpi(36f).toFloat() * dir
                cancelLaneKick(); laneFlight?.dispose(); laneFlight = null
                wheel.animate().cancel(); wheel.translationX = 0f
                if (toLane) {
                    incoming.visibility = View.VISIBLE; incoming.alpha = 1f; incoming.translationX = 0f
                    for (i in 0..1) { val t = lc.getChildAt(i); t.translationX = shift; t.alpha = 0f; t.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start() }
                    wheel.animate().alpha(0f).setDuration(Motion.FAST).setInterpolator(Theme.EASE_OUT).withEndAction { if (lanePos != 0) wheel.visibility = View.INVISIBLE }.start()
                } else {
                    wheel.visibility = View.VISIBLE; wheel.alpha = 0f; wheel.animate().alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
                    for (i in 0..1) lc.getChildAt(i).animate().translationX(-shift).alpha(0f).setDuration(Motion.FAST).setInterpolator(Theme.EASE_OUT).start()
                    laneProp.animate().cancel(); laneProp.animate().rotation(-dir * 60f).scaleX(.5f).scaleY(.5f).translationX(-shift).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction { laneLayer.removeView(laneCol) }.start()
                }
                // Il buddy: un clone vola dalla posizione della rotella a quella della pagina (o al contrario) dopo il primo layout.
                laneBuddy.alpha = 0f; if (!toLane) wheelBuddy.alpha = 1f
                val kickoff = object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        laneCol.viewTreeObserver.removeOnPreDrawListener(this)
                        if (laneKick === this) { laneKick = null; laneKickHost = null }
                        if (!laneCol.isAttachedToWindow || wheelBuddy.width <= 0 || laneBuddy.width <= 0) { laneBuddy.alpha = 1f; return true }
                        val f = BuddyFlight(root, wheel, laneCol, wheelBuddy, laneBuddy, if (toLane) 0f else 1f, fadePages = false) { c, _ -> c.dispose(restoreSource = true); if (laneFlight === c) laneFlight = null }
                        laneFlight = f; f.animateTo(if (toLane) 1f else 0f)
                        return true
                    }
                }
                laneKick = kickoff; laneKickHost = laneCol
                laneCol.viewTreeObserver.addOnPreDrawListener(kickoff)
                laneView = if (l == null) null else incoming
                if (l == null) laneLayer.postDelayed({ if (lanePos == 0) laneLayer.visibility = View.INVISIBLE }, Motion.SLOW + 60L)
                return
            }
            incoming.visibility = View.VISIBLE; incoming.translationX = dir * w * 0.4f; incoming.alpha = 0f
            incoming.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
            outgoing.animate().translationX(-dir * w * 0.4f).alpha(0f).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withEndAction {
                if (outgoing === wheel) { if (lanePos != 0) wheel.visibility = View.INVISIBLE } else laneLayer.removeView(outgoing)
                outgoing.translationX = 0f
            }.start()
            if (incoming === wheel) { wheel.visibility = View.VISIBLE; wheel.alpha = 0f; wheel.animate().alpha(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start() }
            laneView = if (l == null) null else incoming
            if (l == null) laneLayer.postDelayed({ if (lanePos == 0) laneLayer.visibility = View.INVISIBLE }, Motion.SLOW + 60L)
        }
        fun laneStep(d: Int) = laneGo(lanePos + d)
        homeLaneBack = { if (scope.alive && lanePos != 0) { laneGo(0); true } else false }
        /** Tocco sulla funzione: l'oggetto fa il suo gesto, poi si apre. */
        fun laneAction() {
            val l = laneAt(lanePos) ?: return
            (laneView?.tag as? Triple<*, *, *>)?.let { (it.first as? CharacterView)?.react("bounce"); (it.second as? PropView)?.act() }
            laneLayer.postDelayed({ l.go() }, if (Motion.reduced(this)) 0L else Motion.BASE)
        }
        stage.addView(dots, FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL))
        // In basso un solo gesto: tieni premuto e parla all'agente a fuoco (o, sul cerchio, creane uno).
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dpi(22f), dpi(12f), dpi(22f), dpi(12f)); background = roundRect(Theme.FOCUS, dp(26f)); isClickable = true
            addView(IconView(this@MainActivity, "mic", Theme.accent, Theme.ON_ACCENT), LinearLayout.LayoutParams(dpi(22f), dpi(22f)).apply { marginEnd = dpi(8f) })
            addView(pillText)
        }
        var talking = false
        var talkStartY = 0f
        var talkCancelled = false
        // Con TalkBack o la tastiera non si puo' "tenere premuto": un clic avvia l'ascolto senza tenere, un altro lo ferma e invia (come la doppia pressione del tasto laterale).
        pill.setOnClickListener { if (pttLatched) { pttLatched = false; pttStop() } else { pttLatched = true; pttStart() } }
        pill.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> { talkStartY = e.rawY; talkCancelled = false; focusAvatar()?.lookAt(0f, 20f, -1); v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(Motion.PRESS).start(); if (agents.getOrNull(sel) != null) { talking = true; homeVoiceGesture = true; homeVoiceStartY = e.rawY; pttStart() } }
                android.view.MotionEvent.ACTION_UP -> { focusAvatar()?.lookBack(); v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Theme.EASE_OUT).start(); if (talking) { talking = false; pttStop() } else if (!talkCancelled) open() }
                android.view.MotionEvent.ACTION_MOVE -> if (talking && talkStartY - e.rawY > dp(64f)) { talking = false; talkCancelled = true; pttClose(); v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.BASE).start(); snack(UiText.text(this@MainActivity, R.string.ui_4435ce3621)) }
                android.view.MotionEvent.ACTION_CANCEL -> { v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.BASE).start(); talking = false; talkCancelled = true; if (!homeVoiceGesture && !drainingHomeVoiceTouch) pttClose() }
            }
            true
        }
        stage.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(6f) })
        stage.addView(iconButton("more", Theme.accent, filled = false, size = 48f) { menu() }, FrameLayout.LayoutParams(dpi(48f), dpi(48f), Gravity.TOP or Gravity.END))

        // Dito: il carosello segue il dito (con un po' di resistenza ai capi) e al rilascio si ferma sull'agente piu' vicino, o va avanti di uno con uno scatto.
        // Rotella (r1-wheel-touch): un agente ogni ~36 dp. Tocco sul grande = apre la chat; su un vicino = lo porta al centro. Pressione lunga = spazio.
        var startPos = 0f; var startSel = 0; var total = 0f; var dragging = false; var flung = false; var isWheel = false; var acc = 0f; var moved = false; var petted = false
        fun indexAt(y: Float): Int = Math.round((y - stage.height / 2f) / step())
        val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean {
                acc = 0f; moved = false; total = 0f; dragging = false; flung = false; petted = false
                startPos = pos.value; startSel = sel
                isWheel = e.device?.name?.contains("wheel", ignoreCase = true) == true
                return true
            }
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
                if (isWheel) {
                    acc += r1WheelDelta(dx, dy, stage.display?.rotation ?: 0)
                    if (Math.abs(acc) >= dpi(36f)) { if (lanePos != 0) laneStep(if (acc > 0) 1 else -1) else goTo(sel + if (acc > 0) 1 else -1, fromWheel = true); acc = 0f; moved = true }
                } else if (lanePos != 0 || Math.abs(e2.x - (e1?.x ?: e2.x)) > Math.abs(e2.y - (e1?.y ?: e2.y)) * 1.4f && Math.abs(e2.x - (e1?.x ?: e2.x)) > dp(24f)) {
                    // gesto orizzontale o dentro una funzione: lo gestisce il fling, il carosello degli agenti resta fermo
                } else {
                    total = e2.y - (e1?.y ?: e2.y); dragging = true
                    var p = startPos - total / (step() * 0.8f)
                    val top = (n() - 1).toFloat()
                    if (p < 0f) p *= 0.35f else if (p > top) p = top + (p - top) * 0.35f
                    pos.snapTo(p)
                    val ax = e2.x - (e1?.x ?: e2.x)
                    focusAvatar()?.lookAt((ax / dp(40f)).coerceIn(-1f, 1f) * 8f, (total / dp(40f)).coerceIn(-1f, 1f) * 10f, -1, 90)
                    if (!petted && Math.abs(ax) > dp(90f) && Math.abs(total) < dp(40f)) { petted = true; focusAvatar()?.react("love") }
                }
                return true
            }
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { if (lanePos != 0) { laneAction(); return true }; val d = indexAt(e.y); if (d == 0) open() else goTo(sel + d); return true }
            override fun onLongPress(e: android.view.MotionEvent) { if (lanePos == 0 && indexAt(e.y) == 0) agents.getOrNull(sel)?.let { push(space(it)) } }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (isWheel || moved) return true
                if (Math.abs(vx) > 700f && Math.abs(vx) > Math.abs(vy) * 1.4f) { flung = true; laneStep(if (vx < 0) 1 else -1); return true }   // scorri a destra/sinistra: le altre funzioni
                if (lanePos != 0 && Math.abs(vy) > 600f && Math.abs(vy) > Math.abs(vx)) { flung = true; laneGo(0); return true }                 // su/giù dentro una funzione: torna all'agente
                if (Math.abs(vy) < 600f || Math.abs(vy) < Math.abs(vx)) return false
                flung = true; goTo(startSel + if (vy < 0) 1 else -1); return true
            }
        })
        stage.isClickable = true
        val sleeper = Sleeper(stage, { focusAvatar() }, { agents.getOrNull(sel)?.let { a -> focusAvatar()?.show(a) } }, { agents.getOrNull(sel)?.let { a -> focusAvatar()?.show(a, "sleeping") } }, afterProvider = ::idleMs)
        stage.post { sleeper.poke() }
        stage.setOnTouchListener { _, e ->
            sleeper.poke()
            val r = gd.onTouchEvent(e)
            if ((e.action == android.view.MotionEvent.ACTION_UP || e.action == android.view.MotionEvent.ACTION_CANCEL) && dragging) {
                dragging = false; focusAvatar()?.lookBack()
                if (!flung) { val d = pos.value - startSel; goTo(startSel + if (Math.abs(d) > 0.3f) (if (d > 0) 1 else -1) else 0) }
            }
            r
        }
        // Rotelle e mouse che mandano eventi di scorrimento (AXIS_VSCROLL / AXIS_HSCROLL).
        stage.setOnGenericMotionListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_SCROLL) {
                val v = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL).let { if (it != 0f) -it else -e.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL) }
                if (v != 0f) { if (lanePos != 0) laneStep(if (v > 0) 1 else -1) else goTo(sel + if (v > 0) 1 else -1, fromWheel = true); true } else false
            } else false
        }
        rebuild()
        stage.post { layout(pos.value); stage.post { layout(pos.value) } }
        // Hermes dal vivo: chi sta lavorando o ti aspetta lo dice sotto il nome e assume la posa giusta.
        val liveStates = HashMap<Long, String>()
        fun refreshLive() {
            if (!hermesMode() || !scope.alive) return
            Io.run {
                val live = dash.live()
                val states = HashMap<Long, String>()
                for (a in agents) for (c in db.chats(a.id)) { val st = live[db.hsid(c.id)]; if (st == "working" || (st == "waiting" && states[a.id] != "working")) states[a.id] = st!! }
                runOnUiThread {
                    if (!scope.alive) return@runOnUiThread
                    slides.forEach { (i, sl) ->
                        val a = agents.getOrNull(i) ?: return@forEach
                        val now = states[a.id]; val was = liveStates[a.id]
                        if (now == was) return@forEach
                        val roleV = sl.v.getChildAt(1) as? TextView
                        when (now) {
                            "working" -> { roleV?.text = UiText.text(this@MainActivity, R.string.ui_9449f5bbc8); roleV?.visibility = View.VISIBLE; sl.avatar?.setScenePose("working", "search") }
                            "waiting" -> { roleV?.text = UiText.text(this@MainActivity, R.string.ui_70ac6d7132); roleV?.visibility = View.VISIBLE; sl.avatar?.setScenePose("waiting"); if (!Motion.reduced(this@MainActivity)) sl.avatar?.react("surprised") }
                            else -> { roleV?.text = ""; roleV?.visibility = View.GONE; sl.avatar?.setScenePose("idle") }
                        }
                    }
                    liveStates.clear(); liveStates.putAll(states)
                }
            }
        }
        val liveLoop = object : Runnable { override fun run() { if (!scope.alive) return; if (visible && stack.lastOrNull()?.scope === scope) refreshLive(); stage.postDelayed(this, 15_000L) } }
        stage.postDelayed(liveLoop, 1500L)
        if (hermesMode()) {
            // Pallino di stato del collegamento con Hermes (verde pronto, giallo in collegamento, rosso errore, grigio spento).
            val dot = View(this)
            fun paintDot() {
                dot.background = roundRect(when (dash.state) { HermesDash.State.READY -> Theme.OK; HermesDash.State.CONNECTING -> Theme.colorOf("amber"); HermesDash.State.ERROR -> Theme.BAD; else -> Theme.SUBTLE }, dp(6f))
                (dot.tag as? android.animation.ObjectAnimator)?.cancel(); dot.alpha = 1f
                if (dash.state == HermesDash.State.CONNECTING) dot.tag = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply { duration = Motion.PULSE; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start() }
            }
            paintDot(); stopHomeState = dash.watchState { runOnUiThread { if (scope.alive) paintDot() } }
            // La pulsazione "in collegamento" è infinita: si ferma quando la home è coperta da un'altra pagina o chiusa, e riparte al ritorno.
            dot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { if (scope.alive) paintDot() }
                override fun onViewDetachedFromWindow(v: View) { (dot.tag as? android.animation.Animator)?.cancel() }
            })
            // Il pallino e' piccolo: sta in una zona da 40 dp che, toccata, dice a parole lo stato del collegamento.
            val dotBox = FrameLayout(this).apply {
                contentDescription = UiText.text(this@MainActivity, R.string.ui_62db8301e1)
                addView(dot, FrameLayout.LayoutParams(dpi(12f), dpi(12f), Gravity.CENTER))
                setOnClickListener { if (dash.state == HermesDash.State.ERROR || dash.state == HermesDash.State.OFF) sheet(UiText.text(this@MainActivity, R.string.ui_4814f6f7f6), listOf(UiText.text(this@MainActivity, R.string.ui_6ed04a5eb7) to { Io.run { runCatching { dash.profiles() } } }, UiText.text(this@MainActivity, R.string.ui_64ac619989) to { push(providerScreen()) })) else snack(UiText.text(this@MainActivity, when (dash.state) { HermesDash.State.READY -> R.string.ui_f24ee25561; else -> R.string.ui_7c1cae1c78 })) }
            }
            stage.addView(dotBox, FrameLayout.LayoutParams(dpi(48f), dpi(48f), Gravity.TOP or Gravity.END).apply { rightMargin = dpi(52f) })
        }
        // Prima volta con piu' di un agente: un suggerimento una volta sola.
        if (agents.size > 1 && !prefs.getBoolean("hint_home_seen", false)) stage.postDelayed({ if (scope.alive && stack.lastOrNull()?.scope === scope) { prefs.edit().putBoolean("hint_home_seen", true).apply(); snack(UiText.text(this@MainActivity, R.string.ui_d3aff5ed26), long = true) } }, 1800)
        // Una volta sola, anche con un agente solo: il carosello laterale e il tocco prolungato non si scoprono da soli.
        if (agents.isNotEmpty() && !packageName.endsWith(".qa") && prefs.getBoolean("hint_home_seen", false) && !prefs.getBoolean("hint_lane_seen", false)) stage.postDelayed({ if (scope.alive && stack.lastOrNull()?.scope === scope) { prefs.edit().putBoolean("hint_lane_seen", true).apply(); snack(UiText.text(this@MainActivity, R.string.ui_570d5b3ff8), long = true) } }, 2600L)
        val now = System.currentTimeMillis()
        if (hermesMode() && (agents.isEmpty() || now - lastProfileSync > 60_000L)) { lastProfileSync = now; syncProfiles(scope,
            { err -> if (agents.isEmpty()) { emptyNote = err; rebuild() } },
            { agents = visibleAgents(); emptyNote = null; rebuild() }) }
        var centerLong = false
        return Screen(view, { e ->
            // Dentro una funzione: click della rotella = esegui; tenuto premuto = torna all'agente (come il fling verticale col dito).
            if (lanePos != 0 && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
                if (e.action == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) centerLong = false else if (!centerLong) { centerLong = true; laneGo(0) } }
                else if (e.action == KeyEvent.ACTION_UP && !centerLong) laneAction()
                true
            } else
            if (e.action != KeyEvent.ACTION_DOWN) false else
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { laneStep(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { laneStep(1); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_UP, 260 -> { if (lanePos != 0) laneStep(-1) else goTo(sel - 1, fromWheel = true); true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 261 -> { if (lanePos != 0) laneStep(1) else goTo(sel + 1, fromWheel = true); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (lanePos != 0) laneAction() else open(); true }
                else -> false
            }
        }, buddyContext = BuddySceneContext.LIVE, scope = scope, isHome = true, onLeave = { stopHomeState?.invoke(); sleeper.stop(); homeLaneBack = null }, onShow = { laneLayer.alpha = 1f; refreshLive(); sleeper.poke(); agents = visibleAgents(); if (signature() != sig || homeIndex != sel) rebuild() else agents.getOrNull(sel)?.let { focusAvatar()?.show(it) } })
    }

    // ---- chat -----------------------------------------------------------------------------------

    /** Scelta del modello di una chat Hermes: prima il fornitore, poi il modello (da `model.options`). */
    internal fun pickModel(agent: Agent, chatId: Long, say: (String) -> Unit) {
        snack(UiText.text(this@MainActivity, R.string.ui_ead8aeac15))
        Io.run {
            val rows = try { dash.models(agent.profile) } catch (e: Exception) { runOnUiThread { snack(errorText(e), long = true) }; return@run }
            runOnUiThread {
                if (rows.isEmpty()) { snack(UiText.text(this@MainActivity, R.string.ui_473ec0d778), long = true); return@runOnUiThread }
                fun apply(r: HermesDash.ModelRow, confirm: Boolean = false): Unit = Io.run {
                    val res = try { dash.setModel(chatId, agent.profile, r, confirm) } catch (e: Exception) { UiText.text(this@MainActivity, R.string.ui_589110f583, errorText(e)) }
                    runOnUiThread { if (res == "CONFERMA") sheet(UiText.text(this@MainActivity, R.string.ui_99f20c7f7e), listOf(UiText.text(this@MainActivity, R.string.ui_8e62ad1ad1, r.model) to { apply(r, true) })) else say(UiText.text(this@MainActivity, R.string.ui_e308e43542, res)) }
                }
                val provs = rows.groupBy { it.provider }.entries.sortedByDescending { e -> e.value.any { it.current } }
                // Fornitori (quello in uso, con la spunta, per primo); toccandone uno si aprono i suoi modelli, con ricerca.
                sheetOf(UiText.text(this@MainActivity, R.string.ui_64ac619989), provs.map { (_, ms) ->
                    SheetItem(ms.first().providerName, sub = UiText.text(this@MainActivity, R.string.ui_1f6c580980, ms.size), icon = "spark", selected = ms.any { it.current }) {
                        sheetOf(ms.first().providerName.lowercase(), ms.take(120).map { m -> SheetItem(m.model, selected = m.current) { apply(m) } })
                    }
                })
            }
        }
    }

    // Una scheda dell'agente (show_card): barra del colore a sinistra, titolo e testo in markdown su fondo scuro.
    internal fun cardView(agent: Agent, c: org.json.JSONObject): View {
        val col = Theme.colorOf(c.optString("color").ifBlank { agent.color })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(14f)) }
        body.addView(text(20f, 800).keepCase().apply { text = c.optString("title"); letterSpacing = -0.01f })
        body.addView(text(16f, 500).keepCase().apply { setLineSpacing(0f, 1.25f); setPadding(0, dpi(6f), 0, 0); text = Md.render(c.optString("body"), Theme.withAlpha(Theme.FG, 0.14f)) })
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = roundRect(Theme.CARD, dp(20f))
            addView(View(this@MainActivity).apply { background = roundRect(col, dp(2f)) }, LinearLayout.LayoutParams(dpi(5f), -1).apply { setMargins(dpi(10f), dpi(12f), 0, dpi(12f)) })
            addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    internal fun chat(agent: Agent, chatId: Long, say: String? = null, share: Share? = null, observeHsid: String? = null): Screen {
        val chatScope = Scope()
        var engine: Engine? = null
        var stopTaskWatch: (() -> Unit)? = null
        var bindHermes: (HermesRuntime.Task, TextView) -> Unit = { _, _ -> }
        var chatFeedback: (String, String) -> Unit = { _, _ -> }
        val accent = Theme.colorOf(agent.color)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(4f), 0, dpi(8f)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dpi(28f)); addView(list, -1, -2) }
        var busy = false

        // Come ChatScreen.bubble di r1os: Rubik 18, angoli 20 con quello verso il suo lato a 6.
        fun bubble(mine: Boolean): TextView = text(18f, 500, if (mine) Theme.ON_ACCENT else Theme.FG).keepCase().apply {
            setOnLongClickListener {
                val raw = (tag as? String) ?: text.toString()
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(UiText.text(this@MainActivity, R.string.ui_151a6c958d), raw))
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                snack(UiText.text(this@MainActivity, R.string.ui_2eba76fd11)); true
            }
            setPadding(dpi(15f), dpi(12f), dpi(15f), dpi(12f)); setLineSpacing(0f, 1.28f)
            val r = dp(20f); val s = dp(6f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadii = if (mine) floatArrayOf(r, r, r, r, s, s, r, r) else floatArrayOf(r, r, r, r, r, r, s, s)
                setColor(if (mine) accent else Theme.CARD)
            }
        }
        var lastMine: Boolean? = null
        fun lp(mine: Boolean) = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = if (lastMine == mine) dpi(4f) else dpi(12f); lastMine = mine; gravity = if (mine) Gravity.END else Gravity.START
            if (mine) marginStart = dpi(36f) else marginEnd = dpi(24f)
        }
        val codeBg = Theme.withAlpha(Theme.FG, 0.14f)
        val chipRows = java.util.WeakHashMap<View, LinearLayout>()
        val mdCaches = java.util.WeakHashMap<View, Md.Cache>()
        fun actionChips(v: View, acts: List<DeviceActions.Action>) {
            if (acts.isEmpty()) return
            val row = chipRows.getOrPut(v) { LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL } }
            val have = (0 until row.childCount).map { row.getChildAt(it).tag as? String }
            acts.filter { it.key !in have }.forEach { a -> row.addView(button(describeAction(a), accent, primary = false, small = true) { reportAction(a) }.apply { tag = a.key }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) }) }
            fun attach() { if (row.parent == null) { val idx = list.indexOfChild(v); if (idx >= 0) list.addView(row, idx + 1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4f); gravity = Gravity.START }) } }
            attach(); v.post { attach() }
        }
        fun setMsg(v: TextView, raw: String, mine: Boolean, error: Boolean = false) {
            v.tag = raw
            if (error) { v.text = UiText.owned(this@MainActivity, raw); return }
            if (mine) { v.text = raw; return }
            val p = DeviceActions.parse(raw)
            val body = p.text.ifBlank { p.actions.joinToString(", ") { describeAction(it) } }
            val mc = mdCaches.getOrPut(v) { Md.Cache() }
            v.text = Md.render(body, codeBg, mc)
            if (body.contains("```")) v.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            actionChips(v, p.actions)
        }
        // Chat vuota: invece di una pagina nera, un invito con l'agente (sparisce al primo messaggio).
        var emptyHint: View? = null
        var fillInput: (String) -> Unit = {}
        fun add(t: String, mine: Boolean, error: Boolean = false): TextView = bubble(mine).apply {
            emptyHint?.let { list.removeView(it); emptyHint = null }
            setMsg(this, t, mine, error); if (error) { setTextColor(Theme.BAD); post { Motion.shake(this) } }
            list.addView(this, lp(mine))
            if (mine) Motion.enter(this, dx = dp(10f), dy = dp(40f), dur = Motion.SLOW) // il messaggio inviato sale dal campo di testo
            else Motion.enter(this, dx = -dp(18f), dy = dp(12f), dur = Motion.BASE)
        }
        var unreadCue: () -> Unit = {}
        val latest = button(UiText.text(this@MainActivity, R.string.ui_c85846ed60), accent, primary = false, small = true) {}.apply { visibility = View.GONE }
        fun nearBottom() = scroll.scrollY + scroll.height >= list.height - dpi(80f)
        fun bottom(smooth: Boolean = false) = scroll.post { if (smooth && !Motion.reduced(this)) scroll.smoothScrollTo(0, list.height) else scroll.scrollTo(0, list.height); Motion.hide(latest) }
        fun updated(follow: Boolean) {
            if (follow) bottom(true) else if (latest.visibility != View.VISIBLE) {
                Motion.show(latest); unreadCue()
            }
        }
        latest.setOnClickListener { bottom(true) }
        fun cardLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f); marginEnd = dpi(12f) }
        fun note(t: String) {
            list.addView(text(14f, 500, Theme.MUTED).keepCase().apply { text = t; gravity = Gravity.CENTER; setPadding(dpi(8f), dpi(6f), dpi(8f), dpi(6f)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
            bottom()
        }
        fun runCmd(cmd: String) {
            note("$cmd …")
            Io.run {
                val out = try { dash.command(chatId, agent.profile, cmd) } catch (e: Exception) { UiText.text(this@MainActivity, R.string.ui_2f38b99da3, UiText.message(this@MainActivity, e.message)) }
                runOnUiThread { note(out.take(1500)) }
            }
        }
        fun showCommands() = sheet(UiText.text(this@MainActivity, R.string.ui_bcfaf5fac6), listOf(
            UiText.text(this@MainActivity, R.string.ui_03fdb016df) to { runCmd("/compress") },
            UiText.text(this@MainActivity, R.string.ui_68cbd39916) to { runCmd("/undo") },
            UiText.text(this@MainActivity, R.string.ui_d4c120052e) to { askText(UiText.text(this@MainActivity, R.string.ui_9b4e9826bc), UiText.text(this@MainActivity, R.string.ui_cbaa97c396)) { runCmd("/model $it") } },
            UiText.text(this@MainActivity, R.string.ui_391532bba7) to { askText(UiText.text(this@MainActivity, R.string.ui_ed549af454), UiText.text(this@MainActivity, R.string.ui_166425b50b)) { runCmd(it) } }))

        // Cronologia a finestra: solo gli ultimi 40 messaggi; i più vecchi si caricano a richiesta (nessuna vista per ogni messaggio di una sessione lunga).
        val pageSize = 40
        var oldestId = Long.MAX_VALUE
        val moreBtn = button(UiText.text(this@MainActivity, R.string.ui_889569e420), accent, primary = false, small = true) {}
        fun textOf(c: String): String {
            val arr = JSONArray(c); val sb = StringBuilder()
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { b ->
                if (b.getString("type") == "text") sb.append(b.optString("text"))
                else if (b.getString("type") == "attachment") sb.append("\n📎 ").append(b.optString("name"))
            }
            return sb.toString().trim()
        }
        fun loadOlder() {
            val page = db.messagesPage(chatId, pageSize + 1, if (oldestId == Long.MAX_VALUE) null else oldestId)
            val more = page.size > pageSize
            val shown = if (more) page.drop(1) else page
            val before = list.height
            var pos = if (moreBtn.parent != null) 1 else 0
            for ((id, role, c) in shown) {
                oldestId = minOf(oldestId, id)
                val t = textOf(c)
                if (t.isNotBlank()) list.addView(bubble(role == "user").also { setMsg(it, t, role == "user") }, pos++, lp(role == "user"))
                if (role != "user") runCatching { JSONArray(c) }.getOrNull()?.let { arr ->
                    for (k in 0 until arr.length()) arr.getJSONObject(k).let { b -> if (b.optString("type") == "tool_use" && b.optString("name") == "show_card") list.addView(cardView(agent, b.optJSONObject("input") ?: org.json.JSONObject()), pos++, cardLp()) }
                }
            }
            if (more && moreBtn.parent == null) list.addView(moreBtn, 0, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(4f) })
            if (!more && moreBtn.parent != null) list.removeView(moreBtn)
            if (before > 0) list.post { scroll.scrollTo(0, scroll.scrollY + (list.height - before)) }
        }
        moreBtn.setOnClickListener { loadOlder() }
        var first = db.messagesPage(chatId, 1, null).isEmpty()
        loadOlder()
        if (first && observeHsid == null && say == null && share == null) {
            emptyHint = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dpi(24f), dpi(40f), dpi(24f), 0)
                addView(text(24f, 800).apply { text = UiText.text(this@MainActivity, R.string.ui_d5f4254e31, agent.name); gravity = Gravity.CENTER; setPadding(0, dpi(10f), 0, dpi(4f)) })
                addView(text(15f, 500, Theme.MUTED).keepCase().apply { text = UiText.text(this@MainActivity, R.string.ui_ccd94418dd); gravity = Gravity.CENTER })
                // Tre idee per cominciare: toccandone una finisce nel campo (non si invia da sola).
                listOf(R.string.ui_9d6297af1f, R.string.ui_48b918b31b, R.string.ui_591845331b).forEach { r -> val idea = UiText.text(this@MainActivity, r)
                    addView(button(idea, accent, small = true) { fillInput(idea) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6f) }) }
            }
            list.addView(emptyHint)
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val headChar = CharacterView(this, dpi(56f)).also { it.show(agent); it.scaleX = 36f / 56f; it.scaleY = 36f / 56f }
        val avatarSlot = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false; isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
            contentDescription = UiText.text(this@MainActivity, R.string.ui_799d550ed7, agent.name)
            addView(headChar, FrameLayout.LayoutParams(dpi(56f), dpi(56f), Gravity.CENTER))
        }
        var avatarExpanded = false
        var avatarMotion: android.animation.ValueAnimator? = null
        fun resizeAvatar(expanded: Boolean) {
            avatarExpanded = expanded
            avatarSlot.contentDescription = if (expanded) UiText.text(this@MainActivity, R.string.ui_0d423ada7a, agent.name) else UiText.text(this@MainActivity, R.string.ui_799d550ed7, agent.name)
            avatarMotion?.cancel()
            val start = avatarSlot.layoutParams.height
            val target = dpi(if (expanded) 64f else 48f)
            val fromScale = headChar.scaleX
            val toScale = if (expanded) 1f else 36f / 56f
            avatarMotion = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.BASE; interpolator = Theme.SETTLE
                addUpdateListener { animation ->
                    val t = animation.animatedValue as Float
                    avatarSlot.layoutParams = avatarSlot.layoutParams.apply { height = (start + (target - start) * t).toInt() }
                    val scale = fromScale + (toScale - fromScale) * t
                    headChar.scaleX = scale; headChar.scaleY = scale
                }
                start()
            }
        }
        val collapseAvatar = Runnable { resizeAvatar(false) }
        avatarSlot.setOnClickListener {
            avatarSlot.removeCallbacks(collapseAvatar)
            resizeAvatar(!avatarExpanded)
            if (avatarExpanded) avatarSlot.postDelayed(collapseAvatar, 4000)
        }
        val backBtn = iconButton("back", accent, filled = false, size = 48f) { pop() }
        val nameV = text(22f, 800).apply { letterSpacing = -0.02f; text = agent.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dpi(8f), 0, 0, 0); setOnClickListener { if (!busy) push(space(agent)) } }
        head.addView(backBtn)
        head.addView(avatarSlot, LinearLayout.LayoutParams(dpi(48f), dpi(48f)).apply { marginStart = dpi(4f) })
        head.addView(nameV, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var openAttach: () -> Unit
        val menuBtn = iconButton("more", accent, filled = false, size = 48f) {
            val items = ArrayList<Pair<String, () -> Unit>>()
            items += UiText.text(this@MainActivity, R.string.ui_7f4849bfb0) to { if (!busy) replaceTop(chat(agent, db.newChat(agent.id))) else snack(UiText.text(this@MainActivity, R.string.ui_8cec984fbd)) }
            items += UiText.text(this@MainActivity, R.string.ui_0fd7b04a53) to { openAttach() }
            items += (if (!pinEnabled()) UiText.text(this@MainActivity, R.string.ui_dc815e9fc7) else if (chatId.toString() in lockedChats()) UiText.text(this@MainActivity, R.string.ui_9d86e57fdb) else UiText.text(this@MainActivity, R.string.ui_768a2cb129)) to {
                if (!pinEnabled()) pinSetup() else { val on = chatId.toString() !in lockedChats(); setChatLocked(chatId, on); if (on) pinUnlocked = true; cue(if (on) "nod" else "wink", force = true) }
            }
            val hs = db.hsid(chatId)
            if (hermesMode() && agent.profile.isNotEmpty()) {
                items += UiText.text(this@MainActivity, R.string.ui_eef051cd40) to { askText(UiText.text(this@MainActivity, R.string.ui_00c9325b04), UiText.text(this@MainActivity, R.string.ui_a87537ff47)) { t -> Io.run { try { dash.rename(chatId, agent.profile, t); db.title(chatId, t.take(60)); runOnUiThread { snack(UiText.text(this@MainActivity, R.string.ui_9ea5d517e7)) } } catch (e: Exception) { runOnUiThread { snack(errorText(e), long = true) } } } } }
                items += UiText.text(this@MainActivity, R.string.ui_6bec3945e2) to { pickModel(agent, chatId) { m -> note(m) } }
                items += UiText.text(this@MainActivity, R.string.ui_03fdb016df) to { note(UiText.text(this@MainActivity, R.string.ui_9c76b629d1)); Io.run { val r = try { dash.compress(chatId, agent.profile) } catch (e: Exception) { UiText.text(this@MainActivity, R.string.ui_589110f583, errorText(e)) }; runOnUiThread { note(UiText.text(this@MainActivity, R.string.ui_12aa930947, r)) } } }
                if (hs.isNotEmpty()) items += UiText.text(this@MainActivity, R.string.ui_09af77c5ae) to {
                    snack(UiText.text(this@MainActivity, R.string.ui_7f784011b7))
                    Io.run { try { val nh = dash.branch(hs, agent.profile); val id = db.upsertChat(agent.id, nh, (db.chats(agent.id).firstOrNull { it.id == chatId }?.title ?: "chat").take(50) + UiText.text(this@MainActivity, R.string.ui_798b410cf8), System.currentTimeMillis(), 0); runOnUiThread { if (chatScope.alive) openChat(agent, id) } } catch (e: Exception) { runOnUiThread { if (chatScope.alive) snack(errorText(e), long = true) } } }
                }
                if (hs.isNotEmpty() && !busy) items += UiText.text(this@MainActivity, R.string.ui_2e5a083bde) to { confirmTap("archive$chatId") {
                    chatFeedback("upload", UiText.text(this@MainActivity, R.string.ui_f3596da8e1))
                    Io.run {
                        try {
                            dash.archive(hs, agent.profile)
                            dash.forget(chatId); db.deleteChat(chatId)
                            runOnUiThread { if (chatScope.alive) pop() }
                        } catch (e: Exception) { runOnUiThread { if (chatScope.alive) chatFeedback("error", UiText.text(this@MainActivity, R.string.ui_0f2784f610)) } }
                    }
                } }
                items += UiText.text(this@MainActivity, R.string.ui_5f7b655465) to { Io.run { val subs = try { dash.subagents(chatId, agent.profile) } catch (e: Exception) { runOnUiThread { snack(errorText(e), long = true) }; return@run }
                    runOnUiThread {
                        if (subs.isEmpty()) { snack(UiText.text(this@MainActivity, R.string.ui_d2ba27adf3)); return@runOnUiThread }
                        sheet(UiText.text(this@MainActivity, R.string.ui_5f7b655465), subs.map { sa -> ("${sa.goal.take(44)} · ${sa.status}" ) to {
                            sheet(sa.goal.take(30), listOfNotNull(UiText.text(this@MainActivity, R.string.ui_78be0e73b2) to { Io.run { val ok = runCatching { dash.stopSub(chatId, agent.profile, sa.id) }.getOrDefault(false); runOnUiThread { note(if (ok) UiText.text(this@MainActivity, R.string.ui_12fe7179c8) else UiText.text(this@MainActivity, R.string.ui_b26f1e7af3)) } } },
                                if (sa.canSteer) UiText.text(this@MainActivity, R.string.ui_b6a0d0a674) to { askText(UiText.text(this@MainActivity, R.string.ui_217f40a226), UiText.text(this@MainActivity, R.string.ui_04f5089419)) { t -> Io.run { val ok = runCatching { dash.steerSub(chatId, agent.profile, sa.id, t) }.getOrDefault(false); runOnUiThread { note(if (ok) UiText.text(this@MainActivity, R.string.ui_550a3f682d) else UiText.text(this@MainActivity, R.string.ui_f18cdbd92f)) } } } } else null),
                                mapOf(UiText.text(this@MainActivity, R.string.ui_78be0e73b2) to "close", UiText.text(this@MainActivity, R.string.ui_b6a0d0a674) to "chat"))
                        } })
                    } } }
                items += UiText.text(this@MainActivity, R.string.ui_5f694433cb) to { showCommands() }
            }
            sheet("chat", items, danger = setOf(UiText.text(this@MainActivity, R.string.ui_2e5a083bde)), icons = mapOf(UiText.text(this@MainActivity, R.string.ui_7f4849bfb0) to "plus", UiText.text(this@MainActivity, R.string.ui_0fd7b04a53) to "attach", UiText.text(this@MainActivity, R.string.ui_eef051cd40) to "note", UiText.text(this@MainActivity, R.string.ui_6bec3945e2) to "spark", UiText.text(this@MainActivity, R.string.ui_03fdb016df) to "sliders", UiText.text(this@MainActivity, R.string.ui_09af77c5ae) to "repeat", UiText.text(this@MainActivity, R.string.ui_2e5a083bde) to UiText.text(this@MainActivity, R.string.ui_a17c9aaa61), UiText.text(this@MainActivity, R.string.ui_5f7b655465) to "agent", UiText.text(this@MainActivity, R.string.ui_5f694433cb) to "more"))
        }
        head.addView(menuBtn)

        unreadCue = { headChar.lookAt(0f, 12f, 1200); headChar.react("wink") }
        val buddyStage = BuddyStage(this, agent)
        chatFeedback = { kind, label -> buddyStage.flash(kind, label, 2800) }
        var connectionAlive = true
        var previousConnection = dash.state
        fun connectionChanged(state: HermesDash.State) {
            if (!connectionAlive || !hermesMode()) return
            val hadConnectionNotice = buddyStage.mode in listOf("reconnect", "offline")
            if (state == HermesDash.State.READY) {
                if (hadConnectionNotice) buddyStage.flash("connected", UiText.text(this@MainActivity, R.string.ui_f24ee25561))
            } else if (buddyStage.mode == "idle" || hadConnectionNotice) {
                buddyStage.show(if (state == HermesDash.State.CONNECTING) "reconnect" else "offline", if (state == HermesDash.State.CONNECTING) UiText.text(this@MainActivity, R.string.ui_7c1cae1c78) else UiText.text(this@MainActivity, R.string.ui_4814f6f7f6))
            }
            previousConnection = state
        }
        val stopConnectionWatch = if (hermesMode()) dash.watchState { state -> runOnUiThread { if (state != previousConnection) connectionChanged(state) } } else ({})
        buddyStage.post { if (dash.state != HermesDash.State.READY) connectionChanged(dash.state) }
        var updateAction: () -> Unit = {}
        // Allegati in attesa di essere inviati: miniature a pillola sopra la barra, un tocco le toglie.
        val pending = ArrayList<Attachment>()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(chips) }
        fun refreshChips() {
            chips.removeAllViews()
            pending.toList().forEach { a ->
                chips.addView(text(14f, 600).keepCase().apply {
                    text = "📎 ${a.name.take(22)}  ✕"; setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f)); background = roundRect(Theme.CARD, dp(14f)); isClickable = true
                    setOnClickListener { confirmTap("att${a.name}") { pending.remove(a); a.file.delete(); refreshChips(); if (buddyStage.mode in listOf("file", "photo")) buddyStage.dismiss() } }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) })
            }
            if (pending.isEmpty()) Motion.hide(chipsScroll) else Motion.show(chipsScroll)
            updateAction()
        }
        attachHook = { a -> runOnUiThread { pending.add(a); refreshChips(); Motion.enter(chipsScroll, dx = -dp(14f), dy = dp(6f), dur = Motion.BASE); headChar.lookAt(-9f, 9f, 900); buddyStage.flash(if (a.kind == Kind.IMAGE) "photo" else "file", UiText.text(this@MainActivity, R.string.ui_da45045b61, a.name)) } }
        val activity = text(14f, 600, Theme.MUTED).keepCase().apply { visibility = View.GONE; gravity = Gravity.CENTER; setPadding(dpi(4f), dpi(6f), dpi(4f), dpi(4f)); maxLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }   // TalkBack annuncia "sto cercando...", "leggo il file..."
        var lastAct = ""
        // Riga di stato sotto la conversazione: solo mentre l'agente lavora, mai a riposo (come r1os).
        fun setActivity(s: String) {
            if (busy) {
                Motion.crossfade(activity, UiText.owned(this@MainActivity, s.ifBlank { UiText.text(this@MainActivity, R.string.ui_3433289441, agent.name) })); Motion.show(activity)
                val visual = BuddyVisualState.fromActivity(s)
                val kind = if (s.isBlank() && buddyStage.mode == "upload") "done" else visual.kind
                buddyStage.show(kind, UiText.owned(this@MainActivity, s.ifBlank { UiText.text(this@MainActivity, R.string.ui_3433289441, agent.name) }), s)
                if (s.isBlank()) headChar.show(agent, "speaking") else { if (s != lastAct) headChar.react("bounce"); headChar.show(agent, "working", activityKind(s)) }
                lastAct = s
            }
            else Motion.hide(activity)
        }
        val input = field(UiText.text(this@MainActivity, R.string.ui_3265eeab7c), "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setSingleLine(false); setHorizontallyScrolling(false); maxLines = 4   // cresce fino a quattro righe (impostare inputType dopo riporterebbe a una riga sola)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI; background = roundRect(Theme.CARD, dp(26f)); setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f)) }
        fillInput = { t -> input.setText(t); input.setSelection(t.length); input.requestFocus() }
        input.addTextChangedListener(object : android.text.TextWatcher { override fun afterTextChanged(s: android.text.Editable?) {}; override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { if (!busy) headChar.lookAt(0f, 9f, 900); updateAction() } })
        var draftBeforeVoice: String? = null
        fun reviewVoice(transcript: String): Unit {
            if (transcript.isBlank() || busy) return
            if (draftBeforeVoice == null) draftBeforeVoice = input.text.toString()
            input.setSingleLine(false); input.maxLines = 3
            input.setText(appendTranscript(input.text.toString(), transcript)); input.setSelection(input.text.length)
            headChar.show(agent, "idle"); headChar.react("wink")
            buddyStage.review {
                input.setText(draftBeforeVoice.orEmpty()); draftBeforeVoice = null
                input.setSingleLine(true); buddyStage.dismiss()
            }
        }
        val send = iconButton("send", accent, size = 52f) {}
        var lastUser = ""
        lateinit var stopBtn: View
        lateinit var sendBtn: View
        var lastAtts: List<Attachment> = emptyList()
        fun submit(t: String, retry: Boolean = false) {
            if (busy || (t.isBlank() && pending.isEmpty() && !retry)) return
            if (!retry && pending.isEmpty() && tryOpenApp(t)) { input.setText(""); return }
            if (hermesRuntime.isConfiguring()) { snack(UiText.text(this@MainActivity, R.string.ui_b121797238)); return }
            if (hermesRuntime.active(chatId) != null) { snack(UiText.text(this@MainActivity, R.string.ui_c723f3fd80)); openChat(agent, chatId); return }
            val atts = if (retry) lastAtts else pending.toList()
            draftBeforeVoice = null; input.setSingleLine(true)
            lastUser = t; lastAtts = atts
            askNotificationsOnce(); list.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (!retry) { pending.clear(); refreshChips() }
            if (first) { first = false; db.title(chatId, t.ifBlank { atts.firstOrNull()?.name ?: "allegato" }.replace('\n', ' ').take(40)) }
            busy = true; input.setText(""); if (!retry) add((t + atts.joinToString("") { "\n📎 ${it.name}" }).trim(), true); headChar.tag = null; headChar.show(agent, "thinking"); headChar.react("bounce"); updateAction(); setActivity(""); lastAct = ""
            buddyStage.show(if (atts.isEmpty()) "send" else "send_file", if (atts.isEmpty()) UiText.text(this@MainActivity, R.string.ui_bef0afceff) else UiText.text(this@MainActivity, R.string.ui_c767defaed, atts.size))
            buddyStage.postDelayed({ if (busy && buddyStage.mode in listOf("send", "send_file")) setActivity(lastAct) }, 450)
            val reply = add("", false).apply { setCompoundDrawablesRelativeWithIntrinsicBounds(TypingDots(this@MainActivity), null, null, null) }; bottom()
            if (hermesMode()) {
                bindHermes(hermesRuntime.start(agent, chatId, t, retry, atts), reply)
                return
            }
            // Gli aggiornamenti del testo si raggruppano ogni 50 ms (prima: una riassegnazione e uno scorrimento per ogni parola).
            val turnEngine = localEngine(); engine = turnEngine
            val acc = StringBuilder()
            val got = java.util.concurrent.atomic.AtomicBoolean(false)
            val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
            val flush = Runnable {
                scheduled.set(false)
                if (!chatScope.alive || engine !== turnEngine) return@Runnable
                val s = synchronized(acc) { acc.toString() }
                val near = nearBottom()
                reply.setCompoundDrawablesRelative(null, null, null, null)
                setMsg(reply, s, false)
                updated(near)
            }
            Io.run {
                try {
                    turnEngine.turn(agent, chatId, t, retry, onActivity = { s -> runOnUiThread { if (chatScope.alive && engine === turnEngine) setActivity(s) } }, attachments = atts, onCard = { c -> runOnUiThread { if (!chatScope.alive || engine !== turnEngine) return@runOnUiThread; val follow = nearBottom(); val at = list.indexOfChild(reply); list.addView(cardView(agent, c), if (at >= 0) at else list.childCount, cardLp()); headChar.react("love"); updated(follow) } }) { d ->
                        synchronized(acc) { acc.append(d) }
                        if (!got.getAndSet(true)) runOnUiThread { if (chatScope.alive && engine === turnEngine) { buddyStage.dismiss(); headChar.show(agent, "speaking") } }
                        if (scheduled.compareAndSet(false, true)) scroll.postDelayed(flush, 50)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!chatScope.alive || engine !== turnEngine) return@runOnUiThread
                        buddyStage.flash("error", UiText.text(this@MainActivity, R.string.ui_1410a06bb9), 2400); headChar.tag = "x"; headChar.show(agent, "error"); headChar.react("surprised"); if (!got.get()) list.removeView(reply)
                        add(errorText(e) + UiText.text(this@MainActivity, R.string.ui_8eb654d9af), false, error = true).setOnClickListener { v -> if (!busy) { list.removeView(v); submit(lastUser, retry = true) } }
                        bottom()
                    }
                }
                localTurns.remove(turnEngine)
                if (!chatScope.alive || engine !== turnEngine) return@run
                runOnUiThread { flush.run() }
                if (got.get() && !turnEngine.isCancelled && visible && (!gateWanted() || pinUnlocked) && prefs.getBoolean("tts", false)) { val spoken = synchronized(acc) { acc.toString() }; runOnUiThread { if (chatScope.alive && engine === turnEngine) speak(spoken) } }
                runOnUiThread { if (!chatScope.alive || engine !== turnEngine) return@runOnUiThread; busy = false; Motion.hide(activity); updateAction(); if (headChar.tag != "x") { buddyStage.dismiss(); headChar.show(agent, "idle"); if (got.get()) headChar.react("giggle") }; if (!got.get() && reply.parent != null && reply.text.isEmpty()) list.removeView(reply) }
            }
        }
        sendBtn = send; stopBtn = iconButton("close", Theme.BAD, size = 52f) { if (hermesRuntime.active(chatId) != null) hermesRuntime.cancel(chatId) else engine?.cancel(); val m = UiText.text(this@MainActivity, R.string.ui_406565f0d9); snack(m); buddyStage.flash("cancel", m, 2400) }.apply { visibility = View.GONE }
        send.setOnClickListener { submit(input.text.toString()) }
        input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { submit(input.text.toString()); true } else false }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val listen = text(16f, 600, Theme.MUTED).keepCase().apply { visibility = View.GONE; setPadding(dpi(4f), dpi(6f), 0, dpi(6f)); maxLines = 2 }
        var voiceCancelled = false
        var recording = false
        var voiceActive = false
        var micPhase = MicPhase.LISTENING
        val voice = newMic({ if (!voiceCancelled) { listen.text = if (micPhase == MicPhase.TRANSCRIBING) it else UiText.text(this@MainActivity, R.string.ui_60da80eba1, it); buddyStage.show(if (micPhase == MicPhase.TRANSCRIBING || !recording) "transcribing" else "listening", listen.text.toString()) } }, { if (!voiceCancelled) { voiceActive = false; recording = false; Motion.hide(listen); buddyStage.dismiss(); reviewVoice(it) } },
            { err -> if (!voiceCancelled) { voiceActive = false; recording = false; Motion.hide(listen); buddyStage.flash(if (err == null) "cancel" else "error", err ?: UiText.text(this@MainActivity, R.string.ui_1b534b080c)); if (!busy) headChar.show(agent, "idle"); if (err != null) add(err, false, error = true) } })
        val suspendVoice = { voiceCancelled = true; voiceActive = false; recording = false; voice.cancel(); Motion.hide(listen); buddyStage.dismiss(); if (!busy) headChar.show(agent, "idle") }
        cancelActiveMic = suspendVoice
        voice.onPhase { phase -> micPhase = phase; if (!voiceCancelled && phase == MicPhase.TRANSCRIBING) buddyStage.show("transcribing", UiText.text(this@MainActivity, R.string.ui_79b0b7c1f7)) }
        voice.onLevel { buddyStage.level(it) }
        onVoice = { if (!voiceCancelled) reviewVoice(it) }
        fun talk() {
            if (busy) return
            voiceCancelled = false
            if (voice.start()) { voiceActive = true; recording = true; listen.text = UiText.text(this@MainActivity, R.string.ui_3afbd0834d); Motion.show(listen); buddyStage.show("listening", listen.text.toString()); headChar.show(agent, "listening") }
            else if (!voice.startFailed && packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppLanguage.locale(this).toLanguageTag()), 7)
            else if (!voice.startFailed && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) add(UiText.text(this@MainActivity, R.string.ui_4d225fc3f1), false, error = true)
        }
        pttHandler = { e -> if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) talk() else if (e.action == KeyEvent.ACTION_UP && recording) { recording = false; buddyStage.show("transcribing", UiText.text(this@MainActivity, R.string.ui_79b0b7c1f7)); voice.stop() }; true }
        // Gli agganci di QUESTA chat: quando una chat viene sostituita (rilettura da Hermes) o ne copre un'altra, la vecchia azzerava
        // quelli della nuova (PTT, voce, allegati morti). Ora ognuna toglie solo i suoi e li rimette quando torna in cima.
        val myVoice = onVoice; val myMic = cancelActiveMic; val myPtt = pttHandler; val myAttach = attachHook
        fun restoreHooks() { onVoice = myVoice; cancelActiveMic = myMic; pttHandler = myPtt; attachHook = myAttach }
        openAttach = { headChar.lookAt(-10f, 10f, 1200); sheet(UiText.text(this@MainActivity, R.string.ui_0fd7b04a53), listOf(UiText.text(this@MainActivity, R.string.ui_aaa045009b) to { pickPhoto() }, UiText.text(this@MainActivity, R.string.ui_9ef61a89bf) to { pickFile(true) }, UiText.text(this@MainActivity, R.string.ui_7656d6d3ad) to { pickFile(false) }), mapOf(UiText.text(this@MainActivity, R.string.ui_aaa045009b) to "attach", UiText.text(this@MainActivity, R.string.ui_9ef61a89bf) to "look", UiText.text(this@MainActivity, R.string.ui_7656d6d3ad) to "note")) }
        val attachBtn = iconButton("attach", accent, filled = false, size = 48f) { openAttach() }
        // Un solo tasto d'azione a destra: microfono (tieni premuto e parla) a campo vuoto, invio con del testo, stop mentre lavora.
        val mic = iconButton("mic", accent, size = 52f) {}
        var voiceStartY = 0f
        fun cancelVoice() {
            voiceCancelled = true; recording = false; voice.cancel(); Motion.hide(listen); buddyStage.flash("cancel", UiText.text(this@MainActivity, R.string.ui_4435ce3621))
            if (!busy) headChar.show(agent, "idle")
        }
        // TalkBack, tastiera e rotella non possono "tenere premuto": un clic avvia l'ascolto, il clic successivo lo ferma e trascrive.
        mic.setOnClickListener {
            if (recording && !voiceCancelled) { recording = false; buddyStage.show("transcribing", UiText.text(this@MainActivity, R.string.ui_79b0b7c1f7)); voice.stop() } else talk()
        }
        mic.setOnTouchListener { v, e ->
            Motion.touch(v, e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { voiceStartY = e.rawY; talk() }
                android.view.MotionEvent.ACTION_MOVE -> if (!voiceCancelled) { buddyStage.followCancel((voiceStartY - e.rawY) / dp(64f)); if (voiceStartY - e.rawY > dp(64f)) cancelVoice() }
                android.view.MotionEvent.ACTION_UP -> if (!voiceCancelled && recording) { recording = false; buddyStage.show("transcribing", UiText.text(this@MainActivity, R.string.ui_79b0b7c1f7)); voice.stop() }
                android.view.MotionEvent.ACTION_CANCEL -> cancelVoice()
            }
            v.isPressed = !voiceCancelled && (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_MOVE)
            true
        }
        bar.addView(attachBtn, 0, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) })
        val actions = FrameLayout(this)
        actions.addView(mic); actions.addView(send); actions.addView(stopBtn)
        bar.addView(actions, LinearLayout.LayoutParams(dpi(52f), dpi(52f)).apply { marginStart = dpi(6f) })
        var shownAction: View? = null
        updateAction = {
            val want: View = if (busy) stopBtn else if (input.text.isNotBlank() || pending.isNotEmpty()) send else mic
            if (want !== shownAction) {
                shownAction = want
                Motion.swap(listOf(mic, send, stopBtn), want)
            }
        }
        updateAction()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Testata stabile fuori dall'area di lettura: nessun testo passa sotto i controlli.
        head.setBackgroundColor(Theme.BG)
        lateinit var wheelNudge: (Float) -> Unit
        lateinit var fingerDown: () -> Unit
        val stack2 = WheelFrame(this, { d -> wheelNudge(d * 1.8f) }, { fingerDown() })
        stack2.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        list.setPadding(0, dpi(4f), 0, dpi(8f))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (nearBottom()) Motion.hide(latest) }
        // Scorrimento a molla: la rotella manda piccoli scatti e i tasti passi fissi; una molla critica li fonde in un movimento continuo (come la home).
        // Seguimento esponenziale (nessuna velocita' residua): la rotella arriva a gesti (ognuno parte da un punto fisso e va in una sola direzione),
        // e una molla con velocita' superava il bersaglio e tornava indietro. Qui la chat si avvicina al bersaglio e basta.
        var sPos = 0f; var sTarget = 0f; var sRunning = false; var sLast = 0L
        val sFrame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (!sRunning) return
                val dt = if (sLast == 0L) 1f / 60 else ((ns - sLast) / 1e9f).coerceIn(0f, 0.05f); sLast = ns
                sPos += (sTarget - sPos) * (1f - Math.exp(-dt * 16.0).toFloat())
                if (Math.abs(sTarget - sPos) < 0.6f) { sPos = sTarget; sRunning = false }
                scroll.scrollTo(0, Math.round(sPos))
                if (sRunning) android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        fun stopSmooth() { sRunning = false; android.view.Choreographer.getInstance().removeFrameCallback(sFrame) }
        fun nudge(dy: Float) {
            if (!sRunning && Math.abs(scroll.scrollY - sPos) > 2f) { sPos = scroll.scrollY.toFloat(); sTarget = sPos }
            if (sRunning && Math.abs(scroll.scrollY - sPos) > 12f) { sPos = scroll.scrollY.toFloat(); sTarget = sPos }
            val max = (list.height - scroll.height).coerceAtLeast(0).toFloat()
            sTarget = (sTarget + dy).coerceIn(0f, max)
            if (!sRunning) { sRunning = true; sLast = 0L; android.view.Choreographer.getInstance().postFrameCallback(sFrame) }
        }
        wheelNudge = { d -> nudge(d) }; fingerDown = { stopSmooth() }
        col.addView(head); col.addView(stack2, LinearLayout.LayoutParams(-1, 0, 1f)); col.addView(latest); col.addView(chipsScroll); col.addView(buddyStage); col.addView(bar)
        bottom()
        // Bozza della chat: quello che stavi scrivendo torna quando la riapri.
        if (share == null && observeHsid == null) prefs.getString("draft_$chatId", null)?.takeIf { it.isNotBlank() }?.let { input.setText(it); input.setSelection(it.length) }
        if (share != null) { // da "Condividi": testo nella casella e allegati pronti, l'utente può aggiungere due parole e inviare
            if (share.text.isNotBlank()) { if (share.voiceDraft) reviewVoice(share.text) else input.setText(share.text) }
            share.files.forEach { pending.add(it) }; refreshChips()
        }
        bindHermes = { task, reply ->
            stopTaskWatch?.invoke()
            var lastRevision = -1L
            var lastRenderedText = ""
            var errorShown = false
            var flushPosted = false
            var newest = task.progress.snapshot()
            fun renderTask(): Unit {
                flushPosted = false
                if (!chatScope.alive) return
                val snapshot = newest
                if (snapshot.revision <= lastRevision) return
                lastRevision = snapshot.revision
                val follow = nearBottom()
                busy = snapshot.status == TurnProgress.Status.RUNNING
                if (snapshot.text.isNotEmpty() && snapshot.text != lastRenderedText) {
                    lastRenderedText = snapshot.text
                    reply.setCompoundDrawablesRelative(null, null, null, null)
                    setMsg(reply, snapshot.text, false); updated(follow)
                }
                when (snapshot.status) {
                    TurnProgress.Status.RUNNING -> {
                        if (snapshot.activity.isNotBlank() || snapshot.text.isEmpty()) setActivity(snapshot.activity)
                        else { buddyStage.dismiss(); headChar.show(agent, "speaking") }
                    }
                    TurnProgress.Status.ERROR -> {
                        buddyStage.flash("error", UiText.text(this@MainActivity, R.string.ui_21cb865bd3), 2600)
                        headChar.show(agent, "error")
                        if (!errorShown) {
                            errorShown = true
                            add(UiText.owned(this@MainActivity, snapshot.error) + UiText.text(this@MainActivity, R.string.ui_f819674502), false, error = true).setOnClickListener { openChat(agent, chatId) }
                        }
                    }
                    else -> { buddyStage.dismiss(); headChar.show(agent, "idle"); if (snapshot.status == TurnProgress.Status.DONE) { headChar.react("giggle"); autoRunActions(snapshot.text) } }
                }
                if (!busy) {
                    Motion.hide(activity)
                    if (snapshot.text.isEmpty() && reply.parent != null) list.removeView(reply)
                }
                updateAction()
            }
            stopTaskWatch = task.progress.subscribe { snapshot -> runOnUiThread {
                if (chatScope.alive && snapshot.revision >= newest.revision) {
                    newest = snapshot
                    if (!flushPosted) { flushPosted = true; scroll.postDelayed({ renderTask() }, 50) }
                }
            } }
        }
        val ongoing = hermesRuntime.active(chatId)
        if (ongoing != null || observeHsid != null) {
            busy = true; updateAction()
            val reply = add("", false).apply { setCompoundDrawablesRelativeWithIntrinsicBounds(TypingDots(this@MainActivity), null, null, null) }
            bindHermes(ongoing ?: hermesRuntime.observe(agent, chatId, observeHsid!!), reply)
            bottom()
        }
        if (say != null) list.post { submit(say) }
        return Screen(page(col, 14), { e ->
            if (e.keyCode == KeyEvent.KEYCODE_BUTTON_1 || e.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) pttHandler?.invoke(e) ?: false
            else if (currentFocus is EditText) false
            else when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { nudge(-dpi(120f).toFloat()); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { nudge(dpi(120f).toFloat()); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { input.requestFocus(); true }
                else -> false
            }
        }, scope = chatScope, onShow = { restoreHooks() }, onLeave = { prefs.edit().apply { val d = input.text.toString(); if (d.isBlank() || busy) remove("draft_$chatId") else putString("draft_$chatId", d) }.apply(); stopSmooth(); connectionAlive = false; stopConnectionWatch(); buddyStage.dismiss(); avatarSlot.removeCallbacks(collapseAvatar); avatarMotion?.cancel(); engine?.let { it.cancel(); localTurns.remove(it) }; stopTaskWatch?.invoke(); if (onVoice === myVoice) onVoice = null; if (cancelActiveMic === myMic) cancelActiveMic = null; if (pttHandler === myPtt) pttHandler = null; if (attachHook === myAttach) attachHook = null; pending.forEach { it.file.delete() }; pending.clear(); voiceCancelled = true; voice.cancel(); tts?.stop() }, isChat = true, chatKey = chatId, onSuspend = { if (voiceActive) suspendVoice() }, leaveGuard = { if (busy && !hermesMode()) { val now = android.os.SystemClock.uptimeMillis(); if (armedKey == "leave" && now - armedAt < 4000) false else { armedKey = "leave"; armedAt = now; val m = UiText.text(this@MainActivity, R.string.ui_8cec984fbd); snack(m); buddyStage.flash("cancel", m, 3000); true } } else false })
    }

    /** Il microfono scelto in Aspetto e voce: Groq Whisper (se c'è la chiave) o il riconoscimento del dispositivo. */
    internal var qaMicrophone: (((String) -> Unit, (String) -> Unit, (String?) -> Unit) -> Mic)? = null
    internal fun newMic(onStatus: (String) -> Unit, onFinal: (String) -> Unit, onEnd: (String?) -> Unit): Mic {
        if (packageName.endsWith(".qa") && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) qaMicrophone?.let { return it(onStatus, onFinal, onEnd) }
        return Mics.create(this, prefs, onStatus, onFinal, onEnd)
    }

    // ---- pannello vocale (VoiceOverlay di r1os, semplificato): tieni premuto il pulsante e parla -----

    internal var pttView: FrameLayout? = null
    internal var voiceConversation: VoiceConversation? = null

    internal var closingVoice = false
    internal var homeVoiceGesture = false
    internal var drainingHomeVoiceTouch = false
    internal var homeVoiceStartY = 0f

    internal fun pttStart() {
        voiceConversation?.let { it.startListening(); return }
        val agent = visibleAgents().getOrNull(homeIndex) ?: return
        val mode = VoiceConversation(this, agent, hermesMode(), resolveChat = {
            if (hermesMode() && agent.profile.isNotEmpty()) syncSessions(agent)
            db.lastChat(agent.id) ?: db.newChat(agent.id)
        }, microphone = { status, final, end -> newMic(status, final, end) }, onChatVisible = { runtimeVisibility() }, close = { pttClose() })
        presentVoice(mode)
    }

    @JvmName("presentVoice") internal fun presentVoice(mode: VoiceConversation) {
        voiceConversation = mode; pttView = mode
        val screen = Screen(mode, buddyContext = BuddySceneContext.LIVE, isVoice = true, onLeave = {
            mode.dispose(); homeVoiceGesture = false
            if (voiceConversation === mode) { voiceConversation = null; pttView = null }
        })
        push(screen)
        runtimeVisibility(); mode.startListening()
    }

    internal fun pttStop() { voiceConversation?.release() }

    internal fun pttClose(immediate: Boolean = false) {
        val mode = pttView ?: return
        if (closingVoice) return
        closingVoice = true; drainingHomeVoiceTouch = true
        try {
        homeVoiceGesture = false
        if (stack.lastOrNull()?.view === mode) {
            if (!immediate && visible) { pop(); return }
            cancelBuddyFlight(); cancelModalBuddyFlight(); clearCarriedBuddy()
            val screen = stack.removeAt(stack.size - 1); cancelPendingEntry(screen); screen.scope.alive = false; screen.onLeave?.invoke()
            root.removeView(mode)
            stack.lastOrNull()?.let { back ->
                if (back.view.parent == null) root.addView(back.view, 0, ViewGroup.LayoutParams(-1, -1))
                back.view.alpha = 1f; back.view.translationX = 0f; back.view.translationY = 0f
                back.view.scaleX = 1f; back.view.scaleY = 1f; showScreen(back)
            }
        } else {
            // Tolleranza per un controller già staccato da una ricreazione o da un test isolato.
            voiceConversation?.dispose(); voiceConversation = null; pttView = null; root.removeView(mode)
        }
        runtimeVisibility(); restoreBuddies()
        } finally { drainingHomeVoiceTouch = false; closingVoice = false }
    }

    internal fun holdToTalk(v: View, mic: Mic, onStart: () -> Unit = {}) {
        v.setOnTouchListener { _, e -> when (e.action) { android.view.MotionEvent.ACTION_DOWN -> { onStart(); mic.start() }; android.view.MotionEvent.ACTION_UP -> mic.stop(); android.view.MotionEvent.ACTION_CANCEL -> mic.cancel() }; true }
    }

    internal fun speak(t: String) {
        val say = { tts?.speak(t, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "r1") }
        if (tts == null) tts = android.speech.tts.TextToSpeech(this) { s -> if (s == android.speech.tts.TextToSpeech.SUCCESS) { ttsReady = true; tts?.language = AppLanguage.locale(this@MainActivity); say() } }
        else if (ttsReady) say()
    }

    override fun onDestroy() {
        pttClose(immediate = true); cancelLocalTurns(); cancelBuddyFlight(); cancelModalBuddyFlight(); clearCarriedBuddy(); Motion.onAttention = null
        stack.forEach { it.scope.alive = false; it.onLeave?.invoke() }
        tts?.shutdown(); super.onDestroy()
    }
    /** Schermata dell'app in primo piano: con l'app in secondo piano o lo schermo spento i tick (orologio, widget) saltano il lavoro. */
    @Volatile internal var visible = true
    internal fun characters(v: View, f: (CharacterView) -> Unit) { if (v is CharacterView) f(v) else if (v is ViewGroup) for (i in 0 until v.childCount) characters(v.getChildAt(i), f) }
    // ---- scossa: scuotendo il dispositivo i buddy si confondono (giro su se' stessi con la faccia stordita) ----------
    internal var shakeListener: android.hardware.SensorEventListener? = null
    internal var lastShake = 0L
    internal fun startShake() {
        if (shakeListener != null) return
        val sm = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var peaks = 0; var first = 0L
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val g = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()).toFloat() / android.hardware.SensorManager.GRAVITY_EARTH
                if (g < 2.3f) return
                val now = android.os.SystemClock.uptimeMillis()
                if (now - first > 900) { peaks = 0; first = now }
                // Tre scatti forti in meno di un secondo: una scossa vera, non un urto.
                if (++peaks >= 3 && now - lastShake > 3000) { lastShake = now; peaks = 0; onShake() }
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        shakeListener = l; sm.registerListener(l, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }
    internal fun stopShake() { shakeListener?.let { (getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager)?.unregisterListener(it) }; shakeListener = null }
    internal fun onShake() = runOnUiThread { root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); characters(root) { it.react("spin") } }

    override fun onStart() { super.onStart(); watchPackages(true) }
    override fun onStop() { watchPackages(false); super.onStop() }
    override fun onResume() { super.onResume(); startShake(); visible = true; root.removeCallbacks(buddyReaper); root.postDelayed(buddyReaper, 1200L); dev.r1os.host.ui.Spring.reduced = Motion.reduced(this); runtimeVisibility()
        hermesRuntime.requestListener = { request -> runOnUiThread { if (visible) showRequest(request) } }
        hermesRuntime.requestCancelListener = { id -> runOnUiThread { closeOverlay("req-$id") } }
        hermesRuntime.requests.values.forEach { showRequest(it) }
        if (hermesMode()) hermesRuntime.ensureRecoveryService()
         if (::prefs.isInitialized && gateWanted()) showPinGate(); Dim.on(this); characters(root) { it.setActive(true) }; heldBuddies.forEach { it.setActive(false) }; stack.lastOrNull()?.onResume?.invoke() }
    override fun onPause() { root.removeCallbacks(buddyReaper); stopShake(); stack.forEach { it.onSuspend?.invoke() }; pttClose(immediate = true); cancelBuddyFlight(); cancelModalBuddyFlight(restoreSource = overlays.isEmpty()); clearCarriedBuddy(restore = overlays.isEmpty()); if (::prefs.isInitialized && gateWanted()) { cancelActiveMic?.invoke(); tts?.stop(); pinUnlocked = false; showPinGate() }; visible = false; runtimeVisibility(); hermesRuntime.requestListener = null; hermesRuntime.requestCancelListener = null; characters(root) { it.setActive(false) }; Dim.off(); super.onPause() }
    internal val KEYCODE_NAV_NEXT = 261   // KEYCODE_NAVIGATE_NEXT
    internal val KEYCODE_NAV_PREV = 260   // KEYCODE_NAVIGATE_PREVIOUS

    internal fun inputSurface(): View? = pinGate ?: overlays.values.lastOrNull() ?: pttView ?: stack.lastOrNull()?.view

    internal fun isCurrentSurface(view: View): Boolean {
        val surface = inputSurface()
        var node: View? = view
        while (node != null) { if (node === surface) return true; node = node.parent as? View }
        return false
    }

    internal fun firstScroll(): ScrollView? {
        fun find(v: View): ScrollView? = if (!v.isShown) null else if (v is ScrollView) v else if (v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) } else null
        return inputSurface()?.let { find(it) }
    }

    internal fun moveFocus(dir: Int) {
        val surface = inputSurface() ?: return
        val choices = ArrayList<View>(); surface.addFocusables(choices, dir, View.FOCUSABLES_ALL)
        val valid = choices.filter { it.isShown && it.isEnabled && it !== surface }
        val focused = valid.indexOf(currentFocus)
        val next = if (focused < 0) { if (dir == View.FOCUS_BACKWARD) valid.lastOrNull() else valid.firstOrNull() }
            else valid.getOrNull(focused + if (dir == View.FOCUS_BACKWARD) -1 else 1)
        next?.requestFocus()
    }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        // Il cambio orientazione conserva pagina, bozza e turno. Il gesto vocale interrotto non invia nulla.
        if (homeVoiceGesture) {
            homeVoiceGesture = false; drainingHomeVoiceTouch = true
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle(); drainingHomeVoiceTouch = false }
        }
        cancelBuddyFlight(); cancelModalBuddyFlight(restoreSource = overlays.isEmpty()); clearCarriedBuddy(restore = overlays.isEmpty()); wheelSurface = null; backDrag = false; edgeX = -1f; backVt?.recycle(); backVt = null
        overlayRotations.values.toList().forEach { it() }
        stack.forEach { it.onSuspend?.invoke() }; cancelActiveMic?.invoke(); voiceConversation?.viewportChanged()
        root.requestLayout()
    }

    internal var wheelSurface: R1ScrollView? = null
    internal var wheelLastX = 0f
    internal var wheelLastY = 0f

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_SCROLL) {
            val scroll = firstScroll() as? R1ScrollView
            val delta = event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL).let { if (it != 0f) it else event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL) }
            if (scroll != null && delta != 0f) { Dim.poke(); scroll.nudge(-delta * dp(48f)); return true }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    internal var edgeX = -1f
    internal var edgeY = 0f
    internal var backDrag = false
    internal var backDragDistance = 0f
    internal var heroDragStart = 1f
    internal var backVt: android.view.VelocityTracker? = null

    internal fun beginBackDrag(ev: android.view.MotionEvent) {
        // I figli ricevono un ANNULLA una volta sola: da qui in poi il gesto e' della pagina.
        val cancel = android.view.MotionEvent.obtain(ev); cancel.action = android.view.MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel); cancel.recycle()
        val screen = stack.last(); val previous = stack[stack.size - 2]
        cancelPendingEntry(screen)   // un ingresso ancora in attesa non deve partire mentre il dito trascina
        if (screen.heroOrigin != null) {
            if (buddyFlight == null) showScreen(previous)
            prepareBuddyFlight(screen, previous, 1f)?.let { flight ->
                heroDragStart = flight.progress; flight.seek(heroDragStart); backDragDistance = 0f; backDrag = true; return
            }
        }
        val top = screen.view; val under = previous.view; val w = pageWidth()
        top.animate().cancel(); under.animate().cancel()
        if (under.parent == null) { under.translationX = -w * parallax; under.alpha = dimmed; root.addView(under, 0, ViewGroup.LayoutParams(-1, -1)) }
        backDrag = true
    }

    /** La pagina segue il dito dal bordo sinistro; sotto, quella precedente avanza e si illumina. */
    internal fun updateBackDrag(dx: Float) {
        val w = pageWidth(); val p = (dx / w).coerceIn(0f, 1f)
        if (buddyFlight != null && flightChat === stack.last()) { backDragDistance = dx; buddyFlight?.seek(heroDragStart * (1f - p)); return }
        stack.last().view.translationX = dx.coerceIn(0f, w)
        stack[stack.size - 2].view.let { it.translationX = -w * parallax * (1f - p); it.alpha = dimmed + (1f - dimmed) * p }
    }

    internal fun endBackDrag() {
        val vt = backVt; vt?.computeCurrentVelocity(1000)
        val vx = vt?.xVelocity ?: 0f
        val top = stack.last().view; val under = stack[stack.size - 2].view; val w = pageWidth()
        if (buddyFlight != null && flightChat === stack.last()) {
            if (backDragDistance > w * .35f || vx > dp(900f)) pop(fromDrag = true) else buddyFlight?.animateTo(1f)
            return
        }
        if (top.translationX > w * 0.35f || vx > dp(900f)) pop(fromDrag = true)
        else {
            top.animate().translationX(0f).setDuration(Motion.BASE).setInterpolator(Theme.SETTLE).start()
            under.animate().translationX(-w * parallax).alpha(dimmed).setDuration(Motion.BASE).setInterpolator(Theme.SETTLE).withEndAction { if (stack.size > 1 && stack[stack.size - 2].view === under) detach(under) }.start()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        Dim.poke()
        if (homeVoiceGesture && ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> if (homeVoiceStartY - ev.rawY > dp(64f)) { voiceConversation?.cancelRecording(); pttClose() }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Svuota il vecchio target touch senza trasformare la cancellazione interna in un annullamento vocale.
                    drainingHomeVoiceTouch = true
                    val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle(); drainingHomeVoiceTouch = false }
                    homeVoiceGesture = false
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) pttStop()
                    else { voiceConversation?.cancelRecording(); pttClose() }
                }
            }
            return true
        }
        val wheel = ev.device?.name?.contains("wheel", ignoreCase = true) == true
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            wheelSurface = if (wheel) firstScroll() as? R1ScrollView else null
            wheelLastX = ev.x; wheelLastY = ev.y
        }
        if (wheel) wheelSurface?.let { scroll ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (scroll.isAttachedToWindow) scroll.nudge(r1WheelDelta(wheelLastX - ev.x, wheelLastY - ev.y, scroll.display?.rotation ?: 0) * 1.8f)
                    wheelLastX = ev.x; wheelLastY = ev.y
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> wheelSurface = null
            }
            return true
        }
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                edgeX = if (ev.x < dpi(28f) && stack.size > 1 && pttView == null && pinGate == null && overlays.isEmpty()) ev.x else -1f; edgeY = ev.y; backDrag = false
                backVt?.recycle(); backVt = if (edgeX >= 0f) android.view.VelocityTracker.obtain().also { it.addMovement(ev) } else null
            }
            android.view.MotionEvent.ACTION_MOVE -> if (edgeX >= 0f) {
                backVt?.addMovement(ev)
                val dx = ev.x - edgeX
                if (!backDrag && dx > dpi(10f) && Math.abs(ev.y - edgeY) < dx) beginBackDrag(ev)
                if (backDrag) { updateBackDrag(dx); return true }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                backVt?.addMovement(ev)
                if (backDrag) { backDrag = false; edgeX = -1f; if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) endBackDrag() else if (buddyFlight != null && flightChat === stack.last()) buddyFlight?.animateTo(1f) else { stack.last().view.animate().translationX(0f).setDuration(Motion.BASE).start(); detach(stack[stack.size - 2].view) }; backVt?.recycle(); backVt = null; return true }
                edgeX = -1f; backVt?.recycle(); backVt = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 14 && res == RESULT_OK) data?.data?.let { readPairingQr(it) }
        if (req == 15) {
            val path = prefs.getString("pending_qr_photo", null); prefs.edit().remove("pending_qr_photo").apply()
            val file = photoFile ?: path?.let { java.io.File(it) }
            photoFile = null
            if (res == RESULT_OK && file != null && file.exists()) readPairingQr(Uri.fromFile(file)) { file.delete() } else file?.delete()
        }
        if (req == 8 && res == RESULT_OK) data?.data?.let { savePhoto(it, bgPick) }
        if (req == 11) { // esportazione: scrive il file scelto
            val json = exportJson; val sum = exportSummary; exportJson = null; exportSummary = null
            if (res == RESULT_OK && json != null) data?.data?.let { uri ->
                Io.run {
                    try { contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: throw IllegalStateException(UiText.text(this@MainActivity, R.string.ui_3aed9bbb3f))
                        runOnUiThread { snack(UiText.text(this@MainActivity, R.string.ui_01c4e3b47f, UiText.owned(this@MainActivity, sum.toString())), long = true) }
                    } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@MainActivity, R.string.ui_9bf3187280, UiText.message(this@MainActivity, e.message)), long = true) } }
                }
            }
        }
        if (req == 12 && res == RESULT_OK) data?.data?.let { uri -> // importazione: legge, controlla, poi chiede come procedere
            Io.run {
                try {
                    val text = contentResolver.openInputStream(uri)?.use { s -> Backup.read(s) } ?: throw IllegalStateException(UiText.text(this@MainActivity, R.string.ui_0003bfadca))
                    val sum = Backup.inspect(text)
                    runOnUiThread { confirmImport(text, sum) }
                } catch (e: Exception) { runOnUiThread { snack(UiText.message(this@MainActivity, e.message) ?: UiText.text(this@MainActivity, R.string.ui_23a3dc3076), long = true) } }
            }
        }
        if (req == 16) { // scatta e chiedi a un agente: la foto diventa un allegato pronto da inviare nella chat scelta
            val f = photoFile; photoFile = null
            if (res == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                val att = Attach.fromFile(f, "foto-${java.text.SimpleDateFormat("HHmmss", AppLanguage.locale(this@MainActivity)).format(java.util.Date())}.jpg", "image/jpeg")
                val agents = visibleAgents(); val share = Share("", listOf(att))
                when { agents.isEmpty() -> snack(UiText.text(this@MainActivity, R.string.ui_62dde8cda6), long = true); agents.size == 1 -> openLatest(agents[0], null, share)
                    else -> sheet(UiText.text(this@MainActivity, R.string.ui_263a8abb66), agents.map { a -> a.name to { openLatest(a, null, share) } }) }
            } else f?.delete()
        }
        if (req == 9) { // Il risultato resta legato alla chat che ha aperto la fotocamera.
            val f = photoFile; photoFile = null
            val target = photoAttachTarget; photoAttachTarget = null
            if (res == RESULT_OK && f != null && f.exists() && f.length() > 0 && target != null && target === attachHook) {
                target(Attach.fromFile(f, "foto-${java.text.SimpleDateFormat("HHmmss", AppLanguage.locale(this@MainActivity)).format(java.util.Date())}.jpg", "image/jpeg"))
            } else {
                f?.delete()
                if (res == RESULT_OK && target != null && target === attachHook) snack(UiText.text(this@MainActivity, R.string.ui_3550dfcfd5), long = true)
            }
        }
        if (req == 10) {
            val target = fileAttachTarget; fileAttachTarget = null
            if (res == RESULT_OK && target != null && target === attachHook) data?.data?.let { uri ->
                Io.run {
                    try {
                        val a = Attach.fromUri(this, uri)
                        runOnUiThread {
                            if (isDestroyed || target !== attachHook) a.file.delete()
                            else if (a.kind == Kind.OTHER) { a.file.delete(); snack(UiText.text(this@MainActivity, R.string.ui_4f12505835), long = true) }
                            else target(a)
                        }
                    } catch (e: Exception) { runOnUiThread { if (!isDestroyed && target === attachHook) snack(UiText.message(this@MainActivity, e.message) ?: UiText.text(this@MainActivity, R.string.ui_10afdd5e00), long = true) } }
                }
            }
        }
        if (req == 7 && res == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onVoice?.invoke(it) }
    }

    // ---- dati: esporta e importa (Backup.kt) ---------------------------------------------------------------

    internal var exportJson: String? = null
    internal var exportSummary: Backup.Summary? = null

    internal fun startExport() {
        Io.run {
            try {
                val (json, sum) = Backup.export(db, prefs); exportJson = json; exportSummary = sum
                val name = "r1agents-${java.text.SimpleDateFormat("yyyy-MM-dd", AppLanguage.locale(this@MainActivity)).format(java.util.Date())}.json"
                runOnUiThread { @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name), 11) }
            } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@MainActivity, R.string.ui_9bf3187280, UiText.message(this@MainActivity, e.message)), long = true) } }
        }
    }

    internal fun startImport() {
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 12)
    }

    /** Mostra cosa contiene il file e chiede come importarlo: aggiungere ai dati attuali o sostituirli. */
    internal fun confirmImport(json: String, sum: Backup.Summary) {
        overlay("import", UiText.text(this@MainActivity, R.string.ui_7c032d1cfb), { closeOverlay("import") }) { col, close ->
            col.addView(intro(UiText.text(this@MainActivity, R.string.ui_242765df7c, UiText.owned(this@MainActivity, sum.toString()))))
            col.addView(button(UiText.text(this@MainActivity, R.string.ui_17b0bf8e19), toolAccent) { close(); doImport(json, false) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
            col.addView(hint(UiText.text(this@MainActivity, R.string.ui_688ccaab33)))
            col.addView(button(UiText.text(this@MainActivity, R.string.ui_01885652ec), Theme.BAD, filled = false) { close(); doImport(json, true) }, LinearLayout.LayoutParams(-1, -2))
            col.addView(button(UiText.text(this@MainActivity, R.string.ui_9cb9b85e99), Theme.MUTED, primary = false) { close() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        }
    }

    internal fun doImport(json: String, replace: Boolean) {
        Io.run {
            try {
                val sum = Backup.import(this, db, prefs, json, replace)
                runOnUiThread { snack(UiText.text(this@MainActivity, R.string.ui_edb48d717f, UiText.owned(this@MainActivity, sum.toString())), long = true); homeIndex = 0; recreate() }
            } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@MainActivity, R.string.ui_690ea096f1, UiText.message(this@MainActivity, e.message)), long = true) } }
        }
    }

    // ---- allegati: foto, immagini, file, e "Condividi" da altre app -----------------------------------------

    internal var attachHook: ((Attachment) -> Unit)? = null
    internal var photoFile: java.io.File? = null
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); photoFile?.let { out.putString("photo_file", it.path) } }
    internal var photoAttachTarget: ((Attachment) -> Unit)? = null
    internal var fileAttachTarget: ((Attachment) -> Unit)? = null

    internal fun pickPhoto(requestCode: Int = 9) {
        val f = java.io.File(java.io.File(cacheDir, "photos").also { it.mkdirs() }, "foto-${System.currentTimeMillis()}.jpg")
        photoFile = f
        if (requestCode == 9) photoAttachTarget = attachHook
        if (requestCode == 15) prefs.edit().putString("pending_qr_photo", f.path).apply()
        val uri = Uri.parse("content://${packageName}.photos/${f.name}")
        val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("foto", uri)
        if (i.resolveActivity(packageManager) == null) { snack(UiText.text(this@MainActivity, R.string.ui_1fe81db21a), long = true); return }
        @Suppress("DEPRECATION") startActivityForResult(i, requestCode)
    }

    internal fun pickFile(imagesOnly: Boolean) {
        fileAttachTarget = attachHook
        @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (imagesOnly) "image/*" else "*/*"), 10)
    }

    /** "Condividi › R1 Agents" da un'altra app: testo, link, immagini, PDF. Si sceglie l'agente e la chat si apre con tutto pronto da inviare. */
    @Suppress("DEPRECATION")
    internal fun handleShare(i: Intent) {
        val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
        val uris = ArrayList<Uri>()
        (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
        i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        i.action = null
        Io.run {
            val files = uris.mapNotNull { runCatching { Attach.fromUri(this, it) }.getOrNull() }.filter { it.kind != Kind.OTHER }
            runOnUiThread {
                val agents = visibleAgents()
                val share = Share(text, files)
                when {
                    text.isBlank() && files.isEmpty() -> snack(UiText.text(this@MainActivity, R.string.ui_8078441d49), long = true)
                    agents.isEmpty() -> snack(UiText.text(this@MainActivity, R.string.ui_62dde8cda6), long = true)
                    agents.size == 1 -> openLatest(agents[0], null, share)
                    else -> sheet(UiText.text(this@MainActivity, R.string.ui_263a8abb66), agents.map { a -> a.name to { openLatest(a, null, share) } })
                }
            }
        }
    }

    // ---- traduttore ---------------------------------------------------------------------------

    internal val trLangs get() = AppLanguage.wrap(this).resources.getStringArray(R.array.translation_language_names).toList()
    internal var trResult = ""

    // ---- glance: l'orologio (da Glance di r1os, in versione semplice) ---------------------------

    @JvmName("glance") internal fun glance(): Screen {
        val agent = visibleAgents().getOrNull(homeIndex.coerceAtLeast(0)) ?: neutralBuddy
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(6f), dpi(12f), 0, 0) }
        val center = prefs.getString("clock_align", "left") == "center"
        val clock = dev.r1os.host.shell.Clocks.face(this, prefs.getString("clock", "bold") ?: "bold")
        val date = text(19f, 650, Theme.MUTED).apply { if (center) gravity = Gravity.CENTER }
        val status = text(15f, 600, Theme.MUTED).apply { fontFeatureSettings = "tnum"; setPadding(0, dpi(6f), 0, 0) }
        val alarm = text(15f, 600, Theme.accent).apply { setPadding(0, dpi(4f), 0, 0) }
        if (center) { col.gravity = Gravity.CENTER_HORIZONTAL; status.gravity = Gravity.CENTER; alarm.gravity = Gravity.CENTER }
        val shown = prefs.getString("gshow", "date,status,alarm")!!.split(",")
        col.addView(clock)
        if ("date" in shown) col.addView(date, LinearLayout.LayoutParams(-1, -2))
        if ("status" in shown) col.addView(status, LinearLayout.LayoutParams(-1, -2))
        if ("alarm" in shown) col.addView(alarm, LinearLayout.LayoutParams(-1, -2))
        val widgets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(12f), dpi(8f), 0) }
        col.addView(widgets, LinearLayout.LayoutParams(-1, -2))
        // Dock: le app preferite (scelte nel cassetto con il tocco prolungato), sempre a portata sulla schermata iniziale.
        val dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(6f), dpi(8f), 0); if (center) gravity = Gravity.CENTER }
        col.addView(dock, LinearLayout.LayoutParams(-1, -2))
        fun dockUpdate() {
            val favs = favoriteApps()
            if (favs.isEmpty()) { dock.removeAllViews(); return }
            Io.run {
                val apps = (appCache ?: loadApps().also { appCache = it }).filter { it.pkg in favs }.take(5)
                runOnUiThread {
                    dock.removeAllViews()
                    apps.forEach { a -> dock.addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageDrawable(a.icon); contentDescription = a.label; isClickable = true; isFocusable = true; defaultFocusHighlightEnabled = false
                        setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
                        setOnClickListener { launchApp(a) }; setOnTouchListener { v, e -> Motion.touch(v, e); false }
                    }, LinearLayout.LayoutParams(dpi(56f), dpi(56f)).apply { marginEnd = dpi(8f) }) }
                    if (dock.childCount > 0) Motion.stagger(dock, dp(8f))
                }
            }
        }
        dockUpdate()
        // Senza scelta esplicita, con Hermes attivo lo standby mostra lo stato dal vivo di Hermes.
        val wset = prefs.getString("gw", null)?.split(",")?.filter { it.isNotEmpty() } ?: if (hermesMode()) listOf("hermes") else emptyList()
        fun widgetCard(t: String): TextView = text(16f, 600).keepCase().apply {
            text = t; setPadding(dpi(14f), dpi(9f), dpi(14f), dpi(9f)); background = roundRect(Theme.withAlpha(Theme.CARD, 0.85f), dp(16f))
            widgets.addView(this, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dpi(6f) })
        }
        frame.addView(col, FrameLayout.LayoutParams(-1, -2))
        val size = dpi(232f)
        val peek = if (agent != null) CharacterView(this, size).also { it.show(agent); it.tag = "scene-buddy" } else null
        // L'agente sbircia da sotto con una molla; al tocco sale al centro e si rimpicciolisce fino a dove sta nella home (morph glance -> home).
        var morphing = false
        if (peek != null) {
            peek.translationY = size * 0.32f
            frame.addView(peek, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        val sleeper = peek?.let { ch -> Sleeper(frame, { ch }, { agent?.let { ch.show(it) } }, { agent?.let { ch.show(it, "sleeping") } }, afterProvider = ::idleMs) }
        frame.post { sleeper?.poke() }
        // Standby vivo: finché non dorme si guarda intorno, e ogni tanto si alza un poco per vedere meglio.
        if (peek != null) {
            BuddyCuriosity.start(peek, 4f) { sleeper?.asleep != true && !morphing }
            val lifter = object : Runnable { override fun run() {
                if (!peek.isAttachedToWindow) return
                if (sleeper?.asleep != true && !morphing && !Motion.reduced(this@MainActivity)) {
                    peek.animate().translationY(size * 0.22f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withEndAction { peek.animate().translationY(size * 0.32f).setStartDelay(1400L).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start() }.start()
                }
                peek.postDelayed(this, 9000L + (Math.random() * 7000).toLong())
            } }
            peek.postDelayed(lifter, 4000L)
        }
        frame.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) sleeper?.poke(); false }
        fun toHome() {
            sleeper?.stop()
            if (morphing) return
            morphing = true
            push(home(), fade = true)
        }
        var prevCharging: Boolean? = null; var prevMinute = -1; var lowAlerted = false
        fun update() {
            val now = java.time.ZonedDateTime.now()
            date.text = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", AppLanguage.locale(this@MainActivity)))
            val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = b?.let { it.getIntExtra("level", -1) * 100 / maxOf(it.getIntExtra("scale", 100), 1) } ?: -1
            val charging = (b?.getIntExtra("plugged", 0) ?: 0) != 0
            if (peek != null && sleeper?.asleep != true && !morphing && !Motion.reduced(this@MainActivity)) { // piccole reazioni, rare
                if (prevCharging == false && charging) peek.react("bounce")
                if (lvl in 0..14 && !charging && !lowAlerted) { lowAlerted = true; peek.react("shiver") }
                if (now.minute == 0 && prevMinute > 0) peek.react("nod")
            }
            prevCharging = charging; prevMinute = now.minute
            status.text = if (lvl >= 0) "$lvl%" + if (charging) UiText.text(this@MainActivity, R.string.ui_c167ec74e0) else "" else ""
            val al = db.alarms().filter { it.on }.minByOrNull { Sched.next(it.hour, it.minute, it.days) }
            alarm.text = al?.let { UiText.text(this@MainActivity, R.string.ui_c1f893519d, Sched.time(it.hour, it.minute)) }.orEmpty()
        }
        fun widgetsUpdate() {
            widgets.removeAllViews()
            if ("hermes" in wset && hermesMode()) {
                val card = widgetCard("hermes")
                Io.run { // dal vivo: chat in corso e prossima attività pianificata
                    val running = dash.live().values.count { it == "working" || it == "waiting" }
                    val next = runCatching { dash.cronJobs() }.getOrNull()?.filter { it.enabled && it.nextRun > 0 }?.minByOrNull { it.nextRun }
                    val line = "hermes · " + (if (running > 0) UiText.text(this@MainActivity, R.string.ui_c7c47efe18, running) else UiText.text(this@MainActivity, R.string.ui_a6c333ad12)) + (next?.let { " · " + UiText.text(this@MainActivity, R.string.ui_d54c848c40, next.name, relativeIn(next.nextRun - System.currentTimeMillis())) } ?: "")
                    runOnUiThread { if (card.isAttachedToWindow) card.text = line }
                }
            }
            if ("timer" in wset) db.timers().firstOrNull { it.endAt > System.currentTimeMillis() }?.let { t -> val s = Countdown.seconds(t.endAt, System.currentTimeMillis()); widgetCard("timer ${s / 60}:%02d".format(s % 60)) }
            if ("routine" in wset) db.routines().filter { it.on }.minByOrNull { Sched.next(it.hour, it.minute, it.days) }?.let { widgetCard("${it.name} · ${Sched.time(it.hour, it.minute)}") }
            if ("nota" in wset) db.notes().firstOrNull()?.let { widgetCard(it.second.take(80)) }
            if ("meteo" in wset) {
                val city = prefs.getString("weather_city", "").orEmpty()
                if (city.isNotBlank()) {
                    val cached = prefs.getString("w_text", "").orEmpty()
                    if (cached.isNotEmpty() && prefs.getString("w_city", "") == city) widgetCard(cached)
                    // al massimo una richiesta ogni 30 minuti, e solo con il glance in vista
                    if (System.currentTimeMillis() - prefs.getLong("w_at", 0) > 30 * 60_000L || prefs.getString("w_city", "") != city) {
                        prefs.edit().putLong("w_at", System.currentTimeMillis()).putString("w_city", city).apply()
                        Io.run { runCatching { Weather.fetch(city, AppLanguage.selected(this@MainActivity)) }.onSuccess { r -> prefs.edit().putString("w_text", r).apply(); runOnUiThread { if (frame.isAttachedToWindow) widgetsUpdate() } } }
                    }
                }
            }
        }
        update(); widgetsUpdate()
        // Un solo tick al minuto, e solo con la schermata visibile (regola batteria di r1os).
        ViewTicker(frame, 60_000L, { visible && isCurrentSurface(frame) }) { update(); widgetsUpdate() }
        frame.setOnClickListener { toHome() }
        return Screen(page(frame).also { it.background = bgDrawable("glance") }, { e -> if (e.action == KeyEvent.ACTION_DOWN && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) { toHome(); true } else false },
            buddyContext = BuddySceneContext.LIVE, onLeave = { sleeper?.stop() }, onShow = { morphing = false; peek?.translationY = size * .32f; col.alpha = 1f; col.translationY = 0f; sleeper?.poke(); update(); widgetsUpdate() }, onResume = { update(); widgetsUpdate() })
    }

    // ---- strumenti: sveglie, timer, routine ------------------------------------------------------

    internal val toolAccent get() = Theme.accent

    internal var daysRepaint: (() -> Unit)? = null


    /** Intestazione comune a ogni schermata: tasto indietro (l'R1 non ne ha uno) e titolo. */
    internal fun framed(title: String, body: View, trailing: View? = null, buddyHeader: Boolean = true, buddyOverflow: Boolean = false): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Solo le liste con il buddy fisso (listPage): il buddy sta SOPRA la barra del titolo. Le altre pagine tagliano il contenuto che scorre sotto la barra.
        clipChildren = !buddyOverflow; clipToPadding = !buddyOverflow
        // Stessa altezza con o senza il buddy piccolo: il titolo sta alla stessa quota in tutti i sotto menu.
        val head = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(70f); setPadding(0, 0, 0, dpi(6f)) }
        head.addView(iconButton("back", Theme.accent, filled = false) { pop() })
        head.addView(text(28f, 700).apply { letterSpacing = -0.01f; text = title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dpi(12f), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        trailing?.let { head.addView(it) }
        if (buddyHeader && body.findViewWithTag<CharacterView>("scene-buddy") == null) companion(64f)?.let { cv -> head.addView(cv, LinearLayout.LayoutParams(dpi(64f), dpi(64f)).apply { marginStart = dpi(4f) }) }
        addView(head); addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    internal fun listPage(title: String, onLeave: (() -> Unit)? = null, context: BuddySceneContext = BuddySceneContext.READ, onSuspend: (() -> Unit)? = null, build: (LinearLayout) -> Unit): Screen {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }
        val pinned = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        scroll.buddyFx.root = pinned
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; addView(pinned, LinearLayout.LayoutParams(-1, -2)); addView(FrameLayout(this@MainActivity).apply { clipChildren = true; setBackgroundColor(Theme.BG); addView(scroll, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f)) }   /* sfondo opaco: la lista copre la metà bassa del buddy e la sfumatura va verso il colore pagina, non verso il buddy */
        var rendered = false
        fun render() {
            if (rendered && !Motion.reduced(this@MainActivity)) { scroll.animate().cancel(); scroll.alpha = .55f; scroll.animate().alpha(1f).setDuration(Motion.FAST).setInterpolator(Theme.EASE_OUT).start() } // il contenuto si riassesta invece di tagliare di netto
            rendered = true
            col.removeAllViews()
            build(col)
            pinned.removeAllViews()
            (0 until col.childCount).map { col.getChildAt(it) }.firstOrNull { it.tag == "scene-companion-row" }?.let { row ->
                val height = row.layoutParams.height; col.removeView(row); pinned.addView(row, FrameLayout.LayoutParams(-1, height))
            }
            col.addView(View(this), LinearLayout.LayoutParams(-1, dpi(24f)))
        }
        // La lista si prepara prima dell'ingresso; costruirla nel primo frame interrompeva la transizione.
        render()
        body.post { // buddy piccolo in testata: ogni tanto una occhiata, mai invadente (passo lungo)
            if (body.findViewWithTag<CharacterView>("scene-companion-row") == null && !packageName.endsWith(".qa")) {
                var up: View? = body
                repeat(3) { up = up?.parent as? View; up?.findViewWithTag<CharacterView>("scene-buddy")?.let { cv -> BuddyCuriosity.start(cv, 8f) { visible && isCurrentSurface(body) && BuddyEntrancesIdle.ok() }; return@post } }
            }
        }
        return Screen(page(framed(title, body, buddyOverflow = true)), onShow = ::render, onLeave = onLeave, buddyContext = context, onSuspend = onSuspend)
    }

    /** Due schede per riga (l'ultima, se dispari, a tutta larghezza). */
    internal fun grid(items: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        var i = 0
        while (i < items.size) {
            val r = LinearLayout(this@MainActivity).apply { isBaselineAligned = false }
            if (i + 1 < items.size) {
                r.addView(items[i], LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dpi(4f) })
                r.addView(items[i + 1], LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dpi(4f) }); i += 2
            } else { r.addView(items[i], LinearLayout.LayoutParams(-1, -2)); i++ }
            addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
        }
    }

    /** Due pulsanti affiancati. */
    internal fun pairRow(a: View, b: View): View = LinearLayout(this).apply {
        addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpi(4f) })
        addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(4f) })
    }

    internal fun menuSheet(agent: Agent?) {
        companionAgent = agent ?: companionAgent
        sheetPanel("menu", peek = agent) { col, _, then ->
            col.addView(grid(listOfNotNull(
                agent?.let { a -> tile("agent", UiText.text(this@MainActivity, R.string.ui_1f5f0d75b2), a.name, Theme.colorOf(a.color)) { then { push(space(a)) } } },
                tile("plus", UiText.text(this@MainActivity, R.string.ui_b1a1efd9f5), UiText.text(this@MainActivity, R.string.ui_7bdba79e65), Theme.colorOf("green")) { then { push(editor(null)) } },
                tile("timer", UiText.text(this@MainActivity, R.string.ui_6bceb5c35b), UiText.text(this@MainActivity, R.string.ui_fa1f94b99f), Theme.colorOf("amber")) { then { push(tools()) } },
                tile("data", UiText.text(this@MainActivity, R.string.ui_7d1043473d), UiText.text(this@MainActivity, R.string.ui_be6f615b92), Theme.colorOf("teal")) { then { push(appsScreen()) } },
                tile("sliders", UiText.text(this@MainActivity, R.string.ui_0c375bfee9), UiText.text(this@MainActivity, R.string.ui_6a107bf8d4), Theme.colorOf("blue")) { then { push(settingsScreen()) } },
            )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        }
    }

    // ---- launcher: cassetto delle app (l'app è selezionabile come schermata iniziale, ma non si imposta da sola) ----

    internal class AppEntry(val label: String, val pkg: String, val cls: String, val icon: android.graphics.drawable.Drawable)

    /** Vero se questa app è la schermata iniziale scelta dall'utente nel sistema (ruolo HOME). */
    internal fun isDefaultLauncher(): Boolean = runCatching { getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME) }.getOrDefault(false)
    internal fun favoriteApps(): Set<String> = prefs.getString("fav_apps", "").orEmpty().split(",").filter { it.isNotEmpty() }.toSet()
    internal fun loadApps(): List<AppEntry> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(q, 0).filter { it.activityInfo.packageName != packageName }
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.activityInfo.name, it.loadIcon(packageManager)) }
            .distinctBy { it.pkg + "/" + it.cls }.sortedBy { it.label.lowercase() }
    }
    // ---- azioni del dispositivo: l'agente opera sul telefono (vedi DeviceActions) ----
    internal fun describeAction(a: DeviceActions.Action): String = when (a.kind) {
        "apri" -> UiText.text(this@MainActivity, R.string.ui_6ef46e17aa, a.arg)
        "timer" -> { val sec = DeviceActions.seconds(a.arg) ?: 0; UiText.text(this@MainActivity, R.string.ui_922c82d62c, (if (sec < 60) UiText.text(this@MainActivity, R.string.ui_e641f081b3, sec) else if (sec < 3600) UiText.text(this@MainActivity, R.string.ui_32b259a068, sec / 60) else UiText.text(this@MainActivity, R.string.ui_f31be6b4f3, sec / 3600))) }
        "sveglia" -> UiText.text(this@MainActivity, R.string.ui_a21efabed5, a.arg)
        "torcia" -> if (a.arg.equals("off", true)) UiText.text(this@MainActivity, R.string.ui_1a839844ee) else UiText.text(this@MainActivity, R.string.ui_24c8ab553b)
        "nota" -> UiText.text(this@MainActivity, R.string.ui_510a578c56)
        else -> UiText.text(this@MainActivity, R.string.ui_77027ea210, a.arg)
    }
    /** Esegue l'azione e, se viene da un agente, la scrive nel registro (pagina Sicurezza → azioni dell'agente). */
    internal fun runDeviceAction(a: DeviceActions.Action, log: Boolean = true): Boolean = execDeviceAction(a).also { if (log) ActionLog.record(prefs, a.kind, a.arg, it) }
    internal fun execDeviceAction(a: DeviceActions.Action): Boolean = runCatching {
        when (a.kind) {
            "apri" -> {
                val apps = appCache ?: loadApps().also { appCache = it }
                val i = appForCommand("apri " + a.arg, apps.map { it.label }) ?: apps.indexOfFirst { it.label.equals(a.arg, true) }.takeIf { it >= 0 } ?: return@runCatching false
                launchApp(apps[i]); true
            }
            "timer" -> { val sec = DeviceActions.seconds(a.arg) ?: return@runCatching false; askNotificationsOnce(); db.addTimer(System.currentTimeMillis() + sec * 1000L, ""); Sched.all(this, db); true }
            "sveglia" -> { val (h, m) = DeviceActions.time(a.arg) ?: return@runCatching false; db.save(Alarm(0, h, m, 0, "", true)); Sched.all(this, db); true }
            "torcia" -> {
                val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return@runCatching false
                cm.setTorchMode(id, !a.arg.equals("off", true)); true
            }
            "nota" -> { db.addNote(a.arg.take(500)); true }
            "pannello" -> {
                val action = when (a.arg.lowercase()) { "wifi", "internet" -> android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY; "volume" -> android.provider.Settings.Panel.ACTION_VOLUME; "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS; "nfc" -> android.provider.Settings.Panel.ACTION_NFC; else -> return@runCatching false }
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }
            else -> false
        }
    }.getOrDefault(false)
    internal fun reportAction(a: DeviceActions.Action) {
        val ok = runDeviceAction(a)
        snack(if (ok) UiText.text(this@MainActivity, R.string.ui_dab426e957, describeAction(a)) else UiText.text(this@MainActivity, R.string.ui_606d15c3e0, describeAction(a)), long = !ok)
        cue(if (ok) "nod" else "surprised", force = true)
    }
    /** Modalità «automatiche»: le direttive di una risposta appena completata si eseguono una sola volta. */
    @JvmName("autoRunActions") internal fun autoRunActions(text: String) {
        if (DeviceActions.mode != "auto") return
        val done = prefs.getStringSet("dev_done", emptySet())!!.toMutableSet()
        DeviceActions.parse(text).actions.forEach { a ->
            val k = text.hashCode().toString() + ":" + a.key
            if (done.add(k)) { runDeviceAction(a); snack(UiText.text(this@MainActivity, R.string.ui_dab426e957, describeAction(a))) }
        }
        prefs.edit().putStringSet("dev_done", done.toList().takeLast(60).toSet()).apply()
    }

    internal var appCacheValue: List<AppEntry>? = null
    internal var appCacheAt = 0L
    /** L'elenco delle app vale 60 s e si invalida da solo quando ne installi, aggiorni o togli una. */
    internal var appCache: List<AppEntry>?
        get() = appCacheValue?.takeIf { android.os.SystemClock.uptimeMillis() - appCacheAt < 60_000L }
        set(v) { appCacheValue = v; appCacheAt = android.os.SystemClock.uptimeMillis() }
    internal val packageWatcher = object : android.content.BroadcastReceiver() { override fun onReceive(c: android.content.Context, i: Intent) { appCacheValue = null } }
    internal fun watchPackages(on: Boolean) {
        if (on) registerReceiver(packageWatcher, android.content.IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package") })
        else runCatching { unregisterReceiver(packageWatcher) }
    }
    internal fun recentApps(): List<String> = prefs.getString("recent_apps", "").orEmpty().split(",").filter { it.isNotEmpty() }
    internal fun noteLaunched(pkg: String) { prefs.edit().putString("recent_apps", (listOf(pkg) + recentApps().filter { it != pkg }).take(5).joinToString(",")).apply() }
    /** «apri calendario»: se il testo è un comando e un'app corrisponde in modo certo, la apre e non manda nulla all'agente. */
    internal fun tryOpenApp(t: String): Boolean {
        val c = t.trim()
        if (c.length > 60 || !Regex("^\\s*(apri|avvia|lancia|open|launch|start)\\s", RegexOption.IGNORE_CASE).containsMatchIn(c)) return false
        val apps = appCache ?: loadApps().also { appCache = it }
        val i = appForCommand(c, apps.map { it.label }) ?: return false
        snack(UiText.text(this@MainActivity, R.string.ui_a0ea3faf55, apps[i].label)); launchApp(apps[i]); return true
    }
    /** Apre la fotocamera: l'app di scatto del sistema, o la prima app che si chiama così. */
    internal fun launchCamera() {
        try { startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) {}
        val apps = appCache ?: loadApps().also { appCache = it }
        val cam = apps.firstOrNull { it.label.contains("camera", true) || it.label.contains("fotocamera", true) || it.pkg.contains("camera", true) }
        if (cam != null) launchApp(cam) else snack(UiText.text(this@MainActivity, R.string.ui_98a3cf7659))
    }

    internal fun launchApp(a: AppEntry) {
        noteLaunched(a.pkg)
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(a.pkg, a.cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
        catch (e: Exception) { snack(UiText.text(this@MainActivity, R.string.ui_f2c6915e4c)) }
    }
    /** Dopo il tasto home (quando siamo la schermata iniziale): chiude fogli e pagine e torna alla prima schermata, una alla volta. */
    @JvmName("goHome") internal fun goHome() {
        overlays.keys.toList().forEach { closeOverlay(it) }
        if (pttView != null) pttClose()
        val toClose = stack.drop(1).toList() // solo le pagine aperte adesso: una pagina aperta subito dopo non va chiusa
        fun unwind() { val top = stack.lastOrNull(); if (stack.size > 1 && top != null && top in toClose) { pop(); root.postDelayed({ unwind() }, Motion.SLOW + 40L) } }
        unwind()
    }

    internal var torchOn = false
    internal val timerSecs = listOf(10, 30, 60, 120, 180, 300, 600, 900, 1200, 1500, 1800, 2700, 3600, 5400, 7200, 10800)
    internal val timerLabels get() = timerSecs.map { s -> if (s < 60) UiText.text(this@MainActivity, R.string.ui_aac3dd2ce9, s) else if (s < 3600) "${s / 60} ${if (s == 60) UiText.text(this@MainActivity, R.string.ui_e53435e667) else UiText.text(this@MainActivity, R.string.ui_61d73c1f14)}" else if (s == 3600) UiText.text(this@MainActivity, R.string.ui_991e4aeded) else if (s % 3600 == 0) UiText.text(this@MainActivity, R.string.ui_90cde61af0, s / 3600) else UiText.text(this@MainActivity, R.string.ui_ff2bd7fd8f, s / 3600) }
    // ---- attivita' pianificate e competenze di Hermes (sincronizzate dal dashboard) -------------------------

    internal var jobsCache: List<HermesJob>? = null

    // -- suoneria (da notifica)

    internal fun ringScreen(title: String, label: String, kind: String): Screen {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val ringBuddy = companion(128f)?.also { it.setScenePose("waiting"); col.addView(it, LinearLayout.LayoutParams(dpi(128f), dpi(128f))) }
        if (ringBuddy != null && !packageName.endsWith(".qa") && !Motion.reduced(this)) { // allarmato: sorpresa all'arrivo, poi brividi e sguardi, al massimo 8 volte
            var rings = 0
            val alarmLoop = object : Runnable { override fun run() {
                if (!col.isAttachedToWindow || rings++ >= 8) return
                if (visible) { ringBuddy.react("shiver"); ringBuddy.lookAt(if (rings % 2 == 0) 22f else -22f, -6f, hold = Motion.FAST) }
                col.postDelayed(this, Motion.LOOP_SLOW * 3)
            } }
            col.postDelayed({ if (col.isAttachedToWindow) ringBuddy.react("surprised") }, Motion.BASE)
            col.postDelayed(alarmLoop, Motion.BASE + 900L)
        }
        col.addView(text(64f, 700).apply { text = title; gravity = Gravity.CENTER })
        col.addView(text(20f, 600, Theme.MUTED).keepCase().apply { text = label.ifBlank { if (kind == Sched.KIND_TIMER) UiText.text(this@MainActivity, R.string.ui_1bb7fcaedb) else UiText.text(this@MainActivity, R.string.ui_4b15f21af0) }; gravity = Gravity.CENTER; setPadding(0, dpi(6f), 0, dpi(28f)) })
        fun send(a: String) { startService(Intent(this, RingService::class.java).setAction(a).putExtra("label", label)); pop() }
        col.addView(button("stop", Theme.colorOf("green")) { send("stop") }, LinearLayout.LayoutParams(-1, -2))
        col.addView(button(UiText.text(this@MainActivity, R.string.ui_8d6a685440), toolAccent, primary = false) { send("snooze") }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        return Screen(page(col).also { it.tag = "ring" }, buddyContext = BuddySceneContext.ALERT)
    }

    internal fun space(start: Agent): Screen {
        val scope = Scope()
        var agent = start
        var showAll = false; var confirmRemove = false; var expandFrom = -1
        var live = emptyMap<String, String>()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = R1ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, -1, -2) }
        val headerBuddy = companion(64f, start)!!
        val headerName = text(28f, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val headerSummary = text(16f, 650, Theme.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        // Come tutti i sotto menu: indietro, titolo, buddy a destra (qui il titolo e' il nome dell'agente con la sua descrizione sotto).
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(70f); setPadding(0, 0, 0, dpi(6f))
            addView(iconButton("back", Theme.accent, filled = false) { pop() })
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12f), 0, 0, 0); addView(headerName); addView(headerSummary) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(headerBuddy, LinearLayout.LayoutParams(dpi(64f), dpi(64f)).apply { marginStart = dpi(4f) })
        }
        val pageBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(header); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)) }
        var headerLook = ""
        val shown = 8
        fun render() {
            agent = db.agents().firstOrNull { it.id == agent.id } ?: return
            val accent = Theme.colorOf(agent.color)
            col.removeAllViews()
            headerName.text = agent.name
            headerSummary.text = agent.prompt.lineSequence().firstOrNull().orEmpty().ifBlank { UiText.text(this@MainActivity, R.string.ui_45c37524b3) }
            val look = "${agent.form}:${agent.color}:${agent.finish}:${agent.eyes}:${agent.wear}"
            if (look != headerLook) { headerBuddy.show(agent); headerLook = look }
            col.addView(row(UiText.text(this@MainActivity, R.string.ui_7f4849bfb0), accent, bold = true, icon = "plus") { push(chat(agent, db.newChat(agent.id))) })
            val chats = db.chats(agent.id).filter { it.count > 0 || it.title.isNotEmpty() }
            if (chats.isNotEmpty()) col.addView(section("chat"))
            val rowsBase = col.childCount
            (if (showAll) chats else chats.take(shown)).forEach { c ->
                col.addView(row(c.title.ifBlank { "chat" }, accent, sub = "${c.count} ${if (c.count == 1) UiText.text(this@MainActivity, R.string.ui_151a6c958d) else UiText.text(this@MainActivity, R.string.ui_947032528d)} · ${ago(c.updated)}" + (if (live[db.hsid(c.id)] in listOf("working", "waiting")) UiText.text(this@MainActivity, R.string.ui_4d3d99856c) else ""), icon = if (live[db.hsid(c.id)] in listOf("working", "waiting")) "spark" else "chat") { openChat(agent, c.id) }.apply { rowTitle(this).maxLines = 2 })
            }
            if (!showAll && chats.size > shown) col.addView(row(UiText.text(this@MainActivity, R.string.ui_6e3552c6eb), Theme.MUTED, sub = UiText.text(this@MainActivity, R.string.ui_abec4365e1, chats.size - shown)) { showAll = true; expandFrom = shown; render() })
            col.addView(section(UiText.text(this@MainActivity, R.string.ui_a299eba5bf)))
            val n = db.memories(agent.id).size
            if (agent.profile.isEmpty()) col.addView(row(UiText.text(this@MainActivity, R.string.ui_7333237b9c), accent, icon = "note", sub = if (n == 0) UiText.text(this@MainActivity, R.string.ui_7b938546c6) else "$n ${if (n == 1) "nota" else UiText.text(this@MainActivity, R.string.ui_c51048b732)}") { push(memoryScreen(agent)) })
            col.addView(row(UiText.text(this@MainActivity, R.string.ui_fd7448c50e), accent, icon = "look", sub = UiText.text(this@MainActivity, R.string.ui_e573d2350b)) { push(editor(agent)) })
            if (hermesMode() && agent.profile.isNotEmpty()) col.addView(row(UiText.text(this@MainActivity, R.string.ui_d2d5097861), accent, icon = "spark", sub = UiText.text(this@MainActivity, R.string.ui_7ca2785861, agent.name)) { push(skillsScreen(agent)) })
            col.addView(row(UiText.text(this@MainActivity, R.string.ui_07832915bd), accent, icon = "sliders", sub = UiText.text(this@MainActivity, R.string.ui_2ad1ad3777)) { push(manageChats(agent)) })
            val all = visibleAgents(); val at = all.indexOfFirst { it.id == agent.id }
            if (at > 0) col.addView(row(UiText.text(this@MainActivity, R.string.ui_a61739c1b8), Theme.MUTED, icon = "chevron") { db.move(agent.id, -1, all.map { it.id }); homeIndex = (at - 1).coerceAtLeast(0); render() })
            if (at in 0 until all.size - 1) col.addView(row(UiText.text(this@MainActivity, R.string.ui_18e4c8d6fb), Theme.MUTED, icon = "chevron") { db.move(agent.id, 1, all.map { it.id }); homeIndex = at + 1; render() })
            if (agent.profile.isEmpty()) col.addView(row(if (confirmRemove) UiText.text(this@MainActivity, R.string.ui_39ced40b15) else UiText.text(this@MainActivity, R.string.ui_235936fba8), Theme.BAD, icon = "close", sub = if (confirmRemove) UiText.text(this@MainActivity, R.string.ui_ff36018e8f) else null) {
                if (!confirmRemove) { confirmRemove = true; render() } else { db.routines().filter { it.agentId == agent.id }.forEach { Sched.cancel(this, Sched.KIND_ROUTINE, it.id) }; db.delete(agent.id); homeIndex = 0; pop() }
            })
            // Chat appena mostrate con «chat meno recenti»: entrano a cascata.
            if (expandFrom >= 0) { Motion.stagger(col, dp(10f), from = (rowsBase + expandFrom).coerceAtMost(col.childCount)); expandFrom = -1 }
        }
        render()
        if (hermesMode() && agent.profile.isNotEmpty()) Io.run { try { syncSessions(agent) } catch (e: Exception) { }; live = dash.live(); runOnUiThread { if (scope.alive) render() } }
        return Screen(page(pageBody), onShow = { confirmRemove = false; render() }, scope = scope)
    }

    /** Il contesto del telefono arriva agli agenti solo se l'utente lo ha acceso in Sicurezza. */
    internal fun applyPhoneContext() { Engine.phoneContext = if (prefs.getBoolean(PhoneContext.PREF, false)) ({ PhoneContext.describe(applicationContext, db, recentApps().mapNotNull { p -> (appCache ?: emptyList()).firstOrNull { it.pkg == p }?.label }) }) else null }
    internal fun performHapticFeedbackSafe() { root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }

}
