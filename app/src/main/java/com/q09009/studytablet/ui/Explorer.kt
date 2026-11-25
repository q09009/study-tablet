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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
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
import com.q09009.studytablet.data.FileKind
import com.q09009.studytablet.data.FsNode

private data class TreeItem(val node: FsNode, val depth: Int)

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
        if (rows.isEmpty()) {
            Text("비어 있는 폴더입니다. 위의 버튼으로 메모를 만들거나 ⋮ 메뉴에서 PDF를 가져오세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            items(rows, key = { it.node.uri.toString() }) { row ->
                TreeRow(
                    vm, row,
                    isExpanded = row.node.uri in expanded,
                    highlighted = row.node.uri == activeUri,
                    selected = row.node.uri == selected?.uri,
                )
            }
        }
    }

    if (newFolder) {
        NameDialog("새 폴더", "", onDismiss = { newFolder = false }) { vm.newFolder(it); newFolder = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(vm: AppViewModel, row: TreeItem, isExpanded: Boolean, highlighted: Boolean, selected: Boolean) {
    val n = row.node
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val bg = when {
        highlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        selected -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(MaterialTheme.shapes.small)
                .background(bg)
                .combinedClickable(onClick = { vm.select(n) }, onLongClick = { menu = true })
                .padding(start = (8 + row.depth * 16).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (n.isDir) {
                Icon(if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp))
            } else {
                Spacer(Modifier.width(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            val (icon, tint) = iconFor(n.kind)
            Icon(icon, null, Modifier.size(18.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlighted) FontWeight.Medium else null,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
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
            DropdownMenuItem(text = { Text("삭제") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { menu = false; delete = true })
        }
    }
    if (rename) {
        NameDialog("이름 변경", n.name, onDismiss = { rename = false }) { vm.rename(n, it); rename = false }
    }
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
