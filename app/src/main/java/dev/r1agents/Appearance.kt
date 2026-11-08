package dev.r1agents

import android.content.Context

/** Nomi italiani delle scelte di aspetto (il catalogo di r1os e' in inglese); in inglese si usano le etichette del catalogo. */
object Appearance {
    private val forms = mapOf("orb" to "sfera", "blob" to "gelatina", "bot" to "robot", "sprite" to "folletto", "cat" to "gatto", "ghost" to "fantasma", "bunny" to "coniglietto", "bean" to "fagiolo", "cloud" to "nuvola")
    private val colors = mapOf("orange" to "arancione", "amber" to "ambra", "yellow" to "giallo", "lime" to "lime", "green" to "verde", "teal" to "verde acqua", "cyan" to "azzurro", "blue" to "blu", "indigo" to "indaco", "purple" to "viola", "pink" to "rosa", "red" to "rosso")
    private val finishes = mapOf("soft" to "morbido", "matte" to "opaco", "grain" to "grana", "noise" to "rumore", "gradient" to "sfumato", "grainy" to "granuloso", "aurora" to "aurora")
    private val eyes = mapOf("classic" to "classici", "dots" to "puntini", "sparkle" to "scintillanti", "happy" to "felici", "sleepy" to "assonnati", "lashes" to "ciglia", "cyclops" to "un occhio", "visor" to "visiera", "anime" to "lucidi",
        "googly" to "ballerini", "slit" to "da gatto", "stars" to "stelle", "hearts" to "cuori", "smug" to "soddisfatti", "pixel" to "pixel", "swirl" to "a spirale", "button" to "bottoni", "puppy" to "da cucciolo", "calm" to "calmi",
        "rings" to "anelli", "brows" to "sopracciglia", "wink" to "occhiolino", "dizzy" to "storditi", "surprised" to "sorpresi", "love" to "innamorati")
    private val slots = mapOf("head" to "cappello", "face" to "viso", "cheeks" to "guance", "neck" to "collo", "back" to "schiena")
    private val items = mapOf("beanie" to "berretto di lana", "cap" to "berretto", "crown" to "corona", "party" to "cappello da festa", "tophat" to "cilindro", "bow" to "fiocco", "headphones" to "cuffie", "flower" to "fiore", "wizard" to "cappello da mago",
        "cowboy" to "cappello da cowboy", "beret" to "basco", "chef" to "cappello da cuoco", "halo" to "aureola", "horns" to "corna", "sprout" to "germoglio", "propeller" to "berretto con elica", "mortarboard" to "tocco da laureato", "flowercrown" to "corona di fiori",
        "glasses" to "occhiali", "shades" to "occhiali da sole", "monocle" to "monocolo", "aviators" to "occhiali da aviatore", "heartglasses" to "occhiali a cuore", "starglasses" to "occhiali a stella", "nerd" to "occhiali spessi", "goggles" to "occhialoni",
        "eyepatch" to "benda", "mask" to "maschera da eroe", "3d" to "occhiali 3D", "blush" to "rossore", "freckles" to "lentiggini", "whiskers" to "baffi da gatto", "mustache" to "baffi", "bandage" to "cerotto", "sticker" to "adesivo a stella",
        "bowtie" to "papillon", "scarf" to "sciarpa", "bell" to "collare con campanello", "necktie" to "cravatta", "pearls" to "perle", "medal" to "medaglia", "bandana" to "bandana", "lei" to "collana di fiori", "chain" to "catena d'oro",
        "wings" to "ali d'angelo", "batwings" to "ali di pipistrello", "butterfly" to "ali di farfalla", "cape" to "mantello", "jetpack" to "zaino a reazione")

    private fun italian(ctx: Context) = AppLanguage.selected(ctx) != "en"
    fun form(ctx: Context, id: String, fallback: String) = if (italian(ctx)) forms[id] ?: fallback.lowercase() else fallback.lowercase()
    fun color(ctx: Context, id: String) = if (italian(ctx)) colors[id] ?: id else id
    fun finish(ctx: Context, id: String) = if (italian(ctx)) finishes[id] ?: id else id
    fun eyes(ctx: Context, id: String, fallback: String) = if (italian(ctx)) eyes[id] ?: fallback.lowercase() else fallback.lowercase()
    fun slot(ctx: Context, id: String, fallback: String) = if (italian(ctx)) slots[id] ?: fallback.lowercase() else fallback.lowercase()
    fun item(ctx: Context, id: String, fallback: String) = if (italian(ctx)) items[id] ?: fallback.lowercase() else fallback.lowercase()
}
