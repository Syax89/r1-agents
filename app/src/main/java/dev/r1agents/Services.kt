package dev.r1agents

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Suoneria di una sveglia o di un timer: notifica a schermo intero con "stop" e "rimanda". Si ferma da sola dopo un minuto. */
class RingService : Service() {
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "stop" -> { stopSelf(); return START_NOT_STICKY }
            "snooze" -> {
                val db = Db.get(this); val label = i.getStringExtra("label").orEmpty()
                db.addTimer(System.currentTimeMillis() + 10 * 60_000, label); Sched.all(this, db)
                stopSelf(); return START_NOT_STICKY
            }
        }
        val title = i?.getStringExtra("title").orEmpty(); val label = i?.getStringExtra("label").orEmpty(); val kind = i?.getStringExtra("kind").orEmpty()
        Sched.channels(this)
        val show = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("ring_title", title).putExtra("ring_label", label).putExtra("ring_kind", kind)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun act(a: String, text: String) = Notification.Action.Builder(null, text, PendingIntent.getService(this, a.hashCode(),
            Intent(this, RingService::class.java).setAction(a).putExtra("label", label), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        val n = Notification.Builder(this, Sched.CH_RING).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(label.ifBlank { if (kind == Sched.KIND_TIMER) UiText.text(this@RingService, R.string.ui_1bb7fcaedb) else UiText.text(this@RingService, R.string.ui_4b15f21af0) })
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setContentIntent(show).setFullScreenIntent(show, true)
            .addAction(act("snooze", UiText.text(this@RingService, R.string.ui_8d6a685440))).addAction(act("stop", "stop")).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            isLooping = true; play()
        }
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0))
        handler.postDelayed({ stopSelf() }, 60_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); ringtone?.stop()
        getSystemService(Vibrator::class.java)?.cancel(); super.onDestroy()
    }
}

/** Esegue una routine: nuova chat con l'agente, il prompt come primo messaggio, e una notifica con la risposta. */
class RoutineService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val id = i?.getLongExtra("id", 0) ?: 0
        Sched.channels(this)
        startForeground(2, Notification.Builder(this, Sched.CH_INFO).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(UiText.text(this@RoutineService, R.string.ui_5c6d1ac4e6)).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Io.run {
            val ctx: Context = this
            val db = Db.get(ctx)
            try {
                val r = db.routine(id); val agent = db.agents().firstOrNull { it.id == r?.agentId }
                if (r != null && agent != null) {
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    val engine = Engine(db, Settings(prefs), NousAuth(prefs), HermesDash(prefs))
                    val chat = db.newChat(agent.id); db.title(chat, r.name)
                    var text = ""
                    try { engine.turn(agent, chat, r.prompt) { } } catch (e: Exception) { text = UiText.text(this@RoutineService, R.string.ui_2f38b99da3, UiText.message(this@RoutineService, e.message)) }
                    if (text.isEmpty()) text = lastAnswer(db, chat)
                    val open = PendingIntent.getActivity(ctx, chat.toInt(), Intent(ctx, MainActivity::class.java).putExtra("open_chat", chat).putExtra("open_agent", agent.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    getSystemService(android.app.NotificationManager::class.java).notify(1000 + chat.toInt(),
                        Notification.Builder(ctx, Sched.CH_INFO).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("${agent.name} · ${r.name}")
                            .setContentText(text.take(120)).setStyle(Notification.BigTextStyle().bigText(text.take(600))).setContentIntent(open).setAutoCancel(true).build())
                }
            } finally { stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun lastAnswer(db: Db, chat: Long): String {
        val m = db.messages(chat).lastOrNull { it.first == "assistant" } ?: return UiText.text(this@RoutineService, R.string.ui_1f1c954e90)
        val a = org.json.JSONArray(m.second); val sb = StringBuilder()
        for (k in 0 until a.length()) a.getJSONObject(k).takeIf { it.getString("type") == "text" }?.let { sb.append(it.optString("text")) }
        return sb.toString().ifBlank { UiText.text(this@RoutineService, R.string.ui_1f1c954e90) }
    }
}
