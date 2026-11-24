package com.ilan.reminders

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** One line of a pasted schedule: "09:00-10:00 title" / "12:30 title". Dates in the text are ignored. */
data class SchedItem(val start: LocalTime, val end: LocalTime, val title: String, val endGiven: Boolean)

object ScheduleParse {
    private val bidi = Regex("[‎‏‪-‮⁦-⁩﻿]")
    // optional day name + date at the start of the line (ignored), then H:MM [- H:MM] then the title
    private val line = Regex("""^\s*(?:(?:יום\s*)?[א-ת'"]{1,6}[,']?\s+)?(?:\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?[\s,]*)?(\d{1,2})[:.](\d{2})\s*(?:[-–—־]\s*(\d{1,2})[:.](\d{2}))?\s*[-–—:,]?\s*(.*)$""")

    /** [defDurMin]: length for lines without an end time, but never running past the start of the next meeting. */
    fun parse(text: String, defDurMin: Int): Pair<List<SchedItem>, Int> {
        class Raw(val s: LocalTime, val e: LocalTime?, val t: String)
        val raws = ArrayList<Raw>(); var skipped = 0
        for (l0 in text.lines()) {
            val l = bidi.replace(l0, "").trim(); if (l.isEmpty()) continue
            val m = line.find(l)
            val h = m?.groupValues?.get(1)?.toIntOrNull(); val mi = m?.groupValues?.get(2)?.toIntOrNull()
            if (m == null || h == null || mi == null || h > 23 || mi > 59 || m.groupValues[5].isBlank()) { skipped++; continue }
            val h2 = m.groupValues[3].toIntOrNull(); val m2 = m.groupValues[4].toIntOrNull()
            val e = if (h2 != null && m2 != null && h2 <= 23 && m2 <= 59) LocalTime.of(h2, m2) else null
            raws.add(Raw(LocalTime.of(h, mi), e, m.groupValues[5].trim()))
        }
        val out = raws.map { r ->
            val given = r.e != null && r.e.isAfter(r.s)
            val end = if (given) r.e!! else {
                val next = raws.map { it.s }.filter { it.isAfter(r.s) }.minOrNull()
                val byDur = r.s.plusMinutes(defDurMin.toLong()).let { if (it.isBefore(r.s)) LocalTime.of(23, 59) else it }
                if (next != null && next.isBefore(byDur)) next else byDur
            }
            SchedItem(r.s, end, r.t, given)
        }
        return out to skipped
    }
}

@Composable
fun ScheduleImportDialog(cals: List<Cal>, defaultCal: Long, startDate: LocalDate, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val writable = remember(cals) { cals.filter { it.writable } }
    var date by remember { mutableStateOf(startDate) }
    var calId by remember { mutableStateOf((writable.firstOrNull { it.id == defaultCal } ?: writable.firstOrNull { it.account.contains("@") } ?: writable.firstOrNull())?.id ?: -1L) }
    var text by remember { mutableStateOf("") }
    var calMenu by remember { mutableStateOf(false) }
    val off = remember { mutableStateListOf<Int>() }
    var busy by remember { mutableStateOf(false) }
    val defDur = remember { DS.defDur(ctx) }
    val (items, skipped) = remember(text) { ScheduleParse.parse(text, defDur) }
    LaunchedEffect(text) { off.clear() }
    val chosen = items.filterIndexed { i, _ -> i !in off }
    fun overlaps(i: Int): Boolean { val a = items[i]; return items.indices.any { j -> j != i && items[j].start < a.end && a.start < items[j].end } }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("הדבקת לוח זמנים") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show() }) {
                        Text("📅 %02d/%02d/%d".format(date.dayOfMonth, date.monthValue, date.year))
                    }
                    Box {
                        OutlinedButton({ calMenu = true }) { Text("יומן: " + (writable.firstOrNull { it.id == calId }?.name ?: "—"), maxLines = 1) }
                        DropdownMenu(calMenu, { calMenu = false }) {
                            writable.forEach { c -> DropdownMenuItem({ Text(c.name + if (c.account.isNotBlank()) " · " + c.account else "") }, { calId = c.id; calMenu = false }) }
                        }
                    }
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                    placeholder = { Text("הדבק כאן, שורה לכל פגישה:\n09:00-10:00 ישיבת צוות\n12:30 שיחה עם דנה") },
                    textStyle = TextStyle(fontSize = 14.sp, textDirection = TextDirection.Content))
                if (items.isNotEmpty()) {
                    Text("תצוגה מקדימה (הסר סימון כדי לדלג על אירוע):", fontSize = 12.sp)
                    items.forEachIndexed { i, it ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i !in off, { on -> if (on) off.remove(i) else off.add(i) })
                            Column(Modifier.weight(1f)) {
                                Text(it.title, fontSize = 14.sp)
                                Text("%02d:%02d–%02d:%02d".format(it.start.hour, it.start.minute, it.end.hour, it.end.minute) +
                                    (if (!it.endGiven) " (משוער)" else "") + (if (overlaps(i)) " · ⚠ חופף לאירוע אחר" else ""), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (skipped > 0) Text("$skipped שורות בלי שעה לא זוהו ודולגו.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({
                if (calId < 0) { Toast.makeText(ctx, "אין יומן לכתיבה", Toast.LENGTH_SHORT).show(); return@TextButton }
                busy = true
                val rems = DS.defRem(ctx).let { if (it != -2) listOf(it) else emptyList() }
                val av = DS.defAvail(ctx); val ac = DS.defAccess(ctx)
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        chosen.count { s -> CalRepo.save(ctx, null, 0L, EdState(s.title, calId, date, s.start, date, s.end, false, "", "", rems, "", av, ac), 0) >= 0 }
                    }
                    Toast.makeText(ctx, if (ok == chosen.size) "נוצרו $ok אירועים" else "נוצרו $ok מתוך ${chosen.size} אירועים: " + CalRepo.lastError.take(100), Toast.LENGTH_LONG).show()
                    busy = false; onDone()
                }
            }, enabled = chosen.isNotEmpty() && !busy) { Text("צור ${chosen.size} אירועים") }
        },
        dismissButton = { TextButton({ onClose() }, enabled = !busy) { Text("ביטול") } })
}
