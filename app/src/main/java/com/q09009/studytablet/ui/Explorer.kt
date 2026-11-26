package com.q09009.studytablet.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import com.q09009.studytablet.data.FileKind
import com.q09009.studytablet.data.FsNode

private data class TreeItem(val node: FsNode, val depth: Int)

/** Shared drag-and-drop state for the file tree; positions are in window coordinates. */
private const val HOVER_OPEN_MS = 700

/** A dropped item flying from the pointer into its new folder; offsets are in window coordinates. */
private class Landing(val node: FsNode, val label: String, val target: Uri, val from: Offset, val to: Offset)

private class TreeDrag(val root: FsNode) {
    var node by mutableStateOf<FsNode?>(null)
    var pos by mutableStateOf(Offset.Zero)
    var box: LayoutCoordinates? = null
    val rows = mutableStateMapOf<Uri, Pair<Rect, FsNode>>()
    var landing by mutableStateOf<Landing?>(null)
    /** Folder that just received a drop and the moved item's name, briefly highlighted. */
    var landed by mutableStateOf<Pair<Uri, String>?>(null)

    fun hovered(): FsNode? = if (node == null) null else rows.values.firstOrNull { it.first.contains(pos) }?.second

    /** Folder under the pointer: a hovered folder, a hovered file's folder, or the root elsewhere. */
    fun target(): FsNode? {
        if (node == null) return null
        val b = box?.takeIf { it.isAttached }?.boundsInWindow()
        if (b != null && !b.contains(pos)) return null
        val hit = rows.values.firstOrNull { it.first.contains(pos) }?.second ?: return root
        if (hit.isDir) return hit
        val parent = hit.parent ?: return root
        return rows[parent]?.second ?: if (parent == root.uri) root else null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Explorer(vm: AppViewModel, onPickFolder: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val root by vm.root.collectAsState()
    val children by vm.children.collectAsState()
    val expanded by vm.expanded.collectAsState()
    val selected by vm.selected.collectAsState()
    val editor by vm.editor.collectAsState()
    var newFolder by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(
                root?.name ?: "탐색기",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (root != null) {
                IconButton(onClick = vm::newNote) { Icon(Icons.AutoMirrored.Filled.NoteAdd, "새 메모", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, "새 폴더", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "더보기", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("PDF 가져오기") },
                            leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                            onClick = { menuOpen = false; onImport() })
                        DropdownMenuItem(text = { Text("AI로 과목 정리") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                            onClick = { menuOpen = false; vm.startOrganize() })
                        DropdownMenuItem(text = { Text("새로고침") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { menuOpen = false; vm.refresh() })
                        DropdownMenuItem(text = { Text("작업 폴더 변경") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = { menuOpen = false; onPickFolder() })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val r = root
        if (r == null) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("공부 자료를 둘 폴더를 선택하세요. PDF, 요약, 메모, 녹음이 모두 이 폴더에 저장됩니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("폴더 선택")
                }
            }
            return@Column
        }

        val rows = remember(r, children, expanded) {
            buildList {
                fun add(dir: Uri, depth: Int) {
                    children[dir].orEmpty().forEach { n ->
                        add(TreeItem(n, depth))
                        if (n.isDir && n.uri in expanded) add(n.uri, depth + 1)
                    }
                }
                add(r.uri, 0)
            }
        }
        val activeUri = when (val t = editor.focusedTab) {
            is TabContent.Pdf -> t.uri
            is TabContent.Text -> t.uri
            is TabContent.Audio -> t.uri
            else -> null
        }
        val unsorted = children[r.uri].orEmpty().count { it.kind == FileKind.PDF }
        if (unsorted > 0) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .combinedClickable(onClick = vm::startOrganize)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text("정리 안 된 PDF ${unsorted}개", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("AI 정리", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (rows.isEmpty()) {
            Text("비어 있는 폴더입니다. 위의 버튼으로 메모를 만들거나 ⋮ 메뉴에서 PDF를 가져오세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        val drag = remember(r.uri) { TreeDrag(r) }
        val listState = rememberLazyListState()
        val density = LocalDensity.current
        val haptic = LocalHapticFeedback.current
        val dn = drag.node
        val dragTarget = drag.target()
        val canDrop = dn?.let { n -> dragTarget?.let { vm.canMove(n, it.uri) } } == true
        val targetLabel = dragTarget?.let { if (it.uri == r.uri) null else it.name }
        val hoverDir = dn?.let { n ->
            drag.hovered()?.takeIf { it.isDir && it.uri !in expanded && (vm.canMove(n, it.uri) || it.uri == n.parent) }
        }
        LaunchedEffect(hoverDir?.uri) {
            val d = hoverDir ?: return@LaunchedEffect
            delay(HOVER_OPEN_MS.toLong())
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.expandDir(d.uri)
        }
        LaunchedEffect(dn != null) {
            if (dn == null) return@LaunchedEffect
            val edge = with(density) { 72.dp.toPx() }
            val maxSpeed = with(density) { 1400.dp.toPx() }
            var last = withFrameNanos { it }
            while (drag.node != null) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val b = drag.box?.takeIf { it.isAttached } ?: continue
                val p = b.windowToLocal(drag.pos)
                if (p.x < 0f || p.x > b.size.width) continue
                val h = b.size.height.toFloat()
                val v = when {
                    p.y < edge -> -((edge - p.y) / edge).coerceAtMost(1f)
                    p.y > h - edge -> ((p.y - (h - edge)) / edge).coerceAtMost(1f)
                    else -> 0f
                }
                // Quadratic ramp: slow near the edge zone's start, fast at the very edge.
                if (v != 0f) listState.scrollBy(v * abs(v) * maxSpeed * dt)
            }
        }
        LaunchedEffect(drag.landed) {
            if (drag.landed != null) { delay(1600); drag.landed = null }
        }
        val ghostOffset = with(density) { Offset(14.dp.toPx(), -72.dp.toPx()) }
        val onDrop: (FsNode) -> Unit = { n ->
            val t = drag.target()
            if (t != null && vm.canMove(n, t.uri)) {
                val to = with(density) {
                    drag.rows[t.uri]?.first?.let { Offset(it.left + 28.dp.toPx(), it.center.y - 26.dp.toPx()) }
                        ?: drag.box?.takeIf { it.isAttached }?.localToWindow(Offset(16.dp.toPx(), 0f))
                }
                if (to != null) drag.landing = Landing(n, if (t.uri == r.uri) "" else t.name, t.uri, drag.pos, to)
                else drag.landed = t.uri to n.name
                vm.moveTo(n, t.uri)
            }
        }
        val rootDrop = canDrop && dragTarget?.uri == r.uri
        val cs = MaterialTheme.colorScheme
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { drag.box = it }
                .border(1.5.dp, if (rootDrop) cs.primary.copy(alpha = 0.6f) else Color.Transparent, MaterialTheme.shapes.medium)
                .background(if (rootDrop) cs.primary.copy(alpha = 0.06f) else Color.Transparent, MaterialTheme.shapes.medium)
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(6.dp)) {
                items(rows, key = { it.node.uri.toString() }) { row ->
                    val landed = drag.landed
                    TreeRow(
                        vm, row, drag,
                        isExpanded = row.node.uri in expanded,
                        highlighted = row.node.uri == activeUri,
                        selected = row.node.uri == selected?.uri,
                        dropTarget = canDrop && row.node.uri == dragTarget?.uri,
                        opening = row.node.uri == hoverDir?.uri,
                        received = landed?.first == row.node.uri,
                        justMoved = landed != null && row.node.parent == landed.first && row.node.name == landed.second,
                        onDrop = onDrop,
                    )
                }
            }
            val box = drag.box
            if (box != null && box.isAttached) {
                if (dn != null) {
                    val p = box.windowToLocal(drag.pos) + ghostOffset
                    DragGhost(
                        dn, if (dragTarget == null) GhostHint.Cancel else if (canDrop) GhostHint.Move else GhostHint.Blocked,
                        targetLabel, children[dn.uri]?.size,
                        Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) },
                    )
                }
                drag.landing?.let { l ->
                    key(l) {
                        LandingGhost(l, box, ghostOffset, children[l.node.uri]?.size) {
                            drag.landing = null
                            drag.landed = l.target to l.node.name
                        }
                    }
                }
            }
        }
    }

    OrganizeDialog(vm)
    if (newFolder) {
        NameDialog("새 폴더", "", onDismiss = { newFolder = false }) { vm.newFolder(it); newFolder = false }
    }
}

private enum class GhostHint { Move, Blocked, Cancel }

/** The card that follows the finger; folders get stacked sheets so they read as a bundle being carried. */
@Composable
private fun GhostCard(node: FsNode, hint: GhostHint, targetLabel: String?, childCount: Int?) {
    val shape = MaterialTheme.shapes.medium
    val cs = MaterialTheme.colorScheme
    val ok = hint == GhostHint.Move
    Box {
        if (node.isDir) {
            Box(Modifier.matchParentSize().offset(8.dp, 8.dp).clip(shape).background(cs.surfaceContainerHigh).border(1.dp, cs.outlineVariant, shape))
            Box(Modifier.matchParentSize().offset(4.dp, 4.dp).clip(shape).background(cs.surfaceContainerHighest).border(1.dp, cs.outlineVariant, shape))
        }
        Column(
            Modifier
                .shadow(10.dp, shape)
                .clip(shape)
                .background(if (ok) cs.primaryContainer else cs.surfaceContainerHighest)
                .border(1.dp, if (ok) cs.primary else cs.outlineVariant, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 320.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = iconFor(node.kind)
                Icon(icon, null, Modifier.size(20.dp), tint = tint ?: cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(node.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (node.isDir && childCount != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("항목 ${childCount}개", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            val (text, icon) = when (hint) {
                GhostHint.Move -> (if (targetLabel == null) "최상위로 이동" else "‘$targetLabel’(으)로 이동") to Icons.AutoMirrored.Filled.DriveFileMove
                GhostHint.Blocked -> "여기에는 놓을 수 없음" to Icons.Default.Block
                GhostHint.Cancel -> "놓으면 취소" to Icons.Default.Close
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = if (ok) cs.primary else cs.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(text, style = MaterialTheme.typography.labelMedium, color = if (ok) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DragGhost(node: FsNode, hint: GhostHint, targetLabel: String?, childCount: Int?, modifier: Modifier) {
    val lift = remember { Animatable(0f) }
    LaunchedEffect(Unit) { lift.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
    Box(modifier.graphicsLayer {
        val s = 0.8f + 0.2f * lift.value
        scaleX = s; scaleY = s
        rotationZ = -3f * lift.value
        alpha = lift.value.coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(0f, 0.5f)
    }) { GhostCard(node, hint, targetLabel, childCount) }
}

/** After a drop the card shrinks and flies into the destination folder's row. */
@Composable
private fun LandingGhost(l: Landing, box: LayoutCoordinates, ghostOffset: Offset, childCount: Int?, onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(340, easing = FastOutSlowInEasing)); onDone() }
    val from = box.windowToLocal(l.from) + ghostOffset
    val to = box.windowToLocal(l.to)
    Box(
        Modifier
            .offset { val p = lerp(from, to, t.value); IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
            .graphicsLayer {
                val s = 1f - 0.8f * t.value
                scaleX = s; scaleY = s
                rotationZ = -3f * (1f - t.value)
                alpha = 1f - t.value * t.value
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    ) { GhostCard(l.node, GhostHint.Move, l.label.ifEmpty { null }, childCount) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    vm: AppViewModel, row: TreeItem, drag: TreeDrag,
    isExpanded: Boolean, highlighted: Boolean, selected: Boolean, dropTarget: Boolean,
    opening: Boolean, received: Boolean, justMoved: Boolean,
    onDrop: (FsNode) -> Unit,
) {
    val n = row.node
    val haptic = LocalHapticFeedback.current
    val pinnable = LocalPinnableContainer.current
    val cs = MaterialTheme.colorScheme
    val isSource = drag.node?.uri == n.uri
    val contentAlpha = if (isSource) 0.35f else 1f
    val bump = remember { Animatable(1f) }
    LaunchedEffect(received) {
        if (received) { bump.animateTo(1.4f, tween(110)); bump.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f)) }
    }
    val hoverScale = if (dropTarget || opening) 1.15f else 1f
    val flash = remember { Animatable(0f) }
    val enter = remember { Animatable(1f) }
    LaunchedEffect(justMoved) {
        if (justMoved) {
            enter.snapTo(0f)
            flash.snapTo(1f)
            enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
            flash.animateTo(0f, tween(900))
        }
    }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(n.uri) { onDispose { drag.rows.remove(n.uri) } }
    var menu by remember { mutableStateOf(false) }
    val cloudReady = vm.ncAccount.collectAsState().value != null
    val uploading = vm.cloud.collectAsState().value.uploading[n.uri.toString()]
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    val bg = when {
        dropTarget -> cs.primary.copy(alpha = 0.18f)
        isSource -> Color.Transparent
        highlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        selected -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .graphicsLayer {
                    translationX = -24.dp.toPx() * (1f - enter.value)
                    alpha = 0.3f + 0.7f * enter.value
                }
                .clip(MaterialTheme.shapes.small)
                .background(bg)
                .drawBehind {
                    if (flash.value > 0f) drawRect(cs.primary.copy(alpha = 0.3f * flash.value))
                    if (isSource) drawRoundRect(
                        cs.outline,
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                .then(if (dropTarget) Modifier.border(1.5.dp, cs.primary, MaterialTheme.shapes.small) else Modifier)
                .onGloballyPositioned { c -> coords = c; drag.rows[n.uri] = c.boundsInWindow() to n }
                .pointerInput(n.uri) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val long = awaitLongPressOrCancellation(down.id)
                        if (long == null) {
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && up.changedToUp() && !up.isConsumed) { up.consume(); vm.select(n) }
                            return@awaitEachGesture
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val c = coords
                        val toWindow = { o: Offset -> c?.takeIf { it.isAttached }?.localToWindow(o) ?: o }
                        var moved = 0f
                        var dragged = false
                        // Pinning keeps this row composed while auto-scroll moves it off screen,
                        // so the gesture isn't cancelled mid-drag.
                        var pin: PinnableContainer.PinnedHandle? = null
                        try {
                            val ended = drag(long.id) { ch ->
                                moved += ch.positionChange().getDistance()
                                if (moved > slop && !dragged) {
                                    dragged = true
                                    drag.node = n
                                    pin = pinnable?.pin()
                                }
                                drag.pos = toWindow(ch.position)
                                ch.consume()
                            }
                            if (ended && dragged) onDrop(n)
                        } finally {
                            pin?.release()
                            if (dragged) drag.node = null
                        }
                        if (!dragged) menu = true
                    }
                }
                .padding(start = (8 + row.depth * 16).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (n.isDir) {
                Icon(if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp).alpha(contentAlpha))
            } else {
                Spacer(Modifier.width(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            val (icon0, tint) = iconFor(n.kind)
            val icon = if (n.isDir && (dropTarget || opening || received)) Icons.Default.FolderOpen else icon0
            Icon(icon, null, Modifier.size(18.dp).alpha(contentAlpha).graphicsLayer {
                val k = bump.value * hoverScale
                scaleX = k; scaleY = k
            }, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.alpha(contentAlpha),
                fontWeight = if (highlighted) FontWeight.Medium else null,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        }
        if (opening) {
            val prog = remember { Animatable(0f) }
            LaunchedEffect(Unit) { prog.animateTo(1f, tween(HOVER_OPEN_MS, easing = LinearEasing)) }
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp).fillMaxWidth(prog.value).height(2.dp)
                .clip(CircleShape).background(cs.primary))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!n.isDir) {
                DropdownMenuItem(text = { Text("옆에 열기") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                    onClick = { menu = false; vm.open(n, toSide = true) })
            }
            DropdownMenuItem(text = { Text("이름 변경") },
                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("이동…") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                onClick = { menu = false; move = true })
            if (cloudReady) {
                DropdownMenuItem(text = { Text("Nextcloud에 올리기") },
                    leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                    enabled = uploading == null,
                    onClick = { menu = false; vm.uploadToCloud(n) })
            }
            DropdownMenuItem(text = { Text("삭제") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { menu = false; delete = true })
        }
    }
    if (rename) {
        NameDialog("이름 변경", n.name, onDismiss = { rename = false }) { vm.rename(n, it); rename = false }
    }
    if (move) MoveDialog(vm, n, onDismiss = { move = false })
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("삭제") },
            text = { Text(if (n.isDir) "'${n.name}' 폴더와 안의 모든 파일을 삭제할까요?" else "'${n.name}' 을(를) 삭제할까요?") },
            confirmButton = { TextButton(onClick = { vm.delete(n); delete = false }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun iconFor(kind: FileKind): Pair<ImageVector, Color?> = when (kind) {
    FileKind.DIR -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
    FileKind.PDF -> Icons.Default.PictureAsPdf to Color(0xFFE5534B)
    FileKind.TEXT -> Icons.Default.Description to Color(0xFF3B82F6)
    FileKind.AUDIO -> Icons.Default.AudioFile to Color(0xFF9B6BDF)
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile to null
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Preselect the base name (without extension) like desktop editors do.
    val end = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = value.text.isNotBlank(), onClick = { onConfirm(value.text) }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
