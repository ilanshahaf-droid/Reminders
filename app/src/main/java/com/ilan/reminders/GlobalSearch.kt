package com.ilan.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One search box over reminders, notes and documents. */
object GlobalSearch { var open by mutableStateOf(false) }

private data class Hit(val kind: String, val title: String, val sub: String, val open: () -> Unit)

@Composable
fun GlobalSearchScreen(onTask: (Task) -> Unit, onNote: (Note) -> Unit, onDoc: (java.io.File) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val db = remember { AppDb.get(ctx) }
    val tasks by db.dao().all().collectAsState(initial = emptyList())
    val notes by db.notes().all().collectAsState(initial = emptyList())
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    BackHandler { GlobalSearch.open = false }
    val term = q.trim()
    val hits = remember(term, tasks, notes) {
        if (term.length < 1) emptyList() else buildList {
            tasks.filter { it.deletedAt == 0L && (it.title.contains(term, true) || it.notes.contains(term, true)) }.take(30).forEach { t ->
                add(Hit("⏰", t.title, t.notes.lineSequence().firstOrNull().orEmpty()) { onTask(t) })
            }
            notes.filter { it.deletedAt == 0L && (it.title.contains(term, true) || (!it.locked && it.plain.contains(term, true))) }.take(30).forEach { n ->
                val body = if (n.locked) "🔒 נעול" else snippet(n.plain, term)
                add(Hit("📝", n.title.ifBlank { n.plain.lineSequence().firstOrNull().orEmpty().take(40) }, body) { onNote(n) })
            }
            Docs.list(ctx).filter { it.name.contains(term, true) }.take(30).forEach { f ->
                add(Hit(if (Docs.ext(f) == "pdf") "📕" else "📄", f.nameWithoutExtension, f.extension.uppercase()) { onDoc(f) })
            }
        }
    }
    Column(Modifier.fillMaxSize().background(appBackground()).statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it }, singleLine = true, placeholder = { Text("חיפוש בתזכורות, פתקים ומסמכים") },
                modifier = Modifier.weight(1f).focusRequester(fr), shape = RoundedCornerShape(28.dp)
            )
            TextButton({ GlobalSearch.open = false }) { Text("סגור") }
        }
        if (term.isNotEmpty() && hits.isEmpty()) Text("לא נמצא כלום", color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits) { h ->
                Surface(
                    shape = RoundedCornerShape(16.dp), color = cs.surface, shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { GlobalSearch.open = false; h.open() }
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.kind, fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title.ifBlank { "(ללא כותרת)" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.sub.isNotBlank()) Text(h.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun snippet(text: String, term: String): String {
    val i = text.indexOf(term, ignoreCase = true)
    if (i < 0) return text.take(80)
    val s = (i - 25).coerceAtLeast(0)
    return (if (s > 0) "…" else "") + text.substring(s, (i + term.length + 55).coerceAtMost(text.length)).replace("\n", " ")
}
