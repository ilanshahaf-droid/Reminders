package com.ilan.reminders.wear

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.lang.ref.WeakReference

data class Fire(val id: Long, val title: String, val notes: String, val time: String, val snooze: Int = 10)

class ReminderActivity : ComponentActivity() {
    private val fire = mutableStateOf<Fire?>(null)
    private var acted = false
    private val result = mutableStateOf<Triple<String, String, Boolean>?>(null)
    private var addMode = false
    private val finishLater = Runnable { finish() }
    private val picking = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fire.value = parse(intent)
        current = WeakReference(this)
        // the watch's Back button counts as "snooze"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val f = fire.value
                if (f != null && !acted) act(f, "SNOOZE") else finish()
            }
        })
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val r = result.value
                    if (r != null) ConfirmScreen(
                        r.first, r.second, r.third,
                        onDone = { fire.value?.let { f -> followUp(f, "DONE") } },
                        onAdd = { android.os.Handler(mainLooper).removeCallbacks(finishLater); addMode = true; result.value = null; picking.value = true },
                        onCancel = { fire.value?.let { f -> followUp(f, "CANCEL_SNOOZE") } }
                    )
                    else if (picking.value) PickScreen(addMode, onPick = { m ->
                        fire.value?.let { f -> if (addMode) followUp(f, "SNOOZE_ADD", m) else act(f, "SNOOZE", minutes = m) }
                    })
                    else fire.value?.let { f ->
                        ReminderScreen(f, onDone = { act(f, "DONE") }, onSnooze = { act(f, "SNOOZE") }, onPick = { picking.value = true })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acted = false
        result.value = null
        picking.value = false
        addMode = false
        fire.value = parse(intent)
    }

    // pressing the Home button (leaving the screen without choosing) also counts as "snooze"
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // ignore screen timeouts / wrist-down / just-opened screens: only a real Home press while the screen is on counts
        if (System.currentTimeMillis() - shownAt < 3000) return
        val f = fire.value ?: return
        android.os.Handler(mainLooper).postDelayed({
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!acted && !isFinishing && pm.isInteractive && fire.value === f) act(f, "SNOOZE", leaving = true)
        }, 500)
    }

    private var shownAt = 0L
    override fun onResume() {
        super.onResume()
        shownAt = System.currentTimeMillis()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    fun shows(id: Long) = fire.value?.id == id && !acted

    private fun act(f: Fire, action: String, leaving: Boolean = false, minutes: Int = f.snooze) {
        if (acted) return
        acted = true
        WatchBridge.send(this, "/reminder/action", JSONObject().put("id", f.id).put("action", action).put("minutes", minutes).toString())
        getSystemService(NotificationManager::class.java).cancel(f.id.toInt())
        val title: String
        val sub: String
        if (action == "DONE") { title = "הושלמה"; sub = "" } else {
            val at = java.time.LocalTime.now().plusMinutes(minutes.toLong())
            title = "נדחה ל-$minutes דק׳"
            sub = "יופיע שוב ב-%02d:%02d".format(at.hour, at.minute)
        }
        if (action != "DONE") postSnoozeNote(f, "(במצב נודניק עד ${sub.removePrefix("יופיע שוב ב-")})")
        if (leaving) {
            android.widget.Toast.makeText(applicationContext, if (sub.isEmpty()) title else "$title · $sub", android.widget.Toast.LENGTH_LONG).show()
            finish()
        } else {
            result.value = Triple(title, sub, action != "DONE")
            android.os.Handler(mainLooper).postDelayed(finishLater, if (action != "DONE") 8000L else 1800L)
        }
    }

    /** After a snooze: mark done, cancel the snooze, or add more time. */
    private fun followUp(f: Fire, action: String, minutes: Int = 0) {
        val h = android.os.Handler(mainLooper)
        h.removeCallbacks(finishLater)
        WatchBridge.send(this, "/reminder/action", JSONObject().put("id", f.id).put("action", action).put("minutes", minutes).toString())
        val nm = getSystemService(NotificationManager::class.java)
        when (action) {
            "SNOOZE_ADD" -> {
                postSnoozeNote(f, "(במצב נודניק · נוספו $minutes דק׳)")
                result.value = Triple("נוספו $minutes דק׳", "", false)
            }
            else -> {
                nm.cancel(ReminderListenerService.snoozeNoteId(f.id))
                result.value = Triple(if (action == "DONE") "הושלמה" else "הנודניק בוטל", "", false)
            }
        }
        picking.value = false
        h.postDelayed(finishLater, 1800)
    }

    private fun postSnoozeNote(f: Fire, noteText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = "watch_snooze"
        if (nm.getNotificationChannel(ch) == null) {
            val c = android.app.NotificationChannel(ch, "תזכורות בנודניק", NotificationManager.IMPORTANCE_LOW)
            c.setSound(null, null); c.enableVibration(false)
            nm.createNotificationChannel(c)
        }
        val n = androidx.core.app.NotificationCompat.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF6D4AFF.toInt())
            .setContentTitle(f.title)
            .setContentText(noteText)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        nm.notify(ReminderListenerService.snoozeNoteId(f.id), n)
    }

    companion object {
        var current: WeakReference<ReminderActivity>? = null

        fun parse(i: Intent) = Fire(
            i.getLongExtra("id", 0), i.getStringExtra("title") ?: "",
            i.getStringExtra("notes") ?: "", i.getStringExtra("time") ?: "",
            i.getIntExtra("snooze", 10)
        )
    }
}

@androidx.compose.runtime.Composable
private fun ReminderScreen(f: Fire, onDone: () -> Unit, onSnooze: () -> Unit, onPick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4B3BD6)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(f.time, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
                Image(painterResource(R.drawable.ic_bell_big), null, Modifier.size(30.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    f.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 29.sp
                )
                if (f.notes.isNotBlank()) {
                    Text(
                        f.notes, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                ActionButton("✓", "הושלמה", Color.White.copy(alpha = 0.22f), Color.White, onDone)
                ActionButton("⏱", "בחר זמן", Color.White.copy(alpha = 0.22f), Color.White, onPick, size = 36)
                ActionButton("✕", "נודניק", Color.White, Color(0xFF4B3BD6), onSnooze)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ActionButton(symbol: String, label: String, bg: Color, fg: Color, onClick: () -> Unit, size: Int = 44) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(symbol, color = fg, fontSize = (size / 2).sp, fontWeight = FontWeight.Bold) }
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@androidx.compose.runtime.Composable
private fun ConfirmScreen(
    title: String, sub: String, actions: Boolean,
    onDone: () -> Unit, onAdd: () -> Unit, onCancel: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4B3BD6)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Box(Modifier.size(if (actions) 38.dp else 56.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Text("✓", color = Color(0xFF4B3BD6), fontSize = if (actions) 22.sp else 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(title, color = Color.White, fontSize = if (actions) 18.sp else 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = TextAlign.Center)
            if (actions) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    ActionButton("✓", "הושלמה", Color.White.copy(alpha = 0.22f), Color.White, onDone, size = 38)
                    ActionButton("+", "הוסף", Color.White.copy(alpha = 0.22f), Color.White, onAdd, size = 38)
                    ActionButton("↩", "בטל", Color.White.copy(alpha = 0.22f), Color.White, onCancel, size = 38)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PickScreen(add: Boolean, onPick: (Int) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4B3BD6)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (add) "להוסיף כמה דקות?" else "נודניק לכמה דקות?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            (5..60 step 15).forEach { start ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (start until minOf(start + 15, 65) step 5).forEach { m ->
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)).clickable { onPick(m) },
                            contentAlignment = Alignment.Center
                        ) { Text("$m", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
