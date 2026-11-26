package com.q09009.studytablet.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.data.CloudEntry

/** Browses the user's Nextcloud and downloads files into the explorer's selected folder. */
@Composable
fun CloudPanel(vm: AppViewModel, modifier: Modifier = Modifier) {
    val account by vm.ncAccount.collectAsState()
    val s by vm.cloud.collectAsState()
    val root by vm.root.collectAsState()
    val selected by vm.selected.collectAsState()
    val children by vm.children.collectAsState()

    LaunchedEffect(account) {
        if (account != null && !s.loaded && !s.loading) vm.openCloud(s.path)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Nextcloud", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (account != null) {
                IconButton(onClick = { vm.openCloud(s.path) }) {
                    Icon(Icons.Default.Refresh, "새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = vm::ncLogout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, "로그아웃", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val acc = account
        if (acc == null) {
            LoginForm(vm, s)
            return@Column
        }

        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = s.path != "/", onClick = { vm.openCloud(s.path.substringBeforeLast('/').ifEmpty { "/" }) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "상위 폴더")
            }
            Text(if (s.path == "/") acc.userId else s.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        val target = vm.targetDir
        val localNames = remember(children, target) { children[target].orEmpty().map { it.name }.toSet() }
        if (s.loaded && !s.loading && s.entries.isEmpty() && s.error == null) {
            Text("비어 있는 폴더입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            items(s.entries, key = { it.path }) { e ->
                CloudRow(vm, e, local = e.name in localNames, progress = s.downloading[e.path], enabled = root != null)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val targetName = when {
            root == null -> null
            selected == null -> root?.name
            selected!!.isDir -> selected!!.name
            selected!!.parent == root?.uri || selected!!.parent == null -> root?.name
            else -> children.values.flatten().firstOrNull { it.uri == selected!!.parent }?.name
        }
        Text(
            if (targetName == null) "탐색기에서 작업 폴더를 먼저 선택하세요." else "받을 위치: $targetName  ·  탐색기에서 폴더를 선택해 바꿀 수 있어요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LoginForm(vm: AppViewModel, s: CloudState) {
    var server by rememberSaveable { mutableStateOf(vm.nextcloud.lastServer) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nextcloud에 연결하면 서버의 자료를 태블릿 작업 폴더로 바로 받을 수 있어요. 브라우저에서 로그인하고 접근을 허용하면 됩니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = server, onValueChange = { server = it },
            label = { Text("서버 주소") }, placeholder = { Text("cloud.example.com 또는 http://192.168.0.10:8080") },
            supportingText = { Text("http:// 없이 쓰면 HTTPS로 먼저 시도하고, 안 되면 HTTP로 다시 시도합니다.") },
            singleLine = true, enabled = !s.loginBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (s.loginBusy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("브라우저에서 로그인을 기다리는 중…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = vm::cancelNcLogin, modifier = Modifier.fillMaxWidth()) { Text("취소") }
        } else {
            Button(onClick = { vm.ncLogin(server) }, enabled = server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Cloud, null)
                Spacer(Modifier.width(8.dp))
                Text("로그인")
            }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudRow(vm: AppViewModel, e: CloudEntry, local: Boolean, progress: Float?, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    onClick = { if (e.isDir) vm.openCloud(e.path) else if (enabled) vm.downloadCloud(e, openAfter = true) },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = cloudIcon(e)
                Icon(icon, null, Modifier.size(18.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (!e.isDir && e.size > 0) {
                        Text(formatSize(e.size), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    progress != null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    local && !e.isDir -> Icon(Icons.Default.CheckCircle, "받음", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    e.isDir -> {}
                    else -> Icon(Icons.Default.Download, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (progress != null && progress >= 0f && !e.isDir) {
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (e.isDir) "폴더 전체 받기" else "받기") },
                leadingIcon = { Icon(Icons.Default.Download, null) },
                enabled = enabled,
                onClick = { menu = false; vm.downloadCloud(e) },
            )
        }
    }
}

@Composable
private fun cloudIcon(e: CloudEntry): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color?> {
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when {
        e.isDir -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
        ext == "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFE5534B)
        ext in setOf("md", "markdown", "txt") -> Icons.Default.Description to Color(0xFF3B82F6)
        ext in setOf("m4a", "mp3", "wav", "aac", "ogg", "opus", "flac") -> Icons.Default.AudioFile to Color(0xFF9B6BDF)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to null
    }
}

private fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}
