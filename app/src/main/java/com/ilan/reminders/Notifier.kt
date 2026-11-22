package com.ilan.reminders

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).deleteNotificationChannel("reminders_v1")
        runCatching { Sync.ensurePeriodic(this) }
        Thread { runCatching { EventNotify.run(this) } }.start()
    }
}

object Notifier {
    fun patternOf(v: String): LongArray? = when (v) {
        "STRONG" -> longArrayOf(0, 800, 200, 800, 200, 800)
        "SHORT" -> longArrayOf(0, 300)
        "PULSE" -> longArrayOf(0, 150, 100, 150, 100, 150, 100, 150)
        else -> null
    }

    /** A task with no sound of its own uses the default chosen in the app settings. */
    private fun effectiveSound(c: Context, t: Task): String =
        if (t.sound.isNotBlank()) t.sound
        else c.getSharedPreferences("app", Context.MODE_PRIVATE).getString("defSound", "") ?: ""

    /** Android fixes sound and vibration per channel, so each combination gets its own channel. */
    private fun channelFor(c: Context, t: Task): String {
        val snd = effectiveSound(c, t)
        val id = "rem2_${t.vibration}_${if (snd.isBlank()) "def" else abs(snd.hashCode()).toString()}"
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "תזכורות (${t.vibration})", NotificationManager.IMPORTANCE_HIGH)
            // Vibration is done manually (alarm usage) so it also works in Do Not Disturb.
            ch.enableVibration(false)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
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

    private fun action(c: Context, t: Task, act: String, minutes: Int, n: Int): PendingIntent {
        val i = Intent(c, ActionReceiver::class.java).setAction(act)
            .putExtra("id", t.id).putExtra("minutes", minutes)
        return PendingIntent.getBroadcast(
            c, t.id.toInt() * 10 + n, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** App icon (violet rounded square with the bell) for the large notification icon. */
    private fun largeIcon(c: Context): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val clip = Path()
        clip.addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 44f, 44f, Path.Direction.CW)
        cv.clipPath(clip)
        ContextCompat.getDrawable(c, R.drawable.ic_launcher_background)?.let {
            it.setBounds(0, 0, size, size); it.draw(cv)
        }
        val pad = (size * 0.2).toInt()
        ContextCompat.getDrawable(c, R.drawable.ic_launcher_foreground)?.let {
            it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(cv)
        }
        return bmp
    }

    /** Vibrates with ALARM usage, which Do Not Disturb lets through (notification vibration is muted there). */
    private val vh = android.os.Handler(android.os.Looper.getMainLooper())
    private var ringtone: android.media.Ringtone? = null

    /** Repeats the alert sound every few seconds until stopped (replaces the system's insistent repeat). */
    private fun startSoundLoop(c: Context, t: Task) {
        val snd = effectiveSound(c, t)
        if (snd == "silent") return
        val uri = if (snd.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else Uri.parse(snd)
        val app = c.applicationContext
        val until = ringUntil
        fun tick() {
            if (System.currentTimeMillis() >= ringUntil || ringUntil != until) return
            runCatching {
                ringtone?.stop()
                ringtone = RingtoneManager.getRingtone(app, uri)?.also { it.play() }
            }
            vh.postDelayed({ tick() }, 6000L)
        }
        vh.postDelayed({ tick() }, 6000L)   // the notification itself already played the first sound
    }
    @Volatile private var ringUntil = 0L

    /** Short diagnostic log (settings → watch log). */
    fun log(c: Context, line: String) = runCatching {
        val p = c.getSharedPreferences("wlog", Context.MODE_PRIVATE)
        val old = p.getString("log", "")!!.lines().filter { it.isNotBlank() }.takeLast(59)
        p.edit().putString("log", (old + "${java.time.LocalTime.now().withNano(0)} $line").joinToString("\n")).apply()
    }

    /**
     * Vibrates with ALARM usage, which Do Not Disturb lets through (notification vibration is muted there).
     * Played in ~20 s pieces that re-arm themselves only while the alert is still ringing: a stop that fails to cancel the
     * running piece (a cancel only works from the Vibrator instance that started it) still ends the buzz within seconds.
     */
    private fun vibrateNow(c: Context, t: Task) {
        val base = patternOf(t.vibration) ?: return
        vibrateChunk(c.applicationContext, base)
    }

    /** One buzz cycle at a time (about 4 s): whatever happens to a stop request, the buzz ends within one cycle. */
    @Suppress("DEPRECATION")
    private fun vibrateChunk(c: Context, base: LongArray, first: Boolean = true) {
        if (System.currentTimeMillis() >= ringUntil) return
        val chunk = base.copyOf(); if (!first) chunk[0] = 1500L
        val total = chunk.sum()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            vibrator(c)?.vibrate(android.os.VibrationEffect.createWaveform(chunk, -1), attrs)
        } catch (_: Exception) {}
        vh.postDelayed({ vibrateChunk(c, base, false) }, total)
    }

    /** One shared Vibrator: cancel() only works on the same instance that started the vibration. */
    private var vib: android.os.Vibrator? = null
    private fun vibrator(c: Context): android.os.Vibrator? =
        vib ?: c.applicationContext.getSystemService(android.os.Vibrator::class.java).also { vib = it }

    @Suppress("DEPRECATION")
    fun stopVibration(c: Context) {
        // a Vibrator of this process can only cancel its own vibration, so also cancel through a brand-new instance
        try { c.applicationContext.getSystemService(android.os.Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 31) try {
            val vm = c.applicationContext.getSystemService(android.os.VibratorManager::class.java)
            vm?.cancel(); vm?.defaultVibrator?.cancel()
        } catch (_: Exception) {}
        try {
            val v = vibrator(c) ?: return
            v.cancel()
            // belt and braces: a 1 ms buzz replaces whatever is still playing
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            v.vibrate(android.os.VibrationEffect.createOneShot(1, 1), attrs)
        } catch (_: Exception) {}
    }

    private fun alertPrefs(c: Context) = c.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    /** The user pressed a button on this reminder's alert. */
    fun noteHandled(c: Context, id: Long) { alertPrefs(c).edit().putLong("a$id", System.currentTimeMillis()).apply() }
    /** True when a button was pressed after the alert was last shown (so a repeated alert must not come back). */
    fun handledSinceFire(c: Context, id: Long): Boolean {
        val p = alertPrefs(c); return p.getLong("a$id", 0) >= p.getLong("f$id", 0) && p.getLong("a$id", 0) > 0
    }

    fun show(c: Context, t: Task) {
        log(c, "alert id=${t.id}")
        alertPrefs(c).edit().putLong("f${t.id}", System.currentTimeMillis()).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val minutes = c.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("snoozeMin", 10)
        val now = java.time.LocalTime.now()
        val time = "%02d:%02d".format(now.hour, now.minute)
        val text = if (t.notes.isBlank()) time else "$time · ${t.notes}"
        val done = NotificationCompat.Action.Builder(R.drawable.ic_check, "הושלמה", action(c, t, "DONE", 0, 1)).build()
        val snooze = NotificationCompat.Action.Builder(R.drawable.ic_snooze, "נודניק", action(c, t, "SNOOZE", minutes, 2)).build()
        val pickPi = PendingIntent.getActivity(
            c, t.id.toInt() * 10 + 3,
            Intent(c, SnoozePickActivity::class.java).putExtra("id", t.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pick = NotificationCompat.Action.Builder(R.drawable.ic_snooze, "בחר זמן", pickPi).build()
        val n = NotificationCompat.Builder(c, channelFor(c, t))
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF6D4AFF.toInt())
            .setLargeIcon(largeIcon(c))
            .setContentTitle(t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(PendingIntent.getActivity(
                c, t.id.toInt() * 10 + 7,
                Intent(c, AlertActivity::class.java).putExtra("id", t.id).putExtra("title", t.title)
                    .putExtra("text", text).putExtra("minutes", minutes)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setDeleteIntent(PendingIntent.getBroadcast(
                c, t.id.toInt() * 10 + 6, Intent(c, ActionReceiver::class.java).setAction("STOP").putExtra("id", t.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(done)
            .addAction(snooze)
            .addAction(pick)
            .build()
        // No FLAG_INSISTENT: that repeat is run by the system and on some phones it keeps sounding after the notification is gone.
        // The repeat is done by startSoundLoop below, which stopVibration() ends.
        NotificationManagerCompat.from(c).cancel(resultId(t.id))
        NotificationManagerCompat.from(c).notify(t.id.toInt(), n)
        ringUntil = System.currentTimeMillis() + 600_000L
        vh.removeCallbacksAndMessages(null)
        startSoundLoop(c, t)
        vibrateNow(c, t)
    }

    /** Replaces the reminder notification with a short confirmation (snooze set / done). */
    fun showResult(c: Context, t: Task, text: String, autoHideMs: Long = 0, snoozeActions: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, channelFor(c, t))
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF6D4AFF.toInt())
            .setContentTitle(t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .apply { if (autoHideMs > 0) setTimeoutAfter(autoHideMs) }
            .setContentIntent(open)
            .setAutoCancel(!snoozeActions)
            .apply {
                if (snoozeActions) {
                    addAction(NotificationCompat.Action.Builder(R.drawable.ic_check, "הושלמה", action(c, t, "DONE", 0, 1)).build())
                    addAction(NotificationCompat.Action.Builder(R.drawable.ic_snooze, "הוסף נודניק", PendingIntent.getActivity(
                        c, t.id.toInt() * 10 + 5,
                        Intent(c, SnoozePickActivity::class.java).putExtra("id", t.id).putExtra("add", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )).build())
                    addAction(NotificationCompat.Action.Builder(R.drawable.ic_snooze, "בטל נודניק", action(c, t, "CANCEL_SNOOZE", 0, 4)).build())
                }
            }
            .build()
        NotificationManagerCompat.from(c).notify(resultId(t.id), n)
    }

    /** Separate id from the reminder itself, so the watch cancelling the reminder (same package+id) can't remove it. */
    private fun resultId(id: Long) = id.toInt() xor 0x20000000

    /** Every step on its own: one failing step must never leave the sound (the notification) or the buzz running. */
    fun dismissReminder(c: Context, id: Long) {
        log(c, "dismiss id=$id")
        runCatching { NotificationManagerCompat.from(c).cancel(id.toInt()) }
        runCatching { Scheduler.cancelNag(c, id) }
        runCatching { stopVibration(c) }
        runCatching { AlertActivity.finishFor(id) }
        runCatching { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt()) }
    }

    fun cancel(c: Context, id: Long) {
        Scheduler.cancelNag(c, id)
        stopVibration(c); AlertActivity.finishFor(id)
        NotificationManagerCompat.from(c).cancel(id.toInt())
        NotificationManagerCompat.from(c).cancel(resultId(id))
    }
}
