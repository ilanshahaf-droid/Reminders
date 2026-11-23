package com.ilan.reminders

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "Share" a WhatsApp voice message (or any audio) to סדר יום -> on-device Hebrew transcription. */
class TranscribeActivity : androidx.fragment.app.FragmentActivity() {
    companion object { @Volatile var visible = false }
    private var showId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        take(intent)
        setContent { AppTheme { TranscribeScreen(showId, onShown = { showId = null }, onFinish = { finish() }) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); take(intent) }
    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { super.onPause(); visible = false }

    private fun take(i: Intent?) {
        if (i == null) return
        Transcripts.load(this)
        i.getLongExtra("show", 0L).takeIf { it > 0 }?.let { showId = it; i.removeExtra("show") }
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        i.action = Intent.ACTION_MAIN
        val ref = runCatching { referrer?.host }.getOrNull()
        val source = when (ref) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            null, "", packageName -> ""
            else -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(ref, 0)).toString() }.getOrDefault("")
        }
        addAll(this, uris, source) { first -> if (first != null) showId = first }
    }
}

private fun addAll(c: Context, uris: List<Uri>, source: String, done: (Long?) -> Unit) {
    val app = c.applicationContext
    Thread {
        val added = uris.mapNotNull { Transcripts.add(app, it, source) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (added.isEmpty()) Toast.makeText(app, "אי אפשר לקרוא את קובץ השמע", Toast.LENGTH_SHORT).show()
            Transcripts.kick(app)
            done(added.firstOrNull()?.id)
        }
    }.start()
}

private val HM = DateTimeFormatter.ofPattern("d.M · HH:mm")
private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
private fun mb(b: Long) = "${(b + 500_000) / 1_000_000}MB"

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TranscribeScreen(showId: Long?, onShown: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val items = Transcripts.items
    var open by remember { mutableStateOf<Long?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(showId) { if (showId != null) { open = showId; onShown() } }
    // the newest item opens by itself when nothing is open yet
    LaunchedEffect(items.firstOrNull()?.id) { if (open == null) open = items.firstOrNull()?.id }
    // refresh download progress / finish downloads while the screen is open
    LaunchedEffect(Unit) {
        while (true) {
            WModels.all.forEach { WModels.poll(ctx, it) }
            tick++
            delay(700)
        }
    }
    val ready = remember(tick) { WModels.usable(ctx) }
    // whenever the screen opens or the model becomes ready: run whatever is waiting in the queue
    LaunchedEffect(ready != null) { if (ready != null) Transcripts.kick(ctx) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us ->
        if (us.isNotEmpty()) addAll(ctx, us, "") { if (it != null) open = it }
    }

    Box(Modifier.fillMaxSize().background(appBackground())) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onFinish) { Text("✕", fontSize = 20.sp) }
                Text("🎙️ תמלול הודעות קוליות", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton({ pick.launch("audio/*") }) { Text("📂", fontSize = 20.sp) }
                TextButton({ showModels = true }) { Text("⚙️", fontSize = 20.sp) }
            }
            if (!WhisperNative.available) {
                Card(Modifier.padding(12.dp).fillMaxWidth()) { Text("מנוע התמלול לא נטען במכשיר הזה.", Modifier.padding(16.dp)) }
            } else if (ready == null) {
                ModelCard(tick, compact = false)
            }
            Transcripts.kickError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = cs.error) }
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("אין עדיין תמלולים", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("בוואטסאפ: לחיצה ארוכה על הודעה קולית ← שיתוף ← \"תמלול · סדר יום\".\nאפשר גם לבחור קובץ שמע בכפתור 📂 למעלה.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { x -> x.id }) { item ->
                    ItemCard(item, expanded = open == item.id, modelReady = ready != null,
                        onToggle = { open = if (open == item.id) null else item.id }, onFinish = onFinish)
                }
            }
        }
    }
    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("מודל התמלול", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("המודל של ivrit.ai לעברית רץ בטלפון עצמו: ההקלטות לא נשלחות לשום מקום. ההורדה חד-פעמית ונשמרת גם בעדכוני האפליקציה.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            ModelCard(tick, compact = true)
            Spacer(Modifier.height(10.dp))
            Text("מנוע: " + remember { runCatching { WhisperNative.systemInfo() }.getOrDefault("לא נטען") },
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelCard(tick: Int, compact: Boolean) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var sel by remember { mutableStateOf(WModels.selectedKey(ctx)) }
    var confirmDel by remember { mutableStateOf<WModels.Model?>(null) }
    Card(Modifier.padding(if (compact) 0.dp else 12.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!compact) {
                Text("הורדה חד-פעמית של מודל העברית", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("התמלול נעשה בטלפון עצמו, בלי אינטרנט. כדאי להוריד ב-Wi-Fi. הודעות שתשתף בינתיים ימתינו בתור ויתומללו כשההורדה תסתיים.",
                    style = MaterialTheme.typography.bodySmall)
            }
            WModels.all.forEach { m ->
                val pr = remember(tick) { WModels.progress(ctx, m) }
                val have = remember(tick) { WModels.isReady(ctx, m) }
                Surface(shape = RoundedCornerShape(14.dp), color = cs.surface, modifier = Modifier.fillMaxWidth().clickable {
                    sel = m.key; WModels.select(ctx, m.key)
                }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(sel == m.key, { sel = m.key; WModels.select(ctx, m.key) })
                            Column(Modifier.weight(1f)) {
                                Text("${m.label} · ${mb(m.size)}", fontWeight = FontWeight.Bold)
                                Text(m.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            if (have) Text("✓ מותקן", color = cs.primary, fontWeight = FontWeight.Bold)
                        }
                        when {
                            WModels.verifying == m.key -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("בודק את הקובץ…", style = MaterialTheme.typography.bodySmall) }
                            pr != null -> {
                                val frac = if (pr.third > 0) pr.second.toFloat() / pr.third else 0f
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(when (pr.first) {
                                        DownloadManager.STATUS_PENDING -> "ממתין להורדה…"
                                        DownloadManager.STATUS_PAUSED -> "מושהה (ממתין ל-Wi-Fi / רשת) · ${mb(pr.second)} מתוך ${mb(pr.third)}"
                                        else -> "מוריד ${mb(pr.second)} מתוך ${mb(pr.third)} · ${(frac * 100).toInt()}%"
                                    }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton({ WModels.cancelDownload(ctx, m) }) { Text("ביטול") }
                                }
                            }
                            have -> Row { Spacer(Modifier.weight(1f)); TextButton({ confirmDel = m }) { Text("מחק מהטלפון") } }
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ sel = m.key; WModels.select(ctx, m.key); WModels.startDownload(ctx, m, wifiOnly = true) }) { Text("הורד ב-Wi-Fi") }
                                OutlinedButton({ sel = m.key; WModels.select(ctx, m.key); WModels.startDownload(ctx, m, wifiOnly = false) }) { Text("הורד עכשיו") }
                            }
                        }
                    }
                }
            }
            WModels.lastError?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    confirmDel?.let { m ->
        AlertDialog(onDismissRequest = { confirmDel = null },
            title = { Text("למחוק את המודל?") },
            text = { Text("יתפנו ${mb(m.size)}. כדי לתמלל שוב תצטרך להוריד אותו מחדש.") },
            confirmButton = { TextButton({ WModels.delete(ctx, m); confirmDel = null }) { Text("מחק") } },
            dismissButton = { TextButton({ confirmDel = null }) { Text("ביטול") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(item: Transcripts.Item, expanded: Boolean, modelReady: Boolean, onToggle: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var text by remember(item.id, item.status) { mutableStateOf(item.text) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    DisposableEffect(item.id) { onDispose { player?.release(); player = null } }
    fun go(extra: Intent.() -> Unit) {
        if (text != item.text) Transcripts.update(ctx, item, text)
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply(extra))
        onFinish()
    }
    val head = listOfNotNull(item.source.takeIf { s -> s.isNotBlank() }, Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()).format(HM),
        item.durationMs.takeIf { d -> d > 0 }?.let { d -> fmtDur(d) }).joinToString(" · ")

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.clickable(onClick = onToggle).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(head, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Text(when (item.status) {
                    "queued" -> if (modelReady) "⏳ בתור" else "⏳ ממתין למודל"
                    "running" -> "🎧 מתמלל"
                    "error" -> "⚠️ נכשל"
                    "cancelled" -> "⏹ בוטל"
                    else -> ""
                }, style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
            when (item.status) {
                "running" -> {
                    val p = Transcripts.progress
                    if (p > 0) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${Transcripts.phase} ${if (p > 0) "$p%" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ Transcripts.cancelRunning() }) { Text("עצור") }
                    }
                }
                "error", "cancelled" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.error.ifBlank { "התמלול הופסק" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.error)
                    TextButton({ Transcripts.retry(ctx, item) }) { Text("נסה שוב") }
                }
                "done" -> if (!expanded) Text(item.text.ifBlank { "(לא זוהה דיבור)" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                if (item.status == "done") OutlinedTextField(text, { v -> text = v }, Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp), minLines = 3,
                    placeholder = { Text("(לא זוהה דיבור)") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip({
                        val pl = player
                        if (pl != null) { pl.release(); player = null }
                        else runCatching {
                            player = MediaPlayer().apply { setDataSource(item.path); setOnCompletionListener { mp -> mp.release(); player = null }; prepare(); start() }
                        }.onFailure { Toast.makeText(ctx, "אי אפשר לנגן", Toast.LENGTH_SHORT).show() }
                    }, label = { Text(if (player != null) "⏹ עצור" else "▶ השמע") })
                    if (item.status == "done" && text.isNotBlank()) {
                        AssistChip({
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("תמלול", text))
                            Toast.makeText(ctx, "הועתק", Toast.LENGTH_SHORT).show()
                        }, label = { Text("📋 העתק") })
                        AssistChip({
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "שיתוף התמלול"))
                        }, label = { Text("↗ שתף") })
                        AssistChip({
                            scope.launch {
                                val n = newNote(ctx)
                                val title = "הודעה קולית" + (if (item.source.isNotBlank()) " · ${item.source}" else "") + " · " +
                                    Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()).format(HM)
                                val html = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
                                Notes.save(ctx, n.copy(title = title, html = html, plain = text))
                                go { putExtra("trNote", n.id) }
                            }
                        }, label = { Text("📝 שמור כפתק") })
                        AssistChip({ go { putExtra("trTask", text) } }, label = { Text("⏰ תזכורת") })
                        AssistChip({ go { putExtra("trEvent", text) } }, label = { Text("📅 אירוע ביומן") })
                    }
                    AssistChip({ confirmDel = true }, label = { Text("🗑 מחק") })
                }
            }
        }
    }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false },
        title = { Text("למחוק את התמלול?") }, text = { Text("ההקלטה והטקסט יימחקו מהרשימה.") },
        confirmButton = { TextButton({ confirmDel = false; Transcripts.remove(ctx, item) }) { Text("מחק") } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("ביטול") } })
    // keep edits even if the screen is closed without pressing a button
    DisposableEffect(item.id) { onDispose { if (text != item.text && item.status == "done") Transcripts.update(ctx, item, text) } }
}
