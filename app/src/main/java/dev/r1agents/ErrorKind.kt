package dev.r1agents

/** Riconduce un errore di rete/API a poche cause che l'utente sa capire e correggere. */
enum class ErrorKind {
    KEY, NETWORK, TIMEOUT, RATE, SERVER, CONFIG, OTHER;

    companion object {
        fun of(e: Throwable): ErrorKind {
            if (e is IllegalArgumentException) return CONFIG   // indirizzo senza schema o malformato
            if (e is java.net.SocketTimeoutException) return TIMEOUT
            if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is javax.net.ssl.SSLException) return NETWORK
            val m = e.message.orEmpty()
            val code = Regex("HTTP (\\d{3})").find(m)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                code == 401 || code == 403 -> KEY
                code == 429 -> RATE
                code != null && code >= 500 -> SERVER
                m.contains("timeout", true) || m.contains("timed out", true) -> TIMEOUT
                m.contains("Manca la chiave", true) -> KEY
                else -> OTHER
            }
        }
    }
}
