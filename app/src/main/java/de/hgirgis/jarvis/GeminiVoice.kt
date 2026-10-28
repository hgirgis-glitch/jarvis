package de.hgirgis.jarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Natural sounding voice from the Gemini speech models. Falls back to Android TTS on any error. */
class GeminiVoice(private val key: () -> String, private val voice: () -> String) {

    @Volatile private var track: AudioTrack? = null

    /** Returns null on success, otherwise a short reason. Suspends until playback has finished. */
    suspend fun speak(text: String): String? {
        val apiKey = key().trim()
        if (apiKey.isEmpty()) return "kein Schlüssel"
        var last = "unbekannt"
        for (model in MODELS) {
            val r = runCatching { request(model, apiKey, text) }.getOrElse { return "Netzwerk: ${it.message}" }
            if (r.ok) {
                val pcm = extract(r.body) ?: return "keine Audiodaten"
                play(pcm)
                return null
            }
            last = "${r.code}"
            if (r.code != 404 && r.code != 400) break
        }
        return last
    }

    private suspend fun request(model: String, apiKey: String, text: String): Http.Response {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", "Sprich ruhig, souverän und freundlich, in der Sprache des Textes: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voice().ifBlank { DEFAULT_VOICE })))))
        return Http.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            body.toString(), mapOf("x-goog-api-key" to apiKey))
    }

    private fun extract(json: String): ByteArray? {
        val parts = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data").orEmpty()
            if (data.isNotEmpty()) return Base64.decode(data, Base64.DEFAULT)
        }
        return null
    }

    /** Raw 16 bit mono PCM at 24 kHz, as documented for the Gemini speech models. */
    private suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            val frames = pcm.size / 2
            while (track === t && t.playbackHeadPosition < frames && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(60)
            }
        } finally {
            runCatching { t.stop() }; t.release()
            if (track === t) track = null
        }
    }

    fun stop() { track?.let { runCatching { it.stop() } }; track = null }

    companion object {
        const val RATE = 24_000
        const val DEFAULT_VOICE = "Charon"
        private val MODELS = listOf("gemini-3.8-flash-tts", "gemini-2.5-flash-preview-tts")
    }
}
