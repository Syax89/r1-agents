package dev.r1agents

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Meteo attuale con Open-Meteo (senza chiave): cerca la città, poi la temperatura e il codice WMO. */
object Weather {
    private fun get(u: String): JSONObject {
        val c = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        try { return JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }

    internal fun describe(code: Int, language: String = "it"): String {
        if (language == "en") return when (code) {
            0 -> "clear"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "fog"
            in 51..57 -> "drizzle"; in 61..67 -> "rain"; in 71..77 -> "snow"; in 80..82 -> "showers"; in 85..86 -> "snow showers"; in 95..99 -> "thunderstorm"
            else -> ""
        }
        return when (code) {
        0 -> "sereno"; 1 -> "quasi sereno"; 2 -> "poco nuvoloso"; 3 -> "nuvoloso"; 45, 48 -> "nebbia"
        in 51..57 -> "pioggerella"; in 61..67 -> "pioggia"; in 71..77 -> "neve"; in 80..82 -> "rovesci"; in 85..86 -> "neve a tratti"; in 95..99 -> "temporale"
        else -> ""
        }
    }

    /** "Roma 18° · poco nuvoloso". Lancia se la città non esiste o manca la rete. */
    fun fetch(city: String, language: String = "it"): String {
        val g = get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city.trim(), "UTF-8")}&count=1&language=${if (language == "en") "en" else "it"}").optJSONArray("results")?.optJSONObject(0)
            ?: throw IllegalStateException("città non trovata")
        val w = get("https://api.open-meteo.com/v1/forecast?latitude=${g.getDouble("latitude")}&longitude=${g.getDouble("longitude")}&current=temperature_2m,weather_code&timezone=auto").getJSONObject("current")
        return "${g.getString("name")} ${Math.round(w.getDouble("temperature_2m"))}° · ${describe(w.getInt("weather_code"), language)}".trimEnd(' ', '·')
    }
}
