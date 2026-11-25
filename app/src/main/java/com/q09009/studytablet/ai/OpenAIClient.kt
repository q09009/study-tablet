package com.q09009.studytablet.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/** Token usage counted locally from API responses (OpenAI reports it per response). */
data class TokenUsage(
    val todayInput: Long = 0,
    val todayOutput: Long = 0,
    val monthInput: Long = 0,
    val monthOutput: Long = 0,
    val requests: Long = 0,
)

/** Official OpenAI Responses API client (https://platform.openai.com/docs/api-reference/responses). */
class OpenAIClient(context: Context, private val http: OkHttpClient, private val apiKey: () -> String?) {
    private val prefs = context.applicationContext.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val _usage = MutableStateFlow(loadUsage())
    val usage: StateFlow<TokenUsage> = _usage

    /** Checks that the key works by listing models. */
    suspend fun verifyKey(key: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/models").header("Authorization", "Bearer $key").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(errorMessage(resp.code, resp.body?.string()))
        }
    }

    /** Streams a text response. [onDelta] receives each text chunk. Returns the full text. */
    suspend fun complete(
        model: String,
        instructions: String,
        userText: String,
        onDelta: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw IllegalStateException("OpenAI API 키를 먼저 등록하세요.")
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", userText)
            .put("store", false)
            .put("stream", true)

        val req = Request.Builder()
            .url("$BASE/responses")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(errorMessage(resp.code, resp.body?.string()))
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
                    "response.completed", "response.incomplete" -> {
                        event.optJSONObject("response")?.optJSONObject("usage")?.let(::recordUsage)
                        break
                    }
                    "response.failed", "error" -> {
                        val err = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
                        throw IOException("OpenAI 오류: ${err?.optString("message") ?: data}")
                    }
                }
            }
            out.toString()
        }
    }

    fun resetUsage() {
        prefs.edit().clear().apply()
        _usage.value = TokenUsage()
    }

    @Synchronized
    private fun recordUsage(u: JSONObject) {
        val input = u.optLong("input_tokens")
        val output = u.optLong("output_tokens")
        val cur = loadUsage()
        val today = LocalDate.now()
        prefs.edit()
            .putString("day", today.toString())
            .putString("month", today.toString().take(7))
            .putLong("todayIn", cur.todayInput + input)
            .putLong("todayOut", cur.todayOutput + output)
            .putLong("monthIn", cur.monthInput + input)
            .putLong("monthOut", cur.monthOutput + output)
            .putLong("requests", cur.requests + 1)
            .apply()
        _usage.value = loadUsage()
    }

    private fun loadUsage(): TokenUsage {
        val today = LocalDate.now().toString()
        val sameDay = prefs.getString("day", null) == today
        val sameMonth = prefs.getString("month", null) == today.take(7)
        return TokenUsage(
            todayInput = if (sameDay) prefs.getLong("todayIn", 0) else 0,
            todayOutput = if (sameDay) prefs.getLong("todayOut", 0) else 0,
            monthInput = if (sameMonth) prefs.getLong("monthIn", 0) else 0,
            monthOutput = if (sameMonth) prefs.getLong("monthOut", 0) else 0,
            requests = prefs.getLong("requests", 0),
        )
    }

    private fun errorMessage(code: Int, body: String?): String {
        val msg = runCatching { JSONObject(body!!).getJSONObject("error").getString("message") }.getOrNull()
        val hint = when (code) {
            401 -> "API 키가 올바르지 않습니다."
            429 -> "사용 한도 초과 또는 크레딧 부족입니다 (platform.openai.com에서 결제/한도 확인)."
            404 -> "모델을 찾을 수 없습니다. 모델 이름을 확인하세요."
            else -> "요청 실패"
        }
        return "$hint ($code)" + (msg?.let { "\n$it" } ?: "")
    }

    companion object {
        const val BASE = "https://api.openai.com/v1"
    }
}
