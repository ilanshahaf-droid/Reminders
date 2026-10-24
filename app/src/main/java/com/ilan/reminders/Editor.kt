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
val DAY_NAMES = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
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
    "SCHEDULE" -> t.schedule().entries.groupBy({ it.value }, { it.key }).entries.joinToString("\n") { (ts, ds) ->
        val dt = if (ds.size == 7) "כל יום" else ds.sorted().joinToString(" ") { DAY_LETTERS[it] + "׳" }
        dt + " · " + ts.joinToString("  ")
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
    val sch = remember { ScheduleState(initial.schedule()) }
    var categoryId by remember { mutableLongStateOf(initial.categoryId) }
    var sound by remember { mutableStateOf(initial.sound) }
    var vibration by remember { mutableStateOf(initial.vibration) }
    var confirmDelete by remember { mutableStateOf(false) }
    val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val valid = title.isNotBlank() && (repeat != "SCHEDULE" || sch.valid())

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
        containerColor = Color.Transparent,
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
                            days = if (sched) sch.days else 0, times = "",
                            slots = if (sched) encodeSchedule(sch.build()) else "",
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
                    FilterChip(repeat == "SCHEDULE", { repeat = "SCHEDULE" }, label = { Text("ימים ושעות") })
                    FilterChip(repeat == "MONTHLY", { repeat = "MONTHLY" }, label = { Text("חודשי") })
                }
                if (repeat == "SCHEDULE") {
                    ScheduleEditor(sch)
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

class ScheduleState(initial: Map<Int, List<String>>) {
    var days by mutableIntStateOf(initial.keys.fold(0) { a, d -> a or (1 shl d) })
    var diff by mutableStateOf(initial.values.distinct().size > 1)
    val shared = mutableStateListOf<String>().apply { addAll(initial.values.firstOrNull() ?: emptyList()) }
    val perDay = mutableStateMapOf<Int, List<String>>().apply { putAll(initial) }

    fun applyDays(mask: Int) {
        days = mask
        if (diff) for (i in 0..6) {
            if (mask and (1 shl i) != 0) {
                if (i !in perDay) perDay[i] = shared.toList()
            } else perDay.remove(i)
        }
    }

    fun toggle(i: Int) = applyDays(days xor (1 shl i))

    fun applyDiff(v: Boolean) {
        if (v == diff) return
        if (v) {
            for (i in 0..6) if (days and (1 shl i) != 0 && i !in perDay) perDay[i] = shared.toList()
        } else {
            val u = perDay.values.flatten().distinct().sorted()
            shared.clear(); shared.addAll(u)
        }
        diff = v
    }

    fun valid(): Boolean = days != 0 && (
        if (diff) (0..6).filter { days and (1 shl it) != 0 }.all { perDay[it]?.isNotEmpty() == true }
        else shared.isNotEmpty()
    )

    fun build(): Map<Int, List<String>> = (0..6).filter { days and (1 shl it) != 0 }
        .associateWith { if (diff) (perDay[it] ?: emptyList()).sorted() else shared.sorted() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeChips(times: List<String>, onRemove: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        times.forEach { s ->
            InputChip(false, { onRemove(s) }, label = { Text(s) },
                trailingIcon = { Icon(Icons.Default.Close, "הסר", Modifier.size(16.dp)) })
        }
        AssistChip(onClick = onAdd, label = { Text("＋ שעה") })
    }
}

@Composable
fun ScheduleEditor(s: ScheduleState) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    fun pick(onPicked: (String) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onPicked("%02d:%02d".format(h, m)) }, 8, 0, true).show()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0..6) DayCircle(DAY_LETTERS[i], s.days and (1 shl i) != 0) { s.toggle(i) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton({ s.applyDays(127) }) { Text("כל יום") }
        TextButton({ s.applyDays(31) }) { Text("א׳–ה׳") }
        TextButton({ s.applyDays(96) }) { Text("ו׳–ש׳") }
        TextButton({ s.applyDays(0) }) { Text("נקה") }
    }
    if (s.days == 0) {
        Text("בחר את הימים שבהם התזכורת תפעל", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("שעות שונות לכל יום", Modifier.weight(1f))
        Switch(s.diff, { s.applyDiff(it) })
    }
    if (!s.diff) {
        TimeChips(s.shared.sorted(), onRemove = { s.shared.remove(it) },
            onAdd = { pick { t -> if (t !in s.shared) s.shared.add(t) } })
    } else {
        for (i in 0..6) if (s.days and (1 shl i) != 0) {
            val list = s.perDay[i] ?: emptyList()
            Row(verticalAlignment = Alignment.Top) {
                Text("יום ${DAY_NAMES[i]}", Modifier.width(76.dp).padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                TimeChips(list, onRemove = { r -> s.perDay[i] = list - r },
                    onAdd = { pick { t -> if (t !in list) s.perDay[i] = (list + t).sorted() } }, modifier = Modifier.weight(1f))
            }
        }
    }
    if (!s.valid()) {
        Text(
            if (s.diff) "הוסף שעה אחת לפחות לכל יום שנבחר" else "הוסף שעה אחת לפחות",
            color = cs.error, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun BulkScheduleDialog(count: Int, initial: Map<Int, List<String>>, onApply: (Map<Int, List<String>>) -> Unit, onDismiss: () -> Unit) {
    val sch = remember { ScheduleState(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ימים ושעות ל-$count תזכורות") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("התזכורות שנבחרו יהפכו לתזכורות שבועיות לפי ההגדרה כאן.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScheduleEditor(sch)
            }
        },
        confirmButton = { TextButton(enabled = sch.valid(), onClick = { onApply(sch.build()) }) { Text("החל") } },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } }
    )
}
