package dev.r1agents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Scatta l'ora di una sveglia, di un timer o di una routine. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val kind = i.getStringExtra("kind") ?: return; val id = i.getLongExtra("id", 0)
        val db = Db.get(ctx)
        when (kind) {
            Sched.KIND_ALARM -> db.alarm(id)?.takeIf { it.on }?.let { a ->
                if (a.days == 0) { a.on = false; db.save(a) }   // una volta sola: si spegne
                else Sched.all(ctx, db)                          // ripetuta: prossimo giro
                ctx.startForegroundService(Intent(ctx, RingService::class.java).putExtra("title", Sched.time(a.hour, a.minute)).putExtra("label", a.label).putExtra("kind", kind))
            }
            Sched.KIND_TIMER -> db.timers().firstOrNull { it.id == id }?.let { t ->
                db.deleteTimer(id)
                ctx.startForegroundService(Intent(ctx, RingService::class.java).putExtra("title", "timer").putExtra("label", t.label).putExtra("kind", kind))
            }
            Sched.KIND_ROUTINE -> db.routine(id)?.takeIf { it.on && db.agents().any { a -> a.id == it.agentId } }?.let { r ->
                if (r.days == 0) { r.on = false; db.save(r) } else Sched.all(ctx, db)
                ctx.startForegroundService(Intent(ctx, RoutineService::class.java).putExtra("id", id))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val db = Db.get(ctx); Sched.channels(ctx); Sched.all(ctx, db)
        if (ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(CronWatch.PREF, false)) CronWatch.arm(ctx, true)
    }
}
