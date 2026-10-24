package com.ilan.reminders

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val p = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { render(c, m, ids) } finally { p.finish() }
        }
    }

    companion object {
        private val ROWS = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)
        private val TIMES = intArrayOf(R.id.time1, R.id.time2, R.id.time3, R.id.time4)
        private val TITLES = intArrayOf(R.id.title1, R.id.title2, R.id.title3, R.id.title4)

        suspend fun refreshNow(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, ReminderWidget::class.java))
            if (ids.isNotEmpty()) render(c, m, ids)
        }

        private suspend fun render(c: Context, m: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val list = AppDb.get(c).dao().active().sortedBy { it.triggerAt }.take(4)
            val open = PendingIntent.getActivity(
                c, 100, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val add = PendingIntent.getActivity(
                c, 101,
                Intent(c, MainActivity::class.java).putExtra("new", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            for (id in ids) {
                val rv = RemoteViews(c.packageName, R.layout.widget)
                rv.setViewVisibility(R.id.empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
                for (i in 0..3) {
                    val t = list.getOrNull(i)
                    if (t == null) {
                        rv.setViewVisibility(ROWS[i], View.GONE)
                    } else {
                        rv.setViewVisibility(ROWS[i], View.VISIBLE)
                        rv.setTextViewText(TIMES[i], whenText(t.triggerAt))
                        rv.setTextViewText(TITLES[i], t.title)
                        val overdue = t.repeat == "NONE" && t.triggerAt < now
                        rv.setTextColor(
                            TIMES[i],
                            if (overdue) 0xFFDC2626.toInt() else ContextCompat.getColor(c, R.color.widget_accent)
                        )
                    }
                }
                rv.setOnClickPendingIntent(R.id.root, open)
                rv.setOnClickPendingIntent(R.id.add, add)
                m.updateAppWidget(id, rv)
            }
        }
    }
}
