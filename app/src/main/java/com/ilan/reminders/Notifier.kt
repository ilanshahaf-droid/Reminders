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
        val id = "rem_${t.vibration}_${if (snd.isBlank()) "def" else abs(snd.hashCode()).toString()}"
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "תזכורות (${t.vibration})", NotificationManager.IMPORTANCE_HIGH)
            val p = patternOf(t.vibration)
            ch.enableVibration(p != null)
            if (p != null) ch.vibrationPattern = p
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
        val n = NotificationCompat.Builder(c, channelFor(c, t))
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF6D4AFF.toInt())
            .setLargeIcon(largeIcon(c))
            .setContentTitle(t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(done)
            .addAction(snooze)
            .build()
        NotificationManagerCompat.from(c).cancel(resultId(t.id))
        NotificationManagerCompat.from(c).notify(t.id.toInt(), n)
    }

    /** Replaces the reminder notification with a short confirmation (snooze set / done). */
    fun showResult(c: Context, t: Task, text: String, autoHideMs: Long = 0) {
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
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(resultId(t.id), n)
    }

    /** Separate id from the reminder itself, so the watch cancelling the reminder (same package+id) can't remove it. */
    private fun resultId(id: Long) = id.toInt() xor 0x20000000

    fun dismissReminder(c: Context, id: Long) = NotificationManagerCompat.from(c).cancel(id.toInt())

    fun cancel(c: Context, id: Long) {
        NotificationManagerCompat.from(c).cancel(id.toInt())
        NotificationManagerCompat.from(c).cancel(resultId(id))
    }
}
