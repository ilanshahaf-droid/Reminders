package com.ilan.reminders

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Files of the "Documents" tab live in the app's private folder (local to this phone). */
object Docs {
    fun dir(c: Context): File = File(c.filesDir, "docs").apply { mkdirs() }
    fun ext(f: File) = f.extension.lowercase()
    fun list(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile && ext(it) in setOf("docx", "pdf") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().ifBlank { "מסמך" }.take(80)

    fun unique(c: Context, base: String, ext: String): File {
        val b = clean(base)
        var f = File(dir(c), "$b.$ext")
        var i = 2
        while (f.exists()) { f = File(dir(c), "$b ($i).$ext"); i++ }
        return f
    }

    fun import(c: Context, uri: Uri): File? = runCatching {
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
        if (ext !in setOf("docx", "pdf")) return null
        val same = File(dir(c), clean(name.substringBeforeLast('.')) + "." + ext)
        val size = runCatching { c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (same.exists() && size > 0 && same.length() == size) return same.also { it.setLastModified(System.currentTimeMillis()) }
        val out = unique(c, name.substringBeforeLast('.'), ext)
        c.contentResolver.openInputStream(uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } }
        out
    }.getOrNull()

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val f = Docs.import(ctx, uri)
            if (f == null) Toast.makeText(ctx, "אפשר לייבא רק קובצי Word ו-PDF", Toast.LENGTH_SHORT).show()
            else { tick++; Toast.makeText(ctx, "הקובץ נוסף", Toast.LENGTH_SHORT).show() }
        }
    }

    BackHandler(enabled = editing == null && !creating && viewing == null) { onReminders() }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        if (viewing != null) {
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
                                    onClick = { if (isPdf) viewing = f else editing = f },
                                    onLongClick = { menuFor = f.name }
                                )
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isPdf) "📕" else "📝", fontSize = 28.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(f.nameWithoutExtension, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    menuFor = null; if (isPdf) viewing = f else editing = f
                                })
                                DropdownMenuItem(text = { Text("פתח באפליקציה אחרת") }, onClick = { menuFor = null; Docs.openExternal(ctx, f) })
                                DropdownMenuItem(text = { Text("שתף") }, onClick = { menuFor = null; Docs.share(ctx, f) })
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
            }
        }
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
                        else { f.renameTo(target); tick++ }
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
            confirmButton = { TextButton({ f.delete(); deleting = null; tick++ }) { Text("מחק", color = cs.error) } },
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
            if (!t.exists() && f.renameTo(t)) f = t
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
        cur = f
    }

    // autosave 3 s after the last change
    LaunchedEffect(plain, hasImg, title, edited) {
        if (!edited) return@LaunchedEffect
        delay(3000)
        if (!saving) requestSave(false)
    }
    BackHandler { requestSave(true) }

    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ requestSave(true) }) { Icon(Icons.Default.Check, "סיום") }
            Text(if (saving) "שומר…" else if (!loaded) "טוען…" else "", color = cs.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, "עוד") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
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
                    loadUrl("file:///android_asset/note_editor.html")
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
                    DropdownMenuItem(text = { Text("שמור כעותק") }, onClick = { moreMenu = false; save(true, false) })
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
