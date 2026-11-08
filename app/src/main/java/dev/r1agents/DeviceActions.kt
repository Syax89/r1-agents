package dev.r1agents

/**
 * L'agente (Hermes o un agente locale) può agire sul telefono scrivendo direttive nel suo messaggio, una per riga:
 * `[[r1:apri=Calendario]]`, `[[r1:timer=10m]]`, `[[r1:sveglia=07:30]]`, `[[r1:torcia=on]]`, `[[r1:nota=testo]]`,
 * `[[r1:pannello=wifi]]`. Il launcher le toglie dal testo mostrato e le offre come pulsanti (o le esegue, se l'utente lo ha scelto).
 * Solo azioni piccole e reversibili: niente installazioni, chiamate, messaggi o pagamenti.
 */
object DeviceActions {
    /** «ask» (pulsanti da toccare, predefinito), «auto» (le esegue da solo), «off». Si imposta da Aspetto. */
    @Volatile var mode = "ask"

    class Action(val kind: String, val arg: String) { val key get() = "$kind=$arg" }
    class Parsed(val text: String, val actions: List<Action>)

    val KINDS = setOf("apri", "timer", "sveglia", "torcia", "nota", "pannello")
    private val complete = Regex("\\[\\[r1:([a-z]+)=([^\\]\\n]{0,120})\\]\\]")
    private val partial = Regex("\\[\\[r1:[^\\]\\n]*$")

    fun parse(text: String): Parsed {
        if (mode == "off" || !text.contains("[[r1:")) return Parsed(text, emptyList())
        val actions = complete.findAll(text).map { Action(it.groupValues[1], it.groupValues[2].trim()) }.filter { it.kind in KINDS && it.arg.isNotEmpty() }.distinctBy { it.key }.take(5).toList()
        val clean = partial.replace(complete.replace(text, ""), "").replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Parsed(clean, actions)
    }

    /** «600», «10m», «2h», «90s» → secondi (5 s … 24 h) oppure null. */
    fun seconds(arg: String): Int? {
        val m = Regex("^(\\d{1,5})\\s*([smh]?)$", RegexOption.IGNORE_CASE).find(arg.trim()) ?: return null
        val n = m.groupValues[1].toInt()
        val s = when (m.groupValues[2].lowercase()) { "m" -> n * 60; "h" -> n * 3600; else -> n }
        return s.takeIf { it in 5..86_400 }
    }

    /** «07:30», «7.30», «19» → (ore, minuti) oppure null. */
    fun time(arg: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?$").find(arg.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /** Vero se il dispositivo ha un flash: se no la torcia non si propone né all'utente né all'agente. */
    @Volatile var hasFlash = false

    /** Il testo da aggiungere al primo messaggio di una chat perché l'agente sappia cosa può fare. */
    fun instruction(): String = "[Sei nel launcher R1 Agents su un Rabbit R1 (Android). Se l'utente lo chiede o ne ha chiaramente bisogno puoi agire sul telefono scrivendo una direttiva per riga nel tuo messaggio: " +
        "[[r1:apri=NomeApp]] apre un'app, [[r1:timer=10m]] avvia un timer (secondi, m o h), [[r1:sveglia=07:30]] imposta una sveglia, " +
        (if (hasFlash) "[[r1:torcia=on]] o [[r1:torcia=off]], " else "") +
        "[[r1:nota=testo]] salva una nota, [[r1:pannello=wifi]] apre il pannello wifi, bluetooth o volume. Spiega sempre a parole cosa fai. Non esiste nessun'altra azione.]\n"
}
