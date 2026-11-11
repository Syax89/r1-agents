package dev.r1agents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Routine a evento: invece che a un orario partono quando succede qualcosa sul telefono.
 * Gli eventi si ascoltano finché il processo è vivo (R1 Agents è la schermata iniziale, quindi quasi sempre):
 * da Android 8 un'app non può farsi svegliare da questi broadcast con il manifest.
 */
object RoutineEvents {
    /** carica = cavo collegato, scollega = cavo tolto, wifi = connesso a un wifi, cuffie = cuffie o altoparlante collegati. */
    val TRIGGERS = listOf("carica", "scollega", "wifi", "cuffie")
    /** Una stessa routine non riparte prima di 10 minuti (cavo che balla, wifi che va e viene). */
    const val COOLDOWN_MS = 10 * 60_000L

    @Volatile private var started = false

    /** Vero se la routine può partire adesso per questo evento (aggiorna l'ultima partenza). */
    internal fun due(last: Long, now: Long): Boolean = now - last >= COOLDOWN_MS || now < last

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val power = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { fire(app, if (i.action == Intent.ACTION_POWER_CONNECTED) "carica" else "scollega") }
        }
        app.registerReceiver(power, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) })
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            var onWifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    if (wifi && !onWifi) fire(app, "wifi")
                    onWifi = wifi
                }
                override fun onLost(n: Network) { onWifi = false }
            })
        }
        runCatching {
            val am = app.getSystemService(AudioManager::class.java)
            val outputs = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET)
            var primed = false
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    // La prima chiamata elenca i dispositivi già presenti: non è un collegamento.
                    if (!primed) { primed = true; return }
                    if (added.any { it.isSink && it.type in outputs }) fire(app, "cuffie")
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    private fun fire(ctx: Context, trigger: String) {
        Io.run {
            val db = Db.get(ctx)
            val agents = db.agents().map { it.id }.toSet()
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            for (r in db.routines()) {
                if (!r.on || r.trigger != trigger || r.agentId !in agents) continue
                if (!due(prefs.getLong("rt_last_${r.id}", 0L), now)) continue
                prefs.edit().putLong("rt_last_${r.id}", now).apply()
                // Da background Android può rifiutare il servizio in primo piano: allora la routine gira qui, nel processo già vivo.
                try { ctx.startForegroundService(Intent(ctx, RoutineService::class.java).putExtra("id", r.id)) }
                catch (e: Exception) { val id = r.id; Io.run { RoutineService.execute(ctx, id) } }
            }
        }
    }
}
