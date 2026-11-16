package com.ilan.reminders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/** Reads and writes the phone's calendar provider – Google Calendar (and any other account) syncs through it. */
data class Cal(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean)

data class Ev(
    val id: Long, val calId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean,
    val color: Int, val location: String, val repeat: Boolean, val alarm: Boolean
) {
    /** first and last day (inclusive) in local dates; all-day events are stored in UTC */
    fun days(zone: ZoneId): Pair<LocalDate, LocalDate> {
        val z = if (allDay) ZoneOffset.UTC else zone
        val a = Instant.ofEpochMilli(begin).atZone(z).toLocalDate()
        val last = Instant.ofEpochMilli(if (end > begin) end - 1 else begin).atZone(z).toLocalDate()
        return a to (if (last.isBefore(a)) a else last)
    }
    fun on(d: LocalDate, zone: ZoneId): Boolean { val (a, b) = days(zone); return !d.isBefore(a) && !d.isAfter(b) }
    fun startTime(zone: ZoneId): LocalTime = Instant.ofEpochMilli(begin).atZone(zone).toLocalTime()
    fun endTime(zone: ZoneId): LocalTime = Instant.ofEpochMilli(end).atZone(zone).toLocalTime()
}

/** the full row of an event, needed for editing */
data class EvFull(
    val id: Long, val calId: Long, val title: String, val dtstart: Long, val dtend: Long, val duration: String,
    val rrule: String, val allDay: Boolean, val location: String, val desc: String, val tz: String, val reminder: Int
)

data class EdState(
    val title: String, val calId: Long, val startDate: LocalDate, val startTime: LocalTime, val endDate: LocalDate, val endTime: LocalTime,
    val allDay: Boolean, val location: String, val desc: String, val reminder: Int, val repeat: String
)

object CalRepo {
    fun hasRead(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasWrite(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(c: Context): List<Cal> = runCatching {
        val out = ArrayList<Cal>()
        c.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            ), null, null, CalendarContract.Calendars.ACCOUNT_NAME + " ASC"
        )?.use { q ->
            while (q.moveToNext()) out.add(Cal(q.getLong(0), q.getString(1).orEmpty(), q.getString(2).orEmpty(), q.getInt(3), q.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR))
        }
        out
    }.getOrDefault(emptyList())

    /** events (instances of recurring events included) touching [fromMs, toMs) in the given calendars */
    fun events(c: Context, fromMs: Long, toMs: Long, calIds: Set<Long>): List<Ev> = runCatching {
        if (calIds.isEmpty()) return emptyList()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, fromMs); ContentUris.appendId(b, toMs)
        val out = ArrayList<Ev>()
        c.contentResolver.query(
            b.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.RRULE,
                CalendarContract.Instances.HAS_ALARM
            ),
            "${CalendarContract.Instances.CALENDAR_ID} IN (${calIds.joinToString(",")})", null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { q ->
            while (q.moveToNext()) out.add(
                Ev(q.getLong(0), q.getLong(1), q.getString(2).orEmpty().ifBlank { "(ללא כותרת)" }, q.getLong(3), q.getLong(4), q.getInt(5) == 1,
                    q.getInt(6), q.getString(7).orEmpty(), !q.getString(8).isNullOrBlank(), q.getInt(9) == 1)
            )
        }
        out
    }.getOrDefault(emptyList())

    fun load(c: Context, id: Long): EvFull? = runCatching {
        var r: EvFull? = null
        c.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(
                CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.DURATION, CalendarContract.Events.RRULE, CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_TIMEZONE
            ), null, null, null
        )?.use { q ->
            if (q.moveToFirst()) r = EvFull(
                id, q.getLong(0), q.getString(1).orEmpty(), q.getLong(2), q.getLong(3), q.getString(4).orEmpty(), q.getString(5).orEmpty(),
                q.getInt(6) == 1, q.getString(7).orEmpty(), q.getString(8).orEmpty(), q.getString(9).orEmpty(), firstReminder(c, id)
            )
        }
        r
    }.getOrNull()

    private fun firstReminder(c: Context, id: Long): Int {
        var m = -1
        runCatching {
            c.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()), null
            )?.use { q -> if (q.moveToFirst()) m = q.getInt(0) }
        }
        return m
    }

    private fun ms(d: LocalDate, t: LocalTime, z: ZoneId) = d.atTime(t).atZone(z).toInstant().toEpochMilli()
    private fun utc(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** creates (existing == null) or updates an event; [instanceBegin] is the start of the occurrence the user opened. Returns the event id or -1. */
    fun save(c: Context, existing: EvFull?, instanceBegin: Long, s: EdState): Long = runCatching {
        val zone = ZoneId.systemDefault()
        var newBegin: Long; var newEnd: Long
        if (s.allDay) {
            newBegin = utc(s.startDate)
            newEnd = utc(maxOf(s.endDate, s.startDate).plusDays(1))
        } else {
            newBegin = ms(s.startDate, s.startTime, zone)
            newEnd = ms(s.endDate, s.endTime, zone)
            if (newEnd <= newBegin) newEnd = newBegin + 3600_000L
        }
        val rule = when {
            s.repeat == "CUSTOM" -> existing?.rrule.orEmpty()
            s.repeat.isEmpty() -> ""
            else -> "FREQ=${s.repeat}"
        }
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, s.calId)
        v.put(CalendarContract.Events.TITLE, s.title)
        v.put(CalendarContract.Events.EVENT_LOCATION, s.location)
        v.put(CalendarContract.Events.DESCRIPTION, s.desc)
        v.put(CalendarContract.Events.ALL_DAY, if (s.allDay) 1 else 0)
        v.put(CalendarContract.Events.EVENT_TIMEZONE, if (s.allDay) "UTC" else TimeZone.getDefault().id)
        v.put(CalendarContract.Events.HAS_ALARM, if (s.reminder >= 0) 1 else 0)
        // an existing series keeps its own first occurrence: shift it by however much the opened occurrence moved
        val series = existing != null && existing.rrule.isNotEmpty() && rule.isNotEmpty()
        val start = if (series) existing!!.dtstart + (newBegin - instanceBegin) else newBegin
        v.put(CalendarContract.Events.DTSTART, start)
        if (rule.isNotEmpty()) {
            v.put(CalendarContract.Events.RRULE, rule)
            val secs = (newEnd - newBegin) / 1000
            v.put(CalendarContract.Events.DURATION, if (s.allDay) "P${maxOf(1L, secs / 86400)}D" else "P${maxOf(60L, secs)}S")
            v.putNull(CalendarContract.Events.DTEND)
        } else {
            v.putNull(CalendarContract.Events.RRULE)
            v.putNull(CalendarContract.Events.DURATION)
            v.put(CalendarContract.Events.DTEND, newEnd)
        }
        val id: Long
        if (existing == null) {
            val u = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return -1
            id = ContentUris.parseId(u)
        } else {
            c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.id), v, null, null)
            id = existing.id
        }
        c.contentResolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()))
        if (s.reminder >= 0) {
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, id)
            r.put(CalendarContract.Reminders.MINUTES, s.reminder)
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
        }
        id
    }.getOrDefault(-1L)

    fun delete(c: Context, id: Long): Boolean = runCatching {
        c.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    }.getOrDefault(false)
}
