package com.q09009.studytablet.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.pdf.PdfPages
import kotlin.math.roundToInt

class PdfZoom {
    var value by mutableStateOf(1f)
    fun zoomIn() { value = (value + 0.25f).coerceAtMost(MAX) }
    fun zoomOut() { value = (value - 0.25f).coerceAtLeast(MIN) }
    fun reset() { value = 1f }
    companion object { const val MIN = 1f; const val MAX = 4f }
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
            else -> PdfPageList(result.getOrThrow(), zoom)
        }
    }
}

@Composable
private fun PdfPageList(pages: PdfPages, zoom: PdfZoom) {
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(zoom) {
                // Two-finger pinch zoom; single-finger drags fall through to scrolling.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                zoom.value = (zoom.value * z).coerceIn(PdfZoom.MIN, PdfZoom.MAX)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val viewport = maxWidth
        val density = LocalDensity.current
        // Render resolution follows zoom in 0.5 steps to avoid re-rendering on every pinch frame.
        val renderStep = (zoom.value * 2).roundToInt() / 2f
        val renderWidthPx = with(density) { (viewport * renderStep).roundToPx() }

        Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = zoom.value > 1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(viewport * zoom.value).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pages.pageCount) { index ->
                    PdfPageImage(pages, index, renderWidthPx)
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.6f),
        ) {
            Text(
                "$currentPage / ${pages.pageCount}" + if (zoom.value != 1f) "  ·  ${(zoom.value * 100).roundToInt()}%" else "",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PdfPageImage(pages: PdfPages, index: Int, widthPx: Int) {
    var bitmap by remember(pages, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pages, index, widthPx) {
        bitmap = pages.render(index, widthPx)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(pages.aspectRatios[index])
            .background(Color.White)
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "페이지 ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}
