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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One search box over reminders, notes and documents. */
object GlobalSearch { var open by mutableStateOf(false) }

private data class Hit(val kind: String, val title: String, val sub: String, val open: () -> Unit)

@Composable
fun GlobalSearchScreen(onTask: (Task) -> Unit, onNote: (Note) -> Unit, onDoc: (java.io.File) -> Unit, onEvent: (Ev) -> Unit = {}) {
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
    // calendar events (a year back, two years ahead) and the text inside spreadsheets / Word files are searched off the main thread
    var evAll by remember { mutableStateOf<List<Ev>>(emptyList()) }
    LaunchedEffect(Unit) {
        evAll = withContext(Dispatchers.IO) {
            if (!CalRepo.hasRead(ctx)) emptyList() else runCatching {
                val now = System.currentTimeMillis()
                CalRepo.events(ctx, now - 365L * 86400000, now + 2 * 365L * 86400000, CalRepo.calendars(ctx).map { it.id }.toSet())
            }.getOrDefault(emptyList())
        }
        Transcripts.load(ctx)
    }
    var extra by remember { mutableStateOf<List<Hit>>(emptyList()) }
    LaunchedEffect(term, evAll) {
        if (term.isEmpty()) { extra = emptyList(); return@LaunchedEffect }
        delay(250)
        extra = withContext(Dispatchers.IO) {
            val zone = java.time.ZoneId.systemDefault()
            val df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val now = System.currentTimeMillis()
            val m = evAll.filter { it.title.contains(term, true) || it.location.contains(term, true) }.distinctBy { it.id to it.begin }
            val evs = (m.filter { it.end >= now }.sortedBy { it.begin } + m.filter { it.end < now }.sortedByDescending { it.begin }).take(30).map { e ->
                val d = java.time.Instant.ofEpochMilli(e.begin).atZone(zone)
                Hit("📅", e.title, df.format(d) + (if (e.allDay) "" else "  %02d:%02d".format(d.hour, d.minute)) + (if (e.location.isNotBlank()) " · " + e.location else "")) { onEvent(e) }
            }
            val trs = Transcripts.items.filter { it.status == "done" && it.text.contains(term, true) }.take(15).map { t ->
                Hit("🎧", Transcripts.shortTitle(t.text), snippet(t.text, term)) { ctx.startActivity(android.content.Intent(ctx, TranscribeActivity::class.java)) }
            }
            val docs = Docs.list(ctx).filter { Docs.ext(it) == "xlsx" || Docs.ext(it) == "docx" }.mapNotNull { f ->
                if (f.name.contains(term, true)) return@mapNotNull null
                val body = DocText.of(f) ?: return@mapNotNull null
                if (!body.contains(term, true)) null
                else Hit(if (Docs.ext(f) == "xlsx") "📗" else "📄", f.nameWithoutExtension, "🔎 " + snippet(body, term)) { onDoc(f) }
            }.take(20)
            evs + trs + docs
        }
    }
    val base = remember(term, tasks, notes) {
        if (term.length < 1) emptyList() else buildList {
            tasks.filter { it.deletedAt == 0L && (it.title.contains(term, true) || it.notes.contains(term, true)) }.take(30).forEach { t ->
                add(Hit("⏰", t.title, t.notes.lineSequence().firstOrNull().orEmpty()) { onTask(t) })
            }
            notes.filter { it.deletedAt == 0L && (it.title.contains(term, true) || (!it.locked && it.plain.contains(term, true))) }.take(30).forEach { n ->
                val body = if (n.locked) "🔒 נעול" else snippet(n.plain, term)
                add(Hit("📝", n.title.ifBlank { n.plain.lineSequence().firstOrNull().orEmpty().take(40) }, body) { onNote(n) })
            }
            Docs.list(ctx).forEach { f ->
                val byName = f.name.contains(term, true)
                val ocr = if (byName) null else Ocr.text(ctx, f)?.takeIf { it.contains(term, true) }
                if (byName || ocr != null)
                    add(Hit(when (Docs.ext(f)) { "pdf" -> "📕"; "xlsx" -> "📗"; "pptx" -> "📙"; else -> "📄" }, f.nameWithoutExtension, if (ocr != null) "🔤 " + snippet(ocr, term) else f.extension.uppercase()) { onDoc(f) })
            }
        }
    }
    val hits = base + extra
    Column(Modifier.fillMaxSize().background(appBackground()).statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it }, singleLine = true, placeholder = { Text("חיפוש באירועים, תזכורות, פתקים, מסמכים ותמלולים") },
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

/** Plain text of a spreadsheet / Word file (cached until the file changes), for searching inside documents. */
object DocText {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun of(f: java.io.File): String? = runCatching {
        val key = f.path + ":" + f.lastModified()
        cache[key] ?: run {
            val entries = if (f.extension.equals("xlsx", true)) listOf("xl/sharedStrings.xml") else listOf("word/document.xml")
            val sb = StringBuilder()
            java.util.zip.ZipFile(f).use { z ->
                entries.forEach { n -> z.getEntry(n)?.let { e -> sb.append(z.getInputStream(e).bufferedReader().readText()) } }
            }
            val t = sb.toString().replace(Regex("</(w:p|si)>"), "\n").replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            cache[key] = t; t
        }
    }.getOrNull()
}
