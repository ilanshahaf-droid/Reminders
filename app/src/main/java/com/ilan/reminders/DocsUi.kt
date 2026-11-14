package com.ilan.reminders

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Files of the "Documents" tab live in the app's private folder (local to this phone). */
object Docs {
    fun dir(c: Context): File = File(c.filesDir, "docs").apply { mkdirs() }
    fun ext(f: File) = f.extension.lowercase()
    private fun prefs(c: Context) = c.getSharedPreferences("docs", Context.MODE_PRIVATE)
    fun favs(c: Context): Set<String> = prefs(c).getStringSet("favs", emptySet()) ?: emptySet()
    fun isFav(c: Context, f: File) = f.name in favs(c)
    fun setFav(c: Context, name: String, on: Boolean) {
        val n = favs(c).toMutableSet()
        if (on) n.add(name) else n.remove(name)
        prefs(c).edit().putStringSet("favs", n).apply()
    }
    fun renameFav(c: Context, from: String, to: String) {
        if (from in favs(c)) { setFav(c, from, false); setFav(c, to, true) }
    }
    fun pageView(c: Context) = prefs(c).getBoolean("pageView", false)
    fun setPageView(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pageView", on).apply()

    /** Favorites always first, then newest. */
    fun list(c: Context): List<File> {
        val fv = favs(c)
        return dir(c).listFiles()?.filter { it.isFile && ext(it) in setOf("docx", "pdf") }
            ?.sortedWith(compareByDescending<File> { it.name in fv }.thenByDescending { it.lastModified() }) ?: emptyList()
    }

    fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().ifBlank { "מסמך" }.take(80)

    fun unique(c: Context, base: String, ext: String): File {
        val b = clean(base)
        var f = File(dir(c), "$b.$ext")
        var i = 2
        while (f.exists()) { f = File(dir(c), "$b ($i).$ext"); i++ }
        return f
    }

    @Volatile var lastError = ""
    fun import(c: Context, uri: Uri): File? = runCatching {
        lastError = ""
        var name = "מסמך"
        c.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = it.getString(i) ?: name
            }
        }
        val ext = name.substringAfterLast('.', "").lowercase().ifBlank {
            if (c.contentResolver.getType(uri)?.contains("pdf") == true) "pdf" else "docx"
        }
        if (ext !in setOf("docx", "pdf")) { lastError = "סוג קובץ לא נתמך ($name)"; return null }
        val same = File(dir(c), clean(name.substringBeforeLast('.')) + "." + ext)
        val size = runCatching { c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (same.exists() && size > 0 && same.length() == size) return same.also { it.setLastModified(System.currentTimeMillis()) }
        val out = unique(c, name.substringBeforeLast('.'), ext)
        c.contentResolver.openInputStream(uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } }
        out
    }.onFailure { lastError = (it::class.java.simpleName + ": " + (it.message ?: "")).take(120) }.getOrNull()

    // ---- cloud links (Google Drive / OneDrive via the system file picker) ----
    private fun linksJson(c: Context) = runCatching { JSONObject(prefs(c).getString("links", "{}") ?: "{}") }.getOrElse { JSONObject() }
    private fun saveLinks(c: Context, j: JSONObject) = prefs(c).edit().putString("links", j.toString()).apply()
    fun linkOf(c: Context, f: File): Uri? = linksJson(c).optJSONObject(f.name)?.optString("u")?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    fun isLinked(c: Context, f: File) = linkOf(c, f) != null
    @Synchronized fun setLink(c: Context, f: File, uri: Uri, mod: Long) {
        val j = linksJson(c); j.put(f.name, JSONObject().put("u", uri.toString()).put("m", mod)); saveLinks(c, j)
    }
    @Synchronized fun unlink(c: Context, name: String) { val j = linksJson(c); j.remove(name); saveLinks(c, j) }
    @Synchronized fun renameLink(c: Context, from: String, to: String) {
        val j = linksJson(c); val o = j.optJSONObject(from) ?: return
        j.remove(from); j.put(to, o); saveLinks(c, j)
    }
    private fun recMod(c: Context, f: File) = linksJson(c).optJSONObject(f.name)?.optLong("m", 0L) ?: 0L
    fun cloudMod(c: Context, uri: Uri): Long = runCatching {
        c.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L

    /** Refresh the local copy from the cloud file when it changed elsewhere. Returns false if the cloud could not be read. */
    fun pull(c: Context, f: File): Boolean {
        val u = linkOf(c, f) ?: return true
        return runCatching {
            val m = cloudMod(c, u)
            if (m == 0L || m != recMod(c, f)) {
                val tmp = File(f.parentFile, f.name + ".dl")
                c.contentResolver.openInputStream(u)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } ?: error("no stream")
                if (tmp.length() > 0) { f.writeBytes(tmp.readBytes()); setLink(c, f, u, m) }
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** Write the local file back to its cloud file (in the background). */
    fun push(c: Context, f: File) {
        val u = linkOf(c, f) ?: return
        val app = c.applicationContext
        Thread {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val now = cloudMod(app, u); val rec = recMod(app, f)
                if (now > 0 && rec > 0 && now != rec) {
                    h.post { Toast.makeText(app, "הקובץ בענן השתנה במקום אחר – השינויים נשמרו רק בטלפון. השתמש ב\"שמור בענן\" כדי לדרוס.", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                app.contentResolver.openOutputStream(u, "wt")?.use { it.write(f.readBytes()) } ?: error("no stream")
                setLink(app, f, u, cloudMod(app, u))
                h.post { Toast.makeText(app, "נשמר גם בענן ☁️", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                h.post { Toast.makeText(app, "השמירה בענן נכשלה – נשמר בטלפון", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Overwrite cloud with the local copy, ignoring a conflict. */
    fun forcePush(c: Context, f: File) { val u = linkOf(c, f) ?: return; setLink(c, f, u, cloudMod(c, u)); push(c, f) }

    /** True when the app got a lasting WRITE permission, i.e. saving back to the cloud will work. */
    fun keep(c: Context, uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { c.contentResolver.takePersistableUriPermission(uri, rw) }.isSuccess) return true
        runCatching { c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return false
    }

    @Volatile var lastLinked = false

    /** Open a cloud file: keep a local working copy linked to it. */
    fun importLinked(c: Context, uri: Uri): File? {
        lastLinked = keep(c, uri)
        if (!lastLinked) return import(c, uri)
        val j = linksJson(c)
        j.keys().forEach { k ->
            if (j.getJSONObject(k).optString("u") == uri.toString()) {
                val f = File(dir(c), k)
                if (f.exists()) { pull(c, f); f.setLastModified(System.currentTimeMillis()); return f }
            }
        }
        val f = import(c, uri) ?: return null
        setLink(c, f, uri, cloudMod(c, uri))
        return f
    }

    /** Create/choose a cloud file and copy the local file into it, linking them. */
    fun linkTo(c: Context, f: File, uri: Uri): Boolean {
        keep(c, uri)
        return runCatching {
            c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(f.readBytes()) } ?: error("no stream")
            setLink(c, f, uri, cloudMod(c, uri)); true
        }.getOrDefault(false)
    }

    fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
    fun mime(f: File) =
        if (ext(f) == "pdf") "application/pdf" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun share(c: Context, f: File) {
        c.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uri(c, f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null
            )
        )
    }

    fun openExternal(c: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(c, f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { c.startActivity(Intent.createChooser(i, null)) }
            .onFailure { Toast.makeText(c, "אין אפליקציה לפתיחת הקובץ", Toast.LENGTH_SHORT).show() }
    }
}



/** "Save as": pick a name and where to save a copy (Google Drive / OneDrive / this device / inside the app). */
@Composable
fun SaveAsDialog(file: File, onDismiss: () -> Unit, onSavedLocal: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(file.nameWithoutExtension) }
    var mode by remember { mutableStateOf("") }
    val ext = Docs.ext(file)
    fun fname() = Docs.clean(name) + "." + ext

    val create = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        if (mode == "drive") {
                            val copy = Docs.unique(ctx, name, ext)
                            file.copyTo(copy, overwrite = true)
                            Docs.linkTo(ctx, copy, uri)
                        } else {
                            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.readBytes()) } ?: error("no stream")
                            true
                        }
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    ctx, if (!ok) "השמירה נכשלה" else if (mode == "drive") "נשמר ב-Drive ☁️ (נוסף גם לרשימה כקובץ מקושר)" else "נשמר במכשיר", Toast.LENGTH_LONG
                ).show()
                if (ok) { onSavedLocal(); onDismiss() }
            }
        }
    }
    fun launchCreate(m: String, initial: Uri?) {
        mode = m
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(Docs.mime(file))
            .putExtra(Intent.EXTRA_TITLE, fname())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (initial != null) runCatching { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial) }
        runCatching { create.launch(i) }.onFailure { Toast.makeText(ctx, "לא ניתן לפתוח את בורר השמירה", Toast.LENGTH_SHORT).show() }
    }

    fun sendTo(pkg: String, label: String) {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, fname())
        runCatching {
            file.copyTo(f, overwrite = true)
            val i = Intent(Intent.ACTION_SEND).setType(Docs.mime(file))
                .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                .putExtra(Intent.EXTRA_TITLE, fname()).putExtra(Intent.EXTRA_SUBJECT, fname())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setPackage(pkg)
            ctx.startActivity(i)
            onDismiss()
        }.onFailure { Toast.makeText(ctx, "$label לא מותקן או לא מקבל קבצים", Toast.LENGTH_SHORT).show() }
    }

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("שמירה בשם") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("שם הקובץ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("שמור ב:", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ sendTo("com.google.android.apps.docs", "Google Drive") }, Modifier.fillMaxWidth()) { Text("☁️ Google Drive – באפליקציה (עותק)") }
                OutlinedButton({ launchCreate("drive", DocumentsContract.buildDocumentUri("com.google.android.apps.docs.storage", "root")) }, Modifier.fillMaxWidth()) { Text("☁️ Google Drive – מקושר (בורר המערכת)") }
                OutlinedButton({ sendTo("com.microsoft.skydrive", "OneDrive") }, Modifier.fillMaxWidth()) { Text("☁️ OneDrive") }
                OutlinedButton({ launchCreate("device", DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")) }, Modifier.fillMaxWidth()) { Text("📱 במכשיר") }
                OutlinedButton({
                    val c = Docs.unique(ctx, name, ext)
                    runCatching { file.copyTo(c, overwrite = true) }
                    Toast.makeText(ctx, "נשמר עותק: ${c.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
                    onSavedLocal(); onDismiss()
                }, Modifier.fillMaxWidth()) { Text("📂 עותק באפליקציה") }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("ביטול") } }
    )
}

/** Intent for "save to Drive / OneDrive" (creates a cloud file the app can keep writing to). */
internal fun cloudCreateIntent(f: File): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(Docs.mime(f))
        .putExtra(Intent.EXTRA_TITLE, f.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocsHost(onReminders: () -> Unit, onNotes: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    var tick by remember { mutableStateOf(0) }
    val files = remember(tick) { Docs.list(ctx) }
    var editing by remember { mutableStateOf<File?>(null) }
    var creating by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var organizing by remember { mutableStateOf<File?>(null) }
    var toolsMenu by remember { mutableStateOf(false) }
    var mergeDlg by remember { mutableStateOf(false) }
    var mergePick by remember { mutableStateOf(listOf<String>()) }
    var job by remember { mutableStateOf<PdfJobSpec?>(null) }
    val scope = rememberCoroutineScope()
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val blobs = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    runCatching {
                        val b = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                        b to (ctx.contentResolver.getType(u) ?: "image/*")
                    }.getOrNull()
                }
            }
            if (blobs.isEmpty()) Toast.makeText(ctx, "לא נמצאו תמונות", Toast.LENGTH_SHORT).show()
            else job = PdfJobSpec("images", "תמונות", blobs)
        }
    }
    val pending = PendingOpen.file
    LaunchedEffect(pending) {
        if (pending != null) {
            PendingOpen.file = null; tick++
            if (Docs.ext(pending) == "pdf") viewing = pending else editing = pending
        }
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<File?>(null) }

    var linkTarget by remember { mutableStateOf<File?>(null) }
    var saveAsFor by remember { mutableStateOf<File?>(null) }
    fun pickedUri(r: androidx.activity.result.ActivityResult): Uri? {
        val d = r.data ?: return null
        return d.data ?: d.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }
    val cloudOpen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = pickedUri(r)
        if (uri != null) {
            Toast.makeText(ctx, "טוען את הקובץ מהענן…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val f = withContext(Dispatchers.IO) { runCatching { Docs.importLinked(ctx, uri) }.getOrNull() }
                if (f == null) Toast.makeText(ctx, "לא הצלחתי לפתוח את הקובץ: " + Docs.lastError, Toast.LENGTH_LONG).show()
                else {
                    tick++
                    if (!Docs.isLinked(ctx, f)) Toast.makeText(ctx, "נפתח כעותק – השירות לא מאפשר שמירה חזרה. אפשר לשמור בענן מתפריט ⋮", Toast.LENGTH_LONG).show()
                    if (Docs.ext(f) == "pdf") viewing = f else editing = f
                }
            }
        } else {
            Toast.makeText(ctx, "האפליקציה חזרה בלי קובץ (קוד ${r.resultCode}, נתונים: ${if (r.data == null) "אין" else "יש"})", Toast.LENGTH_LONG).show()
        }
    }
    val cloudGet = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = pickedUri(r)
        if (uri != null) {
            scope.launch {
                val f = withContext(Dispatchers.IO) { Docs.import(ctx, uri) }
                if (f == null) Toast.makeText(ctx, "לא הצלחתי לפתוח את הקובץ: " + Docs.lastError, Toast.LENGTH_LONG).show()
                else { tick++; if (Docs.ext(f) == "pdf") viewing = f else editing = f }
            }
        }
    }
    /** Opens the service's own file picker (Drive / OneDrive app UI); falls back to the system picker. */
    fun openIn(pkg: String, name: String) {
        val mimes = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        fun mk(action: String) = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, mimes).addFlags(grant).setPackage(pkg)
        val ok = listOf(Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT).any { a ->
            runCatching { cloudOpen.launch(mk(a)) }.isSuccess
        }
        if (!ok) Toast.makeText(ctx, "$name לא מותקן או לא מאפשר בחירת קבצים", Toast.LENGTH_SHORT).show()
    }
    val cloudSave = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data; val f = linkTarget; linkTarget = null
        if (r.resultCode == android.app.Activity.RESULT_OK && uri != null && f != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Docs.linkTo(ctx, f, uri) }
                Toast.makeText(ctx, if (ok) "נשמר בענן ☁️ ומקושר לקובץ" else "השמירה בענן נכשלה", Toast.LENGTH_SHORT).show()
                tick++
            }
        }
    }
    fun openDoc(f: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Docs.pull(ctx, f) }
            if (!ok) Toast.makeText(ctx, "אין גישה לענן – נפתח העותק שבטלפון", Toast.LENGTH_SHORT).show()
            if (Docs.ext(f) == "pdf") viewing = f else editing = f
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val f = Docs.import(ctx, uri)
            if (f == null) Toast.makeText(ctx, "אפשר לייבא רק קובצי Word ו-PDF", Toast.LENGTH_SHORT).show()
            else { tick++; Toast.makeText(ctx, "הקובץ נוסף", Toast.LENGTH_SHORT).show() }
        }
    }

    BackHandler(enabled = editing == null && !creating && viewing == null && organizing == null) { onReminders() }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        if (organizing != null) {
            PdfOrganizerScreen(organizing!!, dark, onClose = { organizing = null; tick++ })
        } else if (viewing != null) {
            PdfViewerScreen(viewing!!, dark, onClose = { viewing = null; tick++ })
        } else if (editing != null || creating) {
            DocEditorScreen(editing, dark, onClose = { editing = null; creating = false; tick++ })
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                    Text("מסמכים", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        if (files.isEmpty()) "אין מסמכים עדיין" else "${files.size} מסמכים",
                        style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant
                    )
                }
                TabSwitch("docs", onReminders = onReminders, onNotes = onNotes, onDocs = {}, hPad = 0.dp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 110.dp)) {
                    if (files.isEmpty()) item {
                        Text(
                            "לחץ על \"מסמך Word חדש\" כדי ליצור מסמך, או על \"ייבוא קובץ\" כדי להוסיף קובץ Word או PDF מהטלפון.",
                            Modifier.padding(vertical = 24.dp), color = cs.onSurfaceVariant
                        )
                    }
                    items(files, key = { it.name }) { f ->
                        val isPdf = Docs.ext(f) == "pdf"
                        Box {
                            Surface(
                                shape = RoundedCornerShape(16.dp), color = cs.surface, shadowElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { openDoc(f) },
                                    onLongClick = { menuFor = f.name }
                                )
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isPdf) "📕" else "📝", fontSize = 28.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text((if (Docs.isFav(ctx, f)) "⭐ " else "") + (if (Docs.isLinked(ctx, f)) "☁️ " else "") + f.nameWithoutExtension, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            (if (isPdf) "PDF" else "Word") + " · " + SimpleDateFormat("d.M.yyyy HH:mm", Locale("he")).format(Date(f.lastModified())) +
                                                " · " + (if (f.length() > 1_000_000) "%.1f MB".format(f.length() / 1e6) else "${f.length() / 1000 + 1} KB"),
                                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                                        )
                                    }
                                    IconButton({ menuFor = f.name }) { Icon(Icons.Default.MoreVert, "עוד") }
                                }
                            }
                            DropdownMenu(expanded = menuFor == f.name, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text(if (isPdf) "פתח וסמן" else "ערוך") }, onClick = {
                                    menuFor = null; openDoc(f)
                                })
                                DropdownMenuItem(text = { Text(if (Docs.isFav(ctx, f)) "הסר ממועדפים" else "⭐ הוסף למועדפים") }, onClick = {
                                    menuFor = null; Docs.setFav(ctx, f.name, !Docs.isFav(ctx, f)); tick++
                                })
                                if (Docs.isLinked(ctx, f)) {
                                    DropdownMenuItem(text = { Text("☁️ דרוס את הקובץ בענן") }, onClick = { menuFor = null; Docs.forcePush(ctx, f) })
                                    DropdownMenuItem(text = { Text("נתק מהענן") }, onClick = { menuFor = null; Docs.unlink(ctx, f.name); tick++ })
                                } else DropdownMenuItem(text = { Text("☁️ שמור ב-Drive / OneDrive") }, onClick = {
                                    menuFor = null; linkTarget = f
                                    cloudSave.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(Docs.mime(f))
                                        .putExtra(Intent.EXTRA_TITLE, f.name)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                                })
                                if (isPdf) DropdownMenuItem(text = { Text("ארגן עמודים") }, onClick = { menuFor = null; organizing = f })
                                DropdownMenuItem(text = { Text("פתח באפליקציה אחרת") }, onClick = { menuFor = null; Docs.openExternal(ctx, f) })
                                DropdownMenuItem(text = { Text("שתף") }, onClick = { menuFor = null; Docs.share(ctx, f) })
                                DropdownMenuItem(text = { Text("שמירה בשם…") }, onClick = { menuFor = null; saveAsFor = f })
                                DropdownMenuItem(text = { Text("שנה שם") }, onClick = { menuFor = null; renameText = f.nameWithoutExtension; renaming = f })
                                DropdownMenuItem(text = { Text("מחק", color = cs.error) }, onClick = { menuFor = null; deleting = f })
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = { creating = true }, shape = RoundedCornerShape(30.dp), color = cs.primary, shadowElevation = 8.dp,
                    modifier = Modifier.weight(1f).height(58.dp)
                ) { Box(contentAlignment = Alignment.Center) { Text("＋ מסמך Word חדש", color = cs.onPrimary, fontWeight = FontWeight.Bold) } }
                Surface(
                    onClick = { picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")) },
                    shape = RoundedCornerShape(30.dp), color = cs.surface, shadowElevation = 8.dp,
                    modifier = Modifier.weight(1f).height(58.dp)
                ) { Box(contentAlignment = Alignment.Center) { Text("ייבוא קובץ", fontWeight = FontWeight.Bold) } }
                Box {
                    Surface(
                        onClick = { toolsMenu = true },
                        shape = RoundedCornerShape(30.dp), color = cs.surface, shadowElevation = 8.dp,
                        modifier = Modifier.width(88.dp).height(58.dp)
                    ) { Box(contentAlignment = Alignment.Center) { Text("כלים ▾", fontWeight = FontWeight.Bold) } }
                    DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                        DropdownMenuItem(text = { Text("☁️ Google Drive (מתוך האפליקציה)") }, onClick = {
                            toolsMenu = false; openIn("com.google.android.apps.docs", "Google Drive")
                        })
                        DropdownMenuItem(text = { Text("☁️ OneDrive (מתוך האפליקציה)") }, onClick = {
                            toolsMenu = false; openIn("com.microsoft.skydrive", "OneDrive")
                        })
                        DropdownMenuItem(text = { Text("☁️ כל מקור אחר / דרך אפליקציית הענן (עותק)") }, onClick = {
                            toolsMenu = false
                            cloudGet.launch(Intent.createChooser(
                                Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
                                "פתח מ…"
                            ))
                        })
                        DropdownMenuItem(text = { Text("איחוד קובצי PDF") }, onClick = {
                            toolsMenu = false
                            if (files.count { Docs.ext(it) == "pdf" } < 2) Toast.makeText(ctx, "צריך לפחות שני קובצי PDF ברשימה", Toast.LENGTH_SHORT).show()
                            else { mergePick = emptyList(); mergeDlg = true }
                        })
                        DropdownMenuItem(text = { Text("תמונות ל-PDF") }, onClick = { toolsMenu = false; imgPicker.launch("image/*") })
                    }
                }
            }
        }
    }

    saveAsFor?.let { f -> SaveAsDialog(f, { saveAsFor = null }, { tick++ }) }

    if (mergeDlg) {
        val pdfs = files.filter { Docs.ext(it) == "pdf" }
        AlertDialog(
            onDismissRequest = { mergeDlg = false }, title = { Text("איחוד קובצי PDF") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("סמן את הקבצים לפי הסדר שבו הם יחוברו", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    pdfs.forEach { f ->
                        val idx = mergePick.indexOf(f.name)
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = {
                                mergePick = if (idx >= 0) mergePick - f.name else mergePick + f.name
                            }).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (idx >= 0) "${idx + 1}" else "○", Modifier.width(28.dp), fontWeight = FontWeight.Bold, color = cs.primary)
                            Text(f.nameWithoutExtension, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val picked = mergePick.map { File(Docs.dir(ctx), it) }.filter { it.exists() }
                    mergeDlg = false
                    if (picked.size >= 2) scope.launch {
                        val blobs = withContext(Dispatchers.IO) { picked.map { it.readBytes() to "application/pdf" } }
                        job = PdfJobSpec("merge", "איחוד PDF", blobs)
                    } else Toast.makeText(ctx, "בחר לפחות שני קבצים", Toast.LENGTH_SHORT).show()
                }, enabled = mergePick.size >= 2) { Text("אחד") }
            },
            dismissButton = { TextButton({ mergeDlg = false }) { Text("ביטול") } }
        )
    }
    job?.let { spec ->
        PdfJobRunner(
            spec,
            onResult = { b64 ->
                job = null
                scope.launch(Dispatchers.IO) {
                    val out = runCatching {
                        val f = Docs.unique(ctx, spec.name, "pdf")
                        f.writeBytes(Base64.decode(b64, Base64.DEFAULT)); f
                    }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (out != null) { tick++; Toast.makeText(ctx, "נוצר: ${out.nameWithoutExtension}", Toast.LENGTH_LONG).show() }
                        else Toast.makeText(ctx, "השמירה נכשלה", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onFail = { m -> job = null; Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() }
        )
    }
    renaming?.let { f ->
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text("שינוי שם") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val target = File(Docs.dir(ctx), Docs.clean(renameText) + "." + Docs.ext(f))
                    if (target.name != f.name) {
                        if (target.exists()) Toast.makeText(ctx, "כבר קיים קובץ בשם הזה", Toast.LENGTH_SHORT).show()
                        else { if (f.renameTo(target)) { Docs.renameFav(ctx, f.name, target.name); Docs.renameLink(ctx, f.name, target.name) }; tick++ }
                    }
                    renaming = null
                }) { Text("שמור") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("ביטול") } }
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("מחיקת קובץ") },
            text = { Text("למחוק את \"${f.nameWithoutExtension}\" לצמיתות? אי אפשר לשחזר.") },
            confirmButton = { TextButton({ f.delete(); Docs.setFav(ctx, f.name, false); Docs.unlink(ctx, f.name); deleting = null; tick++ }) { Text("מחק", color = cs.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("ביטול") } }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocEditorScreen(file: File?, dark: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf(file) }
    var title by remember { mutableStateOf(file?.nameWithoutExtension ?: "") }
    var edited by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(file == null) }
    var saving by remember { mutableStateOf(false) }
    var closeAfter by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var pageView by remember { mutableStateOf(Docs.pageView(ctx)) }
    var showSaveAs by remember { mutableStateOf(false) }
    var linkTick by remember { mutableStateOf(0) }
    val cloudSave = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data; val f = cur
        if (r.resultCode == android.app.Activity.RESULT_OK && uri != null && f != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Docs.linkTo(ctx, f, uri) }
                Toast.makeText(ctx, if (ok) "נשמר בענן ☁️ – מעכשיו כל שמירה תתעדכן גם שם" else "השמירה בענן נכשלה", Toast.LENGTH_LONG).show()
                linkTick++
            }
        }
    }
    var plain by remember { mutableStateOf("") }
    var hasImg by remember { mutableStateOf(false) }
    val web = remember { mutableStateOf<WebView?>(null) }
    val initial = remember { file?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } ?: "" }

    fun titleChanged() = cur != null && title.isNotBlank() && Docs.clean(title) != cur!!.nameWithoutExtension

    fun requestSave(thenClose: Boolean) {
        if (!loaded) { if (thenClose) onClose(); return }
        if (file == null && plain.isBlank() && !hasImg && title.isBlank()) { if (thenClose) onClose(); return }
        if (!edited && !titleChanged() && file != null) { if (thenClose) onClose(); return }
        saving = true; closeAfter = thenClose
        web.value?.evaluateJavascript("DOC.save(${org.json.JSONObject.quote(title.ifBlank { "מסמך" })})", null)
        if (thenClose) scope.launch { delay(6000); if (closeAfter) onClose() }
    }

    fun write(b64: String) {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        var f = cur
        val wanted = Docs.clean(title.ifBlank { f?.nameWithoutExtension ?: "מסמך" })
        if (f == null) f = Docs.unique(ctx, wanted, "docx")
        else if (wanted != f.nameWithoutExtension) {
            val t = File(Docs.dir(ctx), "$wanted.docx")
            if (!t.exists() && f.renameTo(t)) { Docs.renameFav(ctx, f.name, t.name); Docs.renameLink(ctx, f.name, t.name); f = t }
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
        Docs.push(ctx, f)
        cur = f
    }

    // autosave 3 s after the last change
    LaunchedEffect(plain, hasImg, title, edited) {
        if (!edited) return@LaunchedEffect
        delay(3000)
        if (!saving) requestSave(false)
    }
    BackHandler { requestSave(true) }
    if (showSaveAs) cur?.let { SaveAsDialog(it, { showSaveAs = false }, {}) }
    LaunchedEffect(loaded, pageView) {
        if (loaded) {
            delay(150)
            web.value?.evaluateJavascript("NE.view('" + (if (pageView) "page" else "phone") + "')", null)
        }
    }

    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ requestSave(true) }) { Icon(Icons.Default.Check, "סיום") }
            Text(if (saving) "שומר…" else if (!loaded) "טוען…" else "", color = cs.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, "עוד") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text(if (pageView) "📱 תצוגה מותאמת לטלפון" else "📄 תצוגת עמוד (כמו Word)") }, onClick = {
                        moreMenu = false; pageView = !pageView; Docs.setPageView(ctx, pageView)
                    })
                    if (pageView) {
                        DropdownMenuItem(text = { Text("🔍 הגדל") }, onClick = { web.value?.evaluateJavascript("NE.zoom(1.25)", null) })
                        DropdownMenuItem(text = { Text("🔍 הקטן") }, onClick = { web.value?.evaluateJavascript("NE.zoom(0.8)", null) })
                    }
                    DropdownMenuItem(text = { Text("שמירה בשם…") }, onClick = {
                        moreMenu = false
                        scope.launch {
                            requestSave(false); delay(1500)
                            if (cur?.exists() == true) showSaveAs = true else Toast.makeText(ctx, "אין עדיין מה לשמור", Toast.LENGTH_SHORT).show()
                        }
                    })
                    key(linkTick) {
                        if (cur?.let { Docs.isLinked(ctx, it) } != true) DropdownMenuItem(text = { Text("☁️ שמור ב-Drive / OneDrive") }, onClick = {
                            moreMenu = false
                            scope.launch {
                                requestSave(false); delay(1500)
                                val f = cur
                                if (f == null || !f.exists()) Toast.makeText(ctx, "אין עדיין מה לשמור", Toast.LENGTH_SHORT).show()
                                else cloudSave.launch(cloudCreateIntent(f))
                            }
                        }) else DropdownMenuItem(text = { Text("☁️ מקושר לענן – נשמר אוטומטית") }, onClick = { moreMenu = false })
                    }
                    DropdownMenuItem(text = { Text("שתף כקובץ Word") }, onClick = {
                        moreMenu = false
                        scope.launch {
                            requestSave(false); delay(1200)
                            cur?.let { Docs.share(ctx, it) }
                        }
                    })
                    DropdownMenuItem(text = { Text("הדפס / שמור כ-PDF") }, onClick = {
                        moreMenu = false
                        web.value?.let {
                            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                            val n = title.ifBlank { "מסמך" }
                            pm.print(n, it.createPrintDocumentAdapter(n), null)
                        }
                    })
                }
            }
        }
        TextField(
            title, { title = it; edited = true }, singleLine = true,
            placeholder = { Text("שם המסמך", style = MaterialTheme.typography.headlineSmall, color = cs.onSurfaceVariant) },
            textStyle = MaterialTheme.typography.headlineSmall,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
        var fileCb by remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
        val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCb?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
            fileCb = null
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(
                        EditorBridge(
                            c,
                            onReady = {
                                if (initial.isNotEmpty()) evaluateJavascript("DOC.open($dark)", null)
                                else evaluateJavascript("NE.load('', $dark)", null)
                            },
                            onChanged = { h, t -> plain = t; hasImg = h.contains("<img"); if (loaded) edited = true },
                            onToast = { m -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show() },
                            docData = { initial },
                            onDocOpened = { loaded = true; edited = false },
                            onDocSaved = { b64 ->
                                runCatching { write(b64) }.onFailure {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(ctx, "השמירה נכשלה", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    saving = false; edited = false
                                    if (closeAfter) { closeAfter = false; onClose() }
                                }
                            }
                        ), "Android"
                    )
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onShowFileChooser(
                            w: WebView?, cb: android.webkit.ValueCallback<Array<Uri>>?, p: FileChooserParams?
                        ): Boolean {
                            fileCb?.onReceiveValue(null)
                            fileCb = cb
                            val ok = runCatching { fileLauncher.launch(p!!.createIntent()) }.isSuccess
                            if (!ok) { cb?.onReceiveValue(null); fileCb = null }
                            return ok
                        }
                    }
                    loadUrl("file:///android_asset/note_editor.html?doc=1")
                    web.value = this
                }
            },
            onRelease = { it.destroy() }
        )
    }
}

/** JS side of the PDF viewer (pdf_viewer.html). */
internal class PdfBridge(
    val ctx: Context, val data: () -> String, val onOpened: () -> Unit, val onSaved: (String) -> Unit, val onSaveFailed: () -> Unit,
    val onDirty: (Boolean) -> Unit, val onToast: (String) -> Unit, val onReady: () -> Unit
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @android.webkit.JavascriptInterface fun ready() { main.post { onReady() } }
    @android.webkit.JavascriptInterface fun docData(): String = data()
    @android.webkit.JavascriptInterface fun opened() { main.post { onOpened() } }
    @android.webkit.JavascriptInterface fun docSaved(b64: String) { onSaved(b64) }
    @android.webkit.JavascriptInterface fun saveFailed() { main.post { onSaveFailed() } }
    @android.webkit.JavascriptInterface fun dirty(v: Boolean) { main.post { onDirty(v) } }
    @android.webkit.JavascriptInterface fun toast(m: String) { main.post { onToast(m) } }
    @android.webkit.JavascriptInterface fun getSig(): String =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("pdfSig", "") ?: ""
    @android.webkit.JavascriptInterface fun saveSig(s: String) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("pdfSig", s).apply()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PdfViewerScreen(file: File, dark: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var cur by remember { mutableStateOf(file) }
    var dirty by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var closeAfter by remember { mutableStateOf(false) }
    var asCopy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val web = remember { mutableStateOf<WebView?>(null) }
    val initial = remember { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }

    fun save(copy: Boolean, thenClose: Boolean) {
        if (saving) return
        saving = true; asCopy = copy; closeAfter = thenClose
        web.value?.evaluateJavascript("PV.save()", null)
    }
    fun finish() { if (dirty) confirm = true else onClose() }
    BackHandler { finish() }
    val scope = rememberCoroutineScope()
    var showSaveAs by remember { mutableStateOf(false) }
    var linkTick by remember { mutableStateOf(0) }
    val cloudSave = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Docs.linkTo(ctx, cur, uri) }
                Toast.makeText(ctx, if (ok) "נשמר בענן ☁️ – מעכשיו כל שמירה תתעדכן גם שם" else "השמירה בענן נכשלה", Toast.LENGTH_LONG).show()
                linkTick++
            }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ finish() }) { Icon(Icons.Default.Check, "סיום") }
            Column(Modifier.weight(1f)) {
                Text(cur.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(
                    if (saving) "שומר…" else if (!opened) "טוען…" else if (dirty) "יש שינויים שלא נשמרו" else "",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
            if (dirty) TextButton({ save(false, false) }) { Text("שמור") }
            Box {
                IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, "עוד") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("שמור") }, onClick = { moreMenu = false; save(false, false) })
                    DropdownMenuItem(text = { Text("שמירה בשם…") }, onClick = {
                        moreMenu = false
                        scope.launch { if (dirty) { save(false, false); delay(2500) }; showSaveAs = true }
                    })
                    DropdownMenuItem(text = { Text("שמור כעותק") }, onClick = { moreMenu = false; save(true, false) })
                    key(linkTick) {
                        if (!Docs.isLinked(ctx, cur)) DropdownMenuItem(text = { Text("☁️ שמור ב-Drive / OneDrive") }, onClick = {
                            moreMenu = false; cloudSave.launch(cloudCreateIntent(cur))
                        }) else DropdownMenuItem(text = { Text("☁️ מקושר לענן – נשמר אוטומטית") }, onClick = { moreMenu = false })
                    }
                    DropdownMenuItem(text = { Text("שתף") }, onClick = { moreMenu = false; Docs.share(ctx, cur) })
                    DropdownMenuItem(text = { Text("פתח באפליקציה אחרת") }, onClick = { moreMenu = false; Docs.openExternal(ctx, cur) })
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(
                        PdfBridge(
                            c, data = { initial },
                            onOpened = { opened = true },
                            onSaved = { b64 ->
                                val ok = runCatching {
                                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                                    val target = if (asCopy) Docs.unique(ctx, cur.nameWithoutExtension + " - מסומן", "pdf") else cur
                                    val tmp = File(target.parentFile, target.name + ".tmp")
                                    tmp.writeBytes(bytes)
                                    if (!tmp.renameTo(target)) { target.writeBytes(bytes); tmp.delete() }
                                    cur = target
                                    Docs.push(ctx, target)
                                }.isSuccess
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    saving = false
                                    if (ok) {
                                        dirty = false
                                        Toast.makeText(ctx, if (asCopy) "נשמר עותק: ${cur.nameWithoutExtension}" else "נשמר", Toast.LENGTH_SHORT).show()
                                        if (closeAfter) { closeAfter = false; onClose() }
                                    } else Toast.makeText(ctx, "השמירה נכשלה", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSaveFailed = { saving = false; closeAfter = false },
                            onDirty = { dirty = it },
                            onToast = { m -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show() },
                            onReady = { evaluateJavascript("PV.open($dark)", null) }
                        ), "Android"
                    )
                    loadUrl("file:///android_asset/pdf_viewer.html")
                    web.value = this
                }
            },
            onRelease = { it.destroy() }
        )
    }

    if (showSaveAs) SaveAsDialog(cur, { showSaveAs = false }, {})

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("לשמור את הסימונים?") },
        text = { Text("הסימונים יישמרו בתוך קובץ ה-PDF. אפשר גם לשמור כעותק ולהשאיר את המקור כמו שהוא.") },
        confirmButton = { TextButton({ confirm = false; save(false, true) }) { Text("שמור וסגור") } },
        dismissButton = {
            Row {
                TextButton({ confirm = false; onClose() }) { Text("סגור בלי לשמור", color = cs.error) }
                TextButton({ confirm = false }) { Text("המשך") }
            }
        }
    )
}

class PdfJobSpec(val kind: String, val name: String, val blobs: List<Pair<ByteArray, String>>)

/** Bridge for pdf_organizer.html (page organizer and the invisible merge / images-to-PDF jobs). */
internal class PdfToolsBridge(
    val data: () -> String, val blobs: List<Pair<ByteArray, String>>,
    val onReady: () -> Unit, val onOpened: () -> Unit, val onResult: (String, String) -> Unit,
    val onFail: (String) -> Unit, val onDirty: (Boolean) -> Unit, val onToast: (String) -> Unit
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @android.webkit.JavascriptInterface fun ready() { main.post { onReady() } }
    @android.webkit.JavascriptInterface fun docData(): String = data()
    @android.webkit.JavascriptInterface fun opened() { main.post { onOpened() } }
    @android.webkit.JavascriptInterface fun fileData(i: Int): String = Base64.encodeToString(blobs[i].first, Base64.NO_WRAP)
    @android.webkit.JavascriptInterface fun fileMime(i: Int): String = blobs[i].second
    @android.webkit.JavascriptInterface fun result(kind: String, b64: String) { main.post { onResult(kind, b64) } }
    @android.webkit.JavascriptInterface fun fail(m: String) { main.post { onFail(m) } }
    @android.webkit.JavascriptInterface fun dirty(v: Boolean) { main.post { onDirty(v) } }
    @android.webkit.JavascriptInterface fun toast(m: String) { main.post { onToast(m) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PdfJobRunner(spec: PdfJobSpec, onResult: (String) -> Unit, onFail: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = {}, title = { Text("מעבד…") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(14.dp))
                Text(if (spec.kind == "merge") "מאחד את הקבצים" else "יוצר PDF מהתמונות")
            }
            AndroidView(modifier = Modifier.size(1.dp), factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(
                        PdfToolsBridge(
                            data = { "" }, blobs = spec.blobs,
                            onReady = {
                                evaluateJavascript(if (spec.kind == "merge") "PT.merge(${spec.blobs.size})" else "PT.images(${spec.blobs.size})", null)
                            },
                            onOpened = {}, onResult = { _, b64 -> onResult(b64) }, onFail = onFail, onDirty = {}, onToast = {}
                        ), "Android"
                    )
                    loadUrl("file:///android_asset/pdf_organizer.html")
                }
            }, onRelease = { w: WebView -> w.destroy() })
        },
        confirmButton = {}
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PdfOrganizerScreen(file: File, dark: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var dirty by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var closeAfter by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val web = remember { mutableStateOf<WebView?>(null) }
    val initial = remember { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }

    fun save(thenClose: Boolean) {
        if (saving) return
        saving = true; closeAfter = thenClose
        web.value?.evaluateJavascript("PT.save()", null)
    }
    fun finish() { if (dirty) confirm = true else onClose() }
    BackHandler { finish() }

    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ finish() }) { Icon(Icons.Default.Check, "סיום") }
            Column(Modifier.weight(1f)) {
                Text("ארגון עמודים · " + file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(
                    if (saving) "שומר…" else if (!opened) "טוען…" else if (dirty) "יש שינויים שלא נשמרו" else "בחר עמודים כדי לסובב, להזיז, למחוק או לחלץ",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
            if (dirty) TextButton({ save(false) }) { Text("שמור") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(
                        PdfToolsBridge(
                            data = { initial }, blobs = emptyList(),
                            onReady = { evaluateJavascript("PT.open($dark)", null) },
                            onOpened = { opened = true },
                            onResult = { kind, b64 ->
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                if (kind == "extract") {
                                    val f = Docs.unique(ctx, file.nameWithoutExtension + " - חלק", "pdf")
                                    f.writeBytes(bytes)
                                    Toast.makeText(ctx, "נוצר קובץ חדש: ${f.nameWithoutExtension}", Toast.LENGTH_LONG).show()
                                } else {
                                    val tmp = File(file.parentFile, file.name + ".tmp")
                                    tmp.writeBytes(bytes)
                                    if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
                                    Docs.push(ctx, file)
                                    saving = false; dirty = false
                                    Toast.makeText(ctx, "נשמר", Toast.LENGTH_SHORT).show()
                                    if (closeAfter) { closeAfter = false; onClose() }
                                }
                            },
                            onFail = { m -> saving = false; closeAfter = false; Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() },
                            onDirty = { dirty = it },
                            onToast = { m -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show() }
                        ), "Android"
                    )
                    loadUrl("file:///android_asset/pdf_organizer.html")
                    web.value = this
                }
            },
            onRelease = { it.destroy() }
        )
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("לשמור את השינויים?") },
        text = { Text("סדר העמודים והסיבובים יישמרו בקובץ הזה.") },
        confirmButton = { TextButton({ confirm = false; save(true) }) { Text("שמור וסגור") } },
        dismissButton = {
            Row {
                TextButton({ confirm = false; onClose() }) { Text("סגור בלי לשמור", color = cs.error) }
                TextButton({ confirm = false }) { Text("המשך") }
            }
        }
    )
}
