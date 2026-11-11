package dev.r1agents

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import dev.r1os.host.ui.*

/** Azioni sul telefono chieste da un agente (vedi DeviceActions): descrizione, esecuzione, registro. Spostate da MainActivity. */
internal fun MainActivity.describeAction(a: DeviceActions.Action): String = when (a.kind) {
    "apri" -> UiText.text(this, R.string.ui_6ef46e17aa, a.arg)
    "timer" -> { val sec = DeviceActions.seconds(a.arg) ?: 0; UiText.text(this, R.string.ui_922c82d62c, (if (sec < 60) UiText.text(this, R.string.ui_e641f081b3, sec) else if (sec < 3600) UiText.text(this, R.string.ui_32b259a068, sec / 60) else UiText.text(this, R.string.ui_f31be6b4f3, sec / 3600))) }
    "sveglia" -> UiText.text(this, R.string.ui_a21efabed5, a.arg)
    "torcia" -> if (a.arg.equals("off", true)) UiText.text(this, R.string.ui_1a839844ee) else UiText.text(this, R.string.ui_24c8ab553b)
    "nota" -> UiText.text(this, R.string.ui_510a578c56)
    "volume" -> UiText.text(this, R.string.ui_ed62d9032c, a.arg)
    "luminosita" -> UiText.text(this, R.string.ui_17d094a7e3, a.arg)
    "media" -> UiText.text(this, R.string.ui_9b90bc7395, a.arg)
    "promemoria" -> UiText.text(this, R.string.ui_471cbe4581, a.arg)
    "calendario" -> UiText.text(this, R.string.ui_86f329a66f, a.arg)
    else -> UiText.text(this, R.string.ui_77027ea210, a.arg)
}
/** Esegue l'azione e, se viene da un agente, la scrive nel registro (pagina Sicurezza → azioni dell'agente). */
internal fun MainActivity.runDeviceAction(a: DeviceActions.Action, log: Boolean = true): Boolean = execDeviceAction(a).also { if (log) ActionLog.record(prefs, a.kind, a.arg, it) }
internal fun MainActivity.execDeviceAction(a: DeviceActions.Action): Boolean = runCatching {
    when (a.kind) {
        "apri" -> {
            val apps = appCache ?: loadApps().also { appCache = it }
            val i = appForCommand("apri " + a.arg, apps.map { it.label }) ?: apps.indexOfFirst { it.label.equals(a.arg, true) }.takeIf { it >= 0 } ?: return@runCatching false
            launchApp(apps[i]); true
        }
        "timer" -> { val sec = DeviceActions.seconds(a.arg) ?: return@runCatching false; askNotificationsOnce(); db.addTimer(System.currentTimeMillis() + sec * 1000L, ""); Sched.all(this, db); true }
        "sveglia" -> { val (h, m) = DeviceActions.time(a.arg) ?: return@runCatching false; db.save(Alarm(0, h, m, 0, "", true)); Sched.all(this, db); true }
        "torcia" -> {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return@runCatching false
            cm.setTorchMode(id, !a.arg.equals("off", true)); true
        }
        "nota" -> { db.addNote(a.arg.take(500)); true }
        "volume" -> {
            val am = getSystemService(android.media.AudioManager::class.java); val s = android.media.AudioManager.STREAM_MUSIC; val ui = android.media.AudioManager.FLAG_SHOW_UI
            when (val l = DeviceActions.level(a.arg) ?: return@runCatching false) {
                DeviceActions.UP -> am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_RAISE, ui)
                DeviceActions.DOWN -> am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_LOWER, ui)
                DeviceActions.MUTE -> am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_MUTE, ui)
                else -> am.setStreamVolume(s, Math.round(am.getStreamMaxVolume(s) * l / 100f), ui)
            }
            true
        }
        "luminosita" -> {
            // Cambiare la luminosità di sistema richiede un permesso speciale che l'utente concede una volta.
            if (!android.provider.Settings.System.canWrite(this)) {
                snack(UiText.text(this, R.string.ui_49f38d764e), long = true)
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runCatching false
            }
            val cr = contentResolver; val key = android.provider.Settings.System.SCREEN_BRIGHTNESS
            val now = android.provider.Settings.System.getInt(cr, key, 128)
            val v = when (val l = DeviceActions.level(a.arg) ?: return@runCatching false) { DeviceActions.UP -> now + 40; DeviceActions.DOWN -> now - 40; DeviceActions.MUTE -> return@runCatching false; else -> Math.round(l * 2.55f) }.coerceIn(5, 255)
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            android.provider.Settings.System.putInt(cr, key, v); true
        }
        "media" -> {
            val code = when (a.arg.trim().lowercase()) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pausa" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "avanti" -> KeyEvent.KEYCODE_MEDIA_NEXT; "indietro" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> return@runCatching false }
            val am = getSystemService(android.media.AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); true
        }
        "promemoria" -> {
            val (h, m, text) = DeviceActions.reminder(a.arg) ?: return@runCatching false
            val now = java.time.LocalDateTime.now(); var at = now.toLocalDate().atTime(h, m); if (!at.isAfter(now)) at = at.plusDays(1)
            askNotificationsOnce(); db.addTimer(at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), text.take(80)); Sched.all(this, db); true
        }
        "calendario" -> {
            val (at, title) = DeviceActions.event(a.arg) ?: return@runCatching false
            val begin = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            // Non scrive da solo nel calendario: apre l'evento già compilato nell'app del calendario, e l'utente lo salva.
            val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
                .putExtra(android.provider.CalendarContract.Events.TITLE, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) == null) return@runCatching false
            startActivity(i); true
        }
        "pannello" -> {
            val action = when (a.arg.lowercase()) { "wifi", "internet" -> android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY; "volume" -> android.provider.Settings.Panel.ACTION_VOLUME; "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS; "nfc" -> android.provider.Settings.Panel.ACTION_NFC; else -> return@runCatching false }
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }
        else -> false
    }
}.getOrDefault(false)

internal fun MainActivity.reportAction(a: DeviceActions.Action) {
    val ok = runDeviceAction(a)
    snack(if (ok) UiText.text(this, R.string.ui_dab426e957, describeAction(a)) else UiText.text(this, R.string.ui_606d15c3e0, describeAction(a)), long = !ok)
    cue(if (ok) "nod" else "surprised", force = true)
}
