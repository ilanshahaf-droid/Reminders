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
    fun showSecs(c: Context) = p(c).getInt("evShowSecs", 0)               // 0 = until dismissed
    fun renotify(c: Context) = p(c).getInt("evRenotify", 0)               // minutes, 0 = off

    private class Al(val evId: Long, val title: String, val begin: Long, val end: Long, val loc: String, val fire: Long, val key: String)

    fun useOverlay(c: Context) = showSecs(c) > 0 && EventOverlay.can(c)

    private fun channel(c: Context): String {
        val snd = sound(c); val v = vib(c); val l = led(c); val ov = useOverlay(c)
        val id = "ev1${if (ov) "p" else ""}_${v}_${if (l) 1 else 0}_${if (snd.isBlank()) "def" else abs(snd.hashCode()).toString()}"
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "התראות אירועי יומן", if (ov) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH)
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
        val vis = (p(c).getStringSet("diaryVis", null)?.mapNotNull { it.toLongOrNull() }?.toSet()?.let { it.intersect(cals) } ?: cals) - CalRepo.notifOff(c)
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
                mins.getOrPut(q.getLong(0)) { ArrayList() }.add(q.getInt(1).let { if (it == -1) 10 else it })
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
        NotificationManagerCompat.from(c).notify(nid, build(c, nid, key, title, text, false))
        showSecs(c).let { sec -> if (sec > 0) EventOverlay.show(c, nid, key, title, text, sec) }
        WearBridge.fireEvent(c, nid, key, title, text)
        val rn = renotify(c)
        if (rn > 0 && count < 5) setClock(c, System.currentTimeMillis() + rn * 60_000L, pi(c, nid + 4, "REPOST") {
            it.putExtra("nid", nid).putExtra("key", key).putExtra("title", title).putExtra("text", text).putExtra("count", count + 1)
        })
    }

    private fun build(c: Context, nid: Int, key: String, title: String, text: String, silent: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(c, nid, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).also { i -> val p = key.split(":"); if (p.size >= 2) { i.putExtra("evId", p[0].toLongOrNull() ?: -1L); i.putExtra("evBegin", p[1].toLongOrNull() ?: 0L); i.putExtra("evNid", nid) } }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val snz = snooze(c)
        val b = NotificationCompat.Builder(c, channel(c))
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("📅 $title").setContentText(text)
            .setPriority(if (useOverlay(c)) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(open).setAutoCancel(true)
            .setDeleteIntent(pi(c, nid + 1, "DISMISS") { it.putExtra("nid", nid) })
            .addAction(0, "נודניק $snz דק׳", pi(c, nid + 2, "SNOOZE") { it.putExtra("nid", nid).putExtra("key", key).putExtra("title", title).putExtra("text", text) })
            .addAction(0, "סגור", pi(c, nid + 3, "DISMISS") { it.putExtra("nid", nid) })
        if (silent) b.setSilent(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
        else if (led(c)) b.setLights(0xFFFB8C00.toInt(), 600, 600)
        return b.build()
    }

    /** after the display time: hide the pop-up but keep the notification in the shade (silent copy) */
    fun quiet(c: Context, nid: Int, key: String, title: String, text: String) {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        if (nm.activeNotifications.none { it.id == nid }) return
        val n = build(c, nid, key, title, text, true)
        NotificationManagerCompat.from(c).cancel(nid)
        runCatching { NotificationManagerCompat.from(c).notify(nid, n) }
    }

    fun cancelRepeat(c: Context, nid: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c, nid + 4, "REPOST")); am.cancel(pi(c, nid + 5, "SNOOZE_FIRE")); am.cancel(pi(c, nid + 6, "QUIET"))
    }

    fun snoozeNow(c: Context, nid: Int, key: String, title: String, text: String, minutes: Int = snooze(c)) {
        NotificationManagerCompat.from(c).cancel(nid)
        cancelRepeat(c, nid)
        setClock(c, System.currentTimeMillis() + minutes * 60_000L, pi(c, nid + 5, "SNOOZE_FIRE") {
            it.putExtra("nid", nid).putExtra("key", key).putExtra("title", title).putExtra("text", text).putExtra("count", 0)
        })
    }

    /** posts what is due and sets the alarm for the next reminder */
    fun run(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!enabled(c) || !CalRepo.hasRead(c)) { am.cancel(pi(c, 1, "RUN")); am.cancel(pi(c, 2, "RUN")); return }
        runCatching { EventAtt.purge(c) }
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
        WearBridge.pushAgenda(c)
    }
}

class EventReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nid = i.getIntExtra("nid", 0)
        when (i.action) {
            "DISMISS" -> { EventNotify.cancelRepeat(c, nid); NotificationManagerCompat.from(c).cancel(nid); WearBridge.eventDismiss(c, nid) }
            "SNOOZE" -> { EventNotify.snoozeNow(c, nid, i.getStringExtra("key").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("text").orEmpty()); WearBridge.eventDismiss(c, nid) }
            "QUIET" -> EventNotify.quiet(c, nid, i.getStringExtra("key").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("text").orEmpty())
            "REPOST", "SNOOZE_FIRE" -> EventNotify.post(c, i.getStringExtra("key").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("text").orEmpty(), i.getIntExtra("count", 0))
            else -> {
                val p = goAsync()
                Thread { try { EventNotify.run(c) } finally { p.finish() } }.start()
            }
        }
    }
}


/** own pop-up window drawn over other apps for the user-chosen number of seconds (the system heads-up hides itself after ~5s) */
object EventOverlay {
    private var view: android.view.View? = null
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    fun can(c: Context) = android.provider.Settings.canDrawOverlays(c)

    fun hide(c: Context) {
        h.removeCallbacksAndMessages(null)
        view?.let { v -> runCatching { c.applicationContext.getSystemService(android.view.WindowManager::class.java).removeView(v) } }
        view = null
    }

    fun show(c: Context, nid: Int, key: String, title: String, text: String, secs: Int) {
        if (!can(c)) return
        val ctx = c.applicationContext
        h.post {
            hide(ctx)
            runCatching {
                val wm = ctx.getSystemService(android.view.WindowManager::class.java)
                val dp = ctx.resources.displayMetrics.density
                fun d(x: Int) = (x * dp).toInt()
                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
                    setPadding(d(16), d(12), d(16), d(6))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = d(16).toFloat() }
                    elevation = d(8).toFloat()
                }
                root.addView(android.widget.TextView(ctx).apply { this.text = "📅 $title"; textSize = 18f; setTextColor(0xFF000000.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                root.addView(android.widget.TextView(ctx).apply { this.text = text; textSize = 15f; setTextColor(0xFF000000.toInt()) })
                val row = android.widget.LinearLayout(ctx).apply { layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL; gravity = android.view.Gravity.START }
                fun btn(t: String, f: () -> Unit) = android.widget.TextView(ctx).apply {
                    this.text = t; textSize = 15f; setTextColor(0xFF000000.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(d(12), d(10), d(12), d(10)); setOnClickListener { f() }
                }
                row.addView(btn("סגור") {
                    EventNotify.cancelRepeat(ctx, nid); NotificationManagerCompat.from(ctx).cancel(nid); WearBridge.eventDismiss(ctx, nid); hide(ctx)
                })
                row.addView(btn("נודניק ${EventNotify.snooze(ctx)} דק׳") {
                    EventNotify.snoozeNow(ctx, nid, key, title, text); WearBridge.eventDismiss(ctx, nid); hide(ctx)
                })
                row.addView(btn("בחר זמן") {
                    hide(ctx)
                    ctx.startActivity(Intent(ctx, SnoozePickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("evNid", nid).putExtra("evKey", key).putExtra("evTitle", title).putExtra("evText", text))
                })
                root.addView(row)
                root.setOnClickListener {
                    hide(ctx)
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).also { i ->
                        val p = key.split(":"); if (p.size >= 2) { i.putExtra("evId", p[0].toLongOrNull() ?: -1L); i.putExtra("evBegin", p[1].toLongOrNull() ?: 0L); i.putExtra("evNid", nid) }
                    })
                }
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply { gravity = android.view.Gravity.TOP; y = d(2); horizontalMargin = 0.03f }
                wm.addView(root, lp); view = root
                h.postDelayed({ hide(ctx) }, secs * 1000L)
            }
        }
    }
}
