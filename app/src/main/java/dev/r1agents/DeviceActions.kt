package dev.r1agents

/**
 * L'agente (Hermes o un agente locale) può agire sul telefono scrivendo direttive nel suo messaggio, una per riga:
 * `[[r1:apri=Calendario]]`, `[[r1:timer=10m]]`, `[[r1:sveglia=07:30]]`, `[[r1:torcia=on]]`, `[[r1:nota=testo]]`,
 * `[[r1:pannello=wifi]]`, `[[r1:volume=50]]`, `[[r1:luminosita=70]]`, `[[r1:media=pausa]]`, `[[r1:promemoria=18:30 chiama Anna]]`,
 * `[[r1:calendario=2026-11-20 15:00 dentista]]` (si apre il calendario per confermare). Il launcher le toglie dal testo mostrato e le offre come pulsanti (o le esegue, se l'utente lo ha scelto).
 * Solo azioni piccole e reversibili: niente installazioni, chiamate, messaggi o pagamenti.
 */
object DeviceActions {
    /** «ask» (pulsanti da toccare, predefinito), «auto» (le esegue da solo), «off». Si imposta da Aspetto. */
    @Volatile var mode = "ask"

    class Action(val kind: String, val arg: String) { val key get() = "$kind=$arg" }
    class Parsed(val text: String, val actions: List<Action>)

    val KINDS = setOf("apri", "timer", "sveglia", "torcia", "nota", "pannello", "volume", "luminosita", "media", "promemoria", "calendario")
    val MEDIA = setOf("play", "pausa", "avanti", "indietro")
    private val complete = Regex("\\[\\[r1:([a-z]+)=([^\\]\\n]{0,120})\\]\\]")
    private val partial = Regex("\\[\\[r1:[^\\]\\n]*$")

    fun parse(text: String): Parsed {
        if (mode == "off" || !text.contains("[[r1:")) return Parsed(text, emptyList())
        val actions = complete.findAll(text).map { Action(it.groupValues[1], it.groupValues[2].trim()) }.filter { it.kind in KINDS && it.arg.isNotEmpty() }.distinctBy { it.key }.take(5).toList()
        val clean = partial.replace(complete.replace(text, ""), "").replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Parsed(clean, actions)
    }

    /** Controlla un'azione proposta da uno strumento: null se valida, altrimenti il motivo (testo per il modello). */
    fun validate(kind: String, arg: String): String? = when {
        kind !in KINDS -> "azione sconosciuta; valide: " + KINDS.sorted().joinToString()
        arg.isBlank() -> "manca l'argomento"
        kind == "timer" && seconds(arg) == null -> "durata non valida (da 5 secondi a 24 ore, es. 10m)"
        kind == "sveglia" && time(arg) == null -> "orario non valido (es. 07:30)"
        kind == "torcia" && arg.lowercase() !in setOf("on", "off") -> "usa on oppure off"
        kind == "pannello" && arg.lowercase() !in setOf("wifi", "internet", "bluetooth", "volume") -> "pannello valido: wifi, bluetooth, volume"
        kind == "torcia" && !hasFlash -> "questo dispositivo non ha il flash"
        (kind == "volume" || kind == "luminosita") && level(arg) == null -> "livello non valido: da 0 a 100, oppure su, giu" + (if (kind == "volume") ", muto" else "")
        kind == "luminosita" && arg.trim().lowercase() == "muto" -> "livello non valido: da 0 a 100, oppure su, giu"
        kind == "media" && arg.trim().lowercase() !in MEDIA -> "comando media valido: play, pausa, avanti, indietro"
        kind == "promemoria" && reminder(arg) == null -> "formato: HH:MM testo (es. 18:30 chiama Anna)"
        kind == "calendario" && event(arg) == null -> "formato: [AAAA-MM-GG] HH:MM titolo (es. 2026-11-20 15:00 dentista)"
        else -> null
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

    /** Livello per volume e luminosità: 0…100, oppure SU (-1), GIU (-2), MUTO (-3). */
    const val UP = -1; const val DOWN = -2; const val MUTE = -3
    fun level(arg: String): Int? = when (val a = arg.trim().lowercase().removeSuffix("%").trim()) {
        "su", "alza", "up", "+" -> UP
        "giu", "giù", "abbassa", "down", "-" -> DOWN
        "muto", "mute", "silenzio" -> MUTE
        else -> a.toIntOrNull()?.takeIf { it in 0..100 }
    }

    /** «18:30 chiama Anna» → (18, 30, "chiama Anna") oppure null. */
    fun reminder(arg: String): Triple<Int, Int, String>? {
        val m = Regex("^(\\d{1,2}(?:[:.]\\d{2})?)\\s+(.{1,80})$").find(arg.trim()) ?: return null
        val (h, min) = time(m.groupValues[1]) ?: return null
        return Triple(h, min, m.groupValues[2].trim())
    }

    /** «2026-11-20 15:00 dentista» o «15:00 dentista» (oggi, o domani se è già passato) → (data e ora, titolo). */
    fun event(arg: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Pair<java.time.LocalDateTime, String>? {
        val m = Regex("^(?:(\\d{4}-\\d{2}-\\d{2})\\s+)?(\\d{1,2}(?:[:.]\\d{2})?)\\s+(.{1,80})$").find(arg.trim()) ?: return null
        val (h, min) = time(m.groupValues[2]) ?: return null
        val day = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() ?: return null }
        var at = (day ?: now.toLocalDate()).atTime(h, min)
        if (day == null && at.isBefore(now)) at = at.plusDays(1)
        return at to m.groupValues[3].trim()
    }

    /** Vero se il dispositivo ha un flash: se no la torcia non si propone né all'utente né all'agente. */
    @Volatile var hasFlash = false

    private const val INTRO_END = "Non esiste nessun'altra azione.]\n"

    /** Toglie dal messaggio dell'utente le istruzioni nascoste che il launcher aggiunge al primo invio (cronologia riletta da Hermes). */
    fun stripIntro(text: String): String {
        var t = text
        if (t.startsWith("[Sei nel launcher R1 Agents")) { val e = t.indexOf(INTRO_END); if (e < 0) return text; t = t.substring(e + INTRO_END.length) }
        for (head in listOf("[Istruzioni per questa chat:", "[Cose da ricordare:\n")) if (t.startsWith(head)) { val e = t.indexOf("]\n"); if (e > 0) t = t.substring(e + 2) }
        return if (t === text) text else t.trimStart('\n')
    }

    /** Il testo da aggiungere al primo messaggio di una chat perché l'agente sappia cosa può fare. */
    fun instruction(): String = "[Sei nel launcher R1 Agents su un Rabbit R1 (Android). Se l'utente lo chiede o ne ha chiaramente bisogno puoi agire sul telefono scrivendo una direttiva per riga nel tuo messaggio: " +
        "[[r1:apri=NomeApp]] apre un'app, [[r1:timer=10m]] avvia un timer (secondi, m o h), [[r1:sveglia=07:30]] imposta una sveglia, " +
        (if (hasFlash) "[[r1:torcia=on]] o [[r1:torcia=off]], " else "") +
        "[[r1:nota=testo]] salva una nota, [[r1:pannello=wifi]] apre il pannello wifi, bluetooth o volume, " +
        "[[r1:volume=50]] o [[r1:luminosita=70]] (0-100, su, giu; volume anche muto), [[r1:media=pausa]] (play, pausa, avanti, indietro), " +
        "[[r1:promemoria=18:30 chiama Anna]] avvisa a quell'ora, [[r1:calendario=2026-11-20 15:00 dentista]] propone un evento da confermare nel calendario. " +
        "Spiega sempre a parole cosa fai. Non esiste nessun'altra azione.]\n"
}
