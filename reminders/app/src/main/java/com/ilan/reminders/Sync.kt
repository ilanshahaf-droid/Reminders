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
    /** A task restored from the recycle bin must not be marked as deleted in the cloud any more. */
    fun unmarkDeleted(c: Context, id: Long) {
        val p = prefs(c)
        val set = (p.getStringSet("pendingDel", emptySet()) ?: emptySet()).toMutableSet()
        set.remove(id.toString())
        p.edit().putStringSet("pendingDel", set).apply()
    }
    private fun pendingDeleted(c: Context): Set<Long> =
        (prefs(c).getStringSet("pendingDel", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    private val mutex = Mutex()

    fun configured(c: Context): Boolean {
        val p = prefs(c)
        fun has(k: String) = !p.getString(k, "").isNullOrBlank()
        return has("url") && has("key") && (has("refresh") || (has("email") && has("pass")))
    }

    fun signedInWithGoogle(c: Context) = !prefs(c).getString("refresh", "").isNullOrBlank()
    fun signOut(c: Context) { prefs(c).edit().remove("refresh").remove("account").apply() }

    private fun b64url(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    /** Opens Google sign-in through Supabase (PKCE). The browser returns to com.ilan.reminders://auth?code=... */
    fun startGoogle(c: Context, url: String, key: String) {
        save(c, url, key, prefs(c).getString("email", "") ?: "", prefs(c).getString("pass", "") ?: "")
        val verifier = b64url(java.security.SecureRandom().let { r -> ByteArray(48).also { r.nextBytes(it) } })
        val challenge = b64url(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        prefs(c).edit().putString("verifier", verifier).apply()
        val base = prefs(c).getString("url", "")!!
        val u = "$base/auth/v1/authorize?provider=google&redirect_to=" + java.net.URLEncoder.encode("com.ilan.reminders://auth", "UTF-8") +
            "&code_challenge=$challenge&code_challenge_method=s256"
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    /** Called when the browser comes back with the one-time code. */
    suspend fun finishGoogle(c: Context, code: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        val p = prefs(c)
        try {
            val verifier = p.getString("verifier", "") ?: ""
            if (verifier.isBlank()) error("בקשת ההתחברות לא נמצאה, נסה שוב")
            val r = http(
                "POST", p.getString("url", "") + "/auth/v1/token?grant_type=pkce",
                mapOf("apikey" to (p.getString("key", "") ?: ""), "Content-Type" to "application/json"),
                JSONObject().put("auth_code", code).put("code_verifier", verifier).toString()
            )
            if (r.first !in 200..299) error("התחברות עם Google נכשלה (${r.first}) ${r.second.take(120)}")
            val j = JSONObject(r.second)
            p.edit().putString("refresh", j.getString("refresh_token")).remove("verifier")
                .putString("account", j.optJSONObject("user")?.optString("email") ?: "").apply()
            ensurePeriodic(c)
            "ok"
        } catch (e: Exception) { "❌ " + (e.message ?: e.javaClass.simpleName) }
    } }

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
        alertMode = r.optString("alert_mode", "ONCE"), important = r.optBoolean("important", false),
        every = r.optInt("every_n", 1).coerceAtLeast(1), anchor = r.optLong("anchor", 0), listName = r.optString("list_name", "")
    )

    private fun toJson(it: Task) = JSONObject().put("id", it.id).put("title", it.title).put("notes", it.notes)
        .put("category_id", it.categoryId).put("trigger_at", it.triggerAt)
        .put("repeat_mode", it.repeat).put("days", it.days).put("times", it.times)
        .put("slots", it.slots).put("sound", it.sound).put("vibration", it.vibration)
        .put("done", it.done).put("fired", it.fired).put("snooze_until", it.snoozeUntil)
        .put("updated_at", it.updatedAt).put("deleted", false)
        .put("alert_mode", it.alertMode).put("important", it.important)
        .put("every_n", it.every).put("anchor", it.anchor).put("list_name", it.listName)

    suspend fun syncNow(c: Context): String = withContext(Dispatchers.IO) { mutex.withLock {
        val p = prefs(c)
        val status = try {
            if (!configured(c)) return@withLock "חסרים פרטי חיבור"
            val base = p.getString("url", "")!!
            val key = p.getString("key", "")!!
            val refresh = p.getString("refresh", "") ?: ""
            val login = if (refresh.isNotBlank()) http(
                "POST", "$base/auth/v1/token?grant_type=refresh_token",
                mapOf("apikey" to key, "Content-Type" to "application/json"),
                JSONObject().put("refresh_token", refresh).toString()
            ) else http(
                "POST", "$base/auth/v1/token?grant_type=password",
                mapOf("apikey" to key, "Content-Type" to "application/json"),
                JSONObject().put("email", p.getString("email", "")).put("password", p.getString("pass", "")).toString()
            )
            if (login.first !in 200..299) {
                if (refresh.isNotBlank()) {
                    if (login.first in 400..401) p.edit().remove("refresh").apply()
                    error("ההתחברות פגה. התחבר שוב עם Google")
                }
                error("התחברות נכשלה (${login.first}). בדוק אימייל וסיסמה")
            }
            val lj = JSONObject(login.second)
            if (refresh.isNotBlank()) lj.optString("refresh_token").takeIf { it.isNotBlank() }?.let { p.edit().putString("refresh", it).apply() }
            val token = lj.getString("access_token")
            val h = mapOf("apikey" to key, "Authorization" to "Bearer $token", "Content-Type" to "application/json")
            val dao = AppDb.get(c).dao()

            fun upsert(table: String, arr: JSONArray) {
                if (arr.length() == 0) return
                val hh = h + ("Prefer" to "resolution=merge-duplicates,return=minimal")
                var r = http("POST", "$base/rest/v1/$table?on_conflict=id", hh, arr.toString())
                for (col in listOf("list_name", "every_n", "anchor", "important", "alert_mode")) {
                    if (r.first == 400 && r.second.contains(col)) {
                        // the SQL update that adds this column was not run yet: sync without it
                        for (i in 0 until arr.length()) arr.getJSONObject(i).remove(col)
                        r = http("POST", "$base/rest/v1/$table?on_conflict=id", hh, arr.toString())
                    }
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
                    if (l != null && l.deletedAt == 0L && ru > l.updatedAt) { Reminders.deleteLocal(c, l); changed = true }
                } else if (l == null || ru > l.updatedAt) {
                    Reminders.applyRemote(c, fromJson(r)); changed = true
                }
            }

            // 2) push: everything the phone has that is not older than the cloud copy
            val tasks = dao.allNow().filter { it.deletedAt == 0L }   // the recycle bin stays on the phone
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
