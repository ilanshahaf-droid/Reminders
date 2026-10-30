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

    // "repeat alert until handled": a separate alarm that re-shows the alert every N minutes
    private fun nagPi(c: Context, id: Long, count: Int): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction("NAG").putExtra("id", id).putExtra("nag", count)
        return PendingIntent.getBroadcast(c, (id xor 0x5A5A5A5AL).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun nagMinutes(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("nagMin", 0)

    fun setNag(c: Context, id: Long, count: Int) {
        val m = nagMinutes(c)
        if (m <= 0 || count > 12) { cancelNag(c, id); return }
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + m * 60_000L, show), nagPi(c, id, count))
    }

    fun cancelNag(c: Context, id: Long) {
        c.getSystemService(AlarmManager::class.java).cancel(nagPi(c, id, 0))
    }
}

object Reminders {
    private val zone get() = ZoneId.systemDefault()

    /** Next moment after [after] that matches one of the task's weekday/time slots. */
    /** A weekly time that repeats every N weeks counts only on/after its start date and in every N-th week from it. */
    private fun slotActive(sl: Slot, epochDay: Long): Boolean {
        if (sl.every <= 1 || sl.anchorDay <= 0) return true
        if (epochDay < sl.anchorDay) return false
        val w = Math.floorDiv(epochDay + 4, 7L) - Math.floorDiv(sl.anchorDay + 4, 7L)   // epoch day 3 was a Sunday
        return Math.floorMod(w, sl.every.toLong()) == 0L
    }

    fun nextOccurrence(t: Task, after: Long): Long? {
        val sch = t.schedule()
        if (sch.isEmpty()) return null
        val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        val maxN = sch.values.flatten().maxOf { parseSlot(it).every }
        for (d in 0..(7 * maxN + 8)) {
            val date = start.plusDays(d.toLong())
            val bit = date.dayOfWeek.value % 7 // Sunday(7) -> 0, Monday -> 1 ... Saturday -> 6
            val toks = sch[bit] ?: continue
            val epoch = date.toEpochDay()
            val times = toks.map { parseSlot(it) }.filter { slotActive(it, epoch) }.map { it.time }
            for (tm in times.map { LocalTime.parse(it) }.sorted()) {
                val ms = date.atTime(tm).atZone(zone).toInstant().toEpochMilli()
                if (ms > after) return ms
            }
        }
        return null
    }

    private fun stepAfter(base: Long, now: Long, unit: java.time.temporal.ChronoUnit, n: Int): Long {
        var z = Instant.ofEpochMilli(base).atZone(zone)
        do { z = z.plus(n.coerceAtLeast(1).toLong(), unit) } while (z.toInstant().toEpochMilli() <= now)
        return z.toInstant().toEpochMilli()
    }

    /** First time a weekly "every N weeks" task is saved: the cycle starts in the week of its first occurrence. */
    private fun withAnchor(t: Task, now: Long): Task {
        if (t.repeat != "SCHEDULE" || t.every <= 1 || t.anchor > 0) return t
        val first = nextOccurrence(t.copy(every = 1), now) ?: return t
        return t.copy(anchor = first)
    }

    fun nextList(t: Task, after: Long): Long? = t.dateList().firstOrNull { it > after }

    /** LIST tasks: point at the next date, or at the last one (already fired) when none is left. */
    private fun settleList(t: Task, now: Long): Task {
        val nx = nextList(t, now)
        return t.copy(triggerAt = nx ?: (t.dateList().lastOrNull() ?: now), fired = nx == null)
    }

    /** Whether the main alarm must be armed for this task. */
    fun needsAlarm(t: Task): Boolean = if (t.alertMode == "NONE") false else if (t.repeat == "NONE" || t.repeat == "LIST") !t.fired else true

    /** Moves a recurring task to its next occurrence. */
    fun advance(t: Task, now: Long): Task = when (t.repeat) {
        "LIST" -> settleList(t, now)
        "SCHEDULE" -> nextOccurrence(t, maxOf(now, t.triggerAt))?.let { t.copy(triggerAt = it) } ?: t
        "MONTHLY" -> t.copy(triggerAt = stepAfter(t.triggerAt, now, java.time.temporal.ChronoUnit.MONTHS, t.every))
        "DAILY" -> t.copy(triggerAt = stepAfter(t.triggerAt, now, java.time.temporal.ChronoUnit.DAYS, t.every))
        else -> t
    }

    private fun scheduleMain(c: Context, t: Task) {
        val now = System.currentTimeMillis()
        Scheduler.set(c, t.id, if (t.triggerAt > now) t.triggerAt else now + 3000, false)
    }

    suspend fun save(c: Context, task: Task) {
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        var t = withAnchor(task.copy(done = false, fired = false, snoozeUntil = 0), now)
        if (t.repeat == "SCHEDULE") t = t.copy(triggerAt = nextOccurrence(t, now) ?: (now + 60_000))
        if ((t.repeat == "MONTHLY" || t.repeat == "DAILY") && t.triggerAt <= now) t = advance(t, now)
        if (t.repeat == "LIST") t = settleList(t, now)
        val id = dao.upsert(t.touch())
        val saved = t.copy(id = id)
        Scheduler.cancel(c, id, true)
        if (needsAlarm(saved)) scheduleMain(c, saved) else Scheduler.cancel(c, id, false)
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

    /** User deletion: moves to the recycle bin (kept 30 days) and tells the cloud copy it was deleted. */
    suspend fun delete(c: Context, t: Task) {
        Scheduler.cancel(c, t.id, false)
        Scheduler.cancel(c, t.id, true)
        Notifier.cancel(c, t.id)
        AppDb.get(c).dao().upsert(t.copy(deletedAt = System.currentTimeMillis()).touch())
        Sync.markDeleted(c, t.id)
        ReminderWidget.refreshNow(c)
        WearBridge.pushList(c)
    }

    suspend fun restore(c: Context, t: Task) {
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        Sync.unmarkDeleted(c, t.id)
        var n = t.copy(deletedAt = 0, snoozeUntil = 0)
        if (n.repeat == "NONE" && n.triggerAt <= now) n = n.copy(fired = true)   // don't ring for the past
        else if (n.repeat != "NONE" && n.triggerAt <= now) n = advance(n, now)
        n = n.touch()
        dao.upsert(n)
        if (needsAlarm(n)) scheduleMain(c, n)
        ReminderWidget.refreshNow(c)
        WearBridge.pushList(c)
        Sync.schedulePush(c)
    }

    /** Permanently removes a task that is in the recycle bin. */
    suspend fun purge(c: Context, t: Task) = deleteLocal(c, t)

    suspend fun purgeExpired(c: Context) {
        val dao = AppDb.get(c).dao()
        for (t in dao.expired(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)) deleteLocal(c, t)
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
        var t = withAnchor(remote, now)
        Scheduler.cancel(c, t.id, false)
        Scheduler.cancel(c, t.id, true)
        if (t.done) {
            dao.upsert(t.copy(snoozeUntil = 0))
            Notifier.cancel(c, t.id)
            return
        }
        if (t.repeat == "SCHEDULE") t = t.copy(triggerAt = nextOccurrence(t, now) ?: (now + 60_000))
        else if ((t.repeat == "MONTHLY" || t.repeat == "DAILY") && t.triggerAt <= now) t = advance(t, now)
        else if (t.repeat == "LIST") t = settleList(t, now)
        dao.upsert(t)
        if (needsAlarm(t)) scheduleMain(c, t) else Scheduler.cancel(c, t.id, false)
        if (t.snoozeUntil > 0) Scheduler.set(c, t.id, if (t.snoozeUntil > now) t.snoozeUntil else now + 3000, true)
    }

    /** After boot, app update, time or timezone change. */
    suspend fun rescheduleAll(c: Context) {
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        purgeExpired(c)
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
