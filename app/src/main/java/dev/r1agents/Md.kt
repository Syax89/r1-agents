package dev.r1agents

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Markdown leggero per le risposte: titoli (#), elenchi (- * 1.), **grassetto**, *corsivo*, `codice` e blocchi ```codice```.
 * Un segno non chiuso (durante lo streaming) resta com'è finché non arriva la chiusura.
 */
object Md {
    private val heading = Regex("^(#{1,3})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")
    private val link = Regex("^\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")

    fun render(src: String, codeBg: Int): CharSequence {
        val out = SpannableStringBuilder()
        var inCode = false
        val lines = src.split('\n')
        for ((i, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```")) { inCode = !inCode; continue }
            val start = out.length
            when {
                inCode -> {
                    out.append(line)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.88f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                heading.matches(line) -> {
                    val m = heading.find(line)!!
                    inline(out, m.groupValues[2])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(if (m.groupValues[1].length == 1) 1.2f else 1.1f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bullet.matches(line) -> { val m = bullet.find(line)!!; out.append(m.groupValues[1]).append("\u2022 "); inline(out, m.groupValues[2]) }
                numbered.matches(line) -> { val m = numbered.find(line)!!; out.append(m.groupValues[1]).append(m.groupValues[2]).append(". "); inline(out, m.groupValues[3]) }
                quote.matches(line) -> { val m = quote.find(line)!!; out.append("\u258e "); inline(out, m.groupValues[1]); out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                else -> inline(out, line)
            }
            if (i < lines.lastIndex) out.append('\n')
        }
        return out
    }

    /** Memoria per lo streaming: la parte già completa (fino all'ultima riga vuota fuori dai blocchi di codice) non si rifà. */
    class Cache { internal var src = ""; internal var out = SpannableStringBuilder() }

    /** Come [render], ma durante lo streaming rifà solo la coda ancora in arrivo: il costo smette di crescere con la lunghezza. */
    fun render(src: String, codeBg: Int, cache: Cache): CharSequence {
        val lines = src.split('\n')
        var fences = 0; var cut = -1
        for ((i, l) in lines.withIndex()) {
            if (l.trimStart().startsWith("```")) fences++
            if (l.isEmpty() && fences % 2 == 0 && i < lines.lastIndex) cut = i
        }
        if (cut < 1) return render(src, codeBg)
        val head = lines.subList(0, cut + 1).joinToString("\n")
        if (cache.src != head) {
            if (cache.src.isNotEmpty() && head.startsWith(cache.src + "\n")) cache.out.append('\n').append(render(head.substring(cache.src.length + 1), codeBg))
            else cache.out = SpannableStringBuilder(render(head, codeBg))
            cache.src = head
        }
        return SpannableStringBuilder(cache.out).append('\n').append(render(lines.subList(cut + 1, lines.size).joinToString("\n"), codeBg))
    }

    /** **grassetto**, *corsivo*, _corsivo_, `codice` dentro una riga. */
    private fun inline(out: SpannableStringBuilder, s: String, codeBg: Int = 0x33808080) {
        var i = 0
        fun span(start: Int, what: Any) = out.setSpan(what, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        while (i < s.length) {
            when {
                s.startsWith("**", i) && s.indexOf("**", i + 2) > i + 2 -> {
                    val e = s.indexOf("**", i + 2); val st = out.length
                    inline(out, s.substring(i + 2, e)); span(st, StyleSpan(Typeface.BOLD)); i = e + 2
                }
                s[i] == '`' && s.indexOf('`', i + 1) > i + 1 -> {
                    val e = s.indexOf('`', i + 1); val st = out.length
                    out.append(s, i + 1, e); span(st, TypefaceSpan("monospace")); span(st, BackgroundColorSpan(codeBg)); i = e + 1
                }
                s[i] == '[' && link.find(s.substring(i)) != null -> {
                    val m = link.find(s.substring(i))!!; val st = out.length
                    out.append(m.groupValues[1]); span(st, android.text.style.UnderlineSpan()); i += m.value.length
                }
                (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf(s[i], i + 1) > i + 1 &&
                    (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val e = s.indexOf(s[i], i + 1); val st = out.length
                    inline(out, s.substring(i + 1, e)); span(st, StyleSpan(Typeface.ITALIC)); i = e + 1
                }
                else -> { out.append(s[i]); i++ }
            }
        }
    }
}
