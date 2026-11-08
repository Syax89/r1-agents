package dev.r1agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/** Esito del controllo dopo un riavvio: la risposta al nostro messaggio, il messaggio arrivato ma senza risposta, o mai arrivato. */
internal sealed class Recovery {
    data class Reply(val text: String) : Recovery()
    object NoReply : Recovery()
    object NotSent : Recovery()
}

/** `history`: cronologia di Hermes (ruolo, testo); `sent`: il testo che avevamo inviato. Con testo vuoto l'invio era un allegato ("Guarda l'allegato."). */
internal fun judgeRecovery(history: List<Pair<String, String>>, sent: String): Recovery {
    val lastUser = history.indexOfLast { it.first == "user" }
    if (lastUser < 0) return Recovery.NotSent
    val marker = sent.trim().ifEmpty { "Guarda l'allegato." }
    if (!history[lastUser].second.contains(marker)) return Recovery.NotSent
    val reply = history.drop(lastUser + 1).lastOrNull { it.first == "assistant" && it.second.isNotBlank() }?.second
    return if (reply != null) Recovery.Reply(reply) else Recovery.NoReply
}

/** Possiede connessione e turni Hermes; nessuna Activity viene trattenuta dopo la disiscrizione. */
class HermesRuntime private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var instance: HermesRuntime? = null
        fun get(ctx: Context): HermesRuntime = instance ?: synchronized(this) { instance ?: HermesRuntime(ctx.applicationContext).also { instance = it } }
        const val CHANNEL_WORK = "hermes_work"
        const val CHANNEL_RESULT = "hermes_results"
        private const val PENDING = "running_hermes_chats"
    }
    class Task(val agent: Agent, val chatId: Long, val observing: Boolean = false) {
        val progress = TurnProgress()
        @Volatile var engine: Engine? = null
        val pendingText = StringBuilder()
        var flushQueued = false
    }
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val db = Db.get(ctx)
    val dash = HermesDash(prefs)
    private val tasks = ConcurrentHashMap<Long, Task>()
    val requests = ConcurrentHashMap<String, HermesDash.SrvRequest>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var foreground = false
    @Volatile var visibleChat: Long? = null
    @Volatile var requestListener: ((HermesDash.SrvRequest) -> Unit)? = null
    @Volatile var requestCancelListener: ((String) -> Unit)? = null
    var jobsChanged: (() -> Unit)? = null
    private val configuring = java.util.concurrent.atomic.AtomicBoolean(false)
    fun isConfiguring() = configuring.get()
    fun configure(pairing: Pairing, password: String? = null) {
        check(configuring.compareAndSet(false, true)) { UiText.text(ctx, R.string.ui_3fadbc598d) }
        try {
            synchronized(this) { check(activeTasks().isEmpty()) { UiText.text(ctx, R.string.ui_7ab7a06520) } }
            dash.reconfigure(pairing.origin, pairing.user, password)
            requests.keys.toList().forEach { dismissRequest(it) }
        } finally { configuring.set(false) }
    }
    @Volatile var restoring = false
        private set
    init {
        channels()
        dash.onStored = { key, sid -> db.setHsid(key, sid) }
        dash.onRequest = { request ->
            requests[request.id.toString()] = request
            val listener = requestListener
            if (foreground && listener != null) listener(request)
            else notify(null, UiText.text(ctx, R.string.ui_8ec9ddab56), UiText.text(ctx, R.string.ui_7675071e74), 0x45000000 or (request.id.toString().hashCode() and 0xffffff))
        }
        dash.onRequestCancel = { id -> requests.remove(id); requestCancelListener?.invoke(id); ctx.getSystemService(NotificationManager::class.java).cancel(0x45000000 or (id.hashCode() and 0xffffff)) }
    }
    fun dismissRequest(id: String) {
        requests.remove(id); dash.forgetRequest(id)
        ctx.getSystemService(NotificationManager::class.java).cancel(0x45000000 or (id.hashCode() and 0xffffff))
    }
    fun active(chatId: Long) = tasks[chatId]?.takeIf { it.progress.snapshot().status == TurnProgress.Status.RUNNING }
    fun activeTasks() = tasks.values.filter { it.progress.snapshot().status == TurnProgress.Status.RUNNING }
    /** `prompt`: il testo appena inviato (resta solo in questo turno e non finisce nei backup: prefisso "pending_"), per riconoscere dopo un riavvio se Hermes l'ha davvero ricevuto. */
    @Synchronized private fun remember(id: Long, pending: Boolean, prompt: String? = null) {
        val keys = prefs.getStringSet(PENDING, emptySet()).orEmpty().toMutableSet()
        if (pending) keys.add(id.toString()) else keys.remove(id.toString())
        val edit = prefs.edit().putStringSet(PENDING, keys)
        if (!pending) edit.remove("pending_prompt_$id") else if (prompt != null) edit.putString("pending_prompt_$id", prompt)
        edit.apply()
    }
    fun hasRecovery() = prefs.getStringSet(PENDING, emptySet()).orEmpty().isNotEmpty()
    private fun appendText(task: Task, delta: String) {
        synchronized(task.pendingText) {
            task.pendingText.append(delta)
            if (!task.flushQueued) { task.flushQueued = true; main.postDelayed({ flushText(task) }, 50) }
        }
    }
    private fun flushText(task: Task) {
        synchronized(task.pendingText) {
            task.flushQueued = false
            if (task.pendingText.isNotEmpty()) { val text = task.pendingText.toString(); task.pendingText.setLength(0); task.progress.append(text) }
        }
    }
    private fun startService() { ctx.startForegroundService(Intent(ctx, HermesTurnService::class.java)) }
    fun ensureRecoveryService() { if (hasRecovery()) runCatching { startService() } }
    @Synchronized fun start(agent: Agent, chatId: Long, text: String, retry: Boolean, attachments: List<Attachment>): Task {
        check(!isConfiguring()) { UiText.text(ctx, R.string.ui_b121797238) }
        active(chatId)?.let { return it }
        val task = Task(agent, chatId); tasks[chatId] = task; remember(chatId, true, text)
        try { startService() } catch (e: Exception) { task.progress.fail(UiText.text(ctx, R.string.ui_8687ac1bcd, UiText.message(ctx, e.message))); remember(chatId, false); return task }
        Io.run {
            var recoverable = false
            try {
                val engine = Engine(db, Settings(prefs, "dashboard"), NousAuth(prefs), dash); task.engine = engine
                if (task.progress.snapshot().status == TurnProgress.Status.CANCELLED) return@run
                engine.turn(agent, chatId, text, retry, { task.progress.activity(it) }, attachments, onText = { appendText(task, it) })
                flushText(task); task.progress.finish()
            } catch (e: Exception) {
                flushText(task)
                recoverable = e is java.io.IOException && db.hsid(chatId).isNotBlank()
                task.progress.fail(e.message ?: UiText.text(ctx, R.string.ui_44108762c4))
            } finally { terminal(task, recoverable) }
        }
        changed(); return task
    }
    @Synchronized fun observe(agent: Agent, chatId: Long, hsid: String): Task {
        active(chatId)?.let { return it }
        val task = Task(agent, chatId, true); tasks[chatId] = task; remember(chatId, true)
        try { startService() } catch (e: Exception) { task.progress.fail(e.message ?: UiText.text(ctx, R.string.ui_8705176d8e)); remember(chatId, false); return task }
        Io.run { follow(task, hsid) }
        changed(); return task
    }
    private fun follow(task: Task, hsid: String) {
        var recoverable = false
        try {
            // Resume e active_list sono letture: non ripetere mai prompt.submit durante un recupero.
            if (task.progress.snapshot().status == TurnProgress.Status.CANCELLED) return
            val history = dash.open(task.chatId, hsid, task.agent.profile)
            if (task.progress.snapshot().status == TurnProgress.Status.CANCELLED) return
            if (dash.liveChecked()[hsid] in listOf("working", "waiting")) {
                dash.observe(task.chatId, task.agent.profile, hsid, { task.progress.activity(it) }) { task.progress.append(it) }
                db.replaceMessages(task.agent.id, task.chatId, dash.open(task.chatId, hsid, task.agent.profile))
            } else {
                val sent = prefs.getString("pending_prompt_${task.chatId}", null)
                if (sent == null) {
                    // Chat seguita senza un nostro invio (avviata altrove): vale l'ultima risposta di Hermes.
                    db.replaceMessages(task.agent.id, task.chatId, history)
                    history.lastOrNull { it.first == "assistant" }?.second?.let { task.progress.append(it) }
                } else {
                    // Un nostro invio interrotto (processo terminato): la risposta e' valida solo se segue il nostro messaggio. Altrimenti non si
                    // mostra una risposta vecchia ne' si sovrascrive la chat locale (la bozza resta, si puo' riprovare).
                    val verdict = judgeRecovery(history, sent)
                    val reply = (verdict as? Recovery.Reply)?.text
                    when {
                        reply != null -> { db.replaceMessages(task.agent.id, task.chatId, history); task.progress.append(reply) }
                        verdict == Recovery.NoReply -> throw IllegalStateException(UiText.text(ctx, R.string.ui_63255f1b12))
                        else -> throw IllegalStateException(UiText.text(ctx, R.string.ui_48947903de))
                    }
                }
            }
            task.progress.finish()
        } catch (e: Exception) { recoverable = e is java.io.IOException; task.progress.fail(e.message ?: UiText.text(ctx, R.string.ui_2ed139b29b)) }
        finally { terminal(task, recoverable) }
    }
    fun restore() {
        synchronized(this) { if (restoring) return; restoring = true }
        Io.run {
            try {
                val keys = prefs.getStringSet(PENDING, emptySet()).orEmpty().toList()
                for (value in keys) {
                    val id = value.toLongOrNull() ?: continue
                    if (active(id) != null) continue
                    val agent = db.agents().firstOrNull { a -> db.chats(a.id).any { it.id == id } }
                    val hsid = db.hsid(id)
                    if (agent == null || hsid.isBlank()) { remember(id, false); continue }
                    val task = Task(agent, id, true)
                    synchronized(this) { if (active(id) != null) return@synchronized; tasks[id] = task }
                    if (tasks[id] !== task) continue
                    Io.run { follow(task, hsid) }
                }
            } finally { synchronized(this) { restoring = false }; changed() }
        }
    }
    fun cancel(chatId: Long) {
        val task = active(chatId) ?: return
        flushText(task); task.progress.cancel(); remember(chatId, false)
        if (task.engine != null) task.engine?.cancel(chatId) else dash.cancel(chatId)
        changed()
    }
    fun cancelAll() { activeTasks().forEach { cancel(it.chatId) } }
    private fun terminal(task: Task, recoverable: Boolean) {
        if (!recoverable || task.progress.snapshot().status == TurnProgress.Status.CANCELLED) remember(task.chatId, false)
        val snapshot = task.progress.snapshot()
        if (!(foreground && visibleChat == task.chatId) && snapshot.status != TurnProgress.Status.CANCELLED) {
            val locked = prefs.getString("app_pin_hash", "").orEmpty().isNotEmpty()
            val title = if (locked) "R1 Agents" else task.agent.name
            val text = if (snapshot.status == TurnProgress.Status.DONE) UiText.text(ctx, R.string.ui_83578e8412) else UiText.text(ctx, R.string.ui_3d45dcb794)
            notify(task, title, text, 0x44000000 or (task.chatId.toInt() and 0xffffff))
        }
        changed()
        // Conservare solo pochi risultati: i messaggi completi vivono già nel database.
        if (tasks.size > 24) tasks.entries.filter { it.value.progress.snapshot().status != TurnProgress.Status.RUNNING && it.key != visibleChat }.take(tasks.size - 24).forEach { tasks.remove(it.key, it.value) }
    }
    private fun changed() { main.post { jobsChanged?.invoke() } }
    fun pendingIntent(task: Task?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (task != null) intent.setData(Uri.parse("r1agents://chat/${task.chatId}")).putExtra("open_chat", task.chatId).putExtra("open_agent", task.agent.id)
        return PendingIntent.getActivity(ctx, task?.chatId?.toInt() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun notify(task: Task?, title: String, text: String, id: Int) {
        val notification = Notification.Builder(ctx, CHANNEL_RESULT).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pendingIntent(task)).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }
    fun refreshChannels() { channels(); changed() }
    private fun channels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORK, UiText.text(ctx, R.string.ui_6d6d4c0342), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, UiText.text(ctx, R.string.ui_d10cb378a4), NotificationManager.IMPORTANCE_DEFAULT))
    }
}
