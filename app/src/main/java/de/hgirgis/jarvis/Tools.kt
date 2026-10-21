package de.hgirgis.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Everything JARVIS can do. Reading runs directly, writing only after the user confirms in the app.
 */
class Tools(
    private val vault: Vault,
    private val calendar: Calendar,
    private val google: Google,
    private val confirm: suspend (String) -> Boolean,
    private val sources: (String) -> Unit,
) {

    suspend fun run(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "kalender_lesen" -> {
                val from = LocalDate.parse(args.getString("von_datum"))
                val to = args.optString("bis_datum").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: from
                sources("Kalender")
                ok(calendar.events(from, to))
            }
            "termin_anlegen" -> {
                val title = args.getString("titel")
                val start = LocalDateTime.parse(args.getString("beginn"))
                val end = args.optString("ende").takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it) } ?: start.plusHours(1)
                val ort = args.optString("ort").takeIf { it.isNotBlank() }
                val text = "Termin anlegen: $title, ${start.toLocalDate()} ${start.toLocalTime()} bis ${end.toLocalTime()}" +
                    (ort?.let { ", $it" } ?: "")
                if (confirm(text)) msg(calendar.create(title, start, end, ort)) else msg("Hani hat abgelehnt.")
            }
            "mails_suchen" -> { sources("Gmail"); ok(google.searchMail(args.getString("suchanfrage"))) }
            "mail_lesen" -> { sources("Gmail"); JSONObject().put("ergebnis", google.readMail(args.getString("id"))) }
            "drive_suchen" -> { sources("Drive"); ok(google.searchDrive(args.getString("suchbegriff"))) }
            "drive_datei_lesen" -> { sources("Drive"); JSONObject().put("ergebnis", google.readDrive(args.getString("id"))) }
            "notizen_suchen" -> {
                val hits = vault.search(args.getString("suchbegriff"), 5)
                sources(hits.joinToString(" · ") { it.note.title })
                ok(JSONArray().apply { hits.forEach { put(JSONObject().put("titel", it.note.title).put("auszug", it.snippet)) } })
            }
            "notiz_lesen" -> {
                val n = vault.read(args.getString("titel"))
                if (n == null) msg("Notiz nicht gefunden.")
                else { sources(n.title); JSONObject().put("titel", n.title).put("text", n.text.take(8000)) }
            }
            "notiz_anlegen" -> {
                val title = args.getString("titel")
                if (confirm("Neue Notiz anlegen: $title")) msg(vault.create(title, args.getString("inhalt")))
                else msg("Hani hat abgelehnt.")
            }
            else -> msg("Unbekanntes Werkzeug $name")
        }
    } catch (e: Exception) {
        JSONObject().put("fehler", e.message ?: e.javaClass.simpleName)
    }

    private fun ok(arr: JSONArray) = JSONObject().put("ergebnis", arr).put("anzahl", arr.length())
    private fun msg(text: String) = JSONObject().put("ergebnis", text)

    companion object {
        private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        private fun fn(name: String, desc: String, props: Map<String, JSONObject>, required: List<String>) =
            JSONObject().put("name", name).put("description", desc).put(
                "parameters",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                    .put("required", JSONArray(required))
            )

        /** Function declarations in the Gemini API format. */
        val declarations: JSONArray = JSONArray().apply {
            put(fn("kalender_lesen", "Liest Termine aus Hanis Kalender für einen Zeitraum.",
                mapOf("von_datum" to p("string", "Startdatum YYYY-MM-DD"), "bis_datum" to p("string", "Enddatum YYYY-MM-DD, inklusive")),
                listOf("von_datum")))
            put(fn("termin_anlegen", "Legt einen Termin an. Hani muss in der App bestätigen.",
                mapOf("titel" to p("string", "Titel"), "beginn" to p("string", "Beginn YYYY-MM-DDTHH:MM"),
                    "ende" to p("string", "Ende YYYY-MM-DDTHH:MM, optional"), "ort" to p("string", "Ort, optional")),
                listOf("titel", "beginn")))
            put(fn("mails_suchen", "Sucht in Gmail. Nutzt die Gmail Suchsyntax, z.B. from:anna newer_than:7d subject:Rechnung is:unread.",
                mapOf("suchanfrage" to p("string", "Gmail Suchanfrage")), listOf("suchanfrage")))
            put(fn("mail_lesen", "Liest den Text einer Mail anhand der id aus mails_suchen.",
                mapOf("id" to p("string", "Mail id")), listOf("id")))
            put(fn("drive_suchen", "Sucht Dateien in Google Drive nach Name und Inhalt.",
                mapOf("suchbegriff" to p("string", "Suchbegriff")), listOf("suchbegriff")))
            put(fn("drive_datei_lesen", "Liest den Textinhalt einer Drive Datei anhand der id aus drive_suchen.",
                mapOf("id" to p("string", "Datei id")), listOf("id")))
            put(fn("notizen_suchen", "Durchsucht Hanis Obsidian Notizen (Second Brain) nach Stichworten.",
                mapOf("suchbegriff" to p("string", "Stichworte")), listOf("suchbegriff")))
            put(fn("notiz_lesen", "Liest eine Obsidian Notiz vollständig anhand ihres Titels.",
                mapOf("titel" to p("string", "Titel der Notiz")), listOf("titel")))
            put(fn("notiz_anlegen", "Legt eine neue Obsidian Notiz an. Hani muss in der App bestätigen.",
                mapOf("titel" to p("string", "Titel"), "inhalt" to p("string", "Inhalt in Markdown")),
                listOf("titel", "inhalt")))
        }
    }
}
