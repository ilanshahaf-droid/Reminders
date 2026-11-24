package com.ilan.reminders

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest

/** whisper.cpp compiled into the app (app/src/main/cpp). */
object WhisperNative {
    val available: Boolean = runCatching { System.loadLibrary("rwhisper") }.isSuccess

    fun interface Progress { fun onProgress(p: Int) }

    external fun init(path: String): Long
    external fun free(ctx: Long)
    external fun cancel()
    external fun systemInfo(): String
    external fun transcribe(ctx: Long, threads: Int, lang: String, audio: FloatArray, cb: Progress, mode: Int): ByteArray?
}

/** The Hebrew speech models (ivrit.ai whisper-large-v3-turbo, quantized). Downloaded once; kept across app updates. */
object WModels {
    class Model(val key: String, val label: String, val desc: String, val file: String, val size: Long, val sha256: String, val urls: List<String>)

    val all = listOf(
        Model(
            "q5", "מכווץ (מומלץ)", "מהיר יותר, איכות טובה מאוד", "ggml-ivrit-large-v3-turbo-q5_0.bin", 574_041_195L,
            "6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572",
            listOf("https://huggingface.co/tagidu/ivrit-whisper-large-v3-turbo-ggml-quantized/resolve/main/ggml-ivrit-large-v3-turbo-q5_0.bin")
        ),
        Model(
            "q8", "מדויק", "כמעט זהה למודל המלא, איטי וכבד יותר", "ggml-ivrit-large-v3-turbo-q8_0.bin", 874_188_075L,
            "123a936e686b06d45b52dc1790251a1418841352ca94079ae643d57893ffc9a6",
            listOf(
                "https://huggingface.co/tagidu/ivrit-whisper-large-v3-turbo-ggml-quantized/resolve/main/ggml-ivrit-large-v3-turbo-q8_0.bin",
                "https://huggingface.co/sivelsguy/whisper-large-v3-turbo-ivrit-q8_0-ggml/resolve/main/ggml-ivrit-large-v3-turbo-q8_0.bin",
                "https://github.com/eliranzror-pixel/hibro-models/releases/download/model-v1/ivrit-turbo-q8_0.bin"
            )
        )
    )

    private fun prefs(c: Context) = c.getSharedPreferences("transcribe", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "models").also { it.mkdirs() }
    fun fileOf(c: Context, m: Model) = File(dir(c), m.file)
    fun isReady(c: Context, m: Model) = fileOf(c, m).let { it.exists() && it.length() == m.size }
    fun selectedKey(c: Context) = prefs(c).getString("model", "q5") ?: "q5"
    fun selected(c: Context): Model = all.firstOrNull { it.key == selectedKey(c) } ?: all.first()
    fun select(c: Context, key: String) = prefs(c).edit().putString("model", key).apply()
    /** The model to transcribe with: the chosen one if downloaded, otherwise any downloaded one. */
    fun usable(c: Context): Model? = selected(c).takeIf { isReady(c, it) } ?: all.firstOrNull { isReady(c, it) }

    // ---------- download (system DownloadManager: survives closing the app, shows its own progress notification) ----------
    var verifying by mutableStateOf<String?>(null)
    var lastError by mutableStateOf<String?>(null)

    fun downloadId(c: Context, m: Model) = prefs(c).getLong("dl_${m.key}", -1L)

    fun startDownload(c: Context, m: Model, wifiOnly: Boolean, urlIndex: Int = 0) {
        val dm = c.getSystemService(DownloadManager::class.java)
        cancelDownload(c, m)
        lastError = null
        val part = File(dir(c), m.file + ".part"); part.delete()
        val req = DownloadManager.Request(Uri.parse(m.urls[urlIndex]))
            .setTitle("מודל תמלול עברית (${m.label})")
            .setDescription("סדר יום · הורדה חד-פעמית")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part))
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        prefs(c).edit().putLong("dl_${m.key}", id).putInt("dlUrl_${m.key}", urlIndex).putBoolean("dlWifi_${m.key}", wifiOnly).apply()
    }

    fun cancelDownload(c: Context, m: Model) {
        val id = downloadId(c, m)
        if (id >= 0) runCatching { c.getSystemService(DownloadManager::class.java).remove(id) }
        prefs(c).edit().remove("dl_${m.key}").apply()
        File(dir(c), m.file + ".part").delete()
    }

    fun delete(c: Context, m: Model) { cancelDownload(c, m); fileOf(c, m).delete() }

    /** (status, downloaded bytes, total bytes) of the running download, or null if none. */
    fun progress(c: Context, m: Model): Triple<Int, Long, Long>? {
        val id = downloadId(c, m)
        if (id < 0) return null
        val dm = c.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
            if (!cur.moveToFirst()) return null
            val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val tot = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return Triple(st, got, if (tot > 0) tot else m.size)
        }
        return null
    }

    /** Called repeatedly while the screen is open: finishes a completed download (checks the file) or tries the next mirror. */
    fun poll(c: Context, m: Model) {
        val pr = progress(c, m) ?: return
        when (pr.first) {
            DownloadManager.STATUS_SUCCESSFUL -> if (verifying == null) {
                verifying = m.key
                Thread {
                    val part = File(dir(c), m.file + ".part")
                    val ok = part.length() == m.size && sha256(part) == m.sha256
                    prefs(c).edit().remove("dl_${m.key}").apply()
                    if (ok) { fileOf(c, m).delete(); part.renameTo(fileOf(c, m)) } else part.delete()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        verifying = null
                        if (ok) Transcripts.kick(c) else nextMirror(c, m, "הקובץ שהורד לא תקין")
                    }
                }.start()
            }
            DownloadManager.STATUS_FAILED -> nextMirror(c, m, "ההורדה נכשלה")
        }
    }

    private fun nextMirror(c: Context, m: Model, why: String) {
        val p = prefs(c)
        val next = p.getInt("dlUrl_${m.key}", 0) + 1
        if (next < m.urls.size) startDownload(c, m, p.getBoolean("dlWifi_${m.key}", true), next)
        else { cancelDownload(c, m); lastError = "$why. בדוק את החיבור ונסה שוב." }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Decodes any audio file Android can read (WhatsApp .opus, m4a, mp3, wav...) to 16 kHz mono floats, as whisper needs. */
object AudioDecode {
    fun decode(path: String): Pair<FloatArray, Long> {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        require(track >= 0) { "no audio track" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var mono = FloatArray(1 shl 20); var n = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / ch
                        if (n + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, n + frames))
                        for (f in 0 until frames) {
                            var s = 0f
                            for (k in 0 until ch) s += sb.get()
                            mono[n++] = s / ch / 32768f
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
        return resample(mono, n, rate) to (if (durUs > 0) durUs / 1000 else n * 1000L / rate)
    }

    /** Quiet or very short messages are often judged "no speech": bring the loudest peak up to ~0.9 (gain capped) and add half a second of silence on both sides. */
    fun prepare(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        val gain = if (peak > 0.002f && peak < 0.5f) minOf(0.9f / peak, 12f) else 1f
        val pad = 8000
        val out = FloatArray(x.size + 2 * pad)
        for (i in x.indices) out[pad + i] = (x[i] * gain).coerceIn(-1f, 1f)
        return out
    }

    /** Box-filter (average) resampling to 16 kHz: good enough for speech and avoids most aliasing. */
    private fun resample(x: FloatArray, n: Int, rate: Int): FloatArray {
        if (rate == 16000) return x.copyOf(n)
        val outN = (n.toLong() * 16000 / rate).toInt()
        val out = FloatArray(outN)
        val step = rate / 16000.0
        for (i in 0 until outN) {
            val a = (i * step).toInt(); val b = minOf(n, maxOf(a + 1, ((i + 1) * step).toInt()))
            var s = 0f; for (j in a until b) s += x[j]
            out[i] = s / (b - a)
        }
        return out
    }
}

/** Queue + history of voice-message transcriptions. Work runs in [TranscribeService] so it continues with the screen off. */
object Transcripts {
    class Item(val id: Long, val path: String, val source: String, val name: String, val createdAt: Long,
               var durationMs: Long = 0, var text: String = "", var status: String = "queued", var error: String = "",
               var attempts: Int = 0, var stage: String = "", var diag: String = "")
    // status: queued, running, done, error, cancelled

    // neverEqualPolicy: items are mutated in place (status, text...), so every reassignment must refresh the screen
    var items by mutableStateOf<List<Item>>(emptyList(), androidx.compose.runtime.neverEqualPolicy())
    var progress by mutableStateOf(0)
    var phase by mutableStateOf("")     // shown under the progress bar
    private var loaded = false

    fun shortTitle(t: String): String {
        val one = t.trim().replace(Regex("\\s+"), " ")
        if (one.length <= 60) return one
        val cut = one.substring(0, 60)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 30 } ?: 60) + "…"
    }

    private fun dir(c: Context) = File(c.filesDir, "voice").also { it.mkdirs() }
    private fun store(c: Context) = File(dir(c), "history.json")

    @Synchronized fun load(c: Context) {
        if (loaded) return
        loaded = true
        items = runCatching {
            val a = JSONArray(store(c).readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Item(o.getLong("id"), o.getString("path"), o.optString("source"), o.optString("name"), o.getLong("createdAt"),
                    o.optLong("dur"), o.optString("text"), o.optString("status"), o.optString("error"), o.optInt("attempts"), o.optString("stage"), o.optString("diag"))
                    // a job that was running when the app was closed goes back to the queue
                    .also {
                        // the app was closed (or crashed) in the middle of this one: retry once, then report where it stopped
                        if (it.status == "running") {
                            if (it.attempts >= 2) { it.status = "error"; it.error = "התמלול נעצר באמצע (בשלב: ${it.stage}). אפשר לנסות שוב." }
                            else it.status = "queued"
                        }
                    }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun save(c: Context) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("path", it.path).put("source", it.source).put("name", it.name)
            .put("createdAt", it.createdAt).put("dur", it.durationMs).put("text", it.text).put("status", it.status).put("error", it.error).put("attempts", it.attempts).put("stage", it.stage).put("diag", it.diag)) }
        store(c).writeText(a.toString())
    }

    private fun touch(c: Context) { items = items.toList(); save(c) }

    /** Copies the shared audio into the app (WhatsApp's permission ends when the share screen closes) and queues it. */
    fun add(c: Context, uri: Uri, source: String): Item? {
        load(c)
        val id = System.currentTimeMillis() + items.size
        val name = runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "הודעה קולית"
        val f = File(dir(c), "$id.${name.substringAfterLast('.', "audio").take(5)}")
        val ok = runCatching { c.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
        if (!ok || f.length() == 0L) { f.delete(); return null }
        val item = Item(id, f.path, source, name, System.currentTimeMillis())
        items = listOf(item) + items
        // keep the 30 newest
        items.drop(30).forEach { old -> File(old.path).delete() }
        items = items.take(30)
        save(c)
        return item
    }

    fun remove(c: Context, item: Item) {
        if (item.status == "running") WhisperNative.cancel()
        File(item.path).delete(); items = items.filter { x -> x.id != item.id }; save(c)
    }

    fun update(c: Context, item: Item, text: String) { item.text = text; touch(c) }

    fun retry(c: Context, item: Item) { item.status = "queued"; item.error = ""; item.attempts = 0; touch(c); kick(c) }

    /** Small diagnostic log (last 40 lines) shown in the ⚙️ sheet. */
    fun log(c: Context, line: String) = synchronized(this) {
        runCatching {
            val p = c.getSharedPreferences("tlog", Context.MODE_PRIVATE)
            val ls = (p.getString("l", "") ?: "").split("\n").filter { it.isNotBlank() }.takeLast(39) +
                (java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + line)
            p.edit().putString("l", ls.joinToString("\n")).apply()
        }
    }
    fun logText(c: Context): String = c.getSharedPreferences("tlog", Context.MODE_PRIVATE).getString("l", "").orEmpty()

    fun cancelRunning() { if (WhisperNative.available) WhisperNative.cancel() }

    /** Starts the background worker if there is something to do and a model is ready. */
    var kickError by mutableStateOf<String?>(null)

    fun kick(c: Context) {
        load(c)
        if (items.none { it.status == "queued" } || WModels.usable(c) == null || !WhisperNative.available) { log(c, "kick: nothing to do (queued=${items.count { it.status == "queued" }}, model=${WModels.usable(c) != null}, native=${WhisperNative.available})"); return }
        val i = Intent(c, TranscribeService::class.java)
        kickError = runCatching { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }
            .exceptionOrNull()?.let { log(c, "service start failed: $it"); "אי אפשר להפעיל את התמלול ברקע: ${it.javaClass.simpleName}" }
        log(c, "kick: service start requested, error=$kickError")
    }

    /** Runs on the service thread: transcribes every queued item, newest first. */
    fun runQueue(c: Context, onProgress: (Item, Int) -> Unit) {
        load(c)
        val m = WModels.usable(c) ?: return
        var ctx = 0L
        try {
            while (true) {
                val item = items.firstOrNull { x -> x.status == "queued" } ?: break
                item.status = "running"; item.attempts++; progress = 0
                fun stage(s: String, label: String) { phase = label; item.stage = s; touch(c) }
                try {
                    stage("פענוח השמע", "מפענח את השמע…")
                    val (audio0, dur) = AudioDecode.decode(item.path)
                    var peak = 0f; var sq = 0.0
                    for (v in audio0) { val a = if (v < 0) -v else v; if (a > peak) peak = a; sq += v.toDouble() * v }
                    val rms = if (audio0.isNotEmpty()) Math.sqrt(sq / audio0.size) else 0.0
                    val audio = AudioDecode.prepare(audio0)
                    item.durationMs = dur
                    item.diag = "אורך %.1f שנ׳ · שיא %.3f · עוצמה %.4f".format(audio0.size / 16000.0, peak, rms)
                    log(c, "item ${item.id} ${item.source}: ${item.diag}")
                    if (ctx == 0L) { stage("טעינת המודל", "טוען את מודל העברית…"); ctx = WhisperNative.init(WModels.fileOf(c, m).path) }
                    require(ctx != 0L) { "model" }
                    stage("תמלול", "מתמלל…")
                    val threads = minOf(6, maxOf(2, Runtime.getRuntime().availableProcessors() / 2 + 1))
                    val cb = object : WhisperNative.Progress { override fun onProgress(p: Int) { progress = p; onProgress(item, p) } }
                    var bytes = WhisperNative.transcribe(ctx, threads, "he", audio, cb, 0)
                    var txt = bytes?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
                    if (bytes != null && txt.isEmpty() && item.status == "running") {
                        // nothing came out: try once more with beam search and no fallbacks / filters
                        log(c, "item ${item.id}: empty result, second pass")
                        stage("תמלול", "מנסה שוב בהגדרות אחרות…")
                        bytes = WhisperNative.transcribe(ctx, threads, "he", audio, cb, 1)
                        txt = bytes?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
                        item.diag += " · ניסיון שני" + if (txt.isEmpty()) " (ריק)" else " (הצליח)"
                    }
                    log(c, "item ${item.id}: result ${txt.length} chars")
                    item.attempts = 0
                    if (bytes == null) { item.status = if (item.status == "running") "cancelled" else item.status }
                    else { item.text = txt; item.status = "done" }
                } catch (e: Throwable) {
                    item.status = "error"
                    log(c, "item ${item.id} failed: ${e.javaClass.simpleName} ${e.message}")
                    item.error = if (e.message == "model") "טעינת המודל נכשלה (נסה למחוק ולהוריד מחדש)" else "אי אפשר לקרוא את קובץ השמע"
                }
                touch(c)
                Notif.done(c, item)
            }
        } finally { if (ctx != 0L) WhisperNative.free(ctx); phase = ""; progress = 0 }
    }

    object Notif {
        const val CH = "transcribe"
        fun channel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "תמלול הודעות קוליות", NotificationManager.IMPORTANCE_LOW))
        }
        fun open(c: Context, id: Long): PendingIntent = PendingIntent.getActivity(c, 7301,
            Intent(c, TranscribeActivity::class.java).putExtra("show", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun running(c: Context, item: Item?, p: Int): Notification {
            channel(c)
            return NotificationCompat.Builder(c, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("מתמלל הודעה קולית…").setContentText(if (item?.source.isNullOrBlank()) "$p%" else "${item!!.source} · $p%")
                .setProgress(100, p, p == 0).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open(c, item?.id ?: 0)).build()
        }
        fun done(c: Context, item: Item) {
            if (TranscribeActivity.visible || (item.status != "done" && item.status != "error")) return
            channel(c)
            val n = NotificationCompat.Builder(c, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(if (item.status == "done") "התמלול מוכן" else "התמלול נכשל")
                .setContentText(if (item.status == "done") shortTitle(item.text.ifBlank { "(לא זוהה דיבור)" }) else item.error)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.text.ifBlank { item.error }))
                .setAutoCancel(true).setContentIntent(open(c, item.id)).build()
            runCatching { c.getSystemService(NotificationManager::class.java).notify(7300 + (item.id % 1000).toInt(), n) }
        }
    }
}

class TranscribeService : Service() {
    @Volatile private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Transcripts.Notif.running(this, null, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(7299, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(7299, n)
        Transcripts.log(this, "service start, worker alive=${worker?.isAlive == true}")
        if (worker?.isAlive != true) {
            worker = Thread {
                val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reminders:transcribe")
                wl.acquire(60 * 60 * 1000L)
                var last = -1
                try {
                    Transcripts.runQueue(this) { item, p ->
                        if (p / 5 != last) { last = p / 5; runCatching { getSystemService(NotificationManager::class.java).notify(7299, Transcripts.Notif.running(this, item, p)) } }
                    }
                } finally {
                    if (wl.isHeld) wl.release()
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }.also { it.start() }
        }
        return START_NOT_STICKY
    }
}
