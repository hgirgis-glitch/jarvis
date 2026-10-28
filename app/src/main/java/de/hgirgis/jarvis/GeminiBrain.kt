package de.hgirgis.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gemini over the internet with function calling. Needs an API key from Google AI Studio. */
class GeminiBrain(private val key: () -> String, private val model: () -> String, private val tools: Tools) {

    private val history = ArrayList<JSONObject>()

    fun reset() = history.clear()

    suspend fun answer(question: String): String {
        val apiKey = key().trim()
        if (apiKey.isEmpty()) return "Es ist kein Gemini Schlüssel hinterlegt. Bitte in den Einstellungen eintragen."
        val contents = JSONArray()
        history.forEach { contents.put(it) }
        val userTurn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))
        contents.put(userTurn)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model().trim(), "UTF-8")}:generateContent"
        var search = true
        repeat(MAX_STEPS) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
                .put("contents", contents)
                .put("tools", toolList(search))
                .put("generationConfig", JSONObject().put("temperature", 0.4))
            val r = Http.post(url, body.toString(), mapOf("x-goog-api-key" to apiKey))
            if (r.code == 400 && search) { search = false; return@repeat }  // model without search plus functions: retry without
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
            404 -> "Das Gemini Modell ${model()} gibt es nicht. Bitte in den Einstellungen ein anderes eintragen."
            429 -> "Das kostenlose Gemini Kontingent ist gerade aufgebraucht. Bitte später noch einmal."
            else -> "Gemini Fehler ${r.code}: $msg"
        }
    }

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMANY).format(Date())
        return """
            Du bist JARVIS, der persönliche KI Assistent von Hani Girgis. Sprich ihn mit Sir an.
            Jetzt ist $now. Antworte immer auf Deutsch, knapp, höflich und mit trockenem Humor wie JARVIS aus Iron Man.
            Deine Antworten werden vorgelesen: kein Markdown, keine Aufzählungszeichen, keine Links, höchstens fünf Sätze,
            außer Hani bittet ausdrücklich um Details.
            Nutze die Werkzeuge, um in Kalender, Gmail, Google Drive und den Obsidian Notizen nachzusehen, statt zu raten.
            Bei relativen Angaben wie morgen oder nächste Woche rechne das Datum selbst aus.
            Schreibende Aktionen (Termin oder Notiz anlegen) nur, wenn Hani das ausdrücklich will; die App fragt ihn dann um Bestätigung.
            Du beantwortest alle Fragen von Hani. Für aktuelle Dinge wie Wetter, Nachrichten, Kurse, Ergebnisse oder Öffnungszeiten
            nutzt du die Google Suche, und für Allgemeinwissen dein eigenes Wissen. Frage bei Wetter nach dem Ort, wenn er fehlt;
            Hani wohnt in Hamburg. Persönliche Fakten über Hani, seine Termine, Mails, Dateien oder Notizen nennst du nur, wenn ein
            Werkzeug sie geliefert hat. Kennzeichne Unsicheres als unsicher und erfinde keine Zahlen.
            Du kannst keine Mails senden, nichts löschen und nichts bezahlen. Sag das ehrlich, wenn danach gefragt wird.
            Wenn ein Werkzeug einen Fehler meldet, erkläre kurz, was Hani tun muss.
        """.trimIndent()
    }

    companion object {
        private const val MAX_STEPS = 6
    }
}
