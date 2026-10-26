package com.ilan.reminders

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Small dialog opened from the notification: choose a snooze of 5..60 minutes. */
class SnoozePickActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("id", -1)
        val add = intent.getBooleanExtra("add", false)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(if (add) "להוסיף כמה דקות לנודניק?" else "נודניק לכמה דקות?") },
                        text = {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (5..60 step 5).forEach { m ->
                                    FilterChip(false, {
                                        sendBroadcast(
                                            Intent(this@SnoozePickActivity, ActionReceiver::class.java)
                                                .setAction(if (add) "SNOOZE_ADD" else "SNOOZE").putExtra("id", id).putExtra("minutes", m)
                                        )
                                        finish()
                                    }, label = { Text("$m") })
                                }
                            }
                        },
                        confirmButton = { TextButton({ finish() }) { Text("ביטול") } }
                    )
                }
            }
        }
    }
}
