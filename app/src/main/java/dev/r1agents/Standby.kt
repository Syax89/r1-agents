package dev.r1agents

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager

/**
 * Lo standby di R1 Agents: l'orologio (glance) come schermata di riposo.
 *
 * - Sopra il blocco: se lo schermo si spegne mentre sei in R1 Agents, l'app passa all'orologio e chiede ad Android di
 *   mostrarlo sopra la schermata di blocco. Il blocco resta sotto: per entrare (tocco, rotella) Android chiede il suo
 *   sblocco e solo dopo torni dov'eri. Con un'altra app in primo piano non cambia niente.
 * - Sempre acceso in carica: in carica, dopo un minuto senza tocchi, l'orologio resta acceso con la luce bassa, come
 *   una sveglia da comodino. Staccando il cavo torna il comportamento normale (lo schermo si spegne da solo).
 */
object Standby {
    const val IDLE_MS = 60_000L
    /** Luminosità dello schermo nell'orologio sempre acceso (0..1). */
    const val AOD_BRIGHTNESS = 0.06f
    fun lockOn(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("standby_lock", true)
    fun chargeOn(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("standby_charge", true)
    fun charging(ctx: Context): Boolean =
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}

/** Mette l'orologio in cima (o usa quello già in cima) e lo rende visibile sopra il blocco. `aod`: resta acceso con luce bassa. */
internal fun MainActivity.enterStandby(aod: Boolean) {
    val top = stack.lastOrNull() ?: return
    // Mai sopra cose in corso o da proteggere: PIN, voce, fogli e finestre, suoneria, chat bloccata.
    if (pinGate != null || voiceConversation != null || overlays.isNotEmpty() || top.view.tag == "ring" || chatLocked(top.chatKey)) {
        if (top.view.tag != "ring") setShowWhenLocked(false)   // nient'altro dell'app sopra il blocco (la suoneria sì)
        return
    }
    if (top !== standbyScreen) {
        if (top.view.tag == "glance") standbyScreen = top
        else { val g = glanceScreen(standby = true); standbyScreen = g; push(g, fade = true) }
    }
    setShowWhenLocked(true)
    if (aod) startAod()
}

private fun MainActivity.startAod() {
    if (aodOn) return
    aodOn = true
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.attributes = window.attributes.apply { screenBrightness = Standby.AOD_BRIGHTNESS }
}

internal fun MainActivity.stopAod() {
    if (!aodOn) return
    aodOn = false
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
}

/** L'orologio di standby non c'è più: niente più sopra il blocco, luce normale. */
internal fun MainActivity.standbyGone(screen: MainActivity.Screen) {
    if (standbyScreen !== screen) return
    standbyScreen = null
    setShowWhenLocked(false)
    stopAod()
}

/** Prima di uscire dall'orologio sopra il blocco: Android chiede il suo sblocco, poi `after`. Senza blocco, subito. */
internal fun MainActivity.unlockThen(after: () -> Unit) {
    val km = getSystemService(KeyguardManager::class.java)
    if (km == null || !km.isKeyguardLocked) { after(); return }
    km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() { runOnUiThread(after) }
    })
}

/** Dopo un minuto senza tocchi, in carica: orologio sempre acceso. Si riarma a ogni tocco o tasto. */
internal fun MainActivity.armStandbyIdle() {
    root.removeCallbacks(standbyIdle)
    if (visible && pluggedIn && Standby.chargeOn(this)) root.postDelayed(standbyIdle, Standby.IDLE_MS)
}

/** Cavo collegato o staccato: si riarma l'attesa, e staccandolo l'orologio smette di restare acceso. */
internal fun MainActivity.powerWatcher(): BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        pluggedIn = i.action == Intent.ACTION_POWER_CONNECTED
        if (!pluggedIn) stopAod()
        armStandbyIdle()
    }
}.also {
    pluggedIn = Standby.charging(this)
    val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
    if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(it, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(it, f)
}
