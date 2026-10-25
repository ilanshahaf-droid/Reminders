package com.ilan.reminders.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class ReminderListenerService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        val j = runCatching { JSONObject(String(e.data, Charsets.UTF_8)) }.getOrNull() ?: return
        when (e.path) {
            "/reminder/fire" -> fire(j)
            "/reminder/dismiss" -> dismiss(j.optLong("id"))
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (ev in events) {
            if (ev.type == DataEvent.TYPE_CHANGED && ev.dataItem.uri.path == "/tasks") {
                val json = DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("json") ?: continue
                TaskStore.update(this, json)
            }
        }
    }

    private fun fire(j: JSONObject) {
        val id = j.optLong("id")
        val intent = Intent(this, ReminderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("id", id)
            .putExtra("title", j.optString("title"))
            .putExtra("notes", j.optString("notes"))
            .putExtra("time", j.optString("time"))
            .putExtra("snooze", j.optInt("snooze", 10))
        val pi = PendingIntent.getActivity(
            this, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "תזכורות", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 800, 200, 800, 200, 800)
            ch.setSound(null, null)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF6D4AFF.toInt())
            .setContentTitle(j.optString("title"))
            .setContentText(j.optString("time"))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id.toInt(), n)
        // also try to open the screen directly (works when the overlay permission was granted over ADB)
        runCatching { startActivity(intent) }
    }

    private fun dismiss(id: Long) {
        getSystemService(NotificationManager::class.java).cancel(id.toInt())
        ReminderActivity.current?.get()?.let { a ->
            if (a.shows(id)) a.runOnUiThread { a.finish() }
        }
    }

    companion object {
        private const val CH = "watch_reminders"
    }
}
