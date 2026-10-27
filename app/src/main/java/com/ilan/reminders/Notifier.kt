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
    @Suppress("DEPRECATION")
    private fun vibrateNow(c: Context, t: Task) {
        val base = patternOf(t.vibration) ?: return
        // repeat the pattern (with a pause) for up to 10 minutes, or until an action cancels it
        val list = ArrayList<Long>()
        var total = 0L
        while (total < 600_000L) {
            val chunk = base.toMutableList(); if (list.isNotEmpty()) chunk[0] = 1500L
            list.addAll(chunk); total += chunk.sum()
        }
        val p = list.toLongArray()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            vibrator(c)?.vibrate(android.os.VibrationEffect.createWaveform(p, -1), attrs)
        } catch (_: Exception) {}
    }

    /** One shared Vibrator: cancel() only works on the same instance that started the vibration. */
    private var vib: android.os.Vibrator? = null
    private fun vibrator(c: Context): android.os.Vibrator? =
        vib ?: c.applicationContext.getSystemService(android.os.Vibrator::class.java).also { vib = it }

    @Suppress("DEPRECATION")
    fun stopVibration(c: Context) {
        try {
            val v = vibrator(c) ?: return
            v.cancel()
            // belt and braces: a 1 ms buzz replaces whatever is still playing
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            v.vibrate(android.os.VibrationEffect.createOneShot(1, 1), attrs)
        } catch (_: Exception) {}
    }

    fun show(c: Context, t: Task) {
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
        n.flags = n.flags or Notification.FLAG_INSISTENT   // sound repeats until the notification is dismissed
        NotificationManagerCompat.from(c).cancel(resultId(t.id))
        NotificationManagerCompat.from(c).notify(t.id.toInt(), n)
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

    fun dismissReminder(c: Context, id: Long) { stopVibration(c); AlertActivity.finishFor(id); NotificationManagerCompat.from(c).cancel(id.toInt()) }

    fun cancel(c: Context, id: Long) {
        stopVibration(c); AlertActivity.finishFor(id)
        NotificationManagerCompat.from(c).cancel(id.toInt())
        NotificationManagerCompat.from(c).cancel(resultId(id))
    }
}
