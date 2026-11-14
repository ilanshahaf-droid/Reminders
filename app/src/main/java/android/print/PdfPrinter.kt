package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Writes what a WebView would print straight into a PDF file (no print dialog).
 * It lives in the android.print package because the callback classes can only be extended from there.
 */
class PdfPrinter {
    fun print(adapter: PrintDocumentAdapter, out: File, done: (Boolean) -> Unit) {
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins(400, 400, 400, 400))
            .build()
        adapter.onLayout(null, attrs, null, object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val pfd = try {
                    ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                } catch (e: Exception) { done(false); return }
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<PageRange>?) { runCatching { pfd.close() }; done(true) }
                    override fun onWriteFailed(error: CharSequence?) { runCatching { pfd.close() }; done(false) }
                })
            }
            override fun onLayoutFailed(error: CharSequence?) { done(false) }
        }, null)
    }
}
