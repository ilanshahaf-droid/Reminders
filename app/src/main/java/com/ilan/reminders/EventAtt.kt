package com.ilan.reminders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Images attached to calendar events. The phone calendar has no attachments, so they live in the app (private storage). */
class Att(val file: String, val name: String, val del: Boolean, val after: Long)

object EventAtt {
    private fun p(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    private fun idx(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("evAtt", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun save(c: Context, o: JSONObject) = p(c).edit().putString("evAtt", o.toString()).apply()
    fun dir(c: Context, id: Long) = File(c.filesDir, "evatt/$id")

    fun list(c: Context, id: Long): List<Att> {
        val a = idx(c).optJSONArray(id.toString()) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val f = o.optString("file"); if (!File(f).exists()) null else Att(f, o.optString("name"), o.optBoolean("del"), o.optLong("after"))
        }
    }

    fun ids(c: Context): Set<Long> = idx(c).keys().asSequence().mapNotNull { it.toLongOrNull() }.filter { list(c, it).isNotEmpty() }.toSet()

    fun nameOf(c: Context, u: Uri): String {
        var n = "תמונה"
        runCatching { c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) n = it.getString(0) ?: n } }
        return n
    }

    /** items: either a stored file path or a freshly picked uri */
    class Item(val file: String?, val uri: Uri?, val name: String, var del: Boolean)

    fun commit(c: Context, id: Long, items: List<Item>, after: Long) {
        val d = dir(c, id); d.mkdirs()
        val keep = HashSet<String>()
        val out = JSONArray()
        for (it in items) {
            var target: File? = null
            if (it.file != null && File(it.file).parentFile?.absolutePath == d.absolutePath) target = File(it.file)
            else {
                val t = File(d, System.currentTimeMillis().toString() + "_" + out.length() + "_" + it.name.replace(Regex("[^\\w.\\-]"), "_").takeLast(40))
                runCatching {
                    if (it.file != null) File(it.file).copyTo(t, true)
                    else c.contentResolver.openInputStream(it.uri!!)?.use { i -> t.outputStream().use { o -> i.copyTo(o) } }
                    target = t
                }
            }
            val f = target ?: continue
            keep.add(f.absolutePath)
            out.put(JSONObject().put("file", f.absolutePath).put("name", it.name).put("del", it.del).put("after", after))
        }
        d.listFiles()?.forEach { f -> if (f.absolutePath !in keep) f.delete() }
        val o = idx(c)
        if (out.length() == 0) o.remove(id.toString()) else o.put(id.toString(), out)
        save(c, o)
    }

    fun removeAll(c: Context, id: Long) {
        dir(c, id).deleteRecursively()
        val o = idx(c); o.remove(id.toString()); save(c, o)
    }

    /** removes images marked "delete after the event" once the event is over */
    fun purge(c: Context) {
        val now = System.currentTimeMillis()
        val o = idx(c); var changed = false
        for (k in o.keys().asSequence().toList()) {
            val a = o.optJSONArray(k) ?: continue
            val n = JSONArray()
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                if (e.optBoolean("del") && e.optLong("after") in 1 until now) { File(e.optString("file")).delete(); changed = true } else n.put(e)
            }
            if (n.length() != a.length()) { if (n.length() == 0) o.remove(k) else o.put(k, n) }
        }
        if (changed) save(c, o)
    }

    fun thumb(c: Context, f: String?, u: Uri?, max: Int): Bitmap? = runCatching {
        fun opts(w: Int, h: Int) = BitmapFactory.Options().apply { var s = 1; while (w / (s * 2) >= max && h / (s * 2) >= max) s *= 2; inSampleSize = s }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (f != null) BitmapFactory.decodeFile(f, b) else c.contentResolver.openInputStream(u!!)?.use { BitmapFactory.decodeStream(it, null, b) }
        val o = opts(b.outWidth, b.outHeight)
        if (f != null) BitmapFactory.decodeFile(f, o) else c.contentResolver.openInputStream(u!!)?.use { BitmapFactory.decodeStream(it, null, o) }
    }.getOrNull()
}
