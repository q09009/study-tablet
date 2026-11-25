package com.q09009.studytablet.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.q09009.studytablet.ai.ChatMessage
import com.q09009.studytablet.ai.CodexClient
import com.q09009.studytablet.ai.PdfSummarizer
import com.q09009.studytablet.auth.CodexAuth
import com.q09009.studytablet.data.RecentDoc
import com.q09009.studytablet.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class OpenDoc(val uri: Uri, val name: String)

data class SummaryState(
    val markdown: String? = null,
    val running: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    val store = Store(app)
    val auth = CodexAuth(app, http)
    val codex = CodexClient(auth, http)

    val account = auth.account
    val usage = codex.usage

    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError
    private var loginJob: Job? = null

    private val _model = MutableStateFlow(store.model)
    val model: StateFlow<String> = _model

    private val _recent = MutableStateFlow(store.recentDocs())
    val recent: StateFlow<List<RecentDoc>> = _recent

    private val _doc = MutableStateFlow<OpenDoc?>(null)
    val doc: StateFlow<OpenDoc?> = _doc

    private val _summary = MutableStateFlow(SummaryState())
    val summary: StateFlow<SummaryState> = _summary
    private var summaryJob: Job? = null

    init {
        if (account.value != null) refreshUsage()
    }

    fun login() {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            _loginBusy.value = true
            _loginError.value = null
            try {
                auth.login()
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

    fun openDoc(uri: Uri, name: String) {
        store.addRecent(uri, name)
        _recent.value = store.recentDocs()
        if (_doc.value?.uri != uri) {
            summaryJob?.cancel()
            _summary.value = SummaryState(markdown = store.loadSummary(uri))
        }
        _doc.value = OpenDoc(uri, name)
    }

    fun closeDoc() {
        _doc.value = null
    }

    fun removeRecent(uri: Uri) {
        store.removeRecent(uri)
        _recent.value = store.recentDocs()
    }

    fun summarize() {
        val d = _doc.value ?: return
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            val previous = _summary.value.markdown
            _summary.value = SummaryState(markdown = previous, running = true, status = "준비 중…")
            var lastUpdate = 0L
            try {
                val md = PdfSummarizer.summarize(
                    getApplication(), d.uri, codex, model.value,
                    onProgress = { s -> _summary.update { it.copy(status = s) } },
                    onPartial = { partial ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            lastUpdate = now
                            _summary.update { it.copy(markdown = partial) }
                        }
                    },
                ).trim()
                store.saveSummary(d.uri, md)
                if (_doc.value?.uri == d.uri) _summary.value = SummaryState(markdown = md)
            } catch (e: CancellationException) {
                if (_doc.value?.uri == d.uri) _summary.value = SummaryState(markdown = previous)
                throw e
            } catch (e: Exception) {
                if (_doc.value?.uri == d.uri) {
                    _summary.value = SummaryState(markdown = previous, error = e.message ?: e.toString())
                }
            }
        }
    }

    fun cancelSummary() {
        summaryJob?.cancel()
    }

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat
    private var chatJob: Job? = null

    fun sendChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _chat.value.running) return
        val history = _chat.value.messages + ChatMessage(ChatMessage.Role.USER, text)
        _chat.value = ChatState(messages = history, running = true)
        val docName = _doc.value?.name
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

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val streaming: String = "",
    val error: String? = null,
)
