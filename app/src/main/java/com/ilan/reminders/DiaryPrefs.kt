package com.ilan.reminders

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/** Display (font / colours) and new-event defaults of the diary; the display values are Compose state so the diary updates live. */
object DS {
    var scale by mutableStateOf(1f)
    var family by mutableStateOf("default")
    var evText by mutableStateOf(0)                    // 0 = use each calendar's own colour
    var bar by mutableStateOf(0xFF4A7391.toInt())
    var today by mutableStateOf(0xFFFB8C00.toInt())

    private fun p(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun load(c: Context) {
        val s = p(c)
        scale = s.getFloat("dsScale", 1f); family = s.getString("dsFamily", "default") ?: "default"
        evText = s.getInt("dsEvText", 0); bar = s.getInt("dsBar", 0xFF4A7391.toInt()); today = s.getInt("dsToday", 0xFFFB8C00.toInt())
    }

    fun save(c: Context) {
        p(c).edit().putFloat("dsScale", scale).putString("dsFamily", family).putInt("dsEvText", evText).putInt("dsBar", bar).putInt("dsToday", today).apply()
    }

    fun fontFamily(): FontFamily? = when (family) {
        "sans" -> FontFamily.SansSerif; "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive
        else -> null
    }

    // ---- new event defaults ----
    fun defStart(c: Context) = p(c).getInt("evDefStart", -1)          // minutes of day, -1 = the coming hour
    fun defDur(c: Context) = p(c).getInt("evDefDur", 60)
    fun defRem(c: Context) = p(c).getInt("evDefRem", 10)              // -2 = none
    fun defRemAll(c: Context) = p(c).getInt("evDefRemAll", -2)
    fun defAvail(c: Context) = p(c).getInt("evDefAvail", 0)
    fun defAccess(c: Context) = p(c).getInt("evDefAccess", 0)
    fun setInt(c: Context, k: String, v: Int) { p(c).edit().putInt(k, v).apply() }
}
