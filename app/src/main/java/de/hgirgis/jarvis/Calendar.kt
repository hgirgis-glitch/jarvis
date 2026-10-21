package de.hgirgis.jarvis

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** The phone's own calendar (includes the synced Google calendar). No Google Cloud setup needed. */
class Calendar(private val context: Context) {

    private val zone = ZoneId.systemDefault()
    private val fmt = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm", Locale.GERMANY)

    suspend fun events(from: LocalDate, toInclusive: LocalDate): JSONArray = withContext(Dispatchers.IO) {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = toInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start); ContentUris.appendId(it, end)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = JSONArray()
        context.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.length() < 60) {
                val allDay = c.getInt(3) == 1
                out.put(JSONObject().apply {
                    put("titel", c.getString(0) ?: "(ohne Titel)")
                    put("beginn", if (allDay) "ganztägig " + date(c.getLong(1), true) else time(c.getLong(1)))
                    put("ende", if (allDay) "" else time(c.getLong(2)))
                    c.getString(4)?.takeIf { it.isNotBlank() }?.let { put("ort", it) }
                    put("kalender", c.getString(5) ?: "")
                })
            }
        }
        out
    }

    /** Inserts into the first writable calendar, preferring the primary Google calendar. */
    suspend fun create(title: String, start: LocalDateTime, end: LocalDateTime, location: String?): String =
        withContext(Dispatchers.IO) {
            val calId = writableCalendar() ?: return@withContext "Kein beschreibbarer Kalender gefunden."
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?.let { "Termin angelegt." } ?: "Termin konnte nicht angelegt werden."
        }

    private fun writableCalendar(): Long? {
        val cols = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1"
        var best: Long? = null
        var bestScore = -1
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, sel, null, null)?.use { c ->
            while (c.moveToNext()) {
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                if (score > bestScore) { bestScore = score; best = c.getLong(0) }
            }
        }
        return best
    }

    private fun time(ms: Long) = fmt.format(java.time.Instant.ofEpochMilli(ms).atZone(zone))
    private fun date(ms: Long, utc: Boolean) =
        DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.GERMANY)
            .format(java.time.Instant.ofEpochMilli(ms).atZone(if (utc) ZoneId.of("UTC") else zone))
}
