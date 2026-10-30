package com.ilan.reminders

import android.Manifest
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root() } }
        // while the app is on screen: pull web changes right away and then every 15 seconds
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (Sync.configured(this@MainActivity)) Sync.syncNow(this@MainActivity)
                    delay(15_000)
                }
            }
        }
    }
}

private val BUCKETS = listOf("באיחור", "היום", "מחר", "השבוע", "בהמשך", "הושלמו")
private val SEED = listOf("אישי" to 0xFF6366F1, "עבודה" to 0xFF0EA5E9, "משפחה" to 0xFFF59E0B, "בריאות" to 0xFF10B981)

private fun bucket(t: Task, now: Long): Int {
    if (t.done) return 5
    if (t.alertMode == "NONE") return 6   // list-only reminders have no time: no "overdue", no day groups
    if ((t.repeat == "NONE" || (t.repeat == "LIST" && t.fired)) && t.triggerAt < now) return 0
    val today = LocalDate.now()
    val d = Instant.ofEpochMilli(t.triggerAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        d <= today -> 1
        d == today.plusDays(1) -> 2
        d < today.plusDays(7) -> 3
        else -> 4
    }
}

private const val NO_ALERT = -1L
private const val TODAY = -2L
private const val IMPORTANT = -3L

/** Default time for new reminders: the "defTime" setting (HH:mm), today or tomorrow if already past; otherwise the next full hour. */
fun defaultTriggerAt(c: Context): Long {
    val p = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    val dt = p.getString("defTime", "") ?: ""
    val parsed = runCatching { java.time.LocalTime.parse(dt) }.getOrNull()
    val now = ZonedDateTime.now()
    if (parsed == null) return now.plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
    var z = now.with(parsed).withSecond(0).withNano(0)
    if (!z.isAfter(now)) z = z.plusDays(1)
    return z.toInstant().toEpochMilli()
}

fun defaultCategory(c: Context): Long = c.getSharedPreferences("app", Context.MODE_PRIVATE).getLong("defCat", 0L)

private fun newTask(c: Context, noAlert: Boolean = false): Task {
    val vib = c.getSharedPreferences("app", Context.MODE_PRIVATE).getString("defVibration", "STRONG") ?: "STRONG"
    return Task(title = "", triggerAt = defaultTriggerAt(c), vibration = vib, categoryId = defaultCategory(c),
        alertMode = if (noAlert) "NONE" else "ONCE")
}

private fun batteryOk(c: Context) =
    c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Root() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    val allTasks by dao.all().collectAsState(initial = emptyList())
    val tasks = remember(allTasks) { allTasks.filter { it.deletedAt == 0L } }
    val trash = remember(allTasks) { allTasks.filter { it.deletedAt > 0L } }
    var showTrash by remember { mutableStateOf(false) }
    var showNewList by remember { mutableStateOf(false) }
    var renameList by remember { mutableStateOf<String?>(null) }
    var deleteList by remember { mutableStateOf<String?>(null) }
    var showMove by remember { mutableStateOf(false) }
    var listFilter by remember { mutableStateOf<String?>(null) }
    val lists = remember(tasks) { tasks.filter { it.alertMode == "NONE" }.map { it.listName.trim() }.filter { it.isNotEmpty() }.distinct().sorted() }
    val categories by dao.categories().collectAsState(initial = emptyList())
    var editor by remember { mutableStateOf<Task?>(null) }
    var filter by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showBulkSchedule by remember { mutableStateOf(false) }
    var showBulkCat by remember { mutableStateOf(false) }
    var showBulkDelete by remember { mutableStateOf(false) }
    var showDone by remember {
        mutableStateOf(ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("showDone", false))
    }
    var showClearDone by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf(Sync.prefs(ctx).getString("status", "") ?: "") }
    DisposableEffect(Unit) {
        val p = Sync.prefs(ctx)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, k ->
            if (k == "status") syncStatus = sp.getString("status", "") ?: ""
        }
        p.registerOnSharedPreferenceChangeListener(l)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(l) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) editor = newTask(ctx).copy(title = text)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val act = ctx as? Activity
        if (act?.intent?.getBooleanExtra("new", false) == true) {
            act.intent.removeExtra("new")
            editor = newTask(ctx)
        }
        val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (!p.getBoolean("seeded", false)) {
            SEED.forEach { (n, c) -> dao.addCategory(Category(name = n, color = c)) }
            p.edit().putBoolean("seeded", true).apply()
        }
        Reminders.purgeExpired(ctx)
        Sync.schedulePush(ctx)
        WearBridge.pushList(ctx)
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = editor != null || selected.isNotEmpty() || searching) {
        when {
            editor != null -> editor = null
            selected.isNotEmpty() -> selected = emptySet()
            else -> { searching = false; query = "" }
        }
    }
    val healthy = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() && batteryOk(ctx) }

    Box(Modifier.fillMaxSize().background(appBackground())) {
        val e = editor
        if (e != null) {
            Editor(
                initial = e, categories = categories, lists = lists,
                onManageCategories = { showCats = true },
                onBack = { editor = null },
                onSave = { t -> editor = null; scope.launch { Reminders.save(ctx, t) } },
                onDelete = if (e.id != 0L) ({ editor = null; scope.launch { Reminders.delete(ctx, e) } }) else null
            )
        } else {
            val now = System.currentTimeMillis()
            val q = query.trim()
            // "ללא התראה" (filter == -1) is a separate list; "הכל" and the categories hold only reminders with an alert
            val shown = tasks.filter {
                val inList = if (q.isNotEmpty()) true
                else if (filter == NO_ALERT) it.alertMode == "NONE"
                else if (filter == IMPORTANT) it.important
                else if (filter == TODAY) it.alertMode != "NONE" && bucket(it, now) <= 1
                else it.alertMode != "NONE" && (filter == null || it.categoryId == filter)
                (showDone || !it.done) && inList &&
                    (q.isEmpty() || it.title.contains(q, true) || it.notes.contains(q, true))
            }
            val groups = shown.groupBy { bucket(it, now) }.toSortedMap()
            val hasGeneral = tasks.any { it.alertMode == "NONE" && it.listName.isBlank() }
            val lf = listFilter?.takeIf { if (it.isEmpty()) hasGeneral else it in lists }   // selected sub-list (null = all)
            val catById = categories.associateBy { it.id }
            Scaffold(containerColor = Color.Transparent) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 130.dp)) {
                        if (selected.isNotEmpty()) item {
                            SelectionBar(
                                selected.size, onClose = { selected = emptySet() },
                                onSchedule = { showBulkSchedule = true }, onCategory = { showBulkCat = true },
                                onDelete = { showBulkDelete = true },
                                onMove = if (tasks.any { it.id in selected && it.alertMode == "NONE" }) ({ showMove = true }) else null
                            )
                        } else item {
                            Row(
                                Modifier.padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("תזכורות", style = MaterialTheme.typography.headlineLarge)
                                    val n = shown.count { !it.done }
                                    Text(
                                        if (filter == NO_ALERT) (if (n == 0) "הרשימה ריקה" else "$n פריטים ברשימה ללא התראה")
                                        else if (filter == IMPORTANT) (if (n == 0) "אין תזכורות חשובות" else "$n תזכורות חשובות")
                                        else if (filter == TODAY) (if (n == 0) "אין תזכורות להיום" else "$n תזכורות להיום")
                                        else if (n == 0) "אין תזכורות פעילות" else "$n תזכורות פעילות",
                                        style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant
                                    )
                                    if (Sync.configured(ctx)) Text(
                                        syncStatus, style = MaterialTheme.typography.bodySmall,
                                        color = if (syncStatus.startsWith("❌")) cs.error else cs.onSurfaceVariant,
                                        modifier = Modifier.clickable { showSync = true }
                                    )
                                }
                                Surface(shape = RoundedCornerShape(28.dp), color = cs.surface, shadowElevation = 2.dp) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton({ searching = !searching; if (!searching) query = "" }) {
                                            Icon(Icons.Default.Search, "חיפוש")
                                        }
                                        Box {
                                            IconButton({ menuOpen = true }) {
                                                Icon(
                                                    if (healthy) Icons.Default.MoreVert else Icons.Default.Warning, "תפריט",
                                                    tint = if (healthy) cs.onSurface else cs.error
                                                )
                                            }
                                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                                DropdownMenuItem(
                                                    text = { Text((if (healthy) "" else "⚠ ") + "הגדרות") },
                                                    onClick = { menuOpen = false; showSettings = true }
                                                )
                                                val doneCount = tasks.count { it.done }
                                                DropdownMenuItem(
                                                    text = { Text((if (showDone) "הסתר" else "הצג") + " משימות שהושלמו ($doneCount)") },
                                                    onClick = {
                                                        menuOpen = false; showDone = !showDone
                                                        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("showDone", showDone).apply()
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("נקה משימות שהושלמו") },
                                                    enabled = doneCount > 0,
                                                    onClick = { menuOpen = false; showClearDone = true }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("סל מיחזור (${trash.size})") },
                                                    onClick = { menuOpen = false; showTrash = true }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("קטגוריות") },
                                                    onClick = { menuOpen = false; showCats = true }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("סנכרן עכשיו") },
                                                    onClick = {
                                                        menuOpen = false
                                                        scope.launch { Toast.makeText(ctx, Sync.syncNow(ctx), Toast.LENGTH_LONG).show() }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("סנכרון לענן") },
                                                    onClick = { menuOpen = false; showSync = true }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (searching) item {
                            OutlinedTextField(
                                query, { query = it }, placeholder = { Text("חיפוש") }, singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        item {
                            val live = tasks.filter { !it.done }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SummaryTile("היום", live.count { it.alertMode != "NONE" && bucket(it, now) <= 1 }, "📅", filter == TODAY, Modifier.weight(1f)) {
                                    filter = if (filter == TODAY) null else TODAY
                                }
                                SummaryTile("חשוב", live.count { it.important }, "⭐", filter == IMPORTANT, Modifier.weight(1f)) {
                                    filter = if (filter == IMPORTANT) null else IMPORTANT
                                }
                                SummaryTile("ללא התראה", live.count { it.alertMode == "NONE" }, "📝", filter == NO_ALERT, Modifier.weight(1f)) {
                                    filter = if (filter == NO_ALERT) null else NO_ALERT
                                }
                            }
                        }
                        item {
                            LazyRow(
                                Modifier.padding(vertical = 10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item { FilterChip(filter == null, { filter = null }, label = { Text("הכל") }) }
                                items(categories, key = { it.id }) { c ->
                                    FilterChip(filter == c.id, { filter = c.id }, label = { Text(c.name) }, leadingIcon = { Dot(c.color) })
                                }
                            }
                        }
                        if (filter == NO_ALERT) item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item { FilterChip(lf == null, { listFilter = null }, label = { Text("כל הרשימות") }) }
                                if (hasGeneral && lists.isNotEmpty()) item { FilterChip(lf == "", { listFilter = "" }, label = { Text("כללי") }) }
                                items(lists, key = { "ln$it" }) { n -> FilterChip(lf == n, { listFilter = n }, label = { Text(n) }) }
                                item { AssistChip({ showNewList = true }, label = { Text("＋ רשימה חדשה") }) }
                            }
                        }
                        if (shown.isEmpty()) {
                            item {
                                Box(Modifier.fillParentMaxWidth().padding(top = 80.dp), Alignment.Center) {
                                    Text("אין תזכורות כאן", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                        if (filter == NO_ALERT) {
                            val byList = shown.groupBy { it.listName.trim() }.filterKeys { lf == null || it == lf }
                            val names = byList.keys.filter { it.isNotEmpty() }.sorted() + (if (byList.containsKey("")) listOf("") else emptyList())
                            for (name in names) {
                                val rows = byList[name] ?: continue
                                if (names.size > 1 || name.isNotEmpty() || lf != null) item(key = "l$name") {
                                    ListHeader(
                                        if (name.isEmpty()) "כללי" else name, rows.count { !it.done }, editable = name.isNotEmpty(),
                                        onAdd = { editor = newTask(ctx, true).copy(listName = name) },
                                        onRename = { renameList = name }, onDelete = { deleteList = name }
                                    )
                                }
                                items(rows.sortedWith(compareByDescending<Task> { it.important }.thenBy { it.triggerAt }), key = { it.id }) { t ->
                                    TaskCard(
                                        t, catById[t.categoryId], overdue = false, selected = t.id in selected,
                                        onClick = {
                                            if (selected.isNotEmpty()) selected = if (t.id in selected) selected - t.id else selected + t.id
                                            else editor = t
                                        },
                                        onLongClick = { selected = selected + t.id },
                                        onToggle = { scope.launch { Reminders.setDone(ctx, t, !t.done) } }
                                    )
                                }
                            }
                        } else for ((k, list) in groups) {
                            if (k != 6) item(key = "h$k") {
                                Text(
                                    BUCKETS[k], Modifier.padding(horizontal = 28.dp).padding(top = 16.dp, bottom = 6.dp),
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                    color = if (k == 0) cs.error else cs.onSurface
                                )
                            }
                            items(list.sortedWith(compareByDescending<Task> { it.important }.thenBy { it.triggerAt }), key = { it.id }) { t ->
                                TaskCard(
                                    t, catById[t.categoryId], overdue = k == 0, selected = t.id in selected,
                                    onClick = {
                                        if (selected.isNotEmpty()) selected = if (t.id in selected) selected - t.id else selected + t.id
                                        else editor = t
                                    },
                                    onLongClick = { selected = selected + t.id },
                                    onToggle = { scope.launch { Reminders.setDone(ctx, t, !t.done) } }
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = { editor = newTask(ctx, filter == NO_ALERT).copy(important = filter == IMPORTANT, listName = if (filter == NO_ALERT) (lf ?: "") else "") },
                        shape = RoundedCornerShape(32.dp), color = cs.surface, shadowElevation = 10.dp,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(horizontal = 28.dp, vertical = 16.dp).fillMaxWidth().height(64.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("הוסף תזכורת", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "🎤", fontSize = 28.sp,
                                modifier = Modifier.clip(CircleShape).clickable {
                                    runCatching {
                                        voiceLauncher.launch(
                                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
                                        )
                                    }
                                }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMove) {
        val sel = tasks.filter { it.id in selected && it.alertMode == "NONE" }
        MoveToListDialog(lists, sel.size, onDismiss = { showMove = false }) { target ->
            showMove = false; selected = emptySet()
            scope.launch { sel.forEach { dao.upsert(it.copy(listName = target).touch()) }; Sync.schedulePush(ctx) }
        }
    }
    if (showNewList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewList = false },
            title = { Text("רשימה חדשה") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("למשל: קניות") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    showNewList = false
                    editor = newTask(ctx, true).copy(listName = name.trim())
                }) { Text("צור והוסף פריט") }
            },
            dismissButton = { TextButton({ showNewList = false }) { Text("ביטול") } }
        )
    }
    renameList?.let { old ->
        var name by remember { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renameList = null },
            title = { Text("שינוי שם הרשימה") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renameList = null
                    val n = name.trim()
                    scope.launch {
                        tasks.filter { it.alertMode == "NONE" && it.listName.trim() == old }.forEach { dao.upsert(it.copy(listName = n).touch()) }
                        Sync.schedulePush(ctx)
                    }
                }) { Text("שמור") }
            },
            dismissButton = { TextButton({ renameList = null }) { Text("ביטול") } }
        )
    }
    deleteList?.let { name ->
        val n = tasks.count { it.alertMode == "NONE" && it.listName.trim() == name }
        AlertDialog(
            onDismissRequest = { deleteList = null },
            title = { Text("למחוק את הרשימה \"$name\" ואת $n הפריטים שבה?") },
            text = { Text("הפריטים יעברו לסל המיחזור למשך 30 יום.") },
            confirmButton = {
                TextButton({
                    deleteList = null
                    scope.launch { tasks.filter { it.alertMode == "NONE" && it.listName.trim() == name }.forEach { Reminders.delete(ctx, it) } }
                }) { Text("מחק", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleteList = null }) { Text("ביטול") } }
        )
    }
    if (showSettings) SettingsDialog(tick, categories) { showSettings = false }
    if (showTrash) TrashDialog(
        trash, onDismiss = { showTrash = false },
        onRestore = { t -> scope.launch { Reminders.restore(ctx, t) } },
        onPurge = { t -> scope.launch { Reminders.purge(ctx, t) } },
        onEmpty = { scope.launch { trash.forEach { Reminders.purge(ctx, it) } }; showTrash = false }
    )
    if (showSync) SyncDialog { showSync = false }
    if (showBulkSchedule) {
        val sel = tasks.filter { it.id in selected }
        val init = if (sel.isNotEmpty() && sel.all { it.repeat == "SCHEDULE" } && sel.map { it.schedule() }.distinct().size == 1)
            sel[0].schedule() else emptyMap()
        BulkScheduleDialog(sel.size, init, onDismiss = { showBulkSchedule = false }, onApply = { m ->
            showBulkSchedule = false; selected = emptySet()
            scope.launch {
                sel.forEach {
                    Reminders.save(ctx, it.copy(
                        repeat = "SCHEDULE", slots = encodeSchedule(m), times = "",
                        days = m.keys.fold(0) { a, d -> a or (1 shl d) }
                    ))
                }
            }
        })
    }
    if (showBulkCat) {
        AlertDialog(
            onDismissRequest = { showBulkCat = false },
            title = { Text("קטגוריה ל-${selected.size} תזכורות") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val choose = { id: Long ->
                        val sel = tasks.filter { it.id in selected }
                        showBulkCat = false; selected = emptySet()
                        scope.launch { sel.forEach { dao.upsert(it.copy(categoryId = id).touch()) }; Sync.schedulePush(ctx) }
                        Unit
                    }
                    FilterChip(false, { choose(0L) }, label = { Text("ללא") })
                    categories.forEach { c -> FilterChip(false, { choose(c.id) }, label = { Text(c.name) }, leadingIcon = { Dot(c.color) }) }
                }
            },
            confirmButton = { TextButton({ showBulkCat = false }) { Text("סגור") } }
        )
    }
    if (showClearDone) {
        AlertDialog(
            onDismissRequest = { showClearDone = false },
            title = { Text("למחוק את כל המשימות שהושלמו (${tasks.count { it.done }})?") },
            confirmButton = {
                TextButton({
                    val done = tasks.filter { it.done }
                    showClearDone = false
                    scope.launch { done.forEach { Reminders.delete(ctx, it) }; Sync.schedulePush(ctx) }
                }) { Text("מחק", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showClearDone = false }) { Text("ביטול") } }
        )
    }
    if (showBulkDelete) {
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = { Text("למחוק ${selected.size} תזכורות?") },
            confirmButton = {
                TextButton({
                    val sel = tasks.filter { it.id in selected }
                    showBulkDelete = false; selected = emptySet()
                    scope.launch { sel.forEach { Reminders.delete(ctx, it) } }
                }) { Text("מחק", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showBulkDelete = false }) { Text("ביטול") } }
        )
    }
    if (showCats) CategoriesDialog(
        categories,
        onAdd = { n, c -> scope.launch { dao.addCategory(Category(name = n, color = c)); Sync.schedulePush(ctx) } },
        onDelete = { c ->
            scope.launch {
                dao.clearCategory(c.id); dao.deleteCategory(c.id)
                if (filter == c.id) filter = null
                Sync.schedulePush(ctx)
            }
        },
        onDismiss = { showCats = false }
    )
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onSchedule: () -> Unit, onCategory: () -> Unit, onDelete: () -> Unit, onMove: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp), color = cs.primaryContainer,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.Close, "בטל בחירה") }
                Text("$count נבחרו", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onDelete) { Icon(Icons.Default.Delete, "מחק", tint = cs.error) }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onMove != null) AssistChip(onMove, label = { Text("העבר לרשימה") })
                AssistChip(onCategory, label = { Text("קטגוריה") })
                AssistChip(onSchedule, label = { Text("ימים ושעות") })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    t: Task, cat: Category?, overdue: Boolean, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onToggle: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = if (selected) cs.primaryContainer else cs.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp).fillMaxWidth()
            .clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.Top) {
            if (t.repeat == "NONE") {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(if (t.done) cs.primary else Color.Transparent)
                        .border(2.dp, if (t.done) cs.primary else cs.outline, CircleShape)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) { if (t.done) Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(22.dp)) }
            } else {
                Box(Modifier.size(36.dp).clip(CircleShape).border(2.dp, cs.outline, CircleShape), contentAlignment = Alignment.Center) {
                    Text("↻", color = cs.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (t.important) "⭐ " else "") + t.title, style = MaterialTheme.typography.titleLarge,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) cs.onSurfaceVariant else cs.onSurface
                )
                if (t.alertMode != "NONE") Text(
                    (if (t.repeat != "NONE" && !(t.repeat == "LIST" && t.fired)) "הבא: " else "") + whenText(t.triggerAt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (overdue) cs.error else cs.onSurfaceVariant
                )
                if (!t.done && t.snoozeUntil > System.currentTimeMillis()) {
                    val z = java.time.Instant.ofEpochMilli(t.snoozeUntil).atZone(java.time.ZoneId.systemDefault())
                    Text("(במצב נודניק · נדחה ל-%02d:%02d)".format(z.hour, z.minute),
                        style = MaterialTheme.typography.bodyMedium, color = cs.tertiary, fontWeight = FontWeight.Bold)
                }
                if (t.repeat != "NONE" && t.alertMode != "NONE") {
                    // just a marker; tap it to see every repeat
                    var recOpen by remember { mutableStateOf(false) }
                    Text(
                        if (recOpen) "↻ חוזרת ▴\n" + recurrenceText(t) else "↻ חוזרת ▾",
                        style = MaterialTheme.typography.bodyMedium, color = cs.primary,
                        modifier = Modifier.clickable { recOpen = !recOpen }.padding(vertical = 2.dp)
                    )
                }
                if (t.notes.isNotBlank()) {
                    Text(
                        t.notes, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (cat != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(cat.color).copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(cat.color, 8)
                    Spacer(Modifier.width(6.dp))
                    Text(cat.name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(tick: Int, categories: List<Category>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val notifOk = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val battOk = remember(tick) { batteryOk(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הגדרות · v39") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
                var snooze by remember { mutableIntStateOf(prefs.getInt("snoozeMin", 10)) }
                Text("נודניק (בדקות)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { m ->
                        FilterChip(snooze == m, { snooze = m; prefs.edit().putInt("snoozeMin", m).apply() }, label = { Text("$m") })
                    }
                }
                Text("התראה חוזרת עד לטיפול (דקות)")
                var nag by remember { mutableIntStateOf(prefs.getInt("nagMin", 0)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2, 5, 10, 15).forEach { m ->
                        FilterChip(nag == m, { nag = m; prefs.edit().putInt("nagMin", m).apply() }, label = { Text(if (m == 0) "כבוי" else "$m") })
                    }
                }
                Text("כשפעילה, ההתראה תופיע שוב כל כמה דקות עד שתלחץ על \"הושלמה\" או \"נודניק\" (עד 12 פעמים).", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("ברירת מחדל להתראות חדשות", fontWeight = FontWeight.Bold)
                var defTime by remember { mutableStateOf(prefs.getString("defTime", "") ?: "") }
                var pickDefTime by remember { mutableStateOf(false) }
                if (pickDefTime) TimeWheelDialog(defTime.ifEmpty { "09:00" }, onDismiss = { pickDefTime = false }) {
                    defTime = it; prefs.edit().putString("defTime", it).apply(); pickDefTime = false
                }
                Text("שעה לתזכורת חדשה", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(defTime.isEmpty(), { defTime = ""; prefs.edit().putString("defTime", "").apply() }, label = { Text("השעה העגולה הבאה") })
                    FilterChip(defTime.isNotEmpty(), { pickDefTime = true }, label = { Text(if (defTime.isEmpty()) "שעה קבועה…" else defTime) })
                }
                var defCat by remember { mutableLongStateOf(prefs.getLong("defCat", 0L)) }
                Text("קטגוריה לתזכורת חדשה", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(defCat == 0L, { defCat = 0L; prefs.edit().putLong("defCat", 0L).apply() }, label = { Text("ללא") })
                    categories.forEach { c ->
                        FilterChip(defCat == c.id, { defCat = c.id; prefs.edit().putLong("defCat", c.id).apply() },
                            label = { Text(c.name) }, leadingIcon = { Dot(c.color) })
                    }
                }
                var defSound by remember { mutableStateOf(prefs.getString("defSound", "") ?: "") }
                var defVib by remember { mutableStateOf(prefs.getString("defVibration", "STRONG") ?: "STRONG") }
                val soundPick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    if (r.resultCode == Activity.RESULT_OK) {
                        @Suppress("DEPRECATION")
                        val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                        defSound = uri?.toString() ?: "silent"
                        prefs.edit().putString("defSound", defSound).apply()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("צליל", style = MaterialTheme.typography.bodySmall)
                        Text(soundLabel(ctx, defSound).replace("ברירת מחדל של האפליקציה", "ברירת המחדל של המכשיר"))
                    }
                    if (defSound != "silent") TextButton({
                        val uri = if (defSound.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else Uri.parse(defSound)
                        runCatching { RingtoneManager.getRingtone(ctx, uri).play() }
                    }) { Text("נגן") }
                    TextButton({
                        val cur: Uri? = when (defSound) {
                            "" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                            "silent" -> null
                            else -> Uri.parse(defSound)
                        }
                        soundPick.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
                        })
                    }) { Text("שנה") }
                }
                Text("רטט", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VIBRATIONS.forEach { (k, label) ->
                        FilterChip(defVib == k, {
                            defVib = k; prefs.edit().putString("defVibration", k).apply(); previewVibration(ctx, k)
                        }, label = { Text(label) })
                    }
                }
                Text("הצליל חל על תזכורות ללא צליל משלהן; הרטט חל על תזכורות חדשות.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text((if (notifOk) "✅" else "❌") + " התראות מופעלות")
                if (!notifOk) Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }) { Text("פתח הגדרות התראות") }

                Text((if (battOk) "✅" else "❌") + " סוללה: ללא הגבלה")
                if (!battOk) Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}")))
                }) { Text("אפשר ללא הגבלה") }

                Text("בסמסונג: הגדרות ← סוללה ← הגבלות שימוש ברקע, והוצא את האפליקציה מ-Sleeping apps ו-Deep sleeping apps.")
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${ctx.packageName}")))
                }) { Text("פרטי האפליקציה") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } }
    )
}

@Composable
private fun SummaryTile(label: String, count: Int, icon: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) cs.primaryContainer else cs.surface,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrashDialog(
    trash: List<Task>, onDismiss: () -> Unit, onRestore: (Task) -> Unit, onPurge: (Task) -> Unit, onEmpty: () -> Unit
) {
    val now = System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("סל מיחזור") },
        text = {
            if (trash.isEmpty()) Text("הסל ריק. תזכורות שנמחקו נשמרות כאן 30 יום.")
            else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("נשמרות 30 יום ואז נמחקות סופית.", style = MaterialTheme.typography.bodySmall)
                trash.sortedByDescending { it.deletedAt }.forEach { t ->
                    val left = (30 - (now - t.deletedAt) / 86_400_000L).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title.ifBlank { "(ללא כותרת)" }, maxLines = 2)
                            Text("יימחק בעוד $left ימים", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ onRestore(t) }) { Text("שחזר") }
                        TextButton({ onPurge(t) }) { Text("מחק", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("סגור") } },
        dismissButton = if (trash.isNotEmpty()) ({ TextButton(onEmpty) { Text("רוקן סל", color = MaterialTheme.colorScheme.error) } }) else null
    )
}

@Composable
private fun ListHeader(name: String, count: Int, editable: Boolean, onAdd: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 28.dp).padding(top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Text("  $count", color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        TextButton(onAdd) { Text("＋ פריט") }
        if (editable) Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "אפשרויות הרשימה") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("שנה שם") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("מחק רשימה", color = cs.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveToListDialog(lists: List<String>, count: Int, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("העבר $count פריטים לרשימה") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ onMove("") }, label = { Text("כללי") })
                    lists.forEach { n -> AssistChip({ onMove(n) }, label = { Text(n) }) }
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("או רשימה חדשה") })
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onMove(name.trim()) }) { Text("העבר לרשימה חדשה") } },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } }
    )
}
