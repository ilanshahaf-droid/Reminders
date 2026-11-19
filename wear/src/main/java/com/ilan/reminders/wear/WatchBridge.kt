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
        var payload = payload
        if ((path == "/reminder/action" || path == "/event/action") && attempt == 0) {
            // A message is lost if the phone is out of reach for more than the retries below, and the phone then keeps ringing.
            // So the same action also goes as a Data Layer item, which is kept and delivered the moment the phone is reachable again;
            // the phone ignores whichever copy comes second (same ts).
            val ts = System.currentTimeMillis()
            payload = runCatching { JSONObject(payload).put("ts", ts).toString() }.getOrDefault(payload)
            runCatching {
                val req = com.google.android.gms.wearable.PutDataMapRequest.create("/act/$ts").apply {
                    dataMap.putString("json", payload); dataMap.putLong("ts", ts)
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(app).putDataItem(req)
            }
        }
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

/** Snooze / close buttons on the watch's calendar-event notification: answered back to the phone, which owns the event alert. */
class EventActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nid = i.getIntExtra("nid", 0)
        WatchBridge.send(
            c, "/event/action",
            JSONObject().put("act", i.action ?: "DISMISS").put("nid", nid).put("key", i.getStringExtra("key") ?: "")
                .put("title", i.getStringExtra("title") ?: "").put("text", i.getStringExtra("text") ?: "").toString()
        )
        c.getSystemService(NotificationManager::class.java).cancel(nid)
    }
}
