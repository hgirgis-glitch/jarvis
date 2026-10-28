package de.hgirgis.jarvis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Records one spoken sentence with the microphone, without any system UI.
 * Stops after a short pause once speech was heard. Returns a WAV file or null if nothing was said.
 */
class AudioCapture {

    @Volatile private var stopRequested = false

    fun stop() { stopRequested = true }

    @SuppressLint("MissingPermission")
    suspend fun record(level: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        stopRequested = false
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 20) // 50 ms
        var heard = false
        var silentMs = 0
        var totalMs = 0
        var noise = 300.0
        try {
            rec.startRecording()
            while (coroutineContext.isActive && !stopRequested) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) { val v = buf[i].toInt(); sum += v * v; bb.putShort(buf[i]) }
                pcm.write(bb.array())
                val rms = sqrt(sum / n)
                level((rms / 3000.0).coerceIn(0.0, 1.0).toFloat())
                val ms = n * 1000 / RATE
                totalMs += ms
                // Adaptive threshold: speech is clearly above the background noise.
                if (!heard && totalMs < 400) noise = noise * 0.8 + rms * 0.2
                val speaking = rms > maxOf(noise * 2.5, 500.0)
                if (speaking) { heard = true; silentMs = 0 } else silentMs += ms
                if (heard && silentMs > 1300) break
                if (!heard && totalMs > 7000) break
                if (totalMs > 20000) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!heard) null else wav(pcm.toByteArray())
    }

    private fun wav(data: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.size)
        return h.array() + data
    }

    companion object { const val RATE = 16_000 }
}
