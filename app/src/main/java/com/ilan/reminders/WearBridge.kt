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
    fun send(c: Context, path: String, payload: String, attempt: Int = 0) {
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
        pushAgenda(app); pushNotes(app)
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

    /** Events of today and the next two days, for the watch's diary screen and tile. */
    fun pushAgenda(c: Context) {
        val app = c.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (!CalRepo.hasRead(app)) return@runCatching
                val z = java.time.ZoneId.systemDefault()
                val from = java.time.LocalDate.now().atStartOfDay(z).toInstant().toEpochMilli()
                val to = from + 3L * 86_400_000L
                val all = CalRepo.calendars(app).map { it.id }.toSet()
                val vis = app.getSharedPreferences("app", Context.MODE_PRIVATE).getStringSet("diaryVis", null)?.mapNotNull { it.toLongOrNull() }?.toSet()?.intersect(all) ?: all
                val arr = JSONArray()
                CalRepo.events(app, from, to, vis).sortedWith(compareBy({ !it.allDay }, { it.begin })).take(40).forEach { e ->
                    arr.put(JSONObject().put("id", e.id).put("title", e.title).put("b", e.begin).put("e", e.end).put("ad", e.allDay)
                        .put("loc", e.location).put("col", e.color).put("rep", e.repeat))
                }
                val req = PutDataMapRequest.create("/agenda").apply {
                    dataMap.putString("json", arr.toString()); dataMap.putLong("ts", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(app).putDataItem(req)
            }
        }
    }

    /** The newest notes (plain text only; locked notes are never sent) for reading on the watch. */
    fun pushNotes(c: Context) {
        val app = c.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val arr = JSONArray()
                AppDb.get(app).notes().allNow().filter { it.deletedAt == 0L && !it.locked && !it.isBlank() }
                    .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }).take(20).forEach { n ->
                        arr.put(JSONObject().put("id", n.id).put("title", n.title).put("text", n.plain.take(1500)).put("pin", n.pinned))
                    }
                val req = PutDataMapRequest.create("/notes").apply {
                    dataMap.putString("json", arr.toString()); dataMap.putLong("ts", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(app).putDataItem(req)
            }
        }
    }

    /** A calendar event alert, shown on the watch with snooze / close buttons. */
    fun fireEvent(c: Context, nid: Int, key: String, title: String, text: String) {
        send(c, "/event/fire", JSONObject().put("nid", nid).put("key", key).put("title", title).put("text", text)
            .put("snooze", EventNotify.snooze(c)).toString())
    }

    fun eventDismiss(c: Context, nid: Int) = send(c, "/event/dismiss", JSONObject().put("nid", nid).toString())

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
            "/reminder/action" -> { action(j); ack("/reminder/ack", j) }
            "/event/action" -> eventAction(j)
            "/note/create" -> addNote(j.optString("text"))
            "/event/create" -> newEvent(j.optString("text"))
        }
    }

    private fun eventAction(j: JSONObject) {
        if (firstTime(j)) eventActionNow(j)
        ack("/event/ack", j)
    }

    /** Tells the watch the action arrived, so it stops re-sending it. Also logged for diagnosis (settings → watch log). */
    private fun ack(path: String, j: JSONObject) {
        val ts = j.optLong("ts", 0)
        Notifier.log(this, "watch ${j.optString("action", j.optString("act"))} id=${j.optLong("id", j.optInt("nid").toLong())}")
        if (ts > 0) WearBridge.send(this, path, JSONObject().put("ts", ts).toString())
    }

    /** True the first time this ts is seen (the message and the stored copy both arrive; only one may act). */
    private fun firstTime(j: JSONObject): Boolean {
        val ts = j.optLong("ts", 0)
        if (ts <= 0) return true
        val p = getSharedPreferences("alerts", Context.MODE_PRIVATE)
        synchronized(WearActionService::class.java) {
            if (p.getLong("wa$ts", 0) > 0) return false
            if (System.currentTimeMillis() - ts > 6 * 3600_000L) return false
            p.edit().putLong("wa$ts", System.currentTimeMillis()).apply()
        }
        return true
    }

    private fun eventActionNow(j: JSONObject) {
        val nid = j.optInt("nid")
        if (j.optString("act") == "SNOOZE") EventNotify.snoozeNow(this, nid, j.optString("key"), j.optString("title"), j.optString("text"))
        else { EventNotify.cancelRepeat(this, nid); androidx.core.app.NotificationManagerCompat.from(this).cancel(nid) }
        WearBridge.eventDismiss(this, nid)
    }

    /** A dictated note from the watch becomes a normal note on the phone. */
    private fun addNote(text: String) {
        if (text.isBlank()) return
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                Notes.save(app, newNote(app).copy(html = esc, plain = text))
                WearBridge.pushNotes(app)
            }
        }
    }

    /** A dictated event ("פגישה עם דנה מחר ב-10") becomes a diary event with the default calendar, length and reminder. */
    private fun newEvent(text: String) {
        if (text.isBlank()) return
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (!CalRepo.hasWrite(app)) return@runCatching
                val ds = DS.defStart(app)
                val v = VoiceParse.parse(text, java.time.ZonedDateTime.now(), if (ds >= 0) LocalTime.of(ds / 60, ds % 60) else LocalTime.of(9, 0))
                val start = v.at ?: java.time.ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusMinutes(DS.defDur(app).toLong())
                val writable = CalRepo.calendars(app).filter { it.writable }
                val defId = app.getSharedPreferences("app", Context.MODE_PRIVATE).getLong("diaryDef", -1L)
                val cal = writable.firstOrNull { it.id == defId } ?: writable.firstOrNull { it.account.contains("@") } ?: writable.firstOrNull() ?: return@runCatching
                val r = DS.defRem(app)
                CalRepo.save(app, null, 0L, EdState(v.title.ifBlank { text }, cal.id, start.toLocalDate(), start.toLocalTime(), end.toLocalDate(), end.toLocalTime(),
                    false, "", "", if (r != -2) listOf(r) else emptyList(), ""))
                runCatching { EventNotify.run(app) }
                WearBridge.pushAgenda(app)
            }
        }
    }

    /** The same watch action also arrives as a stored Data Layer item (reliable after a Bluetooth drop); the first copy wins. */
    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        for (ev in events) {
            val path = ev.dataItem.uri.path ?: continue
            if (ev.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED || !path.startsWith("/act/")) continue
            val json = com.google.android.gms.wearable.DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("json") ?: continue
            runCatching {
                val j = JSONObject(json)
                if (j.has("nid")) eventAction(j) else { action(j); ack("/reminder/ack", j) }
            }
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
        // Silence the phone at once, before any database work, so a locked / sleeping phone stops beeping even if the rest is slow.
        val rid = j.optLong("id", -1)
        if (rid >= 0 && j.optString("action", "SNOOZE") != "CANCEL_SNOOZE") runCatching { Notifier.dismissReminder(this, rid) }
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
