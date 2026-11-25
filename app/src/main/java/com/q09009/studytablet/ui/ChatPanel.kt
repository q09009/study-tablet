package com.q09009.studytablet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.ai.ChatMessage

private val EXAMPLES = listOf("이 개념을 쉽게 설명해줘", "예시를 들어줘", "시험에 나올 만한 포인트는?")

/** Free-form Q&A with the model, shown as an editor tab so it can sit beside a document. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(vm: AppViewModel, modifier: Modifier = Modifier) {
    val chat by vm.chat.collectAsState()
    val account by vm.account.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val cs = MaterialTheme.colorScheme

    val itemCount = chat.messages.size + if (chat.running) 1 else 0
    LaunchedEffect(itemCount, chat.streaming.length / 200) {
        if (itemCount > 0) list.animateScrollToItem(itemCount - 1)
    }
    val send = {
        if (input.isNotBlank() && !chat.running) {
            vm.sendChat(input)
            input = ""
        }
    }

    Column(modifier.background(cs.surface).imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelPicker(vm, Modifier.widthIn(max = 280.dp).weight(1f, fill = false), compact = true)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::clearChat, enabled = chat.messages.isNotEmpty() || chat.running) {
                Icon(Icons.Default.DeleteSweep, "대화 지우기")
            }
        }
        HorizontalDivider(color = cs.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (chat.messages.isEmpty() && !chat.running) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(56.dp).background(cs.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AutoAwesome, null, tint = cs.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("무엇이든 물어보세요", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (account == null) "설정에서 ChatGPT로 로그인하면 질문할 수 있습니다."
                        else "보고 있는 문서 이름이 함께 전달됩니다.",
                        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    )
                    if (account != null) {
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            EXAMPLES.forEach { e -> SuggestionChip(onClick = { input = e }, label = { Text(e) }) }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.widthIn(max = 820.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(chat.messages) { m -> Message(m.role == ChatMessage.Role.USER, m.text) }
                    if (chat.running) {
                        item {
                            if (chat.streaming.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar()
                                    Spacer(Modifier.width(12.dp))
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                Message(false, chat.streaming)
                            }
                        }
                    }
                    chat.error?.let { err -> item { Text(err, color = cs.error) } }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = 820.dp).fillMaxWidth()
                    .background(cs.surfaceContainerHigh, RoundedCornerShape(24.dp))
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (input.isEmpty()) {
                        Text(if (account == null) "로그인이 필요합니다" else "질문 입력",
                            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        enabled = account != null,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (chat.running) {
                    FilledIconButton(onClick = vm::stopChat) { Icon(Icons.Default.Stop, "중지") }
                } else {
                    FilledIconButton(onClick = send, enabled = account != null && input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "보내기", Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar() {
    Box(
        Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Message(isUser: Boolean, text: String) {
    if (isUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    } else {
        Row(Modifier.fillMaxWidth()) {
            Avatar()
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(top = 3.dp)) { MarkdownView(text) }
        }
    }
}