package dev.r1agents

import android.content.Intent
import android.widget.LinearLayout
import dev.r1os.host.ui.*

/** Esportazione e importazione dei dati (vedi Backup.kt), con o senza password. Spostate da MainActivity. */
/** Esporta con password: il file si legge solo con quella (vedi Backup.encrypt). */
internal fun MainActivity.startProtectedExport() {
    askText(UiText.text(this@startProtectedExport, R.string.ui_77c7dfe5ae), UiText.text(this@startProtectedExport, R.string.ui_26514a5595), secret = true, note = UiText.text(this@startProtectedExport, R.string.ui_2553dd7241)) { pw -> startExport(pw.toCharArray()) }
}

internal fun MainActivity.startExport(password: CharArray? = null) {
    Io.run {
        try {
            val (plain, sum) = Backup.export(db, prefs)
            val json = if (password != null && password.isNotEmpty()) Backup.encrypt(plain, password).also { password.fill(' ') } else plain
            exportJson = json; exportSummary = sum
            val name = "r1agents-${java.text.SimpleDateFormat("yyyy-MM-dd", AppLanguage.locale(this@startExport)).format(java.util.Date())}.json"
            runOnUiThread { @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name), 11) }
        } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@startExport, R.string.ui_9bf3187280, UiText.message(this@startExport, e.message)), long = true) } }
    }
}

internal fun MainActivity.startImport() {
    @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 12)
}

/** Mostra cosa contiene il file e chiede come importarlo: aggiungere ai dati attuali o sostituirli. */
internal fun MainActivity.confirmImport(json: String, sum: Backup.Summary) {
    overlay("import", UiText.text(this@confirmImport, R.string.ui_7c032d1cfb), { closeOverlay("import") }) { col, close ->
        col.addView(intro(UiText.text(this@confirmImport, R.string.ui_242765df7c, UiText.owned(this@confirmImport, sum.toString()))))
        col.addView(button(UiText.text(this@confirmImport, R.string.ui_17b0bf8e19), toolAccent) { close(); doImport(json, false) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        col.addView(hint(UiText.text(this@confirmImport, R.string.ui_688ccaab33)))
        col.addView(button(UiText.text(this@confirmImport, R.string.ui_01885652ec), Theme.BAD, filled = false) { close(); doImport(json, true) }, LinearLayout.LayoutParams(-1, -2))
        col.addView(button(UiText.text(this@confirmImport, R.string.ui_9cb9b85e99), Theme.MUTED, primary = false) { close() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
    }
}

internal fun MainActivity.doImport(json: String, replace: Boolean) {
    Io.run {
        try {
            val sum = Backup.import(this, db, prefs, json, replace)
            runOnUiThread { snack(UiText.text(this@doImport, R.string.ui_edb48d717f, UiText.owned(this@doImport, sum.toString())), long = true); homeIndex = 0; recreate() }
        } catch (e: Exception) { runOnUiThread { snack(UiText.text(this@doImport, R.string.ui_690ea096f1, UiText.message(this@doImport, e.message)), long = true) } }
    }
}


// ---- scossa: scuotendo il dispositivo i buddy si confondono ----

internal fun MainActivity.startShake() {
    if (shakeListener != null) return
    val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
    val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
    var peaks = 0; var first = 0L
    val l = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val g = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()).toFloat() / android.hardware.SensorManager.GRAVITY_EARTH
            if (g < 2.3f) return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - first > 900) { peaks = 0; first = now }
            // Tre scatti forti in meno di un secondo: una scossa vera, non un urto.
            if (++peaks >= 3 && now - lastShake > 3000) { lastShake = now; peaks = 0; onShake() }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }
    shakeListener = l; sm.registerListener(l, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME)
}
internal fun MainActivity.stopShake() { shakeListener?.let { (getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.unregisterListener(it) }; shakeListener = null }
internal fun MainActivity.onShake() = runOnUiThread { root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); characters(root) { it.react("spin") } }

