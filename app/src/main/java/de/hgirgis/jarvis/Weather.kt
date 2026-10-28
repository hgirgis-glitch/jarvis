package de.hgirgis.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Weather forecast from Open-Meteo. Free, no key, no tracking. */
object Weather {

    suspend fun forecast(place: String): JSONObject {
        val name = place.ifBlank { "Hamburg" }
        val geo = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=1&language=de")
        if (!geo.ok) return JSONObject().put("fehler", "Ortssuche fehlgeschlagen (${geo.code})")
        val hit = JSONObject(geo.body).optJSONArray("results")?.optJSONObject(0)
            ?: return JSONObject().put("fehler", "Ort $name nicht gefunden")
        val lat = hit.getDouble("latitude")
        val lon = hit.getDouble("longitude")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max" +
            "&timezone=auto&forecast_days=7"
        val r = Http.get(url)
        if (!r.ok) return JSONObject().put("fehler", "Wetterdienst Fehler ${r.code}")
        val json = JSONObject(r.body)
        val daily = json.getJSONObject("daily")
        val days = JSONArray()
        val dates = daily.getJSONArray("time")
        for (i in 0 until dates.length()) {
            days.put(JSONObject().apply {
                put("datum", dates.getString(i))
                put("wetter", describe(daily.getJSONArray("weather_code").optInt(i)))
                put("max_c", daily.getJSONArray("temperature_2m_max").optDouble(i))
                put("min_c", daily.getJSONArray("temperature_2m_min").optDouble(i))
                put("regenwahrscheinlichkeit_prozent", daily.getJSONArray("precipitation_probability_max").optInt(i))
                put("regen_mm", daily.getJSONArray("precipitation_sum").optDouble(i))
                put("wind_max_kmh", daily.getJSONArray("wind_speed_10m_max").optDouble(i))
            })
        }
        val cur = json.optJSONObject("current")
        return JSONObject().apply {
            put("ort", hit.optString("name") + (hit.optString("admin1").takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""))
            if (cur != null) put("jetzt", JSONObject().apply {
                put("wetter", describe(cur.optInt("weather_code")))
                put("temperatur_c", cur.optDouble("temperature_2m"))
                put("gefuehlt_c", cur.optDouble("apparent_temperature"))
                put("wind_kmh", cur.optDouble("wind_speed_10m"))
            })
            put("tage", days)
        }
    }

    /** WMO weather interpretation codes. */
    private fun describe(code: Int) = when (code) {
        0 -> "klar"
        1 -> "überwiegend klar"
        2 -> "teils bewölkt"
        3 -> "bedeckt"
        45, 48 -> "Nebel"
        51, 53, 55 -> "Nieselregen"
        56, 57 -> "gefrierender Nieselregen"
        61 -> "leichter Regen"
        63 -> "Regen"
        65 -> "starker Regen"
        66, 67 -> "gefrierender Regen"
        71 -> "leichter Schneefall"
        73 -> "Schneefall"
        75 -> "starker Schneefall"
        77 -> "Schneegriesel"
        80 -> "leichte Regenschauer"
        81 -> "Regenschauer"
        82 -> "heftige Regenschauer"
        85, 86 -> "Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "unbekannt"
    }
}
