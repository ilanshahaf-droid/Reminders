package com.ilan.reminders

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val DAY_LETTERS = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")
val PALETTE = listOf(0xFF6366F1, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFFEC4899, 0xFF8B5CF6)
private val VIBRATIONS = listOf("STRONG" to "חזק", "SHORT" to "קצר", "PULSE" to "פולסים", "NONE" to "ללא")

fun whenText(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = "%02d:%02d".format(z.hour, z.minute)
    return when (z.toLocalDate()) {
        today -> "היום $time"
        today.plusDays(1) -> "מחר $time"
        else -> z.format(DateTimeFormatter.ofPattern("EEE d/M", Locale("he"))) + " $time"
    }
}

fun recurrenceText(t: Task): String = when (t.repeat) {
    "SCHEDULE" -> {
        val ds = if (t.days == 127) "כל יום"
        else (0..6).filter { t.days and (1 shl it) != 0 }.joinToString(" ") { DAY_LETTERS[it] + "׳" }
        ds + " · " + t.times.replace(",", "  ")
    }
    "MONTHLY" -> "כל חודש"
    else -> ""
}

private fun soundLabel(c: Context, s: String): String = when (s) {
    "" -> "ברירת מחדל"
    "silent" -> "ללא צליל"
    else -> runCatching { RingtoneManager.getRingtone(c, Uri.parse(s)).getTitle(c) }.getOrDefault("צליל מותאם")
}

private fun previewVibration(c: Context, v: String) {
    val p = Notifier.patternOf(v) ?: return
    c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(p, -1))
}

@Composable
private fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun Dot(color: Long, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(color)))
}

@Composable
private fun DayCircle(letter: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (on) cs.primary else cs.secondaryContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, color = if (on) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Editor(
    initial: Task,
    categories: List<Category>,
    onManageCategories: () -> Unit,
    onBack: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: (() -> Unit)?
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var at by remember { mutableLongStateOf(initial.triggerAt.takeIf { it > 0 } ?: System.currentTimeMillis() + 3_600_000) }
    var days by remember { mutableIntStateOf(initial.days) }
    val times = remember { mutableStateListOf<String>().apply { addAll(initial.times.split(",").filter { it.isNotBlank() }) } }
    var categoryId by remember { mutableLongStateOf(initial.categoryId) }
    var sound by remember { mutableStateOf(initial.sound) }
    var vibration by remember { mutableStateOf(initial.vibration) }
    var confirmDelete by remember { mutableStateOf(false) }
    val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val valid = title.isNotBlank() && (repeat != "SCHEDULE" || (days != 0 && times.isNotEmpty()))

    val soundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            sound = uri?.toString() ?: "silent"
        }
    }

    fun pickTime(h: Int, m: Int, onPicked: (Int, Int) -> Unit) {
        TimePickerDialog(ctx, { _, hh, mm -> onPicked(hh, mm) }, h, m, true).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") }
                Text(if (initial.id == 0L) "תזכורת חדשה" else "עריכה", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onDelete != null) IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, "מחק", tint = MaterialTheme.colorScheme.error)
                }
                Button(enabled = valid, onClick = {
                    val sched = repeat == "SCHEDULE"
                    onSave(
                        initial.copy(
                            title = title.trim(), notes = notes.trim(), categoryId = categoryId,
                            triggerAt = if (sched) 0L else at, repeat = repeat,
                            days = if (sched) days else 0,
                            times = if (sched) times.sorted().joinToString(",") else "",
                            sound = sound, vibration = vibration
                        )
                    )
                }) { Text("שמור") }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Section {
                val clear = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
                TextField(
                    title, { title = it }, colors = clear, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    placeholder = { Text("מה להזכיר לך?") }
                )
                TextField(
                    notes, { notes = it }, colors = clear, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("הערות (לא חובה)") }
                )
            }

            Section("מתי") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(repeat == "NONE", { repeat = "NONE" }, label = { Text("חד-פעמי") })
                    FilterChip(repeat == "SCHEDULE", {
                        repeat = "SCHEDULE"
                        if (days == 0) days = 127
                        if (times.isEmpty()) times.add("%02d:%02d".format(z.hour, z.minute))
                    }, label = { Text("ימים ושעות") })
                    FilterChip(repeat == "MONTHLY", { repeat = "MONTHLY" }, label = { Text("חודשי") })
                }
                if (repeat == "SCHEDULE") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (i in 0..6) DayCircle(DAY_LETTERS[i], days and (1 shl i) != 0) { days = days xor (1 shl i) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ days = 127 }) { Text("כל יום") }
                        TextButton({ days = 31 }) { Text("א׳–ה׳") }
                        TextButton({ days = 96 }) { Text("ו׳–ש׳") }
                    }
                    Text("שעות", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        times.sorted().forEach { s ->
                            InputChip(false, { times.remove(s) }, label = { Text(s) },
                                trailingIcon = { Icon(Icons.Default.Close, "הסר", Modifier.size(16.dp)) })
                        }
                        AssistChip(onClick = {
                            pickTime(8, 0) { h, m ->
                                val s = "%02d:%02d".format(h, m)
                                if (s !in times) times.add(s)
                            }
                        }, label = { Text("＋ הוסף שעה") })
                    }
                    if (!valid && title.isNotBlank()) {
                        Text("בחר לפחות יום אחד ושעה אחת", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            DatePickerDialog(ctx, { _, y, m, d ->
                                at = z.withYear(y).withMonth(m + 1).withDayOfMonth(d).toInstant().toEpochMilli()
                            }, z.year, z.monthValue - 1, z.dayOfMonth).show()
                        }) { Text("${z.dayOfMonth}/${z.monthValue}/${z.year}") }
                        OutlinedButton(onClick = {
                            pickTime(z.hour, z.minute) { h, m ->
                                at = z.withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant().toEpochMilli()
                            }
                        }) { Text("%02d:%02d".format(z.hour, z.minute)) }
                    }
                }
            }

            Section("קטגוריה") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(categoryId == 0L, { categoryId = 0L }, label = { Text("ללא") })
                    categories.forEach { c ->
                        FilterChip(categoryId == c.id, { categoryId = c.id }, label = { Text(c.name) },
                            leadingIcon = { Dot(c.color) })
                    }
                    AssistChip(onClick = onManageCategories, label = { Text("ניהול") })
                }
            }

            Section("התראה") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("צליל", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(soundLabel(ctx, sound), style = MaterialTheme.typography.titleMedium)
                    }
                    if (sound != "silent") TextButton({
                        val uri = if (sound.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else Uri.parse(sound)
                        runCatching { RingtoneManager.getRingtone(ctx, uri).play() }
                    }) { Text("נגן") }
                    TextButton({
                        val cur: Uri? = when (sound) {
                            "" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                            "silent" -> null
                            else -> Uri.parse(sound)
                        }
                        soundLauncher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
                        })
                    }) { Text("שנה") }
                }
                Text("רטט", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VIBRATIONS.forEach { (k, label) ->
                        FilterChip(vibration == k, { vibration = k; previewVibration(ctx, k) }, label = { Text(label) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק את התזכורת?") },
            confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("מחק", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("ביטול") } }
        )
    }
}

@Composable
fun CategoriesDialog(
    cats: List<Category>,
    onAdd: (String, Long) -> Unit,
    onDelete: (Category) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableLongStateOf(PALETTE[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("קטגוריות") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(c.color, 14)
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, Modifier.weight(1f))
                        IconButton({ onDelete(c) }) { Icon(Icons.Default.Delete, "מחק") }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(name, { name = it }, label = { Text("קטגוריה חדשה") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c }
                        )
                    }
                }
                TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name.trim(), color); name = "" }) { Text("הוסף") }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("סגור") } }
    )
}
