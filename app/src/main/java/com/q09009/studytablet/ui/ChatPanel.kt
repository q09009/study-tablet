package com.q09009.studytablet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.ai.ChatMessage

/** Simple free-form Q&A with the model. Shown on demand from the activity bar. */
@Composable
fun ChatPanel(vm: AppViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val chat by vm.chat.collectAsState()
    val account by vm.account.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()

    val itemCount = chat.messages.size + if (chat.running) 1 else 0
    LaunchedEffect(itemCount, chat.streaming.length / 200) {
        if (itemCount > 0) list.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI에게 질문", style = MaterialTheme.typography.titleMedium)
            ModelPicker(vm, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp), compact = true)
            IconButton(onClick = vm::clearChat, enabled = chat.messages.isNotEmpty() || chat.running) {
                Icon(Icons.Default.DeleteSweep, "대화 지우기")
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기") }
        }
        HorizontalDivider()

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (chat.messages.isEmpty() && !chat.running) {
                item {
                    Text(
                        if (account == null) "왼쪽 아래 설정에서 ChatGPT로 로그인하면 질문할 수 있습니다."
                        else "모르는 개념을 바로 물어보세요.\n예: \"라운드 로빈 스케줄링이 뭐야?\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(chat.messages) { _, m -> Bubble(m.role == ChatMessage.Role.USER, m.text) }
            if (chat.running) {
                item {
                    if (chat.streaming.isEmpty()) {
                        CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Bubble(false, chat.streaming)
                    }
                }
            }
            chat.error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val send = {
                if (input.isNotBlank()) {
                    vm.sendChat(input)
                    input = ""
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("질문 입력") },
                maxLines = 5,
                enabled = account != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!chat.running) send() }),
            )
            if (chat.running) {
                IconButton(onClick = vm::stopChat) { Icon(Icons.Default.Stop, "중지") }
            } else {
                IconButton(onClick = send, enabled = account != null && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "보내기")
                }
            }
        }
    }
}

@Composable
private fun Bubble(isUser: Boolean, text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        val color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        Box(
            Modifier
                .widthIn(max = if (isUser) 360.dp else 640.dp)
                .background(color, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (isUser) Text(text) else MarkdownView(text)
        }
    }
}
