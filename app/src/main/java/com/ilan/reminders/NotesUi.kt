package com.ilan.reminders

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The app has two parts: reminders (Root) and notes. Notes open over the reminders, which stay alive underneath. */
@Composable
fun Home() {
    var screen by rememberSaveable { mutableStateOf("tasks") }
    if (PendingShare.text != null && screen != "notes") screen = "notes"
    Box(Modifier.fillMaxSize()) {
        Root(onOpenNotes = { screen = "notes" })
        if (screen == "notes") NotesHost(onReminders = { screen = "tasks" })
    }
}

@Composable
fun TabSwitch(notesSelected: Boolean, onReminders: () -> Unit, onNotes: () -> Unit, hPad: Dp = 24.dp) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("תזכורות" to false, "פתקים" to true).forEach { (label, isNotes) ->
            val sel = notesSelected == isNotes
            Surface(
                onClick = { if (!sel) { if (isNotes) onNotes() else onReminders() } },
                shape = RoundedCornerShape(20.dp),
                color = if (sel) cs.primaryContainer else cs.surface,
                shadowElevation = if (sel) 0.dp else 1.dp
            ) {
                Text(
                    label, Modifier.padding(horizontal = 22.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge,
                    color = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesHost(onReminders: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val db = remember { AppDb.get(ctx) }
    val all by db.notes().all().collectAsState(initial = emptyList())
    val categories by db.dao().categories().collectAsState(initial = emptyList())
    val notes = remember(all) { all.filter { it.deletedAt == 0L } }
    val trash = remember(all) { all.filter { it.deletedAt > 0L } }
    var editing by remember { mutableStateOf<Note?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var catFilter by remember { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var cardMenu by remember { mutableStateOf<Long?>(null) }
    val np = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var sortBy by remember { mutableStateOf(np.getString("noteSort", "modified") ?: "modified") }
    var cols by remember { mutableStateOf(np.getInt("noteCols", 2)) }
    var pinTop by remember { mutableStateOf(np.getBoolean("notePinTop", true)) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var selMode by remember { mutableStateOf(false) }
    fun clearSel() { selected = emptySet(); selMode = false }
    var showSort by remember { mutableStateOf(false) }
    var showView by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    var selColorMenu by remember { mutableStateOf(false) }
    var selCatMenu by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf(Sync.prefs(ctx).getString("status", "") ?: "") }
    DisposableEffect(Unit) {
        val p = Sync.prefs(ctx)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, k ->
            if (k == "status") syncStatus = sp.getString("status", "") ?: ""
        }
        p.registerOnSharedPreferenceChangeListener(l)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(l) }
    }
    LaunchedEffect(Unit) { Notes.purgeExpired(ctx) }
    val shared = PendingShare.text
    LaunchedEffect(shared) {
        if (shared != null) {
            PendingShare.text = null
            val esc = shared.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
            editing = newNote(ctx).copy(html = esc, plain = shared)
        }
    }
    BackHandler(enabled = editing == null && (selMode || selected.isNotEmpty())) { clearSel() }
    BackHandler(enabled = editing == null && !selMode && selected.isEmpty()) {
        if (searching) { searching = false; query = "" } else onReminders()
    }

    Box(Modifier.fillMaxSize().background(appBackground())) {
        val e = editing
        if (e != null) {
            NoteEditorScreen(e, categories, dark, onClose = { editing = null })
        } else {
            val q = query.trim()
            val shown = notes.filter {
                (catFilter == null || it.categoryId == catFilter) &&
                    (q.isEmpty() || it.title.contains(q, true) || (!it.locked && it.plain.contains(q, true)))
            }
            val sorted = when (sortBy) {
                "created" -> shown.sortedByDescending { it.createdAt }
                "title" -> shown.sortedBy { it.title.ifBlank { it.plain }.lowercase() }
                else -> shown.sortedByDescending { it.updatedAt }
            }
            val pinned = if (pinTop) sorted.filter { it.pinned } else emptyList()
            val others = if (pinTop) sorted.filter { !it.pinned } else sorted
            val selNotes = notes.filter { it.id in selected }
            val card: @Composable (Note) -> Unit = { n ->
                NoteCard(n, categories.firstOrNull { it.id == n.categoryId }, dark, false, {},
                    onClick = {
                        if (selMode || selected.isNotEmpty()) { selected = if (n.id in selected) selected - n.id else selected + n.id }
                        else if (n.locked) authenticate(ctx) { editing = n }
                        else { editing = n }
                    },
                    onLong = { selMode = true; selected = selected + n.id },
                    onPin = {}, onShare = {}, onDelete = {}, selected = n.id in selected)
            }
            val catById = categories.associateBy { it.id }
            Scaffold(containerColor = Color.Transparent) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(cols),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 130.dp),
                        verticalItemSpacing = 10.dp,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(span = StaggeredGridItemSpan.FullLine, key = "head") {
                            Column {
                                if (selMode || selected.isNotEmpty()) Surface(
                                    shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 4.dp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)
                                ) {
                                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton({ clearSel() }) { Text("✕", fontSize = 20.sp, color = cs.onSurface) }
                                        Text("${selected.size} נבחרו", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        TextButton({ selected = notes.map { it.id }.toSet() }) { Text("הכל") }
                                        TextButton({
                                            val pin = !selNotes.all { it.pinned }
                                            scope.launch { selNotes.forEach { Notes.save(ctx, it.copy(pinned = pin)) } }
                                            clearSel()
                                        }) { Text("📌", fontSize = 20.sp) }
                                        Box {
                                            TextButton({ selColorMenu = true }) { Text("🎨", fontSize = 20.sp) }
                                            DropdownMenu(expanded = selColorMenu, onDismissRequest = { selColorMenu = false }) {
                                                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    (listOf("" to null as Color?) + NOTE_COLORS.map { it.first to noteColor(it.first, dark) }).forEach { (key, c) ->
                                                        Box(
                                                            Modifier.size(30.dp).clip(CircleShape).background(c ?: cs.surface).border(1.dp, cs.outline, CircleShape)
                                                                .clickable {
                                                                    selColorMenu = false
                                                                    scope.launch { selNotes.forEach { Notes.save(ctx, it.copy(color = key)) } }
                                                                    clearSel()
                                                                }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (categories.isNotEmpty()) Box {
                                            TextButton({ selCatMenu = true }) { Text("🏷", fontSize = 20.sp) }
                                            DropdownMenu(expanded = selCatMenu, onDismissRequest = { selCatMenu = false }) {
                                                DropdownMenuItem(text = { Text("ללא קטגוריה") }, onClick = {
                                                    selCatMenu = false; scope.launch { selNotes.forEach { Notes.save(ctx, it.copy(categoryId = 0)) } }; clearSel()
                                                })
                                                categories.forEach { c ->
                                                    DropdownMenuItem(text = { Text(c.name) }, onClick = {
                                                        selCatMenu = false; scope.launch { selNotes.forEach { Notes.save(ctx, it.copy(categoryId = c.id)) } }; clearSel()
                                                    })
                                                }
                                            }
                                        }
                                        TextButton({
                                            shareNote(ctx, selNotes.firstOrNull()?.copy(
                                                title = "פתקים", plain = selNotes.filter { !it.locked }.joinToString("\n\n———\n\n") { it.asText() }, html = ""
                                            ) ?: return@TextButton)
                                        }) { Text("↗", fontSize = 20.sp, color = cs.onSurface) }
                                        TextButton({
                                            scope.launch { selNotes.forEach { Notes.trash(ctx, it) } }
                                            clearSel()
                                        }) { Text("🗑", fontSize = 20.sp) }
                                    }
                                }
                                Row(Modifier.padding(start = 0.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("פתקים", style = MaterialTheme.typography.headlineLarge)
                                        Text(
                                            if (notes.isEmpty()) "אין פתקים עדיין" else "${notes.size} פתקים",
                                            style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant
                                        )
                                        if (Sync.configured(ctx) && syncStatus.contains("⚠")) Text(
                                            syncStatus.substringAfter("· "), style = MaterialTheme.typography.bodySmall, color = cs.error
                                        )
                                    }
                                    Surface(shape = RoundedCornerShape(28.dp), color = cs.surface, shadowElevation = 2.dp) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "חיפוש") }
                                            Box {
                                                IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "תפריט") }
                                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                                    DropdownMenuItem(text = { Text("בחר") }, onClick = { menuOpen = false; selMode = true })
                                                    DropdownMenuItem(text = { Text("הצג") }, onClick = { menuOpen = false; showView = true })
                                                    DropdownMenuItem(text = { Text("מיין") }, onClick = { menuOpen = false; showSort = true })
                                                    DropdownMenuItem(
                                                        text = { Text((if (pinTop) "✓ " else "") + "הצמד מועדפים למעלה") },
                                                        onClick = { pinTop = !pinTop; np.edit().putBoolean("notePinTop", pinTop).apply(); menuOpen = false }
                                                    )
                                                    DropdownMenuItem(text = { Text("נהל קטגוריות") }, onClick = { menuOpen = false; showCats = true })
                                                    DropdownMenuItem(
                                                        text = { Text("סל מחזור (${trash.size})") },
                                                        onClick = { menuOpen = false; showTrash = true }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                TabSwitch(true, onReminders = onReminders, onNotes = {}, hPad = 0.dp)
                                if (searching) OutlinedTextField(
                                    query, { query = it }, singleLine = true, placeholder = { Text("חיפוש בפתקים") },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                if (categories.isNotEmpty()) LazyRow(
                                    Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    item { FilterChip(catFilter == null, { catFilter = null }, label = { Text("הכל") }) }
                                    items(categories, key = { it.id }) { c ->
                                        FilterChip(catFilter == c.id, { catFilter = if (catFilter == c.id) null else c.id }, label = { Text(c.name) })
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        if (pinned.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "pinH") {
                            Text("מוצמדים", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                        }
                        items(pinned, key = { it.id }) { n -> card(n) }
                        if (pinned.isNotEmpty() && others.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "othH") {
                            Text("אחרים", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                        }
                        items(others, key = { it.id }) { n -> card(n) }
                        if (shown.isEmpty() && notes.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "none") {
                            Text("לא נמצאו פתקים", Modifier.padding(24.dp), color = cs.onSurfaceVariant)
                        }
                    }
                    Surface(
                        onClick = { editing = newNote(ctx).copy(categoryId = catFilter ?: 0L) },
                        shape = RoundedCornerShape(32.dp), color = cs.surface, shadowElevation = 10.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 16.dp).fillMaxWidth().height(64.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("פתק חדש", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("＋", fontSize = 28.sp, color = cs.primary)
                        }
                    }
                }
            }
        }
    }

    if (showSort) AlertDialog(
        onDismissRequest = { showSort = false }, title = { Text("מיון") },
        text = {
            Column {
                listOf("modified" to "עודכן לאחרונה", "created" to "נוצר לאחרונה", "title" to "כותרת (א-ב)").forEach { (k, label) ->
                    TextButton({ sortBy = k; np.edit().putString("noteSort", k).apply(); showSort = false }, Modifier.fillMaxWidth()) {
                        Text((if (sortBy == k) "● " else "○ ") + label, Modifier.fillMaxWidth(), color = cs.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton({ showSort = false }) { Text("סגור") } }
    )
    if (showView) AlertDialog(
        onDismissRequest = { showView = false }, title = { Text("תצוגה") },
        text = {
            Column {
                listOf(2 to "רשת (שתי עמודות)", 1 to "רשימה (עמודה אחת)", 3 to "רשת צפופה (שלוש עמודות)").forEach { (k, label) ->
                    TextButton({ cols = k; np.edit().putInt("noteCols", k).apply(); showView = false }, Modifier.fillMaxWidth()) {
                        Text((if (cols == k) "● " else "○ ") + label, Modifier.fillMaxWidth(), color = cs.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton({ showView = false }) { Text("סגור") } }
    )
    if (showCats) CategoriesDialog(
        categories,
        onAdd = { n, c -> scope.launch { db.dao().addCategory(Category(name = n, color = c)); Sync.schedulePush(ctx) } },
        onDelete = { c ->
            scope.launch {
                db.dao().clearCategory(c.id); db.dao().deleteCategory(c.id)
                if (catFilter == c.id) catFilter = null
                Sync.schedulePush(ctx)
            }
        },
        onDismiss = { showCats = false }
    )
    if (showTrash) AlertDialog(
        onDismissRequest = { showTrash = false },
        title = { Text("סל מחזור") },
        text = {
            if (trash.isEmpty()) Text("הסל ריק. פתקים שנמחקו נשמרים כאן 30 יום.")
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                trash.forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            n.title.ifBlank { n.plain.lineSequence().firstOrNull() ?: "פתק ריק" },
                            Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        TextButton({ scope.launch { Notes.restore(ctx, n) } }) { Text("שחזר") }
                        TextButton({ scope.launch { Notes.purge(ctx, n) } }) { Text("מחק", color = cs.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ showTrash = false }) { Text("סגור") } }
    )
}

/** Biometric / device-credential check before a locked note is opened. */
fun authenticate(ctx: Context, onOk: () -> Unit) {
    val act = ctx as? androidx.fragment.app.FragmentActivity
    if (act == null) { onOk(); return }
    val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (androidx.biometric.BiometricManager.from(act).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
        Toast.makeText(act, "הגדר נעילת מסך או טביעת אצבע בטלפון כדי לפתוח פתקים נעולים", Toast.LENGTH_LONG).show()
        return
    }
    val prompt = androidx.biometric.BiometricPrompt(
        act, androidx.core.content.ContextCompat.getMainExecutor(act),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { onOk() }
        }
    )
    prompt.authenticate(
        androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("פתק נעול").setSubtitle("אמת זהות כדי להמשיך").setAllowedAuthenticators(auth).build()
    )
}

private fun shareNote(ctx: Context, n: Note) {
    ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, n.title.ifBlank { "פתק" })
                .putExtra(Intent.EXTRA_TEXT, n.asText()), null
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    n: Note, cat: Category?, dark: Boolean, menu: Boolean, onMenuDismiss: () -> Unit,
    onClick: () -> Unit, onLong: () -> Unit, onPin: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
    selected: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val bg = noteColor(n.color, dark) ?: cs.surface
    Box {
        Surface(
            shape = RoundedCornerShape(18.dp), color = bg, shadowElevation = 2.dp,
            border = if (selected) androidx.compose.foundation.BorderStroke(3.dp, cs.primary) else null,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onClick, onLongClick = onLong)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) Text("✔ נבחר", fontSize = 13.sp, color = cs.primary)
                if (n.pinned) Text("📌", fontSize = 14.sp)
                if (n.title.isNotBlank()) Text(
                    n.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                if (n.locked) Text("🔒 פתק נעול", color = cs.onSurfaceVariant)
                else if (n.plain.isNotBlank()) Text(
                    n.plain, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface.copy(alpha = 0.85f),
                    maxLines = 8, overflow = TextOverflow.Ellipsis
                )
                if (!n.locked && n.title.isBlank() && n.plain.isBlank()) Text("פתק ריק", color = cs.onSurfaceVariant)
                if (cat != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(cat.color)))
                    Text("  " + cat.name, fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(text = { Text(if (n.pinned) "בטל הצמדה" else "הצמד") }, onClick = onPin)
            DropdownMenuItem(text = { Text("שתף כטקסט") }, onClick = onShare)
            DropdownMenuItem(text = { Text("מחק", color = cs.error) }, onClick = onDelete)
        }
    }
}

/** What the page inside the WebView can call. */
private class EditorBridge(
    val ctx: android.content.Context, val onReady: () -> Unit, val onChanged: (String, String) -> Unit, val onToast: (String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun ready() { main.post { onReady() } }
    @JavascriptInterface fun getBar(): String = ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getString("noteBar", "") ?: ""
    @JavascriptInterface fun saveBar(v: String) { ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().putString("noteBar", v).apply() }
    @JavascriptInterface fun toast(m: String) { main.post { onToast(m) } }
    @JavascriptInterface fun changed(html: String, text: String) { main.post { onChanged(html, text) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NoteEditorScreen(note: Note, categories: List<Category>, dark: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isNew = remember { note.isBlank() }
    var title by remember { mutableStateOf(note.title) }
    var html by remember { mutableStateOf(note.html) }
    var plain by remember { mutableStateOf(note.plain) }
    var color by remember { mutableStateOf(note.color) }
    var pinned by remember { mutableStateOf(note.pinned) }
    var locked by remember { mutableStateOf(note.locked) }
    var catId by remember { mutableStateOf(note.categoryId) }
    var colorMenu by remember { mutableStateOf(false) }
    var catMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val web = remember { mutableStateOf<WebView?>(null) }
    val titleFocus = remember { FocusRequester() }
    val bg = noteColor(color, dark) ?: cs.surface
    val swatches: List<Pair<String, Color?>> =
        listOf<Pair<String, Color?>>("" to null) + NOTE_COLORS.map { it.first to noteColor(it.first, dark) }

    fun current() = note.copy(title = title, html = html, plain = plain, color = color, pinned = pinned, categoryId = catId, locked = locked)
    fun dirty() = title != note.title || html != note.html || plain != note.plain || color != note.color ||
        pinned != note.pinned || catId != note.categoryId || locked != note.locked

    // keep what was written even if the app is closed while typing
    LaunchedEffect(title, html, plain, color, pinned, catId, locked) {
        delay(800)
        val n = current()
        if (dirty() && (!n.isBlank() || !isNew)) Notes.save(ctx, n)
    }
    if (isNew) LaunchedEffect(Unit) {
        delay(250)
        runCatching { titleFocus.requestFocus() }
    }

    fun finish() {
        web.value?.evaluateJavascript("NE.flush()", null)
        scope.launch {
            delay(200)
            val n = current()
            if (dirty() && (!n.isBlank() || !isNew)) Notes.save(ctx, n)
            onClose()
        }
    }
    var reminder by remember { mutableStateOf<Task?>(null) }
    var finding by remember { mutableStateOf(false) }
    var findQ by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == android.app.Activity.RESULT_OK && !text.isNullOrBlank())
            web.value?.evaluateJavascript("NE.insert(${JSONObject.quote(text + " ")})", null)
    }
    fun dictate() {
        val ok = runCatching {
            voiceLauncher.launch(
                Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            )
        }.isSuccess
        if (!ok) Toast.makeText(ctx, "הקלדה קולית לא זמינה", Toast.LENGTH_SHORT).show()
    }
    var fileCb by remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }
    BackHandler(enabled = reminder == null && full) { full = false }
    BackHandler(enabled = reminder == null && !full) { finish() }

    var exportText by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) } }.isSuccess
            Toast.makeText(ctx, if (ok) "הפתק נשמר" else "השמירה נכשלה", Toast.LENGTH_SHORT).show()
        }
    }

    reminder?.let { rt ->
        Editor(
            initial = rt, categories = categories,
            onManageCategories = {},
            onBack = { reminder = null },
            onSave = { t ->
                reminder = null
                scope.launch { Reminders.save(ctx, t) }
                Toast.makeText(ctx, "התזכורת נוצרה", Toast.LENGTH_SHORT).show()
            },
            onDelete = null
        )
        return
    }

    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (full) TextButton({ full = false }) { Text("⤡ יציאה ממסך מלא") }
            else IconButton({ finish() }) { Icon(Icons.Default.Check, "סיום") }
            Spacer(Modifier.weight(1f))
            if (!full) TextButton({ dictate() }) { Text("🎤", fontSize = 20.sp) }
            TextButton({ pinned = !pinned }) { Text("📌", fontSize = 20.sp, modifier = Modifier.alpha(if (pinned) 1f else 0.35f)) }
            Box {
                TextButton({ colorMenu = true }) { Text("🎨", fontSize = 20.sp) }
                DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        swatches.chunked(5).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { (key, c) ->
                                    Box(
                                        Modifier.size(36.dp).clip(CircleShape).background(c ?: cs.surface)
                                            .border(if (key == color) 3.dp else 1.dp, if (key == color) cs.primary else cs.outline, CircleShape)
                                            .clickable { color = key; colorMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (categories.isNotEmpty()) Box {
                TextButton({ catMenu = true }) {
                    val cn = categories.firstOrNull { it.id == catId }?.name
                    Text(if (cn != null) "🏷 $cn" else "🏷", fontSize = 16.sp, color = cs.onSurface)
                }
                DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                    DropdownMenuItem(text = { Text("ללא קטגוריה") }, onClick = { catId = 0; catMenu = false })
                    categories.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { catId = c.id; catMenu = false }) }
                }
            }
            Box {
                IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, "עוד") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (locked) "🔓 בטל נעילה" else "🔒 נעל פתק") },
                        onClick = {
                            moreMenu = false
                            if (locked) authenticate(ctx) { locked = false } else locked = true
                        }
                    )
                    DropdownMenuItem(text = { Text("חיפוש בפתק") }, onClick = { moreMenu = false; finding = true })
                    DropdownMenuItem(text = { Text("מסך מלא") }, onClick = { moreMenu = false; full = true })
                    DropdownMenuItem(text = { Text("הדפס") }, onClick = {
                        moreMenu = false
                        val wv = web.value
                        if (wv != null) {
                            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                            pm.print(title.ifBlank { "פתק" }, wv.createPrintDocumentAdapter(title.ifBlank { "פתק" }), null)
                        }
                    })
                    DropdownMenuItem(text = { Text("הוסף תזכורת לפתק") }, onClick = {
                        moreMenu = false
                        val n = current()
                        reminder = newTask(ctx).copy(title = n.title.ifBlank { n.plain.lineSequence().firstOrNull().orEmpty().take(80) }, notes = n.plain.take(1000))
                    })
                    DropdownMenuItem(text = { Text("שתף כטקסט") }, onClick = { moreMenu = false; shareNote(ctx, current()) })
                    DropdownMenuItem(text = { Text("שמור כקובץ טקסט") }, onClick = {
                        moreMenu = false
                        exportText = current().asText()
                        exportLauncher.launch((title.ifBlank { "פתק" }) + ".txt")
                    })
                    DropdownMenuItem(text = { Text("מחק", color = cs.error) }, onClick = {
                        moreMenu = false
                        scope.launch {
                            if (!isNew || !current().isBlank()) Notes.trash(ctx, current())
                            onClose()
                        }
                    })
                }
            }
        }
        if (finding && !full) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                findQ, { findQ = it; web.value?.findAllAsync(it) }, singleLine = true, placeholder = { Text("חיפוש בפתק") },
                modifier = Modifier.weight(1f)
            )
            TextButton({ web.value?.findNext(false) }) { Text("▲") }
            TextButton({ web.value?.findNext(true) }) { Text("▼") }
            TextButton({ finding = false; findQ = ""; web.value?.clearMatches() }) { Text("✕") }
        }
        if (!full) TextField(
            title, { title = it }, singleLine = false, maxLines = 3,
            placeholder = { Text("כותרת", style = MaterialTheme.typography.headlineSmall, color = cs.onSurfaceVariant) },
            textStyle = MaterialTheme.typography.headlineSmall,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus)
        )
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
                                evaluateJavascript("NE.load(${JSONObject.quote(html)}, $dark)", null)
                            },
                            onChanged = { h, t -> html = h; plain = t },
                            onToast = { m -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show() }
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
