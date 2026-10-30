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
import androidx.compose.foundation.relocation.bringIntoViewRequester
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

/** "06:45" or "06:45 (כל שבועיים)". */
fun slotLabel(tok: String): String {
    val s = parseSlot(tok)
    return if (s.every > 1) s.time + " (" + everyText(s.every, "w") + ")" else s.time
}

/** "כל יום" / "כל יומיים" / "כל 3 ימים", and the same for weeks (w) and months (m). */
fun everyText(n: Int, unit: String): String = when (unit) {
    "d" -> when (n) { 1 -> "כל יום"; 2 -> "כל יומיים"; else -> "כל $n ימים" }
    "w" -> when (n) { 1 -> "כל שבוע"; 2 -> "כל שבועיים"; else -> "כל $n שבועות" }
    else -> when (n) { 1 -> "כל חודש"; 2 -> "כל חודשיים"; else -> "כל $n חודשים" }
}

fun recurrenceText(t: Task): String = when (t.repeat) {
    "SCHEDULE" -> t.schedule().entries.groupBy({ it.value }, { it.key }).entries.joinToString("\n") { (ts, ds) ->
        val dt = if (ds.size == 7) "כל יום" else ds.sorted().joinToString(" ") { DAY_LETTERS[it] + "׳" }
        dt + " · " + ts.joinToString("  ") { slotLabel(it) }
    }
    "MONTHLY" -> everyText(t.every, "m")
    "DAILY" -> everyText(t.every, "d")
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
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    lists: List<String> = emptyList(),
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
    var every by remember { mutableIntStateOf(initial.every.coerceIn(1, 99)) }
    var lastRecurring by remember { mutableStateOf(if (initial.repeat in listOf("DAILY", "SCHEDULE", "MONTHLY")) initial.repeat else "SCHEDULE") }
    var categoryId by remember { mutableLongStateOf(initial.categoryId) }
    var sound by remember { mutableStateOf(initial.sound) }
    var vibration by remember { mutableStateOf(initial.vibration) }
    var alertMode by remember { mutableStateOf(initial.alertMode) }
    var important by remember { mutableStateOf(initial.important) }
    var listName by remember { mutableStateOf(initial.listName) }
    var newListDlg by remember { mutableStateOf(false) }
    val extraLists = remember { mutableStateListOf<String>() }   // lists created while editing: keep their chips when another list is picked
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
    val valid = title.isNotBlank() && (alertMode == "NONE" || repeat != "SCHEDULE" || sch.valid())

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
                    val noAlert = alertMode == "NONE"
                    val sched = repeat == "SCHEDULE" && !noAlert
                    onSave(
                        initial.copy(
                            title = title.trim(), notes = notes.trim(), categoryId = categoryId,
                            triggerAt = if (noAlert) (if (initial.alertMode == "NONE" && initial.triggerAt > 0) initial.triggerAt else System.currentTimeMillis())
                                else if (sched) 0L else if (repeat == "LIST") (dates.minOrNull() ?: at) else at,
                            repeat = if (noAlert) "NONE" else repeat,
                            every = if (!noAlert && repeat in listOf("DAILY", "MONTHLY")) every else 1,
                            anchor = 0L,
                            days = if (sched) sch.days else 0, times = "",
                            slots = when {
                                noAlert -> ""
                                sched -> encodeSchedule(sch.build())
                                repeat == "LIST" -> dates.sorted().joinToString(",")
                                else -> ""
                            },
                            sound = sound, vibration = vibration,
                            alertMode = alertMode, important = important,
                            listName = if (noAlert) listName.trim() else ""
                        )
                    )
                }) { Text("שמור") }
            }
        }
    ) { pad ->
        val scrollSt = rememberScrollState()
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(scrollSt)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                val clear = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
                TextField(
                    title, { title = it }, colors = clear, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.titleLarge,
                    placeholder = { Text("מה להזכיר לך?", style = MaterialTheme.typography.titleLarge) }
                )
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ONCE" to "עם התראה", "NONE" to "ללא התראה").forEach { (k, l) ->
                    FilterChip(alertMode == k, { alertMode = k }, label = { Text(l) })
                }
                FilterChip(important, { important = !important }, label = { Text("⭐ חשוב") })
            }
            if (alertMode == "NONE") Section("רשימה") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(listName.isBlank(), { listName = "" }, label = { Text("כללי") })
                    (lists + extraLists + listOf(listName.trim()).filter { it.isNotEmpty() }).distinct().forEach { n ->
                        FilterChip(listName.trim() == n, { listName = n }, label = { Text(n) })
                    }
                    AssistChip(onClick = { newListDlg = true }, label = { Text("＋ רשימה חדשה") })
                }
            }
            if (alertMode != "NONE") {
            Section("מתי") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(repeat == "NONE", { repeat = "NONE" }, label = { Text("חד-פעמי") })
                    val recurring = repeat in listOf("DAILY", "SCHEDULE", "MONTHLY")
                    FilterChip(recurring, { repeat = lastRecurring }, label = { Text("חוזרת") })
                    FilterChip(repeat == "LIST", { repeat = "LIST" }, label = { Text("מספר חזרות מוגדר") })
                }
                if (repeat in listOf("DAILY", "SCHEDULE", "MONTHLY")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("DAILY" to "יום", "SCHEDULE" to "שבוע", "MONTHLY" to "חודש").forEach { (k, l) ->
                            FilterChip(repeat == k, { repeat = k; lastRecurring = k }, label = { Text(l) })
                        }
                    }
                    if (repeat != "SCHEDULE") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("כל", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton({ if (every > 1) every-- }, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Text("$every", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        OutlinedButton({ if (every < 99) every++ }, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        Text(
                            when (repeat) { "DAILY" -> if (every == 1) "יום" else "ימים"; "SCHEDULE" -> if (every == 1) "שבוע" else "שבועות"; else -> if (every == 1) "חודש" else "חודשים" },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
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

            if (alertMode != "NONE") Section("התראה") {
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

    if (newListDlg) {
        var nm by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newListDlg = false },
            title = { Text("רשימה חדשה") },
            text = { OutlinedTextField(nm, { nm = it }, singleLine = true, placeholder = { Text("למשל: קניות") }) },
            confirmButton = { TextButton(enabled = nm.isNotBlank(), onClick = { listName = nm.trim(); extraLists.add(nm.trim()); newListDlg = false }) { Text("צור") } },
            dismissButton = { TextButton({ newListDlg = false }) { Text("ביטול") } }
        )
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
private fun TimeChips(times: List<String>, onEdit: (String) -> Unit, onRemove: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        times.forEach { s ->
            // tap the time to edit it; tap the X to remove it
            InputChip(false, { onEdit(s) }, label = { Text(slotLabel(s)) },
                trailingIcon = { Icon(Icons.Default.Close, "הסר", Modifier.size(20.dp).clip(CircleShape).clickable { onRemove(s) }.padding(2.dp)) })
        }
        AssistChip(onClick = onAdd, label = { Text("＋ שעה") })
    }
}

@Composable
fun ScheduleEditor(s: ScheduleState) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var pending by remember { mutableStateOf<SlotEdit?>(null) }
    pending?.let { e ->
        SlotDialog(e.initial, e.daysMask, onDismiss = { pending = null }) { tok -> pending = null; e.apply(tok) }
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
            onEdit = { old ->
                pending = SlotEdit(old, s.days) { n ->
                    val l = s.shared.toMutableList(); val i = l.indexOf(old); if (i >= 0) l[i] = n else l.add(n)
                    s.shared.clear(); s.shared.addAll(l.distinct())
                }
            },
            onAdd = { pending = SlotEdit("08:00", s.days) { t -> if (t !in s.shared) s.shared.add(t) } })
    } else {
        for (i in 0..6) if (s.days and (1 shl i) != 0) {
            val list = s.perDay[i] ?: emptyList()
            Row(verticalAlignment = Alignment.Top) {
                Text("יום ${DAY_NAMES[i]}", Modifier.width(76.dp).padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                TimeChips(list, onRemove = { r -> s.perDay[i] = list - r },
                    onEdit = { old ->
                        pending = SlotEdit(old, 1 shl i) { n ->
                            s.perDay[i] = (list.map { if (it == old) n else it }).distinct().sorted()
                        }
                    },
                    onAdd = { pending = SlotEdit("08:00", 1 shl i) { t -> if (t !in list) s.perDay[i] = (list + t).sorted() } },
                    modifier = Modifier.weight(1f))
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

/** What the user typed into a wheel's number box, even if the wheel has not committed it yet (no need to tap elsewhere first). */
private fun typedValue(np: NumberPicker?, cur: Int, lo: Int, hi: Int): Int {
    val v = (np?.getChildAt(0) as? android.widget.EditText)?.text?.toString()?.trim()?.toIntOrNull()
    return v?.coerceIn(lo, hi) ?: cur
}

@Composable
private fun HmWheels(h: Int, m: Int, views: Array<NumberPicker?>, onH: (Int) -> Unit, onM: (Int) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Wheel(h, 0, 23, color, 100, {}, { views[0] = it }) { onH(it) }
            Text(":", style = MaterialTheme.typography.displaySmall)
            Wheel(m, 0, 59, color, 100, {}, { views[1] = it }) { onM(it) }
        }
    }
}

/** The same hour:minute wheels used for one-off reminders, in a small dialog. */
@Composable
internal fun TimeWheelDialog(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val parts = initial.split(":")
    var h by remember { mutableIntStateOf(parts.getOrNull(0)?.toIntOrNull() ?: 8) }
    var m by remember { mutableIntStateOf(parts.getOrNull(1)?.toIntOrNull() ?: 0) }
    val views = remember { arrayOfNulls<NumberPicker>(2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("בחר שעה") },
        text = { HmWheels(h, m, views, { h = it }, { m = it }) },
        confirmButton = {
            TextButton({ onPicked("%02d:%02d".format(typedValue(views[0], h, 0, 23), typedValue(views[1], m, 0, 59))) }) { Text("אישור") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } }
    )
}

private class SlotEdit(val initial: String, val daysMask: Int, val apply: (String) -> Unit)

/** One weekly time: hour:minute, and how often it repeats (every N weeks, counted from a start date). */
@Composable
private fun SlotDialog(initial: String, daysMask: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val sl0 = parseSlot(initial)
    var h by remember { mutableIntStateOf(sl0.time.substringBefore(":").toIntOrNull() ?: 8) }
    var m by remember { mutableIntStateOf(sl0.time.substringAfter(":", "0").toIntOrNull() ?: 0) }
    var every by remember { mutableIntStateOf(sl0.every.coerceIn(1, 52)) }
    var anchorDay by remember { mutableLongStateOf(if (sl0.anchorDay > 0) sl0.anchorDay else LocalDate.now().toEpochDay()) }
    var pickDate by remember { mutableStateOf(false) }
    val views = remember { arrayOfNulls<NumberPicker>(2) }
    val zone = ZoneId.systemDefault()
    fun token(): Slot = Slot("%02d:%02d".format(h, m), every, if (every > 1) anchorDay else 0L)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שעה ותדירות") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HmWheels(h, m, views, { h = it }, { m = it })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("כל", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton({ if (every > 1) every-- }, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("$every", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    OutlinedButton({ if (every < 52) every++ }, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    Text(if (every == 1) "שבוע" else "שבועות", style = MaterialTheme.typography.titleMedium)
                }
                if (every > 1) {
                    val d = LocalDate.ofEpochDay(anchorDay)
                    OutlinedButton({ pickDate = !pickDate }) { Text("החל מתאריך: ${d.dayOfMonth}/${d.monthValue}/${d.year}") }
                    if (pickDate) CalendarPick(d.atStartOfDay(zone).toInstant().toEpochMilli()) {
                        anchorDay = Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay(); pickDate = false
                    }
                    val first = Reminders.nextOccurrence(
                        Task(title = "", triggerAt = 0,
                            slots = encodeSchedule((0..6).filter { daysMask and (1 shl it) != 0 }.associateWith { listOf(token().token()) })),
                        System.currentTimeMillis()
                    )
                    if (first != null) Text("הפעם הראשונה: " + whenText(first), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton({
                h = typedValue(views[0], h, 0, 23); m = typedValue(views[1], m, 0, 59)
                onSave(token().token())
            }) { Text("אישור") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } }
    )
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
/** A scrolling wheel; tap the centre number to type it with the keyboard. */
@Composable
private fun Wheel(value: Int, min: Int, max: Int, color: Int, widthDp: Int, onTouch: () -> Unit, onView: (NumberPicker) -> Unit = {}, onChange: (Int) -> Unit) {
    AndroidView(
        modifier = Modifier.width(widthDp.dp).height(128.dp),
        factory = { c ->
            NumberPicker(c).apply {
                wrapSelectorWheel = true
                setFormatter { "%02d".format(it) }
                // the system filter would reject the zero-padded text ("08") on first draw
                (getChildAt(0) as? android.widget.EditText)?.filters = arrayOf<android.text.InputFilter>()
                onView(this)
            }
        },
        update = { np ->
            np.setOnValueChangedListener(null)
            np.minValue = min; np.maxValue = max
            np.value = value
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                np.textColor = color
                np.textSize = 28f * np.resources.displayMetrics.scaledDensity
            }
            np.setOnValueChangedListener { _, _, v -> onChange(v) }
            np.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) onTouch(); false }
        }
    )
}

/** Month grid that always fits the width (Sunday on the right). */
@Composable
private fun CalendarPick(ms: Long, onPicked: (Long) -> Unit) {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val sel = z.toLocalDate()
    val today = LocalDate.now()
    var ym by remember { mutableStateOf(java.time.YearMonth.from(sel)) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ ym = ym.minusMonths(1) }) { Text("▶", style = MaterialTheme.typography.titleMedium) }
            Text(
                ym.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("he"))),
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            TextButton({ ym = ym.plusMonths(1) }) { Text("◀", style = MaterialTheme.typography.titleMedium) }
        }
        Row(Modifier.fillMaxWidth()) {
            DAY_LETTERS.forEach { l ->
                Text(l, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
        val first = ym.atDay(1).dayOfWeek.value % 7
        val rows = (first + ym.lengthOfMonth() + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (cIdx in 0 until 7) {
                    val dayNum = r * 7 + cIdx - first + 1
                    if (dayNum < 1 || dayNum > ym.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(44.dp))
                    } else {
                        val d = ym.atDay(dayNum)
                        val on = d == sel
                        Box(
                            Modifier.weight(1f).height(44.dp).padding(2.dp).clip(CircleShape)
                                .background(if (on) cs.primary else Color.Transparent)
                                .clickable { onPicked(z.with(d).toInstant().toEpochMilli()) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$dayNum",
                                color = if (on) cs.onPrimary else if (d == today) cs.primary else cs.onSurface,
                                fontWeight = if (on || d == today) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun dayLabel(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (z.toLocalDate()) {
        today -> "היום"
        today.plusDays(1) -> "מחר"
        else -> "${DAY_LETTERS[z.dayOfWeek.value % 7]}׳ ${z.dayOfMonth}/${z.monthValue}/${z.year % 100}"
    }
}

/** Day button + time button (like Samsung Reminder); the chosen one opens a calendar or hour:minute wheels. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun WhenPicker(ms: Long, quick: Boolean, onChange: (Long) -> Unit) {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val focus = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    var showCalendar by remember { mutableStateOf(false) }
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var wheelTouched by remember { mutableStateOf(false) }
    val imeUp = WindowInsets.isImeVisible
    LaunchedEffect(imeUp, wheelTouched) {
        if (imeUp && wheelTouched) { kotlinx.coroutines.delay(300); bring.bringIntoView() }
        if (!imeUp) wheelTouched = false
    }
    fun atTime(date: LocalDate, h: Int, m: Int) =
        date.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val view = androidx.compose.ui.platform.LocalView.current
    fun hideKb() {
        focus.clearFocus(); kb?.hide()
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }
    LaunchedEffect(showCalendar) { if (showCalendar) hideKb() }
    fun pick(v: Long) { hideKb(); onChange(v) }
    fun setHm(h: Int, m: Int) = pick(z.withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant().toEpochMilli())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val dm = Modifier.weight(1.25f).height(46.dp)
            val tm = Modifier.weight(0.75f).height(46.dp)
            val pad = PaddingValues(horizontal = 8.dp)
            val dayTxt: @Composable () -> Unit = { Text(dayLabel(ms), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false) }
            val timeTxt: @Composable () -> Unit = { Text("%02d:%02d".format(z.hour, z.minute), style = MaterialTheme.typography.titleMedium, maxLines = 1) }
            if (showCalendar) FilledTonalButton({ hideKb() }, dm, contentPadding = pad) { dayTxt() }
            else OutlinedButton({ hideKb(); showCalendar = true }, dm, contentPadding = pad) { dayTxt() }
            if (!showCalendar) FilledTonalButton({ }, tm, contentPadding = pad) { timeTxt() }
            else OutlinedButton({ showCalendar = false }, tm, contentPadding = pad) { timeTxt() }
        }
        if (showCalendar) {
            CalendarPick(ms) { pick(it); showCalendar = false }
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxWidth().bringIntoViewRequester(bring),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Wheel(z.hour, 0, 23, color, 120, { wheelTouched = true }) { setHm(it, z.minute) }
                    Text(":", style = MaterialTheme.typography.displaySmall)
                    Wheel(z.minute, 0, 59, color, 120, { wheelTouched = true }) { setHm(z.hour, it) }
                }
            }
            if (!imeUp) Text("אפשר ללחוץ על המספר ולהקליד אותו", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { pick(z.with(today).toInstant().toEpochMilli()); showCalendar = false }, label = { Text("היום") })
            AssistChip(onClick = { pick(z.with(today.plusDays(1)).toInstant().toEpochMilli()); showCalendar = false }, label = { Text("מחר") })
            if (quick) {
                val nowMin = System.currentTimeMillis() / 60_000 * 60_000
                listOf("בעוד 5 דק׳" to 5, "בעוד 15 דק׳" to 15, "בעוד שעה" to 60).forEach { (l, m) ->
                    AssistChip(onClick = { pick(nowMin + m * 60_000L); showCalendar = false }, label = { Text(l) })
                }
                AssistChip(onClick = { pick(atTime(today, 20, 0)); showCalendar = false }, label = { Text("הערב 20:00") })
                AssistChip(onClick = { pick(atTime(today.plusDays(1), 8, 0)); showCalendar = false }, label = { Text("מחר 08:00") })
            }
        }
    }
}
