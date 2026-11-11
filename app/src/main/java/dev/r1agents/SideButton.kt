package dev.r1agents

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.LinearLayout
import dev.r1agents.MainActivity.Screen
import dev.r1os.host.ui.*

/**
 * Il tasto laterale del Rabbit per Android è il tasto di accensione (KEY_POWER): le app non lo ricevono. La pressione
 * breve resta lo standby. Il "tieni premuto" invece Android lo può dare all'assistente digitale: se R1 Agents è
 * l'assistente (ruolo di sistema, lo conferma l'utente) e in "Tieni premuto il tasto di accensione" è scelto
 * "Assistente digitale", Android apre l'app con ACTION_ASSIST e qui si fa l'azione scelta dall'utente.
 */
object SideButton {
    val ACTIONS = listOf("voice", "chat", "recents", "torch", "clock")
    const val PREF = "side_action"
    /** Valore di Settings.Global.power_button_long_press per "assistente digitale" (LONG_PRESS_POWER_ASSISTANT). */
    private const val LONG_PRESS_ASSISTANT = 5

    fun action(ctx: Context): String = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PREF, "voice")?.takeIf { it in ACTIONS } ?: "voice"

    fun isAssistant(ctx: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        runCatching { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)

    /** Vero se il "tieni premuto" va all'assistente. Su versioni senza l'impostazione si considera da verificare (false). */
    fun longPressToAssistant(ctx: Context): Boolean =
        runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, "power_button_long_press") == LONG_PRESS_ASSISTANT }.getOrDefault(false)

    fun ready(ctx: Context) = isAssistant(ctx) && longPressToAssistant(ctx)

    /** Torcia accesa da qui: la pressione successiva la spegne. */
    @Volatile var torchOn = false
}

private fun MainActivity.sideLabel(id: String): String = when (id) {
    "voice" -> UiText.text(this, R.string.ui_2cf67fba62)
    "chat" -> UiText.text(this, R.string.ui_7ecde348ff)
    "recents" -> UiText.text(this, R.string.ui_950486e052)
    "torch" -> UiText.text(this, R.string.ui_4d897fa9ab)
    else -> UiText.text(this, R.string.ui_3d9a5cdfac)
}

/** Stato breve per la riga in Impostazioni. */
internal fun MainActivity.sideButtonSummary(): String =
    if (SideButton.ready(this)) UiText.text(this, R.string.ui_f9c821d87b, sideLabel(SideButton.action(this))) else UiText.text(this, R.string.ui_3bb63f1950)

/** L'azione del tasto laterale tenuto premuto (arriva come ACTION_ASSIST). */
internal fun MainActivity.runSideAction() {
    when (SideButton.action(this)) {
        "chat" -> visibleAgents().getOrNull(homeIndex)?.let { openLatest(it) }
        "recents" -> openSwitcher()
        "torch" -> { val on = !SideButton.torchOn; if (runDeviceAction(DeviceActions.Action("torcia", if (on) "on" else "off"), log = false)) SideButton.torchOn = on }
        "clock" -> push(glance())
        // Push-to-talk col tasto di accensione: Android dà all'app il "tenuto premuto" ma non il rilascio. Quindi:
        // tieni premuto = inizia ad ascoltare, tieni premuto di nuovo = smette e invia (come la doppia pressione di BUTTON_1).
        else -> if (voiceConversation?.isRecording == true) { pttLatched = false; pttIgnoreUp = false; pttStop() }
                else { pttLatched = true; pttIgnoreUp = false; pttAutoStop = true; pttStart() }
    }
}

/** Tornando dalle pagine di sistema (ruolo, tasto di accensione) lo stato si aggiorna da solo. */
internal fun MainActivity.sideButtonScreen(): Screen = sideButtonList().let { s -> Screen(s.view, onLeave = s.onLeave, onShow = s.onShow, onResume = s.onShow, buddyContext = s.buddyContext, onSuspend = s.onSuspend) }

private fun MainActivity.sideButtonList(): Screen = listPage(UiText.text(this, R.string.ui_addbf9ddc4), context = BuddySceneContext.CONFIGURE) { col ->
    col.addView(intro(UiText.text(this, R.string.ui_76d6820858)))
    val assistant = SideButton.isAssistant(this)
    col.addView(bigRow("spark", UiText.text(this, R.string.ui_3f00a4dc58), UiText.text(this, if (assistant) R.string.ui_0e596a8dcc else R.string.ui_c31924ac93), Theme.colorOf(if (assistant) "green" else "orange")) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            val rm = getSystemService(RoleManager::class.java)
            // Il sistema chiede conferma all'utente; se il dialogo non è disponibile si apre la pagina delle app predefinite.
            if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), REQ_SIDE_ROLE)
            else startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }.onFailure { snack(UiText.text(this, R.string.ui_14be4f8e71)) } }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
    val press = SideButton.longPressToAssistant(this)
    col.addView(bigRow("sliders", UiText.text(this, R.string.ui_129a753f2c), UiText.text(this, if (press) R.string.ui_0e596a8dcc else R.string.ui_d2bcbe16d9), Theme.colorOf(if (press) "green" else "orange")) {
        snack(UiText.text(this, R.string.ui_51698da844), long = true)
        runCatching { startActivity(Intent("android.settings.ACTION_POWER_MENU_SETTINGS")) }
            .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            .onFailure { snack(UiText.text(this, R.string.ui_14be4f8e71)) }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
    val current = SideButton.action(this)
    val choices = SideButton.ACTIONS.filter { it != "torch" || DeviceActions.hasFlash }
    // Come in Aspetto: una riga con il valore scelto, il tocco apre il foglio con le azioni.
    col.addView(settingRow(UiText.text(this, R.string.ui_194603a73f), sideLabel(current)) {
        sheetOf(UiText.text(this, R.string.ui_194603a73f), choices.map { id -> MainActivity.SheetItem(sideLabel(id), selected = id == current) {
            if (id != current) { prefs.edit().putString(SideButton.PREF, id).apply(); stack.lastOrNull()?.onShow?.invoke() } } })
    }.also { it.background = roundRect(Theme.CARD, dp(22f)); it.setPadding(dpi(16f), dpi(6f), dpi(14f), dpi(6f)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
    if (current == "voice") col.addView(hint(UiText.text(this, R.string.ui_4174759ddc)))
    col.addView(button(UiText.text(this, R.string.ui_46053fe150), toolAccent, primary = false, small = true) { runSideAction() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
}

internal const val REQ_SIDE_ROLE = 7311
