package com.ilan.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }
}

object Notifier {
    private const val CH = "reminders_v1"

    fun ensureChannel(c: Context) {
        val ch = NotificationChannel(CH, "תזכורות", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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
        ensureChannel(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
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
