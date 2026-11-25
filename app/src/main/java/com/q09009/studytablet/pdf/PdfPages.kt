package com.q09009.studytablet.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Thread-safe wrapper around [PdfRenderer] with a small bitmap cache. */
class PdfPages private constructor(
    private val renderer: PdfRenderer,
    private val closeable: Closeable,
    /** width/height aspect ratio per page */
    val aspectRatios: List<Float>,
) : Closeable {
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val pageCount get() = aspectRatios.size

    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(1, 4096)
                val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(1, 8192)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                bmp
            }
        }
    }

    override fun close() {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { closeable.close() }
    }

    companion object {
        suspend fun open(context: Context, uri: Uri): PdfPages = withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("파일을 열 수 없습니다.")
            val renderer = PdfRenderer(pfd)
            val ratios = (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { it.width.toFloat() / it.height }
            }
            PdfPages(renderer, pfd, ratios)
        }
    }
}
