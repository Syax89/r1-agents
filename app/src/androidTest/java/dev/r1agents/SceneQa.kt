package dev.r1agents

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import dev.r1os.host.ui.CharacterView
import java.io.File

/** Inventario delle superfici interne. Anteprime login/suoneria senza login reale o audio. */
internal class SceneQa(private val test: Instrumentation, private val edgesOnly: Boolean = false) {
    private val ctx get() = test.targetContext
    private lateinit var activity: MainActivity
    private fun ui(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun root() = field("root") as android.widget.FrameLayout
    private fun stack() = field("stack") as List<*>
    private fun surface(): View = (field("pinGate") as? View) ?: (field("overlays") as Map<*, *>).values.lastOrNull()?.let { it as View } ?: page()
    private fun page(): View = stack().last()!!.let { it.javaClass.getDeclaredField("view").apply { isAccessible = true }.get(it) as View }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun step(s: String) = test.sendStatus(1, Bundle().apply { putString("stream", "$s\n") })
    private fun await(label: String, condition: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(40) }
        error("Timeout: $label")
    }
    private fun settled(): Boolean {
        var ready = false
        ui { ready = field("buddyFlight") == null && field("modalBuddyFlight") == null && page().alpha > .99f && runCatching { buddy(page()).alpha > .99f }.getOrDefault(false) }
        return ready
    }
    private fun buddy(surface: View): CharacterView = (if (surface.tag == "home-screen") MainActivity::class.java.getDeclaredMethod("sceneAvatar", View::class.java, java.lang.Long::class.java).apply { isAccessible = true }.invoke(activity, surface, null) as? CharacterView else null)
        ?: views(surface).filterIsInstance<CharacterView>().firstOrNull { it.tag == "scene-buddy" }
        ?: views(surface).filterIsInstance<CharacterView>().firstOrNull { it.isShown && it.alpha > .5f }
        ?: error("Buddy assente nella superficie")
    private fun assertCentre(name: String, screenshot: Boolean = true, scrolled: Boolean = false) {
        var x = 0; var y = 0; var radius = 0
        if (scrolled) { // Scorrendo il buddy affonda dietro le righe (BuddyScrollFx): resta in parte visibile, rimpicciolito e più tenue, mai sparito.
            ui { val b = buddy(surface()); check(b.isShown && b.alpha > .3f && b.scaleX > .6f) { "Buddy sparito scorrendo: $name alpha=${b.alpha} scala=${b.scaleX}" } }
            return
        }
        ui {
            val anchor = buddy(surface())
            check(anchor.isShown && anchor.alpha > .9f) { "Buddy nascosto: $name" }
            val rect = Rect(); check(anchor.getGlobalVisibleRect(rect)) { "Buddy fuori schermo: $name" }
            val at = IntArray(2); anchor.getLocationOnScreen(at)
            val matrix = android.graphics.Matrix(); anchor.transformMatrixToGlobal(matrix)
            val points = floatArrayOf(0f, 0f, anchor.width / 2f, anchor.height / 2f); matrix.mapPoints(points)
            val bounds = android.graphics.RectF(0f, 0f, anchor.width.toFloat(), anchor.height.toFloat()).also { matrix.mapRect(it) }
            x = (at[0] + points[2] - points[0]).toInt(); y = (at[1] + points[3] - points[1]).toInt()
            radius = (minOf(bounds.width(), bounds.height()) * .06f).toInt().coerceAtLeast(1)
            check(rect.contains(x, y)) { "Centro del buddy ritagliato: $name / $rect / $x,$y" }
        }
        if (!screenshot) return
        val shot = test.uiAutomation.takeScreenshot() ?: error("Screenshot assente")
        try {
            var coloured = 0
            for ((dx, dy) in listOf(0 to 0, radius to 0, -radius to 0, 0 to radius, 0 to -radius)) {
                val pixel = shot.getPixel((x + dx).coerceIn(0, shot.width - 1), (y + dy).coerceIn(0, shot.height - 1))
                val values = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (values.max() - values.min() > 28) coloured++
            }
            check(coloured > 0) { "Contenuto sopra il buddy: $name / $x,$y" }
            File(ctx.getExternalFilesDir(null), "scene-qa/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { shot.recycle() }
    }
    fun run() {
        check(ctx.packageName.endsWith(".qa"))
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        check(power.isInteractive) { "Riattiva e sblocca il Rabbit" }
        @Suppress("DEPRECATION") val awake = power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "r1agents.qa:scenes")
        awake.acquire(240000)
        val mock = MockHermes()
        var frames = 0; var absent = 0
        val records = ArrayList<String>()
        try {
            val prefs = ctx.getSharedPreferences("settings", 0)
            prefs.edit().putString("provider", "custom").putString("start", "home").putString("app_language", "it").putString("look_mode", "dark").putBoolean("voice_tts", false).putInt("home_index", 0).remove("app_pin_hash").remove("running_hermes_chats").commit()
            HermesRuntime.get(ctx).configure(Pairing.create("http://127.0.0.1:${mock.port}", "qa"), "qa")
            val db = Db.get(ctx); db.agents().forEach { db.delete(it.id) }
            activity = test.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ui { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            test.waitForIdleSync(); Thread.sleep(500)
            assertCentre("empty-home")
            ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "settings")) }
            await("impostazioni senza agenti") { settled() }; Thread.sleep(450); assertCentre("empty-settings")
            ui { activity.onBackPressed() }; await("ritorno vuoto") { settled() }; Thread.sleep(450)
            val agent = Agent(0, "kiwi", "", "", "blob", "green").also { db.save(it) }
            repeat(12) { n -> db.addNote("nota QA $n " + "testo per scorrimento ".repeat(12)) }
            ui {
                val s = stack().last()!!
                MainActivity::class.java.getDeclaredMethod("showScreen", s.javaClass).apply { isAccessible = true }.invoke(activity, s)
            }
            Thread.sleep(450)
            val watcher = android.view.ViewTreeObserver.OnPreDrawListener {
                frames++
                val visible = views(root()).filterIsInstance<CharacterView>().any { c ->
                    var alpha = c.alpha; var parent = c.parent
                    while (parent is View) { alpha *= parent.alpha; parent = parent.parent }
                    c.isShown && alpha > .02f && c.getGlobalVisibleRect(Rect())
                }
                if (!visible) absent++
                true
            }
            ui { root().viewTreeObserver.addOnPreDrawListener(watcher) }
            var originalCentre = 0f
            ui {
                val anchor = buddy(page()); val at = IntArray(2); val rootAt = IntArray(2); anchor.getLocationInWindow(at); root().getLocationInWindow(rootAt); originalCentre = at[1] + anchor.height / 2f - rootAt[1]
                test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "settings"))
                test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "look"))
            }
            await("due aperture prima del primo frame") { field("buddyFlight") != null }
            ui {
                val flight = field("buddyFlight") as BuddyFlight
                flight.seek(0f)
                val actor = root().findViewWithTag<View>("buddy-flight")
                check(kotlin.math.abs(actor.translationY + actor.height / 2f - originalCentre) < 3f) { "Il volo parte dalla pagina intermedia invisibile" }
                flight.animateTo(1f)
            }
            await("destinazione dopo doppia apertura") { settled() }; assertCentre("rapid-double-entry")
            ui { activity.onBackPressed() }; await("ritorno intermedio") { settled() }
            ui { activity.onBackPressed() }; await("ritorno dopo doppia apertura") { settled() }
            step("aperture nello stesso frame: origine visibile e nessun listener rimasto: OK")
            val cases = if (edgesOnly) emptyList() else listOf("settings", "look", "dash", "security", "tools", "alarms", "alarm_new", "timers", "routines", "routine_new", "translator", "notes", "space", "memory", "manage", "agent_new", "edit", "jobs", "skills", "about", "login_preview", "ring_preview", "card")
            for (name in cases) {
                ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", name)) }
                await("apertura $name") { stack().size == 2 && settled() }; Thread.sleep(100)
                ui {
                    val current = stack().last()!!
                    val role = current.javaClass.getDeclaredField("buddyContext").apply { isAccessible = true }.get(current) as BuddySceneContext
                    if (name in listOf("alarm_new", "alarms", "timers")) check(role == BuddySceneContext.TIME) { "Contesto errato: $name / $role" }
                    else if (name in listOf("settings", "look", "dash", "routine_new", "agent_new", "edit", "tools", "routines", "jobs", "manage")) check(role == BuddySceneContext.CONFIGURE) { "Contesto errato: $name / $role" }
                    records.add("$name: $role")
                }
                for ((label, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    ui { activity.requestedOrientation = orientation }
                    test.waitForIdleSync(); Thread.sleep(400)
                    ui { views(page()).filterIsInstance<R1ScrollView>().firstOrNull()?.fullScroll(View.FOCUS_UP) }; Thread.sleep(700) // in cima il buddy è di nuovo intero
                    assertCentre("$name-$label")
                    ui { views(page()).filterIsInstance<R1ScrollView>().firstOrNull()?.fullScroll(View.FOCUS_DOWN) }
                    Thread.sleep(120); assertCentre("$name-$label-scrolled", scrolled = true)
                }
                if (name == "edit" || name == "agent_new") {
                    ui {
                        var attempts = 0
                        while (views(page()).filterIsInstance<EditText>().none { it.isShown } && attempts++ < 32) {
                            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
                            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
                        }
                        val entry = views(page()).filterIsInstance<EditText>().firstOrNull { it.isShown }
                            ?: error("Campo testo editor non raggiunto: $name")
                        entry.requestFocus()
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(entry, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                    test.waitForIdleSync(); Thread.sleep(500); assertCentre("$name-text-keyboard")
                    ui { views(page()).filterIsInstance<EditText>().firstOrNull { it.isShown }?.let { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() } }
                    Thread.sleep(250)
                }
                ui { activity.onBackPressed() }; await("ritorno $name") { stack().size == 1 && settled() }; Thread.sleep(100)
                step("$name: buddy fisso, contesto, rotazione e ritorno: OK")
            }
            for (kind in listOf("approval", "clarify", "menu")) {
                ui {
                    val intent = Intent(ctx, MainActivity::class.java)
                    if (kind == "menu") intent.putExtra("debug_screen", kind) else intent.putExtra("debug_req", kind)
                    test.callActivityOnNewIntent(activity, intent)
                    activity.onBackPressed()
                }
                Thread.sleep(650)
                ui {
                    check((field("overlays") as Map<*, *>).isEmpty()) { "Pannello rimasto dopo chiusura immediata: $kind" }
                    check(field("modalBuddyFlight") == null && field("carriedBuddy") == null && root().childCount == 1) { "Attore o finestra fantasma dopo chiusura immediata: $kind" }
                }
                assertCentre("immediate-close-$kind")
                ui {
                    val intent = Intent(ctx, MainActivity::class.java)
                    if (kind == "menu") intent.putExtra("debug_screen", kind) else intent.putExtra("debug_req", kind)
                    test.callActivityOnNewIntent(activity, intent)
                }
                await("ingresso pannello $kind") { field("modalBuddyFlight") != null }
                ui {
                    (field("modalBuddyFlight") as BuddyFlight).seek(.4f)
                    val actor = root().findViewWithTag<View>("buddy-flight")
                    val before = listOf(actor.translationX, actor.translationY, actor.scaleX)
                    activity.onBackPressed()
                    val after = root().findViewWithTag<View>("buddy-flight")
                    check(before.zip(listOf(after.translationX, after.translationY, after.scaleX)).all { kotlin.math.abs(it.first - it.second) < .1f }) { "Salto alla chiusura durante ingresso: $kind" }
                }
                await("ritorno pannello interrotto $kind") { field("modalBuddyFlight") == null }
                Thread.sleep(150); assertCentre("interrupted-close-$kind")
                ui { check(root().childCount == 1) { "Pannello interrotto non rimosso: $kind" } }
            }
            step("pannelli chiusi prima del primo frame senza attori fantasma: OK")
            ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "loading_preview")) }
            await("caricamento in ingresso") { field("buddyFlight") != null }
            ui {
                (field("buddyFlight") as BuddyFlight).seek(.6f)
                val next = MainActivity::class.java.getDeclaredMethod("settingsScreen").apply { isAccessible = true }.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("replaceTop", next.javaClass).apply { isAccessible = true }.invoke(activity, next)
            }
            await("sostituzione del caricamento") { settled() }; assertCentre("loading-replaced")
            ui { activity.onBackPressed() }; await("ritorno dopo caricamento") { stack().size == 1 && settled() }
            step("caricamento sostituito durante il volo senza duplicati: OK")
            ui {
                val glance = MainActivity::class.java.getDeclaredMethod("glance").apply { isAccessible = true }.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("push", glance.javaClass, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, glance, true)
            }
            await("glance") { settled() }; assertCentre("glance")
            ui { (page() as ViewGroup).getChildAt(0).performClick() }
            await("glance verso carosello") { stack().size == 3 && settled() }; assertCentre("glance-carousel")
            ui { activity.onBackPressed() }; await("carosello verso glance") { stack().size == 2 && settled() }; assertCentre("carousel-glance")
            ui { activity.onBackPressed() }; await("fine prova glance") { stack().size == 1 && settled() }
            step("glance e carosello: stesso personaggio e transizione condivisa nei due versi: OK")
            for (request in listOf("approval", "clarify")) {
                ui { test.callActivityOnNewIntent(activity, Intent(ctx, MainActivity::class.java).putExtra("debug_req", request)) }
                Thread.sleep(500); assertCentre("request-$request")
                ui { check(CharacterView::class.java.getDeclaredField("state").apply { isAccessible = true }.get(buddy(surface())) == "waiting") }
                if (request == "clarify") {
                    ui { views(surface()).filterIsInstance<android.widget.TextView>().last { it.text.toString() == activity.getString(R.string.ui_6653fb8a8e) }.performClick() }
                    ui {
                        val texts = views(surface()).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
                        check(activity.getString(R.string.ui_f9558c4eaa) !in texts) { "La prima domanda è rimasta nel chiarimento" }
                        check(activity.getString(R.string.ui_7fd8d27a3b) in texts)
                    }
                    assertCentre("request-clarify-second")
                }
                ui { activity.onBackPressed() }; Thread.sleep(500)
                step("$request: attesa nella testata e contenuto sostituito senza duplicare il buddy: OK")
            }
            ui {
                prefs.edit().putString("app_pin_hash", PinHash.create("4826")).commit()
                MainActivity::class.java.getDeclaredField("pinUnlocked").apply { isAccessible = true }.setBoolean(activity, false)
                MainActivity::class.java.getDeclaredMethod("showPinGate", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                val gate = field("pinGate") as View
                check(buddy(gate).isShown)
                val entry = views(gate).filterIsInstance<EditText>().single(); entry.requestFocus()
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(entry, 0)
            }
            await("ingresso buddy nel PIN") { field("modalBuddyFlight") != null }
            ui {
                val pinFlight = field("modalBuddyFlight") as BuddyFlight
                pinFlight.seek(.35f)
                val request = HermesDash.SrvRequest("pin-edge", "approval", org.json.JSONObject().put("description", "QA protetta").put("choices", org.json.JSONArray(listOf("deny"))))
                MainActivity::class.java.getDeclaredMethod("showRequest", HermesDash.SrvRequest::class.java).apply { isAccessible = true }.invoke(activity, request)
                check(field("modalBuddyFlight") === pinFlight) { "La richiesta dietro il PIN ha interrotto il buddy protetto" }
                MainActivity::class.java.getDeclaredMethod("closeOverlay", String::class.java).apply { isAccessible = true }.invoke(activity, "req-pin-edge")
                check(field("modalBuddyFlight") === pinFlight) { "La chiusura dietro il PIN ha invertito il volo protetto" }
                pinFlight.animateTo(1f)
            }
            Thread.sleep(450)
            ui {
                val gate = field("pinGate") as View; val rect = Rect(); val b = buddy(gate)
                check(b.getGlobalVisibleRect(rect) && rect.height() > 0) { "Buddy PIN non visibile con tastiera" }
                val entry = views(gate).filterIsInstance<EditText>().single(); entry.setText("4826")
                views(gate).filterIsInstance<android.widget.TextView>().last { it.text.toString() == activity.getString(R.string.ui_e01eaf5dd1) }.performClick()
            }
            await("sblocco e ritorno del buddy dal PIN") { field("pinGate") == null && field("modalBuddyFlight") == null }
            ui {
                check(buddy(page()).alpha > .99f && field("carriedBuddy") == null && root().childCount == 1) { "Buddy o pannello perso dopo sblocco PIN" }
                root().viewTreeObserver.removeOnPreDrawListener(watcher)
                prefs.edit().remove("app_pin_hash").commit()
            }
            check(frames > 0 && absent == 0) { "Buddy assente in $absent / $frames frame" }
            File(ctx.getExternalFilesDir(null), "scene-qa/report.txt").apply { parentFile?.mkdirs() }.writeText(records.joinToString("\n") + "\nframes=$frames, absent=$absent\n")
            step("PIN con tastiera e $frames frame senza assenza del buddy: OK")
            db.delete(agent.id)
        } finally { if (::activity.isInitialized) ui { activity.finishAndRemoveTask() }; mock.close(); if (awake.isHeld) awake.release() }
        test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SCENE_QA_RESULT=PASS\n") })
    }
}
