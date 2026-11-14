package com.ilan.reminders

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Ready-made paragraphs the user can insert into any note or Word document with one tap. */
object Snippets {
    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun list(c: Context): List<Pair<String, String>> = runCatching {
        val a = JSONArray(prefs(c).getString("snippets", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("t") to o.getString("x") } }
    }.getOrDefault(emptyList())
    private fun save(c: Context, l: List<Pair<String, String>>) {
        prefs(c).edit().putString("snippets", JSONArray().also { a -> l.forEach { a.put(JSONObject().put("t", it.first).put("x", it.second)) } }.toString()).apply()
    }
    fun add(c: Context, title: String, text: String) = save(c, list(c) + (title to text))
    fun remove(c: Context, i: Int) = save(c, list(c).filterIndexed { k, _ -> k != i })
}

@Composable
fun SnippetsDialog(onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableStateOf(0) }
    val items = remember(tick) { Snippets.list(ctx) }
    var adding by remember { mutableStateOf(false) }
    var t by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (adding) "קטע טקסט חדש" else "קטעי טקסט") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (adding) {
                    OutlinedTextField(t, { t = it }, singleLine = true, label = { Text("שם הקטע") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(x, { x = it }, label = { Text("הטקסט") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                } else {
                    if (items.isEmpty()) Text("עוד אין קטעים. הוסף פסקאות שחוזרות אצלך, ואז אפשר להכניס אותן בלחיצה.", style = MaterialTheme.typography.bodySmall)
                    items.forEachIndexed { i, (title, text) ->
                        Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { onInsert(text); onDismiss() }) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.titleSmall)
                                    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                TextButton({ Snippets.remove(ctx, i); tick++ }) { Text("✕", color = cs.error) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (adding) TextButton({ if (x.isNotBlank()) { Snippets.add(ctx, t.ifBlank { x.take(24) }, x.trim()); t = ""; x = ""; adding = false; tick++ } }) { Text("שמור") }
            else TextButton({ adding = true }) { Text("➕ קטע חדש") }
        },
        dismissButton = { TextButton({ if (adding) adding = false else onDismiss() }) { Text(if (adding) "ביטול" else "סגור") } }
    )
}
