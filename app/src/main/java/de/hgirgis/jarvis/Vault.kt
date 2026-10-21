package de.hgirgis.jarvis

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read only access to the Obsidian vault through the folder the user picked (Storage Access Framework). */
class Vault(private val context: Context) {

    data class Note(val title: String, val path: String, val text: String, val lower: String)
    data class Hit(val note: Note, val snippet: String, val score: Int)

    private var notes: List<Note> = emptyList()
    val size get() = notes.size

    suspend fun load(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val result = ArrayList<Note>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(treeUri, rootId, "", result)
        notes = result
        result.size
    }

    private fun walk(treeUri: Uri, docId: String, prefix: String, out: MutableList<Note>) {
        if (out.size >= MAX_NOTES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < MAX_NOTES) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (name.startsWith(".")) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, id, "$prefix$name/", out)
                } else if (name.endsWith(".md", ignoreCase = true)) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val text = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { s ->
                            val bytes = s.readNBytes(MAX_BYTES)
                            String(bytes, Charsets.UTF_8)
                        }
                    }.getOrNull() ?: continue
                    val title = name.removeSuffix(".md")
                    out += Note(title, "$prefix$name", text, (title + "\n" + text).lowercase())
                }
            }
        }
    }

    /** Simple keyword search: title matches weigh more than body matches. */
    fun search(query: String, limit: Int = 3): List<Hit> {
        val terms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()
        if (terms.isEmpty()) return emptyList()
        return notes.asSequence().mapNotNull { n ->
            var score = 0
            val titleLower = n.title.lowercase()
            for (t in terms) {
                if (titleLower.contains(t)) score += 10
                score += countOf(n.lower, t).coerceAtMost(5)
            }
            if (score == 0) null else Hit(n, snippet(n, terms), score)
        }.sortedByDescending { it.score }.take(limit).toList()
    }

    private fun countOf(text: String, term: String): Int {
        var i = text.indexOf(term)
        var n = 0
        while (i >= 0 && n < 50) { n++; i = text.indexOf(term, i + term.length) }
        return n
    }

    private fun snippet(n: Note, terms: List<String>): String {
        val body = n.text
        val first = terms.map { n.lower.indexOf(it) - (n.title.length + 1) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (first - 200).coerceAtLeast(0)
        val end = (start + SNIPPET_CHARS).coerceAtMost(body.length)
        return body.substring(start, end).replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private const val MAX_NOTES = 5000
        private const val MAX_BYTES = 40_000
        private const val SNIPPET_CHARS = 700
        private val STOPWORDS = setOf(
            "der", "die", "das", "und", "oder", "ist", "sind", "war", "ein", "eine", "einen", "einem", "einer",
            "was", "wie", "wer", "wo", "wann", "warum", "welche", "welcher", "mir", "mich", "mein", "meine",
            "meinen", "meinem", "meiner", "dir", "dich", "dein", "deine", "ich", "du", "wir", "uns", "unser",
            "für", "mit", "von", "zu", "zum", "zur", "auf", "aus", "bei", "nach", "über", "unter", "steht",
            "stehen", "habe", "hast", "hat", "haben", "kannst", "kann", "bitte", "jarvis", "notiz", "notizen",
            "den", "dem", "des", "nicht", "auch", "noch", "schon", "etwas", "alles", "gibt", "sag", "sage",
        )
    }
}
