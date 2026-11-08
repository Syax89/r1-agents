package dev.r1agents

/** Parti consecutive per il TTS locale, senza troncare la risposta né dividere un emoji. */
internal object VoiceSpeech {
    fun chunks(markdown: String, limit: Int): List<String> {
        require(limit >= 2)
        val plain = markdown.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*#`_]"), "").trim()
        val result = ArrayList<String>()
        var start = 0
        while (start < plain.length) {
            var end = minOf(start + limit, plain.length)
            if (end < plain.length) {
                // Preferisce una frase o uno spazio nella seconda metà del blocco.
                val floor = start + limit / 2
                var split = end
                while (split > floor && !plain[split - 1].isWhitespace()) split--
                if (split > floor) end = split
                else if (Character.isHighSurrogate(plain[end - 1]) && Character.isLowSurrogate(plain[end])) end--
            }
            plain.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
        }
        return result
    }
}
