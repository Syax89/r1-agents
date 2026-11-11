package dev.r1agents

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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
internal fun Context.groupTitle(t: String) = text(18f, 800, Theme.FG).apply { text = t; tag = "group"; setPadding(dpi(4f), dpi(22f), 0, dpi(2f)) }
/** Paragrafo introduttivo in testa a una pagina: stessa dimensione, colore e spazi in tutti i sotto menu. */
internal fun Context.intro(t: String) = text(15f, 500, Theme.MUTED).keepCase().apply { text = t; setPadding(dpi(4f), dpi(2f), dpi(4f), dpi(12f)) }
/** Nota piccola sotto un controllo. */
internal fun Context.hint(t: String) = text(13f, 500, Theme.MUTED).keepCase().apply { text = t; setPadding(dpi(4f), dpi(6f), dpi(4f), dpi(2f)) }
internal fun Context.label(t: String) = text(14f, 700, Theme.MUTED).apply { text = t; tag = "label"; letterSpacing = 0.04f; setPadding(dpi(14f), dpi(18f), 0, dpi(8f)) }
// Titoli di sezione: un filo più marcati e spaziati, allineati al testo delle schede.
internal fun Context.section(t: String) = text(14f, 700, Theme.MUTED).apply { text = t; tag = "section"; letterSpacing = 0.04f; setPadding(dpi(14f), dpi(18f), 0, dpi(8f)) }
/** Il chip di scelta usato in tutte le pagine: stesso testo, stessi spazi (bersaglio di 48 dp), stessi colori selezionato/no. */
internal fun Context.chipView(label: String, size: Float = 15f) = text(size, 700).apply {
    text = label; setPadding(dpi(14f), dpi(13f), dpi(14f), dpi(13f)); minHeight = dpi(48f); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
}
internal fun Context.chipPaint(v: TextView, on: Boolean) {
    v.isSelected = on; v.setTextColor(if (on) Theme.ON_ACCENT else Theme.FG); v.background = roundRect(if (on) Theme.accent else Theme.FOCUS, dp(14f))
}


/**
 * Riga d'impostazione (DESIGN.md: scelte a testo): titolo a sinistra, valore scelto a destra e freccia; il tocco apre il
 * foglio con le opzioni. Le righe vicine stanno nella stessa scheda (vedi `cardify`). Le scelte visive (colori, font,
 * palette) restano griglie di anteprime.
 */
internal fun Context.settingRow(title: String, value: String, onClick: () -> Unit): View = LinearLayout(this).apply {
    tag = "setting"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(52f)
    isClickable = true; isFocusable = true; contentDescription = "$title: $value"
    setPadding(dpi(4f), dpi(6f), dpi(2f), dpi(6f))
    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Theme.withAlpha(Theme.FG, 0.10f)), null, roundRect(Theme.FG, dp(12f)))
    addView(text(17f, 600).apply { text = title; maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(text(16f, 600, Theme.accent).keepCaseIf(value).apply { text = value; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dpi(170f); setPadding(dpi(10f), 0, dpi(4f), 0) })
    addView(IconView(this@settingRow, "chevron", Theme.SUBTLE, Theme.ON_ACCENT), LinearLayout.LayoutParams(dpi(18f), dpi(18f)))
    setOnClickListener { onClick() }
}

/** Riga sì/no con interruttore: tutta la riga lo cambia. */
internal fun Context.settingSwitch(title: String, on: Boolean, onChange: (Boolean) -> Unit): View = LinearLayout(this).apply {
    tag = "setting"; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(52f)
    setPadding(dpi(4f), dpi(6f), dpi(2f), dpi(6f))
    addView(text(17f, 600).apply { text = title; maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
    val sw = SwitchView(this@settingSwitch, on, Theme.colorOf("green"), onChange)
    addView(sw)
    isClickable = true; isFocusable = true; contentDescription = title
    setOnClickListener { sw.performClick() }
}

/** I nomi propri (es. "groq whisper", "English") restano come sono; il resto segue la minuscola dell'app. */
private fun TextView.keepCaseIf(v: String): TextView = if (v.any { it.isUpperCase() }) keepCase() else this
