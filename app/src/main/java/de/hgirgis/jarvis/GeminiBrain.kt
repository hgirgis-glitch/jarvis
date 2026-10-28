package de.hgirgis.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gemini over the internet with function calling. Needs an API key from Google AI Studio. */
class GeminiBrain(
    private val key: () -> String,
    private val model: () -> String,
    private val tools: Tools,
    private val status: (String) -> Unit = {},
) {

    private val history = ArrayList<JSONObject>()

    fun reset() = history.clear()

    suspend fun answer(question: String): String {
        val apiKey = key().trim()
        if (apiKey.isEmpty()) return "Es ist kein Gemini Schlüssel hinterlegt. Bitte in den Einstellungen eintragen."
        val contents = JSONArray()
        history.forEach { contents.put(it) }
        val userTurn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))
        contents.put(userTurn)

        var search = false  // Google Search runs as its own tool (web_suche), not mixed with the function tools
        var currentModel = model().trim()
        var fallbackUsed = false
        var waited = false
        repeat(MAX_STEPS + 2) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(currentModel, "UTF-8")}:generateContent"
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
                .put("contents", contents)
                .put("tools", toolList(search))
                .put("generationConfig", JSONObject().put("temperature", 0.4))
            status(if (search) "Frage Gemini, mit Google Suche" else "Frage Gemini ($currentModel)")
            val r = Http.post(url, body.toString(), mapOf("x-goog-api-key" to apiKey))
            if (r.code == 400 && search) { search = false; return@repeat }  // model without search plus functions: retry without
            if (r.code in 500..504) {
                // Model overloaded on Google's side: switch to the lighter model, otherwise wait a moment once.
                if (!fallbackUsed && currentModel != FALLBACK_MODEL) { fallbackUsed = true; currentModel = FALLBACK_MODEL; return@repeat }
                if (!waited) { waited = true; status("Gemini ist überlastet, versuche es gleich noch einmal"); kotlinx.coroutines.delay(3000); return@repeat }
            }
            if (r.code == 429) {
                // Free tier is tight: first drop the search tool, then switch to the lighter model.
                if (search) { search = false; return@repeat }
                if (!fallbackUsed && currentModel != FALLBACK_MODEL) { fallbackUsed = true; currentModel = FALLBACK_MODEL; return@repeat }
            }
            if (!r.ok) return explain(r)
            val content = JSONObject(r.body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: return "Gemini hat keine Antwort geliefert."
            val parts = content.optJSONArray("parts") ?: JSONArray()
            // Keep the model turn exactly as returned (it may carry thought signatures).
            contents.put(content)

            val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
                remember(userTurn, text)
                return text.ifEmpty { "Dazu habe ich keine Antwort." }
            }
            val responses = JSONArray()
            for (call in calls) {
                val name = call.getString("name")
                val result = tools.run(name, call.optJSONObject("args") ?: JSONObject())
                responses.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", result)))
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return "Das war mir zu viele Schritte auf einmal. Frag bitte etwas genauer."
    }

    /** Own request with only the Google Search tool, so it works on every model that supports grounding. */
    suspend fun webSearch(question: String): String {
        val apiKey = key().trim()
        if (apiKey.isEmpty()) return "Kein Gemini Schlüssel hinterlegt."
        var m = model().trim()
        var last = "keine Antwort"
        repeat(3) { attempt ->
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(
                    JSONObject().put("text", "Beantworte sachlich, kurz und auf Deutsch mit aktuellen Fakten aus der Suche: $question")))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val r = Http.post("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(m, "UTF-8")}:generateContent",
                body.toString(), mapOf("x-goog-api-key" to apiKey))
            if (r.ok) {
                val parts = JSONObject(r.body).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val text = parts?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }.orEmpty().trim()
                return text.ifEmpty { "Die Suche hat nichts geliefert." }
            }
            last = "Suche Fehler ${r.code}"
            if (r.code in listOf(429, 500, 502, 503, 504, 400, 404) && m != FALLBACK_MODEL) m = FALLBACK_MODEL
            else kotlinx.coroutines.delay(2000L * (attempt + 1))
        }
        return last
    }

    private fun toolList(search: Boolean) = JSONArray().apply {
        put(JSONObject().put("functionDeclarations", Tools.declarations))
        if (search) put(JSONObject().put("google_search", JSONObject()))
    }

    /** Only the plain question and answer go into the history, so it stays small. */
    private fun remember(userTurn: JSONObject, answer: String) {
        history.add(userTurn)
        history.add(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", answer))))
        while (history.size > 12) history.removeAt(0)
    }

    private fun explain(r: Http.Response): String {
        val msg = runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrDefault(r.body.take(200))
        return when (r.code) {
            400, 403 -> "Gemini lehnt den Schlüssel oder die Anfrage ab: $msg"
            500, 502, 503, 504 -> "Gemini ist bei Google gerade überlastet, Sir. Bitte in ein bis zwei Minuten noch einmal fragen."
            404 -> "Das Gemini Modell ${model()} gibt es nicht. Bitte in den Einstellungen ein anderes eintragen."
            429 -> "Das kostenlose Gemini Kontingent ist aufgebraucht. Google schreibt: ${msg.take(220)}"
            else -> "Gemini Fehler ${r.code}: $msg"
        }
    }

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMANY).format(Date())
        return """
            Du bist JARVIS, der persönliche KI Assistent von Hani Girgis. Sprich ihn mit Sir an.
            Jetzt ist $now. Antworte immer in der Sprache, in der Hani fragt (meist Deutsch), knapp, höflich und mit trockenem Humor wie JARVIS aus Iron Man.
            Deine Antworten werden vorgelesen: kein Markdown, keine Aufzählungszeichen, keine Links, höchstens fünf Sätze,
            außer Hani bittet ausdrücklich um Details.
            Nutze die Werkzeuge, um in Kalender, Gmail, Google Drive und den Obsidian Notizen nachzusehen, statt zu raten.
            Bei relativen Angaben wie morgen oder nächste Woche rechne das Datum selbst aus.
            Schreibende Aktionen (Termin oder Notiz anlegen) nur, wenn Hani das ausdrücklich will; die App fragt ihn dann um Bestätigung.
            Du beantwortest alle Fragen von Hani. Bei Wetterfragen rufst du das Werkzeug wetter auf (Standardort Hamburg, wo Hani wohnt).
            Für andere aktuelle Dinge wie Nachrichten, Kurse, Ergebnisse oder Öffnungszeiten rufst du web_suche auf, und für
            Allgemeinwissen nutzt du dein eigenes Wissen. Sage nie, dir fehle ein Werkzeug für Wetter oder Internet. Persönliche Fakten über Hani, seine Termine, Mails, Dateien oder Notizen nennst du nur, wenn ein
            Werkzeug sie geliefert hat. Kennzeichne Unsicheres als unsicher und erfinde keine Zahlen.
            Du kannst keine Mails senden, nichts löschen und nichts bezahlen. Sag das ehrlich, wenn danach gefragt wird.
            Wenn ein Werkzeug einen Fehler meldet, erkläre kurz, was Hani tun muss.
        """.trimIndent()
    }

    companion object {
        private const val MAX_STEPS = 6
        private const val FALLBACK_MODEL = "gemini-flash-lite-latest"
    }
}
