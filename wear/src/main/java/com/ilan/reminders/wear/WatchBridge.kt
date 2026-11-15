package com.ilan.reminders.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import com.google.android.gms.wearable.Wearable

object WatchBridge {
    /**
     * Messages are fire-and-forget, so a momentary Bluetooth drop would silently lose a snooze / done tap and the phone would keep
     * buzzing. Retry (only when no phone was reachable or the send failed) a few times.
     */
    fun send(c: Context, path: String, payload: String, attempt: Int = 0) {
        val app = c.applicationContext
        fun retry() {
            if (attempt < 5) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ send(app, path, payload, attempt + 1) }, 1500L * (attempt + 1))
        }
        runCatching {
            Wearable.getNodeClient(app).connectedNodes
                .addOnSuccessListener { nodes ->
                    if (nodes.isEmpty()) { retry(); return@addOnSuccessListener }
                    for (n in nodes) {
                        Wearable.getMessageClient(app).sendMessage(n.id, path, payload.toByteArray(Charsets.UTF_8))
                            .addOnFailureListener { retry() }
                    }
                }
                .addOnFailureListener { retry() }
        }.onFailure { retry() }
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
