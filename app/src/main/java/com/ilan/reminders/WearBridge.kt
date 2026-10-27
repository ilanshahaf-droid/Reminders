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
    private fun send(c: Context, path: String, payload: String) {
        runCatching {
            val app = c.applicationContext
            Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                for (n in nodes) {
                    Wearable.getMessageClient(app).sendMessage(n.id, path, payload.toByteArray(Charsets.UTF_8))
                }
            }
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
                AppDb.get(app).dao().active().sortedBy { it.triggerAt }.take(30).forEach { t ->
                    arr.put(
                        JSONObject().put("id", t.id).put("title", t.title).put("when", whenText(t.triggerAt))
                            .put("rec", recurrenceText(t).replace("\n", " | "))
                            .put("overdue", t.repeat == "NONE" && t.triggerAt < now)
                            .put("snoozeUntil", t.snoozeUntil)
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

    fun dismiss(c: Context, id: Long, done: Boolean = false) =
        send(c, "/reminder/dismiss", JSONObject().put("id", id).put("done", done).toString())
}

/** Receives "done" / "snooze" taps made on the watch and reuses the notification action logic. */
class WearActionService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path == "/tasks/request") { WearBridge.pushList(this); return }
        if (e.path != "/reminder/action") return
        val j = runCatching { JSONObject(String(e.data, Charsets.UTF_8)) }.getOrNull() ?: return
        val minutes = j.optInt("minutes", 0).takeIf { it > 0 }
            ?: getSharedPreferences("app", Context.MODE_PRIVATE).getInt("snoozeMin", 10)
        sendBroadcast(
            Intent(this, ActionReceiver::class.java).setAction(j.optString("action", "SNOOZE"))
                .putExtra("id", j.optLong("id", -1)).putExtra("minutes", minutes)
        )
    }
}
