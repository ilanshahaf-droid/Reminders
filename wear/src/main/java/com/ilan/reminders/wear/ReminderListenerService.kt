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
            "/reminder/ack", "/event/ack" -> WatchBridge.acked(j.optLong("ts"))
            "/reminder/fire" -> fire(j)
            "/reminder/dismiss" -> dismiss(j.optLong("id"), j.optBoolean("done"))
            "/event/fire" -> fireEvent(j)
            "/event/dismiss" -> getSystemService(NotificationManager::class.java).cancel(j.optInt("nid"))
        }
    }

    /** A calendar event alert from the phone: a notification with snooze / close, answered back to the phone. */
    private fun fireEvent(j: JSONObject) {
        val nid = j.optInt("nid")
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(EV_CH) == null) {
            val ch = NotificationChannel(EV_CH, "אירועי יומן", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        fun act(a: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            this, nid + code,
            Intent(this, EventActionReceiver::class.java).setAction(a).putExtra("nid", nid).putExtra("key", j.optString("key"))
                .putExtra("title", j.optString("title")).putExtra("text", j.optString("text")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, nid, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, EV_CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF4A7391.toInt())
            .setContentTitle("📅 " + j.optString("title"))
            .setContentText(j.optString("text"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(j.optString("text")))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "נודניק ${j.optInt("snooze", 10)} דק׳", act("SNOOZE", 1))
            .addAction(0, "סגור", act("DISMISS", 2))
            .build()
        nm.notify(nid, n)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (ev in events) {
            val path = ev.dataItem.uri.path
            if (ev.type == DataEvent.TYPE_CHANGED && path != null && path.startsWith("/dis/")) {
                // the phone's "stop" kept as a data item: delivered even if the message was lost while out of reach
                val dm = DataMapItem.fromDataItem(ev.dataItem).dataMap
                val id = path.removePrefix("/dis/").toLongOrNull() ?: continue
                val fired = getSharedPreferences("watch", MODE_PRIVATE).getLong("fireAt$id", 0)
                if (dm.getLong("ts") >= fired - 5000L) dismiss(id, dm.getBoolean("done"))
                continue
            }
            if (ev.type == DataEvent.TYPE_CHANGED && path == "/agenda") {
                DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("json")?.let { WatchData.updateAgenda(this, it) }
                runCatching { androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(NextEventTileService::class.java) }
                continue
            }
            if (ev.type == DataEvent.TYPE_CHANGED && path == "/notes") {
                DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("json")?.let { WatchData.updateNotes(this, it) }
                continue
            }
            if (ev.type == DataEvent.TYPE_CHANGED && path == "/tasks") {
                val json = DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("json") ?: continue
                TaskStore.update(this, json)
                stopStaleAlerts()
                runCatching { androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(ReminderTileService::class.java) }
            }
        }
    }

    private fun fire(j: JSONObject) {
        val id = j.optLong("id")
        markFiring(this, id, true)
        getSharedPreferences("watch", MODE_PRIVATE).edit().putInt("snoozeMin", j.optInt("snooze", 10)).apply()
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
        markFiring(this, id, false)
        stopVibration(this)
        if (done) nm.cancel(snoozeNoteId(id))
        ReminderActivity.current?.get()?.let { a ->
            if (a.shows(id)) a.runOnUiThread { a.finish() }
        }
    }

    /**
     * Safety net: the phone's task list always follows a snooze/done tap. If a "dismiss" message was lost, an alert that is still
     * buzzing for a task that is now snoozed or gone from the list is stopped here.
     */
    private fun stopStaleAlerts() {
        val ids = firing(this)
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val list = TaskStore.tasks.value
        for (id in ids) {
            val t = list.firstOrNull { it.id == id }
            if (t == null || t.snoozeUntil > now) dismiss(id, false)
        }
    }

    companion object {
        private fun firing(c: android.content.Context): Set<Long> =
            c.getSharedPreferences("watch", android.content.Context.MODE_PRIVATE).getStringSet("firing", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

        fun markFiring(c: android.content.Context, id: Long, on: Boolean) {
            val p = c.getSharedPreferences("watch", android.content.Context.MODE_PRIVATE)
            val set = p.getStringSet("firing", emptySet())!!.toMutableSet()
            if (on) set.add(id.toString()) else set.remove(id.toString())
            p.edit().putStringSet("firing", set).apply { if (on) putLong("fireAt$id", System.currentTimeMillis()) }.apply()
        }

        private val vt = android.os.HandlerThread("alert-fx").apply { start() }
        private val vh = android.os.Handler(vt.looper)
        @Volatile private var ringUntil = 0L

        /** Repeats the buzz (alarm usage, so it also works in Do Not Disturb) in single ~4 s cycles for up to 10 minutes or until stopped. */
        fun startVibration(c: android.content.Context) {
            ringUntil = System.currentTimeMillis() + 600_000L
            vh.removeCallbacksAndMessages(null)
            chunk(c.applicationContext, true)
        }

        @Suppress("DEPRECATION")
        private fun chunk(c: android.content.Context, first: Boolean) {
            if (System.currentTimeMillis() >= ringUntil) return
            val base = longArrayOf(0, 800, 200, 800, 200, 800)
            if (!first) base[0] = 1500L
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            try {
                vibrator(c)?.vibrate(android.os.VibrationEffect.createWaveform(base, -1), attrs)
            } catch (_: Exception) {}
            vh.postDelayed({ chunk(c, false) }, base.sum())
        }

        /** One shared Vibrator: cancel() only works on the instance that started the vibration. */
        private var vib: android.os.Vibrator? = null
        private fun vibrator(c: android.content.Context): android.os.Vibrator? =
            vib ?: c.applicationContext.getSystemService(android.os.Vibrator::class.java).also { vib = it }

        @Suppress("DEPRECATION")
        fun stopVibration(c: android.content.Context) {
            ringUntil = 0L
            vh.removeCallbacksAndMessages(null)
            try {
                val v = vibrator(c) ?: return
                v.cancel()
                val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
                v.vibrate(android.os.VibrationEffect.createOneShot(1, 1), attrs)
            } catch (_: Exception) {}
        }

        private const val CH = "watch_reminders2"
        private const val EV_CH = "watch_events1"
        fun snoozeNoteId(id: Long) = id.toInt() xor 0x40000000
    }
}
