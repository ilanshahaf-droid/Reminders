package com.ilan.reminders

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.lang.ref.WeakReference

/** Full-screen alert: wakes the phone, shows over the lock screen and stays until a button is pressed. */
class AlertActivity : ComponentActivity() {
    private var taskId = -1L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = WeakReference(this)
        taskId = intent.getLongExtra("id", -1)
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val minutes = intent.getIntExtra("minutes", 10)
        fun act(a: String, m: Int = 0) {
            sendBroadcast(Intent(this, ActionReceiver::class.java).setAction(a).putExtra("id", taskId).putExtra("minutes", m))
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        Modifier.fillMaxSize().background(Color(0xFF12121A)).statusBarsPadding().navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Text("🔔", style = MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.height(16.dp))
                        Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = Color(0xFFB8B8C8))
                        Spacer(Modifier.height(40.dp))
                        Button({ act("DONE") }, Modifier.fillMaxWidth().height(64.dp)) {
                            Text("✓  הושלמה", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton({ act("SNOOZE", minutes) }, Modifier.fillMaxWidth().height(64.dp)) {
                            Text("נודניק $minutes דק׳", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton({
                            startActivity(Intent(this@AlertActivity, SnoozePickActivity::class.java).putExtra("id", taskId))
                        }, Modifier.fillMaxWidth().height(56.dp)) { Text("בחר זמן נודניק", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<AlertActivity>? = null
        fun finishFor(id: Long) {
            current?.get()?.let { a -> if (a.taskId == id) a.runOnUiThread { a.finish() } }
        }
    }
}
