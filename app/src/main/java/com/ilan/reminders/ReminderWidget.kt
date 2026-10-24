package com.ilan.reminders

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking

class ReminderWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = render(c, m, ids)

    companion object {
        suspend fun refreshNow(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, ReminderWidget::class.java))
            if (ids.isNotEmpty()) render(c, m, ids)
        }

        private fun render(c: Context, m: AppWidgetManager, ids: IntArray) {
            val imm = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(c, 100, Intent(c, MainActivity::class.java), imm)
            val add = PendingIntent.getActivity(
                c, 101,
                Intent(c, MainActivity::class.java).putExtra("new", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                imm
            )
            // template for list rows must be mutable so the fill-in intent can be merged
            val template = PendingIntent.getActivity(
                c, 102, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            for (id in ids) {
                val svc = Intent(c, WidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
                val rv = RemoteViews(c.packageName, R.layout.widget)
                rv.setRemoteAdapter(R.id.list, svc)
                rv.setEmptyView(R.id.list, R.id.empty)
                rv.setPendingIntentTemplate(R.id.list, template)
                rv.setOnClickPendingIntent(R.id.header, open)
                rv.setOnClickPendingIntent(R.id.add, add)
                m.updateAppWidget(id, rv)
            }
            m.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
    }
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val c: Context) : RemoteViewsFactory {
        var items: List<Task> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            items = runBlocking { AppDb.get(c).dao().active() }.sortedBy { it.triggerAt }
        }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget_item)
            val t = items.getOrNull(position) ?: return rv
            val overdue = t.repeat == "NONE" && t.triggerAt < System.currentTimeMillis()
            rv.setTextViewText(R.id.time, whenText(t.triggerAt))
            rv.setTextViewText(R.id.title, t.title)
            rv.setTextColor(R.id.time, if (overdue) 0xFFDC2626.toInt() else ContextCompat.getColor(c, R.color.widget_accent))
            rv.setOnClickFillInIntent(R.id.item, Intent())
            return rv
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.id ?: position.toLong()
        override fun hasStableIds() = true
    }
}
