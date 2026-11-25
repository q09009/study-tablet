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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.ai.TokenUsage
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
            AccountCard(vm, Modifier.width(400.dp))
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
    val apiKey by vm.apiKey.collectAsState()
    val usage by vm.usage.collectAsState()
    val busy by vm.keyBusy.collectAsState()
    val error by vm.keyError.collectAsState()
    val model by vm.model.collectAsState()
    var modelText by remember(model) { mutableStateOf(model) }
    var keyText by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    Card(modifier) {
        Column(
            Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OpenAI API", style = MaterialTheme.typography.titleMedium)
            val key = apiKey
            if (key == null) {
                Text("OpenAI 공식 API 키를 등록하면 PDF를 AI로 요약합니다. 키는 기기 안에 암호화되어 저장됩니다.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("API 키 (sk-...)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.saveApiKey(keyText) }, enabled = !busy && keyText.isNotBlank()) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("확인 후 저장")
                    }
                    OutlinedButton(onClick = { uriHandler.openUri("https://platform.openai.com/api-keys") }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text("키 발급")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                Text("연결됨 · ${key.take(7)}…${key.takeLast(4)}", fontWeight = FontWeight.SemiBold)
                UsageSection(
                    usage,
                    onOpenDashboard = { uriHandler.openUri("https://platform.openai.com/usage") },
                    onReset = vm::resetUsage,
                )
                OutlinedButton(onClick = { keyText = ""; vm.removeApiKey() }) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("키 삭제")
                }
            }
            OutlinedTextField(
                value = modelText,
                onValueChange = { modelText = it },
                label = { Text("모델") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("예: gpt-5-mini, gpt-5, gpt-4.1-mini") },
            )
            if (modelText != model) {
                Button(onClick = { vm.setModel(modelText) }) { Text("모델 저장") }
            }
        }
    }
}

@Composable
private fun UsageSection(usage: TokenUsage, onOpenDashboard: () -> Unit, onReset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("이 기기 사용량 (토큰)", style = MaterialTheme.typography.labelLarge)
        Text("오늘: 입력 ${fmt(usage.todayInput)} · 출력 ${fmt(usage.todayOutput)}", style = MaterialTheme.typography.bodySmall)
        Text("이번 달: 입력 ${fmt(usage.monthInput)} · 출력 ${fmt(usage.monthOutput)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenDashboard) { Text("전체 사용량·비용 보기") }
            TextButton(onClick = onReset) { Text("초기화") }
        }
    }
}

private fun fmt(n: Long): String = "%,d".format(n)