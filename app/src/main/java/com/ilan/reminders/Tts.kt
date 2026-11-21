package com.ilan.reminders

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Reads a document aloud with Android's own text-to-speech engine (Hebrew). State: 0 idle, 1 playing, 2 paused. */
class SpeechReader(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var chunks: List<String> = emptyList()
    private var onReady: (() -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    var state by mutableStateOf(0)
    var index by mutableStateOf(0)
    var total by mutableStateOf(0)
    var rate by mutableStateOf(1f)

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st != TextToSpeech.SUCCESS) { failed = true; main.post { toast("הקראה לא זמינה במכשיר") }; return@TextToSpeech }
            val r = tts?.setLanguage(Locale("he", "IL")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                failed = true
                main.post { toast("אין קול עברי מותקן. אפשר להתקין בהגדרות ← ניהול כללי ← המרת טקסט לדיבור") }
                return@TextToSpeech
            }
            pickVoice()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { id?.removePrefix("c")?.toIntOrNull()?.let { i -> main.post { index = i } } }
                override fun onDone(id: String?) { if (id == "c${chunks.size - 1}") main.post { state = 0; index = 0 } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { if (state == 1) state = 0 } }
            })
            ready = true
            main.post { onReady?.invoke(); onReady = null }
        }
    }

    private fun toast(m: String) = Toast.makeText(ctx.applicationContext, m, Toast.LENGTH_LONG).show()

    private val gaps = ArrayList<Long>()

    /** Cleans the text so the engine reads it naturally: headings get a full stop, quotes / brackets are not read out, dashes become commas. */
    private fun prepare(text: String): String {
        val sb = StringBuilder()
        for (line in text.replace("\r", "").split("\n")) {
            var l = line.trim()
            if (l.isEmpty()) { sb.append('\n'); continue }
            l = l.replace(Regex("[\"\u201C\u201D\u05F4()\\[\\]{}*_#|]"), "").replace(Regex("\\s[-\u2013\u2014]+\\s"), ", ").replace(Regex("\\s{2,}"), " ")
            if (!l.last().let { it == '.' || it == '!' || it == '?' || it == ':' || it == ';' || it == ',' }) l += "."
            sb.append(l).append('\n')
        }
        return sb.toString()
    }

    /** One piece per sentence (the engine keeps natural intonation inside a sentence) and a measured silence after it. */
    private fun split(text: String): List<String> {
        val out = ArrayList<String>(); gaps.clear()
        val src = prepare(text)
        val cur = StringBuilder(); var i = 0
        fun flush(para: Boolean) {
            val p = cur.toString().trim(); cur.setLength(0)
            if (p.isEmpty() || p.all { !it.isLetterOrDigit() }) return
            val gap = when { para -> 650L; p.endsWith("?") || p.endsWith("!") -> 450L; p.endsWith(".") -> 380L; p.endsWith(":") -> 300L; else -> 220L }
            if (p.length > 600) {
                val parts = p.chunked(600)
                parts.forEachIndexed { k, q -> out.add(q); gaps.add(if (k == parts.lastIndex) gap else 0L) }
            } else { out.add(p); gaps.add(gap) }
        }
        while (i < src.length) {
            val ch = src[i]
            if (ch == '\n') { flush(true); i++; continue }
            cur.append(ch)
            if (ch in ".!?:;" && (i + 1 >= src.length || src[i + 1].isWhitespace())) {
                var j = i + 1; var nl = false
                while (j < src.length && src[j].isWhitespace()) { if (src[j] == '\n') nl = true; j++ }
                flush(nl); i = j; continue
            }
            i++
        }
        flush(true)
        return out
    }

    fun start(text: String) {
        val c = split(text)
        if (c.isEmpty()) { toast("אין טקסט להקראה"); return }
        chunks = c; total = c.size; index = 0
        fun go() { state = 1; speakFrom(0) }
        if (failed) return
        if (ready) go() else onReady = { go() }
    }

    private fun speakFrom(i: Int) {
        val t = tts ?: return
        t.stop(); t.setSpeechRate(rate)
        for (j in i until chunks.size) {
            t.speak(chunks[j], TextToSpeech.QUEUE_ADD, null, "c$j")
            if (j < chunks.size - 1 && gaps.getOrElse(j) { 0L } > 0) t.playSilentUtterance((gaps[j] / rate).toLong(), TextToSpeech.QUEUE_ADD, "s$j")
        }
    }

    /** The most natural Hebrew voice installed: highest quality first (network voices included). */
    private fun pickVoice() {
        val t = tts ?: return
        runCatching {
            val v = t.voices?.filter { it.locale.language == "he" || it.locale.language == "iw" }
                ?.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.maxByOrNull { it.quality * 10 + (if (it.isNetworkConnectionRequired) 3 else 0) - it.latency / 100 }
            if (v != null) t.voice = v
        }
    }

    fun pause() { if (state == 1) { tts?.stop(); state = 2 } }
    fun resume() { if (state == 2) { state = 1; speakFrom(index) } }
    fun stop() { tts?.stop(); state = 0; index = 0 }
    fun setSpeed(r: Float) { rate = r; if (state == 1) speakFrom(index) }
    fun release() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null }
}

@Composable
fun rememberSpeechReader(): SpeechReader {
    val ctx = LocalContext.current
    val r = remember { SpeechReader(ctx) }
    DisposableEffect(Unit) { onDispose { r.release() } }
    return r
}

/** Compact play / pause / stop / speed strip shown while a document is being read aloud. */
@Composable
fun ReadAloudBar(r: SpeechReader) {
    if (r.state == 0) return
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    DisposableEffect(r.state) { view.keepScreenOn = r.state == 1; onDispose { view.keepScreenOn = false } }
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    Surface(color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔊", fontSize = 18.sp)
            TextButton({ if (r.state == 1) r.pause() else r.resume() }) { Text(if (r.state == 1) "⏸ השהה" else "▶ המשך") }
            TextButton({ r.stop() }) { Text("⏹ עצור") }
            Spacer(Modifier.weight(1f))
            Text("${minOf(r.index + 1, r.total)}/${r.total}", fontSize = 12.sp, color = cs.onSecondaryContainer)
            TextButton({ val i = speeds.indexOfFirst { it >= r.rate - 0.01f }.let { if (it < 0) 0 else (it + 1) % speeds.size }; r.setSpeed(speeds[i]) }) {
                Text((if (r.rate == r.rate.toInt().toFloat()) r.rate.toInt().toString() else r.rate.toString()) + "×")
            }
        }
    }
}

/** Shown when a start marker was placed in a document: read from there, or remove the marker. */
@Composable
fun MarkerBar(onRead: () -> Unit, onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📍 ההקראה תתחיל מהסימון", fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            TextButton(onRead) { Text("▶ הקרא מכאן") }
            TextButton(onClear) { Text("✕") }
        }
    }
}
