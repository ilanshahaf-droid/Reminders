package com.ilan.reminders.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import com.google.android.gms.wearable.Wearable

object WatchBridge {
    fun send(c: Context, path: String, payload: String) {
        runCatching {
            val app = c.applicationContext
            Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                for (n in nodes) {
                    Wearable.getMessageClient(app).sendMessage(n.id, path, payload.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
}

/** The "בטל נודניק" button on the watch's snooze notification. */
class SnoozeActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        WatchBridge.send(c, "/reminder/action", JSONObject().put("id", id).put("action", "CANCEL_SNOOZE").put("minutes", 0).toString())
        c.getSystemService(NotificationManager::class.java).cancel(ReminderListenerService.snoozeNoteId(id))
        android.widget.Toast.makeText(c.applicationContext, "הנודניק בוטל", android.widget.Toast.LENGTH_SHORT).show()
    }
}
