package dev.r1agents

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Stato della rete in tempo reale (callback di sistema invece di accorgersene al primo errore dopo 10 s).
 * "In linea" = esiste una rete predefinita, anche senza internet verificato: il dashboard Hermes sta sulla rete di casa.
 */
object NetworkMonitor {
    @Volatile var online = true
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        started = true
        online = cm.activeNetwork != null
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = set(true)
                override fun onLost(network: Network) = set(cm.activeNetwork.let { it != null && it != network })
            })
        }.onFailure { started = false }
    }

    private fun set(v: Boolean) { if (v == online) return; online = v; listeners.forEach { runCatching { it(v) } } }

    /** Ascolta i cambi (chiamato da un thread di sistema). Ritorna la funzione per smettere. */
    fun watch(listener: (Boolean) -> Unit): () -> Unit { listeners.add(listener); return { listeners.remove(listener) } }
}
