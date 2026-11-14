package com.ilan.reminders

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One zip with reminders, notes, categories and the documents. Written to / read from any place the system file picker offers (Drive, OneDrive, device). */
object Backup {
    suspend fun export(c: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDb.get(c)
            val tasks = JSONArray().also { a -> db.dao().allNow().filter { it.deletedAt == 0L }.forEach { a.put(Sync.toJson(it)) } }
            val notes = JSONArray().also { a -> db.notes().allNow().filter { it.deletedAt == 0L }.forEach { a.put(Sync.noteToJson(it)) } }
            val cats = JSONArray().also { a -> db.dao().categoriesNow().forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) } }
            val docs = Docs.list(c)
            c.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                ZipOutputStream(os.buffered()).use { z ->
                    z.putNextEntry(ZipEntry("data.json"))
                    z.write(JSONObject().put("app", "seder-yom").put("version", 1).put("time", System.currentTimeMillis())
                        .put("tasks", tasks).put("notes", notes).put("categories", cats).toString().toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                    docs.forEach { f -> z.putNextEntry(ZipEntry("docs/" + f.name).also { it.time = f.lastModified() }); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                }
            }
            "הגיבוי נשמר: ${tasks.length()} תזכורות, ${notes.length()} פתקים, ${docs.size} מסמכים"
        }.getOrElse { "הגיבוי נכשל: " + (it.message ?: it.javaClass.simpleName) }
    }

    /** Adds what is missing and takes the backup copy only when it is newer. Nothing on the phone is deleted. */
    suspend fun restore(c: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDb.get(c)
            var json: JSONObject? = null
            val dir = Docs.dir(c)
            var docsBack = 0
            c.contentResolver.openInputStream(uri)!!.use { ins ->
                ZipInputStream(ins.buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        if (e.name == "data.json") json = JSONObject(String(z.readBytes(), Charsets.UTF_8))
                        else if (e.name.startsWith("docs/") && !e.isDirectory) {
                            val name = e.name.removePrefix("docs/").substringAfterLast('/')
                            val f = File(dir, name)
                            if (name.isNotBlank() && (!f.exists() || (e.time > f.lastModified() + 2000))) {
                                f.writeBytes(z.readBytes()); if (e.time > 0) f.setLastModified(e.time); docsBack++
                            }
                        }
                        e = z.nextEntry
                    }
                }
            }
            val j = json ?: error("זה לא קובץ גיבוי של האפליקציה")
            val catIds = db.dao().categoriesNow().map { it.id }.toSet()
            j.optJSONArray("categories")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { r ->
                    if (r.getLong("id") !in catIds) db.dao().addCategory(Category(r.getLong("id"), r.optString("name"), r.optLong("color")))
                }
            }
            val localT = db.dao().allNow().associateBy { it.id }
            var t = 0
            j.optJSONArray("tasks")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = Sync.fromJson(a.getJSONObject(i)); val l = localT[r.id]
                    if (l == null || (l.deletedAt == 0L && r.updatedAt > l.updatedAt)) { Reminders.applyRemote(c, r); t++ }
                }
            }
            val localN = db.notes().allNow().associateBy { it.id }
            var n = 0
            j.optJSONArray("notes")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = Sync.noteFromJson(a.getJSONObject(i)); val l = localN[r.id]
                    if (l == null || r.updatedAt > l.updatedAt) { db.notes().upsert(r); n++ }
                }
            }
            ReminderWidget.refreshNow(c); WearBridge.pushList(c); Sync.schedulePush(c)
            "שוחזרו: $t תזכורות, $n פתקים, $docsBack מסמכים"
        }.getOrElse { "השחזור נכשל: " + (it.message ?: it.javaClass.simpleName) }
    }
}
