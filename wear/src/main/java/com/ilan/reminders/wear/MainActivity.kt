package com.ilan.reminders.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskStore.load(this)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { TaskListScreen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ask the phone for a fresh list every time the app opens
        WatchBridge.send(this, "/tasks/request", "{}")
    }
}

@Composable
private fun TaskListScreen() {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val tasks = TaskStore.tasks.value
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 38.dp, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("תזכורות v24", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (tasks.isEmpty()) "אין תזכורות פעילות" else "${tasks.size} תזכורות פעילות",
                        color = Color.Gray, fontSize = 13.sp
                    )
                }
            }
            items(tasks, key = { it.id }) { t ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF1E1E26)).padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        t.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        t.whenText, fontSize = 14.sp,
                        color = if (t.overdue) Color(0xFFFF6B6B) else Color(0xFFB8BBC9)
                    )
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
}
