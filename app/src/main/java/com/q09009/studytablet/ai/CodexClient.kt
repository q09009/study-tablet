package com.q09009.studytablet.ai

import com.q09009.studytablet.auth.CodexAccount
import com.q09009.studytablet.auth.CodexAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

data class UsageWindow(val usedPercent: Double, val windowMinutes: Long?, val resetsInSeconds: Long?)

data class CodexUsage(
    val primary: UsageWindow?,
    val secondary: UsageWindow?,
    val planType: String?,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Calls the ChatGPT-plan Codex backend (same endpoint the Codex CLI uses with ChatGPT sign-in). */
class CodexClient(private val auth: CodexAuth, private val http: OkHttpClient) {

    private val _usage = MutableStateFlow<CodexUsage?>(null)
    val usage: StateFlow<CodexUsage?> = _usage

    /** Streams a text response. [onDelta] receives each text chunk. Returns the full text. */
    suspend fun complete(
        model: String,
        instructions: String,
        userText: String,
        onDelta: (String) -> Unit = {},
    ): String = chat(model, instructions, listOf(ChatMessage(ChatMessage.Role.USER, userText)), onDelta)

    /** Multi-turn streaming completion. History is sent each time (store=false). */
    suspend fun chat(
        model: String,
        instructions: String,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val input = JSONArray()
        for (m in messages) {
            val user = m.role == ChatMessage.Role.USER
            input.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", if (user) "user" else "assistant")
                    .put("content", JSONArray().put(
                        JSONObject().put("type", if (user) "input_text" else "output_text").put("text", m.text)
                    ))
            )
        }
        var account = auth.freshAccount()
        try {
            stream(account, model, instructions, input, onDelta)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            account = auth.freshAccount(forceRefresh = true)
            stream(account, model, instructions, input, onDelta)
        }
    }

    private suspend fun stream(
        account: CodexAccount,
        model: String,
        instructions: String,
        input: JSONArray,
        onDelta: (String) -> Unit,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("tools", JSONArray())
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", false)
            .put("store", false)
            .put("stream", true)
            .put("include", JSONArray())

        val req = Request.Builder()
            .url("$BASE/codex/responses")
            .headers(authHeaders(account))
            .header("Accept", "text/event-stream")
            .header("OpenAI-Beta", "responses=experimental")
            .header("session_id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            updateUsageFromHeaders(resp.headers)
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, "Codex 요청 실패 (${resp.code}): ${resp.body?.string().orEmpty().take(500)}")
            }
            val source = resp.body!!.source()
            val out = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        out.append(delta)
                        onDelta(delta)
                    }
                    "response.failed", "error" -> {
                        val err = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
                        throw IOException("Codex 오류: ${err?.optString("message") ?: data}")
                    }
                    "response.completed" -> break
                }
            }
            return out.toString()
        }
    }

    /** Fetches plan usage (same data the Codex CLI `/status` shows). Falls back silently. */
    suspend fun refreshUsage(): CodexUsage? = withContext(Dispatchers.IO) {
        val account = runCatching { auth.freshAccount() }.getOrNull() ?: return@withContext null
        val req = Request.Builder().url("$BASE/wham/usage").headers(authHeaders(account)).get().build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body!!.string())
                val limits = json.optJSONObject("rate_limit")
                fun window(o: JSONObject?): UsageWindow? = o?.let {
                    UsageWindow(
                        usedPercent = it.optDouble("used_percent", 0.0),
                        windowMinutes = it.optLong("limit_window_seconds", -1).takeIf { s -> s > 0 }?.div(60),
                        resetsInSeconds = it.optLong("reset_after_seconds", -1).takeIf { s -> s >= 0 },
                    )
                }
                CodexUsage(
                    primary = window(limits?.optJSONObject("primary_window")),
                    secondary = window(limits?.optJSONObject("secondary_window")),
                    planType = json.optString("plan_type").ifEmpty { account.planType },
                ).also { _usage.value = it }
            }
        }.getOrNull() ?: _usage.value
    }

    private fun updateUsageFromHeaders(h: Headers) {
        fun window(prefix: String): UsageWindow? {
            val used = h["x-codex-$prefix-used-percent"]?.toDoubleOrNull() ?: return null
            return UsageWindow(
                usedPercent = used,
                windowMinutes = h["x-codex-$prefix-window-minutes"]?.toLongOrNull(),
                resetsInSeconds = h["x-codex-$prefix-reset-after-seconds"]?.toLongOrNull(),
            )
        }
        val primary = window("primary")
        val secondary = window("secondary")
        if (primary != null || secondary != null) {
            _usage.value = CodexUsage(primary, secondary, _usage.value?.planType ?: auth.account.value?.planType)
        }
    }

    private fun authHeaders(account: CodexAccount): Headers {
        val b = Headers.Builder()
            .add("Authorization", "Bearer ${account.accessToken}")
            .add("originator", CodexAuth.ORIGINATOR)
            .add("User-Agent", "codex_cli_rs/0.50.0 (Android) StudyTablet")
        account.accountId?.let { b.add("chatgpt-account-id", it) }
        return b.build()
    }

    class HttpException(val code: Int, message: String) : IOException(message)

    companion object {
        const val BASE = "https://chatgpt.com/backend-api"
    }
}
