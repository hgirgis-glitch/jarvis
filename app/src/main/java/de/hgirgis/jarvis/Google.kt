package de.hgirgis.jarvis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Read only access to Gmail and Google Drive with the user's Google account. */
class Google(private val context: Context) {

    private var token: String? = null
    private var tokenTime = 0L

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES.map { Scope(it) })
        .build()

    /** Returns a pending intent when the user still has to approve access, otherwise null. */
    suspend fun authorize(): PendingIntent? {
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        return if (result.hasResolution()) result.pendingIntent else { store(result); null }
    }

    fun onConsent(data: Intent?): Boolean {
        val result = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }
            .getOrNull() ?: return false
        store(result)
        return token != null
    }

    private fun store(result: AuthorizationResult) {
        token = result.accessToken
        tokenTime = System.currentTimeMillis()
    }

    private suspend fun accessToken(): String {
        val fresh = token != null && System.currentTimeMillis() - tokenTime < 45 * 60 * 1000
        if (!fresh) {
            if (authorize() != null) throw IllegalStateException(NOT_CONNECTED)
        }
        return token ?: throw IllegalStateException(NOT_CONNECTED)
    }

    private suspend fun api(url: String): JSONObject {
        val r = Http.get(url, mapOf("Authorization" to "Bearer ${accessToken()}"))
        if (r.code == 401) token = null
        if (!r.ok) throw IllegalStateException("Google meldet Fehler ${r.code}: ${r.body.take(200)}")
        return JSONObject(r.body)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- Gmail ----------

    suspend fun searchMail(query: String, max: Int = 6): JSONArray {
        val list = api("$GMAIL/messages?maxResults=$max&q=${enc(query)}")
        val out = JSONArray()
        val ids = list.optJSONArray("messages") ?: return out
        for (i in 0 until ids.length()) {
            val id = ids.getJSONObject(i).getString("id")
            val m = api("$GMAIL/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
            val h = headers(m)
            out.put(JSONObject().apply {
                put("id", id)
                put("von", h["From"].orEmpty())
                put("betreff", h["Subject"].orEmpty())
                put("datum", h["Date"].orEmpty())
                put("auszug", m.optString("snippet"))
            })
        }
        return out
    }

    suspend fun readMail(id: String): JSONObject {
        val m = api("$GMAIL/messages/${enc(id)}?format=full")
        val h = headers(m)
        val text = plainText(m.optJSONObject("payload")).ifBlank { m.optString("snippet") }
        return JSONObject().apply {
            put("von", h["From"].orEmpty())
            put("an", h["To"].orEmpty())
            put("betreff", h["Subject"].orEmpty())
            put("datum", h["Date"].orEmpty())
            put("text", text.replace(Regex("\\s+"), " ").take(5000))
        }
    }

    private fun headers(m: JSONObject): Map<String, String> {
        val arr = m.optJSONObject("payload")?.optJSONArray("headers") ?: return emptyMap()
        return (0 until arr.length()).associate { arr.getJSONObject(it).let { h -> h.getString("name") to h.getString("value") } }
    }

    private fun plainText(part: JSONObject?): String {
        if (part == null) return ""
        val mime = part.optString("mimeType")
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isNotEmpty() && (mime == "text/plain" || mime == "text/html")) {
            val raw = String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
            return if (mime == "text/html") raw.replace(Regex("<[^>]+>"), " ") else raw
        }
        val parts = part.optJSONArray("parts") ?: return ""
        val texts = (0 until parts.length()).map { parts.getJSONObject(it) }
        // Prefer text/plain over html when both exist.
        texts.firstOrNull { it.optString("mimeType") == "text/plain" }?.let { return plainText(it) }
        return texts.joinToString("\n") { plainText(it) }
    }

    // ---------- Drive ----------

    suspend fun searchDrive(query: String, max: Int = 8): JSONArray {
        val safe = query.replace("\\", "").replace("'", "\\'")
        val q = "(name contains '$safe' or fullText contains '$safe') and trashed = false"
        val r = api("$DRIVE/files?pageSize=$max&orderBy=modifiedTime%20desc&fields=files(id,name,mimeType,modifiedTime)&q=${enc(q)}")
        val out = JSONArray()
        val files = r.optJSONArray("files") ?: return out
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            out.put(JSONObject().apply {
                put("id", f.getString("id"))
                put("name", f.optString("name"))
                put("typ", f.optString("mimeType").substringAfterLast('.').substringAfterLast('/'))
                put("geaendert", f.optString("modifiedTime").take(10))
            })
        }
        return out
    }

    suspend fun readDrive(id: String): JSONObject {
        val meta = api("$DRIVE/files/${enc(id)}?fields=name,mimeType")
        val mime = meta.optString("mimeType")
        val url = when {
            mime == "application/vnd.google-apps.document" -> "$DRIVE/files/${enc(id)}/export?mimeType=text/plain"
            mime == "application/vnd.google-apps.spreadsheet" -> "$DRIVE/files/${enc(id)}/export?mimeType=text/csv"
            mime == "application/vnd.google-apps.presentation" -> "$DRIVE/files/${enc(id)}/export?mimeType=text/plain"
            mime.startsWith("text/") || mime == "application/json" -> "$DRIVE/files/${enc(id)}?alt=media"
            else -> null
        }
        val text = if (url == null) "Dieses Dateiformat ($mime) kann ich nicht als Text lesen."
        else Http.get(url, mapOf("Authorization" to "Bearer ${accessToken()}")).let { if (it.ok) it.body else "Fehler ${it.code}" }
        return JSONObject().apply {
            put("name", meta.optString("name"))
            put("text", text.take(8000))
        }
    }

    companion object {
        const val NOT_CONNECTED = "Google ist nicht verbunden. Bitte in den Einstellungen mit Google verbinden."
        private const val GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val DRIVE = "https://www.googleapis.com/drive/v3"
        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/drive.readonly",
        )
    }
}
