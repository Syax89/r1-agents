package dev.r1agents

/**
 * Un solo client HTTP di base per tutta l'app: ogni uso ne deriva uno con `newBuilder()` (tempi, cookie, DNS propri)
 * ma condivide pool di connessioni e thread. Prima ogni trascrizione e ogni prova della chiave creava i suoi.
 */
object Net {
    val base: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }
}
