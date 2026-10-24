package com.ilan.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val p = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block() } finally { p.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        val snooze = i.getBooleanExtra("snooze", false)
        async {
            val dao = AppDb.get(c).dao()
            val t = dao.get(id) ?: return@async
            if (t.done) return@async
            Notifier.show(c, t)
            when {
                snooze -> dao.upsert(t.copy(snoozeUntil = 0))
                t.repeat != "NONE" -> {
                    val n = Reminders.advance(t, System.currentTimeMillis())
                    dao.upsert(n)
                    Scheduler.set(c, id, n.triggerAt, false)
                }
                else -> dao.upsert(t.copy(fired = true))
            }
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        val minutes = i.getIntExtra("minutes", 10)
        val act = i.action
        async {
            val dao = AppDb.get(c).dao()
            val t = dao.get(id) ?: return@async
            Notifier.cancel(c, id)
            if (act == "DONE") {
                Scheduler.cancel(c, id, true)
                if (t.repeat == "NONE") {
                    Scheduler.cancel(c, id, false)
                    dao.upsert(t.copy(done = true, snoozeUntil = 0))
                } else dao.upsert(t.copy(snoozeUntil = 0))
            } else {
                val at = System.currentTimeMillis() + minutes * 60_000L
                dao.upsert(t.copy(snoozeUntil = at))
                Scheduler.set(c, id, at, true)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        async { Reminders.rescheduleAll(c) }
    }
}
