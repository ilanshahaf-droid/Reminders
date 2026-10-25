package com.ilan.reminders

import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
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
        send(
            c, "/reminder/fire",
            JSONObject().put("id", t.id).put("title", t.title).put("notes", t.notes)
                .put("time", "%02d:%02d".format(now.hour, now.minute)).toString()
        )
    }

    fun dismiss(c: Context, id: Long) = send(c, "/reminder/dismiss", JSONObject().put("id", id).toString())
}

/** Receives "done" / "snooze" taps made on the watch and reuses the notification action logic. */
class WearActionService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != "/reminder/action") return
        val j = runCatching { JSONObject(String(e.data, Charsets.UTF_8)) }.getOrNull() ?: return
        val minutes = getSharedPreferences("app", Context.MODE_PRIVATE).getInt("snoozeMin", 10)
        sendBroadcast(
            Intent(this, ActionReceiver::class.java).setAction(j.optString("action", "SNOOZE"))
                .putExtra("id", j.optLong("id", -1)).putExtra("minutes", minutes)
        )
    }
}
