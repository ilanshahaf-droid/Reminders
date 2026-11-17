package com.ilan.reminders

import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/** Talks to the watch app (same applicationId) through the Wearable Data Layer. */
object WearBridge {
    private fun send(c: Context, path: String, payload: String, attempt: Int = 0) {
        val app = c.applicationContext
        // no watch in reach (or a failed send) is retried a few times; nothing is retried once a send went through
        fun retry() {
            if (attempt < 3) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ send(app, path, payload, attempt + 1) }, 2000L * (attempt + 1))
        }
        runCatching {
            Wearable.getNodeClient(app).connectedNodes
                .addOnSuccessListener { nodes ->
                    // a phone without a paired watch has no nodes at all: do not keep retrying forever
                    for (n in nodes) {
                        Wearable.getMessageClient(app).sendMessage(n.id, path, payload.toByteArray(Charsets.UTF_8))
                            .addOnFailureListener { retry() }
                    }
                }
                .addOnFailureListener { retry() }
        }
    }

    fun fire(c: Context, t: Task) {
        val now = LocalTime.now()
        val snoozeMin = c.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("snoozeMin", 10)
        send(
            c, "/reminder/fire",
            JSONObject().put("id", t.id).put("title", t.title).put("notes", t.notes).put("snooze", snoozeMin)
                .put("time", "%02d:%02d".format(now.hour, now.minute)).toString()
        )
    }

    /** Sends the active task list to the watch as a Data Layer item (delivered even if the watch is offline now). */
    fun pushList(c: Context) {
        val app = c.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val now = System.currentTimeMillis()
                val arr = JSONArray()
                AppDb.get(app).dao().active().filter { it.alertMode != "NONE" }.sortedBy { it.triggerAt }.take(30).forEach { t ->
                    arr.put(
                        JSONObject().put("id", t.id).put("title", t.title).put("when", whenText(t.triggerAt))
                            .put("rec", recurrenceText(t).replace("\n", " | "))
                            .put("overdue", t.repeat == "NONE" && t.triggerAt < now)
                            .put("snoozeUntil", t.snoozeUntil).put("at", t.triggerAt)
                    )
                }
                val req = PutDataMapRequest.create("/tasks").apply {
                    dataMap.putString("json", arr.toString())
                    dataMap.putLong("ts", now)
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(app).putDataItem(req)
            }
        }
    }

    /** A message is fire-and-forget, so repeat it: a watch that is briefly out of reach (locked phone, Doze) would otherwise keep buzzing. */
    fun dismiss(c: Context, id: Long, done: Boolean = false) {
        val body = JSONObject().put("id", id).put("done", done).toString()
        send(c, "/reminder/dismiss", body)
        val app = c.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            for (d in longArrayOf(2_000, 4_000, 8_000)) {
                kotlinx.coroutines.delay(d)
                send(app, "/reminder/dismiss", body)
            }
        }
    }
}

/** Receives "done" / "snooze" taps made on the watch and reuses the notification action logic. */
class WearActionService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path == "/tasks/request") { WearBridge.pushList(this); return }
        val j = runCatching { JSONObject(String(e.data, Charsets.UTF_8)) }.getOrNull() ?: return
        when (e.path) {
            "/reminder/create", "/reminder/delete", "/reminder/settime" -> { edit(e.path, j); return }
            "/reminder/action" -> action(j)
        }
    }

    /** The same watch action also arrives as a stored Data Layer item (reliable after a Bluetooth drop); the first copy wins. */
    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        for (ev in events) {
            val path = ev.dataItem.uri.path ?: continue
            if (ev.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED || !path.startsWith("/act/")) continue
            val json = com.google.android.gms.wearable.DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("json") ?: continue
            runCatching { action(JSONObject(json)) }
            runCatching { Wearable.getDataClient(this).deleteDataItems(ev.dataItem.uri) }
        }
    }

    private fun action(j: JSONObject) {
        val ts = j.optLong("ts", 0)
        if (ts > 0) {
            val p = getSharedPreferences("alerts", Context.MODE_PRIVATE)
            synchronized(WearActionService::class.java) {
                if (p.getLong("wa$ts", 0) > 0) return
                if (System.currentTimeMillis() - ts > 6 * 3600_000L) return
                p.edit().putLong("wa$ts", System.currentTimeMillis()).apply()
                // keep the list small
                val old = p.all.keys.filter { it.startsWith("wa") && (p.getLong(it, 0) < System.currentTimeMillis() - 24 * 3600_000L) }
                if (old.isNotEmpty()) p.edit().apply { old.forEach { remove(it) } }.apply()
            }
        }
        val minutes = j.optInt("minutes", 0).takeIf { it > 0 }
            ?: getSharedPreferences("app", Context.MODE_PRIVATE).getInt("snoozeMin", 10)
        sendBroadcast(
            Intent(this, ActionReceiver::class.java).setAction(j.optString("action", "SNOOZE"))
                .putExtra("id", j.optLong("id", -1)).putExtra("minutes", minutes)
        )
    }

    /** Create / delete / re-time a reminder from the watch. */
    private fun edit(path: String, j: JSONObject) {
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val dao = AppDb.get(app).dao()
                when (path) {
                    "/reminder/create" -> {
                        val title = j.optString("title").trim()
                        val at = j.optLong("at")
                        if (title.isNotEmpty() && at > 0) {
                            val vib = app.getSharedPreferences("app", Context.MODE_PRIVATE).getString("defVibration", "STRONG") ?: "STRONG"
                            Reminders.save(app, Task(title = title, triggerAt = at, vibration = vib, categoryId = defaultCategory(app)))
                        }
                    }
                    "/reminder/delete" -> dao.get(j.optLong("id"))?.let { Reminders.delete(app, it) }
                    "/reminder/settime" -> dao.get(j.optLong("id"))?.let { t ->
                        if (t.repeat == "NONE" || t.repeat == "MONTHLY" || t.repeat == "DAILY") Reminders.save(app, t.copy(triggerAt = j.optLong("at")))
                    }
                }
                ReminderWidget.refreshNow(app)
                WearBridge.pushList(app)
            }
        }
    }
}
