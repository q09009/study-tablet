package com.q09009.studytablet.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.ai.CodexUsage
import com.q09009.studytablet.ai.UsageWindow
import java.text.DateFormat
import java.util.Date

@Composable
fun AppRoot(vm: AppViewModel, onPdfPicked: (Uri) -> Unit) {
    val doc by vm.doc.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPdfPicked)
    }
    val openPicker = { picker.launch(arrayOf("application/pdf")) }

    val current = doc
    if (current == null) {
        HomeScreen(vm, onOpenPdf = openPicker)
    } else {
        BackHandler { vm.closeDoc() }
        DocumentScreen(vm, current, onBack = vm::closeDoc)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(vm: AppViewModel, onOpenPdf: () -> Unit) {
    val recent by vm.recent.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Study Tablet") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenPdf,
                icon = { Icon(Icons.Default.FolderOpen, null) },
                text = { Text("PDF 열기") },
            )
        },
    ) { pad ->
        Row(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AccountCard(vm, Modifier.fillMaxWidth())
                UpdateCard(vm, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f)) {
                Text("최근 문서", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (recent.isEmpty()) {
                    Text("아직 연 문서가 없습니다. 오른쪽 아래 버튼으로 PDF를 열어보세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn {
                    items(recent, key = { it.uri.toString() }) { d ->
                        ListItem(
                            modifier = Modifier.clickable { vm.openDoc(d.uri, d.name) },
                            leadingContent = { Icon(Icons.Default.Description, null) },
                            headlineContent = { Text(d.name) },
                            supportingContent = {
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.openedAt)))
                            },
                            trailingContent = {
                                IconButton(onClick = { vm.removeRecent(d.uri) }) { Icon(Icons.Default.Close, "삭제") }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val account by vm.account.collectAsState()
    val usage by vm.usage.collectAsState()
    val busy by vm.loginBusy.collectAsState()
    val error by vm.loginError.collectAsState()
    val model by vm.model.collectAsState()
    var modelText by remember(model) { mutableStateOf(model) }

    Card(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ChatGPT 계정 (Codex)", style = MaterialTheme.typography.titleMedium)
            val acc = account
            if (acc == null) {
                Text("ChatGPT 플랜으로 로그인하면 Codex 사용량으로 PDF를 요약합니다.",
                    style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("브라우저에서 로그인을 완료하세요…", Modifier.weight(1f))
                        OutlinedButton(onClick = vm::cancelLogin) { Text("취소") }
                    }
                } else {
                    Button(onClick = vm::login, modifier = Modifier.fillMaxWidth()) { Text("ChatGPT로 로그인") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                Text(acc.email ?: "로그인됨", fontWeight = FontWeight.SemiBold)
                acc.planType?.let { Text("플랜: $it", style = MaterialTheme.typography.bodySmall) }
                UsageSection(usage, onRefresh = vm::refreshUsage)
                OutlinedButton(onClick = vm::logout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("로그아웃")
                }
            }
            OutlinedTextField(
                value = modelText,
                onValueChange = { modelText = it },
                label = { Text("모델") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("예: gpt-5, gpt-5-codex") },
            )
            if (modelText != model) {
                Button(onClick = { vm.setModel(modelText) }) { Text("모델 저장") }
            }
        }
    }
}

@Composable
private fun UsageSection(usage: CodexUsage?, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("사용량", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "새로고침") }
        }
        if (usage == null || (usage.primary == null && usage.secondary == null)) {
            Text("사용량 정보 없음 (요약을 한 번 실행하면 표시됩니다)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            usage.primary?.let { UsageBar("단기 한도", it) }
            usage.secondary?.let { UsageBar("주간 한도", it) }
        }
    }
}

@Composable
private fun UsageBar(label: String, w: UsageWindow) {
    Column {
        val reset = w.resetsInSeconds?.let { " · ${formatDuration(it)} 후 초기화" }.orEmpty()
        Text("$label: ${"%.0f".format(w.usedPercent)}% 사용$reset", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(
            progress = { (w.usedPercent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h >= 24 -> "${h / 24}일 ${h % 24}시간"
        h > 0 -> "${h}시간 ${m}분"
        else -> "${m}분"
    }
}
