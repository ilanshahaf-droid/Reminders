package com.ilan.reminders

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
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
    }
}

private val BUCKETS = listOf("באיחור", "היום", "מחר", "השבוע", "בהמשך", "הושלמו")
private val SEED = listOf("אישי" to 0xFF6366F1, "עבודה" to 0xFF0EA5E9, "משפחה" to 0xFFF59E0B, "בריאות" to 0xFF10B981)

private fun bucket(t: Task, now: Long): Int {
    if (t.done) return 5
    if (t.repeat == "NONE" && t.triggerAt < now) return 0
    val today = LocalDate.now()
    val d = Instant.ofEpochMilli(t.triggerAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        d <= today -> 1
        d == today.plusDays(1) -> 2
        d < today.plusDays(7) -> 3
        else -> 4
    }
}

private fun newTask(): Task {
    val z = ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    return Task(title = "", triggerAt = z.toInstant().toEpochMilli())
}

private fun batteryOk(c: Context) =
    c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

@Composable
fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    val tasks by dao.all().collectAsState(initial = emptyList())
    val categories by dao.categories().collectAsState(initial = emptyList())
    var editor by remember { mutableStateOf<Task?>(null) }
    var filter by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val act = ctx as? android.app.Activity
        if (act?.intent?.getBooleanExtra("new", false) == true) {
            act.intent.removeExtra("new")
            editor = newTask()
        }
        val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (!p.getBoolean("seeded", false)) {
            SEED.forEach { (n, c) -> dao.addCategory(Category(name = n, color = c)) }
            p.edit().putBoolean("seeded", true).apply()
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = editor != null) { editor = null }
    val healthy = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() && batteryOk(ctx) }

    val e = editor
    if (e != null) {
        Editor(
            initial = e, categories = categories,
            onManageCategories = { showCats = true },
            onBack = { editor = null },
            onSave = { t -> editor = null; scope.launch { Reminders.save(ctx, t) } },
            onDelete = if (e.id != 0L) ({ editor = null; scope.launch { Reminders.delete(ctx, e) } }) else null
        )
    } else {
        val now = System.currentTimeMillis()
        val shown = tasks.filter { filter == null || it.categoryId == filter }
        val groups = shown.groupBy { bucket(it, now) }.toSortedMap()
        val catById = categories.associateBy { it.id }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { editor = newTask() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("תזכורת חדשה") }
                )
            }
        ) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                item {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("תזכורות", style = MaterialTheme.typography.headlineLarge)
                            val n = tasks.count { !it.done }
                            Text(
                                if (n == 0) "אין תזכורות פעילות" else "$n תזכורות פעילות",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton({ showSettings = true }) {
                            Icon(
                                if (healthy) Icons.Default.Settings else Icons.Default.Warning, "הגדרות",
                                tint = if (healthy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                item {
                    LazyRow(
                        Modifier.padding(vertical = 14.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { FilterChip(filter == null, { filter = null }, label = { Text("הכל") }) }
                        items(categories, key = { it.id }) { c ->
                            FilterChip(filter == c.id, { filter = c.id }, label = { Text(c.name) }, leadingIcon = { Dot(c.color) })
                        }
                        item { AssistChip(onClick = { showCats = true }, label = { Text("ניהול") }) }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxWidth().padding(top = 80.dp), Alignment.Center) {
                            Text("אין תזכורות כאן. לחץ על ״תזכורת חדשה״", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                for ((k, list) in groups) {
                    item(key = "h$k") {
                        Text(
                            BUCKETS[k], Modifier.padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (k == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(list.sortedBy { it.triggerAt }, key = { it.id }) { t ->
                        TaskCard(
                            t, catById[t.categoryId], overdue = k == 0,
                            onClick = { editor = t },
                            onToggle = { scope.launch { Reminders.setDone(ctx, t, !t.done) } }
                        )
                    }
                }
            }
        }
    }

    if (showSettings) SettingsDialog(tick) { showSettings = false }
    if (showCats) CategoriesDialog(
        categories,
        onAdd = { n, c -> scope.launch { dao.addCategory(Category(name = n, color = c)) } },
        onDelete = { c -> scope.launch { dao.clearCategory(c.id); dao.deleteCategory(c.id); if (filter == c.id) filter = null } },
        onDismiss = { showCats = false }
    )
}

@Composable
private fun TaskCard(t: Task, cat: Category?, overdue: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.repeat == "NONE") {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (t.done) cs.primary else Color.Transparent)
                        .border(2.dp, if (t.done) cs.primary else cs.outline, CircleShape)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) { if (t.done) Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp)) }
            } else {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Text("↻", color = cs.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) cs.onSurfaceVariant else cs.onSurface
                )
                Text(
                    (if (t.repeat != "NONE") "הבא: " else "") + whenText(t.triggerAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (overdue) cs.error else cs.onSurfaceVariant
                )
                if (t.repeat != "NONE") {
                    Text(recurrenceText(t), style = MaterialTheme.typography.bodySmall, color = cs.primary)
                }
            }
            if (cat != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(cat.color).copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
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

@Composable
fun SettingsDialog(tick: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val notifOk = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val battOk = remember(tick) { batteryOk(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הגדרות אמינות") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
