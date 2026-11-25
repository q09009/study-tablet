package com.q09009.studytablet.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun UpdateCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val token by vm.hasGhToken.collectAsState()
    val s by vm.update.collectAsState()
    val context = LocalContext.current
    var tokenText by remember { mutableStateOf("") }

    Card(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("앱 업데이트", style = MaterialTheme.typography.titleMedium)
            Text("현재 버전 ${vm.updater.currentVersion}", style = MaterialTheme.typography.bodySmall)

            if (token == null) {
                Text(
                    "비공개 저장소의 GitHub 릴리즈에서 업데이트를 받으려면 읽기 전용 토큰이 필요합니다. " +
                        "(Fine-grained token → Repository: study-tablet → Contents: Read-only)",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("GitHub 토큰") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveGhToken(tokenText); tokenText = "" }, enabled = tokenText.isNotBlank()) {
                        Text("저장")
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/settings/personal-access-tokens/new")))
                    }) { Text("토큰 만들기") }
                }
                return@Column
            }

            val release = s.release
            when {
                s.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("확인 중…")
                }
                s.progress != null -> Column {
                    Text("다운로드 중… ${(s.progress!! * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { s.progress!! }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                release != null -> {
                    Text("새 버전 ${release.version} 사용 가능", style = MaterialTheme.typography.bodyMedium)
                    if (release.notes.isNotBlank()) {
                        Text(release.notes.take(400), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = {
                        if (!vm.installUpdate()) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")))
                        }
                    }) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("업데이트 설치")
                    }
                    if (!vm.updater.canInstall()) {
                        Text("처음 한 번은 '이 출처 허용'을 켜야 합니다. 켠 뒤 다시 눌러주세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                s.upToDate -> Text("최신 버전입니다.", style = MaterialTheme.typography.bodyMedium)
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.checkUpdate() }, enabled = !s.checking && s.progress == null) {
                    Text("업데이트 확인")
                }
                TextButton(onClick = vm::removeGhToken) { Text("토큰 삭제") }
            }
        }
    }
}
