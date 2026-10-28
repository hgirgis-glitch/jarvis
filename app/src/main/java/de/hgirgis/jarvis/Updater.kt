package de.hgirgis.jarvis

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks a small version.json and installs a newer APK with one tap. */
class Updater(private val context: Context) {

    data class Update(val versionCode: Long, val versionName: String, val apkUrl: String)

    private val installed: Long
        get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    suspend fun check(): Update? {
        val r = runCatching { Http.get("$BASE/version.json?t=${System.currentTimeMillis()}") }.getOrNull() ?: return null
        if (!r.ok) return null
        val j = runCatching { JSONObject(r.body) }.getOrNull() ?: return null
        val u = Update(j.optLong("versionCode"), j.optString("versionName"), j.optString("apk").ifBlank { "$BASE/jarvis.apk" })
        return if (u.versionCode > installed) u else null
    }

    /** Downloads the APK and opens the system installer. Returns an error text or null. */
    suspend fun install(u: Update, progress: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "jarvis.apk")
        runCatching {
            val c = URL(u.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
            c.disconnect()
        }.onFailure { return@withContext "Download fehlgeschlagen: ${it.message}" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        null
    }

    companion object {
        const val BASE = "https://hgirgis-glitch.github.io/jarvis"
    }
}
