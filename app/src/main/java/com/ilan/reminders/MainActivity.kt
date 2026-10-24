package com.ilan.reminders

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Root() }
            }
        }
    }
}

private val REPEATS = listOf("NONE" to "ללא", "DAILY" to "יומי", "WEEKLY" to "שבועי", "MONTHLY" to "חודשי")
private val FMT = DateTimeFormatter.ofPattern("EEE, d/M/yyyy HH:mm", Locale("he"))
private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(FMT)

private fun batteryOk(c: Context) =
    c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    val tasks by dao.all().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Task?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val healthy = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() && batteryOk(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("תזכורות") },
                actions = { TextButton(onClick = { showSettings = true }) { Text(if (healthy) "הגדרות" else "⚠ הגדרות") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val z = ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                editing = Task(title = "", triggerAt = z.toInstant().toEpochMilli())
            }) { Icon(Icons.Default.Add, "הוסף") }
        }
    ) { pad ->
        if (tasks.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { Text("אין משימות. לחץ + להוספה") }
        } else LazyColumn(Modifier.padding(pad)) {
            items(tasks, key = { it.id }) { t ->
                ListItem(
                    modifier = Modifier.clickable { editing = t },
                    leadingContent = {
                        Checkbox(checked = t.done, onCheckedChange = { scope.launch { Reminders.setDone(ctx, t, it) } })
                    },
                    headlineContent = { Text(t.title) },
                    supportingContent = {
                        val rep = REPEATS.first { it.first == t.repeat }.second
                        Text(fmt(t.triggerAt) + if (t.repeat != "NONE") " · $rep" else "")
                    },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { Reminders.delete(ctx, t) } }) {
                            Icon(Icons.Default.Delete, "מחק")
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { t ->
        EditDialog(t, onDismiss = { editing = null }) { saved ->
            editing = null
            scope.launch { Reminders.save(ctx, saved) }
        }
    }
    if (showSettings) SettingsDialog(tick) { showSettings = false }
}

@Composable
fun EditDialog(initial: Task, onDismiss: () -> Unit, onSave: (Task) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var at by remember { mutableLongStateOf(initial.triggerAt) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "משימה חדשה" else "עריכה") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("כותרת") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("הערות") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        DatePickerDialog(ctx, { _, y, m, d ->
                            at = z.withYear(y).withMonth(m + 1).withDayOfMonth(d).toInstant().toEpochMilli()
                        }, z.year, z.monthValue - 1, z.dayOfMonth).show()
                    }) { Text("${z.dayOfMonth}/${z.monthValue}/${z.year}") }
                    OutlinedButton(onClick = {
                        TimePickerDialog(ctx, { _, h, min ->
                            at = z.withHour(h).withMinute(min).withSecond(0).withNano(0).toInstant().toEpochMilli()
                        }, z.hour, z.minute, true).show()
                    }) { Text("%02d:%02d".format(z.hour, z.minute)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REPEATS.forEach { (k, label) ->
                        FilterChip(selected = repeat == k, onClick = { repeat = k }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(initial.copy(title = title.trim(), notes = notes.trim(), triggerAt = at, repeat = repeat))
            }) { Text("שמור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
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
