package com.q09009.studytablet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shows the AI's proposed subject for each unsorted PDF; nothing moves until the user confirms. */
@Composable
fun OrganizeDialog(vm: AppViewModel) {
    val s by vm.organize.collectAsState()
    if (!s.open) return
    val count = s.items.count { it.checked && it.subject.isNotBlank() }
    AlertDialog(
        onDismissRequest = { if (!s.running) vm.dismissOrganize() },
        title = { Text("AI 과목 정리") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    s.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(s.status ?: "분석 중…")
                    }
                    s.items.isNotEmpty() -> Text(
                        "확인한 PDF를 과목 폴더로 옮깁니다. 같은 이름의 요약(.md)도 함께 옮겨요. 과목은 직접 고칠 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (s.items.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(s.items) { i, item ->
                            OrganizeRow(item, s.subjects, isNew = item.subject !in s.existing, enabled = !s.running) {
                                vm.updateOrganize(i, it)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !s.running && count > 0, onClick = vm::applyOrganize) { Text("${count}개 이동") }
        },
        dismissButton = { TextButton(onClick = vm::dismissOrganize) { Text("취소") } },
    )
}

@Composable
private fun OrganizeRow(
    item: OrganizeItem,
    subjects: List<String>,
    isNew: Boolean,
    enabled: Boolean,
    onChange: (OrganizeItem) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(item.checked, { onChange(item.copy(checked = it)) }, enabled = enabled)
        Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp), tint = Color(0xFFE5534B))
        Spacer(Modifier.width(8.dp))
        Text(item.file.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(200.dp)) {
            OutlinedTextField(
                value = item.subject,
                onValueChange = { onChange(item.copy(subject = it)) },
                singleLine = true,
                enabled = enabled && item.checked,
                textStyle = MaterialTheme.typography.bodyMedium,
                supportingText = if (isNew && item.subject.isNotBlank()) {
                    { Text("새 폴더") }
                } else null,
                trailingIcon = {
                    IconButton(onClick = { menu = true }, enabled = enabled && item.checked) {
                        Icon(Icons.Default.ArrowDropDown, "과목 선택")
                    }
                },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                subjects.forEach { sub ->
                    DropdownMenuItem(text = { Text(sub) }, onClick = { menu = false; onChange(item.copy(subject = sub)) })
                }
            }
        }
    }
}
