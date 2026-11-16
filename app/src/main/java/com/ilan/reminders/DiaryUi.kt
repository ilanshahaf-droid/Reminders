package com.ilan.reminders

import android.app.DatePickerDialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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

/** reminders the user chose to show in the diary (stored on the phone) */
object DiaryTasks {
    var pending: Boolean? = null
    private fun p(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun ids(c: Context): Set<Long> = (p(c).getStringSet("diaryTasks", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    fun has(c: Context, id: Long) = id in ids(c)
    fun set(c: Context, id: Long, on: Boolean) {
        val n = if (on) ids(c) + id else ids(c) - id
        p(c).edit().putStringSet("diaryTasks", n.map { it.toString() }.toSet()).apply()
    }
}

/** the moments a reminder fires inside [from, to) */
private fun taskOcc(t: Task, from: Long, to: Long): List<Long> {
    val out = ArrayList<Long>()
    val z = ZoneId.systemDefault()
    when (t.repeat) {
        "SCHEDULE" -> {
            var a = from - 1; var n = 0
            while (n++ < 400) { val x = Reminders.nextOccurrence(t, a) ?: break; if (x >= to) break; out.add(x); a = x }
        }
        "DAILY", "MONTHLY" -> {
            val daily = t.repeat == "DAILY"; val k = t.every.coerceAtLeast(1).toLong()
            var d = Instant.ofEpochMilli(t.triggerAt).atZone(z); var n = 0
            while (d.toInstant().toEpochMilli() > from && n++ < 400) d = if (daily) d.minusDays(k) else d.minusMonths(k)
            n = 0
            while (d.toInstant().toEpochMilli() < to && n++ < 800) {
                val m = d.toInstant().toEpochMilli(); if (m >= from) out.add(m)
                d = if (daily) d.plusDays(k) else d.plusMonths(k)
            }
        }
        "LIST" -> out.addAll(t.dateList().filter { it in from until to })
        else -> if (t.triggerAt in from until to) out.add(t.triggerAt)
    }
    return out
}

class EditTarget(val ev: Ev?, val date: LocalDate, val time: LocalTime?, val copyOf: Ev? = null)

@Composable
fun DiaryHost(onReminders: () -> Unit, onNotes: () -> Unit, onDocs: () -> Unit, onTask: (Task) -> Unit = {}) {
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
        } else DiaryMain(onReminders, onNotes, onDocs, onTask)
    }
}

@Composable
private fun Modifier.vSwipe(onShift: (Int) -> Unit): Modifier {
    val cur by rememberUpdatedState(onShift)
    var total by remember { mutableStateOf(0f) }
    val st = androidx.compose.foundation.gestures.rememberDraggableState { total += it }
    return this.draggable(
        st, androidx.compose.foundation.gestures.Orientation.Vertical,
        onDragStarted = { total = 0f },
        onDragStopped = { v -> if (total < -50f || v < -900f) cur(1) else if (total > 50f || v > 900f) cur(-1); total = 0f }
    )
}

/** vertical, animated paging between periods (days / weeks / months); [key] identifies the period a date belongs to */
@Composable
private fun <K> VPager(date: LocalDate, key: (LocalDate) -> K, step: (LocalDate, Int) -> LocalDate, onSettled: (LocalDate) -> Unit, content: @Composable (LocalDate) -> Unit) {
    val center = 5000
    var anchor by remember { mutableStateOf(date) }
    val st = androidx.compose.foundation.pager.rememberPagerState(initialPage = center, pageCount = { 2 * center })
    fun pd(page: Int) = step(anchor, page - center)
    val cur by rememberUpdatedState(date)
    val settle by rememberUpdatedState(onSettled)
    LaunchedEffect(date) {
        val here = pd(st.currentPage)
        if (key(here) != key(date)) {
            when {
                key(pd(st.currentPage + 1)) == key(date) -> st.animateScrollToPage(st.currentPage + 1)
                key(pd(st.currentPage - 1)) == key(date) -> st.animateScrollToPage(st.currentPage - 1)
                else -> { anchor = date; st.scrollToPage(center) }
            }
        }
    }
    LaunchedEffect(st) {
        snapshotFlow { st.settledPage }.collect { p ->
            val d = pd(p)
            if (key(d) != key(cur)) settle(d)
        }
    }
    androidx.compose.foundation.pager.VerticalPager(st, Modifier.fillMaxSize(), key = { it }) { p ->
        content(pd(p))
    }
}

@Composable
private fun DiaryMain(onReminders: () -> Unit, onNotes: () -> Unit, onDocs: () -> Unit, onTask: (Task) -> Unit) {
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
    var showNotif by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var dayDlg by remember { mutableStateOf<LocalDate?>(null) }
    var dayEvSel by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(tick) { withContext(Dispatchers.IO) { runCatching { EventNotify.run(ctx) } } }
    var menuNav by remember { mutableStateOf(false) }
    var menuMore by remember { mutableStateOf(false) }
    val cals = remember(tick) { CalRepo.calendars(ctx) }
    var visPref by remember { mutableStateOf(prefs.getStringSet("diaryVis", null)?.mapNotNull { it.toLongOrNull() }?.toSet()) }
    var defCal by remember { mutableStateOf(prefs.getLong("diaryDef", -1L)) }
    val visible: Set<Long> = remember(cals, visPref) { val all = cals.map { it.id }.toSet(); if (visPref == null) all else visPref!!.intersect(all) }
    var events by remember { mutableStateOf<List<Ev>>(emptyList()) }
    val monthKey = date.year * 12 + date.monthValue
    val taskList by remember { AppDb.get(ctx).dao().all() }.collectAsState(emptyList())
    var remTask by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(monthKey, tick, visible, taskList) {
        val first = date.withDayOfMonth(1)
        val from = wkStart(first.minusMonths(2)).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = first.plusMonths(3).plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        events = withContext(Dispatchers.IO) {
            val ids = DiaryTasks.ids(ctx)
            val rem = taskList.filter { it.id in ids && it.deletedAt == 0L && !it.done && it.alertMode != "NONE" }.flatMap { t ->
                taskOcc(t, from, to).map { Ev(-t.id, 0, "⏰ " + t.title, it, it + 30 * 60_000L, false, 0xFFFB8C00.toInt(), "", false, false) }
            }
            (CalRepo.events(ctx, from, to, visible) + rem)
                .distinctBy { Triple(it.title.removePrefix("⏰ ").trim(), it.begin, it.allDay) }
                .sortedBy { it.begin }
        }
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
    val onDay: (LocalDate) -> Unit = { d -> dayEvSel = null; dayDlg = d }
    val onCellEv: (Ev, LocalDate) -> Unit = { e, d -> dayEvSel = e.id to e.begin; dayDlg = d }
    val onEvent: (Ev) -> Unit = { e -> if (e.id < 0) remTask = taskList.firstOrNull { it.id == -e.id } else editing = EditTarget(e, e.days(zone).first, null) }
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
                            DropdownMenuItem({ Text("🔔 התראות אירועים") }, { menuNav = false; showNotif = true })
                        }
                    }
                    val page = pager.currentPage
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        val mon = HE_MONTHS[date.monthValue - 1]
                        val title = when (page) { 2 -> "${date.dayOfMonth} ב$mon ${date.year}"; 1 -> "$mon ${date.year}"; else -> mon }
                        val sub = when (page) { 2 -> "שב׳ ${wkNo(date)}, ${DAY_FULL[dayIdx(date)]}"; 1 -> "שב׳ ${wkNo(date)}"; else -> "${date.year}" }
                        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
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
                            DropdownMenuItem({ Text("אבחון היום הנוכחי") }, { menuMore = false; showDiag = true })
                        }
                    }
                }
            }
            // ---- pager: day | week | month ----
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        when (page) {
                            2 -> VPager(date, { it }, { d, n -> d.plusDays(n.toLong()) }, { dayNum = it.toEpochDay() }) { d -> DayView(d, events, zone, today, onNew, onEvent, onDay, {}) }
                            1 -> VPager(date, { wkStart(it) }, { d, n -> d.plusWeeks(n.toLong()) }, { dayNum = it.toEpochDay() }) { d -> WeekView(d, events, zone, today, onDay, onCellEv, onNew, {}) }
                            else -> VPager(date, { it.year * 12 + it.monthValue }, { d, n -> d.plusMonths(n.toLong()) }, { dayNum = it.toEpochDay() }) { d -> MonthView(d, events, zone, today, onDay, onCellEv, {}) }
                        }
                    }
                }
            }
        }
        dayDlg?.let { d ->
            DayDialog(
                d, dayEvs(events, d, zone), cals, zone, dayEvSel,
                onClose = { dayDlg = null },
                onEdit = { e -> dayDlg = null; onEvent(e) },
                onCopy = { e -> dayDlg = null; editing = EditTarget(null, e.days(zone).first, null, copyOf = e) },
                onNew = { dayDlg = null; onNew(d, null) },
                onOpenDay = { dayDlg = null; dayNum = d.toEpochDay(); scope.launch { pager.animateScrollToPage(2) } },
                onChanged = { tick++ }
            )
        }
        remTask?.let { rt ->
            AlertDialog(
                onDismissRequest = { remTask = null }, title = { Text("⏰ " + rt.title) },
                text = { Text(if (rt.notes.isBlank()) "תזכורת מהאפליקציה" else rt.notes) },
                confirmButton = { TextButton({ remTask = null; onTask(rt) }) { Text("פתח בתזכורות") } },
                dismissButton = { TextButton({ remTask = null }) { Text("סגירה") } }
            )
        }
        val t = editing
        if (t != null) EventEditor(
            target = t, cals = cals, defaultCal = defCal, zone = zone,
            onClose = { editing = null },
            onDefault = { defCal = it; prefs.edit().putLong("diaryDef", it).apply() },
            onChanged = { editing = null; tick++ }
        )
        if (showDiag) {
            val fmt = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).let { "%02d/%02d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.hour, it.minute) } }
            val txt = buildString {
                append("תאריך: $date\nסה״כ נטענו: ${events.size} אירועים, יומנים מוצגים: ${visible.size}/${cals.size}\n\n")
                dayEvs(events, date, zone).forEach { e ->
                    append("• ${e.title}\n  ${fmt(e.begin)} → ${fmt(e.end)}${if (e.allDay) " (כל היום)" else ""}${if (e.repeat) " (חוזר)" else ""}\n  יומן ${e.calId} · מזהה ${e.id}\n")
                }
            }
            AlertDialog(onDismissRequest = { showDiag = false }, title = { Text("אבחון") },
                text = { Text(txt, Modifier.verticalScroll(rememberScrollState()), fontSize = 13.sp) },
                confirmButton = { TextButton({ showDiag = false }) { Text("סגירה") } })
        }
        if (showNotif) NotifSettings(onDismiss = { showNotif = false; tick++ })
        if (showSettings) SettingsDialog(cals, visible, defCal, onDismiss = { showSettings = false }) { vis, def ->
            visPref = vis; defCal = def
            prefs.edit().putStringSet("diaryVis", vis.map { it.toString() }.toSet()).putLong("diaryDef", def).apply()
            showSettings = false
        }
    }
}

@Composable
private fun DayDialog(
    date: LocalDate, evs: List<Ev>, cals: List<Cal>, zone: ZoneId, initial: Pair<Long, Long>?,
    onClose: () -> Unit, onEdit: (Ev) -> Unit, onCopy: (Ev) -> Unit, onNew: () -> Unit, onOpenDay: () -> Unit, onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sel by remember { mutableStateOf(if (initial == null) 0 else evs.indexOfFirst { it.id == initial.first && it.begin == initial.second }.coerceAtLeast(0)) }
    var delAsk by remember { mutableStateOf<Ev?>(null) }
    fun timeOf(e: Ev) = if (e.allDay) "כל היום" else "${hm(e.startTime(zone))}–${hm(e.endTime(zone))}"
    fun dateOf(e: Ev): String {
        val (a, b) = e.days(zone)
        val f = { d: LocalDate -> "${DAY_FULL[dayIdx(d)].replace("יום ", "יום ")}, ${d.dayOfMonth} ב${HE_MONTHS[d.monthValue - 1]} ${d.year}" }
        return if (a == b) f(a) else f(a) + " – " + f(b)
    }
    fun share(e: Ev) {
        val txt = buildString {
            append(e.title).append("\n").append(dateOf(e)).append("\n").append(timeOf(e))
            if (e.location.isNotBlank()) append("\n📍 ").append(e.location)
            val d = CalRepo.load(ctx, e.id)?.desc.orEmpty()
            if (d.isNotBlank()) append("\n\n").append(d)
        }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "שיתוף אירוע").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.8f), shape = RoundedCornerShape(6.dp), color = Color.White) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${DAY_FULL[dayIdx(date)].replace("יום ", "יום ")}, ${date.dayOfMonth} ב${HE_MONTHS[date.monthValue - 1]} ${date.year}", Modifier.weight(1f), fontSize = 20.sp, color = INK)
                        TextButton(onOpenDay) { Text("תצוגת יום", color = BAR) }
                    }
                    HorizontalDivider()
                    if (evs.isEmpty()) Text("אין אירועים ביום הזה", Modifier.padding(24.dp), color = Color.Gray)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        evs.forEachIndexed { i, e ->
                            val col = evColor(e)
                            val isRem = e.id < 0
                            if (i == sel.coerceAtMost(evs.size - 1)) {
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0xFFFAFAFA)).border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))) {
                                    Column(Modifier.fillMaxWidth().background(col).padding(14.dp)) {
                                        Text(if (isRem) "תזכורת" else cals.firstOrNull { it.id == e.calId }?.name.orEmpty(), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
                                        Text(e.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("📅", fontSize = 22.sp); Spacer(Modifier.width(12.dp)); Text(dateOf(e), fontSize = 17.sp, color = INK)
                                    }
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("🕐", fontSize = 22.sp); Spacer(Modifier.width(12.dp)); Text(timeOf(e), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = INK)
                                        if (!e.allDay) Text("  ·  " + durText(((e.end - e.begin) / 60000L).coerceAtLeast(0)), fontSize = 15.sp, color = Color.Gray)
                                    }
                                    if (e.location.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                                        Text("📍", fontSize = 20.sp); Spacer(Modifier.width(12.dp)); Text(e.location, fontSize = 16.sp, color = INK)
                                    }
                                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        if (!isRem) {
                                            TextButton({ delAsk = e }) { Text("🗑", fontSize = 24.sp) }
                                            TextButton({ onCopy(e) }) { Text("📋", fontSize = 24.sp) }
                                            TextButton({ share(e) }) { Text("📤", fontSize = 24.sp) }
                                        }
                                        TextButton({ onEdit(e) }) { Text(if (isRem) "פתח בתזכורות" else "✏️", fontSize = if (isRem) 16.sp else 24.sp) }
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0xFFF1F1F1)).clickable { sel = i }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(col))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text((if (e.repeat) "⇄ " else "") + e.title, fontSize = 18.sp, color = INK)
                                        Text(timeOf(e), fontSize = 16.sp, color = Color.Gray)
                                    }
                                    if (e.alarm || isRem) Text("🔔", fontSize = 20.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(56.dp))
                    }
                }
                FloatingActionButton(onNew, Modifier.align(Alignment.BottomStart).padding(16.dp), containerColor = Color.White, contentColor = Color(0xFFE91E63)) {
                    Icon(Icons.Default.Add, "אירוע חדש")
                }
            }
        }
    }
    delAsk?.let { e ->
        AlertDialog(
            onDismissRequest = { delAsk = null }, title = { Text("למחוק את האירוע?") },
            text = { Text(if (e.repeat) "כל הסדרה החוזרת תימחק גם מיומן גוגל." else "האירוע יימחק גם מיומן גוגל.") },
            confirmButton = { TextButton({ delAsk = null; scope.launch { withContext(Dispatchers.IO) { CalRepo.delete(ctx, e.id) }; sel = 0; onChanged() } }) { Text("מחיקה", color = RED) } },
            dismissButton = { TextButton({ delAsk = null }) { Text("ביטול") } }
        )
    }
}

@Composable
private fun NotifSettings(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(EventNotify.enabled(ctx)) }
    var snd by remember { mutableStateOf(EventNotify.sound(ctx)) }
    var vib by remember { mutableStateOf(EventNotify.vib(ctx)) }
    var led by remember { mutableStateOf(EventNotify.led(ctx)) }
    var snz by remember { mutableStateOf(EventNotify.snooze(ctx)) }
    var ren by remember { mutableStateOf(EventNotify.renotify(ctx)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u: Uri? = r.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (r.resultCode == android.app.Activity.RESULT_OK) { snd = if (u == null) "silent" else u.toString(); prefs.edit().putString("evSound", snd).apply() }
    }
    fun sndName(): String = when (snd) {
        "" -> "ברירת מחדל"; "silent" -> "שקט"
        else -> runCatching { android.media.RingtoneManager.getRingtone(ctx, Uri.parse(snd)).getTitle(ctx) }.getOrDefault("מותאם אישית")
    }
    val vibs = listOf("ANDROID" to "Android", "STRONG" to "חזק", "SHORT" to "קצר", "PULSE" to "פעימות", "NONE" to "ללא")
    val snzs = listOf(5, 10, 15, 30, 60)
    val rens = listOf(0, 5, 10, 15, 30)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("התראות אירועים") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("התראה", fontSize = 17.sp); Text(if (on) "מופעל" else "כבוי", fontSize = 13.sp, color = Color.Gray) }
                    Switch(on, { on = it; prefs.edit().putBoolean("evOn", it).apply() })
                }
                Text("ההתראות נשלחות לפי התזכורות שהוגדרו בכל אירוע. אם אפליקציית יומן אחרת מציגה התראות לאותם אירועים, כדאי לכבות אותן שם כדי שלא יוכפלו.", fontSize = 12.sp, color = Color.Gray)
                Column(Modifier.fillMaxWidth().clickable {
                    picker.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, when (snd) { "" -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION); "silent" -> null; else -> Uri.parse(snd) })
                    })
                }.padding(vertical = 6.dp)) { Text("צליל התראה", fontSize = 17.sp); Text(sndName(), fontSize = 13.sp, color = Color.Gray) }
                Row(Modifier.fillMaxWidth().clickable { led = !led; prefs.edit().putBoolean("evLed", led).apply() }, verticalAlignment = Alignment.CenterVertically) {
                    Text("נורית", Modifier.weight(1f), fontSize = 17.sp); Checkbox(led, { led = it; prefs.edit().putBoolean("evLed", it).apply() })
                }
                Choice("רטט", vibs.first { it.first == vib }.second, vibs.map { it.second }) { vib = vibs[it].first; prefs.edit().putString("evVib", vib).apply() }
                Choice("נודניק", "$snz דקות", snzs.map { "$it דקות" }) { snz = snzs[it]; prefs.edit().putInt("evSnooze", snz).apply() }
                Choice("Re-Notify", if (ren == 0) "כבוי" else "כל $ren דקות", rens.map { if (it == 0) "כבוי" else "כל $it דקות" }) { ren = rens[it]; prefs.edit().putInt("evRenotify", ren).apply() }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("סגירה") } }
    )
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
private fun WeekDayCell(d: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, modifier: Modifier, onDay: (LocalDate) -> Unit, onEvent: (Ev, LocalDate) -> Unit, onNew: (LocalDate, LocalTime?) -> Unit) {
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
                shown.forEach { e -> EvLine(e, z, 13.sp) { onEvent(e, d) } }
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
private fun WeekView(date: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, onDay: (LocalDate) -> Unit, onEvent: (Ev, LocalDate) -> Unit, onNew: (LocalDate, LocalTime?) -> Unit, onShift: (Int) -> Unit) {
    val start = wkStart(date)
    Row(Modifier.fillMaxSize().background(Color.White)) {
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
private fun MonthView(date: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, onDay: (LocalDate) -> Unit, onEvent: (Ev, LocalDate) -> Unit, onShift: (Int) -> Unit) {
    val first = date.withDayOfMonth(1)
    val gridStart = wkStart(first)
    Column(Modifier.fillMaxSize().background(Color.White)) {
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
private fun MonthCell(d: LocalDate, inMonth: Boolean, all: List<Ev>, z: ZoneId, today: LocalDate, modifier: Modifier, onDay: (LocalDate) -> Unit, onEvent: (Ev, LocalDate) -> Unit) {
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
            shown.forEach { e -> EvLine(e, z, 12.sp, false) { onEvent(e, d) } }
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
    val hourH = 64.dp
    val scroll = rememberScrollState()
    val dens = LocalDensity.current
    LaunchedEffect(date) {
        val firstH = evs.filter { !it.allDay }.minOfOrNull { spanOn(it, date, z).first / 60 }
        var h = if (date == today) maxOf(0, LocalTime.now().hour - 1) else 6
        if (firstH != null && firstH < h) h = firstH
        scroll.scrollTo((hourH.value * dens.density * h).toInt())
    }
    val curShift by rememberUpdatedState(onShift)
    val edge = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                acc = if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && consumed.y == 0f) acc + available.y else 0f
                return Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val a = acc; acc = 0f
                if (a < -40f) curShift(1) else if (a > 40f) curShift(-1)
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            evs.filter { it.allDay }.forEach { e -> EvLine(e, z, 16.sp) { onEvent(e) } }
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
                      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Box(Modifier.fillMaxSize()) {
                        for (h in 0..23) {
                            Box(Modifier.absoluteOffset(y = hourH * h).fillMaxWidth().height(1.dp).background(GRID))
                            Text("%02d".format(h), fontSize = 13.sp, color = Color(0xFF777777), modifier = Modifier.align(Alignment.TopStart).absoluteOffset(y = hourH * h - 9.dp).padding(start = 8.dp))
                        }
                        val slots = remember(evs) { evs.filter { !it.allDay }.map { val (a, b) = spanOn(it, date, z); DSlot(it, a, b) }.also { placeSlots(it) } }
                        slots.forEach { s ->
                            val w = areaW / s.n
                            val h = maxOf(22.dp, hourH * ((s.b - s.a) / 60f))
                            val col = evColor(s.e)
                            Box(
                                Modifier.absoluteOffset(x = labelW + 2.dp + w * s.col, y = hourH * (s.a / 60f)).size(w - 2.dp, h - 1.dp)
                                    .clip(RoundedCornerShape(5.dp)).background(col.copy(alpha = 0.92f)).border(1.dp, Color.Black.copy(alpha = 0.25f), RoundedCornerShape(5.dp))
                                    .clickable { onEvent(s.e) }.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text("${hm(s.e.startTime(z))}–${hm(s.e.endTime(z))} ${s.e.title}" + if (s.e.repeat) " ⇄" else "", fontSize = 16.sp, color = Color.White, maxLines = if (h > 44.dp) 4 else 1, overflow = TextOverflow.Ellipsis)
                                }
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
            }
        }
        Column(Modifier.width(64.dp).fillMaxHeight().background(Color(0xFFEFF3F7)).padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(HE_MONTHS[date.monthValue - 1], fontSize = 12.sp, color = BAR, maxLines = 1)
            Text("${date.dayOfMonth}", fontSize = 34.sp, color = BAR, fontWeight = FontWeight.Medium)
            Text(DAY_FULL[dayIdx(date)].removePrefix("יום "), fontSize = 12.sp, color = BAR, maxLines = 1)
            Spacer(Modifier.weight(1f))
        }
    }
}

// ============================ EDITOR ============================

private val REM_OPTS = listOf(0, 5, 10, 15, 30, 45, 60, 120, 1440, 2880, 10080)
private fun remLabel(m: Int) = when {
    m == 0 -> "בזמן האירוע"
    m < 60 -> "$m דקות"
    m == 60 -> "שעה"
    m < 1440 -> "${m / 60} שעות"
    m == 1440 -> "יום"
    m % 10080 == 0 -> if (m == 10080) "שבוע" else "${m / 10080} שבועות"
    else -> "${m / 1440} ימים"
}
private val AVAIL = listOf(0 to "עסוק", 1 to "פנוי")
private val ACCESS = listOf(0 to "ברירת מחדל", 3 to "ציבורי", 2 to "פרטי", 1 to "חסוי")
private fun durText(mins: Long): String = when {
    mins <= 0 -> "0 דקות"
    mins < 60 -> "$mins דקות"
    mins % 1440 == 0L -> if (mins == 1440L) "יום" else "${mins / 1440} ימים"
    mins % 60 == 0L -> if (mins == 60L) "שעה" else "${mins / 60} שעות"
    else -> "${mins / 60}:%02d שעות".format(mins % 60)
}
private val REPEATS = listOf("" to "ללא חזרה", "DAILY" to "כל יום", "WEEKLY" to "כל שבוע", "MONTHLY" to "כל חודש", "YEARLY" to "כל שנה")

@Composable
private fun EventEditor(target: EditTarget, cals: List<Cal>, defaultCal: Long, zone: ZoneId, onClose: () -> Unit, onDefault: (Long) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val writable = cals.filter { it.writable }
    val ev = target.ev
    var full by remember { mutableStateOf<EvFull?>(null) }
    val src = ev ?: target.copyOf
    var loaded by remember { mutableStateOf(src == null) }
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
    val rems = remember { mutableStateListOf(10) }
    var avail by remember { mutableStateOf(0) }
    var access by remember { mutableStateOf(0) }
    val guests = remember { mutableStateListOf<String>() }
    var guestIn by remember { mutableStateOf("") }
    var rep by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf(false) }
    var pickCal by remember { mutableStateOf(false) }

    LaunchedEffect(src?.id) {
        if (src != null) {
            val f = withContext(Dispatchers.IO) { CalRepo.load(ctx, src.id) }
            full = if (ev != null) f else null          // a copy is saved as a new event
            title = f?.title ?: src.title
            calId = f?.calId ?: src.calId
            allDay = src.allDay
            val (a, b) = src.days(zone)
            sd = a; ed = b
            if (!src.allDay) { stT = src.startTime(zone); enT = src.endTime(zone) }
            loc = f?.location ?: src.location; desc = f?.desc.orEmpty()
            rems.clear(); rems.addAll(f?.reminders.orEmpty())
            avail = f?.availability ?: 0; access = f?.access ?: 0
            guests.clear(); guests.addAll(f?.guests.orEmpty())
            rep = if (ev == null) "" else if (f != null && f.rrule.isNotEmpty()) (REPEATS.map { it.first }.firstOrNull { it.isNotEmpty() && f.rrule == "FREQ=$it" } ?: "CUSTOM") else ""
            loaded = true
        }
    }
    BackHandler { onClose() }
    val canWrite = cals.firstOrNull { it.id == calId }?.writable != false
    fun save() {
        if (calId < 0) { android.widget.Toast.makeText(ctx, "אין יומן לכתיבה", android.widget.Toast.LENGTH_SHORT).show(); return }
        val s = EdState(title.trim(), calId, sd, stT, if (ed.isBefore(sd)) sd else ed, enT, allDay, loc, desc, rems.toList(), rep, avail, access, guests.toList())
        scope.launch {
            val id = withContext(Dispatchers.IO) { CalRepo.save(ctx, full, ev?.begin ?: 0L, s) }
            if (id < 0) android.widget.Toast.makeText(ctx, "השמירה נכשלה", android.widget.Toast.LENGTH_SHORT).show() else onChanged()
        }
    }
    fun startMoved(oldD: LocalDate, oldT: LocalTime, newD: LocalDate, newT: LocalTime) {
        // keep the duration when the start moves
        val delta = java.time.Duration.between(oldD.atTime(oldT), newD.atTime(newT))
        val e = ed.atTime(enT).plus(delta)
        ed = e.toLocalDate(); enT = e.toLocalTime()
    }
    fun shiftEnd(mins: Long) {
        val e = ed.atTime(enT).plusMinutes(mins)
        if (e.isAfter(sd.atTime(stT))) { ed = e.toLocalDate(); enT = e.toLocalTime() }
    }
    fun pickDate(cur: LocalDate, set: (LocalDate) -> Unit) =
        DatePickerDialog(ctx, { _, y, m, d -> set(LocalDate.of(y, m + 1, d)) }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
    fun pickTime(cur: LocalTime, set: (LocalTime) -> Unit) =
        android.app.TimePickerDialog(ctx, { _, h, m -> set(LocalTime.of(h, m)) }, cur.hour, cur.minute, true).show()

    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFFE91E63)).statusBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
                Text(if (ev == null) (if (target.copyOf != null) "העתקת אירוע" else "פגישה חדשה") else "עריכת פגישה", Modifier.weight(1f), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                    OutlinedButton({ pickDate(sd) { n -> if (allDay) { sd = n; if (ed.isBefore(n)) ed = n } else { val od = sd; sd = n; startMoved(od, stT, n, stT) } } }, Modifier.weight(1f)) { Text("${sd.dayOfMonth}/${sd.monthValue}/${sd.year}") }
                    if (!allDay) OutlinedButton({ pickTime(stT) { n -> val ot = stT; stT = n; startMoved(sd, ot, sd, n) } }, Modifier.weight(0.7f)) { Text(hm(stT)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pickDate(ed) { ed = it } }, Modifier.weight(1f)) { Text("${ed.dayOfMonth}/${ed.monthValue}/${ed.year}") }
                    if (!allDay) OutlinedButton({ pickTime(enT) { enT = it } }, Modifier.weight(0.7f)) { Text(hm(enT)) }
                }
                if (!allDay) {
                    val mins = java.time.Duration.between(sd.atTime(stT), ed.atTime(enT)).toMinutes()
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⏱ " + durText(mins), fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
                        listOf("−1h" to -60L, "−15" to -15L, "+15" to 15L, "+1h" to 60L).forEach { (l, d) ->
                            Text(l, Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFE6E6E6)).clickable { shiftEnd(d) }.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 15.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Choice("📌", AVAIL.first { it.first == avail }.second, AVAIL.map { it.second }) { avail = AVAIL[it].first } }
                    Box(Modifier.weight(1f)) { Choice("👁", ACCESS.firstOrNull { it.first == access }?.second ?: "ברירת מחדל", ACCESS.map { it.second }) { access = ACCESS[it].first } }
                }
                OutlinedTextField(loc, { loc = it }, Modifier.fillMaxWidth(), label = { Text("מיקום") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("תיאור") }, minLines = 2)
                Text("👥 אורחים", fontWeight = FontWeight.Bold, color = INK)
                guests.toList().forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g, Modifier.weight(1f), fontSize = 15.sp)
                        TextButton({ guests.remove(g) }) { Text("✕", color = RED) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(guestIn, { guestIn = it }, Modifier.weight(1f), label = { Text("כתובת מייל של אורח") }, singleLine = true)
                    TextButton({
                        val g = guestIn.trim()
                        if (g.contains("@") && guests.none { it.equals(g, true) }) { guests.add(g); guestIn = "" }
                        else android.widget.Toast.makeText(ctx, "כתובת מייל לא תקינה", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("הוסף") }
                }
                Text("🔔 תזכורות", fontWeight = FontWeight.Bold, color = INK)
                rems.toList().forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Choice("התראה", remLabel(m) + (if (m > 0) " לפני" else ""), REM_OPTS.map { remLabel(it) }) { rems[i] = REM_OPTS[it] } }
                        TextButton({ rems.removeAt(i) }) { Text("✕", color = RED) }
                    }
                }
                TextButton({ rems.add(REM_OPTS.firstOrNull { it !in rems } ?: 10) }) { Text("＋ הוסף תזכורת", color = Color(0xFFE91E63)) }
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
