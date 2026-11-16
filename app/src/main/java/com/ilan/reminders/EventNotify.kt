package com.ilan.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Notifications for the reminders of calendar events, with their own settings (sound, vibration, light, snooze, re-notify). */
object EventNotify {
    private fun p(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("evOn", true)
    fun sound(c: Context) = p(c).getString("evSound", "") ?: ""          // "" = default, "silent", or a uri
    fun vib(c: Context) = p(c).getString("evVib", "ANDROID") ?: "ANDROID" // ANDROID, STRONG, SHORT, PULSE, NONE
    fun led(c: Context) = p(c).getBoolean("evLed", false)
    fun snooze(c: Context) = p(c).getInt("evSnooze", 10)
    fun renotify(c: Context) = p(c).getInt("evRenotify", 0)               // minutes, 0 = off

    private class Al(val evId: Long, val title: String, val begin: Long, val end: Long, val loc: String, val fire: Long, val key: String)

    private fun channel(c: Context): String {
        val snd = sound(c); val v = vib(c); val l = led(c)
        val id = "ev1_${v}_${if (l) 1 else 0}_${if (snd.isBlank()) "def" else abs(snd.hashCode()).toString()}"
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "התראות אירועי יומן", NotificationManager.IMPORTANCE_HIGH)
            when (v) {
                "NONE" -> ch.enableVibration(false)
                "ANDROID" -> ch.enableVibration(true)
                else -> { ch.enableVibration(true); Notifier.patternOf(v)?.let { ch.vibrationPattern = it } }
            }
            ch.enableLights(l)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            when {
                snd == "silent" -> ch.setSound(null, null)
                snd.isBlank() -> ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
                else -> ch.setSound(Uri.parse(snd), attrs)
            }
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        return id
    }

    private fun upcoming(c: Context, from: Long, to: Long): List<Al> = runCatching {
        if (!CalRepo.hasRead(c)) return emptyList()
        val zone = ZoneId.systemDefault()
        val cals = CalRepo.calendars(c).map { it.id }.toSet()
        val vis = p(c).getStringSet("diaryVis", null)?.mapNotNull { it.toLongOrNull() }?.toSet()?.let { it.intersect(cals) } ?: cals
        val evs = CalRepo.events(c, from - 7 * 3600_000L, to + 14 * 86400_000L, vis)
        if (evs.isEmpty()) return emptyList()
        val ids = evs.map { it.id }.toSet()
        val mins = HashMap<Long, MutableList<Int>>()
        c.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} IN (${ids.joinToString(",")})", null, null
        )?.use { q ->
            while (q.moveToNext()) {
                if (q.getInt(2) == CalendarContract.Reminders.METHOD_EMAIL) continue
                mins.getOrPut(q.getLong(0)) { ArrayList() }.add(q.getInt(1).let { if (it < 0) 10 else it })
            }
        }
        val out = ArrayList<Al>()
        for (e in evs) {
            val base = if (e.allDay) e.days(zone).first.atStartOfDay(zone).toInstant().toEpochMilli() else e.begin
            for (m in mins[e.id].orEmpty().distinct()) {
                val fire = base - m * 60_000L
                if (fire in from until to) out.add(Al(e.id, e.title, base, e.end, e.location, fire, "${e.id}:${e.begin}:$m"))
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun pi(c: Context, code: Int, act: String, f: (Intent) -> Unit = {}): PendingIntent {
        val i = Intent(c, EventReceiver::class.java).setAction(act); f(i)
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun setClock(c: Context, at: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    private fun hm(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    fun post(c: Context, key: String, title: String, text: String, count: Int) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val nid = 900000 + abs(key.hashCode() % 90000)
        val open = PendingIntent.getActivity(c, nid, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val snz = snooze(c)
        val b = NotificationCompat.Builder(c, channel(c))
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("📅 $title").setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(open).setAutoCancel(true)
            .setDeleteIntent(pi(c, nid + 1, "DISMISS") { it.putExtra("nid", nid) })
            .addAction(0, "נודניק $snz דק׳", pi(c, nid + 2, "SNOOZE") { it.putExtra("nid", nid).putExtra("key", key).putExtra("title", title).putExtra("text", text) })
            .addAction(0, "סגור", pi(c, nid + 3, "DISMISS") { it.putExtra("nid", nid) })
        if (led(c)) b.setLights(0xFFFB8C00.toInt(), 600, 600)
        NotificationManagerCompat.from(c).notify(nid, b.build())
        val rn = renotify(c)
        if (rn > 0 && count < 5) setClock(c, System.currentTimeMillis() + rn * 60_000L, pi(c, nid + 4, "REPOST") {
            it.putExtra("nid", nid).putExtra("key", key).putExtra("title", title).putExtra("text", text).putExtra("count", count + 1)
        })
    }

    fun cancelRepeat(c: Context, nid: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c, nid + 4, "REPOST")); am.cancel(pi(c, nid + 5, "SNOOZE_FIRE"))
    }

    fun snoozeNow(c: Context, nid: Int, key: String, title: String, text: String) {
        NotificationManagerCompat.from(c).cancel(nid)
        cancelRepeat(c, nid)
        setClock(c, System.currentTimeMillis() + snooze(c) * 60_000L, pi(c, nid + 5, "SNOOZE_FIRE") {
            it.putExtra("nid", nid).putExtra("key", key).putExtra("title", title).putExtra("text", text).putExtra("count", 0)
        })
    }

    /** posts what is due and sets the alarm for the next reminder */
    fun run(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!enabled(c) || !CalRepo.hasRead(c)) { am.cancel(pi(c, 1, "RUN")); am.cancel(pi(c, 2, "RUN")); return }
        val now = System.currentTimeMillis()
        val shown = (p(c).getStringSet("evShown", emptySet()) ?: emptySet()).toMutableSet()
        shown.removeAll { k -> (k.split(":").getOrNull(1)?.toLongOrNull() ?: 0L) < now - 3 * 86400_000L }
        val all = upcoming(c, now - 3 * 3600_000L, now + 8 * 86400_000L)
        for (a in all.filter { it.fire <= now && it.end > now - 60_000L || it.fire <= now && it.begin > now - 3600_000L }) {
            if (a.key in shown) continue
            shown.add(a.key)
            val tm = if (a.end > a.begin) "${hm(a.begin)}–${hm(a.end)}" else hm(a.begin)
            post(c, a.key, a.title, tm + if (a.loc.isNotBlank()) " · ${a.loc}" else "", 0)
        }
        p(c).edit().putStringSet("evShown", shown).apply()
        val next = all.filter { it.fire > now && it.key !in shown }.minOfOrNull { it.fire }
        if (next != null) setClock(c, next, pi(c, 1, "RUN")) else am.cancel(pi(c, 1, "RUN"))
        // safety net: look again in 6 hours even if nothing woke us earlier
        am.setAndAllowWhileIdle(AlarmManager.RTC, now + 6 * 3600_000L, pi(c, 2, "RUN"))
    }
}

class EventReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nid = i.getIntExtra("nid", 0)
        when (i.action) {
            "DISMISS" -> { EventNotify.cancelRepeat(c, nid); NotificationManagerCompat.from(c).cancel(nid) }
            "SNOOZE" -> EventNotify.snoozeNow(c, nid, i.getStringExtra("key").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("text").orEmpty())
            "REPOST", "SNOOZE_FIRE" -> EventNotify.post(c, i.getStringExtra("key").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("text").orEmpty(), i.getIntExtra("count", 0))
            else -> {
                val p = goAsync()
                Thread { try { EventNotify.run(c) } finally { p.finish() } }.start()
            }
        }
    }
}
