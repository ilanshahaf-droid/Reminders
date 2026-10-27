package com.ilan.reminders.wear

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray

data class WatchTask(val id: Long, val title: String, val whenText: String, val rec: String, val overdue: Boolean, val snoozeUntil: Long = 0, val at: Long = 0)

/** Last task list received from the phone, kept in memory and in SharedPreferences. */
object TaskStore {
    val tasks = mutableStateOf<List<WatchTask>>(emptyList())

    fun update(c: Context, json: String) {
        c.getSharedPreferences("watch", Context.MODE_PRIVATE).edit().putString("tasks", json).apply()
        tasks.value = parse(json)
    }

    fun load(c: Context) {
        val j = c.getSharedPreferences("watch", Context.MODE_PRIVATE).getString("tasks", null)
        if (j != null) tasks.value = parse(j)
    }

    private fun parse(json: String): List<WatchTask> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            WatchTask(o.getLong("id"), o.getString("title"), o.getString("when"), o.optString("rec"), o.optBoolean("overdue"), o.optLong("snoozeUntil"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())
}
