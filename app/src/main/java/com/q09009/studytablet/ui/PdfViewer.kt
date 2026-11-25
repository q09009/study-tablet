package com.q09009.studytablet.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.pdf.PdfPages
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Page positions at zoom 1, in px. */
class PageLayout(val pageWidth: Float, val pad: Float, val tops: FloatArray, val heights: FloatArray, val total: Float) {
    companion object {
        fun compute(aspectRatios: List<Float>, viewportWidth: Float, pad: Float): PageLayout {
            val pageWidth = (viewportWidth - 2 * pad).coerceAtLeast(1f)
            val tops = FloatArray(aspectRatios.size)
            val heights = FloatArray(aspectRatios.size)
            var y = pad
            aspectRatios.forEachIndexed { i, ar ->
                tops[i] = y
                heights[i] = pageWidth / ar
                y += heights[i] + pad
            }
            return PageLayout(pageWidth, pad, tops, heights, y)
        }
    }
}

/** Zoom + scroll state of the PDF viewer. Everything scales around a focal point. */
@Stable
class PdfZoom {
    var value by mutableFloatStateOf(1f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    var scrollY by mutableFloatStateOf(0f)
        private set

    private var viewportW = 0f
    private var viewportH = 0f
    private var layout: PageLayout? = null

    internal fun attach(w: Float, h: Float, l: PageLayout) {
        if (layout != null && viewportW > 0f && w != viewportW) {
            // Viewport width changed (rotation/split resize): keep relative position.
            scrollY *= l.total / layout!!.total
        }
        viewportW = w; viewportH = h; layout = l
        clamp()
    }

    val contentWidth get() = viewportW * value
    val contentHeight get() = (layout?.total ?: 0f) * value

    /** Screen x of the content's left edge. Content narrower than the viewport is centered. */
    val contentLeft get() = if (contentWidth <= viewportW) (viewportW - contentWidth) / 2f else -scrollX

    fun zoomIn() = zoomBy(1.25f)
    fun zoomOut() = zoomBy(0.8f)
    fun reset() = zoomBy(1f / value)

    fun zoomBy(factor: Float, focus: Offset = Offset(viewportW / 2f, viewportH / 2f)) {
        val old = value
        val new = (old * factor).coerceIn(MIN, MAX)
        if (new == old) return
        val ratio = new / old
        val focusContentX = focus.x - contentLeft
        value = new
        scrollX = focusContentX * ratio - focus.x
        scrollY = (scrollY + focus.y) * ratio - focus.y
        clamp()
    }

    fun panBy(dx: Float, dy: Float) {
        scrollX -= dx
        scrollY -= dy
        clamp()
    }

    private fun clamp() {
        scrollX = scrollX.coerceIn(0f, max(0f, contentWidth - viewportW))
        scrollY = scrollY.coerceIn(0f, max(0f, contentHeight - viewportH))
    }

    companion object { const val MIN = 0.5f; const val MAX = 5f }
}

@Composable
fun PdfViewer(uri: android.net.Uri, zoom: PdfZoom, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<Result<PdfPages>?>(null, uri) {
        val r = runCatching { PdfPages.open(context, uri) }
        value = r
        awaitDispose { r.getOrNull()?.close() }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val result = state
        when {
            result == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            result.isFailure -> Text(
                "PDF를 열 수 없습니다: ${result.exceptionOrNull()?.message}",
                Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            else -> PdfCanvas(result.getOrThrow(), zoom)
        }
    }
}

@Composable
private fun PdfCanvas(pages: PdfPages, zoom: PdfZoom) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    var motion by remember { mutableStateOf<Job?>(null) }
    val padPx = with(LocalDensity.current) { 12.dp.toPx() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(zoom) {
                detectTapGestures(onDoubleTap = { pos ->
                    motion?.cancel()
                    val target = if (zoom.value > 1.05f) 1f else 2.5f
                    motion = scope.launch {
                        animate(zoom.value, target) { v, _ -> zoom.zoomBy(v / zoom.value, pos) }
                    }
                })
            }
            .pointerInput(zoom) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    motion?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) multiTouch = true
                        val pan = event.calculatePan()
                        if (pressed >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) zoom.zoomBy(z, event.calculateCentroid())
                        }
                        if (pan != Offset.Zero) zoom.panBy(pan.x, pan.y)
                        if (pressed == 1) {
                            event.changes.first { it.pressed }.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        } else {
                            tracker.resetTracking()
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })

                    if (!multiTouch) {
                        val v = tracker.calculateVelocity()
                        motion = scope.launch {
                            coroutineScope {
                                launch {
                                    var last = 0f
                                    AnimationState(0f, v.y).animateDecay(decay) { zoom.panBy(0f, value - last); last = value }
                                }
                                launch {
                                    var last = 0f
                                    AnimationState(0f, v.x).animateDecay(decay) { zoom.panBy(value - last, 0f); last = value }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val layout = remember(pages, vw, padPx) { PageLayout.compute(pages.aspectRatios, vw, padPx) }
        SideEffect { zoom.attach(vw, vh, layout) }

        val z = zoom.value
        val left = zoom.contentLeft
        val top = -zoom.scrollY
        // Whole-page bitmaps follow zoom in 0.25 steps (up to viewport width); higher zoom uses detail tiles.
        val baseScale = min(1f, ceil(z * 4f) / 4f)
        val baseWidthPx = (layout.pageWidth * baseScale).roundToInt()

        var currentPage = 1
        val centerY = (zoom.scrollY + vh / 2f) / z
        for (i in 0 until pages.pageCount) {
            if (centerY >= layout.tops[i] - layout.pad) currentPage = i + 1
            val y = top + layout.tops[i] * z
            val h = layout.heights[i] * z
            if (y > vh || y + h < 0f) continue
            key(i) {
                PdfPage(
                    pages, i,
                    x = left + layout.pad * z, y = y,
                    w = layout.pageWidth * z, h = h,
                    viewportW = vw, viewportH = vh,
                    baseWidthPx = baseWidthPx,
                    detail = z > 1.05f,
                )
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.6f),
        ) {
            Text(
                "$currentPage / ${pages.pageCount}  ·  ${(z * 100).roundToInt()}%",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private class Tile(val bitmap: Bitmap, val pageWidth: Float, val left: Int, val top: Int)

/** Places content at an absolute pixel rect inside the parent. */
private fun Modifier.placeAt(x: Float, y: Float, w: Float, h: Float) = layout { measurable, _ ->
    val p = measurable.measure(Constraints.fixed(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)))
    layout(0, 0) { p.place(x.roundToInt(), y.roundToInt()) }
}

@Composable
private fun PdfPage(
    pages: PdfPages,
    index: Int,
    x: Float, y: Float, w: Float, h: Float,
    viewportW: Float, viewportH: Float,
    baseWidthPx: Int,
    detail: Boolean,
) {
    var base by remember(pages, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pages, index, baseWidthPx) {
        base = pages.render(index, baseWidthPx)
    }

    var tile by remember(pages, index) { mutableStateOf<Tile?>(null) }
    // Visible part of the page, in page-local px at the current zoom.
    val l = max(0f, -x).toInt()
    val t = max(0f, -y).toInt()
    val r = min(w, viewportW - x).toInt()
    val b = min(h, viewportH - y).toInt()
    if (detail && r > l && b > t) {
        LaunchedEffect(pages, index, w, l, t, r, b) {
            delay(150) // wait for the gesture to settle
            tile = Tile(pages.renderRegion(index, w, l, t, r - l, b - t), w, l, t)
        }
    }

    Box(Modifier.placeAt(x, y, w, h).background(Color.White)) {
        base?.let {
            Image(it.asImageBitmap(), "페이지 ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        val tl = tile
        if (detail && tl != null) {
            val s = w / tl.pageWidth
            Image(
                tl.bitmap.asImageBitmap(), null,
                Modifier.placeAt(tl.left * s, tl.top * s, tl.bitmap.width * s, tl.bitmap.height * s),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}
