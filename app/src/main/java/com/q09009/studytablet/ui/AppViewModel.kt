package com.q09009.studytablet.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.q09009.studytablet.ai.OpenAIClient
import com.q09009.studytablet.ai.PdfSummarizer
import com.q09009.studytablet.data.ApiKeyStore
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
    val keys = ApiKeyStore(app)
    val openai = OpenAIClient(app, http) { keys.key.value }

    val apiKey = keys.key
    val usage = openai.usage

    private val _keyBusy = MutableStateFlow(false)
    val keyBusy: StateFlow<Boolean> = _keyBusy
    private val _keyError = MutableStateFlow<String?>(null)
    val keyError: StateFlow<String?> = _keyError

    private val _model = MutableStateFlow(store.model)
    val model: StateFlow<String> = _model

    private val _recent = MutableStateFlow(store.recentDocs())
    val recent: StateFlow<List<RecentDoc>> = _recent

    private val _doc = MutableStateFlow<OpenDoc?>(null)
    val doc: StateFlow<OpenDoc?> = _doc

    private val _summary = MutableStateFlow(SummaryState())
    val summary: StateFlow<SummaryState> = _summary
    private var summaryJob: Job? = null

    /** Verifies the key with the API before saving it. */
    fun saveApiKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty() || _keyBusy.value) return
        viewModelScope.launch {
            _keyBusy.value = true
            _keyError.value = null
            try {
                openai.verifyKey(key)
                keys.save(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _keyError.value = e.message ?: e.toString()
            } finally {
                _keyBusy.value = false
            }
        }
    }

    fun removeApiKey() = keys.clear()

    fun resetUsage() = openai.resetUsage()
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
                    getApplication(), d.uri, openai, model.value,
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
}
