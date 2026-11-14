package com.ilan.reminders

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** "היום שלי": today's reminders and phone-calendar events, overdue items, pinned notes and recent documents. */
object TodayView { var open by mutableStateOf(false) }

private data class TRow(val time: LocalTime?, val title: String, val sub: String, val task: Task?)

@Composable
fun TodayScreen(onTask: (Task) -> Unit, onNote: (Note) -> Unit, onDoc: (java.io.File) -> Unit, onSearch: () -> Unit, onCalendar: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val zone = remember { ZoneId.systemDefault() }
    val db = remember { AppDb.get(ctx) }
    val tasks by db.dao().all().collectAsState(initial = emptyList())
    val notes by db.notes().all().collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    BackHandler { TodayView.open = false }

    val events = remember(granted) {
        if (!granted) emptyList() else runCatching {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(b, today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli())
            val out = ArrayList<TRow>()
            ctx.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(2) == 1
                    val begin = java.time.Instant.ofEpochMilli(c.getLong(1))
                    val date = if (allDay) begin.atZone(ZoneOffset.UTC).toLocalDate() else begin.atZone(zone).toLocalDate()
                    if (date != today) continue
                    out.add(TRow(if (allDay) null else begin.atZone(zone).toLocalTime(), c.getString(0).orEmpty().ifBlank { "(ללא כותרת)" }, c.getString(3).orEmpty(), null))
                }
            }
            out
        }.getOrDefault(emptyList())
    }
    val startToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val overdue = tasks.filter { !it.done && it.deletedAt == 0L && it.repeat == "NONE" && it.triggerAt in 1 until startToday }.sortedBy { it.triggerAt }
    val rows = (tasks.filter { occursOn(it, today, zone) }.map { TRow(timeOf(it, zone), it.title, "", it) } + events)
        .sortedWith(compareBy({ it.time == null }, { it.time }))
    val pinned = notes.filter { it.pinned && it.deletedAt == 0L }.take(6)
    val recent = remember { Docs.list(ctx).sortedByDescending { it.lastModified() }.take(4) }
    val hour = LocalTime.now().hour
    val greet = when { hour < 5 -> "לילה טוב"; hour < 12 -> "בוקר טוב"; hour < 18 -> "צהריים טובים"; else -> "ערב טוב" }

    @Composable fun section(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
    @Composable fun card(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) = Surface(
        shape = RoundedCornerShape(14.dp), color = cs.surface, shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).let { if (onClick != null) it.clickable { onClick() } else it }
    ) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, content = content) }

    Column(Modifier.fillMaxSize().background(appBackground()).statusBarsPadding().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$greet ☀️", style = MaterialTheme.typography.headlineSmall)
                Text("יום ${HE_DAYS[sundayIndex(today)]} · ${today.dayOfMonth}/${today.monthValue}/${today.year}", color = cs.onSurfaceVariant)
            }
            TextButton({ TodayView.open = false; onSearch() }) { Text("🔎") }
            TextButton({ TodayView.open = false; onCalendar() }) { Text("📆") }
            TextButton({ TodayView.open = false }) { Text("✕", fontSize = 20.sp) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (overdue.isNotEmpty()) {
                item { section("⚠ באיחור") }
                items(overdue.size) { i ->
                    val t = overdue[i]
                    card({ TodayView.open = false; onTask(t) }) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(cs.error)); Spacer(Modifier.width(10.dp))
                        Text(t.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(java.time.Instant.ofEpochMilli(t.triggerAt).atZone(zone).format(DateTimeFormatter.ofPattern("d/M HH:mm")), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item { section("היום (${rows.size})") }
            if (rows.isEmpty()) item { Text("אין תזכורות או אירועים להיום 🎉", color = cs.onSurfaceVariant) }
            items(rows.size) { i ->
                val e = rows[i]
                card(e.task?.let { t -> { TodayView.open = false; onTask(t) } }) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (e.task != null) cs.primary else Color(0xFF00897B))); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (e.sub.isNotBlank()) Text(e.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    Text(e.time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "כל היום", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!granted) item { Text("אירועי יומן הטלפון יופיעו כאן אחרי מתן הרשאה (בלוח השנה).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
            if (pinned.isNotEmpty()) {
                item { section("📌 פתקים מסומנים") }
                items(pinned.size) { i ->
                    val n = pinned[i]
                    card({ TodayView.open = false; onNote(n) }) {
                        Column(Modifier.weight(1f)) {
                            Text(n.title.ifBlank { n.plain.lineSequence().firstOrNull().orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            if (!n.locked && n.plain.isNotBlank()) Text(n.plain.take(90), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item { section("📄 מסמכים אחרונים") }
                items(recent.size) { i ->
                    val f = recent[i]
                    card({ TodayView.open = false; onDoc(f) }) {
                        Text(if (Docs.ext(f) == "pdf") "📕" else "📄"); Spacer(Modifier.width(10.dp))
                        Text(f.nameWithoutExtension, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
