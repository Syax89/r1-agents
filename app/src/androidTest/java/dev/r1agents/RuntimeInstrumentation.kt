package dev.r1agents

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Esegue il servizio reale contro un server locale. Solo il pacchetto dev.r1agents.qa è ammesso. */
class RuntimeInstrumentation : Instrumentation() {
    private var functionalSuite = false
    private var sceneSuite = false
    private var sceneEdgesOnly = false
    private var developmentSuite = false
    private var voiceSuite = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); functionalSuite = arguments?.getString("suite") == "functional"; voiceSuite = arguments?.getString("suite") == "voice"; developmentSuite = arguments?.getString("suite") == "development"; sceneEdgesOnly = arguments?.getString("suite") == "scene_edges"; sceneSuite = sceneEdgesOnly || arguments?.getString("suite") == "scenes"; start() }
    private fun step(label: String) { android.util.Log.i("R1QA", label); sendStatus(1, Bundle().apply { putString("stream", label + "\n") }) }
    /** Ogni suite ha un tempo massimo: una suite bloccata prima teneva fermo `am instrument` per sempre. */
    private fun suite(prefix: String, limitMs: Long, body: () -> Unit) {
        val watchdog = java.util.Timer("qa-timeout-$prefix", true).apply { schedule(object : java.util.TimerTask() { override fun run() { finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "${prefix}_QA_RESULT=FAIL\ntimeout della suite\n") }) } }, limitMs) }
        try { body() } catch (e: Throwable) { finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "${prefix}_QA_RESULT=FAIL\n" + android.util.Log.getStackTraceString(e)) }) } finally { watchdog.cancel() }
    }
    override fun onStart() {
        // Lo standby (orologio sopra il blocco, sempre acceso in carica) coprirebbe le pagine durante prove lunghe: spento nella copia QA.
        targetContext.getSharedPreferences("settings", 0).edit().putBoolean("standby_lock", false).putBoolean("standby_charge", false).commit()
        if (functionalSuite) { suite("FUNCTIONAL", 300_000) { FunctionalQa(this).run() }; return }
        if (sceneSuite) { suite("SCENE", 300_000) { SceneQa(this, sceneEdgesOnly).run() }; return }
        if (developmentSuite) { suite("DEVELOPMENT", 400_000) { DevelopmentQa(this).run() }; return }
        if (voiceSuite) { suite("VOICE", 300_000) { VoiceQa(this).run() }; return }
        val watchdog = java.util.Timer("qa-timeout", true).apply { schedule(object : java.util.TimerTask() { override fun run() { finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "QA_RESULT=FAIL\ntimeout della suite QA\n") }) } }, 90000) }
        val result = Bundle()
        var outcome = Activity.RESULT_CANCELED
        var awake: android.os.PowerManager.WakeLock? = null
        var mock: MockHermes? = null
        var activity: Activity? = null
        try {
            step("avvio QA isolato")
            val pairing = Pairing.create("http://192.168.1.20:9119", "utente + è")
            val qr = PairingQr.image(pairing)
            try { check(PairingQr.read(qr) == pairing) } finally { qr.recycle() }
            step("QR immagine: OK")
            val ctx = targetContext
            check(ctx.packageName.endsWith(".qa")) { "Vietato eseguire questi test sul pacchetto utente" }
            val power = ctx.getSystemService(android.os.PowerManager::class.java)
            check(power.isInteractive) { "Riattiva il Rabbit prima della prova QA" }
            @Suppress("DEPRECATION")
            val keepAwake = power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "r1agents.qa:device-check")
            keepAwake.acquire(90000); awake = keepAwake
            val photoDir = java.io.File(ctx.cacheDir, "photos").apply { mkdirs() }
            val photo = java.io.File(photoDir, "qa-qr.png")
            val photoUri = android.net.Uri.parse("content://${ctx.packageName}.photos/${photo.name}")
            context.assets.open("pairing-example.png").use { input -> ctx.contentResolver.openOutputStream(photoUri)!!.use { input.copyTo(it) } }
            try { check(PairingQr.read { ctx.contentResolver.openInputStream(photoUri) } == Pairing.create("http://192.168.1.20:9119", "esempio")) } finally { photo.delete() }
            step("QR desktop e PhotoProvider: OK")
            uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            mock = MockHermes()
            val prefs = ctx.getSharedPreferences("settings", 0)
            prefs.edit().putString("provider", "dashboard").putString("dev_actions", "off").putString("dash_url", "http://127.0.0.1:${mock.port}").putString("dash_user", "qa").putSecret("dash_pass", "qa").putString("app_language", "it").remove("app_pin_hash").remove("running_hermes_chats").commit()
            val runtime = HermesRuntime.get(ctx)
            val db = Db.get(ctx)
            db.agents().forEach { db.delete(it.id) } // Isolated QA database only.
            val agent = Agent(0, "QA buddy", "", "", profile = "qa").also { db.save(it) }
            step("apro attività QA")
            activity = startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val chat = db.newChat(agent.id)
            lateinit var task: HermesRuntime.Task
            runOnMainSync { task = runtime.start(agent, chat, "normal", false, emptyList()) }
            val nm = ctx.getSystemService(NotificationManager::class.java)
            step("verifico servizio foreground")
            await("servizio foreground") { nm.activeNotifications.any { it.id == 3 } }
            runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("open_chat", chat).putExtra("open_agent", agent.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("chat collegata al turno") { runtime.visibleChat == chat }
            val previousAddress = runtime.dash.url
            check(runCatching { runtime.configure(Pairing.create("http://127.0.0.1:9999", "altro")) }.isFailure)
            check(runtime.dash.url == previousAddress)
            step("cambio collegamento protetto durante un turno: OK")
            runOnMainSync { activity?.moveTaskToBack(true); activity?.finishAndRemoveTask() }
            await("attività chiusa mentre il servizio lavora") { activity?.isDestroyed == true }
            await("app in background") { !runtime.foreground }
            await("risposta in background") { task.progress.snapshot().status != TurnProgress.Status.RUNNING }
            check(task.progress.snapshot().status == TurnProgress.Status.DONE)
            check(db.messages(chat).last().second.contains("risposta normal"))
            await("notifica risultato") { nm.activeNotifications.any { it.id == (0x44000000 or chat.toInt()) } }
            check(mock.promptCount == 1) { "Invio duplicato" }
            await("servizio fermato dopo risposta") { nm.activeNotifications.none { it.id == 3 } }

            step("risposta e notifica background: OK")
            // Riporta in primo piano solo l’app QA per avviare due turni concorrenti.
            activity = startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val cancelChat = db.newChat(agent.id); val otherChat = db.newChat(agent.id)
            lateinit var cancelled: HermesRuntime.Task; lateinit var other: HermesRuntime.Task
            runOnMainSync { cancelled = runtime.start(agent, cancelChat, "cancel", false, emptyList()); other = runtime.start(agent, otherChat, "other", false, emptyList()) }
            await("due prompt ricevuti") { mock.texts.containsAll(listOf("cancel", "other")) }
            runOnMainSync { runtime.cancel(cancelChat); activity?.moveTaskToBack(true) }
            await("altro turno completato") { other.progress.snapshot().status != TurnProgress.Status.RUNNING }
            check(cancelled.progress.snapshot().status == TurnProgress.Status.CANCELLED)
            check(other.progress.snapshot().status == TurnProgress.Status.DONE) { "Stop ha interrotto un’altra chat" }
            check(mock.interrupted.none { it == mock.sessionFor("other") })

            step("stop per chat: OK")
            // Contratto Hermes: il mock rifiuta metodi e parametri sbagliati, quindi queste prove non sono tautologiche.
            val done = mock.sessionFor("other")!!
            check(runCatching { runtime.dash.delete("inesistente", "qa") }.isFailure) { "Eliminazione di una sessione sconosciuta accettata" }
            val branchId = runtime.dash.branch(done, "qa")
            check(branchId != done && mock.branches.contains(done to branchId)) { "Ramo senza identificatore distinto" }
            check(runtime.dash.open(0L, branchId, "qa").any { it.second.contains("risposta other") }) { "Il ramo non conserva la cronologia" }
            check(runCatching { runtime.dash.branch("inesistente", "qa") }.isFailure)
            runtime.dash.archive(done, "qa"); check(mock.archived.contains(done))
            runtime.dash.open(0L, branchId, "qa") // il ramo ora è "live" lato server
            runtime.dash.delete(branchId, "qa"); check(mock.deleted.contains(branchId)) { "Eliminazione di una sessione live senza chiuderla prima" }
            val busyChat = db.newChat(agent.id); lateinit var busy: HermesRuntime.Task
            runOnMainSync { busy = runtime.start(agent, busyChat, "cancel", false, emptyList()) }
            await("sessione occupata") { mock.activeFor("cancel") != null }
            val busyId = mock.activeFor("cancel")!!
            check(runCatching { runtime.dash.delete(busyId, "qa") }.isFailure) { "Eliminata una sessione ancora attiva" }
            check(db.chats(agent.id).any { it.id == busyChat }) { "Chat locale persa dopo rifiuto remoto" }
            runOnMainSync { runtime.cancel(busyChat) }
            step("contratto Hermes: ramo, archivio, eliminazione e rifiuti: OK")
            val feat = db.newChat(agent.id)
            val png = byteArrayOf(1, 2, 3, 4); val pdf = "%PDF-1.4 qa".toByteArray()
            runtime.dash.attachImage(feat, "qa", png, "foto.png"); runtime.dash.attachPdf(feat, "qa", pdf, "doc.pdf")
            check(mock.attachments == listOf("image.attach_bytes" to "foto.jpg", "pdf.attach" to "doc.pdf")) { "Allegati: ${mock.attachments}" }
            check(runCatching { runtime.dash.attachPdf(feat, "qa", png, "falso.pdf") }.isFailure) { "PDF non valido accettato" }
            check(runtime.dash.compress(feat, "qa") == "compresso")
            check(runtime.dash.command(feat, "qa", "undo").contains("/undo"))
            val rows = runtime.dash.models("qa"); check(rows.map { it.model } == listOf("qa-small", "qa-expensive") && rows.first().current)
            check(runtime.dash.setModel(feat, "qa", rows[0]) == "qa-small")
            check(runtime.dash.setModel(feat, "qa", rows[1]) == "CONFERMA" && mock.models.size == 1) { "Modello costoso senza conferma" }
            check(runtime.dash.setModel(feat, "qa", rows[1], confirm = true) == "qa-expensive")
            check(runtime.dash.subagents(feat, "qa").single().let { it.id == "sub-1" && it.canSteer })
            check(runtime.dash.steerSub(feat, "qa", "sub-1", "più breve") && !runtime.dash.steerSub(feat, "qa", "x", "no"))
            check(runtime.dash.stopSub(feat, "qa", "sub-1") && !runtime.dash.stopSub(feat, "qa", "x"))
            step("contratto Hermes: allegati, compressione, modelli, comandi e sottoagenti: OK")
            runtime.dash.cronCreate("qa", "Prova", "ricorda", "0 9 * * *", false)
            check(runCatching { runtime.dash.cronCreate("", "Senza profilo", "x", "0 9 * * *", true) }.isFailure) { "Routine senza profilo accettata" }
            var cron = runtime.dash.cronJobs().single(); check(cron.enabled && cron.profile == "qa" && cron.schedule == "0 9 * * *")
            runtime.dash.cronAction(cron, "pause"); cron = runtime.dash.cronJobs().single(); check(!cron.enabled && cron.state == "paused")
            runtime.dash.cronAction(cron, "resume"); check(runtime.dash.cronJobs().single().enabled)
            runtime.dash.cronAction(cron, "trigger"); check(runtime.dash.cronJobs().single().lastStatus == "ok")
            runtime.dash.cronDelete(cron); check(runtime.dash.cronJobs().isEmpty())
            check(runCatching { runtime.dash.cronDelete(cron) }.isFailure) { "Eliminazione ripetuta accettata" }
            check(runtime.dash.skills("qa").associate { it.name to it.enabled } == mapOf("ricerca" to true, "calendario" to false))
            runtime.dash.toggleSkill("qa", "calendario", true); check(runtime.dash.skills("qa").all { it.enabled })
            check(runCatching { runtime.dash.toggleSkill("qa", "inesistente", true) }.isFailure)
            step("contratto Hermes: routine e skill REST: OK")
            // Una caduta dopo prompt.submit non deve ripetere l’invio durante restore.
            runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }; await("attività QA riaperta") { runtime.foreground }
            val recoverChat = db.newChat(agent.id)
            lateinit var broken: HermesRuntime.Task
            runOnMainSync { broken = runtime.start(agent, recoverChat, "recover", false, emptyList()) }
            await("connessione caduta") { broken.progress.snapshot().status == TurnProgress.Status.ERROR }
            val sent = mock.promptCount
            runOnMainSync { runtime.ensureRecoveryService(); activity?.moveTaskToBack(true) }
            await("ripresa terminata", 20000) { db.messages(recoverChat).lastOrNull()?.second?.contains("risposta recover") == true }
            check(mock.promptCount == sent) { "Il recupero ha ripetuto prompt.submit" }
            check(runtime.active(recoverChat) == null)
            result.putString("stream", "OK: foreground, risposta e notifica in background; stop per chat; recupero senza reinvio")
            step("recupero senza reinvio: OK")
            // Il blocco si prova solo nella copia QA: nessuna preferenza del pacchetto utente cambia.
            // Il PIN protegge le chat, non l'app: qui tutte le chat sono bloccate e se ne apre una.
            prefs.edit().putString("app_pin_hash", PinHash.create("004279")).putBoolean("lock_all_chats", true).commit()
            runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("open_chat", recoverChat).putExtra("open_agent", agent.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }; await("attività QA riaperta") { runtime.foreground }
            fun findText(view: android.view.View, text: String): android.widget.TextView? {
                if (view is android.widget.TextView && view.text.toString() == text) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
                return null
            }
            fun pinField(view: android.view.View): android.widget.EditText? {
                if (view is android.widget.EditText && view.hint?.toString() == "PIN") return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) pinField(view.getChildAt(i))?.let { return it }
                return null
            }
            await("chat bloccata: richiesta del PIN") { var found = false; runOnMainSync { found = findText(activity!!.window.decorView, "sblocca r1agents") != null }; found }
            check(activity!!.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            runOnMainSync {
                val decor = activity!!.window.decorView
                check(findText(decor, "sblocca r1agents") != null)
                pinField(decor)!!.setText("004278"); findText(decor, "sblocca")!!.performClick()
            }
            await("PIN errato rifiutato") { var found = false; runOnMainSync { found = findText(activity!!.window.decorView, "PIN errato") != null }; found }
            var annoyed = false; val untilPin = System.currentTimeMillis() + 2500
            fun faces(view: android.view.View): List<dev.r1os.host.ui.CharacterView> = if (view is dev.r1os.host.ui.CharacterView) listOf(view) else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { faces(view.getChildAt(it)) } else emptyList()
            while (!annoyed && System.currentTimeMillis() < untilPin) { runOnMainSync { annoyed = faces(activity!!.window.decorView).any { it.expression == "brows" } }; Thread.sleep(50) }
            check(annoyed) { "Con il PIN errato il buddy non si infastidisce" }
            runOnMainSync { val decor = activity!!.window.decorView; pinField(decor)!!.setText("004279"); findText(decor, "sblocca")!!.performClick() }
            await("PIN corretto accettato") { var unlocked = false; runOnMainSync { unlocked = findText(activity!!.window.decorView, "sblocca r1agents") == null }; unlocked }
            prefs.edit().remove("app_pin_hash").remove("lock_all_chats").commit()
            runOnMainSync { activity!!.onBackPressed() }
            step("PIN corretto ed errato su chat bloccata: OK")
            val oldAddress = runtime.dash.url
            val link = Pairing.create("http://192.168.1.20:9119", "esempio").link()
            runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("conferma del QR") { var found = false; runOnMainSync { found = findText(activity!!.window.decorView, "usa questo collegamento") != null }; found }
            check(runtime.dash.url == oldAddress) { "Il QR ha modificato la configurazione senza conferma" }
            runOnMainSync { findText(activity!!.window.decorView, "annulla")!!.performClick() }
            check(runtime.dash.url == oldAddress)
            step("QR richiede conferma e annullamento conserva la configurazione: OK")
            val oldProvider = prefs.getString("provider", null)
            val oldBase = prefs.getString("base_custom", null)
            val appInfo = activity!!.applicationInfo
            val originalFlags = appInfo.flags
            try {
                appInfo.flags = appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
                runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("provider", "custom").putExtra("base_url", "https://example.invalid").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                waitForIdleSync()
                check(prefs.getString("provider", null) == oldProvider && prefs.getString("base_custom", null) == oldBase) { "Un intent ha riconfigurato una build non debuggabile" }
            } finally { appInfo.flags = originalFlags }
            step("provisioning esterno disabilitato senza flag debug: OK")
            prefs.edit().putString("tr_lang", "spagnolo").commit()
            runOnMainSync { activity!!.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            val named = Agent(0, "impostazioni", "Istruzioni personali da non tradurre", "", profile = "qa").also { db.save(it) }
            val englishChat = db.newChat(named.id)
            lateinit var englishTask: HermesRuntime.Task
            runOnMainSync { englishTask = runtime.start(named, englishChat, "english", false, emptyList()) }
            runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("open_chat", englishChat).putExtra("open_agent", named.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("chat pronta prima del cambio lingua") { runtime.visibleChat == englishChat }
            runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("debug_screen", "look").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            // La lingua è una riga con il valore a destra ("Italiano"): il tocco apre il foglio con le lingue.
            fun clickable(v: android.view.View): android.view.View { var c: android.view.View = v; while (!c.isClickable) c = c.parent as? android.view.View ?: break; return c }
            await("selettore lingua nell’interfaccia") { var found = false; runOnMainSync { found = findText(activity!!.window.decorView, "Italiano") != null }; found }
            runOnMainSync { clickable(findText(activity!!.window.decorView, "Italiano")!!).performClick() }
            await("foglio delle lingue") { var found = false; runOnMainSync { found = findText(activity!!.window.decorView, "English") != null }; found }
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { clickable(findText(activity!!.window.decorView, "English")!!).performClick() }
            activity = waitForMonitorWithTimeout(monitor, 12000) ?: error("ricreazione inglese non completata")
            removeMonitor(monitor)
            check(activity!!.resources.configuration.locales[0].language == "en")
            check(AppLanguage.selected(ctx) == "en")
            await("notifica inglese durante un turno già iniziato") { nm.activeNotifications.any { it.id == 3 && it.notification.extras.getCharSequence("android.title")?.toString() == "Hermes is working" } }
            val englishContext = AppLanguage.forLanguage(ctx, "en")
            for (entry in UiCatalog.entries) {
                val count = Regex("%([0-9]+)\\$[sd]").findAll(entry.source).count()
                UiText.text(englishContext, entry.resource, *Array(count) { i -> if (Regex("%" + (i + 1) + "\\$" + "d").containsMatchIn(entry.source)) 7 else "Nome È + 100%" })
            }
            check(UiText.translationLanguage(ctx, "spagnolo") == "Spanish")
            check(UiText.translationLanguage(ctx, "es") == "Spanish")
            check(UiText.owned(ctx, "invio Report È [1].pdf…") == "sending Report È [1].pdf…")
            check(UiText.owned(ctx, "legge un file: /tmp/Nome È + 100%") == "reading a file: /tmp/Nome È + 100%")
            runOnMainSync { val raw = englishContext.row("Impostazioni È", dev.r1os.host.ui.Theme.accent, raw = true) {}; check(rowTitle(raw).text.toString() == "Impostazioni È") }
            runOnMainSync { activity?.moveTaskToBack(true) }
            await("risposta inglese in background") { englishTask.progress.snapshot().status == TurnProgress.Status.DONE }
            await("nome utente invariato nella notifica") { nm.activeNotifications.any { it.id == (0x44000000 or englishChat.toInt()) && it.notification.extras.getCharSequence("android.title")?.toString() == "impostazioni" } }
            check(db.agents().first { it.id == named.id }.prompt == "Istruzioni personali da non tradurre")
            runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("ritorno al controllo inglese") { runtime.foreground }
            val screenshots = java.io.File(ctx.getExternalFilesDir(null), "qa-screens").apply { mkdirs() }
            fun preview(screen: String, title: String) {
                runOnMainSync { ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("debug_screen", screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                await("schermata inglese " + screen) { var present = false; runOnMainSync { present = findText(activity!!.window.decorView, title) != null && activity!!.hasWindowFocus() }; present }
                Thread.sleep(350)
                val image = uiAutomation.takeScreenshot() ?: error("screenshot non disponibile")
                java.io.File(screenshots, "english-" + screen + ".png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            preview("settings", "settings")
            preview("dash", "provider")
            preview("agent_new", "new agent")
            step("inglese, formati, notifiche e contenuti utente invariati: OK")
            // Aggiornamenti: firma, pacchetto, versione, hash e dimensione contro le vere API di Android (l'APK del pacchetto QA stesso).
            val own = java.io.File(ctx.applicationInfo.sourceDir)
            val code = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(own.readBytes()).joinToString("") { "%02x".format(it) }
            val http = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            thread(isDaemon = true) { repeat(3) { runCatching { http.accept().use { c -> c.getInputStream().bufferedReader().readLine(); c.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${own.length()}\r\nConnection: close\r\n\r\n".toByteArray()); write(own.readBytes()); flush() } } } } }
            val manifest = UpdateManifest(code, "qa", "http://127.0.0.1:${http.localPort}/a.apk", digest, own.length(), "")
            val target = java.io.File(ctx.cacheDir, "update-test.apk")
            Updater.download(manifest, target)
            check(target.length() == own.length()) { "Download incompleto" }
            Updater.verifyApk(ctx, target, manifest)
            val badHash = runCatching { Updater.download(manifest.copy(sha256 = "0".repeat(64)), java.io.File(ctx.cacheDir, "update-bad.apk")) }.exceptionOrNull()
            check(badHash is UpdateError && !java.io.File(ctx.cacheDir, "update-bad.apk").exists()) { "Un hash sbagliato doveva essere rifiutato e il file cancellato" }
            val badSize = runCatching { Updater.download(manifest.copy(sizeBytes = own.length() - 1), java.io.File(ctx.cacheDir, "update-size.apk")) }.exceptionOrNull()
            check(badSize is UpdateError) { "Una dimensione diversa doveva essere rifiutata" }
            http.close()
            // Manifest completo: lo stesso flusso dell'app (scarica il manifest, controlla che sia piu' nuovo, scarica l'APK annunciato) contro un server locale.
            val manifestServer = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val apkServer = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val announced = manifest.copy(versionCode = code + 1, versionName = "qa-next", apkUrl = "http://127.0.0.1:${apkServer.localPort}/next.apk")
            val manifestJson = JSONObject().put("schema", 1).put("packageName", ctx.packageName).put("versionCode", announced.versionCode).put("versionName", announced.versionName)
                .put("apkUrl", announced.apkUrl).put("sha256", announced.sha256).put("sizeBytes", announced.sizeBytes).put("notes", "novita' di prova").toString()
            thread(isDaemon = true) { runCatching { manifestServer.accept().use { c -> c.getInputStream().bufferedReader().readLine(); val b = manifestJson.toByteArray(); c.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() } } } }
            thread(isDaemon = true) { runCatching { apkServer.accept().use { c -> c.getInputStream().bufferedReader().readLine(); c.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${own.length()}\r\nConnection: close\r\n\r\n".toByteArray()); write(own.readBytes()); flush() } } } }
            val fetched = Updater.fetchManifest(ctx, "http://127.0.0.1:${manifestServer.localPort}/update.json", allowLoopbackHttp = true)
            check(Updater.isNewer(fetched, code) && fetched.versionName == "qa-next" && fetched.notes == "novita' di prova") { "Manifest non letto bene: $fetched" }
            check(!Updater.isNewer(fetched, code + 1))
            val fetchedApk = Updater.download(fetched, java.io.File(ctx.cacheDir, "update-next.apk"))
            check(fetchedApk.length() == own.length()) { "APK annunciato non scaricato" }
            fetchedApk.delete(); manifestServer.close(); apkServer.close()
            check(runCatching { Updater.fetchManifest(ctx, "http://127.0.0.1:1/x.json") }.exceptionOrNull() is UpdateError) { "http senza flag doveva essere rifiutato" }
            check(runCatching { Updater.verifyApk(ctx, target, manifest.copy(versionCode = code + 1)) }.exceptionOrNull() is UpdateError) { "Versione diversa non rifiutata" }
            val resigned = java.io.File(ctx.getExternalFilesDir(null), "update-test/resigned.apk")
            var signatureChecked = false
            if (resigned.exists()) {
                val rejected = runCatching { Updater.verifyApk(ctx, resigned, manifest) }.exceptionOrNull()
                check(rejected is UpdateError && rejected.resource == R.string.ui_4b07cc5415) { "Una firma diversa doveva essere rifiutata: $rejected" }
                signatureChecked = true
            }
            target.delete()
            step("aggiornamento: manifest, download, hash, dimensione, versione" + (if (signatureChecked) " e firma diversa" else "") + ": OK")
            result.putInt("checks", 13)
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", android.util.Log.getStackTraceString(error))
        } finally {
            if (targetContext.packageName.endsWith(".qa")) {
                runCatching { HermesRuntime.get(targetContext).cancelAll() }
                runCatching { targetContext.stopService(Intent(targetContext, HermesTurnService::class.java)) }
                targetContext.getSharedPreferences("settings", 0).edit().putString("app_language", "it").remove("app_pin_hash").commit()
            }
            runCatching { runOnMainSync { activity?.finish() } }
            mock?.close(); watchdog.cancel(); if (awake?.isHeld == true) awake?.release()
        }
        result.putString("stream", "QA_RESULT=" + (if (outcome == Activity.RESULT_OK) "PASS" else "FAIL") + "\nchecks=" + result.getInt("checks") + "\n" + result.getString("stream", "") + "\n")
        finish(outcome, result)
    }
    private fun await(label: String, timeout: Long = 12000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(60) }
        error("Timeout: $label")
    }
}

/** Server HTTP/WebSocket minimale e deterministico, confinato al loopback del dispositivo QA. */
internal class MockHermes {
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    private class Session(val id: String) { @Volatile var live = false; @Volatile var text = ""; @Volatile var active = false; @Volatile var completed = false; @Volatile var interrupted = false }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    val texts = java.util.concurrent.CopyOnWriteArrayList<String>()
    val interrupted = java.util.concurrent.CopyOnWriteArrayList<String>()
    val archived = java.util.concurrent.CopyOnWriteArrayList<String>()
    val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()
    val attachments = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    val models = java.util.concurrent.CopyOnWriteArrayList<String>()
    val branches = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var promptCount = 0
        private set
    private var nextId = 0
    init {
        thread(isDaemon = true) { while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            sockets.add(socket)
            thread(isDaemon = true) { runCatching { handle(socket) }; sockets.remove(socket); runCatching { socket.close() } }
        } }
    }
    val jobs = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    val skillState = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply { put("ricerca", true); put("calendario", false) }
    val restLog = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun rest(method: String, fullPath: String, body: String): Pair<Int, String> {
        val path = fullPath.substringBefore('?'); val query = fullPath.substringAfter('?', "")
        restLog.add("$method $path")
        fun bad(detail: String, code: Int = 422) = code to JSONObject().put("detail", detail).toString()
        val json = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull() ?: return bad("JSON non valido", 400)
        if (path.startsWith("/api/cron/jobs") || path.startsWith("/api/skills")) {
            val wanted = query.substringAfter("profile=", "").substringBefore('&')
            if (method == "GET" && path == "/api/cron/jobs") { if (wanted != "all") return bad("profile=all richiesto"); return 200 to JSONArray(jobs.values.toList()).toString() }
            if (method == "POST" && path == "/api/cron/jobs") {
                val extra = json.keys().asSequence().toSet() - setOf("name", "prompt", "schedule", "deliver", "repeat")
                if (extra.isNotEmpty()) return bad("Extra inputs are not permitted: ${extra.joinToString()}")
                if (json.optString("prompt").isBlank() || json.optString("schedule").isBlank()) return bad("Field required: prompt, schedule")
                if (wanted.isBlank()) return bad("profile richiesto")
                val id = "job-${jobs.size + 1}"
                jobs[id] = JSONObject().put("id", id).put("name", json.optString("name")).put("prompt", json.getString("prompt")).put("schedule_display", json.getString("schedule")).put("enabled", true).put("state", "scheduled").put("profile", wanted)
                return 200 to jobs.getValue(id).toString()
            }
            val job = Regex("/api/cron/jobs/([^/]+)(?:/(pause|resume|trigger))?").matchEntire(path)
            if (job != null) {
                val id = java.net.URLDecoder.decode(job.groupValues[1], "UTF-8"); val target = jobs[id] ?: return bad("Job non trovato", 404)
                when {
                    method == "DELETE" && job.groupValues[2].isEmpty() -> jobs.remove(id)
                    method == "POST" && job.groupValues[2] == "pause" -> target.put("enabled", false).put("state", "paused")
                    method == "POST" && job.groupValues[2] == "resume" -> target.put("enabled", true).put("state", "scheduled")
                    method == "POST" && job.groupValues[2] == "trigger" -> target.put("last_status", "ok")
                    else -> return bad("Metodo non consentito", 405)
                }
                return 200 to "{}"
            }
            if (method == "GET" && path == "/api/skills") return 200 to JSONArray(skillState.map { JSONObject().put("name", it.key).put("description", "skill ${it.key}").put("enabled", it.value) }).toString()
            if (method == "PUT" && path == "/api/skills/toggle") {
                val name = json.optString("name"); if (!skillState.containsKey(name) || !json.has("enabled")) return bad("Skill o campo mancante")
                skillState[name] = json.getBoolean("enabled"); return 200 to "{}"
            }
            return bad("Percorso sconosciuto", 404)
        }
        return when (path) { "/api/status" -> 200 to """{"auth_required":true,"auth_providers":["basic"],"version":"qa"}"""; "/api/auth/ws-ticket" -> 200 to """{"ticket":"qa"}"""; "/auth/password-login" -> 200 to "{}"; else -> bad("Percorso sconosciuto", 404) }
    }
    /** Prova: una sessione già in lavorazione sul server (per lo stato dal vivo sulla home). */
    fun markActive(id: String) { sessions[id] = Session(id).also { it.active = true } }
    fun activeFor(text: String) = sessions.values.firstOrNull { it.text == text && it.active }?.id
    fun sessionFor(text: String) = sessions.values.firstOrNull { it.text == text }?.id
    fun close() { server.close(); sockets.forEach { runCatching { it.close() } } }
    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream()); val out = socket.getOutputStream()
        fun line(): String = buildString { while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) append(b.toChar()) } }
        val requestLine = line().split(' '); val path = requestLine.getOrNull(1) ?: return; val httpMethod = requestLine.getOrNull(0).orEmpty()
        val headers = HashMap<String, String>()
        while (true) { val row = line(); if (row.isEmpty()) break; val at = row.indexOf(':'); if (at > 0) headers[row.substring(0, at).lowercase()] = row.substring(at + 1).trim() }
        val bodyBytes = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0).also { for (i in it.indices) it[i] = input.read().toByte() }
        if (path != "/api/ws") {
            val (code, text) = rest(httpMethod, path, String(bodyBytes))
            val response = text.toByteArray()
            out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(response); out.flush(); return
        }
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: hermes-gateway-v1\r\n\r\n".toByteArray()); out.flush()
        fun send(value: JSONObject) { val data = value.toString().toByteArray(); synchronized(out) { out.write(0x81); if (data.size < 126) out.write(data.size) else { out.write(126); out.write(data.size shr 8); out.write(data.size and 255) }; out.write(data); out.flush() } }
        fun event(id: String, type: String, text: String = "") = send(JSONObject().put("jsonrpc", "2.0").put("method", "event").put("params", JSONObject().put("session_id", id).put("type", type).put("payload", JSONObject().put("text", text))))
        fun complete(session: Session) {
            if (session.interrupted) return
            event(session.id, "message.delta", "risposta ${session.text}")
            session.completed = true; session.active = false
            event(session.id, "message.complete", "risposta ${session.text}")
        }
        event("", "gateway.ready")
        while (!socket.isClosed) {
            val first = input.read(); if (first < 0 || first and 15 == 8) return
            val second = input.read(); var size = second and 127
            if (size == 126) size = (input.read() shl 8) or input.read()
            check(size < 65536)
            val mask = ByteArray(4); if (second and 128 != 0) for (i in 0..3) mask[i] = input.read().toByte()
            val data = ByteArray(size); for (i in data.indices) { val b = input.read(); if (b < 0) return; data[i] = (b xor mask[i % 4].toInt()).toByte() }
            if (first and 15 != 1) continue
            val rpc = JSONObject(String(data).trim()); val method = rpc.optString("method"); val params = rpc.optJSONObject("params") ?: JSONObject()
            val result = JSONObject()
            var error: JSONObject? = null
            fun fail(message: String, code: Int = -32602) { error = JSONObject().put("code", code).put("message", message) }
            /** Rifiuta parametri mancanti o non previsti, come fa il gateway reale (pydantic `extra = forbid`). */
            fun strict(required: Set<String>, optional: Set<String> = emptySet()): Boolean {
                val keys = params.keys().asSequence().toSet()
                val extra = keys - required - optional - setOf("profile")
                val missing = required - keys
                if (extra.isNotEmpty()) fail("Extra inputs are not permitted: ${extra.joinToString()}")
                else if (missing.isNotEmpty()) fail("Field required: ${missing.joinToString()}")
                return error == null
            }
            when (method) {
                "client.capabilities" -> {}
                "billing.state", "clarify.lock", "profiles.create" -> {}
                "image.attach_bytes", "pdf.attach" -> if (strict(setOf("session_id", "content_base64", "filename"))) {
                    val bytes = runCatching { Base64.getDecoder().decode(params.getString("content_base64")) }.getOrNull()
                    if (sessions[params.getString("session_id")] == null) fail("Unknown session", -32004)
                    else if (bytes == null || bytes.isEmpty()) fail("content_base64 non valido")
                    else if (method == "image.attach_bytes" && !params.getString("filename").endsWith(".jpg")) fail("Estensione immagine non valida")
                    else if (method == "pdf.attach" && bytes.copyOf(4).decodeToString() != "%PDF") fail("Non è un PDF")
                    else attachments.add(method to params.getString("filename"))
                }
                "model.options" -> { strict(emptySet()); result.put("model", "qa-small").put("provider", "qa").put("providers", JSONArray().put(JSONObject().put("slug", "qa").put("name", "QA").put("is_current", true).put("models", JSONArray().put("qa-small").put(JSONObject().put("id", "qa-expensive"))))) }
                "config.set" -> if (strict(setOf("session_id", "key", "value"), setOf("confirm_expensive_model"))) {
                    val value = params.getString("value")
                    if (params.getString("key") != "model" || !value.contains("--provider")) fail("Valore non valido")
                    else if (value.startsWith("qa-expensive") && !params.optBoolean("confirm_expensive_model")) result.put("confirm_required", true)
                    else { result.put("value", value.substringBefore(' ')); models.add(value) }
                }
                "slash.exec" -> if (strict(setOf("session_id", "command"))) { if (!params.getString("command").startsWith("/")) fail("Comando senza barra") else result.put("output", "eseguito ${params.getString("command")}") }
                "subagent.list" -> if (strict(setOf("session_id"))) result.put("subagents", JSONArray().put(JSONObject().put("subagent_id", "sub-1").put("goal", "cerca").put("status", "running").put("tool_count", 2).put("accepting_steer", true)))
                "subagent.interrupt" -> if (strict(setOf("session_id", "subagent_id"))) result.put("found", params.getString("subagent_id") == "sub-1")
                "subagent.steer" -> if (strict(setOf("session_id", "subagent_id", "text"))) result.put("status", if (params.getString("subagent_id") == "sub-1") "queued" else "unknown")
                "session.close" -> if (strict(setOf("session_id"))) { val t = sessions[params.getString("session_id")]; result.put("closed", t != null); t?.live = false }
                "session.title" -> if (strict(setOf("session_id", "title"))) { if (sessions[params.getString("session_id")] == null) fail("Unknown session", -32004) }
                "session.compress" -> if (strict(setOf("session_id"))) { if (sessions[params.getString("session_id")] == null) fail("Unknown session", -32004) else result.put("status", "compresso") }
                "session.archive" -> if (strict(setOf("session_id", "archived"))) {
                    val id = params.getString("session_id"); val target = sessions[id]
                    if (target == null) fail("Unknown session", -32004) else if (target.active) fail("Session is still active", -32009) else archived.add(id)
                }
                "session.delete" -> if (strict(setOf("session_id"))) {
                    val id = params.getString("session_id"); val target = sessions[id]
                    if (target == null) fail("Unknown session", -32004) else if (target.active || target.live) fail("cannot delete an active session", -32009) else { sessions.remove(id); deleted.add(id) }
                }
                "session.branch_stored" -> if (strict(setOf("parent_session_id"))) {
                    val parent = sessions[params.getString("parent_session_id")]
                    if (parent == null) fail("Unknown session", -32004) else {
                        val id = "qa-${synchronized(this) { ++nextId }}"
                        sessions[id] = Session(id).also { it.text = parent.text; it.completed = parent.completed }
                        branches.add(parent.id to id)
                        result.put("stored_session_id", id)
                    }
                }
                "profiles.list" -> result.put("profiles", JSONArray())
                "session.list" -> result.put("sessions", JSONArray())
                "session.create" -> {
                    val id = "qa-${synchronized(this) { ++nextId }}"; sessions[id] = Session(id).also { it.live = true }
                    result.put("session_id", id).put("stored_session_id", id)
                }
                "session.resume" -> {
                    val id = params.getString("session_id"); val session = sessions[id]
                    if (session == null) fail("Unknown session", -32004) else {
                    session.live = true
                    val messages = JSONArray()
                    if (session.text.isNotEmpty()) messages.put(JSONObject().put("role", "user").put("text", session.text))
                    if (session.completed) messages.put(JSONObject().put("role", "assistant").put("text", "risposta ${session.text}"))
                    result.put("session_id", id).put("messages", messages)
                    }
                }
                "session.active_list" -> result.put("sessions", JSONArray(sessions.values.filter { it.active }.map { JSONObject().put("session_key", it.id).put("status", "working") }))
                "session.interrupt" -> { val id = params.getString("session_id"); interrupted.add(id); sessions[id]?.let { it.interrupted = true; it.active = false } }
                "prompt.submit" -> {
                    val session = sessions.getValue(params.getString("session_id")); session.text = params.getString("text"); session.active = true
                    texts.add(session.text); synchronized(this) { promptCount++ }
                }
                else -> fail("Method not found: $method", -32601)
            }
            if (rpc.has("id")) send(JSONObject().put("jsonrpc", "2.0").put("id", rpc.get("id")).also { if (error != null) it.put("error", error) else it.put("result", result) })
            if (method == "prompt.submit") {
                val session = sessions.getValue(params.getString("session_id"))
                if (session.text == "recover") { socket.close(); return }
                thread(isDaemon = true) { runCatching { Thread.sleep(if (session.text == "cancel") 6000 else if (session.text == "english") 6500 else 2400); complete(session) } }
            }
            if (method == "session.resume") {
                val session = sessions.getValue(params.getString("session_id"))
                if (session.text == "recover" && session.active) thread(isDaemon = true) { runCatching { Thread.sleep(2800); if (!session.completed) complete(session) } }
            }
        }
    }
}
