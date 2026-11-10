package dev.r1agents

import android.content.Context
import android.view.Gravity
import android.widget.TextView
import dev.r1os.host.ui.Theme
import dev.r1os.host.ui.dp
import dev.r1os.host.ui.dpi
import dev.r1os.host.ui.keepCase
import dev.r1os.host.ui.text

/*
 * Componenti comuni delle pagine (fase 5 della review: MainActivity si snellisce spostando il codice cosi' com'e').
 * Titoli di gruppo, etichette, paragrafi introduttivi, note e chip: un solo stile in tutti i sotto menu.
 */
internal fun Context.groupTitle(t: String) = text(18f, 800, Theme.FG).apply { text = t; setPadding(dpi(4f), dpi(22f), 0, dpi(2f)) }
/** Paragrafo introduttivo in testa a una pagina: stessa dimensione, colore e spazi in tutti i sotto menu. */
internal fun Context.intro(t: String) = text(15f, 500, Theme.MUTED).keepCase().apply { text = t; setPadding(dpi(4f), dpi(2f), dpi(4f), dpi(12f)) }
/** Nota piccola sotto un controllo. */
internal fun Context.hint(t: String) = text(13f, 500, Theme.MUTED).keepCase().apply { text = t; setPadding(dpi(4f), dpi(6f), dpi(4f), dpi(2f)) }
internal fun Context.label(t: String) = text(15f, 600, Theme.MUTED).apply { text = t; tag = "label"; setPadding(dpi(10f), dpi(16f), 0, dpi(6f)) }
internal fun Context.section(t: String) = text(15f, 600, Theme.MUTED).apply { text = t; setPadding(dpi(10f), dpi(16f), 0, dpi(6f)) }
/** Il chip di scelta usato in tutte le pagine: stesso testo, stessi spazi (bersaglio di 48 dp), stessi colori selezionato/no. */
internal fun Context.chipView(label: String, size: Float = 15f) = text(size, 700).apply {
    text = label; setPadding(dpi(14f), dpi(13f), dpi(14f), dpi(13f)); minHeight = dpi(48f); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
}
internal fun Context.chipPaint(v: TextView, on: Boolean) {
    v.isSelected = on; v.setTextColor(if (on) Theme.ON_ACCENT else Theme.FG); v.background = roundRect(if (on) Theme.accent else Theme.FOCUS, dp(14f))
}

