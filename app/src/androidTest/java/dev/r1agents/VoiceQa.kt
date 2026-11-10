package dev.r1agents

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.io.File

/** Microfono simulato e server loopback: non registra né invia dati agli agenti dell'utente. */
internal class VoiceQa(private val test: Instrumentation) {
    private lateinit var activity: MainActivity
    private lateinit var mode: VoiceConversation
    private lateinit var fake: FakeMic
    private val ctx get() = test.targetContext
    private fun step(s: String) { test.sendStatus(1, Bundle().apply { putString("stream", "$s\n") }) }
    private fun ui(action: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun await(label: String, testValue: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 12000
        while (android.os.SystemClock.uptimeMillis() < end) { if (testValue()) return; Thread.sleep(40) }
        error("Timeout: $label")
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun root() = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(activity) as android.widget.FrameLayout
    private fun set(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun screenshot(name: String) {
        val file = File(ctx.getExternalFilesDir(null), "voice-qa/$name.png").apply { parentFile?.mkdirs() }
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("Screenshot non disponibile")
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
    private fun rotate(orientation: Int) {
        ui { activity.requestedOrientation = orientation }
        val wanted = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        await("rotazione $wanted") { activity.resources.configuration.orientation == wanted }
        test.waitForIdleSync(); Thread.sleep(350)
        check(!activity.isDestroyed) { "La rotazione ha distrutto l'Activity" }
    }
    private fun flight(): BuddyFlight? = MainActivity::class.java.getDeclaredField("buddyFlight").apply { isAccessible = true }.get(activity) as? BuddyFlight
    private fun clonePose(): List<Float> {
        val clone = root().findViewWithTag<View>("buddy-flight") ?: error("Buddy di transizione assente")
        return listOf(clone.translationX, clone.translationY, clone.scaleX, clone.scaleY)
    }
    private fun openCarouselChat() {
        ui { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)) }
        await("volo dal carosello") { var present = false; ui { present = flight() != null }; present }
    }
    private fun transitionChecks() {
        openCarouselChat()
        lateinit var entering: List<Float>
        ui { flight()!!.seek(.5f); entering = clonePose() }
        Thread.sleep(100); screenshot("chat-entry-half")
        ui { flight()!!.animateTo(1f) }
        await("ingresso concluso") { flight() == null }
        ui { activity.onBackPressed() }
        await("volo di ritorno") { flight() != null }
        ui {
            flight()!!.seek(.5f)
            val exiting = clonePose()
            check(entering.zip(exiting).all { (a, b) -> kotlin.math.abs(a - b) < 1f }) { "L'uscita non ripercorre l'ingresso: $entering / $exiting" }
        }
        Thread.sleep(100); screenshot("chat-exit-half")
        ui { flight()!!.animateTo(0f) }
        await("ritorno concluso") { flight() == null }
        ui { check(root().findViewWithTag<View>("buddy-flight") == null) }
        step("ingresso e uscita chat: stessa geometria e scala a meta' percorso: OK")
        openCarouselChat(); ui { flight()!!.animateTo(1f) }; await("chat pronta al gesto") { flight() == null }
        fun gesture(cancel: Boolean) {
            ui {
                val now = android.os.SystemClock.uptimeMillis(); val y = root().height * .5f
                fun touch(action: Int, x: Float, time: Long) {
                    val e = android.view.MotionEvent.obtain(now, now + time, action, x, y, 0)
                    try { activity.dispatchTouchEvent(e) } finally { e.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN, 3f, 0)
                touch(android.view.MotionEvent.ACTION_MOVE, root().width * .2f, 180)
                check(flight() != null && flight()!!.progress < 1f) { "Il gesto non controlla la transizione" }
                if (cancel) touch(android.view.MotionEvent.ACTION_CANCEL, root().width * .2f, 250)
                else { touch(android.view.MotionEvent.ACTION_MOVE, root().width * .6f, 400); touch(android.view.MotionEvent.ACTION_UP, root().width * .6f, 450) }
            }
            await("gesto concluso") { flight() == null }
        }
        gesture(true); gesture(false)
        ui { check(root().findViewWithTag<View>("buddy-flight") == null) }
        step("gesto indietro reversibile: annullamento e ritorno al carosello: OK")
    }
    private fun heldVoiceNavigationChecks() {
        val factory: ((String) -> Unit, (String) -> Unit, (String?) -> Unit) -> Mic = { partial, final, end -> FakeMic(final, end, partial).also { fake = it } }
        var x = 0f; var y = 0f
        val start = android.os.SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        ui {
            set("qaMicrophone", factory)
            val label = views(root()).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == activity.getString(R.string.ui_1cbfd76bb1) }
            val at = IntArray(2); label.getLocationInWindow(at); x = at[0] + label.width / 2f; y = at[1] + label.height / 2f
            touch(android.view.MotionEvent.ACTION_DOWN)
            mode = MainActivity::class.java.getDeclaredField("voiceConversation").apply { isAccessible = true }.get(activity) as VoiceConversation
        }
        var entry: List<Float>? = null
        if (!Motion.reduced(activity)) {
            await("volo verso la voce") { flight() != null }
            ui { flight()!!.seek(.5f); entry = clonePose(); flight()!!.animateTo(1f) }
        }
        await("voce arrivata durante pressione") { flight() == null && mode.alpha > .99f }
        Thread.sleep(120)
        ui {
            check(!fake.cancelled && fake.starts == 1) { "Staccare il carosello ha annullato la pressione vocale" }
            touch(android.view.MotionEvent.ACTION_UP)
            check(fake.stops == 1) { "Il rilascio non ha raggiunto il microfono" }
            fake.final("")
            activity.onBackPressed()
        }
        if (entry != null) {
            await("volo di ritorno dalla voce") { flight() != null }
            ui {
                flight()!!.seek(.5f)
                val exit = clonePose()
                check(entry!!.zip(exit).all { kotlin.math.abs(it.first - it.second) < .02f }) { "Percorso vocale non inverso: $entry / $exit" }
                flight()!!.animateTo(0f)
            }
        }
        await("voce rimossa dopo il ritorno") { root().indexOfChild(mode) < 0 && flight() == null }
        ui {
            touch(android.view.MotionEvent.ACTION_DOWN)
            mode = MainActivity::class.java.getDeclaredField("voiceConversation").apply { isAccessible = true }.get(activity) as VoiceConversation
        }
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        ui { touch(android.view.MotionEvent.ACTION_UP); check(root().indexOfChild(mode) >= 0 && fake.cancelled) { "La rotazione della pressione iniziale ha chiuso la voce" }; activity.onBackPressed() }
        await("uscita dopo rotazione della pressione") { root().indexOfChild(mode) < 0 && flight() == null }
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val childrenBefore = root().childCount
        ui { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_1)) }
        await("nuovo ingresso vocale rapido") { flight() != null }
        ui { flight()!!.seek(.7f); activity.onBackPressed() }
        Thread.sleep(60)
        ui {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_1))
            mode = MainActivity::class.java.getDeclaredField("voiceConversation").apply { isAccessible = true }.get(activity) as VoiceConversation
        }
        await("rientro prima della fine dell'uscita") { flight() == null && mode.alpha > .99f }
        ui { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_1)); fake.final(""); activity.onBackPressed() }
        await("uscita dopo rientro rapido") { root().indexOfChild(mode) < 0 && flight() == null }
        ui { check(root().childCount == childrenBefore) { "Una vecchia scena vocale è rimasta sopra il carosello" }; set("qaMicrophone", null) }
        val agent = Db.get(ctx).agents().first()
        ui {
            mode = VoiceConversation(activity, agent, true, { error("Nessun invio nella prova di reflow") }, factory, close = { activity.onBackPressed() })
            MainActivity::class.java.getDeclaredMethod("presentVoice", VoiceConversation::class.java).apply { isAccessible = true }.invoke(activity, mode)
        }
        await("ingresso per risposta rapida") { flight() != null }
        ui {
            flight()!!.seek(.5f); mode.cancelRecording()
            VoiceConversation::class.java.getDeclaredMethod("render", TurnProgress.Snapshot::class.java).apply { isAccessible = true }.invoke(mode, TurnProgress.Snapshot(1, "risposta rapida simulata", status = TurnProgress.Status.RUNNING))
        }
        test.waitForIdleSync(); Thread.sleep(120)
        ui {
            flight()!!.seek(.999f)
            val anchor = mode.findViewWithTag<View>("scene-buddy")
            val matrix = android.graphics.Matrix(); anchor.transformMatrixToGlobal(matrix); root().transformMatrixToLocal(matrix)
            val bounds = android.graphics.RectF(0f, 0f, anchor.width.toFloat(), anchor.height.toFloat()).also { matrix.mapRect(it) }
            val actor = root().findViewWithTag<View>("buddy-flight")
            check(kotlin.math.abs(actor.translationY + actor.height / 2f - bounds.centerY()) < 3f && kotlin.math.abs(actor.width * actor.scaleX - bounds.width()) < 3f) { "Il buddy arriva alla posizione precedente al fumetto" }
            flight()!!.animateTo(1f)
        }
        await("risposta rapida assestata") { flight() == null }
        ui { activity.onBackPressed() }
        await("chiusura risposta rapida") { root().indexOfChild(mode) < 0 && flight() == null }
        step("pressione reale dell'interfaccia con mic simulato: distacco del carosello, rilascio, percorso inverso, rientro rapido e reflow del fumetto: OK")
    }
    private fun menuContinuityChecks() {
        var emptyFrames = 0
        var checkedFrames = 0
        var originalChildren = 0
        ui { originalChildren = root().childCount }
        fun visibleBuddy(): Boolean = views(root()).filterIsInstance<dev.r1os.host.ui.CharacterView>().any { view ->
            val tag = view.tag as? String ?: ""
            if (tag != "scene-buddy" && tag != "buddy-flight" && tag != "buddy-carry" && !tag.startsWith("home-agent-")) false
            else {
                var alpha = view.alpha; var parent = view.parent
                while (parent is View) { alpha *= parent.alpha; parent = parent.parent }
                view.isShown && alpha > .02f && view.getGlobalVisibleRect(android.graphics.Rect()) && view.width > 0
            }
        }
        val watcher = android.view.ViewTreeObserver.OnPreDrawListener {
            checkedFrames++; if (!visibleBuddy()) emptyFrames++; true
        }
        fun stackSize() = (MainActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }.get(activity) as List<*>).size
        fun clickText(text: String) {
            val label = views(root()).filterIsInstance<TextView>().lastOrNull { it.isShown && it.text.toString().equals(text, ignoreCase = true) } ?: error("Voce non trovata: $text")
            var target: View? = label
            while (target != null && !target.isClickable) target = target.parent as? View
            check(target?.performClick() == true) { "Voce non attivabile: $text" }
        }
        ui { root().viewTreeObserver.addOnPreDrawListener(watcher); test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "menu")) }
        Thread.sleep(100)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        ui {
            val peek = views(root()).filterIsInstance<dev.r1os.host.ui.CharacterView>().last { it.isShown && it.alpha > .5f }
            val rect = android.graphics.Rect(); check(peek.getGlobalVisibleRect(rect) && rect.height() >= peek.height * peek.scaleY - 1) { "Buddy tagliato dopo la rotazione del menu" }
        }
        screenshot("menu-buddy")
        ui { clickText("impostazioni") }
        await("menu verso impostazioni") { var ready = false; ui { ready = stackSize() == 2 && flight() == null && root().findViewWithTag<View>("buddy-carry") == null }; ready }
        Thread.sleep(150); screenshot("settings-pinned-buddy")
        ui { clickText("aspetto") }
        await("sottomenu aspetto") { var ready = false; ui { ready = stackSize() == 3 && flight() == null }; ready }
        Thread.sleep(150); screenshot("submenu-buddy")
        ui {
            val scroll = views(root()).filterIsInstance<R1ScrollView>().first { it.isShown }
            scroll.fullScroll(View.FOCUS_DOWN)
        }
        Thread.sleep(200)
        ui { check(visibleBuddy()) { "Il buddy esce dallo schermo scorrendo il sottomenu" } }
        screenshot("submenu-scrolled-buddy")
        ui { activity.onBackPressed() }; await("ritorno a impostazioni") { flight() == null }; Thread.sleep(80)
        ui { activity.onBackPressed() }; await("ritorno al carosello") { flight() == null }; Thread.sleep(80)
        ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "menu")) }
        Thread.sleep(450)
        ui { activity.onBackPressed() }
        Thread.sleep(80)
        ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "settings")) }
        await("nuova destinazione durante la chiusura del menu") { flight() == null && stackSize() == 2 }
        ui { activity.onBackPressed() }; await("ritorno dopo interruzione") { flight() == null }
        Thread.sleep(120)
        ui {
            check(root().childCount == originalChildren) { "Un pannello chiuso e' rimasto sopra la pagina: ${root().childCount} / $originalChildren" }
            root().viewTreeObserver.removeOnPreDrawListener(watcher)
        }
        check(checkedFrames > 0 && emptyFrames == 0) { "Buddy assente in $emptyFrames / $checkedFrames frame della navigazione" }
        step("carosello → menu → impostazioni → sottomenu e ritorno: buddy visibile in $checkedFrames frame, anche scorrendo: OK")
    }
    private fun clockAndTimerChecks() {
        ui {
            val screen = Class.forName("dev.r1agents.GlanceScreenKt").getDeclaredMethod("glance", MainActivity::class.java).apply { isAccessible = true }.invoke(null, activity)
            val view = screen.javaClass.getDeclaredField("view").apply { isAccessible = true }.get(screen) as View
            @Suppress("UNCHECKED_CAST")
            val refresh = screen.javaClass.getDeclaredField("onResume").apply { isAccessible = true }.get(screen) as () -> Unit
            repeat(6) { refresh() }
            check(views(view).filterIsInstance<dev.r1os.host.ui.CharacterView>().size == 1) { "L'orologio accumula copie del buddy" }
        }
        val ticks = java.util.concurrent.atomic.AtomicInteger()
        lateinit var anchor: android.widget.FrameLayout
        ui { anchor = android.widget.FrameLayout(activity); ViewTicker(anchor, 100L, { true }) { ticks.incrementAndGet() }; root().addView(anchor, ViewGroup.LayoutParams(1, 1)) }
        await("ticker collegato") { ticks.get() >= 2 }
        ui { root().removeView(anchor) }
        val stopped = ticks.get(); Thread.sleep(260)
        check(ticks.get() == stopped) { "Ticker ancora attivo dopo il distacco" }
        ui { root().addView(anchor, ViewGroup.LayoutParams(1, 1)) }
        await("ticker ripreso dopo il ritorno") { ticks.get() > stopped }
        ui { root().removeView(anchor) }
        val db = Db.get(ctx)
        val timer = db.addTimer(System.currentTimeMillis() + 850L, "QA ultimo secondo")
        try {
            ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "timers")) }
            Thread.sleep(1500); test.waitForIdleSync()
            ui {
                check(!activity.isDestroyed)
                check(views(root()).filterIsInstance<TextView>().none { it.isShown && it.text.toString() == "00:00" }) { "Timer scaduto ancora nella lista" }
                activity.onBackPressed()
            }
            await("ritorno dai timer") { flight() == null }
        } finally { db.deleteTimer(timer) }
        step("orologio senza copie, ticker fermo/ripreso e scadenza timer nell'ultimo secondo: OK")
    }
    /** Scorrendo il buddy affonda a metà dietro le righe (BuddyScrollFx): visibile, più piccolo e tenue, non sparito. */
    private fun assertBuddyPeeking() {
        ui {
            val buddy = views(root()).filterIsInstance<dev.r1os.host.ui.CharacterView>().last { it.isShown && it.tag == "scene-buddy" }
            check(buddy.alpha > .3f && buddy.scaleX > .6f) { "Buddy sparito scorrendo: alpha=${buddy.alpha} scala=${buddy.scaleX}" }
        }
    }
    private fun assertBuddyUncovered() {
        var x = 0; var y = 0
        ui {
            val buddy = views(root()).filterIsInstance<dev.r1os.host.ui.CharacterView>().last { it.isShown && it.tag == "scene-buddy" }
            val at = IntArray(2); buddy.getLocationOnScreen(at)
            x = at[0] + buddy.width / 2; y = at[1] + buddy.height / 2
        }
        val shot = test.uiAutomation.takeScreenshot() ?: error("Screenshot non disponibile")
        try {
            val pixel = shot.getPixel(x, y)
            check(android.graphics.Color.green(pixel) > 70 && android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) * 1.3f) { "Il contenuto scorrevole copre il centro del buddy: $x,$y, colore=$pixel" }
        } finally { shot.recycle() }
    }
    private fun wheelGesture(scroll: R1ScrollView) {
        val device = android.view.InputDevice.getDeviceIds().map { android.view.InputDevice.getDevice(it) }.filterNotNull().firstOrNull { it.name.contains("wheel", true) }
            ?: error("Dispositivo rotella non trovato")
        val rotation = scroll.display?.rotation ?: 0
        val direction = r1WheelSign(rotation)
        val horizontal = rotation == 1 || rotation == 3
        val x = root().width * .5f; val y = root().height * .12f // la rotella deve funzionare anche sopra il buddy fisso
        val now = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, offset: Float): android.view.MotionEvent {
            val properties = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
            val coords = android.view.MotionEvent.PointerCoords().apply { this.x = x - if (horizontal) offset * direction else 0f; this.y = y - if (horizontal) 0f else offset * direction; pressure = 1f; size = 1f }
            return android.view.MotionEvent.obtain(now, now + (offset + 1).toLong(), action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, device.id, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        scroll.scrollTo(0, 0)
        for ((action, offset) in listOf(android.view.MotionEvent.ACTION_DOWN to 0f, android.view.MotionEvent.ACTION_MOVE to 64f, android.view.MotionEvent.ACTION_UP to 64f)) {
            val e = event(action, offset); try { activity.dispatchTouchEvent(e) } finally { e.recycle() }
        }
    }
    private class FakeMic(val final: (String) -> Unit, val end: (String?) -> Unit, val partial: (String) -> Unit) : Mic {
        var cancelled = false
        var starts = 0
        var stops = 0
        var phase: ((MicPhase) -> Unit)? = null
        var level: ((Float) -> Unit)? = null
        override fun onLevel(listener: (Float) -> Unit) { level = listener }
        override fun start(): Boolean { starts++; phase?.invoke(MicPhase.LISTENING); partial("TRASCRIZIONE PRIVATA NON VISIBILE"); return true }
        override fun stop() { stops++; phase?.invoke(MicPhase.TRANSCRIBING) }
        override fun cancel() { cancelled = true }
        override fun onPhase(listener: (MicPhase) -> Unit) { phase = listener }
    }
    fun run() {
        check(ctx.packageName.endsWith(".qa"))
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        check(power.isInteractive) { "Riattiva e sblocca il Rabbit" }
        @Suppress("DEPRECATION") val wake = power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "r1agents.qa:voice")
        wake.acquire(90000)
        val mock = MockHermes()
        val samples = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val phases = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Long>>()
        val category = java.util.concurrent.atomic.AtomicReference("preparazione")
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var originalRotation = android.view.Surface.ROTATION_0
        try {
            test.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            val prefs = ctx.getSharedPreferences("settings", 0)
            prefs.edit().putString("provider", "dashboard").putString("dev_actions", "off").putString("dash_url", "http://127.0.0.1:${mock.port}").putString("dash_user", "qa").putSecret("dash_pass", "qa").putString("app_language", "it").putString("look_mode", "dark").putBoolean("voice_tts", false).putInt("home_index", 0).putString("start", "home").remove("app_pin_hash").remove("running_hermes_chats").commit()
            ctx.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            val db = Db.get(ctx); db.agents().forEach { db.delete(it.id) }
            val agent = Agent(0, "kiwi", "", "", "blob", "green", profile = "qa").also { db.save(it) }
            val chat = db.newChat(agent.id)
            activity = test.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            originalOrientation = activity.requestedOrientation
            originalRotation = activity.display?.rotation ?: android.view.Surface.ROTATION_0
            step("display richiesto: mode=${activity.window.attributes.preferredDisplayModeId}, Hz=${activity.window.attributes.preferredRefreshRate}")
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            heldVoiceNavigationChecks()
            check(mock.promptCount == 0)
            ui {
                val bitmapField = dev.r1os.host.ui.CharacterView::class.java.getDeclaredField("bodyBitmap").apply { isAccessible = true }
                val a = dev.r1os.host.ui.CharacterView(activity, 180).apply { show(agent) }
                val b = dev.r1os.host.ui.CharacterView(activity, 180).apply { show(agent) }
                val shared = bitmapField.get(a) as Bitmap
                check(shared === bitmapField.get(b)) { "Il corpo del buddy non viene riusato" }
                b.set("orb", "orange", "matte", "idle")
                check(!shared.isRecycled && bitmapField.get(a) === shared) { "Cambio aspetto ricicla una bitmap ancora usata" }
                val beforePose = bitmapField.get(b)
                a.setScenePose("working", "build"); b.copyScenePoseFrom(a)
                check(bitmapField.get(b) === beforePose) { "La posa contestuale ha cambiato il corpo del buddy" }
                a.setActive(false); b.setActive(false)
                step("cache bitmap buddy condivisa senza riciclo di immagini in uso: OK")
                val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                    if (dropped == 0) {
                        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                        samples.add(duration)
                        phases.getOrPut(category.get()) { java.util.concurrent.CopyOnWriteArrayList() }.add(duration)
                    }
                }
                activity.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
                views(root()).filterIsInstance<dev.r1os.host.ui.CharacterView>().forEach { it.setActive(false) }
                mode = VoiceConversation(activity, agent, true, { chat }, { partial, final, end -> FakeMic(final, end, partial).also { fake = it } }, onChatVisible = { MainActivity::class.java.getDeclaredMethod("runtimeVisibility").apply { isAccessible = true }.invoke(activity) }, close = { activity.onBackPressed() })
                MainActivity::class.java.getDeclaredMethod("presentVoice", VoiceConversation::class.java).apply { isAccessible = true }.invoke(activity, mode)
                // Nessun audio durante QA; la riproduzione locale si prova poi con la voce scelta dall'utente.
                check(views(mode).filterIsInstance<TextView>().none { it.text.toString() in listOf("voce sì", "voce no") }) { "Controllo audio rimasto nella scena vocale" }
            }
            check(fake.starts == 1)
            Thread.sleep(500); category.set("ascolto"); repeat(18) { i -> ui { fake.level?.invoke((i % 6) / 5f) }; Thread.sleep(100) }; screenshot("listening-liquid")
            ui { check(views(mode).filterIsInstance<TextView>().none { it.text.contains("TRASCRIZIONE PRIVATA") }); mode.cancelRecording(); fake.final("non inviare") }
            Thread.sleep(250); check(mock.promptCount == 0)
            step("annullamento e trascrizione privata: OK")
            category.set("attesa")
            ui { mode.startListening(); mode.release(); fake.final("normal"); fake.final("duplicato") }
            await("un solo invio") { mock.promptCount == 1 }
            category.set("rotazione")
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            check(mock.promptCount == 1)
            check(root().indexOfChild(mode) >= 0)
            await("risposta salvata") { db.messages(chat).any { it.first == "assistant" && it.second.contains("risposta normal") } }
            test.waitForIdleSync()
            await("risposta disegnata") { var shown = false; ui { shown = views(mode).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "risposta normal" } }; shown }
            Thread.sleep(500); category.set("risposta_inattiva"); Thread.sleep(1800)
            screenshot("reply-landscape")
            category.set("rotazione")
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); screenshot("reply-portrait")
            check(mock.promptCount == 1)
            ui { check(HermesRuntime.get(ctx).visibleChat == chat) { "La scena vocale non risulta visibile al runtime" } }
            check(ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == (0x44000000 or (chat.toInt() and 0xffffff)) }) { "Notifica duplicata per la risposta vocale visibile" }
            step("risposta, invio unico, rotazione e notifica non duplicata: OK")
            category.set("streaming_simulato")
            val render = VoiceConversation::class.java.getDeclaredMethod("render", TurnProgress.Snapshot::class.java).apply { isAccessible = true }
            repeat(18) { i -> ui { render.invoke(mode, TurnProgress.Snapshot(i.toLong(), "risposta normal " + "testo in arrivo ".repeat(i + 1))) }; Thread.sleep(80) }
            ui { render.invoke(mode, TurnProgress.Snapshot(99, "risposta normal", status = TurnProgress.Status.DONE)) }
            category.set("rotazione")

            ui { mode.startListening() }
            val interruptedMic = fake
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            ui { interruptedMic.final("invio tardivo dopo rotazione") }
            check(interruptedMic.cancelled); Thread.sleep(250); check(mock.promptCount == 1)
            step("rotazione interrompe il gesto senza inviare: OK")
            ui { activity.onBackPressed() }
            await("uscita animata dalla voce") { root().indexOfChild(mode) < 0 }
            check(!activity.isFinishing)
            ui { check(HermesRuntime.get(ctx).visibleChat == null) { "Il runtime considera ancora visibile la voce chiusa" } }
            step("indietro chiude la voce e ritorna al carosello: OK")
            category.set("transizioni")
            transitionChecks()
            category.set("menu")
            menuContinuityChecks()
            category.set("strumenti")
            clockAndTimerChecks()
            val homeIndex = MainActivity::class.java.getDeclaredField("homeIndex").apply { isAccessible = true }
            val beforeMenu = homeIndex.getInt(activity)
            ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "menu")) }
            Thread.sleep(500)
            ui { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)); activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)) }
            check(homeIndex.getInt(activity) == beforeMenu) { "Il menu ha mosso il carosello sottostante" }
            ui { activity.onBackPressed() }; Thread.sleep(500)
            step("navigazione confinata al menu: OK")
            // Verifica che anche gli altri schermi si adattino senza perdere la navigazione.
            category.set("rotazione_strumenti")
            for (screen in listOf("settings", "tools", "alarm_new", "notes", "translator", "agent_new")) {
                ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", screen)) }
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                screenshot("$screen-portrait")
                if (screen == "settings") {
                    lateinit var scroll: R1ScrollView
                    ui { scroll = views(root()).filterIsInstance<R1ScrollView>().first { it.isShown }; wheelGesture(scroll) }
                    Thread.sleep(550); ui { check(scroll.scrollY > 0) { "Rotella verticale senza scorrimento" } }; assertBuddyPeeking()
                }
                rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                screenshot("$screen-landscape")
                if (screen == "settings") {
                    lateinit var scroll: R1ScrollView
                    ui { scroll = views(root()).filterIsInstance<R1ScrollView>().first { it.isShown }; wheelGesture(scroll) }
                    Thread.sleep(550); ui { check(scroll.scrollY > 0) { "Rotella orizzontale senza scorrimento" } }; assertBuddyPeeking(); screenshot("settings-landscape-scrolled")
                    step("tocchi della rotella nelle due orientazioni: OK")
                }
                ui { activity.onBackPressed() }
            }
            step("rotazione impostazioni, strumenti, sveglia, note, traduttore e agente: OK")
            ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("open_chat", chat).putExtra("open_agent", agent.id)) }
            await("input chat") { var found = false; ui { found = views(root()).filterIsInstance<EditText>().any { it.isShown } }; found }
            ui { views(root()).filterIsInstance<EditText>().first { it.isShown }.setText("bozza di prova rotazione") }
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            ui { check(views(root()).filterIsInstance<EditText>().any { it.isShown && it.text.toString() == "bozza di prova rotazione" }) }
            check(mock.promptCount == 1)
            step("rotazione conserva la bozza della chat senza inviare: OK")
            val sorted = samples.sorted()
            val refresh = activity.display?.refreshRate ?: 60f
            val budget = (1_000_000_000L / refresh).toLong()
            val report = "frame=${sorted.size}, refresh=$refresh, oltre_budget=${sorted.count { it > budget }}, p95_ms=${sorted.getOrElse((sorted.size * .95).toInt().coerceAtMost((sorted.size - 1).coerceAtLeast(0))) { 0L } / 1e6}, max_ms=${(sorted.lastOrNull() ?: 0L) / 1e6}"
            File(ctx.getExternalFilesDir(null), "voice-qa/frames.txt").writeText(report)
            val detail = phases.entries.sortedBy { it.key }.joinToString("\n") { (phase, data) -> val ordered = data.sorted(); "$phase: frame=${data.size}, oltre_budget=${data.count { it > budget }}, p95_ms=${ordered.getOrElse((ordered.size * .95).toInt().coerceAtMost((ordered.size - 1).coerceAtLeast(0))) { 0L } / 1e6}" }
            File(ctx.getExternalFilesDir(null), "voice-qa/frames.txt").appendText("\n" + detail)
            step(report + "\n" + detail)
        } finally {
            try {
                if (::activity.isInitialized) {
                    val restore = when (originalRotation) {
                        android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        android.view.Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    ui { activity.requestedOrientation = restore }
                    await("ripristino orientazione iniziale") { activity.display?.rotation == originalRotation }
                    ui { activity.requestedOrientation = originalOrientation; activity.finishAndRemoveTask() }
                }
            } finally { HermesRuntime.get(ctx).cancelAll(); mock.close(); if (wake.isHeld) wake.release() }
        }
        test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "VOICE_QA_RESULT=PASS\n") })
    }
}
