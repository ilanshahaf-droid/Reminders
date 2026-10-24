package com.ilan.reminders

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One-way mirror of the local database to Supabase, so the list can be viewed from a computer. */
object Sync {
    fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun save(c: Context, url: String, key: String, email: String, pass: String) {
        prefs(c).edit()
            .putString("url", url.trim().trimEnd('/')).putString("key", key.trim())
            .putString("email", email.trim()).putString("pass", pass).apply()
    }

    fun configured(c: Context) = listOf("url", "key", "email", "pass").all { !prefs(c).getString(it, "").isNullOrBlank() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Debounced background push after a change. Does nothing until the sync details are filled in. */
    fun schedulePush(c: Context) {
        if (!configured(c)) return
        val app = c.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(3000)
            syncNow(app)
        }
    }

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    suspend fun syncNow(c: Context): String = withContext(Dispatchers.IO) {
        val p = prefs(c)
        val status = try {
            if (!configured(c)) return@withContext "חסרים פרטי חיבור"
            val base = p.getString("url", "")!!
            val key = p.getString("key", "")!!
            val login = http(
                "POST", "$base/auth/v1/token?grant_type=password",
                mapOf("apikey" to key, "Content-Type" to "application/json"),
                JSONObject().put("email", p.getString("email", "")).put("password", p.getString("pass", "")).toString()
            )
            if (login.first !in 200..299) error("התחברות נכשלה (${login.first}). בדוק אימייל וסיסמה")
            val token = JSONObject(login.second).getString("access_token")
            val h = mapOf("apikey" to key, "Authorization" to "Bearer $token", "Content-Type" to "application/json")
            val dao = AppDb.get(c).dao()
            val tasks = dao.allNow()
            val cats = dao.categoriesNow()

            fun upsert(table: String, arr: JSONArray) {
                if (arr.length() == 0) return
                val r = http("POST", "$base/rest/v1/$table?on_conflict=id",
                    h + ("Prefer" to "resolution=merge-duplicates,return=minimal"), arr.toString())
                if (r.first !in 200..299) error("$table: ${r.first} ${r.second.take(160)}")
            }
            fun prune(table: String, ids: List<Long>) {
                val filter = if (ids.isEmpty()) "id=gt.0" else "id=not.in.(${ids.joinToString(",")})"
                val r = http("DELETE", "$base/rest/v1/$table?$filter", h, null)
                if (r.first !in 200..299) error("$table delete: ${r.first} ${r.second.take(160)}")
            }

            val catArr = JSONArray()
            cats.forEach { catArr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
            val taskArr = JSONArray()
            tasks.forEach {
                taskArr.put(
                    JSONObject().put("id", it.id).put("title", it.title).put("notes", it.notes)
                        .put("category_id", it.categoryId).put("trigger_at", it.triggerAt)
                        .put("repeat_mode", it.repeat).put("days", it.days).put("times", it.times)
                        .put("slots", it.slots).put("sound", it.sound).put("vibration", it.vibration)
                        .put("done", it.done).put("fired", it.fired).put("snooze_until", it.snoozeUntil)
                )
            }
            upsert("rem_categories", catArr)
            upsert("rem_tasks", taskArr)
            prune("rem_categories", cats.map { it.id })
            prune("rem_tasks", tasks.map { it.id })
            "✅ סונכרן ב-" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            "❌ " + (e.message ?: e.javaClass.simpleName)
        }
        p.edit().putString("status", status).apply()
        status
    }
}
