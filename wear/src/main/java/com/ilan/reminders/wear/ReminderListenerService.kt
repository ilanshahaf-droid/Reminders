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
            "/reminder/dismiss" -> dismiss(j.optLong("id"), j.optBoolean("done"))
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
        getSystemService(NotificationManager::class.java).cancel(snoozeNoteId(id))
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
            ch.enableVibration(false)   // vibration is repeated manually until the user acts
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
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        nm.notify(id.toInt(), n)
        startVibration(this)
        // also try to open the screen directly (works when the overlay permission was granted over ADB)
        runCatching { startActivity(intent) }
    }

    private fun dismiss(id: Long, done: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(id.toInt())
        stopVibration(this)
        if (done) nm.cancel(snoozeNoteId(id))
        ReminderActivity.current?.get()?.let { a ->
            if (a.shows(id)) a.runOnUiThread { a.finish() }
        }
    }

    companion object {
        /** Repeats the buzz (alarm usage, so it also works in Do Not Disturb) for up to 10 minutes or until stopped. */
        @Suppress("DEPRECATION")
        fun startVibration(c: android.content.Context) {
            val base = longArrayOf(0, 800, 200, 800, 200, 800)
            val list = ArrayList<Long>(); var total = 0L
            while (total < 600_000L) {
                val chunk = base.toMutableList(); if (list.isNotEmpty()) chunk[0] = 1500L
                list.addAll(chunk); total += chunk.sum()
            }
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            try {
                c.getSystemService(android.os.Vibrator::class.java)
                    ?.vibrate(android.os.VibrationEffect.createWaveform(list.toLongArray(), -1), attrs)
            } catch (_: Exception) {}
        }

        fun stopVibration(c: android.content.Context) {
            try { c.getSystemService(android.os.Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        }

        private const val CH = "watch_reminders2"
        fun snoozeNoteId(id: Long) = id.toInt() xor 0x40000000
    }
}
