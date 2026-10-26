package com.ilan.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** One main alarm and one snooze alarm per task. setAlarmClock is exempt from Doze and
 *  battery restrictions and needs no exact-alarm permission. */
object Scheduler {
    private fun pi(c: Context, id: Long, snooze: Boolean): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(if (snooze) "SNOOZE_FIRE" else "FIRE")
            .putExtra("id", id).putExtra("snooze", snooze)
        val code = (id * 2 + if (snooze) 1 else 0).toInt()
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun set(c: Context, id: Long, at: Long, snooze: Boolean) {
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, id, snooze))
    }

    fun cancel(c: Context, id: Long, snooze: Boolean) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, id, snooze))
    }
}

object Reminders {
    private val zone get() = ZoneId.systemDefault()

    /** Next moment after [after] that matches one of the task's weekday/time slots. */
    fun nextOccurrence(t: Task, after: Long): Long? {
        val sch = t.schedule()
        if (sch.isEmpty()) return null
        val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (d in 0..8) {
            val date = start.plusDays(d.toLong())
            val bit = date.dayOfWeek.value % 7 // Sunday(7) -> 0, Monday -> 1 ... Saturday -> 6
            val times = sch[bit] ?: continue
            for (tm in times.map { LocalTime.parse(it) }.sorted()) {
                val ms = date.atTime(tm).atZone(zone).toInstant().toEpochMilli()
                if (ms > after) return ms
            }
        }
        return null
    }

    private fun monthlyAfter(base: Long, now: Long): Long {
        var z = Instant.ofEpochMilli(base).atZone(zone)
        do { z = z.plusMonths(1) } while (z.toInstant().toEpochMilli() <= now)
        return z.toInstant().toEpochMilli()
    }

    fun nextList(t: Task, after: Long): Long? = t.dateList().firstOrNull { it > after }

    /** LIST tasks: point at the next date, or at the last one (already fired) when none is left. */
    private fun settleList(t: Task, now: Long): Task {
        val nx = nextList(t, now)
        return t.copy(triggerAt = nx ?: (t.dateList().lastOrNull() ?: now), fired = nx == null)
    }

    /** Whether the main alarm must be armed for this task. */
    fun needsAlarm(t: Task): Boolean = if (t.repeat == "NONE" || t.repeat == "LIST") !t.fired else true

    /** Moves a recurring task to its next occurrence. */
    fun advance(t: Task, now: Long): Task = when (t.repeat) {
        "LIST" -> settleList(t, now)
        "SCHEDULE" -> nextOccurrence(t, maxOf(now, t.triggerAt))?.let { t.copy(triggerAt = it) } ?: t
        "MONTHLY" -> t.copy(triggerAt = monthlyAfter(t.triggerAt, now))
        else -> t
    }

    private fun scheduleMain(c: Context, t: Task) {
        val now = System.currentTimeMillis()
        Scheduler.set(c, t.id, if (t.triggerAt > now) t.triggerAt else now + 3000, false)
    }

    suspend fun save(c: Context, task: Task) {
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        var t = task.copy(done = false, fired = false, snoozeUntil = 0)
        if (t.repeat == "SCHEDULE") t = t.copy(triggerAt = nextOccurrence(t, now) ?: (now + 60_000))
        if (t.repeat == "MONTHLY" && t.triggerAt <= now) t = advance(t, now)
        if (t.repeat == "LIST") t = settleList(t, now)
        val id = dao.upsert(t.touch())
        val saved = t.copy(id = id)
        Scheduler.cancel(c, id, true)
        if (needsAlarm(saved)) scheduleMain(c, saved)
        ReminderWidget.refreshNow(c)
    }

    suspend fun setDone(c: Context, t: Task, done: Boolean) {
        val dao = AppDb.get(c).dao()
        if (done) {
            dao.upsert(t.copy(done = true, snoozeUntil = 0).touch())
            Scheduler.cancel(c, t.id, false)
            Scheduler.cancel(c, t.id, true)
            Notifier.cancel(c, t.id)
        } else {
            var n = t.copy(done = false, fired = false)
            if (n.repeat == "LIST") n = settleList(n, System.currentTimeMillis())
            n = n.touch()
            dao.upsert(n)
            if (needsAlarm(n)) scheduleMain(c, n)
        }
        ReminderWidget.refreshNow(c)
    }

    /** User deletion: removes locally and tells the cloud copy (the web viewer) it was deleted. */
    suspend fun delete(c: Context, t: Task) {
        deleteLocal(c, t)
        Sync.markDeleted(c, t.id)
    }

    suspend fun deleteLocal(c: Context, t: Task) {
        Scheduler.cancel(c, t.id, false)
        Scheduler.cancel(c, t.id, true)
        Notifier.cancel(c, t.id)
        AppDb.get(c).dao().delete(t.id)
        ReminderWidget.refreshNow(c)
    }

    /** A task changed on the web: store it as is (keeping its edit time) and re-arm its alarms. */
    suspend fun applyRemote(c: Context, remote: Task) {
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        var t = remote
        Scheduler.cancel(c, t.id, false)
        Scheduler.cancel(c, t.id, true)
        if (t.done) {
            dao.upsert(t.copy(snoozeUntil = 0))
            Notifier.cancel(c, t.id)
            return
        }
        if (t.repeat == "SCHEDULE") t = t.copy(triggerAt = nextOccurrence(t, now) ?: (now + 60_000))
        else if (t.repeat == "MONTHLY" && t.triggerAt <= now) t = advance(t, now)
        else if (t.repeat == "LIST") t = settleList(t, now)
        dao.upsert(t)
        if (needsAlarm(t)) scheduleMain(c, t)
        if (t.snoozeUntil > 0) Scheduler.set(c, t.id, if (t.snoozeUntil > now) t.snoozeUntil else now + 3000, true)
    }

    /** After boot, app update, time or timezone change. */
    suspend fun rescheduleAll(c: Context) {
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        for (t0 in dao.active()) {
            var t = t0
            if (t.repeat != "NONE" && t.triggerAt <= now) {
                t = advance(t, now)
                dao.upsert(t)
            }
            if (needsAlarm(t)) scheduleMain(c, t) // missed one-offs fire right away
            if (t.snoozeUntil > 0) {
                Scheduler.set(c, t.id, if (t.snoozeUntil > now) t.snoozeUntil else now + 3000, true)
            }
        }
    }
}
