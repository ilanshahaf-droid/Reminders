package com.ilan.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A document linked to a reminder or a calendar event: a file kept in the app's Documents tab (kind "doc", by file name)
 * or a web link, e.g. Google Drive / OneDrive (kind "url"). At most 3 per item. Stored as JSON text.
 */
data class DocLink(val kind: String, val name: String, val url: String = "") {
    val label: String get() = name.ifBlank { url.removePrefix("https://").removePrefix("http://").take(40) }
    val icon: String get() = if (kind == "doc") (when (name.substringAfterLast('.', "").lowercase()) { "pdf" -> "📕"; "xlsx" -> "📗"; "pptx" -> "📙"; else -> "📘" }) else "🔗"
}

private val DOC_MIMES = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation")

object DocLinks {
    const val MAX = 3

    fun parse(s: String?): List<DocLink> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val k = o.optString("t"); val n = o.optString("n"); val u = o.optString("u")
            if (k == "doc" && n.isNotBlank()) DocLink("doc", n) else if (k == "url" && u.isNotBlank()) DocLink("url", n, u) else null
        }.take(MAX)
    }.getOrDefault(emptyList())

    fun encode(l: List<DocLink>): String = if (l.isEmpty()) "" else JSONArray().also { a ->
        l.take(MAX).forEach { a.put(JSONObject().put("t", it.kind).put("n", it.name).apply { if (it.kind == "url") put("u", it.url) }) }
    }.toString()

    // ---- links of calendar events (the phone calendar has no such field, so they live in the app) ----
    private fun p(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    private fun idx(c: Context) = runCatching { JSONObject(p(c).getString("evDocs", "{}") ?: "{}") }.getOrDefault(JSONObject())
    fun forEvent(c: Context, id: Long): List<DocLink> = parse(idx(c).optString(id.toString(), ""))
    fun setEvent(c: Context, id: Long, l: List<DocLink>) {
        val o = idx(c); val e = encode(l)
        if (e.isEmpty()) o.remove(id.toString()) else o.put(id.toString(), e)
        p(c).edit().putString("evDocs", o.toString()).apply()
    }
    fun eventRaw(c: Context, id: Long): String = idx(c).optString(id.toString(), "")

    /** Opens a linked document: an app document in the Documents tab, a web link in the browser / Drive / OneDrive app. */
    fun open(c: Context, l: DocLink) {
        if (l.kind == "doc") {
            val f = File(Docs.dir(c), l.name)
            if (f.exists()) PendingOpen.file = f
            else Toast.makeText(c, "המסמך \"${l.name}\" לא נמצא באפליקציה", Toast.LENGTH_LONG).show()
        } else {
            val uri = Uri.parse(l.url)
            val host = (uri.host ?: "").lowercase()
            // The OneDrive app often fails to download a shared link ("problem downloading the file"); the browser opens it reliably.
            val oneDrive = host == "1drv.ms" || host.endsWith("onedrive.live.com") || host.endsWith("sharepoint.com") || host.endsWith("onedrive.com")
            val ok = oneDrive && runCatching {
                val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(i)
            }.isSuccess
            if (!ok) runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(c, "לא ניתן לפתוח את הקישור", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun nameOf(c: Context, u: Uri): String {
        var n = "מסמך"
        runCatching { c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) n = it.getString(0) ?: n } }
        return n
    }

    /** Copies a file picked on the phone into the app's documents (so it also syncs to the web) and returns its name. */
    fun importPicked(c: Context, u: Uri): String? = runCatching {
        var name = nameOf(c, u)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("docx", "pdf", "xlsx", "pptx")) return null
        var f = File(Docs.dir(c), name)
        var k = 1
        while (f.exists()) { name = name.substringBeforeLast('.') + " ($k)." + ext; f = File(Docs.dir(c), name); k++ }
        c.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return null
        name
    }.getOrNull()
}

/** The "linked documents" block of the reminder / event editor: chips with ✕, and an add button (up to 3). */
@Composable
fun DocLinksField(links: SnapshotStateList<DocLink>) {
    val ctx = LocalContext.current
    var dlg by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("📎 מסמכים מקושרים", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        links.toList().forEachIndexed { i, l ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F3F6)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(l.icon + " " + l.label, Modifier.weight(1f).clickable { DocLinks.open(ctx, l) }.padding(vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                TextButton({ links.removeAt(i) }) { Text("✕", color = Color(0xFFD32F2F)) }
            }
        }
        if (links.size < DocLinks.MAX) OutlinedButton({ dlg = true }, Modifier.fillMaxWidth()) { Text("＋ צרף מסמך (${links.size}/${DocLinks.MAX})") }
    }
    if (dlg) AddDocLinkDialog({ dlg = false }) { links.add(it); dlg = false }
}

@Composable
private fun AddDocLinkDialog(onDismiss: () -> Unit, onPick: (DocLink) -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf("menu") }
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            val n = DocLinks.importPicked(ctx, u)
            if (n != null) onPick(DocLink("doc", n)) else Toast.makeText(ctx, "אפשר לצרף קובצי Word, PDF, Excel ו-PowerPoint", Toast.LENGTH_LONG).show()
        }
    }
    // the cloud apps' own pickers (Drive / OneDrive): the file is chosen inside the app and copied here
    val appPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.data
        if (r.resultCode == android.app.Activity.RESULT_OK && u != null) {
            val n = DocLinks.importPicked(ctx, u)
            if (n != null) onPick(DocLink("doc", n)) else Toast.makeText(ctx, "אפשר לצרף קובצי Word, PDF, Excel ו-PowerPoint", Toast.LENGTH_LONG).show()
        }
    }
    fun installed(pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    fun fromApp(pkg: String) {
        runCatching {
            appPicker.launch(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, DOC_MIMES).setPackage(pkg))
        }.onFailure { Toast.makeText(ctx, "האפליקציה לא מאפשרת בחירת קובץ מכאן. נסה את הבחירה דרך בוחר המערכת", Toast.LENGTH_LONG).show() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (mode) { "app" -> "מסמך מהאפליקציה"; "url" -> "קישור (Drive / OneDrive)"; else -> "צירוף מסמך" }) },
        text = {
            when (mode) {
                "app" -> {
                    val docs = remember { Docs.list(ctx) }
                    Column(Modifier.heightIn(max = 360.dp).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (docs.isEmpty()) Text("אין מסמכים באפליקציה", color = Color.Gray)
                        docs.forEach { f ->
                            val l = DocLink("doc", f.name)
                            Text(l.icon + " " + f.name, Modifier.fillMaxWidth().clickable { onPick(l) }.padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                "url" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("הדבק קישור לקובץ ב-Google Drive או ב-OneDrive (שיתוף ← העתק קישור).", fontSize = 13.sp, color = Color.Gray)
                    OutlinedTextField(url, { url = it }, label = { Text("קישור") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(label, { label = it }, label = { Text("שם להצגה (לא חובה)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val opts = buildList {
                        add("📂 מסמך מהאפליקציה" to "app")
                        if (installed("com.google.android.apps.docs")) add("🟢 מאפליקציית Google Drive" to "gdrive")
                        if (installed("com.microsoft.skydrive")) add("🔵 מאפליקציית OneDrive" to "onedrive")
                        add("📱 בוחר המערכת (הטלפון / Drive / OneDrive)" to "file")
                        add("🔗 הדבקת קישור (פתיחה בדפדפן)" to "url")
                    }
                    opts.forEach { (t, m) ->
                        Text(t, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F3F6)).clickable {
                            when (m) {
                                "file" -> picker.launch(DOC_MIMES + "application/octet-stream")
                                "gdrive" -> fromApp("com.google.android.apps.docs")
                                "onedrive" -> fromApp("com.microsoft.skydrive")
                                else -> mode = m
                            }
                        }.padding(14.dp), fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (mode == "url") TextButton(enabled = url.trim().startsWith("http", true), onClick = { onPick(DocLink("url", label.trim(), url.trim())) }) { Text("הוסף") }
        },
        dismissButton = { TextButton({ if (mode == "menu") onDismiss() else mode = "menu" }) { Text(if (mode == "menu") "ביטול" else "חזרה") } }
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

/** Tappable chips for the linked documents (card / popup display). */
@Composable
fun DocLinkChips(links: List<DocLink>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (links.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        links.forEach { l ->
            Text(l.icon + " " + l.label, Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFE8EAF6)).clickable { DocLinks.open(ctx, l) }.padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF283593))
        }
    }
}
