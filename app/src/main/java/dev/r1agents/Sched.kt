package dev.r1agents

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Calendar

/** Pianificazione di sveglie, timer e routine con AlarmManager. `days`: bit 0 = lunedì … bit 6 = domenica; 0 = una volta. */
object Sched {
    const val KIND_ALARM = "alarm"; const val KIND_TIMER = "timer"; const val KIND_ROUTINE = "routine"
    const val CH_RING = "ring"; const val CH_INFO = "info"
    val DAYS = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RING, UiText.text(ctx, R.string.ui_9ae88045a8), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Routine", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Prossimo istante (ms) per quell'ora nei giorni indicati, dopo `from`. */
    fun next(hour: Int, minute: Int, days: Int, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        repeat(8) {
            val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lunedì = 0
            if (c.timeInMillis > from && (days == 0 || days and (1 shl bit) != 0)) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return from + 24 * 3600_000L
    }

    private fun pi(ctx: Context, kind: String, id: Long): PendingIntent =
        PendingIntent.getBroadcast(ctx, (kind.hashCode() xor id.toInt()), Intent(ctx, AlarmReceiver::class.java).setData(Uri.parse("r1agents://$kind/$id"))
            .putExtra("kind", kind).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun cancel(ctx: Context, kind: String, id: Long) { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, kind, id)) }

    private fun set(ctx: Context, kind: String, id: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // sveglie e timer come "sveglia di sistema" (icona nella barra, esatte senza permessi); routine esatte senza icona
        if (kind == KIND_ROUTINE) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, kind, id))
        else am.setAlarmClock(AlarmManager.AlarmClockInfo(at, open(ctx)), pi(ctx, kind, id))
    }

    /** Riprogramma tutto ciò che è attivo (all'avvio, dopo il riavvio del telefono e dopo ogni modifica). */
    fun all(ctx: Context, db: Db) {
        for (a in db.alarms()) if (a.on) set(ctx, KIND_ALARM, a.id, next(a.hour, a.minute, a.days)) else cancel(ctx, KIND_ALARM, a.id)
        for (t in db.timers()) if (t.endAt > System.currentTimeMillis()) set(ctx, KIND_TIMER, t.id, t.endAt)
        val agents = db.agents().map { it.id }.toSet()
        for (r in db.routines()) if (r.on && r.agentId in agents && r.trigger.isEmpty()) set(ctx, KIND_ROUTINE, r.id, next(r.hour, r.minute, r.days)) else cancel(ctx, KIND_ROUTINE, r.id)
    }

    fun time(h: Int, m: Int) = "%02d:%02d".format(h, m)
    fun daysText(days: Int) = if (days == 0) "una volta" else if (days == 127) "ogni giorno" else if (days == 31) "lun-ven"
        else DAYS.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
}
