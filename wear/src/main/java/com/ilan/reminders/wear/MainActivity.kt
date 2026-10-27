package com.ilan.reminders.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskStore.load(this)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { WatchRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ask the phone for a fresh list every time the app opens
        WatchBridge.send(this, "/tasks/request", "{}")
    }
}

/** Simple navigation between the watch screens. */
private sealed class Nav {
    object Home : Nav()
    data class Menu(val t: WatchTask) : Nav()
    data class Snooze(val t: WatchTask) : Nav()
    data class SetTime(val t: WatchTask) : Nav()
    data class Choose(val title: String) : Nav()
    data class NewTime(val title: String) : Nav()
}

private fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

private fun sendAction(c: Context, id: Long, action: String, minutes: Int = 0) =
    WatchBridge.send(c, "/reminder/action", JSONObject().put("id", id).put("action", action).put("minutes", minutes).toString())

private fun sendCreate(c: Context, title: String, at: Long) =
    WatchBridge.send(c, "/reminder/create", JSONObject().put("title", title).put("at", at).toString())

private fun atOf(date: LocalDate, h: Int, m: Int) =
    date.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
private fun Pill(text: String, bg: Color = Color(0xFF2A2A36), fg: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun Screen(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 34.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun WatchRoot() {
    val ctx = LocalContext.current
    var nav by remember { mutableStateOf<Nav>(Nav.Home) }
    BackHandler(nav != Nav.Home) { nav = Nav.Home }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val txt = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!txt.isNullOrBlank()) nav = Nav.Choose(txt)
    }
    fun startSpeech() {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "מה להזכיר?")
            )
        } catch (e: Exception) {
            toast(ctx, "אין הכתבה קולית בשעון")
        }
    }

    when (val n = nav) {
        Nav.Home -> TaskListScreen(onOpen = { nav = Nav.Menu(it) }, onNew = { startSpeech() })
        is Nav.Menu -> MenuScreen(n.t, onSnooze = { nav = Nav.Snooze(n.t) }, onSetTime = { nav = Nav.SetTime(n.t) }, onDone = { nav = Nav.Home })
        is Nav.Snooze -> SnoozeScreen(n.t) { nav = Nav.Home }
        is Nav.SetTime -> TimeScreen(n.t.title, n.t.at.takeIf { it > 0 } ?: (System.currentTimeMillis() + 3_600_000)) { at ->
            WatchBridge.send(ctx, "/reminder/settime", JSONObject().put("id", n.t.id).put("at", at).toString())
            toast(ctx, "השעה עודכנה")
            nav = Nav.Home
        }
        is Nav.Choose -> ChooseScreen(n.title, onCustom = { nav = Nav.NewTime(n.title) }, onDone = { nav = Nav.Home })
        is Nav.NewTime -> TimeScreen(n.title, System.currentTimeMillis() + 3_600_000) { at ->
            sendCreate(ctx, n.title, at)
            toast(ctx, "התזכורת נוצרה")
            nav = Nav.Home
        }
    }
}

@Composable
private fun TaskListScreen(onOpen: (WatchTask) -> Unit, onNew: () -> Unit) {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val tasks = TaskStore.tasks.value
    Screen {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("תזכורות v29", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (tasks.isEmpty()) "אין תזכורות פעילות" else "${tasks.size} תזכורות פעילות",
                    color = Color.Gray, fontSize = 13.sp
                )
            }
        }
        item { Pill("＋ תזכורת חדשה (הכתבה)", Color(0xFF6D4AFF)) { onNew() } }
        items(tasks, key = { it.id }) { t ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1E1E26)).clickable { onOpen(t) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    t.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    t.whenText, fontSize = 14.sp,
                    color = if (t.overdue) Color(0xFFFF6B6B) else Color(0xFFB8BBC9)
                )
                if (t.snoozeUntil > System.currentTimeMillis()) {
                    val z = Instant.ofEpochMilli(t.snoozeUntil).atZone(ZoneId.systemDefault())
                    Text(
                        "(במצב נודניק · נדחה ל-%02d:%02d)".format(z.hour, z.minute),
                        color = Color(0xFFFFC857), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (t.rec.isNotBlank()) {
                    Text(
                        "↻ " + t.rec, color = Color(0xFFB3A6FF), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            Button(onClick = {
                ctx.startActivity(
                    Intent(ctx, ReminderActivity::class.java)
                        .putExtra("id", 999L).putExtra("title", "בדיקה")
                        .putExtra("notes", "").putExtra("time", "08:00")
                )
            }) { Text("בדיקת מסך", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun MenuScreen(t: WatchTask, onSnooze: () -> Unit, onSetTime: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Screen {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(t.whenText, color = Color(0xFFB8BBC9), fontSize = 13.sp)
            }
        }
        item { Pill("✓ הושלמה", Color(0xFF6D4AFF)) { sendAction(ctx, t.id, "DONE"); toast(ctx, "התזכורת הושלמה"); onDone() } }
        item { Pill("⏱ נודניק") { onSnooze() } }
        if (t.rec.isBlank()) item { Pill("🕒 שנה שעה") { onSetTime() } }
        item {
            Pill(if (confirm) "לחץ שוב למחיקה" else "🗑 מחק", Color(0xFF5A2A2A), Color(0xFFFF9B9B)) {
                if (!confirm) confirm = true
                else {
                    WatchBridge.send(ctx, "/reminder/delete", JSONObject().put("id", t.id).toString())
                    toast(ctx, "נמחקה")
                    onDone()
                }
            }
        }
        item { Pill("← חזרה", Color(0xFF1E1E26)) { onDone() } }
    }
}

@Composable
private fun SnoozeScreen(t: WatchTask, onDone: () -> Unit) {
    val ctx = LocalContext.current
    Screen {
        item { Text("נודניק לכמה דקות?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        items((5..60 step 5).toList().chunked(3)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0xFF2A2A36))
                            .clickable {
                                sendAction(ctx, t.id, "SNOOZE", m)
                                toast(ctx, "נדחה ל-$m דק׳")
                                onDone()
                            }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("$m", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        item { Pill("← חזרה", Color(0xFF1E1E26)) { onDone() } }
    }
}

/** Day (today / tomorrow) + hour:minute wheels. */
@Composable
private fun TimeScreen(title: String, initial: Long, onOk: (Long) -> Unit) {
    val z = Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault())
    var hour by remember { mutableIntStateOf(z.hour) }
    var minute by remember { mutableIntStateOf(z.minute) }
    var tomorrow by remember { mutableStateOf(z.toLocalDate().isAfter(LocalDate.now())) }
    Screen {
        item { Text(title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) { Pill("היום", if (!tomorrow) Color(0xFF6D4AFF) else Color(0xFF2A2A36)) { tomorrow = false } }
                Box(Modifier.weight(1f)) { Pill("מחר", if (tomorrow) Color(0xFF6D4AFF) else Color(0xFF2A2A36)) { tomorrow = true } }
            }
        }
        item {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Wheel(hour, 0, 23) { hour = it }
                    Text(":", color = Color.White, fontSize = 26.sp)
                    Wheel(minute, 0, 59) { minute = it }
                }
            }
        }
        item {
            Pill("אישור", Color(0xFF6D4AFF)) {
                val day = if (tomorrow) LocalDate.now().plusDays(1) else LocalDate.now()
                onOk(atOf(day, hour, minute))
            }
        }
    }
}

@Composable
private fun Wheel(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    AndroidView(
        modifier = Modifier.width(76.dp).height(112.dp),
        factory = { c ->
            NumberPicker(c).apply {
                wrapSelectorWheel = true
                setFormatter { "%02d".format(it) }
                (getChildAt(0) as? android.widget.EditText)?.filters = arrayOf<android.text.InputFilter>()
            }
        },
        update = { np ->
            np.setOnValueChangedListener(null)
            np.minValue = min; np.maxValue = max
            np.value = value
            if (Build.VERSION.SDK_INT >= 29) {
                np.textColor = Color.White.toArgb()
                np.textSize = 24f * np.resources.displayMetrics.scaledDensity
            }
            np.setOnValueChangedListener { _, _, v -> onChange(v) }
        }
    )
}

/** After dictating a title: when should it ring? */
@Composable
private fun ChooseScreen(title: String, onCustom: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    fun create(at: Long) { sendCreate(ctx, title, at); toast(ctx, "התזכורת נוצרה"); onDone() }
    val now = System.currentTimeMillis() / 60_000 * 60_000
    val today = LocalDate.now()
    Screen {
        item {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        item { Text("מתי להזכיר?", color = Color.Gray, fontSize = 13.sp) }
        item { Pill("בעוד 5 דק׳") { create(now + 5 * 60_000L) } }
        item { Pill("בעוד 15 דק׳") { create(now + 15 * 60_000L) } }
        item { Pill("בעוד שעה") { create(now + 60 * 60_000L) } }
        item { Pill("הערב 20:00") { create(atOf(today, 20, 0).let { if (it < now) atOf(today.plusDays(1), 20, 0) else it }) } }
        item { Pill("מחר 08:00") { create(atOf(today.plusDays(1), 8, 0)) } }
        item { Pill("🕒 בחר שעה", Color(0xFF6D4AFF)) { onCustom() } }
        item { Pill("ביטול", Color(0xFF1E1E26)) { onDone() } }
    }
}
