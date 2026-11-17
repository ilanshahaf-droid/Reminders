package com.ilan.reminders.wear

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray

data class WatchEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val loc: String, val color: Int, val rep: Boolean)
data class WatchNote(val id: Long, val title: String, val text: String, val pinned: Boolean) {
    val heading: String get() = title.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.ifBlank { "פתק" }
}

/** The diary (next days) and the notes last received from the phone. */
object WatchData {
    val events = mutableStateOf<List<WatchEvent>>(emptyList())
    val notes = mutableStateOf<List<WatchNote>>(emptyList())

    private fun p(c: Context) = c.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun updateAgenda(c: Context, json: String) { p(c).edit().putString("agenda", json).apply(); events.value = parseEvents(json) }
    fun updateNotes(c: Context, json: String) { p(c).edit().putString("notes", json).apply(); notes.value = parseNotes(json) }

    fun load(c: Context) {
        p(c).getString("agenda", null)?.let { events.value = parseEvents(it) }
        p(c).getString("notes", null)?.let { notes.value = parseNotes(it) }
    }

    private fun parseEvents(json: String): List<WatchEvent> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            WatchEvent(o.getLong("id"), o.optString("title"), o.getLong("b"), o.getLong("e"), o.optBoolean("ad"), o.optString("loc"), o.optInt("col"), o.optBoolean("rep"))
        }
    }.getOrDefault(emptyList())

    private fun parseNotes(json: String): List<WatchNote> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            WatchNote(o.getLong("id"), o.optString("title"), o.optString("text"), o.optBoolean("pin"))
        }
    }.getOrDefault(emptyList())
}
