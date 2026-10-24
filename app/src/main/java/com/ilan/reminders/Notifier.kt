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

    /** Android fixes sound and vibration per channel, so each combination gets its own channel. */
    private fun channelFor(c: Context, t: Task): String {
        val id = "rem_${t.vibration}_${if (t.sound.isBlank()) "def" else abs(t.sound.hashCode()).toString()}"
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
                t.sound == "silent" -> ch.setSound(null, null)
                t.sound.isBlank() -> ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
                else -> ch.setSound(Uri.parse(t.sound), attrs)
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

    fun show(c: Context, t: Task) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, channelFor(c, t))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t.title)
            .setContentText(t.notes.ifBlank { "תזכורת" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "בוצע", action(c, t, "DONE", 0, 1))
            .addAction(0, "דחה 10 דק׳", action(c, t, "SNOOZE", 10, 2))
            .addAction(0, "דחה שעה", action(c, t, "SNOOZE", 60, 3))
            .build()
        NotificationManagerCompat.from(c).notify(t.id.toInt(), n)
    }

    fun cancel(c: Context, id: Long) = NotificationManagerCompat.from(c).cancel(id.toInt())
}
