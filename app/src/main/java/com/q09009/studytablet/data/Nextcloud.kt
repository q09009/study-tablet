package com.q09009.studytablet.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import java.text.SimpleDateFormat
import java.util.Locale

data class NcAccount(val server: String, val loginName: String, val appPassword: String, val userId: String)

data class CloudEntry(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Nextcloud access: Login Flow v2 for an app password, WebDAV for listing, downloading and uploading. */
class Nextcloud(context: Context, private val http: OkHttpClient) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("nextcloud", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(load())
    val account: StateFlow<NcAccount?> = _account

    var lastServer: String
        get() = prefs.getString("last_server", "") ?: ""
        private set(v) = prefs.edit().putString("last_server", v).apply()

    private fun load(): NcAccount? {
        val server = prefs.getString("server", null) ?: return null
        return NcAccount(
            server,
            prefs.getString("login", null) ?: return null,
            prefs.getString("password", null) ?: return null,
            prefs.getString("user", null) ?: return null,
        )
    }

    /** Opens the browser for the user to approve access, then polls until Nextcloud hands out an app password. */
    suspend fun login(serverInput: String): NcAccount = withContext(Dispatchers.IO) {
        val typed = serverInput.trim()
        val explicit = typed.startsWith("http://", true) || typed.startsWith("https://", true)
        var server = normalize(serverInput)
        val start = try {
            startLogin(server)
        } catch (e: SSLException) {
            // No scheme typed: home servers often serve plain HTTP on a custom port, so retry without TLS.
            if (explicit) throw tlsError(server, e)
            server = "http://" + server.removePrefix("https://")
            try { startLogin(server) } catch (e2: IOException) { throw tlsError("https://" + server.removePrefix("http://"), e) }
        }
        lastServer = server
        val poll = start.getJSONObject("poll")
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(onServer(start.getString("login"), server))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        val result = withTimeout(20 * 60 * 1000L) {
            var done: JSONObject? = null
            while (done == null) {
                delay(2000)
                val body = FormBody.Builder().add("token", poll.getString("token")).build()
                done = http.newCall(Request.Builder().url(onServer(poll.getString("endpoint"), server)).post(body).build())
                    .execute().use { r -> if (r.code == 200) JSONObject(r.body!!.string()) else null }
            }
            done
        }

        // Keep the address that actually worked: Nextcloud may report an internal/proxy host (e.g. behind Tailscale MagicDNS).
        val base = server
        val login = result.getString("loginName")
        val password = result.getString("appPassword")
        val userId = fetchUserId(base, login, password) ?: login
        val acc = NcAccount(base, login, password, userId)
        prefs.edit().putString("server", base).putString("login", login)
            .putString("password", password).putString("user", userId).apply()
        _account.value = acc
        acc
    }

    /** Points a server-issued Login Flow URL at the address the user reached, since the reported host may not resolve here. */
    private fun onServer(url: String, server: String): String {
        val i = url.indexOf("/login/v2")
        return if (i < 0) url else "$server/index.php" + url.substring(i)
    }

    private fun startLogin(server: String): JSONObject = http.newCall(
        Request.Builder().url("$server/index.php/login/v2")
            .header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody()).build()
    ).execute().use { r ->
        if (r.code == 400) throw IOException("Nextcloud가 이 주소를 신뢰하지 않습니다 (HTTP 400). 서버 config.php의 trusted_domains에 이 주소를 추가하세요.")
        if (!r.isSuccessful) throw IOException("Nextcloud 서버에 연결할 수 없습니다 (HTTP ${r.code}). 주소를 확인하세요.")
        val body = r.body!!.string()
        runCatching { JSONObject(body) }.getOrElse { throw IOException("Nextcloud 응답이 아닙니다. 주소와 포트를 확인하세요.") }
    }

    private fun tlsError(server: String, e: SSLException): IOException = IOException(
        if (e is SSLHandshakeException) "HTTPS 인증서를 신뢰할 수 없습니다 ($server). 자체 서명 인증서라면 http:// 주소를 쓰거나 정식 인증서를 설정하세요."
        else "HTTPS로 연결할 수 없습니다 ($server). 서버가 HTTP만 쓴다면 주소 앞에 http://를 붙이세요."
    )

    private fun fetchUserId(server: String, login: String, password: String): String? = runCatching {
        http.newCall(
            Request.Builder().url("$server/ocs/v2.php/cloud/user?format=json")
                .header("Authorization", Credentials.basic(login, password))
                .header("OCS-APIRequest", "true").build()
        ).execute().use { r ->
            if (!r.isSuccessful) null
            else JSONObject(r.body!!.string()).getJSONObject("ocs").getJSONObject("data").getString("id")
        }
    }.getOrNull()

    /** Forgets the account and revokes the app password on the server (best effort). */
    suspend fun logout() {
        val acc = _account.value
        prefs.edit().remove("server").remove("login").remove("password").remove("user").apply()
        _account.value = null
        if (acc != null) withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(
                    Request.Builder().url("${acc.server}/ocs/v2.php/core/apppassword")
                        .header("Authorization", auth(acc)).header("OCS-APIRequest", "true")
                        .delete().build()
                ).execute().close()
            }
        }
    }

    suspend fun list(path: String): List<CloudEntry> = withContext(Dispatchers.IO) {
        val acc = requireAccount()
        val req = Request.Builder().url(davUrl(acc, path))
            .header("Authorization", auth(acc))
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("Nextcloud 로그인이 만료되었습니다. 다시 로그인하세요.")
            if (!r.isSuccessful) throw IOException("폴더를 불러올 수 없습니다 (HTTP ${r.code}).")
            val self = normalizePath(path)
            parse(r.body!!.string())
                .filter { it.path != self }
                .sortedWith(compareBy<CloudEntry>({ !it.isDir }, { it.name.lowercase() }))
        }
    }

    suspend fun download(path: String, out: OutputStream, onProgress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        val acc = requireAccount()
        http.newCall(Request.Builder().url(davUrl(acc, path)).header("Authorization", auth(acc)).build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("다운로드 실패 (HTTP ${r.code}).")
                val body = r.body!!
                val total = body.contentLength()
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else null)
                    }
                }
            }
    }

    /** Creates a remote folder; an already existing folder is fine. */
    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val acc = requireAccount()
        http.newCall(Request.Builder().url(davUrl(acc, path)).header("Authorization", auth(acc)).method("MKCOL", null).build())
            .execute().use { r ->
                if (r.code == 401) throw IOException("Nextcloud 로그인이 만료되었습니다. 다시 로그인하세요.")
                if (!r.isSuccessful && r.code != 405) throw IOException("폴더를 만들 수 없습니다 (HTTP ${r.code}).")
            }
    }

    /** Streams a file to [path] with WebDAV PUT. */
    suspend fun upload(path: String, size: Long, mime: String, open: () -> InputStream, onProgress: (Float?) -> Unit) =
        withContext(Dispatchers.IO) {
            val acc = requireAccount()
            val body = object : RequestBody() {
                override fun contentType(): MediaType = mime.ifEmpty { "application/octet-stream" }.toMediaType()
                override fun contentLength() = if (size > 0) size else -1L
                override fun writeTo(sink: BufferedSink) {
                    open().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            done += n
                            onProgress(if (size > 0) done.toFloat() / size else null)
                        }
                    }
                }
            }
            http.newCall(Request.Builder().url(davUrl(acc, path)).header("Authorization", auth(acc)).put(body).build())
                .execute().use { r ->
                    when {
                        r.code == 401 -> throw IOException("Nextcloud 로그인이 만료되었습니다. 다시 로그인하세요.")
                        r.code == 507 -> throw IOException("Nextcloud 저장 공간이 부족합니다.")
                        !r.isSuccessful -> throw IOException("올리기 실패 (HTTP ${r.code}).")
                    }
                }
        }

    private fun requireAccount() = _account.value ?: throw IOException("Nextcloud에 로그인하세요.")

    private fun auth(acc: NcAccount) = Credentials.basic(acc.loginName, acc.appPassword)

    private fun davUrl(acc: NcAccount, path: String): String {
        val segs = normalizePath(path).split('/').filter { it.isNotEmpty() }
        return "${acc.server}/remote.php/dav/files/${Uri.encode(acc.userId)}" +
            segs.joinToString("") { "/" + Uri.encode(it) }
    }

    private fun parse(xml: String): List<CloudEntry> {
        val out = mutableListOf<CloudEntry>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        var href: String? = null
        var isDir = false
        var size = 0L
        var modified = 0L
        var text = StringBuilder()
        val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = null; isDir = false; size = 0; modified = 0 }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0
                    "getlastmodified" -> modified = runCatching { httpDate.parse(text.toString().trim())?.time }.getOrNull() ?: 0
                    "response" -> href?.let { h -> entry(h, isDir, size, modified)?.let(out::add) }
                }
            }
        }
        return out
    }

    private fun entry(href: String, isDir: Boolean, size: Long, modified: Long): CloudEntry? {
        val decoded = Uri.decode(Uri.parse(href).path ?: href)
        val i = decoded.indexOf(DAV_MARKER)
        if (i < 0) return null
        // "<user>/a/b" -> "/a/b"
        val rest = decoded.substring(i + DAV_MARKER.length).substringAfter('/', "")
        val path = normalizePath(rest)
        val name = path.substringAfterLast('/')
        return CloudEntry(path, name, isDir, size, modified)
    }

    companion object {
        private const val USER_AGENT = "Study Tablet (Android)"
        private const val DAV_MARKER = "/remote.php/dav/files/"
        private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        fun normalize(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s.removeSuffix("/index.php")
        }

        fun normalizePath(path: String): String = "/" + path.trim('/')
    }
}
