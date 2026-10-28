package de.hgirgis.jarvis

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log, so a bug report can be copied with one tap instead of sending screenshots. */
object EventLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    @Synchronized
    fun add(kind: String, text: String) {
        if (kind == "state" && text == "idle") return
        lines.addLast("${fmt.format(Date())} $kind: ${text.take(400)}")
        while (lines.size > 150) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
