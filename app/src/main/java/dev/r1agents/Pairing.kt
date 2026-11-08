package dev.r1agents

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Collegamento senza segreti, valido anche prima che esista una configurazione locale. */
data class Pairing(val origin: String, val user: String) {
    fun link(): String = "r1agents://connect?v=1&url=${URLEncoder.encode(origin, "UTF-8")}&user=${URLEncoder.encode(user, "UTF-8")}"
    companion object {
        fun create(address: String, user: String): Pairing {
            val raw = address.trim().let { if (it.contains("://")) it else "http://$it" }
            val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("indirizzo del dashboard non valido") }
            val scheme = uri.scheme?.lowercase()
            require(scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "usa un indirizzo HTTP o HTTPS valido" }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/")) { "il collegamento non deve contenere password, token o percorsi" }
            require(uri.port == -1 || uri.port in 1..65535) { "porta non valida" }
            val origin = URI(scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
            require(httpAllowed(origin)) { "per un server pubblico serve HTTPS" }
            val name = user.trim()
            require(name.length <= 128 && name.none { it.isISOControl() }) { "nome utente non valido" }
            return Pairing(origin, name)
        }
        fun parse(text: String): Pairing {
            val raw = text.trim()
            require(raw.length <= 4096) { "QR troppo lungo" }
            if (!raw.startsWith("r1agents:", ignoreCase = true)) return create(raw, "")
            val uri = URI(raw)
            require(uri.scheme.equals("r1agents", true) && uri.host == "connect" && uri.path.orEmpty() in listOf("", "/") && uri.rawFragment == null && uri.userInfo == null && uri.port == -1) { "QR di collegamento non valido" }
            val params = LinkedHashMap<String, String>()
            for (part in uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                require(key in listOf("v", "url", "user") && key !in params) { "campi QR sconosciuti o duplicati" }
                params[key] = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            require(params["v"] == null || params["v"] == "1") { "versione QR non supportata" }
            return create(params["url"] ?: throw IllegalArgumentException("indirizzo assente nel QR"), params["user"].orEmpty())
        }
    }
}
