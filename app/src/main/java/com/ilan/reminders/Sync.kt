package com.ilan.reminders

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Two-way sync with Supabase: the phone is the master of alarms, the web page can view and edit. */
object Sync {
    fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun save(c: Context, url: String, key: String, email: String, pass: String) {
        prefs(c).edit()
            .putString("url", url.trim().trimEnd('/')).putString("key", key.trim())
            .putString("email", email.trim()).putString("pass", pass).apply()
        ensurePeriodic(c)
    }

    /** Background pull every 15 minutes (the minimum WorkManager allows). */
    fun ensurePeriodic(c: Context) {
        if (!configured(c)) return
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniquePeriodicWork("cloud_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Deleted tasks are remembered until the cloud copy has been marked as deleted. */
    fun markDeleted(c: Context, id: Long) {
        val p = prefs(c)
        val set = (p.getStringSet("pendingDel", emptySet()) ?: emptySet()).toMutableSet()
        set.add(id.toString())
        p.edit().putStringSet("pendingDel", set).apply()
        schedulePush(c)
    }
    private fun pendingDeleted(c: Context): Set<Long> =
        (prefs(c).getStringSet("pendingDel", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    private val mutex = Mutex()

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

    private fun fromJson(r: JSONObject) = Task(
        id = r.getLong("id"), title = r.optString("title"), notes = r.optString("notes"),
        categoryId = r.optLong("category_id"), triggerAt = r.optLong("trigger_at"),
        repeat = r.optString("repeat_mode", "NONE"), days = r.optInt("days"), times = r.optString("times"),
        sound = r.optString("sound"), vibration = r.optString("vibration", "STRONG"),
        done = r.optBoolean("done"), fired = r.optBoolean("fired"), snoozeUntil = r.optLong("snooze_until"),
        slots = r.optString("slots"), updatedAt = r.optLong("updated_at"),
        alertMode = r.optString("alert_mode", "ONCE")
    )

    private fun toJson(it: Task) = JSONObject().put("id", it.id).put("title", it.title).put("notes", it.notes)
        .put("category_id", it.categoryId).put("trigger_at", it.triggerAt)
        .put("repeat_mode", it.repeat).put("days", it.days).put("times", it.times)
        .put("slots", it.slots).put("sound", it.sound).put("vibration", it.vibration)
        .put("done", it.done).put("fired", it.fired).put("snooze_until", it.snoozeUntil)
        .put("updated_at", it.updatedAt).put("deleted", false)
        .put("alert_mode", it.alertMode)

    suspend fun syncNow(c: Context): String = withContext(Dispatchers.IO) { mutex.withLock {
        val p = prefs(c)
        val status = try {
            if (!configured(c)) return@withLock "חסרים פרטי חיבור"
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

            fun upsert(table: String, arr: JSONArray) {
                if (arr.length() == 0) return
                val hh = h + ("Prefer" to "resolution=merge-duplicates,return=minimal")
                var r = http("POST", "$base/rest/v1/$table?on_conflict=id", hh, arr.toString())
                if (r.first == 400 && r.second.contains("alert_mode")) {
                    // the SQL update that adds this column was not run yet: sync without it
                    for (i in 0 until arr.length()) arr.getJSONObject(i).remove("alert_mode")
                    r = http("POST", "$base/rest/v1/$table?on_conflict=id", hh, arr.toString())
                }
                if (r.first !in 200..299) error("$table: ${r.first} ${r.second.take(160)}")
            }

            // 1) pull: what changed on the web since the phone last touched each task
            val rr = http("GET", "$base/rest/v1/rem_tasks?select=*", h, null)
            if (rr.first !in 200..299) error("rem_tasks: ${rr.first} ${rr.second.take(160)}")
            val remote = JSONArray(rr.second)
            val pending = pendingDeleted(c)
            val local0 = dao.allNow().associateBy { it.id }
            val remoteUpdated = HashMap<Long, Long>()
            val remoteDeleted = HashSet<Long>()
            var changed = false
            for (i in 0 until remote.length()) {
                val r = remote.getJSONObject(i)
                val id = r.getLong("id")
                if (id in pending) continue
                val ru = r.optLong("updated_at")
                remoteUpdated[id] = ru
                val deleted = r.optBoolean("deleted", false)
                if (deleted) remoteDeleted.add(id)
                val l = local0[id]
                if (deleted) {
                    if (l != null && ru > l.updatedAt) { Reminders.deleteLocal(c, l); changed = true }
                } else if (l == null || ru > l.updatedAt) {
                    Reminders.applyRemote(c, fromJson(r)); changed = true
                }
            }

            // 2) push: everything the phone has that is not older than the cloud copy
            val tasks = dao.allNow()
            val cats = dao.categoriesNow()
            val taskArr = JSONArray()
            tasks.forEach { t ->
                val ru = remoteUpdated[t.id]
                if (ru == null || t.updatedAt >= ru) taskArr.put(toJson(t))
            }
            upsert("rem_tasks", taskArr)
            if (pending.isNotEmpty()) {
                val r = http("PATCH", "$base/rest/v1/rem_tasks?id=in.(${pending.joinToString(",")})", h + ("Prefer" to "return=minimal"),
                    JSONObject().put("deleted", true).put("updated_at", System.currentTimeMillis()).toString())
                if (r.first !in 200..299) error("rem_tasks delete: ${r.first} ${r.second.take(160)}")
                val left = (p.getStringSet("pendingDel", emptySet()) ?: emptySet()) - pending.map { it.toString() }.toSet()
                p.edit().putStringSet("pendingDel", left).apply()
            }

            // categories stay phone-managed (mirrored one way)
            val catArr = JSONArray()
            cats.forEach { catArr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
            upsert("rem_categories", catArr)
            val filter = if (cats.isEmpty()) "id=gt.0" else "id=not.in.(${cats.joinToString(",") { it.id.toString() }})"
            val d = http("DELETE", "$base/rest/v1/rem_categories?$filter", h, null)
            if (d.first !in 200..299) error("rem_categories delete: ${d.first} ${d.second.take(160)}")

            if (changed) {
                ReminderWidget.refreshNow(c)
                WearBridge.pushList(c)
            }
            "✅ סונכרן ב-" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            "❌ " + (e.message ?: e.javaClass.simpleName)
        }
        p.edit().putString("status", status).apply()
        status
    } }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        if (Sync.configured(applicationContext)) Sync.syncNow(applicationContext)
        return Result.success()
    }
}
