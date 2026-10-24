package com.ilan.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/** One main alarm and one snooze alarm per task. Uses setAlarmClock: exempt from Doze and
 *  battery restrictions, and needs no exact-alarm permission. */
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
    fun nextAfter(base: Long, repeat: String, now: Long): Long {
        var z = Instant.ofEpochMilli(base).atZone(ZoneId.systemDefault())
        do {
            z = when (repeat) {
                "DAILY" -> z.plusDays(1)
                "WEEKLY" -> z.plusWeeks(1)
                else -> z.plusMonths(1)
            }
        } while (z.toInstant().toEpochMilli() <= now)
        return z.toInstant().toEpochMilli()
    }

    private fun scheduleMain(c: Context, t: Task) {
        val now = System.currentTimeMillis()
        Scheduler.set(c, t.id, if (t.triggerAt > now) t.triggerAt else now + 3000, false)
    }

    suspend fun save(c: Context, task: Task) {
        val dao = AppDb.get(c).dao()
        val t = task.copy(done = false, fired = false, snoozeUntil = 0)
        val id = dao.upsert(t)
        val saved = t.copy(id = id)
        Scheduler.cancel(c, id, true)
        scheduleMain(c, saved)
    }

    suspend fun setDone(c: Context, t: Task, done: Boolean) {
        val dao = AppDb.get(c).dao()
        if (done) {
            dao.upsert(t.copy(done = true, snoozeUntil = 0))
            Scheduler.cancel(c, t.id, false)
            Scheduler.cancel(c, t.id, true)
            Notifier.cancel(c, t.id)
        } else {
            val n = t.copy(done = false, fired = false)
            dao.upsert(n)
            scheduleMain(c, n)
        }
    }

    suspend fun delete(c: Context, t: Task) {
        Scheduler.cancel(c, t.id, false)
        Scheduler.cancel(c, t.id, true)
        Notifier.cancel(c, t.id)
        AppDb.get(c).dao().delete(t.id)
    }

    /** After boot, app update, time or timezone change. */
    suspend fun rescheduleAll(c: Context) {
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        for (t0 in dao.active()) {
            var t = t0
            if (t.repeat != "NONE" && t.triggerAt <= now) {
                t = t.copy(triggerAt = nextAfter(t.triggerAt, t.repeat, now))
                dao.upsert(t)
            }
            if (t.repeat != "NONE" || !t.fired) scheduleMain(c, t) // missed one-offs fire right away
            if (t.snoozeUntil > 0) {
                Scheduler.set(c, t.id, if (t.snoozeUntil > now) t.snoozeUntil else now + 3000, true)
            }
        }
    }
}
