package com.q09009.studytablet.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Thread-safe wrapper around [PdfRenderer] with a bitmap cache for whole-page renders. */
class PdfPages private constructor(
    private val renderer: PdfRenderer,
    private val closeable: Closeable,
    /** width/height aspect ratio per page */
    val aspectRatios: List<Float>,
) : Closeable {
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(32L shl 20, 256L shl 20).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val pageCount get() = aspectRatios.size

    /** Renders the whole page at [widthPx] wide (cached). */
    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        val w = widthPx.coerceIn(1, 4096)
        val key = "$index@$w"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            renderer.openPage(index).use { page ->
                val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(1, 8192)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                bmp
            }
        }
    }

    /**
     * Renders only a region of the page, as if the page were [pageWidthPx] wide.
     * Used for sharp detail at high zoom without allocating huge bitmaps.
     */
    suspend fun renderRegion(index: Int, pageWidthPx: Float, left: Int, top: Int, width: Int, height: Int): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                renderer.openPage(index).use { page ->
                    val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val scale = pageWidthPx / page.width
                    val m = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(-left.toFloat(), -top.toFloat())
                    }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
