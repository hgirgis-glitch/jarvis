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
                        is DownloadStatus.DownloadProgress -> status("Download läuft")
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

    suspend fun answer(question: String, notes: List<Vault.Hit>): String {
        if (!ready) prepare()
        if (!ready) return "Mein Sprachmodell ist noch nicht bereit."
        val prompt = buildPrompt(question, notes)
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

    private fun buildPrompt(question: String, notes: List<Vault.Hit>): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMANY).format(Date())
        val sb = StringBuilder()
        sb.append(
            "Du bist JARVIS, der persönliche KI Assistent von Hani. Antworte immer auf Deutsch, " +
                "höflich, knapp und präzise in höchstens vier Sätzen, ohne Aufzählungszeichen und ohne Markdown, " +
                "denn deine Antwort wird vorgelesen. Wenn dir Informationen fehlen, sag das ehrlich. " +
                "Erfinde nichts.\n"
        )
        sb.append("Aktuelle Zeit: ").append(now).append("\n")
        if (notes.isNotEmpty()) {
            sb.append("\nPassende Ausschnitte aus Hanis Obsidian Notizen:\n")
            for (h in notes) sb.append("[").append(h.note.title).append("] ").append(h.snippet).append("\n")
        }
        if (history.isNotEmpty()) {
            sb.append("\nBisheriges Gespräch:\n")
            for ((q, a) in history) sb.append("Hani: ").append(q.take(300)).append("\nJARVIS: ").append(a.take(400)).append("\n")
        }
        sb.append("\nHani: ").append(question).append("\nJARVIS:")
        // The Prompt API accepts less than about 4000 tokens; stay well below.
        return if (sb.length > 9000) sb.substring(sb.length - 9000) else sb.toString()
    }
}
