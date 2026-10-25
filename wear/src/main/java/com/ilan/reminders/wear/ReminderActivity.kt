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
    private val result = mutableStateOf<Pair<String, String>?>(null)

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
                if (f != null) act(f, "SNOOZE") else finish()
            }
        })
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val r = result.value
                    if (r != null) ConfirmScreen(r.first, r.second)
                    else fire.value?.let { f ->
                        ReminderScreen(f, onDone = { act(f, "DONE") }, onSnooze = { act(f, "SNOOZE") })
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
        fire.value = parse(intent)
    }

    // pressing the Home button (leaving the screen without choosing) also counts as "snooze"
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val f = fire.value
        if (f != null && !acted) act(f, "SNOOZE", leaving = true)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    fun shows(id: Long) = fire.value?.id == id

    private fun act(f: Fire, action: String, leaving: Boolean = false) {
        if (acted) return
        acted = true
        WatchBridge.send(this, "/reminder/action", JSONObject().put("id", f.id).put("action", action).toString())
        getSystemService(NotificationManager::class.java).cancel(f.id.toInt())
        val title: String
        val sub: String
        if (action == "DONE") { title = "הושלמה"; sub = "" } else {
            val at = java.time.LocalTime.now().plusMinutes(f.snooze.toLong())
            title = "נדחה ל-${f.snooze} דק׳"
            sub = "יופיע שוב ב-%02d:%02d".format(at.hour, at.minute)
        }
        if (action != "DONE") postSnoozeNote(f, sub)
        if (leaving) {
            android.widget.Toast.makeText(applicationContext, if (sub.isEmpty()) title else "$title · $sub", android.widget.Toast.LENGTH_LONG).show()
            finish()
        } else {
            result.value = title to sub
            android.os.Handler(mainLooper).postDelayed({ finish() }, 1800)
        }
    }

    private fun postSnoozeNote(f: Fire, sub: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = "watch_snooze"
        if (nm.getNotificationChannel(ch) == null) {
            val c = android.app.NotificationChannel(ch, "תזכורות בנודניק", NotificationManager.IMPORTANCE_LOW)
            c.setSound(null, null); c.enableVibration(false)
            nm.createNotificationChannel(c)
        }
        val at = sub.removePrefix("יופיע שוב ב-")
        val n = androidx.core.app.NotificationCompat.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF6D4AFF.toInt())
            .setContentTitle(f.title)
            .setContentText("(במצב נודניק עד $at)")
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
private fun ReminderScreen(f: Fire, onDone: () -> Unit, onSnooze: () -> Unit) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                ActionButton("✓", "הושלמה", Color.White.copy(alpha = 0.22f), Color.White, onDone)
                ActionButton("✕", "נודניק", Color.White, Color(0xFF4B3BD6), onSnooze)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ActionButton(symbol: String, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(symbol, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@androidx.compose.runtime.Composable
private fun ConfirmScreen(title: String, sub: String) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4B3BD6)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Text("✓", color = Color(0xFF4B3BD6), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}
