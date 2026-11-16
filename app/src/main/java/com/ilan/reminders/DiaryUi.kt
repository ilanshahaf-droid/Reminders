package com.ilan.reminders

import android.app.DatePickerDialog
import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Meetings diary: week (default) / day (swipe right) / month (swipe left) views over the phone's calendar provider. */
private val BAR = Color(0xFF4A7391)
private val ORANGE = Color(0xFFFB8C00)
private val WEEKEND_BG = Color(0xFFEFEFEF)
private val GRID = Color(0xFFD9DCE3)
private val INK = Color(0xFF222222)
private val RED = Color(0xFFE53935)
private val DAY_FULL = listOf("יום ראשון", "יום שני", "יום שלישי", "יום רביעי", "יום חמישי", "יום שישי", "יום שבת")
private val MON_SHORT = listOf("ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יוני", "יולי", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳")

private fun wkStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value % 7).toLong())
private fun wkNo(d: LocalDate): Int = wkStart(d).plusDays(1).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
private fun evColor(e: Ev) = Color((if (e.color == 0) 0xFF4A7391.toInt() else e.color) or 0xFF000000.toInt())
private fun dayEvs(all: List<Ev>, d: LocalDate, z: ZoneId) = all.filter { it.on(d, z) }.sortedWith(compareBy({ !it.allDay }, { it.begin }))
private fun dayIdx(d: LocalDate) = d.dayOfWeek.value % 7

/** minutes since midnight that [e] covers on day [d] */
private fun spanOn(e: Ev, d: LocalDate, z: ZoneId): Pair<Int, Int> {
    val s = Instant.ofEpochMilli(e.begin).atZone(z); val en = Instant.ofEpochMilli(maxOf(e.end, e.begin)).atZone(z)
    val a = if (s.toLocalDate() == d) s.hour * 60 + s.minute else 0
    val b = if (en.toLocalDate() == d) en.hour * 60 + en.minute else 1440
    return a to minOf(1440, maxOf(b, a + 25))
}

class EditTarget(val ev: Ev?, val date: LocalDate, val time: LocalTime?)

@Composable
fun DiaryHost(onReminders: () -> Unit, onNotes: () -> Unit, onDocs: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(CalRepo.hasRead(ctx) && CalRepo.hasWrite(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = CalRepo.hasRead(ctx) && CalRepo.hasWrite(ctx)
    }
    BackHandler { onReminders() }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        if (!granted) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📅", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("יומן פגישות", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = INK)
                Spacer(Modifier.height(8.dp))
                Text("כדי להציג וליצור פגישות צריך לאשר גישה ליומן של הטלפון. יומן גוגל שמחובר לטלפון יסתנכרן אוטומטית.", textAlign = TextAlign.Center, color = INK)
                Spacer(Modifier.height(18.dp))
                Button({ launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { Text("אישור גישה ליומן") }
                TextButton(onReminders) { Text("חזרה") }
            }
        } else DiaryMain(onReminders, onNotes, onDocs)
    }
}

@Composable
private fun Modifier.vSwipe(onShift: (Int) -> Unit): Modifier {
    val cur by rememberUpdatedState(onShift)
    return this.pointerInput(Unit) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total < -110f) cur(1) else if (total > 110f) cur(-1) },
            onDragCancel = { total = 0f }
        ) { _, dy -> total += dy }
    }
}

@Composable
private fun DiaryMain(onReminders: () -> Unit, onNotes: () -> Unit, onDocs: () -> Unit) {
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var dayNum by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(dayNum)
    val today = LocalDate.now()
    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var menuNav by remember { mutableStateOf(false) }
    var menuMore by remember { mutableStateOf(false) }
    val cals = remember(tick) { CalRepo.calendars(ctx) }
    var visPref by remember { mutableStateOf(prefs.getStringSet("diaryVis", null)?.mapNotNull { it.toLongOrNull() }?.toSet()) }
    var defCal by remember { mutableStateOf(prefs.getLong("diaryDef", -1L)) }
    val visible: Set<Long> = remember(cals, visPref) { val all = cals.map { it.id }.toSet(); if (visPref == null) all else visPref!!.intersect(all) }
    var events by remember { mutableStateOf<List<Ev>>(emptyList()) }
    val gridStart = wkStart(date.withDayOfMonth(1))
    LaunchedEffect(gridStart, tick, visible) {
        val from = gridStart.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = gridStart.plusDays(44).atStartOfDay(zone).toInstant().toEpochMilli()
        events = withContext(Dispatchers.IO) { CalRepo.events(ctx, from, to, visible) }
    }
    DisposableEffect(Unit) {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { tick++ } }
        ctx.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o)
        onDispose { ctx.contentResolver.unregisterContentObserver(o) }
    }
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
    BackHandler(enabled = pager.currentPage != 1 && editing == null) { scope.launch { pager.animateScrollToPage(1) } }

    fun shift(dir: Int) {
        val n = when (pager.currentPage) { 2 -> date.plusDays(dir.toLong()); 1 -> date.plusWeeks(dir.toLong()); else -> date.plusMonths(dir.toLong()) }
        dayNum = n.toEpochDay()
    }
    val onDay: (LocalDate) -> Unit = { d -> dayNum = d.toEpochDay(); scope.launch { pager.animateScrollToPage(2) } }
    val onEvent: (Ev) -> Unit = { e -> editing = EditTarget(e, e.days(zone).first, null) }
    val onNew: (LocalDate, LocalTime?) -> Unit = { d, t -> editing = EditTarget(null, d, t) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            // ---- top bar ----
            Box(Modifier.fillMaxWidth().background(BAR).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton({ menuNav = true }) { Icon(Icons.Default.Menu, "תפריט", tint = Color.White) }
                        DropdownMenu(menuNav, { menuNav = false }) {
                            DropdownMenuItem({ Text("⏰ תזכורות") }, { menuNav = false; onReminders() })
                            DropdownMenuItem({ Text("📝 פתקים") }, { menuNav = false; onNotes() })
                            DropdownMenuItem({ Text("📄 מסמכים") }, { menuNav = false; onDocs() })
                            HorizontalDivider()
                            DropdownMenuItem({ Text("🗓 הגדרות יומנים") }, { menuNav = false; showSettings = true })
                        }
                    }
                    val page = pager.currentPage
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        val title = if (page == 2) "${date.dayOfMonth} ב${HE_MONTHS[date.monthValue - 1]} ${date.year}" else "${HE_MONTHS[date.monthValue - 1]} ${date.year}"
                        val sub = when (page) { 2 -> "שב׳ ${wkNo(date)}, ${DAY_FULL[dayIdx(date)]}"; 1 -> "שב׳ ${wkNo(date)}"; else -> "" }
                        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1)
                    }
                    Text("▶", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { shift(-1) }.padding(horizontal = 10.dp, vertical = 12.dp))
                    Text("◀", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { shift(1) }.padding(horizontal = 10.dp, vertical = 12.dp))
                    Text("היום", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { dayNum = today.toEpochDay() }.padding(horizontal = 10.dp, vertical = 6.dp))
                    IconButton({ onNew(date, null) }) { Icon(Icons.Default.Add, "פגישה חדשה", tint = Color.White) }
                    Box {
                        IconButton({ menuMore = true }) { Icon(Icons.Default.MoreVert, "עוד", tint = Color.White) }
                        DropdownMenu(menuMore, { menuMore = false }) {
                            DropdownMenuItem({ Text("מעבר לתאריך…") }, {
                                menuMore = false
                                DatePickerDialog(ctx, { _, y, m, d -> dayNum = LocalDate.of(y, m + 1, d).toEpochDay() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                            })
                            DropdownMenuItem({ Text("הגדרות יומנים") }, { menuMore = false; showSettings = true })
                            DropdownMenuItem({ Text("רענון") }, { menuMore = false; tick++ })
                        }
                    }
                }
            }
            // ---- pager: day | week | month ----
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        when (page) {
                            2 -> DayView(date, events, zone, today, onNew, onEvent, onDay, ::shift)
                            1 -> WeekView(date, events, zone, today, onDay, onEvent, onNew, ::shift)
                            else -> MonthView(date, events, zone, today, onDay, onEvent, ::shift)
                        }
                    }
                }
            }
        }
        val t = editing
        if (t != null) EventEditor(
            target = t, cals = cals, defaultCal = defCal, zone = zone,
            onClose = { editing = null },
            onDefault = { defCal = it; prefs.edit().putLong("diaryDef", it).apply() },
            onChanged = { editing = null; tick++ }
        )
        if (showSettings) SettingsDialog(cals, visible, defCal, onDismiss = { showSettings = false }) { vis, def ->
            visPref = vis; defCal = def
            prefs.edit().putStringSet("diaryVis", vis.map { it.toString() }.toSet()).putLong("diaryDef", def).apply()
            showSettings = false
        }
    }
}

@Composable
private fun SettingsDialog(cals: List<Cal>, visible: Set<Long>, defCal: Long, onDismiss: () -> Unit, onSave: (Set<Long>, Long) -> Unit) {
    var vis by remember { mutableStateOf(visible) }
    var def by remember { mutableStateOf(defCal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("יומנים") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("סמן יומנים להצגה. «קבע» בוחר את יומן ברירת המחדל לפגישות חדשות (אפשר גם לבחור יומן בעורך — הבחירה נשמרת).", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                cals.groupBy { it.account }.forEach { (acc, list) ->
                    Text(acc, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    list.forEach { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.id in vis, { on -> vis = if (on) vis + c.id else vis - c.id })
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(c.color or 0xFF000000.toInt())))
                            Text(c.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.writable) { Text(if (def == c.id) "ברירת מחדל" else "קבע", fontSize = 12.sp, color = if (def == c.id) ORANGE else BAR, modifier = Modifier.clickable { def = c.id }.padding(6.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSave(vis, def) }) { Text("שמירה") } },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } }
    )
}

// ============================ WEEK ============================

@Composable
private fun EvLine(e: Ev, z: ZoneId, size: TextUnit, showTime: Boolean = true, onClick: () -> Unit) {
    val col = evColor(e)
    if (e.allDay) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp).clip(RoundedCornerShape(3.dp)).background(col.copy(alpha = 0.30f)).clickable(onClick = onClick).padding(horizontal = 4.dp)) {
            Text(e.title, fontSize = size, maxLines = 1, overflow = TextOverflow.Ellipsis, color = INK)
        }
    } else {
        Text((if (showTime) "${hm(e.startTime(z))} " else "") + e.title, fontSize = size, maxLines = 1, overflow = TextOverflow.Clip, color = col,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp))
    }
}

@Composable
private fun WeekDayCell(d: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, modifier: Modifier, onDay: (LocalDate) -> Unit, onEvent: (Ev) -> Unit, onNew: (LocalDate, LocalTime?) -> Unit) {
    val idx = dayIdx(d); val isToday = d == today
    val evs = remember(all, d) { dayEvs(all, d, z) }
    BoxWithConstraints(modifier.background(if (idx >= 5) WEEKEND_BG else Color.White)) {
        val cap = ((maxHeight - 30.dp) / 18.dp).toInt().coerceAtLeast(0)
        val shown = if (evs.size <= cap) evs else evs.take((cap - 1).coerceAtLeast(0))
        val hidden = evs.size - shown.size
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(if (isToday) ORANGE else Color.Transparent).clickable { onDay(d) }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fg = if (isToday) Color.White else INK
                Text("${d.dayOfMonth}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (isToday) Color.White else if (idx == 6) RED else INK)
                Spacer(Modifier.width(6.dp))
                Text(DAY_FULL[idx] + if (d.dayOfMonth == 1 || idx == 0) " / ${MON_SHORT[d.monthValue - 1]}" else "", fontSize = 14.sp, color = fg, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (hidden > 0) Text("+$hidden", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (isToday) Color.White else ORANGE)
            }
            Column(Modifier.weight(1f).fillMaxWidth().pointerInput(d) { detectTapGestures(onTap = { onDay(d) }, onLongPress = { onNew(d, null) }) }) {
                shown.forEach { e -> EvLine(e, z, 13.sp) { onEvent(e) } }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(GRID))
    }
}

@Composable
private fun MiniWeek(start: LocalDate, all: List<Ev>, z: ZoneId, selected: LocalDate?, today: LocalDate, modifier: Modifier, onDay: (LocalDate) -> Unit) {
    Column(modifier.background(Color(0xFFF3F6F9))) {
        Row(Modifier.fillMaxWidth().height(18.dp).background(BAR.copy(alpha = 0.9f))) {
            for (i in 0..6) {
                val d = start.plusDays(i.toLong())
                Text("${d.dayOfMonth}", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, fontWeight = if (d == selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).background(if (d == today) ORANGE else Color.Transparent))
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            for (i in 0..6) {
                val d = start.plusDays(i.toLong())
                val evs = remember(all, d) { dayEvs(all, d, z).filter { !it.allDay } }
                val allDay = remember(all, d) { dayEvs(all, d, z).any { it.allDay } }
                Canvas(Modifier.weight(1f).fillMaxHeight().background(if (d == selected) BAR.copy(alpha = 0.10f) else Color.Transparent).clickable { onDay(d) }) {
                    drawRect(GRID, Offset(0f, 0f), Size(1.5f, size.height))
                    if (allDay) drawRect(Color(0xFFE57373).copy(alpha = 0.35f), Offset(0f, 0f), Size(size.width, size.height))
                    evs.forEach { e ->
                        val (a, b) = spanOn(e, d, z)
                        val y1 = ((a - 360) / 1080f).coerceIn(0f, 1f) * size.height
                        val y2 = ((b - 360) / 1080f).coerceIn(0f, 1f) * size.height
                        drawRoundRect(evColor(e).copy(alpha = 0.9f), Offset(3f, y1), Size(size.width - 6f, maxOf(6f, y2 - y1)), CornerRadius(4f, 4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekView(date: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, onDay: (LocalDate) -> Unit, onEvent: (Ev) -> Unit, onNew: (LocalDate, LocalTime?) -> Unit, onShift: (Int) -> Unit) {
    val start = wkStart(date)
    Row(Modifier.fillMaxSize().vSwipe(onShift).background(Color.White)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            for (i in 0..3) WeekDayCell(start.plusDays(i.toLong()), all, z, today, Modifier.weight(1f).fillMaxWidth(), onDay, onEvent, onNew)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(GRID))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            for (i in 4..6) WeekDayCell(start.plusDays(i.toLong()), all, z, today, Modifier.weight(1f).fillMaxWidth(), onDay, onEvent, onNew)
            MiniWeek(start, all, z, date, today, Modifier.weight(1f).fillMaxWidth(), onDay)
        }
    }
}

// ============================ MONTH ============================

@Composable
private fun MonthView(date: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, onDay: (LocalDate) -> Unit, onEvent: (Ev) -> Unit, onShift: (Int) -> Unit) {
    val first = date.withDayOfMonth(1)
    val gridStart = wkStart(first)
    Column(Modifier.fillMaxSize().vSwipe(onShift).background(Color.White)) {
        Row(Modifier.fillMaxWidth().background(BAR).padding(vertical = 6.dp)) {
            for (i in 0..6) Text("יום ${HE_DAYS[i]}", Modifier.weight(1f), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
        for (r in 0..5) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0..6) {
                    val d = gridStart.plusDays((r * 7 + c).toLong())
                    MonthCell(d, d.monthValue == date.monthValue, all, z, today, Modifier.weight(1f).fillMaxHeight(), onDay, onEvent)
                }
            }
        }
    }
}

@Composable
private fun MonthCell(d: LocalDate, inMonth: Boolean, all: List<Ev>, z: ZoneId, today: LocalDate, modifier: Modifier, onDay: (LocalDate) -> Unit, onEvent: (Ev) -> Unit) {
    val idx = dayIdx(d); val isToday = d == today
    val evs = remember(all, d) { dayEvs(all, d, z) }
    BoxWithConstraints(modifier.background(if (inMonth) Color.White else Color(0xFFE4E4E4)).border(0.5.dp, GRID).clickable { onDay(d) }) {
        val cap = ((maxHeight - 24.dp) / 15.dp).toInt().coerceAtLeast(0)
        val shown = if (evs.size <= cap) evs else evs.take((cap - 1).coerceAtLeast(0))
        val hidden = evs.size - shown.size
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${d.dayOfMonth}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (isToday) Color.White else if (idx == 6) RED else INK,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (isToday) ORANGE else Color.Transparent).padding(horizontal = 3.dp))
                if (hidden > 0) Text(" +$hidden", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ORANGE)
                if (idx == 0) {
                    Spacer(Modifier.width(4.dp))
                    Text("${wkNo(d)}", fontSize = 11.sp, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(BAR.copy(alpha = 0.65f)).padding(horizontal = 3.dp))
                }
            }
            shown.forEach { e -> EvLine(e, z, 12.sp, false) { onEvent(e) } }
        }
    }
}

// ============================ DAY ============================

private class DSlot(val e: Ev, val a: Int, val b: Int) { var col = 0; var n = 1 }

private fun placeSlots(slots: List<DSlot>) {
    val sorted = slots.sortedBy { it.a }
    var cluster = ArrayList<DSlot>(); var clusterEnd = -1
    fun flush() { val n = (cluster.maxOfOrNull { it.col } ?: -1) + 1; cluster.forEach { it.n = maxOf(n, 1) }; cluster = ArrayList() }
    for (s in sorted) {
        if (cluster.isNotEmpty() && s.a >= clusterEnd) { flush(); clusterEnd = -1 }
        var c = 0
        while (cluster.any { it.col == c && it.b > s.a }) c++
        s.col = c; cluster.add(s); clusterEnd = maxOf(clusterEnd, s.b)
    }
    flush()
}

@Composable
private fun DayView(date: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, onNew: (LocalDate, LocalTime?) -> Unit, onEvent: (Ev) -> Unit, onDay: (LocalDate) -> Unit, onShift: (Int) -> Unit) {
    val evs = remember(all, date) { dayEvs(all, date, z) }
    val hourH = 56.dp
    val scroll = rememberScrollState()
    val dens = LocalDensity.current
    LaunchedEffect(date) {
        val h = if (date == today) maxOf(0, LocalTime.now().hour - 1) else 6
        scroll.scrollTo((hourH.value * dens.density * h).toInt())
    }
    val curShift by rememberUpdatedState(onShift)
    val edge = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                acc = if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) acc + available.y else 0f
                return Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val a = acc; acc = 0f
                if (a < -90f) curShift(1) else if (a > 90f) curShift(-1)
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    Row(Modifier.fillMaxSize().nestedScroll(edge)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            evs.filter { it.allDay }.forEach { e -> EvLine(e, z, 14.sp) { onEvent(e) } }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val labelW = 40.dp
                val areaW = maxWidth - labelW - 6.dp
                Column(Modifier.verticalScroll(scroll)) {
                    Box(
                        Modifier.fillMaxWidth().height(hourH * 24).pointerInput(date) {
                            detectTapGestures { off ->
                                val h = (off.y / (hourH.toPx())).toInt().coerceIn(0, 23)
                                onNew(date, LocalTime.of(h, 0))
                            }
                        }
                    ) {
                        for (h in 0..23) {
                            Box(Modifier.absoluteOffset(y = hourH * h).fillMaxWidth().height(1.dp).background(GRID))
                            Text("%02d".format(h), fontSize = 13.sp, color = Color(0xFF777777), modifier = Modifier.align(Alignment.TopEnd).absoluteOffset(y = hourH * h - 9.dp).padding(end = 8.dp))
                        }
                        val slots = remember(evs) { evs.filter { !it.allDay }.map { val (a, b) = spanOn(it, date, z); DSlot(it, a, b) }.also { placeSlots(it) } }
                        slots.forEach { s ->
                            val w = areaW / s.n
                            val h = maxOf(22.dp, hourH * ((s.b - s.a) / 60f))
                            val col = evColor(s.e)
                            Box(
                                Modifier.absoluteOffset(x = 4.dp + w * s.col, y = hourH * (s.a / 60f)).size(w - 2.dp, h - 1.dp)
                                    .clip(RoundedCornerShape(5.dp)).background(col.copy(alpha = 0.92f)).border(1.dp, Color.Black.copy(alpha = 0.25f), RoundedCornerShape(5.dp))
                                    .clickable { onEvent(s.e) }.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text("${hm(s.e.startTime(z))}–${hm(s.e.endTime(z))} ${s.e.title}" + if (s.e.repeat) " ⇄" else "", fontSize = 13.sp, color = Color.White, maxLines = if (h > 40.dp) 3 else 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (date == today) {
                            val now = LocalTime.now(); val y = hourH * ((now.hour * 60 + now.minute) / 60f)
                            Box(Modifier.absoluteOffset(y = y).fillMaxWidth().height(2.dp).background(RED))
                        }
                    }
                }
            }
        }
        Column(Modifier.width(112.dp).fillMaxHeight().vSwipe(onShift).background(Color(0xFFEFF3F7)).padding(8.dp)) {
            Text(HE_MONTHS[date.monthValue - 1], fontSize = 15.sp, color = BAR)
            Text("${date.dayOfMonth}", fontSize = 56.sp, color = BAR, fontWeight = FontWeight.Medium)
            Text(DAY_FULL[dayIdx(date)], fontSize = 15.sp, color = BAR)
            Spacer(Modifier.weight(1f))
            MiniWeek(wkStart(date), all, z, date, today, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(4.dp)), onDay)
        }
    }
}

// ============================ EDITOR ============================

private val REMINDERS = listOf(-1 to "ללא", 0 to "בזמן האירוע", 5 to "5 דקות לפני", 10 to "10 דקות לפני", 30 to "30 דקות לפני", 60 to "שעה לפני", 1440 to "יום לפני")
private val REPEATS = listOf("" to "ללא חזרה", "DAILY" to "כל יום", "WEEKLY" to "כל שבוע", "MONTHLY" to "כל חודש", "YEARLY" to "כל שנה")

@Composable
private fun EventEditor(target: EditTarget, cals: List<Cal>, defaultCal: Long, zone: ZoneId, onClose: () -> Unit, onDefault: (Long) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val writable = cals.filter { it.writable }
    val ev = target.ev
    var full by remember { mutableStateOf<EvFull?>(null) }
    var loaded by remember { mutableStateOf(ev == null) }
    val firstCal = writable.firstOrNull { it.id == defaultCal } ?: writable.firstOrNull { it.account.contains("@") } ?: writable.firstOrNull()
    var title by remember { mutableStateOf("") }
    var calId by remember { mutableStateOf(firstCal?.id ?: -1L) }
    val now = LocalTime.now()
    val st0 = target.time ?: LocalTime.of(minOf(now.hour + 1, 23), 0)
    var sd by remember { mutableStateOf(target.date) }
    var stT by remember { mutableStateOf(st0) }
    var ed by remember { mutableStateOf(target.date) }
    var enT by remember { mutableStateOf(if (st0.hour >= 23) LocalTime.of(23, 59) else st0.plusHours(1)) }
    var allDay by remember { mutableStateOf(false) }
    var loc by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var rem by remember { mutableStateOf(10) }
    var rep by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf(false) }
    var pickCal by remember { mutableStateOf(false) }

    LaunchedEffect(ev?.id) {
        if (ev != null) {
            val f = withContext(Dispatchers.IO) { CalRepo.load(ctx, ev.id) }
            full = f
            title = f?.title ?: ev.title
            calId = f?.calId ?: ev.calId
            allDay = ev.allDay
            val (a, b) = ev.days(zone)
            sd = a; ed = b
            if (!ev.allDay) { stT = ev.startTime(zone); enT = ev.endTime(zone) }
            loc = f?.location ?: ev.location; desc = f?.desc.orEmpty()
            rem = f?.reminder ?: -1
            rep = if (f != null && f.rrule.isNotEmpty()) (REPEATS.map { it.first }.firstOrNull { it.isNotEmpty() && f.rrule == "FREQ=$it" } ?: "CUSTOM") else ""
            loaded = true
        }
    }
    BackHandler { onClose() }
    val canWrite = cals.firstOrNull { it.id == calId }?.writable != false
    fun save() {
        if (calId < 0) { android.widget.Toast.makeText(ctx, "אין יומן לכתיבה", android.widget.Toast.LENGTH_SHORT).show(); return }
        val s = EdState(title.trim(), calId, sd, stT, if (ed.isBefore(sd)) sd else ed, enT, allDay, loc, desc, rem, rep)
        scope.launch {
            val id = withContext(Dispatchers.IO) { CalRepo.save(ctx, full, ev?.begin ?: 0L, s) }
            if (id < 0) android.widget.Toast.makeText(ctx, "השמירה נכשלה", android.widget.Toast.LENGTH_SHORT).show() else onChanged()
        }
    }
    fun pickDate(cur: LocalDate, set: (LocalDate) -> Unit) =
        DatePickerDialog(ctx, { _, y, m, d -> set(LocalDate.of(y, m + 1, d)) }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
    fun pickTime(cur: LocalTime, set: (LocalTime) -> Unit) =
        android.app.TimePickerDialog(ctx, { _, h, m -> set(LocalTime.of(h, m)) }, cur.hour, cur.minute, true).show()

    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFFE91E63)).statusBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
                Text(if (ev == null) "פגישה חדשה" else "עריכת פגישה", Modifier.weight(1f), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (ev != null) TextButton({ confirmDel = true }) { Text("🗑", fontSize = 20.sp) }
                TextButton({ save() }, enabled = loaded && canWrite) { Text("שמירה", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("כותרת") }, singleLine = true)
                val cur = cals.firstOrNull { it.id == calId }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { pickCal = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(Color((cur?.color ?: 0xFF4A7391.toInt()) or 0xFF000000.toInt())))
                    Spacer(Modifier.width(10.dp))
                    Column { Text(cur?.name ?: "בחר יומן", fontSize = 16.sp); Text(cur?.account.orEmpty(), fontSize = 12.sp, color = Color.Gray) }
                }
                if (!canWrite) Text("היומן הזה לקריאה בלבד", color = RED, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("כל היום", Modifier.weight(1f), fontSize = 16.sp); Switch(allDay, { allDay = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pickDate(sd) { sd = it; if (ed.isBefore(it)) ed = it } }, Modifier.weight(1f)) { Text("${sd.dayOfMonth}/${sd.monthValue}/${sd.year}") }
                    if (!allDay) OutlinedButton({ pickTime(stT) { stT = it } }, Modifier.weight(0.7f)) { Text(hm(stT)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pickDate(ed) { ed = it } }, Modifier.weight(1f)) { Text("${ed.dayOfMonth}/${ed.monthValue}/${ed.year}") }
                    if (!allDay) OutlinedButton({ pickTime(enT) { enT = it } }, Modifier.weight(0.7f)) { Text(hm(enT)) }
                }
                OutlinedTextField(loc, { loc = it }, Modifier.fillMaxWidth(), label = { Text("מיקום") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("תיאור") }, minLines = 2)
                Choice("תזכורת", REMINDERS.firstOrNull { it.first == rem }?.second ?: "$rem דקות לפני", REMINDERS.map { it.second }) { rem = REMINDERS[it].first }
                val repLabel = if (rep == "CUSTOM") "חזרה מותאמת (נשמרת)" else REPEATS.firstOrNull { it.first == rep }?.second ?: ""
                Choice("חזרה", repLabel, REPEATS.map { it.second }) { rep = REPEATS[it].first }
                if (full != null && full!!.rrule.isNotEmpty()) Text("שינוי בפגישה חוזרת חל על כל הסדרה.", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
    if (pickCal) AlertDialog(
        onDismissRequest = { pickCal = false }, title = { Text("בחירת יומן") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                writable.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { calId = c.id; pickCal = false; if (ev == null) onDefault(c.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(c.color or 0xFF000000.toInt())))
                        Spacer(Modifier.width(10.dp))
                        Column { Text(c.name); Text(c.account, fontSize = 12.sp, color = Color.Gray) }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton({ pickCal = false }) { Text("סגירה") } }
    )
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false }, title = { Text("למחוק את הפגישה?") },
        text = { Text(if (full?.rrule.isNullOrEmpty()) "הפגישה תימחק גם מיומן גוגל." else "כל הסדרה החוזרת תימחק גם מיומן גוגל.") },
        confirmButton = { TextButton({ confirmDel = false; scope.launch { withContext(Dispatchers.IO) { CalRepo.delete(ctx, ev!!.id) }; onChanged() } }) { Text("מחיקה", color = RED) } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("ביטול") } }
    )
}

@Composable
private fun Choice(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: $value", Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
        DropdownMenu(open, { open = false }) { options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, { open = false; onPick(i) }) } }
    }
}
