package com.ilan.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SyncDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { Sync.prefs(ctx) }
    var url by remember { mutableStateOf(p.getString("url", "") ?: "") }
    var key by remember { mutableStateOf(p.getString("key", "") ?: "") }
    var email by remember { mutableStateOf(p.getString("email", "") ?: "") }
    var pass by remember { mutableStateOf(p.getString("pass", "") ?: "") }
    var status by remember { mutableStateOf(p.getString("status", "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("סנכרון לענן (Supabase)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Project URL") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("anon / publishable key") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("אימייל") }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("סיסמה") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                Sync.save(ctx, url, key, email, pass)
                busy = true
                scope.launch { status = Sync.syncNow(ctx); busy = false }
            }) { Text(if (busy) "מסנכרן…" else "שמור וסנכרן") }
        },
        dismissButton = { TextButton(onDismiss) { Text("סגור") } }
    )
}
