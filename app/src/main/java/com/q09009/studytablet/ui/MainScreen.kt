package com.q09009.studytablet.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.record.RecorderService

private enum class SideView { EXPLORER, SETTINGS }

@Composable
fun AppRoot(vm: AppViewModel) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setWorkspace)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val root by vm.root.collectAsState()
    val update by vm.update.collectAsState()
    val recording by RecorderService.state.collectAsState()
    val message by vm.message.collectAsState()
    var side by rememberSaveable { mutableStateOf<SideView?>(SideView.EXPLORER) }
    val editor by vm.editor.collectAsState()
    val chatOpen = editor.findPane(TabContent.Chat.key) >= 0
    val recorderOpen = editor.findPane(TabContent.Recorder.key) >= 0
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            val wide = maxWidth >= 840.dp
            val sidePanel: @Composable (Modifier) -> Unit = { m ->
                when (side) {
                    SideView.EXPLORER -> Explorer(
                        vm,
                        onPickFolder = { folderPicker.launch(root?.uri) },
                        onImport = { importer.launch(arrayOf("application/pdf")) },
                        modifier = m,
                    )
                    SideView.SETTINGS -> SettingsPanel(vm, m)
                    null -> {}
                }
            }

            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(64.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ActivityIcon(Icons.Default.Folder, "탐색기", side == SideView.EXPLORER) {
                        side = if (side == SideView.EXPLORER) null else SideView.EXPLORER
                    }
                    ActivityIcon(Icons.Default.Forum, "AI에게 질문", chatOpen) {
                        if (chatOpen) vm.closeChat() else vm.openChat()
                    }
                    ActivityIcon(
                        if (recording != null) Icons.Default.FiberManualRecord else Icons.Default.Mic,
                        "녹음", recorderOpen,
                        tint = if (recording != null) MaterialTheme.colorScheme.error else null,
                    ) { vm.openRecorder() }
                    Spacer(Modifier.weight(1f))
                    ActivityIcon(Icons.Default.Settings, "설정", side == SideView.SETTINGS,
                        badge = update.release != null) {
                        side = if (side == SideView.SETTINGS) null else SideView.SETTINGS
                    }
                }
                if (wide && side != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        sidePanel(Modifier.width(300.dp).fillMaxHeight())
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                EditorArea(vm, singlePane = !wide, modifier = Modifier.weight(1f).fillMaxHeight())
            }

            // Narrow screens: the sidebar floats over the editor.
            if (!wide) {
                AnimatedVisibility(side != null, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier.fillMaxSize().padding(start = 64.dp).background(Color.Black.copy(alpha = 0.3f))
                            .clickable(remember { MutableInteractionSource() }, null) { side = null },
                    )
                }
                AnimatedVisibility(
                    side != null,
                    modifier = Modifier.padding(start = 64.dp),
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it },
                ) {
                    Surface(tonalElevation = 2.dp, shadowElevation = 8.dp) {
                        sidePanel(Modifier.width(300.dp).fillMaxHeight())
                    }
                }
            }


            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

@Composable
private fun ActivityIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tint: Color? = null,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (active) cs.primaryContainer else Color.Transparent, label = "activityBg")
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BadgedBox(badge = { if (badge) Badge() }) {
            Icon(icon, label, tint = tint ?: if (active) cs.onPrimaryContainer else cs.onSurfaceVariant)
        }
    }
}
