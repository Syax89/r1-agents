package dev.r1agents

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Stato del telefono che l'utente sceglie di condividere con gli agenti locali (pref `ctx_phone`, spenta di default). Solo dati locali e brevi: niente notifiche, contatti, posizione. */
object PhoneContext {
    const val PREF = "ctx_phone"

    /** Funzione pura: il testo per il prompt. Restituisce stringa vuota se non c'è nulla da dire. */
    fun format(battery: Int?, charging: Boolean, network: String?, nextAlarm: String?, timers: Int, recents: List<String>): String {
        val parts = mutableListOf<String>()
        if (battery != null && battery in 0..100) parts += "batteria $battery%" + if (charging) " (in carica)" else ""
        if (!network.isNullOrBlank()) parts += "rete: $network"
        if (!nextAlarm.isNullOrBlank()) parts += "prossima sveglia $nextAlarm"
        if (timers > 0) parts += if (timers == 1) "1 timer attivo" else "$timers timer attivi"
        if (recents.isNotEmpty()) parts += "app usate di recente: " + recents.take(5).joinToString()
        return if (parts.isEmpty()) "" else "Stato del telefono dell'utente: " + parts.joinToString("; ") + "."
    }

    fun nextAlarm(alarms: List<Alarm>, nowHour: Int, nowMinute: Int): String? {
        val on = alarms.filter { it.on }
        if (on.isEmpty()) return null
        val now = nowHour * 60 + nowMinute
        val next = on.minByOrNull { val m = it.hour * 60 + it.minute; if (m >= now) m - now else m + 1440 - now } ?: return null
        return "%02d:%02d".format(next.hour, next.minute)
    }

    fun describe(ctx: Context, db: Db, recents: List<String>): String = runCatching {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = (b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when { caps == null -> "assente"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dati mobili"; else -> "connessa" }
        val now = Instant.now().atZone(ZoneId.systemDefault())
        format(pct, charging, net, nextAlarm(db.alarms(), now.hour, now.minute), db.timers().count { it.endAt > System.currentTimeMillis() }, recents)
    }.getOrDefault("")
}
