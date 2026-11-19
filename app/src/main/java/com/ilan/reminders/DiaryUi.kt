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
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
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
private val BAR: Color get() = Color(DS.bar)
private val ORANGE: Color get() = Color(DS.today)
private val WEEKEND_BG = Color(0xFFEFEFEF)
private val GRID = Color(0xFFD9DCE3)
private val INK = Color(0xFF222222)
private val RED = Color(0xFFE53935)
private val DAY_FULL = listOf("יום ראשון", "יום שני", "יום שלישי", "יום רביעי", "יום חמישי", "יום שישי", "יום שבת")
private val MON_SHORT = listOf("ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יוני", "יולי", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳")

private val HE_MON_SHORT = listOf("ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יוני", "יולי", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳")
private fun wkStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value % 7).toLong())
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

class EditTarget(val ev: Ev?, val date: LocalDate, val time: LocalTime?, val copyOf: Ev? = null, val pre: String? = null)

@Composable
fun DiaryHost(onReminders: () -> Unit, onNotes: () -> Unit, onDocs: () -> Unit, onTask: (Task) -> Unit = {}) {
    val ctx = LocalContext.current
    remember { DS.load(ctx); 0 }
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
        } else {
            val cur = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(cur.density, cur.fontScale * DS.scale)) {
                androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = DS.fontFamily())) {
                    DiaryMain(onReminders, onNotes, onDocs, onTask)
                }
            }
        }
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
    var showFont by remember { mutableStateOf(false) }
    var showDefs by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var dayDlg by remember { mutableStateOf<LocalDate?>(null) }
    var dayEvSel by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(tick) { withContext(Dispatchers.IO) { runCatching { EventNotify.run(ctx) }; runCatching { EventAtt.purge(ctx) } } }
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
                .let { l -> val ai = EventAtt.ids(ctx) + DocLinks.eventIds(ctx); if (ai.isEmpty()) l else l.map { if (it.id in ai) it.copy(att = true) else it } }
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
    val pend = PendingOpen.evId
    LaunchedEffect(pend, PendingOpen.evBegin) {
        if (pend == null) return@LaunchedEffect
        val b = PendingOpen.evBegin
        val d = java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        editing = null; showSettings = false; dayDlg = null
        dayNum = d.toEpochDay()
        if (pager.currentPage != 1) pager.scrollToPage(1)
        var waited = 0
        while (waited < 3000 && events.none { it.id == pend && it.begin == b }) { kotlinx.coroutines.delay(100); waited += 100 }
        dayEvSel = pend to b
        dayDlg = d
        PendingOpen.evId = null
    }
    val onDay: (LocalDate) -> Unit = { d -> dayEvSel = null; dayDlg = d }
    val onCellEv: (Ev, LocalDate) -> Unit = { e, d -> dayEvSel = e.id to e.begin; dayDlg = d }
    val onEvent: (Ev) -> Unit = { e -> if (e.id < 0) remTask = taskList.firstOrNull { it.id == -e.id } else editing = EditTarget(e, e.days(zone).first, null) }
    val onNew: (LocalDate, LocalTime?) -> Unit = { d, t -> editing = EditTarget(null, d, t) }
    var weekCell by remember { mutableStateOf(prefs.getString("weekCell", "chart") ?: "chart") }
    var showWeekCell by remember { mutableStateOf(false) }
    val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == android.app.Activity.RESULT_OK && !text.isNullOrBlank()) {
            val ds = DS.defStart(ctx)
            val v = VoiceParse.parse(text, java.time.ZonedDateTime.now(), if (ds >= 0) LocalTime.of(ds / 60, ds % 60) else LocalTime.of(9, 0))
            val at = v.at?.withZoneSameInstant(zone)
            editing = EditTarget(null, at?.toLocalDate() ?: date, at?.toLocalTime(), null, v.title)
        }
    }
    val startVoice = {
        runCatching {
            voiceLauncher.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "למשל: פגישה עם דנה מחר ב-10"))
        }.onFailure { Toast.makeText(ctx, "זיהוי קולי לא זמין במכשיר", Toast.LENGTH_SHORT).show() }
        Unit
    }

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
                            DropdownMenuItem({ Text("⚙ הגדרות") }, { menuNav = false; showSettings = true })
                        }
                    }
                    val page = pager.currentPage
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        val mon = HE_MONTHS[date.monthValue - 1]
                        val wa = wkStart(date); val wb = wa.plusDays(6)
                        val title = when (page) {
                            2 -> "${date.dayOfMonth} ב$mon ${date.year}"
                            1 -> if (wa.month == wb.month) mon else if (wa.year == wb.year) "${HE_MON_SHORT[wa.monthValue - 1]} / ${HE_MON_SHORT[wb.monthValue - 1]}" else "${HE_MON_SHORT[wa.monthValue - 1]} ${wa.year}"
                            else -> mon
                        }
                        val sub = when (page) {
                            2 -> DAY_FULL[dayIdx(date)]
                            1 -> if (wa.year == wb.year) "${wb.year}" else "${HE_MON_SHORT[wb.monthValue - 1]} ${wb.year}"
                            else -> "${date.year}"
                        }
                        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
                    }
                    Text("▶", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { shift(-1) }.padding(horizontal = 10.dp, vertical = 12.dp))
                    Text("◀", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { shift(1) }.padding(horizontal = 10.dp, vertical = 12.dp))
                    Text("היום", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { dayNum = today.toEpochDay() }.padding(horizontal = 10.dp, vertical = 6.dp))
                    IconButton({ startVoice() }) { Text("🎤", fontSize = 20.sp) }
                    IconButton({ onNew(date, null) }) { Icon(Icons.Default.Add, "פגישה חדשה", tint = Color.White) }
                    Box {
                        IconButton({ menuMore = true }) { Icon(Icons.Default.MoreVert, "עוד", tint = Color.White) }
                        DropdownMenu(menuMore, { menuMore = false }) {
                            DropdownMenuItem({ Text("מעבר לתאריך…") }, {
                                menuMore = false
                                DatePickerDialog(ctx, { _, y, m, d -> dayNum = LocalDate.of(y, m + 1, d).toEpochDay() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                            })
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
                            1 -> VPager(date, { wkStart(it) }, { d, n -> d.plusWeeks(n.toLong()) }, { dayNum = it.toEpochDay() }) { d -> WeekView(d, events, zone, today, onDay, onCellEv, onNew, cals, taskList, weekCell, listOf(onReminders, onNotes, onDocs)) }
                            else -> VPager(date, { it.year * 12 + it.monthValue }, { d, n -> d.plusMonths(n.toLong()) }, { dayNum = it.toEpochDay() }) { d -> MonthView(d, events, zone, today, onDay, onCellEv, {}) }
                        }
                    }
                }
            }
        }
        dayDlg?.let { d ->
          androidx.compose.runtime.key(d, dayEvSel) {
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
            target = t, cals = cals, visible = visible, defaultCal = defCal, zone = zone,
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
        if (showSettings) DiarySettingsHost(cals, visible, defCal, weekCell, onWeekCell = { weekCell = it; prefs.edit().putString("weekCell", it).apply() },
            onSaveCals = { vis, def ->
                visPref = vis; defCal = def; tick++
                prefs.edit().putStringSet("diaryVis", vis.map { it.toString() }.toSet()).putLong("diaryDef", def).apply()
            }, onClose = { DS.save(ctx); showSettings = false; tick++ })
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
    var initDone by remember { mutableStateOf(false) }
    LaunchedEffect(initial, evs.size) {
        if (initial != null && !initDone) { val i = evs.indexOfFirst { it.id == initial.first && it.begin == initial.second }; if (i >= 0) { sel = i; initDone = true } }
    }
    var delAsk by remember { mutableStateOf<Ev?>(null) }
    var viewAtt by remember { mutableStateOf<String?>(null) }
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
                                        Text(e.title + (if (e.repeat) " ⇄" else ""), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
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
                                    if (!isRem) DocLinkChips(remember(e.id) { DocLinks.forEvent(ctx, e.id) }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                                    if (!isRem && e.att) {
                                        val al = remember(e.id) { EventAtt.list(ctx, e.id) }
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            al.forEach { a ->
                                                val bm = remember(a.file) { EventAtt.thumb(ctx, a.file, null, 240) }
                                                if (bm != null) androidx.compose.foundation.Image(bm.asImageBitmap(), null, Modifier.size(84.dp).clip(RoundedCornerShape(6.dp)).clickable { viewAtt = a.file }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                            }
                                        }
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
                                        Text(e.title + (if (e.repeat) " ⇄" else ""), fontSize = 18.sp, color = INK)
                                        Text(timeOf(e), fontSize = 16.sp, color = Color.Gray)
                                    }
                                    if (e.att && !isRem) Text("📎", fontSize = 20.sp)
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
    viewAtt?.let { f ->
        androidx.compose.ui.window.Dialog({ viewAtt = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            val bm = remember(f) { EventAtt.thumb(ctx, f, null, 2000) }
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewAtt = null }) {
                if (bm != null) androidx.compose.foundation.Image(bm.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            }
        }
    }
    delAsk?.let { e -> DeleteDialog(e, { delAsk = null }) { delAsk = null; sel = 0; onChanged() } }
}

@Composable
private fun NotifSettings(tab: Boolean = false, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(EventNotify.enabled(ctx)) }
    var snd by remember { mutableStateOf(EventNotify.sound(ctx)) }
    var vib by remember { mutableStateOf(EventNotify.vib(ctx)) }
    var led by remember { mutableStateOf(EventNotify.led(ctx)) }
    var snz by remember { mutableStateOf(EventNotify.snooze(ctx)) }
    var ren by remember { mutableStateOf(EventNotify.renotify(ctx)) }
    var shw by remember { mutableStateOf(EventNotify.showSecs(ctx)) }
    val shws = listOf(0) + (1..12).map { it * 5 }
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
    DlgOrTab(tab, 
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
                Choice("חלון התראה על המסך (מעל אפליקציות)", if (shw == 0) "עד לסגירה" else if (shw == 60) "דקה" else "$shw שניות", shws.map { if (it == 0) "עד לסגירה" else if (it == 60) "דקה" else "$it שניות" }) {
                    shw = shws[it]; prefs.edit().putInt("evShowSecs", shw).apply()
                    if (shw > 0 && !EventOverlay.can(ctx)) {
                        Toast.makeText(ctx, "יש לאשר \"הצגה מעל אפליקציות אחרות\" לסדר יום", Toast.LENGTH_LONG).show()
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                    }
                }
                Choice("Re-Notify", if (ren == 0) "כבוי" else "כל $ren דקות", rens.map { if (it == 0) "כבוי" else "כל $it דקות" }) { ren = rens[it]; prefs.edit().putInt("evRenotify", ren).apply() }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("סגירה") } }
    )
}

@Composable
private fun SettingsDialog(cals: List<Cal>, visible: Set<Long>, defCal: Long, tab: Boolean = false, onDismiss: () -> Unit, onSave: (Set<Long>, Long) -> Unit) {
    val ctx = LocalContext.current
    var vis by remember { mutableStateOf(visible) }
    var def by remember { mutableStateOf(defCal) }
    var rev by remember { mutableStateOf(0) }
    if (tab) LaunchedEffect(vis, def) { onSave(vis, def) }
    val list = remember(rev, cals) { CalRepo.calendars(ctx) }
    var menu by remember { mutableStateOf<Long?>(null) }
    var rename by remember { mutableStateOf<Cal?>(null) }
    var recolor by remember { mutableStateOf<Cal?>(null) }
    val palette = listOf(0, 0xFFD50000.toInt(), 0xFFE67C73.toInt(), 0xFFF4511E.toInt(), 0xFFF6BF26.toInt(), 0xFF33B679.toInt(), 0xFF0B8043.toInt(), 0xFF039BE5.toInt(), 0xFF3F51B5.toInt(), 0xFF7986CB.toInt(), 0xFF8E24AA.toInt(), 0xFF616161.toInt())
    DlgOrTab(tab, 
        onDismissRequest = onDismiss,
        title = { Text("יומנים") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("סמן יומנים להצגה (רק יומנים מסומנים ניתנים לבחירה ביצירת אירוע; 🔒 = קריאה בלבד). בתפריט ⋮: צבע, שם, ברירת מחדל, סנכרון והתראות לכל יומן (צבע ושם נשמרים באפליקציה בלבד).", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                list.groupBy { it.account }.forEach { (acc, items) ->
                    Text(acc, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    items.forEach { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.id in vis, { on -> vis = if (on) vis + c.id else vis - c.id })
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(c.color or 0xFF000000.toInt())))
                            Text(c.name + (if (def == c.id) " ★" else "") + (if (!c.writable) " 🔒" else ""), Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box {
                                IconButton({ menu = c.id }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "אפשרויות") }
                                DropdownMenu(menu == c.id, { menu = null }) {
                                    DropdownMenuItem({ Text("צבע היומן") }, { menu = null; recolor = c })
                                    DropdownMenuItem({ Text("שינוי שם") }, { menu = null; rename = c })
                                    if (c.writable) DropdownMenuItem({ Text(if (def == c.id) "✓ יומן ברירת מחדל" else "קבע כיומן ברירת מחדל") }, { def = c.id; menu = null })
                                    val sy = remember(menu, rev) { CalRepo.syncOn(ctx, c.id) }
                                    DropdownMenuItem({ Text((if (sy) "✓ " else "") + "סנכרון") }, {
                                        if (!CalRepo.setSync(ctx, c.id, !sy)) Toast.makeText(ctx, "לא ניתן לשנות סנכרון ליומן זה", Toast.LENGTH_SHORT).show()
                                        rev++; menu = null
                                    })
                                    val nOff = remember(menu, rev) { c.id in CalRepo.notifOff(ctx) }
                                    DropdownMenuItem({ Text((if (!nOff) "✓ " else "") + "התראות לאירועים") }, { CalRepo.setNotifOff(ctx, c.id, !nOff); rev++; menu = null })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSave(vis, def) }) { Text("שמירה") } },
        dismissButton = { TextButton(onDismiss) { Text("ביטול") } }
    )
    rename?.let { c ->
        var t by remember(c.id) { mutableStateOf(c.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("שינוי שם יומן") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true, supportingText = { Text("ריק = השם המקורי") }) },
            confirmButton = { TextButton({ CalRepo.setOverride(ctx, c.id, t, null); rev++; rename = null }) { Text("אישור") } },
            dismissButton = { TextButton({ rename = null }) { Text("ביטול") } })
    }
    recolor?.let { c ->
        AlertDialog(onDismissRequest = { recolor = null }, title = { Text("צבע היומן") },
            text = { Column { Text("«A» = הצבע המקורי", fontSize = 12.sp); Spacer(Modifier.height(8.dp)); SwatchRow(palette, c.color or 0xFF000000.toInt()) { col -> CalRepo.setOverride(ctx, c.id, null, if (col == 0) 0 else col); rev++; recolor = null } } },
            confirmButton = {}, dismissButton = { TextButton({ recolor = null }) { Text("סגירה") } })
    }
}

// ============================ WEEK ============================

/** how many of [evs] fit in [avail] when a long title wraps onto a second line (estimated from text length) */
private fun packEvs(evs: List<Ev>, avail: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp, sizeSp: Float, lineDp: Float, showTime: Boolean): List<Ev> {
    val sc = DS.scale
    val cpl = ((width.value - 8f) / (sizeSp * sc * 0.56f)).toInt().coerceAtLeast(4)
    var used = 0f
    val out = ArrayList<Ev>()
    for (e in evs) {
        val len = e.title.length + (if (showTime && !e.allDay) 6 else 0) + (if (e.repeat) 2 else 0) + (if (e.att) 3 else 0)
        val h = (if (len > cpl) 2 else 1) * lineDp * sc
        if (used + h > avail.value + 1f) break
        used += h; out.add(e)
    }
    return out
}

@Composable
private fun EvLine(e: Ev, z: ZoneId, size: TextUnit, showTime: Boolean = true, onClick: () -> Unit) {
    val col = if (DS.evText != 0) Color(DS.evText) else evColor(e)
    if (e.allDay) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp).clip(RoundedCornerShape(3.dp)).background(col.copy(alpha = 0.30f)).clickable(onClick = onClick).padding(horizontal = 4.dp)) {
            Text(e.title + (if (e.repeat) " ⇄" else "") + (if (e.att) " 📎" else ""), fontSize = size, maxLines = 2, overflow = TextOverflow.Ellipsis, color = INK)
        }
    } else {
        Text((if (showTime) "${hm(e.startTime(z))} " else "") + e.title + (if (e.repeat) " ⇄" else "") + (if (e.att) " 📎" else ""), fontSize = size, maxLines = 2, overflow = TextOverflow.Ellipsis, color = col,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp))
    }
}

@Composable
private fun WeekDayCell(d: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, modifier: Modifier, onDay: (LocalDate) -> Unit, onEvent: (Ev, LocalDate) -> Unit, onNew: (LocalDate, LocalTime?) -> Unit) {
    val idx = dayIdx(d); val isToday = d == today
    val evs = remember(all, d) { dayEvs(all, d, z) }
    BoxWithConstraints(modifier.background(if (idx >= 5) WEEKEND_BG else Color.White)) {
        val shown = packEvs(evs, maxHeight - 30.dp, maxWidth, 13f, 18f, true)
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
private fun WeekView(date: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, onDay: (LocalDate) -> Unit, onEvent: (Ev, LocalDate) -> Unit, onNew: (LocalDate, LocalTime?) -> Unit, cals: List<Cal>, tasks: List<Task>, cellMode: String, nav: List<() -> Unit>) {
    val start = wkStart(date)
    Row(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            for (i in 0..3) WeekDayCell(start.plusDays(i.toLong()), all, z, today, Modifier.weight(1f).fillMaxWidth(), onDay, onEvent, onNew)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(GRID))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            for (i in 4..6) WeekDayCell(start.plusDays(i.toLong()), all, z, today, Modifier.weight(1f).fillMaxWidth(), onDay, onEvent, onNew)
            if (cellMode == "chart") MiniWeek(start, all, z, date, today, Modifier.weight(1f).fillMaxWidth(), onDay)
            else WeekExtraCell(cellMode, start, all, z, today, cals, tasks, nav, Modifier.weight(1f).fillMaxWidth())
        }
    }
}


private val WEEK_CELL_OPTIONS = listOf(
    "chart" to "גרף עומס השבוע",
    "hours" to "סיכום שעות לפי יומן",
    "tasks" to "משימות ותזכורות פתוחות השבוע",
    "next" to "האירוע הבא",
    "free" to "חלונות פנויים",
    "note" to "פתק חופשי לשבוע",
    "links" to "מעבר לתזכורות, פתקים ומסמכים"
)

@Composable
private fun WeekCellSettings(cur: String, tab: Boolean = false, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    DlgOrTab(tab, onDismissRequest = onDismiss, title = { Text("משבצת בתצוגת שבוע") },
        text = {
            Column {
                WEEK_CELL_OPTIONS.forEach { (k, l) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(k) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(cur == k, { onPick(k) }); Spacer(Modifier.width(6.dp)); Text(l, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("סגירה") } })
}

@Composable
private fun WeekExtraCell(mode: String, start: LocalDate, all: List<Ev>, z: ZoneId, today: LocalDate, cals: List<Cal>, tasks: List<Task>, nav: List<() -> Unit>, modifier: Modifier) {
    val ctx = LocalContext.current
    val endD = start.plusDays(6)
    val weekMs0 = start.atStartOfDay(z).toInstant().toEpochMilli()
    val weekMs1 = endD.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    val head: @Composable (String) -> Unit = { t -> Text(t, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BAR) }
    Column(modifier.background(Color(0xFFF3F6F9)).padding(horizontal = 8.dp, vertical = 6.dp).verticalScroll(rememberScrollState())) {
        when (mode) {
            "hours" -> {
                head("שעות פגישות השבוע")
                val per = remember(all, start) {
                    all.filter { !it.allDay }.groupBy { it.calId }.mapValues { (_, l) -> l.sumOf { (minOf(it.end, weekMs1) - maxOf(it.begin, weekMs0)).coerceAtLeast(0L) } / 60000 }
                        .filter { it.value > 0 }.toList().sortedByDescending { it.second }
                }
                if (per.isEmpty()) Text("אין פגישות", fontSize = 12.sp, color = Color.Gray)
                per.forEach { (id, m) ->
                    val c = cals.firstOrNull { it.id == id }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(Color((c?.color ?: 0xFF4A7391.toInt()) or 0xFF000000.toInt())))
                        Text(c?.name.orEmpty(), Modifier.weight(1f).padding(horizontal = 5.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${m / 60}:%02d".format(m % 60), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (per.size > 1) Text("סה״כ ${per.sumOf { it.second } / 60}:%02d".format(per.sumOf { it.second } % 60), fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
            }
            "tasks" -> {
                head("משימות השבוע")
                val l = remember(tasks, start) { tasks.filter { !it.done && it.deletedAt == 0L && it.triggerAt in weekMs0 until weekMs1 }.sortedBy { it.triggerAt } }
                if (l.isEmpty()) Text("אין משימות פתוחות", fontSize = 12.sp, color = Color.Gray)
                l.take(12).forEach { t ->
                    val dt = Instant.ofEpochMilli(t.triggerAt).atZone(z)
                    Text("${HE_DAYS[dayIdx(dt.toLocalDate())]} ${hm(dt.toLocalTime())} ${t.title}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 1.dp))
                }
                if (l.size > 12) Text("+${l.size - 12}", fontSize = 12.sp, color = ORANGE)
            }
            "next" -> {
                head("האירוע הבא")
                val nowMs = System.currentTimeMillis()
                val n = all.filter { !it.allDay && it.end > nowMs }.minByOrNull { it.begin }
                if (n == null) Text("אין אירועים קרובים", fontSize = 12.sp, color = Color.Gray)
                else {
                    val dt = Instant.ofEpochMilli(n.begin).atZone(z)
                    val mins = ((n.begin - nowMs) / 60000).coerceAtLeast(0)
                    Text(n.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${if (dt.toLocalDate() == today) "היום" else "${HE_DAYS[dayIdx(dt.toLocalDate())]} ${dt.dayOfMonth}/${dt.monthValue}"} ${hm(dt.toLocalTime())}", fontSize = 12.sp)
                    Text(if (n.begin <= nowMs) "מתקיים עכשיו" else if (mins >= 1440) "בעוד ${mins / 1440} ימים" else if (mins >= 60) "בעוד ${mins / 60}:%02d ש׳".format(mins % 60) else "בעוד $mins דק׳", fontSize = 13.sp, color = ORANGE, fontWeight = FontWeight.Bold)
                }
            }
            "free" -> {
                head("חלונות פנויים (8–18)")
                val slots = remember(all, start) {
                    val out = ArrayList<Triple<LocalDate, Int, Int>>()
                    val nowMs = System.currentTimeMillis()
                    for (i in 0..5) {
                        val d = start.plusDays(i.toLong())
                        if (d.isBefore(today)) continue
                        var from = 480
                        if (d == today) { val nm = Instant.ofEpochMilli(nowMs).atZone(z); from = maxOf(from, nm.hour * 60 + nm.minute) }
                        val busy = dayEvs(all, d, z).filter { !it.allDay }.map { spanOn(it, d, z) }.sortedBy { it.first }
                        var cur = from
                        for ((a, b) in busy) { if (a - cur >= 60 && cur < 1080) out.add(Triple(d, cur, minOf(a, 1080))); cur = maxOf(cur, b) }
                        if (1080 - cur >= 60) out.add(Triple(d, cur, 1080))
                    }
                    out.sortedByDescending { it.third - it.second }.take(5).sortedWith(compareBy({ it.first }, { it.second }))
                }
                if (slots.isEmpty()) Text("אין חלונות של שעה ומעלה", fontSize = 12.sp, color = Color.Gray)
                slots.forEach { (d, a, b) -> Text("${HE_DAYS[dayIdx(d)]} ${d.dayOfMonth}/${d.monthValue}  ${hm(LocalTime.of(a / 60, a % 60))}–${hm(LocalTime.of(b / 60, b % 60))}", fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp)) }
            }
            "links" -> {
                listOf("⏰ תזכורות", "📝 פתקים", "📄 מסמכים").forEachIndexed { i, l ->
                    Text(l, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BAR, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
                        .clickable { nav[i]() }.padding(horizontal = 10.dp, vertical = 7.dp))
                    Spacer(Modifier.height(4.dp))
                }
            }
            else -> {
                val key = "weekNote_${start.toEpochDay()}"
                val p = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
                var t by remember(key) { mutableStateOf(p.getString(key, "") ?: "") }
                head("פתק לשבוע")
                androidx.compose.foundation.text.BasicTextField(t, { t = it; p.edit().putString(key, it).apply() }, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 60.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = INK))
                if (t.isEmpty()) Text("לחץ והקלד…", fontSize = 12.sp, color = Color.Gray)
            }
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
        val shown = packEvs(evs, maxHeight - 24.dp, maxWidth, 12f, 15f, false)
        val hidden = evs.size - shown.size
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${d.dayOfMonth}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (isToday) Color.White else if (idx == 6) RED else INK,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (isToday) ORANGE else Color.Transparent).padding(horizontal = 3.dp))
                if (hidden > 0) Text(" +$hidden", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ORANGE)
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

private val REM_OPTS = listOf(0, 1, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1080, 1440, 2880, 4320, 7200, 10080, 14400, 20160)
private fun remLabel(m: Int) = when {
    m == 0 -> "בזמן האירוע"
    m % 1440 == 0 -> if (m == 1440) "1 יום" else "${m / 1440} ימים"
    m >= 60 && m % 60 == 0 -> if (m == 60) "1 שעה" else "${m / 60} שעות"
    m > 60 && m < 1440 -> (if (m / 60 == 1) "1 שעה" else "${m / 60} שעות") + " ${m % 60} דקות"
    m == 1 -> "1 דקה"
    else -> "$m דקות"
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

@Composable
private fun EventEditor(target: EditTarget, cals: List<Cal>, visible: Set<Long>, defaultCal: Long, zone: ZoneId, onClose: () -> Unit, onDefault: (Long) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val writable = cals.filter { it.writable && (it.id in visible || it.id == target.ev?.calId || it.id == target.copyOf?.calId) }.ifEmpty { cals.filter { it.writable } }
    val ev = target.ev
    var full by remember { mutableStateOf<EvFull?>(null) }
    val src = ev ?: target.copyOf
    var loaded by remember { mutableStateOf(src == null) }
    val firstCal = writable.firstOrNull { it.id == defaultCal } ?: writable.firstOrNull { it.account.contains("@") } ?: writable.firstOrNull()
    var title by remember { mutableStateOf(target.pre.orEmpty()) }
    var calId by remember { mutableStateOf(firstCal?.id ?: -1L) }
    val now = LocalTime.now()
    val dStart = remember { DS.defStart(ctx) }
    val dDur = remember { DS.defDur(ctx) }
    val st0 = target.time ?: if (dStart >= 0) LocalTime.of(dStart / 60, dStart % 60) else LocalTime.of(minOf(now.hour + 1, 23), 0)
    val end0 = remember { target.date.atTime(st0).plusMinutes(dDur.toLong()) }
    var sd by remember { mutableStateOf(target.date) }
    var stT by remember { mutableStateOf(st0) }
    var ed by remember { mutableStateOf(end0.toLocalDate()) }
    var enT by remember { mutableStateOf(end0.toLocalTime()) }
    var allDay by remember { mutableStateOf(false) }
    var loc by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    val isNew = ev == null && target.copyOf == null
    val rems = remember { mutableStateListOf<Int>().also { l -> if (isNew) DS.defRem(ctx).let { if (it != -2) l.add(it) } else l.add(10) } }
    var avail by remember { mutableStateOf(if (isNew) DS.defAvail(ctx) else 0) }
    var access by remember { mutableStateOf(if (isNew) DS.defAccess(ctx) else 0) }
    val guests = remember { mutableStateListOf<String>() }
    var guestIn by remember { mutableStateOf("") }
    var rep by remember { mutableStateOf("") }
    var repDlg by remember { mutableStateOf(false) }
    var remCustom by remember { mutableStateOf<Int?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    var scope0 by remember { mutableStateOf(1) }
    val atts = remember { mutableStateListOf<EventAtt.Item>() }
    val docLinks = remember { mutableStateListOf<DocLink>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us ->
        us.forEach { u -> atts.add(EventAtt.Item(null, u, EventAtt.nameOf(ctx, u), false)) }
    }
    var askScope by remember { mutableStateOf(ev?.repeat == true) }
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
            atts.clear(); EventAtt.list(ctx, src.id).forEach { atts.add(EventAtt.Item(it.file, null, it.name, it.del)) }
            docLinks.clear(); docLinks.addAll(DocLinks.forEvent(ctx, src.id))
            avail = f?.availability ?: 0; access = f?.access ?: 0
            guests.clear(); guests.addAll(f?.guests.orEmpty())
            rep = if (ev == null) "" else f?.rrule.orEmpty()
            loaded = true
        }
    }
    BackHandler { onClose() }
    val canWrite = cals.firstOrNull { it.id == calId }?.writable != false
    fun save() {
        if (calId < 0) { android.widget.Toast.makeText(ctx, "אין יומן לכתיבה", android.widget.Toast.LENGTH_SHORT).show(); return }
        val s = EdState(title.trim(), calId, sd, stT, if (ed.isBefore(sd)) sd else ed, enT, allDay, loc, desc, rems.toList(), rep, avail, access, guests.toList())
        scope.launch {
            val id = withContext(Dispatchers.IO) { CalRepo.save(ctx, full, ev?.begin ?: 0L, s, scope0) }
            if (id >= 0) withContext(Dispatchers.IO) {
                val endZ = if (allDay) ed.plusDays(1).atStartOfDay(zone) else ed.atTime(enT).atZone(zone)
                runCatching { EventAtt.commit(ctx, id, atts.toList(), endZ.toInstant().toEpochMilli()) }
                runCatching { DocLinks.setEvent(ctx, id, docLinks.toList()) }
            }
            if (id < 0) android.widget.Toast.makeText(ctx, "השמירה נכשלה: " + CalRepo.lastError.take(160), android.widget.Toast.LENGTH_LONG).show() else onChanged()
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
                    Text("כל היום", Modifier.weight(1f), fontSize = 16.sp); Switch(allDay, { v ->
                        if (isNew) {
                            val from = if (allDay) DS.defRemAll(ctx) else DS.defRem(ctx)
                            val to = if (v) DS.defRemAll(ctx) else DS.defRem(ctx)
                            val untouched = rems.toList() == (if (from == -2) emptyList() else listOf(from))
                            if (untouched) { rems.clear(); if (to != -2) rems.add(to) }
                        }
                        allDay = v
                    })
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
                Text("🔔 תזכורות", fontWeight = FontWeight.Bold, color = INK)
                rems.toList().forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Choice("התראה", if (allDay && (m < 0 || m % 1440 != 0)) remAbs(m) else remLabel(m) + (if (m > 0) " לפני" else ""), listOf("מותאם אישית…") + REM_OPTS.map { remLabel(it) }) { if (it == 0) remCustom = i else rems[i] = REM_OPTS[it - 1] } }
                        TextButton({ rems.removeAt(i) }) { Text("✕", color = RED) }
                    }
                }
                TextButton({ rems.add(REM_OPTS.firstOrNull { it !in rems } ?: 10) }) { Text("＋ הוסף תזכורת", color = Color(0xFFE91E63)) }
                OutlinedButton({ repDlg = true }, Modifier.fillMaxWidth()) { Text("🔁 " + repText(rep, sd), Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                if (full != null && full!!.rrule.isNotEmpty()) Text("שינוי בפגישה חוזרת חל על כל הסדרה.", fontSize = 12.sp, color = Color.Gray)
                DocLinksField(docLinks)
                Text("📎 קבצים מצורפים (תמונות)", fontWeight = FontWeight.Bold, color = INK)
                atts.toList().forEachIndexed { i, a ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val bmp = remember(a.file, a.uri) { EventAtt.thumb(ctx, a.file, a.uri, 160) }
                        if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(Modifier.size(56.dp).background(Color(0xFFE6E6E6)))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("מחק אחרי האירוע", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                                Switch(a.del, { v -> atts[i] = EventAtt.Item(a.file, a.uri, a.name, v) })
                            }
                        }
                        TextButton({ atts.removeAt(i) }) { Text("✕", color = RED) }
                    }
                }
                TextButton({ picker.launch("image/*") }) { Text("＋ הוסף תמונה", color = Color(0xFFE91E63)) }
                if (atts.isNotEmpty()) Text("התמונות נשמרות באפליקציה בלבד (לא ביומן גוגל). \"מחק אחרי האירוע\" מוחק את התמונה אוטומטית בתום האירוע.", fontSize = 12.sp, color = Color.Gray)
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
                OutlinedTextField(loc, { loc = it }, Modifier.fillMaxWidth(), label = { Text("מיקום") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("תיאור") }, minLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Choice("📌", AVAIL.first { it.first == avail }.second, AVAIL.map { it.second }) { avail = AVAIL[it].first } }
                    Box(Modifier.weight(1f)) { Choice("👁", ACCESS.firstOrNull { it.first == access }?.second ?: "ברירת מחדל", ACCESS.map { it.second }) { access = ACCESS[it].first } }
                }
                Spacer(Modifier.navigationBarsPadding().height(24.dp))
            }
        }
    }
    remCustom?.let { idx ->
        CustomReminderDialog(rems.getOrNull(idx) ?: 10, allDay, if (allDay) 0 else stT.hour * 60 + stT.minute, { remCustom = null }) { m -> if (idx in rems.indices) rems[idx] = m; remCustom = null }
    }
    if (repDlg) RepeatDialog(rep, sd, allDay, { repDlg = false }, { r -> rep = r; repDlg = false }) { cur, set -> pickDate(cur, set) }
    if (askScope) AlertDialog(
        onDismissRequest = { onClose() }, title = { Text("עריכה") },
        text = {
            Column {
                listOf(1 to "רק האירוע הזה", 2 to "האירוע הזה ואירועים עתידיים", 0 to "כל האירועים").forEach { (v, l) ->
                    Row(Modifier.fillMaxWidth().clickable { scope0 = v }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(scope0 == v, { scope0 = v }); Spacer(Modifier.width(6.dp)); Text(l, fontSize = 17.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton({ askScope = false }) { Text("אישור") } },
        dismissButton = { TextButton({ onClose() }) { Text("ביטול") } }
    )
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
    if (confirmDel && ev != null) DeleteDialog(ev.copy(repeat = ev.repeat || !full?.rrule.isNullOrEmpty()), { confirmDel = false }) { confirmDel = false; onChanged() }
}

@Composable
private fun Choice(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: $value", Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
        DropdownMenu(open, { open = false }) { options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, { open = false; onPick(i) }) } }
    }
}


private val RCODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
private val RSHORT = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
private val RWEEKDAYS = "SU,MO,TU,WE,TH"

private fun ruleMap(r: String) = r.split(";").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }

private fun repText(rule: String, start: LocalDate): String {
    if (rule.isBlank()) return "לא חוזר"
    val m = ruleMap(rule)
    val n = (m["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val days = m["BYDAY"]?.split(",").orEmpty()
    var t = when (m["FREQ"]) {
        "DAILY" -> if (n == 1) "כל יום" else "כל $n ימים"
        "WEEKLY" -> when {
            n == 1 && days.joinToString(",") == RWEEKDAYS -> "כל יום חול (א׳–ה׳)"
            else -> (if (n == 1) "כל שבוע" else "כל $n שבועות") + if (days.isNotEmpty()) " ב" + days.joinToString(", ") { c -> RSHORT.getOrElse(RCODES.indexOf(c.takeLast(2))) { c } } else ""
        }
        "MONTHLY" -> (if (n == 1) "כל חודש" else "כל $n חודשים") + when {
            m["BYMONTHDAY"] != null -> " ביום ${m["BYMONTHDAY"]}"
            days.isNotEmpty() -> { val d = days[0]; val k = d.dropLast(2); " ב" + DAY_FULL.getOrElse(RCODES.indexOf(d.takeLast(2))) { "" } + if (k == "-1") " האחרון" else " ה-$k" }
            else -> ""
        }
        "YEARLY" -> if (n == 1) "כל שנה" else "כל $n שנים"
        else -> "חוזר"
    }
    m["COUNT"]?.let { t += ", $it פעמים" }
    m["UNTIL"]?.takeIf { it.length >= 8 }?.let { t += ", עד ${it.substring(6, 8)}/${it.substring(4, 6)}/${it.substring(0, 4)}" }
    return t
}

@Composable
private fun RepeatDialog(cur: String, start: LocalDate, allDay: Boolean, onClose: () -> Unit, onPick: (String) -> Unit, pickDate: (LocalDate, (LocalDate) -> Unit) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val dow = RCODES[dayIdx(start)]
    val nth = (start.dayOfMonth - 1) / 7 + 1
    val presets = listOf(
        "" to "לא חוזר",
        "FREQ=DAILY" to "כל יום",
        "FREQ=WEEKLY;BYDAY=$dow" to "כל שבוע ביום ${DAY_FULL[dayIdx(start)].removePrefix("יום ")}",
        "FREQ=MONTHLY;BYMONTHDAY=${start.dayOfMonth}" to "כל חודש ביום ${start.dayOfMonth}",
        "FREQ=MONTHLY;BYDAY=$nth$dow" to "כל חודש ביום ${DAY_FULL[dayIdx(start)].removePrefix("יום ")} ה-$nth",
        "FREQ=YEARLY" to "כל שנה ב-${start.dayOfMonth} ב${HE_MONTHS[start.monthValue - 1]}",
        "FREQ=WEEKLY;BYDAY=$RWEEKDAYS" to "כל יום חול (א׳–ה׳)"
    )
    if (!custom) AlertDialog(
        onDismissRequest = onClose, title = { Text("חזרה") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                presets.forEach { (r, l) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(cur == r, { onPick(r) }); Spacer(Modifier.width(6.dp)); Text(l, fontSize = 17.sp)
                    }
                }
                val isCustom = cur.isNotBlank() && presets.none { it.first == cur }
                Row(Modifier.fillMaxWidth().clickable { custom = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(isCustom, { custom = true }); Spacer(Modifier.width(6.dp))
                    Text(if (isCustom) "מותאם אישית: " + repText(cur, start) else "מותאם אישית…", fontSize = 17.sp)
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClose) { Text("ביטול") } }
    ) else {
        val m = remember { ruleMap(cur) }
        var every by remember { mutableStateOf((m["INTERVAL"] ?: "1")) }
        var freq by remember { mutableStateOf(m["FREQ"] ?: "WEEKLY") }
        val days = remember { mutableStateListOf<Int>().also { l -> val b = m["BYDAY"]?.split(",").orEmpty().map { RCODES.indexOf(it.takeLast(2)) }.filter { it >= 0 }; l.addAll(b.ifEmpty { listOf(dayIdx(start)) }) } }
        var monthNth by remember { mutableStateOf(m["BYDAY"] != null && m["FREQ"] == "MONTHLY") }
        var endMode by remember { mutableStateOf(if (m["COUNT"] != null) 2 else if (m["UNTIL"] != null) 1 else 0) }
        var count by remember { mutableStateOf(m["COUNT"] ?: "10") }
        var until by remember { mutableStateOf(m["UNTIL"]?.takeIf { it.length >= 8 }?.let { runCatching { LocalDate.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt()) }.getOrNull() } ?: start.plusMonths(3)) }
        AlertDialog(
            onDismissRequest = { custom = false }, title = { Text("חזרה מותאמת") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("כל", fontSize = 16.sp)
                        OutlinedTextField(every, { v -> every = v.filter { it.isDigit() }.take(3) }, Modifier.width(80.dp), singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("DAILY" to "יום", "WEEKLY" to "שבוע", "MONTHLY" to "חודש", "YEARLY" to "שנה").forEach { (f, l) ->
                            Text(l, Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (freq == f) Color(0xFFE91E63) else Color(0xFFE6E6E6)).clickable { freq = f }.padding(vertical = 8.dp),
                                color = if (freq == f) Color.White else INK, textAlign = TextAlign.Center, fontSize = 15.sp)
                        }
                    }
                    if (freq == "WEEKLY") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RSHORT.forEachIndexed { i, l ->
                            val on = i in days
                            Text(l, Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) Color(0xFFE91E63) else Color(0xFFE6E6E6))
                                .clickable { if (on) { if (days.size > 1) days.remove(i) } else days.add(i) }.padding(vertical = 8.dp),
                                color = if (on) Color.White else INK, textAlign = TextAlign.Center, fontSize = 14.sp)
                        }
                    }
                    if (freq == "MONTHLY") {
                        Row(Modifier.fillMaxWidth().clickable { monthNth = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(!monthNth, { monthNth = false }); Text("ביום ${start.dayOfMonth} בחודש") }
                        Row(Modifier.fillMaxWidth().clickable { monthNth = true }, verticalAlignment = Alignment.CenterVertically) { RadioButton(monthNth, { monthNth = true }); Text("ביום ${DAY_FULL[dayIdx(start)].removePrefix("יום ")} ה-$nth") }
                    }
                    Text("סיום", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().clickable { endMode = 0 }, verticalAlignment = Alignment.CenterVertically) { RadioButton(endMode == 0, { endMode = 0 }); Text("אף פעם") }
                    Row(Modifier.fillMaxWidth().clickable { endMode = 1 }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(endMode == 1, { endMode = 1 }); Text("בתאריך ")
                        OutlinedButton({ endMode = 1; pickDate(until) { until = it } }) { Text("${until.dayOfMonth}/${until.monthValue}/${until.year}") }
                    }
                    Row(Modifier.fillMaxWidth().clickable { endMode = 2 }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(endMode == 2, { endMode = 2 }); Text("אחרי ")
                        OutlinedTextField(count, { v -> count = v.filter { it.isDigit() }.take(3); endMode = 2 }, Modifier.width(80.dp), singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                        Text(" פעמים")
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val n = every.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val sb = StringBuilder("FREQ=$freq")
                    if (n > 1) sb.append(";INTERVAL=$n")
                    if (freq == "WEEKLY") sb.append(";BYDAY=" + days.sorted().joinToString(",") { RCODES[it] })
                    if (freq == "MONTHLY") sb.append(if (monthNth) ";BYDAY=$nth$dow" else ";BYMONTHDAY=${start.dayOfMonth}")
                    when (endMode) {
                        1 -> sb.append(";UNTIL=" + if (allDay) until.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                            else until.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")))
                        2 -> sb.append(";COUNT=${(count.toIntOrNull() ?: 1).coerceAtLeast(1)}")
                    }
                    onPick(sb.toString())
                }) { Text("אישור") }
            },
            dismissButton = { TextButton({ custom = false }) { Text("חזרה") } }
        )
    }
}

private fun remAbs(m: Int): String {
    val d = if (m > 0) (m + 1439) / 1440 else 0
    val t = d * 1440 - m
    return (if (d == 0) "באותו יום" else if (d == 1) "יום לפני" else "$d ימים לפני") + " ב-%02d:%02d".format(t / 60, t % 60)
}

@Composable
private fun CustomReminderDialog(initial: Int, allDay: Boolean, startMin: Int, onClose: () -> Unit, onOk: (Int) -> Unit) {
    val ctx = LocalContext.current
    val units = listOf(1 to "דקות", 60 to "שעות", 1440 to "ימים", 10080 to "שבועות")
    val u0 = units.lastOrNull { initial > 0 && initial % it.first == 0 } ?: units[0]
    var mode by remember { mutableStateOf(if (allDay || initial < 0) 1 else 0) }
    var unit by remember { mutableStateOf(u0.first) }
    var num by remember { mutableStateOf((maxOf(initial, 0) / u0.first).toString()) }
    val d0 = if (initial > 0) (initial + 1439) / 1440 else 0
    var days by remember { mutableStateOf(if (allDay || initial < 0) d0.toString() else "1") }
    var tmin by remember { mutableStateOf(if (allDay || initial < 0) d0 * 1440 - initial else 9 * 60) }
    fun chip(sel: Boolean) = Modifier.clip(RoundedCornerShape(18.dp)).background(if (sel) Color(0xFFE91E63) else Color(0xFFE6E6E6))
    AlertDialog(
        onDismissRequest = onClose, title = { Text("התראה מותאמת אישית") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "זמן לפני האירוע", 1 to "ימים לפני + שעה").forEach { (v, l) ->
                        Text(l, chip(mode == v).weight(1f).clickable { mode = v }.padding(vertical = 8.dp), color = if (mode == v) Color.White else INK, textAlign = TextAlign.Center, fontSize = 14.sp)
                    }
                }
                if (mode == 0) {
                    OutlinedTextField(num, { v -> num = v.filter { it.isDigit() }.take(4) }, Modifier.width(110.dp), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        units.forEach { (f, l) ->
                            Text(l, chip(unit == f).weight(1f).clickable { unit = f }.padding(vertical = 8.dp), color = if (unit == f) Color.White else INK, textAlign = TextAlign.Center, fontSize = 14.sp)
                        }
                    }
                    Text("לפני תחילת האירוע", fontSize = 14.sp, color = Color.Gray)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(days, { v -> days = v.filter { it.isDigit() }.take(3) }, Modifier.width(90.dp), singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                        Text("ימים לפני (0 = באותו יום)", fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("בשעה", fontSize = 16.sp)
                        OutlinedButton({ android.app.TimePickerDialog(ctx, { _, h, m -> tmin = h * 60 + m }, tmin / 60, tmin % 60, true).show() }) { Text("%02d:%02d".format(tmin / 60, tmin % 60)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val r = if (mode == 0) (num.toIntOrNull() ?: 0) * unit else (days.toIntOrNull() ?: 0) * 1440 + startMin - tmin
                onOk(if (allDay) r.coerceIn(-1439, 40320) else r.coerceIn(0, 40320))
            }) { Text("אישור") }
        },
        dismissButton = { TextButton(onClose) { Text("ביטול") } }
    )
}


@Composable
private fun DeleteDialog(e: Ev, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sc by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onClose, title = { Text(if (e.repeat) "מחיקה" else "למחוק את האירוע?") },
        text = {
            if (e.repeat) Column {
                listOf(1 to "רק האירוע הזה", 2 to "האירוע הזה ואירועים עתידיים", 0 to "כל האירועים").forEach { (v, l) ->
                    Row(Modifier.fillMaxWidth().clickable { sc = v }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sc == v, { sc = v }); Spacer(Modifier.width(6.dp)); Text(l, fontSize = 17.sp)
                    }
                }
            } else Text("האירוע יימחק גם מיומן גוגל.")
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    withContext(Dispatchers.IO) {
                        CalRepo.delete(ctx, e.id, e.begin, if (e.repeat) sc else 0)
                        if (!e.repeat || sc == 0) EventAtt.removeAll(ctx, e.id)
                    }
                    onDone()
                }
            }) { Text("אישור", color = RED) }
        },
        dismissButton = { TextButton(onClose) { Text("ביטול") } }
    )
}

@Composable
private fun SwatchRow(options: List<Int>, sel: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { c ->
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(if (c == 0) Color(0xFFE0E0E0) else Color(c))
                    .border(if (c == sel) 3.dp else 1.dp, if (c == sel) Color.Black else Color(0xFFBDBDBD), RoundedCornerShape(17.dp)).clickable { onPick(c) },
                contentAlignment = Alignment.Center
            ) { if (c == 0) Text("A", fontSize = 13.sp, color = Color.DarkGray) }
        }
    }
}

@Composable
private fun FontSettings(tab: Boolean = false, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val fams = listOf("default" to "ברירת מחדל", "sans" to "Sans", "serif" to "Serif", "mono" to "Mono", "cursive" to "כתב יד")
    val textCols = listOf(0, 0xFF222222.toInt(), 0xFF000000.toInt(), 0xFF0D47A1.toInt(), 0xFFB71C1C.toInt(), 0xFF1B5E20.toInt(), 0xFF4A148C.toInt(), 0xFF6D4C41.toInt())
    val barCols = listOf(0xFF4A7391.toInt(), 0xFF1E3A5F.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(), 0xFFC62828.toInt(), 0xFF37474F.toInt(), 0xFFE91E63.toInt(), 0xFF00796B.toInt())
    val todayCols = listOf(0xFFFB8C00.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFE91E63.toInt())
    val cur = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(cur.density, cur.fontScale / DS.scale)) {
    DlgOrTab(tab, 
        onDismissRequest = { DS.save(ctx); onDismiss() }, title = { Text("תצוגה: פונט וצבעים") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("גודל גופן", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    Button({ DS.scale = (Math.round((DS.scale - 0.1f) * 10f) / 10f).coerceAtLeast(0.5f) }, enabled = DS.scale > 0.51f) { Text("−", fontSize = 22.sp) }
                    Text("${Math.round(DS.scale * 100)}%", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Button({ DS.scale = (Math.round((DS.scale + 0.1f) * 10f) / 10f).coerceAtMost(2f) }, enabled = DS.scale < 1.99f) { Text("+", fontSize = 22.sp) }
                }
                Text("סוג גופן", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fams.forEach { (k, l) ->
                        val on = DS.family == k
                        Text(l, Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Color(0xFFE91E63) else Color(0xFFE6E6E6)).clickable { DS.family = k }.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = if (on) Color.White else INK, fontSize = 15.sp,
                            fontFamily = when (k) { "sans" -> FontFamily.SansSerif; "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive; else -> null })
                    }
                }
                Text("צבע טקסט האירועים (A = צבע היומן)", fontWeight = FontWeight.Bold)
                SwatchRow(textCols, DS.evText) { DS.evText = it }
                Text("צבע הסרגל העליון", fontWeight = FontWeight.Bold)
                SwatchRow(barCols, DS.bar) { DS.bar = it }
                Text("צבע היום הנוכחי", fontWeight = FontWeight.Bold)
                SwatchRow(todayCols, DS.today) { DS.today = it }
                TextButton({ DS.scale = 1f; DS.family = "default"; DS.evText = 0; DS.bar = barCols[0]; DS.today = todayCols[0] }) { Text("איפוס לברירת מחדל") }
            }
        },
        confirmButton = { TextButton({ DS.save(ctx); onDismiss() }) { Text("סגירה") } }
    )
    }
}

@Composable
private fun EventDefaults(tab: Boolean = false, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var start by remember { mutableStateOf(DS.defStart(ctx)) }
    var dur by remember { mutableStateOf(DS.defDur(ctx)) }
    var rem by remember { mutableStateOf(DS.defRem(ctx)) }
    var remAll by remember { mutableStateOf(DS.defRemAll(ctx)) }
    var avail by remember { mutableStateOf(DS.defAvail(ctx)) }
    var access by remember { mutableStateOf(DS.defAccess(ctx)) }
    val durs = listOf(15, 30, 45, 60, 90, 120, 180)
    val allOpts = listOf(-540, 900, 2340, 9540)
    DlgOrTab(tab, 
        onDismissRequest = onDismiss, title = { Text("ברירות מחדל לאירוע חדש") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("(היומן ברירת המחדל נבחר ב\"הגדרות יומנים\")", fontSize = 12.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        val m = if (start >= 0) start else 10 * 60
                        android.app.TimePickerDialog(ctx, { _, h, mi -> start = h * 60 + mi; DS.setInt(ctx, "evDefStart", start) }, m / 60, m % 60, true).show()
                    }, Modifier.weight(1f)) { Text("שעת התחלה: " + if (start < 0) "השעה הקרובה" else "%02d:%02d".format(start / 60, start % 60)) }
                    if (start >= 0) TextButton({ start = -1; DS.setInt(ctx, "evDefStart", -1) }) { Text("אוטומטי") }
                }
                Choice("משך", durText(dur.toLong()), durs.map { durText(it.toLong()) }) { dur = durs[it]; DS.setInt(ctx, "evDefDur", dur) }
                Choice("התראה", if (rem == -2) "ללא" else remLabel(rem) + (if (rem > 0) " לפני" else ""), listOf("ללא") + REM_OPTS.map { remLabel(it) }) {
                    rem = if (it == 0) -2 else REM_OPTS[it - 1]; DS.setInt(ctx, "evDefRem", rem)
                }
                Choice("התראה (כל היום)", if (remAll == -2) "ללא" else remAbs(remAll), listOf("ללא") + allOpts.map { remAbs(it) }) {
                    remAll = if (it == 0) -2 else allOpts[it - 1]; DS.setInt(ctx, "evDefRemAll", remAll)
                }
                Choice("📌", AVAIL.first { it.first == avail }.second, AVAIL.map { it.second }) { avail = AVAIL[it].first; DS.setInt(ctx, "evDefAvail", avail) }
                Choice("👁 פרטיות", ACCESS.firstOrNull { it.first == access }?.second ?: "ברירת מחדל", ACCESS.map { it.second }) { access = ACCESS[it].first; DS.setInt(ctx, "evDefAccess", access) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("סגירה") } }
    )
}


@Composable
private fun DlgOrTab(tab: Boolean, onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable (() -> Unit)? = null) {
    if (tab) text() else AlertDialog(onDismissRequest = onDismissRequest, title = title, text = text, confirmButton = confirmButton, dismissButton = dismissButton)
}

@Composable
private fun DiarySettingsHost(cals: List<Cal>, visible: Set<Long>, defCal: Long, weekCell: String, onWeekCell: (String) -> Unit, onSaveCals: (Set<Long>, Long) -> Unit, onClose: () -> Unit) {
    var tabI by remember { mutableStateOf(0) }
    val tabs = listOf("יומנים", "התראות", "תצוגה", "ברירות מחדל", "משבצת")
    var wc by remember { mutableStateOf(weekCell) }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("הגדרות") },
        text = {
            Column {
                ScrollableTabRow(tabI, edgePadding = 0.dp) {
                    tabs.forEachIndexed { i, t -> Tab(tabI == i, { tabI = i }, text = { Text(t, fontSize = 14.sp, maxLines = 1) }) }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.heightIn(max = 420.dp)) {
                    when (tabI) {
                        0 -> SettingsDialog(cals, visible, defCal, true, onClose, onSaveCals)
                        1 -> NotifSettings(true, onClose)
                        2 -> FontSettings(true, onClose)
                        3 -> EventDefaults(true, onClose)
                        else -> Box(Modifier.verticalScroll(rememberScrollState())) { WeekCellSettings(wc, true, onClose) { wc = it; onWeekCell(it) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("סגירה") } }
    )
}
