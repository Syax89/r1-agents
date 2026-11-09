package dev.r1agents

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject

/** Avvisi opzionali (pref `cron_notify`, spenta di default): ogni 30 minuti controlla i cron di Hermes e notifica le attività che hanno girato dall'ultima volta. */
object CronWatch {
    const val PREF = "cron_notify"
    private const val SEEN = "pending_cron_seen"
    private const val EVERY = 30 * 60_000L

    /** Funzione pura: i job eseguiti dopo l'ultimo controllo. Un job mai visto serve solo a ricordarne l'ultima esecuzione (nessun avviso al primo giro). */
    fun newRuns(jobs: List<HermesJob>, seen: Map<String, Long>, firstRun: Boolean): List<HermesJob> =
        if (firstRun) emptyList() else jobs.filter { it.lastRun > 0 && it.lastRun > (seen[it.id] ?: 0L) }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, CronPollReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun arm(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (on) am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + EVERY, EVERY, pending(ctx)) else am.cancel(pending(ctx))
    }

    fun poll(ctx: Context) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF, false) || Settings(prefs).provider.id != "dashboard") return
        val jobs = HermesRuntime.get(ctx).dash.cronJobs()
        val raw = prefs.getString(SEEN, null)
        val seen = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject()).let { o -> o.keys().asSequence().associateWith { o.optLong(it) } }
        val fresh = newRuns(jobs, seen, raw == null)
        prefs.edit().putString(SEEN, JSONObject(jobs.associate { it.id to it.lastRun }).toString()).apply()
        if (fresh.isEmpty()) return
        Sched.channels(ctx)
        val open = PendingIntent.getActivity(ctx, 78, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val locked = prefs.getString("app_pin_hash", "").orEmpty().isNotEmpty()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fresh.take(5).forEach { j ->
            nm.notify(0x46000000 or (j.id.hashCode() and 0xffffff), Notification.Builder(ctx, Sched.CH_INFO).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (locked) "R1 Agents" else j.name).setContentText(UiText.text(ctx, R.string.ui_e35f849a62))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

class CronPollReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val result = goAsync()
        Io.run { try { runCatching { CronWatch.poll(ctx.applicationContext) } } finally { result.finish() } }
    }
}
