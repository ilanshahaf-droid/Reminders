package com.ilan.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun BroadcastReceiver.async(c: Context, block: suspend () -> Unit) {
    val p = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block(); ReminderWidget.refreshNow(c) } finally { p.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        val snooze = i.getBooleanExtra("snooze", false)
        val nag = if (i.action == "NAG") i.getIntExtra("nag", 1) else 0
        async(c) {
            val dao = AppDb.get(c).dao()
            val t = dao.get(id) ?: return@async
            if (t.done || t.deletedAt > 0) return@async
            // a repeated alert is pointless (and a source of ghost alerts) if the user already pressed a button
            if (nag > 0 && Notifier.handledSinceFire(c, id)) return@async
            if (t.alertMode != "NONE") {
                Notifier.show(c, t)
                WearBridge.fire(c, t)
                Scheduler.setNag(c, id, nag + 1)
            }
            if (nag > 0) return@async
            // re-read: the user may have tapped a button while the alert was being shown, and that must not be overwritten
            val cur = dao.get(id) ?: return@async
            if (cur.done || cur.deletedAt > 0) { Notifier.dismissReminder(c, id); return@async }
            if (snooze) {
                dao.upsert(cur.copy(snoozeUntil = 0))
                WearBridge.pushList(c)
                return@async
            }
            val n = if (cur.repeat == "NONE") cur.copy(fired = true) else Reminders.advance(cur, System.currentTimeMillis())
            dao.upsert(n)
            if (cur.repeat != "NONE" && Reminders.needsAlarm(n)) Scheduler.set(c, id, n.triggerAt, false)
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        val minutes = i.getIntExtra("minutes", 10)
        val act = i.action
        if (act == "STOP") { Notifier.stopVibration(c); return }
        async(c) {
            val dao = AppDb.get(c).dao()
            val t = dao.get(id) ?: return@async
            val now = System.currentTimeMillis()
            Notifier.noteHandled(c, id)
            Notifier.dismissReminder(c, id)
            fun hm(at: Long): String {
                val z = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                return "%02d:%02d".format(z.hour, z.minute)
            }
            fun toast(msg: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(c.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            when (act) {
                "DONE" -> {
                    toast("התזכורת הושלמה.")
                    Scheduler.cancel(c, id, true)
                    val finished = t.repeat == "NONE" ||
                        (t.repeat == "LIST" && Reminders.nextList(t, now) == null)
                    if (finished) {
                        Scheduler.cancel(c, id, false)
                        dao.upsert(t.copy(done = true, snoozeUntil = 0).touch())
                    } else dao.upsert(t.copy(snoozeUntil = 0).touch())
                    Notifier.showResult(c, t, "הושלמה ✓", 8000)
                }
                "CANCEL_SNOOZE" -> {
                    toast("הנודניק בוטל.")
                    Scheduler.cancel(c, id, true)
                    dao.upsert(t.copy(snoozeUntil = 0).touch())
                    Notifier.showResult(c, t, "הנודניק בוטל", 6000)
                }
                "SNOOZE_ADD" -> {
                    val at = maxOf(t.snoozeUntil, now) + minutes * 60_000L
                    toast("הנודניק עודכן ל-${hm(at)}.")
                    dao.upsert(t.copy(snoozeUntil = at).touch())
                    Scheduler.set(c, id, at, true)
                    Notifier.showResult(c, t, "נדחה · נוספו $minutes דק׳ · יופיע שוב ב-${hm(at)}", snoozeActions = true)
                }
                else -> {
                    val at = now + minutes * 60_000L
                    toast("הנודניק הופעל ל-${hm(at)}.")
                    dao.upsert(t.copy(snoozeUntil = at).touch())
                    Scheduler.set(c, id, at, true)
                    Notifier.showResult(c, t, "נדחה ל-$minutes דק׳ · יופיע שוב ב-${hm(at)}", snoozeActions = true)
                }
            }
            WearBridge.dismiss(c, id, act == "DONE" || act == "CANCEL_SNOOZE")
            WearBridge.pushList(c)
            // belt and braces: stop any vibration that a racing alert may have started in the meantime
            kotlinx.coroutines.delay(1500)
            Notifier.dismissReminder(c, id)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        async(c) { Reminders.rescheduleAll(c) }
    }
}
