package com.ilan.reminders.wear

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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

data class Fire(val id: Long, val title: String, val notes: String, val time: String)

class ReminderActivity : ComponentActivity() {
    private val fire = mutableStateOf<Fire?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fire.value = parse(intent)
        current = WeakReference(this)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    fire.value?.let { f ->
                        ReminderScreen(f, onDone = { act(f, "DONE") }, onSnooze = { act(f, "SNOOZE") })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fire.value = parse(intent)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    fun shows(id: Long) = fire.value?.id == id

    private fun act(f: Fire, action: String) {
        WatchBridge.send(this, "/reminder/action", JSONObject().put("id", f.id).put("action", action).toString())
        getSystemService(NotificationManager::class.java).cancel(f.id.toInt())
        finish()
    }

    companion object {
        var current: WeakReference<ReminderActivity>? = null

        fun parse(i: Intent) = Fire(
            i.getLongExtra("id", 0), i.getStringExtra("title") ?: "",
            i.getStringExtra("notes") ?: "", i.getStringExtra("time") ?: ""
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
