package com.q09009.studytablet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.data.FsNode

/** Lets the user browse the workspace folders and pick where to move [node]. */
@Composable
fun MoveDialog(vm: AppViewModel, node: FsNode, onDismiss: () -> Unit) {
    val root by vm.root.collectAsState()
    val r = root ?: return onDismiss()
    // Breadcrumb stack of (uri, name); the last entry is the folder being shown.
    var stack by remember { mutableStateOf(listOf(r.uri to r.name)) }
    var folders by remember { mutableStateOf<List<FsNode>?>(null) }
    val current = stack.last().first

    LaunchedEffect(current) {
        folders = null
        folders = runCatching { vm.subfolders(current) }.getOrDefault(emptyList()).filter { it.uri != node.uri }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("'${node.name}' 이동", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = stack.size > 1, onClick = { stack = stack.dropLast(1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "상위 폴더")
                    }
                    Text(stack.joinToString(" / ") { it.second }, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    val list = folders
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        list.isEmpty() -> Text("하위 폴더 없음", Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn {
                            items(list, key = { it.uri.toString() }) { f ->
                                Row(
                                    Modifier.fillMaxWidth().height(44.dp)
                                        .clickable { stack = stack + (f.uri to f.name) }
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = vm.canMove(node, current), onClick = { vm.moveTo(node, current); onDismiss() }) {
                Text("여기로 이동")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
