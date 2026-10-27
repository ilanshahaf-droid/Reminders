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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.NumberPicker
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val DAY_LETTERS = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")
val DAY_NAMES = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
val PALETTE = listOf(0xFF6366F1, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFFEC4899, 0xFF8B5CF6)
internal val VIBRATIONS = listOf("STRONG" to "חזק", "SHORT" to "קצר", "PULSE" to "פולסים", "NONE" to "ללא")

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
    "LIST" -> t.dateList().let { l ->
        "${l.size} התראות: " + l.joinToString("  ·  ") {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d/M HH:mm"))
        }
    }
    else -> ""
}

internal fun soundLabel(c: Context, s: String): String = when (s) {
    "" -> "ברירת מחדל של האפליקציה"
    "silent" -> "ללא צליל"
    else -> runCatching { RingtoneManager.getRingtone(c, Uri.parse(s)).getTitle(c) }.getOrDefault("צליל מותאם")
}

internal fun previewVibration(c: Context, v: String) {
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
    var alertMode by remember { mutableStateOf(initial.alertMode) }
    val dates = remember {
        mutableStateListOf<Long>().apply {
            val saved = initial.dateList()
            if (saved.isNotEmpty()) addAll(saved)
            else {
                val base = initial.triggerAt.takeIf { it > 0 } ?: (System.currentTimeMillis() + 3_600_000)
                for (i in 0 until 3) add(base + i * 86_400_000L)
            }
        }
    }
    fun setCount(n: Int) {
        while (dates.size < n) dates.add((dates.lastOrNull() ?: System.currentTimeMillis()) + 86_400_000L)
        while (dates.size > n) dates.removeAt(dates.size - 1)
    }
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
                            triggerAt = if (sched) 0L else if (repeat == "LIST") (dates.minOrNull() ?: at) else at, repeat = repeat,
                            days = if (sched) sch.days else 0, times = "",
                            slots = when {
                                sched -> encodeSchedule(sch.build())
                                repeat == "LIST" -> dates.sorted().joinToString(",")
                                else -> ""
                            },
                            sound = sound, vibration = vibration,
                            alertMode = alertMode
                        )
                    )
                }) { Text("שמור") }
            }
        }
    ) { pad ->
        val scrollSt = rememberScrollState()
        val kbc = LocalSoftwareKeyboardController.current
        val fm = LocalFocusManager.current
        LaunchedEffect(scrollSt.isScrollInProgress) { if (scrollSt.isScrollInProgress) { kbc?.hide(); fm.clearFocus() } }
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(scrollSt)
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
                    FilterChip(repeat == "LIST", { repeat = "LIST" }, label = { Text("מספר חזרות מוגדר") })
                }
                if (repeat == "SCHEDULE") {
                    ScheduleEditor(sch)
                } else if (repeat == "LIST") {
                    Text("כמה התראות?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..10).forEach { n -> FilterChip(dates.size == n, { setCount(n) }, label = { Text("$n") }) }
                    }
                    dates.forEachIndexed { i, ms ->
                        Text("התראה ${i + 1}", style = MaterialTheme.typography.labelLarge)
                        WhenPicker(ms, quick = false) { dates[i] = it }
                    }
                } else {
                    WhenPicker(at, quick = true) { at = it }
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ONCE" to "עם התראה", "NONE" to "ללא התראה (רק ברשימה)").forEach { (k, l) ->
                        FilterChip(alertMode == k, { alertMode = k }, label = { Text(l) })
                    }
                }
                if (alertMode != "NONE") {
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

/** "08:45", "845", "0845" -> minutes since midnight, or null when it is not a valid time. */
private fun parseHm(raw: String): Int? {
    val s = raw.trim()
    val (h, m) = if (s.contains(':')) {
        val p = s.split(":")
        if (p.size != 2) return null
        (p[0].toIntOrNull() ?: return null) to (p[1].toIntOrNull() ?: return null)
    } else {
        val d = s.filter { it.isDigit() }
        when (d.length) {
            3 -> d.substring(0, 1).toInt() to d.substring(1).toInt()
            4 -> d.substring(0, 2).toInt() to d.substring(2).toInt()
            else -> return null
        }
    }
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

/** One place to set a moment: quick presets, today/tomorrow chips, date picker, and a time you can simply type. */
private val MONTHS = arrayOf("ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יוני", "יולי", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳")
private val TWO = Array(60) { "%02d".format(it) }

/** A scrolling wheel (like the system date/time spinner). */
@Composable
private fun Wheel(value: Int, min: Int, max: Int, labels: Array<String>?, color: Int, widthDp: Int, onChange: (Int) -> Unit) {
    AndroidView(
        modifier = Modifier.width(widthDp.dp).height(128.dp),
        factory = { c ->
            NumberPicker(c).apply {
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                wrapSelectorWheel = true
            }
        },
        update = { np ->
            np.setOnValueChangedListener(null)
            np.displayedValues = null
            np.minValue = min; np.maxValue = max
            if (labels != null) np.displayedValues = labels.copyOfRange(0, max - min + 1)
            np.value = value
            if (android.os.Build.VERSION.SDK_INT >= 29) np.textColor = color
            np.setOnValueChangedListener { _, _, v -> onChange(v) }
        }
    )
}

/** Date wheels (day / month / year) over time wheels (hour : minute), like the system picker. */
@Composable
internal fun WheelDateTime(ms: Long, onChange: (Long) -> Unit) {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val focus = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    fun set(y: Int = z.year, mo: Int = z.monthValue, d: Int = z.dayOfMonth, h: Int = z.hour, mi: Int = z.minute) {
        focus.clearFocus(); kb?.hide()
        val dd = minOf(d, java.time.YearMonth.of(y, mo).lengthOfMonth())
        onChange(java.time.LocalDateTime.of(y, mo, dd, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }
    val dim = java.time.YearMonth.of(z.year, z.monthValue).lengthOfMonth()
    val y0 = minOf(LocalDate.now().year, z.year)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Wheel(z.dayOfMonth, 1, dim, null, color, 84) { set(d = it) }
            Wheel(z.monthValue, 1, 12, MONTHS, color, 96) { set(mo = it) }
            Wheel(z.year, y0, y0 + 10, null, color, 96) { set(y = it) }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel(z.hour, 0, 23, TWO, color, 96) { set(h = it) }
                Text(":", style = MaterialTheme.typography.headlineMedium)
                Wheel(z.minute, 0, 59, TWO, color, 96) { set(mi = it) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhenPicker(ms: Long, quick: Boolean, onChange: (Long) -> Unit) {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    fun atTime(date: LocalDate, h: Int, m: Int) =
        date.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val focus = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    fun pick(v: Long) { focus.clearFocus(); kb?.hide(); onChange(v) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(whenText(ms), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(z.toLocalDate() == today, { pick(z.with(today).toInstant().toEpochMilli()) }, label = { Text("היום") })
            FilterChip(z.toLocalDate() == today.plusDays(1), { pick(z.with(today.plusDays(1)).toInstant().toEpochMilli()) }, label = { Text("מחר") })
        }
        WheelDateTime(ms, onChange)
        if (quick) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val nowMin = System.currentTimeMillis() / 60_000 * 60_000
            listOf("בעוד 5 דק׳" to 5, "בעוד 15 דק׳" to 15, "בעוד שעה" to 60).forEach { (l, m) ->
                AssistChip(onClick = { pick(nowMin + m * 60_000L) }, label = { Text(l) })
            }
            AssistChip(onClick = { pick(atTime(today, 20, 0)) }, label = { Text("הערב 20:00") })
            AssistChip(onClick = { pick(atTime(today.plusDays(1), 8, 0)) }, label = { Text("מחר 08:00") })
        }
    }
}
