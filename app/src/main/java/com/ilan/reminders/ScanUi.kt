package com.ilan.reminders

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class ScanBridge(
    val first: ByteArray,
    val onReady: () -> Unit, val onResult: (String, String) -> Unit, val onCamera: (String) -> Unit,
    val onClose: () -> Unit, val onToast: (String) -> Unit
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @android.webkit.JavascriptInterface fun ready() { main.post { onReady() } }
    @android.webkit.JavascriptInterface fun opened() {}
    @android.webkit.JavascriptInterface fun fileData(i: Int): String = Base64.encodeToString(first, Base64.NO_WRAP)
    @android.webkit.JavascriptInterface fun result(name: String, b64: String) { main.post { onResult(name, b64) } }
    @android.webkit.JavascriptInterface fun camera(mode: String) { main.post { onCamera(mode) } }
    @android.webkit.JavascriptInterface fun close() { main.post { onClose() } }
    @android.webkit.JavascriptInterface fun toast(m: String) { main.post { onToast(m) } }
}

/** After a photo is taken: crop, rotate, filter and add pages, then PDF settings and save. Full screen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScanEditorScreen(first: ByteArray, dark: Boolean, onClose: () -> Unit, onCreated: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val web = remember { mutableStateOf<WebView?>(null) }
    val shot = remember { File(ctx.cacheDir, "share").apply { mkdirs() }.let { File(it, "scan2.jpg") } }
    var pendingMode by remember { mutableStateOf("a") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && shot.exists() && shot.length() > 0) {
            val b64 = Base64.encodeToString(shot.readBytes(), Base64.NO_WRAP)
            web.value?.evaluateJavascript("SC.shot('$b64','$pendingMode')", null)
        }
    }
    val defName = remember { "סריקה " + java.text.SimpleDateFormat("dd-MM-yy HH.mm", java.util.Locale.US).format(java.util.Date()) }
    Dialog(
        onDismissRequest = { web.value?.evaluateJavascript("SC.back()", null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(
                        ScanBridge(
                            first,
                            onReady = { evaluateJavascript("SC.open(${dark}, ${org.json.JSONObject.quote(defName)})", null) },
                            onResult = { name, b64 ->
                                scope.launch {
                                    val out = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val f = Docs.unique(ctx, name.ifBlank { defName }, "pdf")
                                            f.writeBytes(Base64.decode(b64, Base64.DEFAULT)); f
                                        }.getOrNull()
                                    }
                                    if (out != null) onCreated(out) else Toast.makeText(ctx, "השמירה נכשלה", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCamera = { m ->
                                pendingMode = m
                                runCatching { shot.delete(); camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shot)) }
                                    .onFailure { Toast.makeText(ctx, "המצלמה לא זמינה", Toast.LENGTH_SHORT).show() }
                            },
                            onClose = onClose,
                            onToast = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                        ), "Android"
                    )
                    loadUrl("file:///android_asset/scan_editor.html")
                    web.value = this
                }
            }, onRelease = { w: WebView -> w.destroy() })
        }
    }
}
