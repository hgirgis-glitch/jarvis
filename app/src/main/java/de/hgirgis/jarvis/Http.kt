package de.hgirgis.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP client, enough for the Google REST APIs. */
object Http {

    class Response(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        request("GET", url, headers, null)

    suspend fun post(url: String, json: String, headers: Map<String, String> = emptyMap()): Response =
        request("POST", url, headers + ("Content-Type" to "application/json; charset=utf-8"), json)

    private suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                Response(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                c.disconnect()
            }
        }
}
