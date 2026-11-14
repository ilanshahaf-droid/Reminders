package com.ilan.reminders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** On-device Hebrew + English text recognition for scanned PDFs. The text is kept next to the document and used by the search. */
object Ocr {
    private fun sidecar(c: Context, f: File) = File(File(c.filesDir, "ocr").apply { mkdirs() }, f.name + ".txt")

    fun text(c: Context, f: File): String? = sidecar(c, f).takeIf { it.exists() }?.readText()

    fun forget(c: Context, f: File) { sidecar(c, f).delete() }

    private fun ensureData(c: Context): String {
        val root = File(c.filesDir, "tess"); val td = File(root, "tessdata").apply { mkdirs() }
        for (l in listOf("heb", "eng")) {
            val out = File(td, "$l.traineddata")
            if (!out.exists() || out.length() == 0L) c.assets.open("tessdata/$l.traineddata").use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        return root.absolutePath
    }

    /** Reads every page of [f] (a PDF) and returns the recognised text. [progress] gets (page, total). */
    suspend fun run(c: Context, f: File, progress: (Int, Int) -> Unit): String = withContext(Dispatchers.Default) {
        val api = TessBaseAPI()
        val sb = StringBuilder()
        try {
            if (!api.init(ensureData(c), "heb+eng")) error("init")
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) {
                        withContext(Dispatchers.Main) { progress(i + 1, r.pageCount) }
                        r.openPage(i).use { p ->
                            val k = 2000f / maxOf(p.width, p.height)
                            val bmp = Bitmap.createBitmap((p.width * k).toInt(), (p.height * k).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            api.setImage(bmp)
                            val t = api.utf8Text.orEmpty().trim()
                            if (t.isNotEmpty()) sb.append(t).append("\n\n")
                            bmp.recycle()
                        }
                    }
                }
            }
        } finally { runCatching { api.recycle() } }
        val out = sb.toString().trim()
        sidecar(c, f).writeText(out)
        out
    }
}
