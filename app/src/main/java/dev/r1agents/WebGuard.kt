package dev.r1agents

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Regole pure per `web_fetch`: quali indirizzi sono pubblici e come leggere una pagina per intero entro un limite. */
object WebGuard {
    fun publicAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet4Address) {
            val o0 = b[0].toInt() and 0xff; val o1 = b[1].toInt() and 0xff
            if (o0 == 0 || o0 == 10 || o0 == 127) return false
            if (o0 == 100 && o1 in 64..127) return false          // 100.64/10: CGNAT e Tailscale
            if (o0 == 169 && o1 == 254) return false
            if (o0 == 172 && o1 in 16..31) return false
            if (o0 == 192 && o1 == 168) return false
            if (o0 >= 224) return false
            return true
        }
        if (a is Inet6Address) {
            val first = b[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false               // fc00::/7: indirizzi locali unici
            val mapped = (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
            if (mapped) return publicAddress(InetAddress.getByAddress(b.copyOfRange(12, 16)))
            return true
        }
        return false
    }

    /** Legge fino a `max` caratteri (una sola read() restituiva spesso solo il primo pezzo della pagina). */
    fun readUpTo(r: java.io.Reader, max: Int): String {
        val sb = StringBuilder(); val buf = CharArray(8192)
        while (sb.length < max) { val n = r.read(buf, 0, minOf(buf.size, max - sb.length)); if (n < 0) break; sb.append(buf, 0, n) }
        return sb.toString()
    }
}
