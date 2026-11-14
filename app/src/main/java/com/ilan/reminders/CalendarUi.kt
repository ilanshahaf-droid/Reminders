package com.ilan.reminders

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Month / week view that puts reminders and the phone's calendar events side by side. */
object CalendarView { var open by mutableStateOf(false) }

private data class Entry(val date: LocalDate, val time: LocalTime?, val title: String, val sub: String, val task: Task?)

private val HE_DAYS = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
private val HE_MONTHS = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")
private fun sundayIndex(d: LocalDate) = d.dayOfWeek.value % 7   // Sunday = 0
private fun weekStart(d: LocalDate): LocalDate = d.minusDays(sundayIndex(d).toLong())

private fun occursOn(t: Task, day: LocalDate, zone: ZoneId): Boolean {
    if (t.done || t.deletedAt != 0L || t.alertMode == "NONE" && t.triggerAt == 0L) return false
    val first = if (t.triggerAt > 0) java.time.Instant.ofEpochMilli(t.triggerAt).atZone(zone).toLocalDate() else null
    return when (t.repeat) {
        "NONE" -> first == day
        "DAILY" -> first == null || !day.isBefore(first)
        "MONTHLY" -> first != null && !day.isBefore(first) && day.dayOfMonth == first.dayOfMonth
        "SCHEDULE" -> {
            val idx = sundayIndex(day)
            val slotDays = t.slots.split(";").mapNotNull { it.substringBefore("=").toIntOrNull() }
            (t.days shr idx and 1) == 1 || idx in slotDays
        }
        else -> false
    }
}

private fun timeOf(t: Task, zone: ZoneId): LocalTime? =
    if (t.triggerAt > 0) java.time.Instant.ofEpochMilli(t.triggerAt).atZone(zone).toLocalTime().withSecond(0).withNano(0) else null

@Composable
fun CalendarScreen(onTask: (Task) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val zone = remember { ZoneId.systemDefault() }
    val tasks by AppDb.get(ctx).dao().all().collectAsState(initial = emptyList())
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var weekMode by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    BackHandler { CalendarView.open = false }

    val rangeStart = if (weekMode) weekStart(selected) else month.atDay(1).let { weekStart(it) }
    val rangeEnd = if (weekMode) rangeStart.plusDays(6) else rangeStart.plusDays(41)
    val events = remember(granted, rangeStart, rangeEnd) {
        if (!granted) emptyList() else runCatching {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, rangeStart.atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(b, rangeEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val out = ArrayList<Entry>()
            ctx.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(2) == 1
                    val begin = java.time.Instant.ofEpochMilli(c.getLong(1))
                    val date = if (allDay) begin.atZone(ZoneOffset.UTC).toLocalDate() else begin.atZone(zone).toLocalDate()
                    val time = if (allDay) null else begin.atZone(zone).toLocalTime()
                    out.add(Entry(date, time, c.getString(0).orEmpty().ifBlank { "(ללא כותרת)" }, c.getString(3).orEmpty(), null))
                }
            }
            out
        }.getOrDefault(emptyList())
    }
    fun entriesOn(d: LocalDate): List<Entry> {
        val ts = tasks.filter { occursOn(it, d, zone) }.map { Entry(d, timeOf(it, zone), it.title, "", it) }
        return (ts + events.filter { it.date == d }).sortedWith(compareBy({ it.time == null }, { it.time }))
    }
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().background(appBackground()).statusBarsPadding().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (weekMode) selected = selected.minusWeeks(1).also { month = YearMonth.from(it) } else { month = month.minusMonths(1); selected = month.atDay(1) } }) { Text("▶", fontSize = 20.sp) }
            Text(
                if (weekMode) "שבוע של ${weekStart(selected).dayOfMonth}/${weekStart(selected).monthValue}" else "${HE_MONTHS[month.monthValue - 1]} ${month.year}",
                Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge
            )
            TextButton({ if (weekMode) selected = selected.plusWeeks(1).also { month = YearMonth.from(it) } else { month = month.plusMonths(1); selected = month.atDay(1) } }) { Text("◀", fontSize = 20.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!weekMode, { weekMode = false }, label = { Text("חודש") })
            FilterChip(weekMode, { weekMode = true }, label = { Text("שבוע") })
            TextButton({ selected = today; month = YearMonth.now() }) { Text("היום") }
            Spacer(Modifier.weight(1f))
            TextButton({ CalendarView.open = false }) { Text("סגור") }
        }
        if (!granted) Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("כדי לראות גם אירועים מיומן הטלפון צריך הרשאה.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ ask.launch(Manifest.permission.READ_CALENDAR) }) { Text("אפשר") }
            }
        }
        if (!weekMode) {
            Row(Modifier.fillMaxWidth()) {
                HE_DAYS.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
            }
            val gridStart = weekStart(month.atDay(1))
            val weeks = if (month.atEndOfMonth().isAfter(gridStart.plusDays(34))) 6 else 5
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0..6) {
                        val d = gridStart.plusDays((w * 7 + i).toLong())
                        val inMonth = d.month == month.month
                        val es = if (inMonth || true) entriesOn(d) else emptyList()
                        val sel = d == selected
                        Box(
                            Modifier.weight(1f).height(52.dp).padding(2.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) cs.primary else if (d == today) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { selected = d },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    d.dayOfMonth.toString(), fontSize = 15.sp,
                                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (sel) cs.onPrimary else if (inMonth) cs.onSurface else cs.onSurface.copy(alpha = 0.35f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(8.dp)) {
                                    if (es.any { it.task != null }) Box(Modifier.size(6.dp).clip(CircleShape).background(if (sel) cs.onPrimary else cs.primary))
                                    if (es.any { it.task == null }) Box(Modifier.size(6.dp).clip(CircleShape).background(if (sel) cs.onPrimary else Color(0xFF00897B)))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary)); Spacer(Modifier.width(4.dp)); Text("תזכורות", style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00897B))); Spacer(Modifier.width(4.dp)); Text("אירועי יומן", style = MaterialTheme.typography.labelSmall) }
            }
        }
        val days = if (weekMode) (0..6).map { weekStart(selected).plusDays(it.toLong()) } else listOf(selected)
        LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d ->
                val es = entriesOn(d)
                item(key = "h$d") {
                    Text(
                        "יום ${HE_DAYS[sundayIndex(d)]} · ${d.dayOfMonth}/${d.monthValue}" + if (d == today) " · היום" else "",
                        style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (es.isEmpty()) item(key = "e$d") { Text("אין פריטים", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                items(es, key = { "$d${it.title}${it.time}${it.task?.id}" }) { e ->
                    Surface(
                        shape = RoundedCornerShape(14.dp), color = cs.surface, shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().let { m -> if (e.task != null) m.clickable { CalendarView.open = false; onTask(e.task) } else m }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (e.task != null) cs.primary else Color(0xFF00897B)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                if (e.sub.isNotBlank()) Text(e.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                            Text(e.time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "כל היום", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
