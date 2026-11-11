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
                         val isHome: Boolean = false,
                         /** Prima di essere sostituita dalla stessa chat: consegna bozza e allegati in attesa (vedi `chatHandoff`). */
                         val onHandOff: (() -> Unit)? = null) {
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
        // Anteprime del multitasking: servizio di accessibilità che si accende solo dalle impostazioni di Android.
        col.addView(row(UiText.text(this@MainActivity, R.string.ui_748240f22f), Theme.accent, sub = UiText.text(this@MainActivity, R.string.ui_0452cbabe0), check = PreviewService.enabled(this@MainActivity), icon = "recents") {
            if (PreviewService.enabled(this@MainActivity).not()) PreviewService.clear(this@MainActivity)
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
        Engine.deviceExec = deviceExec
        if (android.os.Build.VERSION.SDK_INT >= 33) (backCallback as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it) }
        NetworkMonitor.start(this)
        RoutineEvents.start(this)
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
        if (db.agents().none { it.profile.isEmpty() }) db.save(Agent(0, "kiwi", UiText.text(this@MainActivity, R.string.ui_8c29b079b8), "", "blob", "green")) // prompt nella lingua dell'app
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
                "card" -> if (ag != null) push(listPage(UiText.text(this@MainActivity, R.string.ui_3b225a819e)) { col -> col.addView(cardView(ag, org.json.JSONObject().put("title", UiText.text(this@MainActivity, R.string.ui_0ea049a68d)).put("body", UiText.text(this@MainActivity, R.string.ui_991379a059)))) }); "edit" -> if (ag != null) push(editor(ag)); "dash" -> push(providerScreen()); "jobs" -> push(hermesJobsScreen()); "skills" -> if (ag != null) push(skillsScreen(ag)); "switch" -> push(listPage("interruttori") { col -> col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(text(20f, 700).apply { text = "acceso" }, LinearLayout.LayoutParams(0, -2, 1f)); addView(SwitchView(this@MainActivity, true, Theme.colorOf("green")) {}) }); col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(text(20f, 700).apply { text = "spento" }, LinearLayout.LayoutParams(0, -2, 1f)); addView(SwitchView(this@MainActivity, false, Theme.colorOf("green")) {}) }) }); "md" -> push(listPage("md") { col -> col.addView(text(16f, 500).keepCase().apply { text = Md.render("# titolo\n1. uno\n2. due\n> citazione\n[link](https://example.org) e **grassetto**\n\n| nome | valore |\n|---|---|\n| alfa | 1 |\n| beta lungo | 22 |\n\n```\nprint(1)\n```", Theme.withAlpha(Theme.FG, 0.14f)); movementMethod = android.text.method.LinkMovementMethod.getInstance() }) }); "about" -> push(aboutScreen()); "menu" -> menuSheet(ag); "switcher" -> { if (recentApps().isEmpty()) loadApps().take(4).forEach { noteLaunched(it.pkg) }; root.post { openSwitcher() } }; "shake" -> onShake()
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
            // Aperta con un tocco reale: affiora nell'acqua dal punto del dito, come le pagine. Altrimenti sale come sempre.
            val touch = touchOrigin()
            if (touch != null) ContainerReveal.open(ov, touch, root.width.toFloat(), root.height.toFloat())
            else Motion.enter(ov, dy = dp(36f), dur = Motion.SLOW)
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
        // Si tocca, ma la rotella non si ferma sul buddy (decorativo): prima prendeva il fuoco con un riquadro grigio di sistema.
        cv.isFocusable = false; cv.defaultFocusHighlightEnabled = false
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
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = topRoundRect(Theme.mix(Theme.BG, Theme.CARD, 0.62f), dp(Shapes.SHEET)); isClickable = true; alpha = 0f }
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
                        // Le voci possono stare dentro una scheda di gruppo: si filtrano lì e la scheda sparisce se resta vuota.
                        fun match(v: View) { val t = v.tag as? String; if (t != null) v.visibility = if (q.isEmpty() || (!t.startsWith("§") && t.contains(q))) View.VISIBLE else View.GONE }
                        for (i in 0 until col.childCount) col.getChildAt(i).let { v ->
                            if (v is GroupCard) { for (j in 0 until v.childCount) match(v.getChildAt(j)); v.visibility = if ((0 until v.childCount).any { v.getChildAt(it).visibility == View.VISIBLE }) View.VISIBLE else View.GONE }
                            else match(v)
                        }
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
        groupRows(col, chevrons = false) // anche nei fogli le voci vicine stanno in una scheda sola, con la pillola liquida
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

    internal fun askText(title: String, hint: String, secret: Boolean = false, note: String? = null, onOk: (String) -> Unit) {
        sheetPanel(title) { col, close, then ->
            note?.let { col.addView(this@MainActivity.hint(it)) }
            val f = field(hint, "").apply {
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                if (secret) { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = android.text.method.PasswordTransformationMethod.getInstance() }
            }
            col.addView(f, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
            fun submit() { val t = f.text.toString().trim(); if (t.isNotEmpty()) then { onOk(t) } }
            f.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
            col.addView(button(UiText.text(this@MainActivity, R.string.ui_cc123cba38), Theme.colorOf("green")) { submit() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
            f.post { f.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f, 0) }
        }
    }

    /** Le richieste di prova (`debug-…`, solo build di debug) non vengono da Hermes: nessun avviso. */
    private fun approvalNotSent(r: HermesDash.SrvRequest) = runOnUiThread { if (!isDestroyed && !r.id.toString().startsWith("debug-")) snack(UiText.text(this@MainActivity, R.string.ui_7343aee34f), long = true) }

    /** Una domanda di Hermes: consenti o nega un comando (`approval`), oppure una o più domande dell'agente (`clarify`). */
    @JvmName("showRequest") internal fun showRequest(r: HermesDash.SrvRequest) {
        voiceConversation?.pauseForDialog()
        val id = "req-" + r.id
        root.postDelayed({ cue("surprised", force = true) }, Motion.SLOW * 2) // chiede attenzione
        when (r.method) {
            "approval" -> overlay(id, UiText.text(this@MainActivity, R.string.ui_74be1d4823), { Io.run { if (!dash.answerApproval(r.id, "deny")) approvalNotSent(r) }; closeOverlay(id) }, context = BuddySceneContext.SECURITY) { col, close ->
                col.addView(text(15f, 500, Theme.MUTED).keepCase().apply { text = r.params.optString("description").ifBlank { UiText.text(this@MainActivity, R.string.ui_76522a8687) }; setPadding(0, 0, 0, dpi(8f)) })
                val cmd = r.params.optString("command")
                if (cmd.isNotBlank()) col.addView(text(14f, 500).keepCase().apply {
                    text = cmd.take(1200); typeface = android.graphics.Typeface.MONOSPACE; background = roundRect(Theme.CARD, dp(14f)); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f)) })
                val choices = r.params.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf("once", "deny")
                val labels = mapOf("once" to UiText.text(this@MainActivity, R.string.ui_9522540105), "session" to UiText.text(this@MainActivity, R.string.ui_f0a1e830c8), "always" to UiText.text(this@MainActivity, R.string.ui_2818f5e9df), "deny" to UiText.text(this@MainActivity, R.string.ui_fb80edc0ec))
                for (c in choices) col.addView(button(labels[c] ?: c, if (c == "deny") Theme.BAD else if (c == "always") Theme.colorOf("amber") else Theme.colorOf("green"), filled = c == "once") { Io.run { if (!dash.answerApproval(r.id, c)) approvalNotSent(r) }; close() },
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



    internal var armedKey: String? = null
    internal var armedAt = 0L






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


    internal val sessionSyncAt = HashMap<Long, Long>()
    /** Aperture in corso (id chat, o -id agente): un doppio tocco apriva due chat una sopra l'altra. */
    internal val opening = HashSet<Long>()



    /** La prima schermata: Glance (orologio) o la home, come scelto in Aspetto. */
    internal var rootProvider = ""

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

    internal fun prepareBuddyFlight(chat: Screen, home: Screen, initial: Float, fadePages: Boolean = true): BuddyFlight? {
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
        val flight = BuddyFlight(root, home.view, chat.view, source, target, initial, fadePages = fadePages,
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
        val top = stack.lastOrNull()?.view ?: return null
        // Solo un tocco reale e recente sullo schermo: con la rotella le pagine si aprono come sempre.
        val v = Motion.lastTap?.get()?.takeIf { android.os.SystemClock.uptimeMillis() - Motion.lastTapAt <= 900 } ?: return null
        if (!v.isAttachedToWindow || v.width == 0 || !isDescendant(top, v)) return null
        val l = IntArray(2); v.getLocationInWindow(l); val r = IntArray(2); root.getLocationInWindow(r)
        return android.graphics.RectF((l[0] - r[0]).toFloat(), (l[1] - r[1]).toFloat(), (l[0] - r[0] + v.width).toFloat(), (l[1] - r[1] + v.height).toFloat())
    }

    /** Il punto del tocco reale appena fatto (nel root), come piccolo rettangolo: da lì parte l'acqua. Null con la rotella o se il tocco è vecchio. */
    internal fun touchOrigin(): android.graphics.RectF? {
        if (Motion.reduced(this) || android.os.SystemClock.uptimeMillis() - Motion.lastTapAt > 900) return null
        val r = IntArray(2); root.getLocationOnScreen(r)
        val x = Motion.lastTouchX - r[0]; val y = Motion.lastTouchY - r[1]
        if (x < 0 || y < 0 || x > root.width || y > root.height) return null
        val h = dp(24f)
        return android.graphics.RectF(x - h, y - h, x + h, y + h)
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
        // Dalla voce toccata (o a fuoco con la rotella): va letta PRIMA di mettere in cima la pagina nuova, che non la contiene.
        // Anche da un foglio o da una finestra sopra la pagina: in mancanza della voce, il punto del dito.
        val origin = if (prev != null && !fade && !Motion.reduced(this)) tapOrigin() ?: touchOrigin() else null
        stack.add(s); root.addView(s.view, ViewGroup.LayoutParams(-1, -1)); pinGate?.bringToFront(); runtimeVisibility()
        if (chatLocked(s.chatKey)) { pinUnlocked = false; root.post { if (stack.lastOrNull() === s) showPinGate(false) } }   // chat bloccata: chiede il PIN
        if (prev == null) return
        if (flying) {
            s.heroOrigin = hero ?: Hero(0f, 0f, sourceBuddy!!.width, selected!!); hero = null
            // Con una voce d'origine: il buddy vola come sempre, ma la pagina non sfuma, nasce dalla voce (trasformazione del contenitore).
            if (origin != null) s.origin = origin
            s.view.alpha = if (origin != null) 1f else 0f
            val entry = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    s.view.viewTreeObserver.removeOnPreDrawListener(this); s.pendingEntry = null
                    val flight = if (stack.lastOrNull() === s) prepareBuddyFlight(s, prev, 0f, fadePages = origin == null) else null
                    if (flight != null) {
                        if (origin != null) {
                            ContainerReveal.open(s.view, origin, root.width.toFloat(), root.height.toFloat())
                            prev.view.animate().alpha(dimmed).scaleX(0.96f).scaleY(0.96f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withLayer().start()
                        }
                        flight.animateTo(1f)
                    } else { s.view.alpha = 1f; detach(prev.view); clearCarriedBuddy() }
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
        if (origin != null) {
            // La pagina nasce dall'elemento toccato (riga, scheda, pulsante): parte piccola dov'era e si espande; quella sotto si allontana appena.
            // Trasformazione del contenitore: la voce si allarga e diventa la pagina (ritaglio con la sua forma, velo dell'accento che svanisce).
            s.origin = origin
            if (Motion.reduced(this)) { s.view.alpha = 0f; s.view.animate().alpha(1f).setDuration(Motion.BASE).start() }
            else ContainerReveal.open(s.view, origin, root.width.toFloat(), root.height.toFloat())
            prev.view.animate().scaleX(0.94f).scaleY(0.94f).alpha(dimmed).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withLayer().withEndAction { if (stack.lastOrNull() !== prev) detach(prev.view) }.start()
            return
        }
        s.view.translationX = w
        s.view.animate().translationX(0f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withLayer().start()
        prev.view.animate().translationX(-w * parallax).alpha(dimmed).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).withLayer().withEndAction { if (stack.lastOrNull() !== prev) detach(prev.view) }.start()
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
            val into: android.graphics.RectF? = null // l'acqua solo all'apertura: chiudendo il volo del buddy come sempre
            val flight = prepareBuddyFlight(s, back, 1f, fadePages = into == null)
            if (flight != null) {
                if (into != null) {
                    // Il buddy torna in volo e la pagina si richiude nella voce da cui era nata; quella sotto si riaccende.
                    back.view.animate().cancel()
                    back.view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Theme.SETTLE).start()
                    ContainerReveal.close(s.view, into, root.width.toFloat(), root.height.toFloat()) {}
                }
                runtimeVisibility(); flight.animateTo(0f); return
            }
        }
        val w = pageWidth()
        val origin: android.graphics.RectF? = null // l'acqua solo all'apertura: chiudendo la pagina scorre via come sempre
        if (!fromDrag || back.view.parent == null) {
            detach(back.view)
            if (origin != null) { back.view.scaleX = 0.94f; back.view.scaleY = 0.94f; back.view.alpha = dimmed } else { back.view.translationX = -w * parallax; back.view.alpha = dimmed }
            root.addView(back.view, 0, ViewGroup.LayoutParams(-1, -1))
        }
        back.view.animate().cancel()
        back.view.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(if (fromDrag) Motion.BASE else Motion.SLOW).setInterpolator(Theme.SETTLE).withLayer().start()
        s.view.animate().cancel()
        if (origin != null) {
            // Torna nell'elemento da cui era nata: si rimpicciolisce verso di esso e sfuma.
            // Si richiude dentro la voce da cui era nata, che riprende il colore della goccia mentre si stringe.
            if (Motion.reduced(this)) s.view.animate().alpha(0f).setDuration(Motion.BASE).withEndAction { root.removeView(s.view); s.view.alpha = 1f }.start()
            else ContainerReveal.close(s.view, origin, root.width.toFloat(), root.height.toFloat()) { root.removeView(s.view) }
        } else {
            s.view.animate().translationX(w).setDuration(Motion.BASE).setInterpolator(Motion.EXIT).withLayer().withEndAction { root.removeView(s.view); s.view.translationX = 0f }.start()
        }
        runtimeVisibility()
        showScreen(back)
    }

    /** Indietro dal sistema: con Android 13+ arriva da OnBackInvokedCallback (registrata in onCreate), prima da onBackPressed. */
    @Deprecated("Deprecated in Java") override fun onBackPressed() = handleBack()

    internal val backCallback: Any? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { handleBack() } else null
    }

    @JvmName("handleBack") internal fun handleBack() {
        if (pinGate != null) { if (gateWanted()) leaveLockedChat() else if (!isDefaultLauncher()) finish(); return }
        appSwitcher?.let { it.close(); return }
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
        appSwitcher?.let { sw -> if (e.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e); if (e.keyCode == KeyEvent.KEYCODE_BUTTON_1 || e.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) return true; if (sw.key(e)) return true }
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
    /** Bozza e allegati in attesa (chat, testo, file) passati da una chat alla sua sostituta. */
    internal var chatHandoff: Triple<Long, String, List<Attachment>>? = null
    internal var welcomeChecked = false
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
        if (Engine.deviceExec === deviceExec) Engine.deviceExec = null
        tts?.shutdown(); super.onDestroy()
    }
    /** Schermata dell'app in primo piano: con l'app in secondo piano o lo schermo spento i tick (orologio, widget) saltano il lavoro. */
    @Volatile internal var visible = true
    internal fun characters(v: View, f: (CharacterView) -> Unit) { if (v is CharacterView) f(v) else if (v is ViewGroup) for (i in 0 until v.childCount) characters(v.getChildAt(i), f) }
    // ---- scossa: scuotendo il dispositivo i buddy si confondono (giro su se' stessi con la faccia stordita) ----------
    internal var shakeListener: android.hardware.SensorEventListener? = null
    internal var lastShake = 0L
    // startShake(), stopShake(), onShake(): BackupUi.kt (in fondo)
    override fun onStart() { super.onStart(); watchPackages(true); if (::dash.isInitialized) dash.background(false) }
    override fun onStop() { if (switcherLaunched) appSwitcher?.dismissNow(); watchPackages(false); if (::dash.isInitialized) dash.background(true); super.onStop() }
    override fun onResume() { super.onResume(); LiquidLaunch.clear(root); startShake(); visible = true; root.removeCallbacks(buddyReaper); root.postDelayed(buddyReaper, 1200L); dev.r1os.host.ui.Spring.reduced = Motion.reduced(this); runtimeVisibility()
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
        if (swSwallow && ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) { if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swSwallow = false; return true }   // resto del gesto che ha aperto il selettore
        if (appSwitcher != null) return super.dispatchTouchEvent(ev)   // il selettore gestisce da sé dito e rotella
        val wheel = ev.device?.name?.contains("wheel", ignoreCase = true) == true
        if (!wheel && switcherGesture(ev)) return true
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

    // Multitasking col dito: dal fondo dello schermo scorri in alto e tieni fermo un attimo (come i recenti di sistema).
    private var swStartY = -1f
    private var swStartX = 0f
    private var swSwallow = false
    private val swHold = Runnable {
        if (swStartY < 0f || appSwitcher != null) return@Runnable
        swStartY = -1f; swSwallow = true
        // Le pagine sotto smettono di seguire il dito: il gesto ora è del selettore.
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        openSwitcher()
    }

    /** Vero se l'evento è stato preso dal gesto del multitasking. */
    private fun switcherGesture(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swSwallow = false; root.removeCallbacks(swHold)
                val h = window.decorView.height   // coordinate della finestra, come quelle dell'evento
                swStartY = if (pinGate == null && pttView == null && voiceConversation == null && h > 0 && ev.y > h - dp(96f) && ev.y < h - dp(18f)) ev.y else -1f
                swStartX = ev.x
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (swSwallow) return true
                if (swStartY >= 0f) {
                    val up = swStartY - ev.y; val side = Math.abs(ev.x - swStartX)
                    if (up < -dp(12f) || (side > dp(28f) && side > up)) { swStartY = -1f; root.removeCallbacks(swHold) }
                    else if (up > dp(72f)) { root.removeCallbacks(swHold); root.postDelayed(swHold, 260L) }   // si apre quando il dito si ferma
                    else root.removeCallbacks(swHold)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                root.removeCallbacks(swHold); swStartY = -1f
                if (swSwallow) { swSwallow = false; return true }
            }
        }
        return swSwallow
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
                    if (Backup.isEncrypted(text)) runOnUiThread {
                        // Backup protetto: prima la password, poi lo stesso riepilogo di sempre.
                        askText(UiText.text(this@MainActivity, R.string.ui_26514a5595), UiText.text(this@MainActivity, R.string.ui_26514a5595), secret = true, note = UiText.text(this@MainActivity, R.string.ui_00dead5d37)) { pw ->
                            Io.run {
                                try { val plain = Backup.decrypt(text, pw.toCharArray()); val sum = Backup.inspect(plain); runOnUiThread { confirmImport(plain, sum) } }
                                catch (e: Exception) { runOnUiThread { snack(UiText.message(this@MainActivity, e.message) ?: UiText.text(this@MainActivity, R.string.ui_23a3dc3076), long = true) } }
                            }
                        }
                    } else {
                        val sum = Backup.inspect(text)
                        runOnUiThread { confirmImport(text, sum) }
                    }
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

    // startExport(), startImport(), confirmImport(), doImport(): BackupUi.kt

    // ---- allegati: foto, immagini, file, e "Condividi" da altre app -----------------------------------------

    internal var attachHook: ((Attachment) -> Unit)? = null
    internal var photoFile: java.io.File? = null
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); photoFile?.let { out.putString("photo_file", it.path) } }
    internal var photoAttachTarget: ((Attachment) -> Unit)? = null
    internal var fileAttachTarget: ((Attachment) -> Unit)? = null



    /** "Condividi › R1 Agents" da un'altra app: testo, link, immagini, PDF. Si sceglie l'agente e la chat si apre con tutto pronto da inviare. */
    @Suppress("DEPRECATION")

    // ---- traduttore ---------------------------------------------------------------------------

    internal val trLangs get() = AppLanguage.wrap(this).resources.getStringArray(R.array.translation_language_names).toList()
    internal var trResult = ""

    // ---- strumenti: sveglie, timer, routine ------------------------------------------------------

    internal val toolAccent get() = Theme.accent

    internal var daysRepaint: (() -> Unit)? = null







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
    // Azioni del dispositivo: DeviceActionRunner.kt
    /** Azioni chieste da un agente con lo strumento phone_action in modalità automatica: esito vero all'agente, avviso all'utente. */
    private val deviceExec: (DeviceActions.Action) -> Boolean = { a ->
        val ok = !isDestroyed && runDeviceAction(a)
        if (!isDestroyed) snack(if (ok) UiText.text(this@MainActivity, R.string.ui_dab426e957, describeAction(a)) else UiText.text(this@MainActivity, R.string.ui_606d15c3e0, describeAction(a)), long = !ok)
        ok
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
    internal fun noteLaunched(pkg: String) { prefs.edit().putString("recent_apps", (listOf(pkg) + recentApps().filter { it != pkg }).take(8).joinToString(",")).apply() }
    internal fun forgetRecent(pkg: String) { prefs.edit().putString("recent_apps", recentApps().filter { it != pkg }.joinToString(",")).apply() }

    // ---- multitasking (AppSwitcher): app recenti come schede che fluttuano --------------------------------
    internal var appSwitcher: AppSwitcher? = null
    internal var switcherLaunched = false

    // openSwitcher(): AppSwitcher.kt (in fondo)
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
        // La goccia della voce si gonfia fino a riempire lo schermo e l'app compare da lì, in dissolvenza.
        val from = tapOrigin()
        LiquidLaunch.start(root, from) {
            try {
                // Android 17 decide da solo come entra un'app esterna (scorrendo da destra) e ignora le animazioni personalizzate
                // e la rivelazione a ritaglio. Senza animazione di sistema l'app compare quando il fluido ha già coperto lo schermo:
                // l'unico movimento che si vede è il nostro.
                // Il ritorno dall'app resta quello di sistema: su Android 17 l'uscita di un'altra app non si può sfumare né cambiare.
                val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or (if (from != null && !Motion.reduced(this)) Intent.FLAG_ACTIVITY_NO_ANIMATION else 0)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(a.pkg, a.cls).addFlags(flags)); true
            } catch (e: Exception) { snack(UiText.text(this@MainActivity, R.string.ui_f2c6915e4c)); false }
        }
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

    // ringScreen() e space(): SpaceScreen.kt
    internal fun applyPhoneContext() { Engine.phoneContext = if (prefs.getBoolean(PhoneContext.PREF, false)) ({ PhoneContext.describe(applicationContext, db, recentApps().mapNotNull { p -> (appCache ?: emptyList()).firstOrNull { it.pkg == p }?.label }) }) else null }
    internal fun performHapticFeedbackSafe() { root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }

}
