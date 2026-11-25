package com.q09009.studytablet.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.q09009.studytablet.ai.ChatMessage
import com.q09009.studytablet.ai.CodexClient
import com.q09009.studytablet.ai.ModelOption
import com.q09009.studytablet.ai.PdfSummarizer
import com.q09009.studytablet.auth.CodexAuth
import com.q09009.studytablet.data.FileKind
import com.q09009.studytablet.data.FsNode
import com.q09009.studytablet.data.Store
import com.q09009.studytablet.data.Workspace
import com.q09009.studytablet.record.RecorderService
import com.q09009.studytablet.update.ReleaseInfo
import com.q09009.studytablet.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SummaryState(
    val markdown: String? = null,
    val running: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

data class ModelsState(
    val options: List<ModelOption> = FALLBACK_MODELS,
    val loading: Boolean = false,
    val fromServer: Boolean = false,
    val error: String? = null,
)

val FALLBACK_MODELS = listOf(
    ModelOption("gpt-5", "gpt-5"),
    ModelOption("gpt-5-codex", "gpt-5-codex"),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    val store = Store(app)
    val auth = CodexAuth(app, http)
    val codex = CodexClient(auth, http)
    val ws = Workspace(app)

    val account = auth.account
    val usage = codex.usage

    // ---- Account & model ----

    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError
    private var loginJob: Job? = null

    private val _model = MutableStateFlow(store.model)
    val model: StateFlow<String> = _model

    private val _models = MutableStateFlow(ModelsState())
    val models: StateFlow<ModelsState> = _models

    // ---- Workspace tree ----

    private val _root = MutableStateFlow<FsNode?>(null)
    val root: StateFlow<FsNode?> = _root
    private val _children = MutableStateFlow<Map<Uri, List<FsNode>>>(emptyMap())
    val children: StateFlow<Map<Uri, List<FsNode>>> = _children
    private val _expanded = MutableStateFlow<Set<Uri>>(emptySet())
    val expanded: StateFlow<Set<Uri>> = _expanded
    private val _selected = MutableStateFlow<FsNode?>(null)
    /** Last tapped tree node; its folder is where new notes, imports and recordings go. */
    val selected: StateFlow<FsNode?> = _selected
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    // ---- Editor ----

    private val _editor = MutableStateFlow(EditorState.fromJson(store.editor))
    val editor: StateFlow<EditorState> = _editor

    private val _summaries = MutableStateFlow<Map<Uri, SummaryState>>(emptyMap())
    val summaries: StateFlow<Map<Uri, SummaryState>> = _summaries
    private val summaryJobs = mutableMapOf<Uri, Job>()

    // ---- Update ----

    val updater = Updater(app, http)
    private val _update = MutableStateFlow(UpdateState())
    val update: StateFlow<UpdateState> = _update
    private var updateJob: Job? = null

    init {
        if (account.value != null) {
            refreshUsage()
            loadModels()
        }
        checkUpdate(silent = true)
        store.workspace?.let { tree ->
            if (ws.hasPermission(tree)) openWorkspace(tree) else store.workspace = null
        }
        viewModelScope.launch {
            RecorderService.savedCount.drop(1).collect {
                RecorderService.saved.value?.let { refresh(it) }
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        _message.value = e.message ?: e.toString()
    }

    // ---- Workspace ----

    fun setWorkspace(tree: Uri) {
        try {
            ws.takePermission(tree)
        } catch (e: Exception) {
            fail(e); return
        }
        store.workspace = tree
        _children.value = emptyMap()
        _expanded.value = emptySet()
        _selected.value = null
        openWorkspace(tree)
    }

    private fun openWorkspace(tree: Uri) {
        viewModelScope.launch {
            try {
                val r = ws.root(tree)
                _root.value = r
                refresh(r.uri)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Folder that receives new files: the selected folder, the selected file's folder, or the root. */
    val targetDir: Uri?
        get() {
            val s = _selected.value
            return when {
                s == null -> _root.value?.uri
                s.isDir -> s.uri
                else -> s.parent ?: _root.value?.uri
            }
        }

    fun refresh(dir: Uri? = null) {
        viewModelScope.launch {
            try {
                if (dir == null) {
                    val r = _root.value ?: return@launch
                    (setOf(r.uri) + _expanded.value).forEach { d ->
                        _children.update { it + (d to ws.list(d)) }
                    }
                } else {
                    _children.update { it + (dir to ws.list(dir)) }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun select(node: FsNode) {
        _selected.value = node
        if (node.isDir) {
            if (node.uri in _expanded.value) {
                _expanded.update { set -> set.filterNot { it == node.uri || isInside(it, node.uri) }.toSet() }
            } else {
                _expanded.update { it + node.uri }
                refresh(node.uri)
            }
        } else {
            open(node)
        }
    }

    fun open(node: FsNode, toSide: Boolean = false) {
        val content = when (node.kind) {
            FileKind.PDF -> TabContent.Pdf(node.uri, node.name, node.parent)
            FileKind.TEXT -> TabContent.Text(node.uri, node.name, node.parent)
            FileKind.AUDIO -> TabContent.Audio(node.uri, node.name)
            else -> {
                _message.value = "이 형식은 열 수 없습니다: ${node.name}"
                return
            }
        }
        openTab(content, toSide)
    }

    fun openExternalPdf(uri: Uri) {
        openTab(TabContent.Pdf(uri, ws.displayName(uri), null))
    }

    private suspend fun uniqueName(dir: Uri, base: String, ext: String): String {
        val names = ws.list(dir).map { it.name }.toSet()
        var name = "$base.$ext"
        var i = 2
        while (name in names) name = "$base ($i).$ext".also { i++ }
        return name
    }

    private fun requireDir(): Uri? = targetDir ?: run {
        _message.value = "먼저 왼쪽에서 작업 폴더를 선택하세요."
        null
    }

    fun newNote() {
        val dir = requireDir() ?: return
        viewModelScope.launch {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.getDefault()).format(Date())
                val name = uniqueName(dir, "메모 $stamp", "md")
                val uri = ws.createFile(dir, name)
                expand(dir)
                refresh(dir)
                openTab(TabContent.Text(uri, name, dir))
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun newFolder(name: String) {
        val dir = requireDir() ?: return
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            try {
                ws.createDir(dir, clean)
                expand(dir)
                refresh(dir)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        val dir = requireDir() ?: return
        viewModelScope.launch {
            try {
                var last: TabContent.Pdf? = null
                uris.forEach { src ->
                    val display = ws.displayName(src)
                    val base = display.substringBeforeLast('.')
                    val ext = display.substringAfterLast('.', "pdf")
                    val name = uniqueName(dir, base, ext)
                    val uri = ws.importUri(dir, src, name, "application/pdf")
                    last = TabContent.Pdf(uri, name, dir)
                }
                expand(dir)
                refresh(dir)
                last?.let { openTab(it) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun expand(dir: Uri) {
        if (dir != _root.value?.uri) _expanded.update { it + dir }
    }

    fun rename(node: FsNode, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == node.name) return
        viewModelScope.launch {
            try {
                val newUri = ws.rename(node.uri, clean)
                if (node.isDir) {
                    forgetDir(node.uri)
                } else {
                    val tabs = _editor.value.panes.flatMap { it.tabs }
                    tabs.filter { tabUri(it) == node.uri }.forEach { t ->
                        val replaced = when (t) {
                            is TabContent.Pdf -> t.copy(uri = newUri, name = clean)
                            is TabContent.Text -> t.copy(uri = newUri, name = clean)
                            is TabContent.Audio -> t.copy(uri = newUri, name = clean)
                            else -> t
                        }
                        setEditor(_editor.value.replace(t.key, replaced))
                    }
                }
                if (_selected.value?.uri == node.uri) _selected.value = null
                node.parent?.let { refresh(it) } ?: refresh()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun delete(node: FsNode) {
        viewModelScope.launch {
            try {
                ws.delete(node.uri)
                if (node.isDir) forgetDir(node.uri)
                else _editor.value.panes.flatMap { it.tabs }.filter { tabUri(it) == node.uri }
                    .forEach { setEditor(_editor.value.closeKey(it.key)) }
                if (_selected.value?.uri == node.uri) _selected.value = null
                node.parent?.let { refresh(it) } ?: refresh()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Drops tree state and open tabs that live inside a renamed or deleted folder. */
    private fun forgetDir(dir: Uri) {
        _expanded.update { set -> set.filterNot { it == dir || isInside(it, dir) }.toSet() }
        _children.update { m -> m.filterKeys { it != dir && !isInside(it, dir) } }
        _editor.value.panes.flatMap { it.tabs }
            .filter { t -> tabUri(t)?.let { isInside(it, dir) } == true }
            .forEach { setEditor(_editor.value.closeKey(it.key)) }
    }

    private fun docId(uri: Uri) = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    private fun isInside(child: Uri, dir: Uri): Boolean {
        val c = docId(child) ?: return false
        val d = docId(dir) ?: return false
        return c.startsWith("$d/")
    }

    private fun tabUri(t: TabContent): Uri? = when (t) {
        is TabContent.Pdf -> t.uri
        is TabContent.Text -> t.uri
        is TabContent.Audio -> t.uri
        else -> null
    }

    suspend fun readText(uri: Uri): String = ws.readText(uri)

    /** Saves in the ViewModel scope so a save started while closing a tab still completes. */
    fun saveText(uri: Uri, text: String) {
        viewModelScope.launch {
            try {
                ws.writeText(uri, text)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    // ---- Editor ----

    private fun setEditor(s: EditorState) {
        _editor.value = s
        store.editor = s.toJson()
    }

    fun openTab(content: TabContent, toSide: Boolean = false) = setEditor(_editor.value.open(content, toSide))
    fun activateTab(pane: Int, key: String) = setEditor(_editor.value.activate(pane, key))
    fun focusPane(pane: Int) {
        if (_editor.value.focused != pane) setEditor(_editor.value.copy(focused = pane))
    }

    fun closeTab(pane: Int, key: String) {
        val tab = _editor.value.panes.getOrNull(pane)?.tabs?.firstOrNull { it.key == key }
        if (tab is TabContent.Summary) {
            summaryJobs[tab.pdf.uri]?.cancel()
        }
        setEditor(_editor.value.close(pane, key))
    }

    fun moveTab(pane: Int, key: String) = setEditor(_editor.value.moveToOther(pane, key))

    // ---- Summaries ----

    private fun summaryName(pdf: TabContent.Pdf) = "${pdf.name.substringBeforeLast('.')} 요약.md"
    private fun summaryDir(pdf: TabContent.Pdf) = pdf.parent ?: _root.value?.uri

    /** Opens the saved summary of [pdf] beside it, generating one first if needed (or if [regenerate]). */
    fun summarize(pdf: TabContent.Pdf, regenerate: Boolean = false) {
        val summaryKey = TabContent.Summary(pdf).key
        if (summaryJobs[pdf.uri]?.isActive == true) {
            openBeside(TabContent.Summary(pdf))
            return
        }
        viewModelScope.launch {
            val dir = summaryDir(pdf)
            val existing = runCatching { dir?.let { ws.find(it, summaryName(pdf)) } }.getOrNull()
            if (existing != null && !regenerate) {
                openBeside(TabContent.Text(existing.uri, existing.name, dir))
                return@launch
            }
            if (account.value == null) {
                _message.value = "설정에서 ChatGPT로 로그인하면 요약을 만들 수 있습니다."
                return@launch
            }
            existing?.let { setEditor(_editor.value.closeKey(TabContent.Text(it.uri, it.name, dir).key)) }
            openBeside(TabContent.Summary(pdf))
            summaryJobs[pdf.uri] = viewModelScope.launch { runSummary(pdf, dir, existing, summaryKey) }
        }
    }

    private fun openBeside(content: TabContent) {
        val e = _editor.value
        val pdfPane = e.focused
        val inOther = e.findPane(content.key).let { it >= 0 && it != pdfPane }
        setEditor(if (inOther) e.open(content) else e.open(content, toSide = true))
    }

    private suspend fun runSummary(pdf: TabContent.Pdf, dir: Uri?, existing: FsNode?, key: String) {
        fun set(f: (SummaryState) -> SummaryState) =
            _summaries.update { it + (pdf.uri to f(it[pdf.uri] ?: SummaryState())) }
        set { SummaryState(running = true, status = "준비 중…") }
        var last = 0L
        try {
            val md = PdfSummarizer.summarize(
                getApplication(), pdf.uri, codex, model.value,
                onProgress = { s -> set { it.copy(status = s) } },
                onPartial = { partial ->
                    val now = System.currentTimeMillis()
                    if (now - last > 250) {
                        last = now
                        set { it.copy(markdown = partial) }
                    }
                },
            ).trim()
            if (dir == null) {
                set { SummaryState(markdown = md, status = "작업 폴더를 선택하면 파일로 저장됩니다.") }
                return
            }
            val name = existing?.name ?: summaryName(pdf)
            val uri = existing?.uri ?: ws.createFile(dir, name)
            ws.writeText(uri, md + "\n")
            _summaries.update { it - pdf.uri }
            setEditor(_editor.value.replace(key, TabContent.Text(uri, name, dir)))
            refresh(dir)
        } catch (e: CancellationException) {
            _summaries.update { it - pdf.uri }
            throw e
        } catch (e: Exception) {
            set { it.copy(running = false, status = null, error = e.message ?: e.toString()) }
        }
    }

    fun cancelSummary(pdf: TabContent.Pdf) {
        summaryJobs[pdf.uri]?.cancel()
        setEditor(_editor.value.closeKey(TabContent.Summary(pdf).key))
    }

    // ---- Recording ----

    /** Returns false when there is no folder to save into. */
    fun startRecording(): Boolean {
        val dir = requireDir() ?: return false
        val name = "녹음 " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date()) + ".m4a"
        val title = _editor.value.focusedTab?.takeIf { it !is TabContent.Recorder }?.title ?: "강의 녹음"
        RecorderService.start(getApplication(), dir, name, title)
        return true
    }

    // ---- Update ----

    fun checkUpdate(silent: Boolean = false) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _update.value = UpdateState(checking = true)
            _update.value = try {
                val r = updater.latest()
                UpdateState(release = r.takeIf(updater::isNewer), upToDate = !updater.isNewer(r))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState(error = if (silent) null else e.message ?: e.toString())
            }
        }
    }

    /** Returns false when the user must first allow installs from this app. */
    fun installUpdate(): Boolean {
        val release = _update.value.release ?: return true
        if (!updater.canInstall()) return false
        if (updateJob?.isActive == true) return true
        updateJob = viewModelScope.launch {
            _update.update { it.copy(progress = 0f, error = null) }
            try {
                val apk = updater.download(release) { p -> _update.update { it.copy(progress = p) } }
                updater.install(apk)
                _update.update { it.copy(progress = null) }
            } catch (e: CancellationException) {
                _update.update { it.copy(progress = null) }
                throw e
            } catch (e: Exception) {
                _update.update { it.copy(progress = null, error = e.message ?: e.toString()) }
            }
        }
        return true
    }

    // ---- Account ----

    fun login() {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            _loginBusy.value = true
            _loginError.value = null
            try {
                auth.login()
                loadModels()
                codex.refreshUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loginError.value = e.message ?: e.toString()
            } finally {
                _loginBusy.value = false
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _loginBusy.value = false
    }

    fun logout() = auth.logout()

    fun refreshUsage() {
        viewModelScope.launch { codex.refreshUsage() }
    }

    fun setModel(value: String) {
        store.model = value
        _model.value = store.model
    }

    fun loadModels() {
        if (_models.value.loading) return
        viewModelScope.launch {
            _models.update { it.copy(loading = true, error = null) }
            try {
                val list = codex.models()
                if (list.isEmpty()) throw IllegalStateException("모델 목록이 비어 있습니다.")
                _models.value = ModelsState(options = list, fromServer = true)
                if (!store.modelChosen && list.none { it.slug == _model.value }) setModel(list.first().slug)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _models.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    // ---- Chat ----

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat
    private var chatJob: Job? = null

    fun sendChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _chat.value.running) return
        val history = _chat.value.messages + ChatMessage(ChatMessage.Role.USER, text)
        _chat.value = ChatState(messages = history, running = true)
        val docName = when (val t = _editor.value.focusedTab) {
            is TabContent.Pdf, is TabContent.Text, is TabContent.Summary -> t.title
            else -> null
        }
        chatJob = viewModelScope.launch {
            val reply = StringBuilder()
            var lastUpdate = 0L
            try {
                codex.chat(model.value, chatInstructions(docName), history) { delta ->
                    reply.append(delta)
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 150) {
                        lastUpdate = now
                        _chat.update { it.copy(streaming = reply.toString()) }
                    }
                }
                _chat.value = ChatState(messages = history + ChatMessage(ChatMessage.Role.ASSISTANT, reply.toString().trim()))
            } catch (e: CancellationException) {
                _chat.value = ChatState(messages = history.withPartial(reply))
                throw e
            } catch (e: Exception) {
                _chat.value = ChatState(messages = history.withPartial(reply), error = e.message ?: e.toString())
            }
        }
    }

    fun stopChat() {
        chatJob?.cancel()
    }

    fun clearChat() {
        chatJob?.cancel()
        _chat.value = ChatState()
    }

    private fun List<ChatMessage>.withPartial(reply: StringBuilder) =
        if (reply.isBlank()) this else this + ChatMessage(ChatMessage.Role.ASSISTANT, reply.toString().trim())

    private fun chatInstructions(docName: String?) = buildString {
        append("You are a friendly study assistant helping a student during a lecture. ")
        append("Answer concept questions clearly and concisely, with short examples when helpful. ")
        append("Reply in the user's language (default Korean). Use Markdown; use LaTeX-free plain notation for math.")
        if (docName != null) append(" The student is currently viewing lecture material titled \"$docName\".")
    }
}

data class UpdateState(
    val checking: Boolean = false,
    val release: ReleaseInfo? = null,
    val upToDate: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val streaming: String = "",
    val error: String? = null,
)
