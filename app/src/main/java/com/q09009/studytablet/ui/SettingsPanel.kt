package com.q09009.studytablet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.ai.CodexUsage
import com.q09009.studytablet.ai.UsageWindow

@Composable
fun SettingsPanel(vm: AppViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        AccountCard(vm, Modifier.fillMaxWidth())
        ThemeCard(vm, Modifier.fillMaxWidth())
        UpdateCard(vm, Modifier.fillMaxWidth())
    }
}

/** Flat, outlined card used for every settings section. */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val mode by vm.theme.collectAsState()
    SettingsCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("화면", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val icons = listOf(Icons.Default.BrightnessAuto, Icons.Default.LightMode, Icons.Default.DarkMode)
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { vm.setTheme(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        icon = { SegmentedButtonDefaults.Icon(mode == m) { Icon(icons[i], null, Modifier.size(18.dp)) } },
                        label = { Text(m.label) },
                    )
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

    SettingsCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ChatGPT 계정 (Codex)", style = MaterialTheme.typography.titleSmall)
            val acc = account
            if (acc == null) {
                Text("ChatGPT 플랜으로 로그인하면 구독 사용량으로 요약과 질문을 쓸 수 있습니다.",
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
            }
            HorizontalDivider()
            ModelPicker(vm, Modifier.fillMaxWidth())
            if (acc != null) {
                HorizontalDivider()
                UsageSection(usage, onRefresh = vm::refreshUsage)
                OutlinedButton(onClick = vm::logout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("로그아웃")
                }
            }
        }
    }
}

/** Dropdown of models available to the signed-in plan, plus a custom entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(vm: AppViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val model by vm.model.collectAsState()
    val models by vm.models.collectAsState()
    val account by vm.account.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf<String?>(null) }
    val current = models.options.firstOrNull { it.slug == model }

    Column(modifier) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            if (compact) {
                Row(
                    Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(current?.displayName ?: model, style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                }
            } else OutlinedTextField(
                value = current?.displayName ?: model,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = if (compact) null else ({ Text("AI 모델") }),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.options.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.displayName, fontWeight = if (m.slug == model) FontWeight.Bold else null)
                                m.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        onClick = { vm.setModel(m.slug); expanded = false },
                    )
                }
                if (current == null) {
                    DropdownMenuItem(text = { Text("$model (직접 입력)", fontWeight = FontWeight.Bold) },
                        onClick = { expanded = false })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("직접 입력…") }, onClick = { custom = model; expanded = false })
                if (account != null) {
                    DropdownMenuItem(
                        text = { Text(if (models.loading) "목록 불러오는 중…" else "목록 새로고침") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        enabled = !models.loading,
                        onClick = { vm.loadModels(); expanded = false },
                    )
                }
            }
        }
        if (!compact) {
            val note = when {
                account == null -> "로그인하면 사용 가능한 모델 목록을 불러옵니다."
                models.error != null -> "목록을 불러오지 못해 기본 목록을 표시합니다: ${models.error}"
                else -> null
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    custom?.let { text ->
        AlertDialog(
            onDismissRequest = { custom = null },
            title = { Text("모델 직접 입력") },
            text = {
                OutlinedTextField(value = text, onValueChange = { custom = it }, singleLine = true,
                    label = { Text("모델 slug (예: gpt-5)") })
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { vm.setModel(text); custom = null }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { custom = null }) { Text("취소") } },
        )
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
            Text("사용량 정보 없음 (AI를 한 번 사용하면 표시됩니다)",
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
