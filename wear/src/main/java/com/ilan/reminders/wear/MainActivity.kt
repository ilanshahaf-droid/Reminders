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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val ctx = LocalContext.current
                    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("תזכורות", color = Color.White, fontSize = 22.sp)
                            Text("ממתין לתזכורות מהטלפון", color = Color.Gray, fontSize = 12.sp)
                            Button(onClick = {
                                ctx.startActivity(
                                    Intent(ctx, ReminderActivity::class.java)
                                        .putExtra("id", 999L).putExtra("title", "בדיקה")
                                        .putExtra("notes", "").putExtra("time", "08:00")
                                )
                            }) { Text("בדיקת מסך") }
                        }
                    }
                }
            }
        }
    }
}
