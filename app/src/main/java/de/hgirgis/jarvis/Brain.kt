package de.hgirgis.jarvis

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gemini Nano on the device via the ML Kit Prompt API. No network, no costs. */
class Brain(private val status: (String) -> Unit) {

    private val model = Generation.getClient()
    private val history = ArrayDeque<Pair<String, String>>()
    var ready = false
        private set

    suspend fun prepare() {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> ready = true
            FeatureStatus.DOWNLOADING -> status("Gemini Nano wird gerade geladen. Bitte kurz warten.")
            FeatureStatus.DOWNLOADABLE -> {
                status("Lade Gemini Nano auf das Gerät")
                model.download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadStarted -> status("Download gestartet")
                        is DownloadStatus.DownloadProgress -> status("Lade Gemini Nano: ${s.totalBytesDownloaded / 1_000_000} MB")
                        is DownloadStatus.DownloadFailed -> status("Download fehlgeschlagen")
                        DownloadStatus.DownloadCompleted -> {
                            ready = true
                            status("Gemini Nano bereit")
                        }
                    }
                }
            }
            else -> status("Gemini Nano ist auf diesem Gerät nicht verfügbar")
        }
        if (ready) runCatching { model.warmup() }
    }

    suspend fun answer(question: String, context: String): String {
        // Never let the small model guess: without real data from the phone there is no answer.
        NanoRouter.unsupported(question)?.let { return it }
        if (context.isBlank()) {
            return "Dazu finde ich in deinem Kalender und deinen Notizen nichts, Sir. Ich erfinde lieber nichts."
        }
        if (!ready) prepare()
        if (!ready) return "Mein Sprachmodell ist noch nicht bereit."
        val prompt = buildPrompt(question, context)
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = 0.3f
                topK = 16
            }
        )
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
            .ifEmpty { "Dazu habe ich leider keine Antwort." }
        history.addLast(question to text)
        while (history.size > 3) history.removeFirst()
        return text
    }

    private fun buildPrompt(question: String, context: String): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMANY).format(Date())
        val sb = StringBuilder()
        sb.append(
            "Du bist JARVIS, der persönliche KI Assistent von Hani. Antworte immer auf Deutsch, " +
                "höflich, knapp und präzise in höchstens vier Sätzen, ohne Aufzählungszeichen und ohne Markdown, " +
                "denn deine Antwort wird vorgelesen. Nutze ausschließlich die gefundenen Informationen unten. " +
                "Steht die Antwort dort nicht, sage genau: Das weiß ich nicht. Erfinde niemals Fakten, Zahlen, " +
                "Termine oder Namen und nutze kein eigenes Weltwissen.\n"
        )
        sb.append("Aktuelle Zeit: ").append(now).append("\n")
        if (context.isNotBlank()) sb.append("\nGefundene Informationen:\n").append(context.take(5000)).append("\n")
        if (history.isNotEmpty()) {
            sb.append("\nBisheriges Gespräch:\n")
            for ((q, a) in history) sb.append("Hani: ").append(q.take(300)).append("\nJARVIS: ").append(a.take(400)).append("\n")
        }
        sb.append("\nHani: ").append(question).append("\nJARVIS:")
        // The Prompt API accepts less than about 4000 tokens; stay well below.
        return if (sb.length > 9000) sb.substring(sb.length - 9000) else sb.toString()
    }
}

/**
 * Gemini Nano cannot call tools reliably, so JARVIS decides by keywords what to look up
 * and hands the result to Nano as context. Read only.
 */
class NanoRouter(private val tools: Tools) {

    suspend fun gather(question: String): String {
        val q = question.lowercase()
        val out = StringBuilder()
        val today = java.time.LocalDate.now()
        if (CAL.any { q.contains(it) }) {
            val (from, to) = when {
                q.contains("übermorgen") -> today.plusDays(2) to today.plusDays(2)
                q.contains("morgen") -> today.plusDays(1) to today.plusDays(1)
                q.contains("heute") -> today to today
                q.contains("nächste woche") -> today.plusDays(7L - today.dayOfWeek.value + 1) to today.plusDays(14L - today.dayOfWeek.value)
                else -> today to today.plusDays(7)
            }
            out.append("Kalender ").append(from).append(" bis ").append(to).append(": ")
                .append(tools.run("kalender_lesen", org.json.JSONObject().put("von_datum", from.toString()).put("bis_datum", to.toString())))
                .append("\n")
        }
        if (WEATHER.any { q.contains(it) }) {
            val place = Regex("(?:in|für|fuer) ([A-ZÄÖÜ][\\p{L}-]+)").find(question)?.groupValues?.get(1) ?: "Hamburg"
            out.append("Wettervorhersage (Open-Meteo, heute ist ").append(today).append("): ")
                .append(tools.run("wetter", org.json.JSONObject().put("ort", place))).append("\n")
        }
        if (MAIL.any { q.contains(it) }) {
            val from = Regex("von ([\\p{L}.@-]+)").find(q)?.groupValues?.get(1)
            val query = if (from != null) "from:$from" else keywords(q).ifBlank { "newer_than:3d" }
            out.append("Gmail Treffer: ").append(tools.run("mails_suchen", org.json.JSONObject().put("suchanfrage", query))).append("\n")
        }
        if (DRIVE.any { q.contains(it) }) {
            out.append("Drive Treffer: ").append(tools.run("drive_suchen", org.json.JSONObject().put("suchbegriff", keywords(q)))).append("\n")
        }
        if (out.isEmpty()) {
            val notes = tools.run("notizen_suchen", org.json.JSONObject().put("suchbegriff", question))
            if (notes.optInt("anzahl", 0) > 0) out.append("Obsidian Notizen: ").append(notes).append("\n")
        }
        return out.toString()
    }

    private fun keywords(q: String) = q.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 4 && it !in IGNORE }.take(3).joinToString(" ")

    companion object {
        private val UNSUPPORTED = listOf("nachrichten", "aktie", "börse", "kurs", "fußball",
            "ergebnis", "verkehr", "stau", "bitcoin", "news")

        /** Topics that need live internet data, which the on-device brain does not have. */
        fun unsupported(question: String): String? {
            val q = question.lowercase()
            return if (UNSUPPORTED.any { q.contains(it) })
                "Dafür brauche ich das Internet, Sir. Bitte in den Einstellungen auf Gemini mit Schlüssel umstellen, dann beantworte ich auch das."
            else null
        }

        private val WEATHER = listOf("wetter", "regen", "temperatur", "sonne", "schnee", "gewitter", "grad ")
        private val CAL = listOf("termin", "kalender", "meeting", "besprechung", "verabredung", "was steht", "was habe ich", "zeit")
        private val MAIL = listOf("mail", "nachricht von", "posteingang", "geschrieben")
        private val DRIVE = listOf("drive", "dokument", "datei", "pdf", "tabelle", "präsentation")
        private val IGNORE = setOf("mail", "mails", "email", "e-mail", "suche", "such", "finde", "zeig", "zeige", "bitte", "meine",
            "meinen", "meinem", "drive", "dokument", "datei", "dateien", "jarvis", "letzte", "letzten", "neue", "neuen", "habe", "gibt")
    }
}
