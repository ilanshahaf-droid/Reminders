package com.ilan.reminders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees as A
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
    val color: Int, val location: String, val repeat: Boolean, val alarm: Boolean, val att: Boolean = false
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
    val rrule: String, val allDay: Boolean, val location: String, val desc: String, val tz: String, val reminders: List<Int>,
    val availability: Int, val access: Int, val guests: List<String>, val organizer: String
)

data class EdState(
    val title: String, val calId: Long, val startDate: LocalDate, val startTime: LocalTime, val endDate: LocalDate, val endTime: LocalTime,
    val allDay: Boolean, val location: String, val desc: String, val reminders: List<Int>, val repeat: String,
    val availability: Int = 0, val access: Int = 0, val guests: List<String> = emptyList()
)

object CalRepo {
    fun hasRead(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun hasWrite(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun ap(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    /** calendars with the user's local name/colour overrides applied (stored in prefs only, the provider is untouched) */
    fun calendars(c: Context): List<Cal> {
        val p = ap(c)
        return calendarsRaw(c).map { k ->
            k.copy(name = p.getString("calName_${k.id}", null)?.takeIf { it.isNotBlank() } ?: k.name,
                color = if (p.contains("calColor_${k.id}")) p.getInt("calColor_${k.id}", k.color) else k.color)
        }
    }
    fun notifOff(c: Context): Set<Long> = ap(c).getStringSet("calNotifOff", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun setNotifOff(c: Context, id: Long, off: Boolean) {
        val n = notifOff(c).let { if (off) it + id else it - id }
        ap(c).edit().putStringSet("calNotifOff", n.map { it.toString() }.toSet()).apply()
    }
    fun setOverride(c: Context, id: Long, name: String?, color: Int?) {
        val e = ap(c).edit()
        if (name != null) { if (name.isBlank()) e.remove("calName_$id") else e.putString("calName_$id", name.trim()) }
        if (color != null) { if (color == 0) e.remove("calColor_$id") else e.putInt("calColor_$id", color) }
        e.apply()
    }
    fun syncOn(c: Context, id: Long): Boolean = runCatching {
        c.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id), arrayOf(CalendarContract.Calendars.SYNC_EVENTS), null, null, null)?.use { it.moveToFirst() && it.getInt(0) == 1 } ?: true
    }.getOrDefault(true)
    fun setSync(c: Context, id: Long, on: Boolean): Boolean = runCatching {
        c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
            android.content.ContentValues().apply { put(CalendarContract.Calendars.SYNC_EVENTS, if (on) 1 else 0) }, null, null) > 0
    }.getOrDefault(false)

    private fun calendarsRaw(c: Context): List<Cal> = runCatching {
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
    fun events(c: Context, fromMs: Long, toMs: Long, calIds: Set<Long>): List<Ev> {
        if (calIds.isEmpty()) return emptyList()
        // an edited occurrence of a series is a separate "exception" event; it still counts as repeating (ORIGINAL_ID / ORIGINAL_SYNC_ID are set)
        val list = runCatching { queryEvents(c, fromMs, toMs, calIds, true) }.getOrNull() ?: runCatching { queryEvents(c, fromMs, toMs, calIds, false) }.getOrDefault(emptyList())
        val p = ap(c)
        if (p.all.keys.none { it.startsWith("calColor_") }) return list
        val raw = calendarsRaw(c).associate { it.id to (it.color or 0xFF000000.toInt()) }
        return list.map { e ->
            val k = "calColor_${e.calId}"
            // only events that use the calendar's own colour follow the override; events with their own colour keep it
            if (p.contains(k) && (e.color or 0xFF000000.toInt()) == raw[e.calId]) e.copy(color = p.getInt(k, e.color)) else e
        }
    }

    private fun queryEvents(c: Context, fromMs: Long, toMs: Long, calIds: Set<Long>, withOrig: Boolean): List<Ev> {
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, fromMs); ContentUris.appendId(b, toMs)
        val out = ArrayList<Ev>()
        val cols = arrayListOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.RRULE,
            CalendarContract.Instances.HAS_ALARM
        )
        if (withOrig) { cols.add(CalendarContract.Events.ORIGINAL_ID); cols.add(CalendarContract.Events.ORIGINAL_SYNC_ID); cols.add(CalendarContract.Events.RDATE) }
        c.contentResolver.query(
            b.build(), cols.toTypedArray(),
            "${CalendarContract.Instances.CALENDAR_ID} IN (${calIds.joinToString(",")})", null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { q ->
            while (q.moveToNext()) {
                var rep = !q.getString(8).isNullOrBlank()
                if (withOrig && !rep) rep = (!q.isNull(10) && q.getLong(10) > 0) || !q.getString(11).isNullOrBlank() || !q.getString(12).isNullOrBlank()
                out.add(Ev(q.getLong(0), q.getLong(1), q.getString(2).orEmpty().ifBlank { "(ללא כותרת)" }, q.getLong(3), q.getLong(4), q.getInt(5) == 1,
                    q.getInt(6), q.getString(7).orEmpty(), rep, q.getInt(9) == 1))
            }
        }
        return out
    }

    fun load(c: Context, id: Long): EvFull? = runCatching {
        var r: EvFull? = null
        c.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(
                CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.DURATION, CalendarContract.Events.RRULE, CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.AVAILABILITY, CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ORGANIZER
            ), null, null, null
        )?.use { q ->
            if (q.moveToFirst()) r = EvFull(
                id, q.getLong(0), q.getString(1).orEmpty(), q.getLong(2), q.getLong(3), q.getString(4).orEmpty(), q.getString(5).orEmpty(),
                q.getInt(6) == 1, q.getString(7).orEmpty(), q.getString(8).orEmpty(), q.getString(9).orEmpty(), reminderList(c, id),
                q.getInt(10), q.getInt(11), guestList(c, id, q.getString(12).orEmpty()), q.getString(12).orEmpty()
            )
        }
        r
    }.getOrNull()

    private fun reminderList(c: Context, id: Long): List<Int> {
        val out = ArrayList<Int>()
        runCatching {
            c.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()), null
            )?.use { q -> while (q.moveToNext()) out.add(q.getInt(0).let { if (it < 0) 10 else it }) }
        }
        return out
    }

    /** guest e-mails (the organizer / the owner's own row is left out) */
    private fun guestList(c: Context, id: Long, organizer: String): List<String> {
        val out = ArrayList<String>()
        runCatching {
            c.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP),
                "${CalendarContract.Attendees.EVENT_ID}=?", arrayOf(id.toString()), null
            )?.use { q ->
                while (q.moveToNext()) {
                    val m = q.getString(0).orEmpty()
                    if (m.isNotBlank() && !m.equals(organizer, true) && q.getInt(1) != CalendarContract.Attendees.RELATIONSHIP_ORGANIZER) out.add(m)
                }
            }
        }
        return out
    }

    private fun ms(d: LocalDate, t: LocalTime, z: ZoneId) = d.atTime(t).atZone(z).toInstant().toEpochMilli()
    private fun utc(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** creates (existing == null) or updates an event; [instanceBegin] is the start of the occurrence the user opened. Returns the event id or -1. */
    var lastError = ""

    private fun stripRule(rule: String, until: Boolean): String =
        rule.split(";").filter { it.isNotBlank() && !it.startsWith("COUNT=") && !(until && it.startsWith("UNTIL=")) }.joinToString(";")

    /** scope for a recurring event: 0 = whole series, 1 = only this occurrence, 2 = this and the following ones */
    fun save(c: Context, existing: EvFull?, instanceBegin: Long, s: EdState, scope: Int = 0): Long = runCatching {
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
        val recurring = existing != null && existing.rrule.isNotEmpty()
        val sc = if (!recurring) 0 else if (scope == 2 && instanceBegin <= existing!!.dtstart) 0 else scope
        var rule = when {
            s.repeat == "CUSTOM" -> existing?.rrule.orEmpty()
            s.repeat.isEmpty() -> ""
            s.repeat.startsWith("FREQ=") -> s.repeat
            else -> "FREQ=${s.repeat}"
        }
        if (sc == 1) rule = ""
        if (sc == 2 && rule.isNotEmpty()) rule = stripRule(rule, false)
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, s.calId)
        v.put(CalendarContract.Events.TITLE, s.title)
        v.put(CalendarContract.Events.EVENT_LOCATION, s.location)
        v.put(CalendarContract.Events.DESCRIPTION, s.desc)
        v.put(CalendarContract.Events.ALL_DAY, if (s.allDay) 1 else 0)
        v.put(CalendarContract.Events.EVENT_TIMEZONE, if (s.allDay) "UTC" else TimeZone.getDefault().id)
        v.put(CalendarContract.Events.HAS_ALARM, if (s.reminders.isNotEmpty()) 1 else 0)
        v.put(CalendarContract.Events.AVAILABILITY, s.availability)
        v.put(CalendarContract.Events.ACCESS_LEVEL, s.access)
        if (s.guests.isNotEmpty()) v.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
        if ((existing == null || sc != 0) && s.guests.isNotEmpty()) v.put(CalendarContract.Events.ORGANIZER, calendars(c).firstOrNull { it.id == s.calId }?.account.orEmpty())
        // an existing series keeps its own first occurrence: shift it by however much the opened occurrence moved
        val series = sc == 0 && existing != null && existing.rrule.isNotEmpty() && rule.isNotEmpty()
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
        if (sc == 2) {
            // end the old series just before this occurrence, then start a new series from here
            val e = existing!!
            val until = if (e.allDay) java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(Instant.ofEpochMilli(instanceBegin).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1))
            else java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(Instant.ofEpochMilli(instanceBegin - 1000).atZone(ZoneOffset.UTC))
            val ov = ContentValues()
            ov.put(CalendarContract.Events.RRULE, stripRule(e.rrule, true) + ";UNTIL=" + until)
            c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), ov, null, null)
            val u = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return -1
            id = ContentUris.parseId(u)
        } else if (sc == 1) {
            v.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
            val exUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, existing!!.id)
            // an exception may not carry DTEND: give it a DURATION instead; if the provider still refuses,
            // cancel this occurrence and create a separate one-off event in its place
            var made: Uri? = null; var err: Throwable? = null
            val secs = maxOf(60L, (newEnd - newBegin) / 1000)
            val v1 = ContentValues(v)
            v1.remove(CalendarContract.Events.DTEND); v1.remove(CalendarContract.Events.RRULE)
            v1.put(CalendarContract.Events.DURATION, if (s.allDay) "P${maxOf(1L, secs / 86400)}D" else "P${secs}S")
            try { made = c.contentResolver.insert(exUri, v1) } catch (t: Throwable) { err = t }
            if (made == null) {
                try {
                    val cv = ContentValues()
                    cv.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
                    cv.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                    c.contentResolver.insert(exUri, cv)
                    val v2 = ContentValues(v)
                    v2.remove(CalendarContract.Events.ORIGINAL_INSTANCE_TIME)
                    made = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v2)
                } catch (t: Throwable) { err = t }
            }
            if (made == null) { lastError = err?.message.orEmpty(); return -1 }
            id = ContentUris.parseId(made)
        } else if (existing == null) {
            val u = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return -1
            id = ContentUris.parseId(u)
        } else {
            c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.id), v, null, null)
            id = existing.id
        }
        c.contentResolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()))
        s.reminders.distinct().forEach { m ->
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, id)
            r.put(CalendarContract.Reminders.MINUTES, m)
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
        }
        runCatching { saveGuests(c, id, if (sc == 0) existing else null, s.guests) }
        id
    }.onFailure { lastError = it.message ?: it.toString() }.getOrDefault(-1L)

    private fun saveGuests(c: Context, id: Long, existing: EvFull?, guests: List<String>) {
        val old = existing?.guests.orEmpty()
        old.filter { o -> guests.none { it.equals(o, true) } }.forEach {
            c.contentResolver.delete(A.CONTENT_URI, "${A.EVENT_ID}=? AND ${A.ATTENDEE_EMAIL}=?", arrayOf(id.toString(), it))
        }
        guests.filter { g -> old.none { it.equals(g, true) } }.forEach { g ->
            val v = ContentValues()
            v.put(A.EVENT_ID, id); v.put(A.ATTENDEE_NAME, g); v.put(A.ATTENDEE_EMAIL, g)
            v.put(A.ATTENDEE_RELATIONSHIP, A.RELATIONSHIP_ATTENDEE); v.put(A.ATTENDEE_TYPE, A.TYPE_REQUIRED)
            v.put(A.ATTENDEE_STATUS, A.ATTENDEE_STATUS_INVITED)
            c.contentResolver.insert(A.CONTENT_URI, v)
        }
    }

    /** scope for a recurring event: 0 = whole series, 1 = only this occurrence, 2 = this and the following ones */
    fun delete(c: Context, id: Long, instanceBegin: Long = 0L, scope: Int = 0): Boolean = runCatching {
        val f = if (scope != 0) load(c, id) else null
        val whole = { c.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0 }
        if (f == null || f.rrule.isEmpty()) return@runCatching whole()
        if (scope == 1) {
            val cv = ContentValues()
            cv.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
            cv.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            c.contentResolver.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, id), cv) != null
        } else {
            if (instanceBegin <= f.dtstart) return@runCatching whole()
            val until = if (f.allDay) java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(Instant.ofEpochMilli(instanceBegin).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1))
            else java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(Instant.ofEpochMilli(instanceBegin - 1000).atZone(ZoneOffset.UTC))
            val ov = ContentValues()
            ov.put(CalendarContract.Events.RRULE, stripRule(f.rrule, true) + ";UNTIL=" + until)
            c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), ov, null, null) > 0
        }
    }.getOrDefault(false)
}
