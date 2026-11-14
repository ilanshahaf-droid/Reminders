package com.ilan.reminders

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Small home-screen widget: new note / new reminder in one tap. */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val imm = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun launch(code: Int, extra: String, flags: Int) = PendingIntent.getActivity(
            c, code, Intent(c, MainActivity::class.java).putExtra(extra, true).addFlags(flags), imm
        )
        for (id in ids) {
            val rv = RemoteViews(c.packageName, R.layout.note_widget)
            rv.setOnClickPendingIntent(R.id.nwNote, launch(201, "newNote", Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            rv.setOnClickPendingIntent(R.id.nwTask, launch(202, "new", Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            m.updateAppWidget(id, rv)
        }
    }
}
