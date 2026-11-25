package com.q09009.studytablet.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CodexAccount(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val accountId: String?,
    val email: String?,
    val planType: String?,
    val expiresAtMs: Long,
)

/**
 * "Sign in with ChatGPT" flow used by the OpenAI Codex CLI (OAuth 2.0 + PKCE).
 * The registered redirect is http://localhost:1455/auth/callback, so the app runs a tiny
 * loopback HTTP server on that port while the browser completes the login.
 */
class CodexAuth(context: Context, private val http: OkHttpClient) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("codex_auth", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()

    private val _account = MutableStateFlow(load())
    val account: StateFlow<CodexAccount?> = _account

    suspend fun login(): CodexAccount = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(32)

        val authUrl = Uri.parse("$ISSUER/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "openid profile email offline_access")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("originator", ORIGINATOR)
            .build()

        val code = withTimeout(10 * 60 * 1000L) {
            awaitCallback(state) {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, authUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val json = postToken(Request.Builder().url("$ISSUER/oauth/token").post(body).build())
        val acc = parseTokens(json, previousRefresh = null)
        save(acc)
        acc
    }

    fun logout() {
        prefs.edit().clear().apply()
        _account.value = null
    }

    /** Returns an account whose access token is valid for at least a few minutes. */
    suspend fun freshAccount(forceRefresh: Boolean = false): CodexAccount = refreshLock.withLock {
        val current = _account.value ?: throw IllegalStateException("ChatGPT 로그인이 필요합니다.")
        if (!forceRefresh && current.expiresAtMs - System.currentTimeMillis() > 5 * 60 * 1000) {
            return current
        }
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("client_id", CLIENT_ID)
                .put("grant_type", "refresh_token")
                .put("refresh_token", current.refreshToken)
                .put("scope", "openid profile email")
                .toString()
            val req = Request.Builder().url("$ISSUER/oauth/token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val json = try {
                postToken(req)
            } catch (e: AuthException) {
                if (e.code == 400 || e.code == 401) logout()
                throw e
            }
            val acc = parseTokens(json, previousRefresh = current, )
            save(acc)
            acc
        }
    }

    private fun postToken(req: Request): JSONObject {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AuthException(resp.code, "토큰 요청 실패 (${resp.code}): $text")
            return JSONObject(text)
        }
    }

    private fun parseTokens(json: JSONObject, previousRefresh: CodexAccount?): CodexAccount {
        val access = json.getString("access_token")
        val idToken = json.optString("id_token").ifEmpty { previousRefresh?.idToken.orEmpty() }
        val refresh = json.optString("refresh_token").ifEmpty { previousRefresh?.refreshToken.orEmpty() }
        val idClaims = decodeJwt(idToken)
        val authClaims = idClaims?.optJSONObject("https://api.openai.com/auth")
            ?: decodeJwt(access)?.optJSONObject("https://api.openai.com/auth")
        val accessExp = decodeJwt(access)?.optLong("exp", 0L) ?: 0L
        val expiresAt = when {
            accessExp > 0 -> accessExp * 1000
            json.has("expires_in") -> System.currentTimeMillis() + json.getLong("expires_in") * 1000
            else -> System.currentTimeMillis() + 60 * 60 * 1000
        }
        return CodexAccount(
            accessToken = access,
            refreshToken = refresh,
            idToken = idToken,
            accountId = authClaims?.optString("chatgpt_account_id")?.ifEmpty { null },
            email = idClaims?.optString("email")?.ifEmpty { null },
            planType = authClaims?.optString("chatgpt_plan_type")?.ifEmpty { null },
            expiresAtMs = expiresAt,
        )
    }

    private fun save(acc: CodexAccount) {
        prefs.edit()
            .putString("access", acc.accessToken)
            .putString("refresh", acc.refreshToken)
            .putString("id", acc.idToken)
            .putString("account_id", acc.accountId)
            .putString("email", acc.email)
            .putString("plan", acc.planType)
            .putLong("exp", acc.expiresAtMs)
            .apply()
        _account.value = acc
    }

    private fun load(): CodexAccount? {
        val access = prefs.getString("access", null) ?: return null
        return CodexAccount(
            accessToken = access,
            refreshToken = prefs.getString("refresh", "")!!,
            idToken = prefs.getString("id", "")!!,
            accountId = prefs.getString("account_id", null),
            email = prefs.getString("email", null),
            planType = prefs.getString("plan", null),
            expiresAtMs = prefs.getLong("exp", 0L),
        )
    }

    /** Starts the loopback server, runs [onReady], and suspends until the redirect arrives. */
    private suspend fun awaitCallback(expectedState: String, onReady: () -> Unit): String =
        suspendCancellableCoroutine { cont ->
            val server = try {
                ServerSocket(PORT).apply { reuseAddress = true }
            } catch (e: IOException) {
                cont.resumeWithException(IOException("포트 $PORT 를 열 수 없습니다: ${e.message}"))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { server.close() } }
            thread(name = "codex-oauth-callback", isDaemon = true) {
                try {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            val requestLine = reader.readLine().orEmpty()
                            val path = requestLine.split(" ").getOrNull(1).orEmpty()
                            val uri = Uri.parse("http://localhost$path")
                            val out = s.getOutputStream()
                            if (uri.path != "/auth/callback") {
                                out.write(httpResponse(404, "Not found").toByteArray())
                                return@use
                            }
                            val error = uri.getQueryParameter("error")
                            val code = uri.getQueryParameter("code")
                            val state = uri.getQueryParameter("state")
                            when {
                                error != null -> {
                                    out.write(httpResponse(400, page("로그인 실패: $error")).toByteArray())
                                    server.close()
                                    if (cont.isActive) cont.resumeWithException(IOException("로그인 실패: $error"))
                                }
                                state != expectedState || code == null -> {
                                    out.write(httpResponse(400, page("잘못된 요청입니다.")).toByteArray())
                                }
                                else -> {
                                    out.write(httpResponse(200, page("로그인 완료! 앱으로 돌아가세요.")).toByteArray())
                                    out.flush()
                                    server.close()
                                    if (cont.isActive) cont.resume(code)
                                }
                            }
                        }
                    }
                } catch (e: SocketException) {
                    if (cont.isActive) cont.resumeWithException(e)
                } catch (e: Exception) {
                    runCatching { server.close() }
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            onReady()
        }

    private fun httpResponse(status: Int, html: String): String {
        val bytes = html.toByteArray()
        return "HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$html"
    }

    private fun page(message: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Study Tablet</title></head>
        <body style="font-family:sans-serif;text-align:center;padding-top:20vh">
        <h2>$message</h2>
        <p><a href="intent://auth#Intent;scheme=studytablet;package=${appContext.packageName};end"
              style="display:inline-block;padding:14px 28px;background:#3F51B5;color:#fff;border-radius:8px;text-decoration:none">
              앱으로 돌아가기</a></p>
        </body></html>
    """.trimIndent()

    class AuthException(val code: Int, message: String) : IOException(message)

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val PORT = 1455
        const val REDIRECT_URI = "http://localhost:$PORT/auth/callback"
        const val ORIGINATOR = "codex_cli_rs"

        private val random = SecureRandom()

        private fun randomUrlSafe(bytes: Int): String =
            base64Url(ByteArray(bytes).also { random.nextBytes(it) })

        private fun base64Url(data: ByteArray): String =
            Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun decodeJwt(token: String?): JSONObject? {
            val part = token?.split(".")?.getOrNull(1) ?: return null
            return runCatching {
                JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            }.getOrNull()
        }
    }
}
